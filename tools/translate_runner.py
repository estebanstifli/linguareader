"""Orquestador: traduce uno tras otro los libros del catálogo con la skill /graded-translate.

Cada traducción se hace en una sesión nueva de `claude -p` (historial limpio por libro), con la misma
vigilancia de tokens que tools/book_runner.py: si el consumo es anormal, la sesión se corta y el
orquestador se detiene sin pasar al siguiente libro.

Uso:
  python -I tools/translate_runner.py --lang es              # todos los pendientes al español
  python -I tools/translate_runner.py --lang es,fr --level A1 --max-books 4
  python -I tools/translate_runner.py --lang fr --title "Puss in Boots"
  python -I tools/translate_runner.py --lang es --dry-run    # plan y límites, sin ejecutar

Pendiente = libro del catálogo en inglés sin fila en la tabla «Traducciones» para ese idioma. Los
originales íntegros de B2 (Standard Ebooks) se excluyen salvo --include-originals: traducirlos es una
traducción literaria completa de 25.000-50.000 palabras, no de una adaptación propia.

Detención (código de salida 1) si en una traducción:
  - salida, entrada acumulada, contexto de una llamada o llamadas superan el límite;
  - la sesión acaba con error;
  - no aparece la fila en «Traducciones», falta el fichero o tools/translation_check.py no da OK
    (se ejecuta aquí, independiente de lo que diga la sesión).
Límites por nivel en LIMITS; tras dos traducciones del mismo nivel e idioma, `--factor` × la mediana
si es menor. Registro: .claude/translate-runs.jsonl.
"""
import argparse
import os
import re
import statistics
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from book_runner import README, ROOT, finish, run_session  # noqa: E402

LOG = os.path.join(ROOT, ".claude", "translate-runs.jsonl")

# Límites duros por traducción. Referencia medida: A1 (The Brave Little Tailor → fr/es) ≈ 7.000
# tokens de salida por libro escrito; leer el original, escribir, verificar y retocar.
LIMITS = {
    "A1": {"output": 40_000, "input": 1_500_000, "context": 120_000, "calls": 25},
    "A2": {"output": 55_000, "input": 2_000_000, "context": 140_000, "calls": 30},
    "B1": {"output": 130_000, "input": 5_000_000, "context": 200_000, "calls": 50},
    "B2": {"output": 200_000, "input": 8_000_000, "context": 240_000, "calls": 70},
}
LANG_NAMES = {"es": "español", "fr": "francés", "de": "alemán", "it": "italiano", "pt": "portugués"}

PROMPT = (
    "/graded-translate {source} {lang}\n\n"
    "Traduce `content/graded-books/{source}` ({title}, {level}) al {lang_name}. "
    "Ejecución desatendida desde tools/translate_runner.py: no preguntes nada, no hagas commit y no "
    "toques otros libros ni otras traducciones. PYTHONIOENCODING=utf-8 ya está definido en el "
    "entorno: no lo antepongas a los comandos. Al terminar responde solo con una línea: "
    "`RESULTADO: ok` o `RESULTADO: fallo <motivo>`."
)

SOURCE_ROW = re.compile(r"^\| `(en/(A1|A2|B1|B2)/[^`]+)` \| ([^|]+?) \| (?:A1|A2|B1|B2) \|.*\| ([^|]+) \|$", re.M)
TRANSLATION_ROW = re.compile(
    r"^\| `(([a-z]{2})/(?:A1|A2|B1|B2)/[^`]+)` \|[^\n]*\| `(en/[^`]+)` \|", re.M)


def catalogue():
    text = open(README, encoding="utf-8").read()
    sources = [{"source": m[1], "level": m[2], "title": m[3].strip(),
                "original": "Standard Ebooks" in m[4]} for m in SOURCE_ROW.finditer(text)]
    done = {(m[2], m[3]): m[1] for m in TRANSLATION_ROW.finditer(text)}
    return sources, done


def history():
    if not os.path.exists(LOG):
        return []
    import json
    with open(LOG, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def limits_for(job, factor):
    base = dict(LIMITS[job["level"]])
    done = [r for r in history() if r["level"] == job["level"] and r["lang"] == job["lang"]
            and r["status"] == "ok"]
    if len(done) >= 2:
        for key in ("output", "input", "calls"):
            adaptive = int(factor * statistics.median(r[key] for r in done))
            base[key] = min(base[key], max(adaptive, 1))
    return base


def verify(job):
    """La traducción cuenta como hecha si tiene fila en «Traducciones», existe y pasa el checker."""
    _, done = catalogue()
    target = done.get((job["lang"], job["source"]))
    if not target:
        return False, "no hay fila en la tabla «Traducciones» del README"
    path = os.path.join(ROOT, "content", "graded-books", target)
    if not os.path.exists(path):
        return False, f"falta el fichero {target}"
    check = subprocess.run(
        [sys.executable, "-I", os.path.join(ROOT, "tools", "translation_check.py"),
         os.path.join(ROOT, "content", "graded-books", job["source"]), path, "--lang", job["lang"]],
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        env=dict(os.environ, PYTHONIOENCODING="utf-8"))
    if check.returncode != 0:
        last = [l for l in check.stdout.splitlines() if "ERROR" in l or "FUERA" in l or "TIPOGRAF" in l]
        return False, "translation_check no da OK: " + "; ".join(last[:3])
    return True, target


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--lang", required=True, help="idioma(s) destino separados por comas: es,fr")
    ap.add_argument("--level", choices=["A1", "A2", "B1", "B2"])
    ap.add_argument("--title", help="solo este libro (título inglés del catálogo)")
    ap.add_argument("--max-books", type=int, default=0, help="0 = todos")
    ap.add_argument("--include-originals", action="store_true", help="incluir originales íntegros de B2")
    ap.add_argument("--factor", type=float, default=2.5, help="límite adaptativo = factor × mediana")
    ap.add_argument("--budget-usd", type=float, help="tope de gasto por libro (--max-budget-usd)")
    ap.add_argument("--model", help="modelo para las sesiones (por defecto, el configurado)")
    ap.add_argument("--permission-mode", default="acceptEdits")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    langs = [l.strip() for l in args.lang.split(",") if l.strip()]
    unknown = [l for l in langs if l not in LANG_NAMES]
    if unknown:
        sys.exit(f"Idioma no soportado por translation_check.py: {unknown} (válidos: {', '.join(LANG_NAMES)})")

    sources, done = catalogue()
    jobs = [dict(s, lang=l, lang_name=LANG_NAMES[l]) for l in langs for s in sources
            if (l, s["source"]) not in done
            and (args.include_originals or not s["original"])
            and (not args.level or s["level"] == args.level)
            and (not args.title or s["title"].lower() == args.title.lower())]
    if args.max_books:
        jobs = jobs[: args.max_books]
    if not jobs:
        print("No hay traducciones pendientes que coincidan.")
        return 0

    for i, job in enumerate(jobs, 1):
        lim = limits_for(job, args.factor)
        print(f"[{i}/{len(jobs)}] {job['title']} ({job['level']}) -> {job['lang']}  "
              f"límites: salida {lim['output']:,}, entrada {lim['input']:,}, contexto {lim['context']:,}, "
              f"llamadas {lim['calls']}")
        if args.dry_run:
            continue
        base = {k: job[k] for k in ("source", "title", "level", "lang")}
        record, reason = run_session(PROMPT.format(**job), lim, args, base)
        ok, detail = verify(job)
        if ok:
            record["target"] = detail
        rec = finish(record, reason, ok, f"la traducción no quedó verificada: {detail}", LOG)
        cost = f", ${rec['cost_usd']:.2f}" if rec.get("cost_usd") is not None else ""
        print(f"  -> {rec['status']}: {rec['calls']} llamadas, entrada {rec.get('input', 0):,}, "
              f"salida {rec.get('output', 0):,}{cost}, {rec['minutes']} min"
              + (f"  [{rec['target']}]" if rec.get("target") else ""))
        if rec["status"] == "ok-over":
            print(f"  AVISO: {rec['reason']}, pero la traducción ya estaba verificada; se continúa.")
        elif rec["status"] != "ok":
            print(f"  DETENIDO: {rec['reason']}")
            print(f"  Revisa la sesión con: claude --resume {rec.get('session') or ''}".rstrip())
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
