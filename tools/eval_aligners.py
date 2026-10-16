"""Compara alineadores forzados de audio.cpp (MMS y Qwen3) sobre audio de Supertonic.

Requisitos (descargados en .models/, fuera de git):
  - .models/audiocpp/bin/audiocpp_cli.exe      (audio.cpp v0.9.2, Windows x64 CPU)
  - .models/aligners/MMS-Forced-Aligner-GGUF/mms-forced-aligner-f16.gguf        (CC-BY-NC-4.0)
  - .models/aligners/Qwen3-ForcedAligner-0.6B-GGUF/qwen3-forced-aligner-0.6b-q8_0.gguf (Apache-2.0)
  - .models/align-corpus/  (./gradlew :engine:supertonic:alignCorpus)
  - ffmpeg en el PATH; python -m pip install psutil

Uso:  python tools/eval_aligners.py [--threads 4] [--only mms|qwen3]
Salida: tabla por consola + .models/align-corpus/results.json
"""
import argparse
import json
import statistics
import subprocess
import sys
import time
import unicodedata
from pathlib import Path

import psutil

ROOT = Path(__file__).resolve().parents[1]
M = ROOT / ".models"
CLI = M / "audiocpp" / "bin" / "audiocpp_cli.exe"
CORPUS = M / "align-corpus"
TRIM_MARGIN = 0.040  # los clips de palabra de la referencia incluyen 40 ms de margen a cada lado

ALIGNERS = {
    "mms": {
        "family": "mms_forced_aligner",
        "model": M / "aligners" / "MMS-Forced-Aligner-GGUF" / "mms-forced-aligner-f16.gguf",
        "lang": {"en": "eng", "fr": "fra"},
    },
    "qwen3": {
        "family": "qwen3_forced_aligner",
        "model": M / "aligners" / "Qwen3-ForcedAligner-0.6B-GGUF" / "qwen3-forced-aligner-0.6b-q8_0.gguf",
        "lang": {"en": "English", "fr": "French"},
    },
}


def romanize(text: str) -> str:
    """MMS usa un alfabeto latino sin diacríticos: NFKD + quitar marcas combinantes."""
    return "".join(c for c in unicodedata.normalize("NFKD", text) if not unicodedata.combining(c))


def to16k(src: Path) -> Path:
    dst = CORPUS / "16k" / src.relative_to(CORPUS)
    dst.parent.mkdir(parents=True, exist_ok=True)
    if not dst.exists():
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-i", str(src), "-ac", "1", "-ar", "16000", str(dst)], check=True)
    return dst


def wav_seconds(p: Path) -> float:
    import wave
    with wave.open(str(p)) as w:
        return w.getnframes() / w.getframerate()


def run(aligner: str, item: dict, threads: int):
    cfg = ALIGNERS[aligner]
    audio = to16k(CORPUS / item["wav"])
    out = CORPUS / "out" / aligner / (item["id"] + ".json")
    out.parent.mkdir(parents=True, exist_ok=True)
    text = item["text"]
    cmd = [str(CLI), "--task", "align", "--family", cfg["family"], "--model", str(cfg["model"]),
           "--backend", "cpu", "--audio", str(audio), "--language", cfg["lang"][item["lang"]],
           "--words-out", str(out), "--metrics"]
    if threads:
        cmd += ["--threads", str(threads)]
    if aligner == "mms" and item["lang"] != "en":
        text = romanize(text)
        cmd += ["--request-option", "text_normalization=pre_romanized"]
    cmd += ["--text", text]
    t0 = time.perf_counter()
    proc = psutil.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, encoding="utf-8", errors="replace")
    peak = 0
    while proc.poll() is None:
        try:
            peak = max(peak, proc.memory_info().peak_wset if hasattr(proc.memory_info(), "peak_wset") else proc.memory_info().rss)
        except psutil.Error:
            pass
        time.sleep(0.02)
    stdout, stderr = proc.communicate()
    wall = time.perf_counter() - t0
    if proc.returncode != 0 or not out.exists():
        return {"ok": False, "error": (stderr or stdout)[-600:], "wall": wall}
    words = parse_words(json.loads(out.read_text(encoding="utf-8")))
    infer_ms = next((float(l.split("=")[1]) for l in stdout.splitlines() if l.startswith("metrics.wall_ms=")), None)
    return {"ok": True, "wall": wall, "infer_s": (infer_ms or 0) / 1000, "peak_mb": peak / 1e6, "words": words}


def parse_words(data):
    """Acepta varias formas de JSON (lista o {words:[...]}) y tiempos en s o en muestras."""
    items = data.get("words", data.get("word_timestamps", data)) if isinstance(data, dict) else data
    words = []
    for w in items:
        word = w.get("word") or w.get("text") or w.get("token")
        if "start" in w:
            s, e = float(w["start"]), float(w["end"])
        elif "start_sample" in w:
            sr = float(w.get("sample_rate", 16000))
            s, e = w["start_sample"] / sr, w["end_sample"] / sr
        else:
            s, e = float(w.get("start_time", 0)), float(w.get("end_time", 0))
        words.append({"word": word, "start": s, "end": e, "conf": w.get("confidence", w.get("score"))})
    return words


def norm(w: str) -> str:
    return romanize(w).lower().strip(".,;:!?«»\"'")


def evaluate(results, corpus):
    by_id = {c["id"]: c for c in corpus}
    report = {}
    for name, rows in results.items():
        ok = [r for r in rows if r["ok"]]
        start_err, end_err, coverage, monotonic = [], [], [], []
        rtf, rtf_infer = [], []
        for r in ok:
            item = by_id[r["id"]]
            dur = wav_seconds(CORPUS / item["wav"])
            rtf.append(r["wall"] / dur)
            rtf_infer.append(r["infer_s"] / dur)
            got = [w for w in r["words"] if norm(w["word"])]
            coverage.append(len(got) / max(1, len(item["words"])))
            monotonic.append(all(b["start"] >= a["start"] for a, b in zip(got, got[1:])))
            if item["kind"] == "reference" and len(got) == len(item["truth"]):
                for g, t in zip(got, item["truth"]):
                    start_err.append(abs(g["start"] - (t["start"] + TRIM_MARGIN)))
                    end_err.append(abs(g["end"] - (t["end"] - TRIM_MARGIN)))
        report[name] = {
            "runs_ok": f"{len(ok)}/{len(rows)}",
            "wall_s_median": round(statistics.median([r["wall"] for r in ok]), 2) if ok else None,
            "rtf_process_median": round(statistics.median(rtf), 2) if rtf else None,
            "rtf_inference_median": round(statistics.median(rtf_infer), 2) if rtf_infer else None,
            "load_s_median": round(statistics.median([r["wall"] - r["infer_s"] for r in ok]), 2) if ok else None,
            "peak_mb_max": round(max(r["peak_mb"] for r in ok)) if ok else None,
            "coverage_mean": round(statistics.mean(coverage), 3) if coverage else None,
            "monotonic_all": all(monotonic) if monotonic else None,
            "ref_start_err_ms_mean": round(1000 * statistics.mean(start_err)) if start_err else None,
            "ref_start_err_ms_p90": round(1000 * sorted(start_err)[int(0.9 * (len(start_err) - 1))]) if start_err else None,
            "ref_end_err_ms_mean": round(1000 * statistics.mean(end_err)) if end_err else None,
            "ref_words_within_50ms": round(sum(e <= 0.05 for e in start_err) / len(start_err), 3) if start_err else None,
        }
    # Acuerdo entre aligners en frases naturales (misma tokenización).
    if len(results) == 2:
        a, b = results.values()
        diffs = []
        for ra, rb in zip(a, b):
            if ra["ok"] and rb["ok"] and by_id[ra["id"]]["kind"] == "natural":
                ga = [w for w in ra["words"] if norm(w["word"])]
                gb = [w for w in rb["words"] if norm(w["word"])]
                if len(ga) == len(gb):
                    diffs += [abs(x["start"] - y["start"]) for x, y in zip(ga, gb)]
        report["agreement_natural_start_ms_mean"] = round(1000 * statistics.mean(diffs)) if diffs else None
        report["agreement_natural_start_ms_p90"] = round(1000 * sorted(diffs)[int(0.9 * (len(diffs) - 1))]) if diffs else None
    return report


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--threads", type=int, default=12)
    ap.add_argument("--only", choices=list(ALIGNERS))
    ap.add_argument("--limit", type=int, default=0)
    args = ap.parse_args()
    corpus = json.loads((CORPUS / "corpus.json").read_text(encoding="utf-8"))
    if args.limit:
        corpus = corpus[: args.limit] + [c for c in corpus if c["kind"] == "reference"]
    results = {}
    for name in ([args.only] if args.only else list(ALIGNERS)):
        rows = []
        for item in corpus:
            r = run(name, item, args.threads)
            r["id"] = item["id"]
            rows.append(r)
            status = f"inf {r.get('infer_s', 0):.2f}s total {r['wall']:.2f}s {r.get('peak_mb', 0):.0f}MB {len(r.get('words', []))} palabras" if r["ok"] else "ERROR " + r["error"][-200:]
            print(f"[{name}] {item['id']:28s} {status}", flush=True)
        results[name] = rows
    report = evaluate(results, corpus)
    (CORPUS / "results.json").write_text(json.dumps({"report": report, "results": results}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    sys.exit(main())
