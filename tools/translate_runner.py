"""Orquestador: traduce uno tras otro los libros del catálogo con la skill /graded-translate.

Cada traducción se hace en una sesión nueva de `claude -p` (historial limpio por libro), con la misma
vigilancia de tokens que tools/book_runner.py: si el consumo es anormal, la sesión se corta y el
orquestador se detiene sin pasar al siguiente libro.

Uso:
  python -I tools/translate_runner.py --lang de              # todos los ⬜ del alemán, en orden
  python -I tools/translate_runner.py --lang de,it --level A1 --max-books 4
  python -I tools/translate_runner.py --lang pt --title "Puss in Boots"
  python -I tools/translate_runner.py --lang de --dry-run    # plan y límites, sin ejecutar
  python -I tools/translate_runner.py --lang es --add-missing --dry-run   # añade filas ⬜ de todo el catálogo

Control por idioma: content/graded-books/<idioma>/README.md (tools/graded_langs.py). Pendiente = fila ⬜
de ese fichero; si no existe, se crea con los 16 libros de graded_langs.DEFAULT_PLAN. --add-missing añade
filas ⬜ para los libros del catálogo inglés que aún no estén (los originales íntegros de B2, Standard
Ebooks, solo con --include-originals: traducirlos es una traducción literaria de 25.000-50.000 palabras).
Modelo: `opus` salvo --model (no bajar de opus: la naturalidad de la traducción depende de él).

Detención (código de salida 1) si en una traducción:
  - salida, entrada acumulada, contexto de una llamada o llamadas superan el límite;
  - la sesión acaba con error;
  - la fila no queda ✅ con fichero en el README del idioma, falta el fichero o translation_check.py no da OK
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
import graded_langs  # noqa: E402
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
LANG_NAMES = {code: info["name"] for code, info in graded_langs.LANGS.items()}

PROMPT = (
    "/graded-translate {source} {lang}\n\n"
    "Traduce `content/graded-books/{source}` ({title}, {level}) al {lang_name}; es la fila #{n} (⬜) de "
    "`content/graded-books/{lang}/README.md`, que debes dejar ✅ con fichero, título y métricas. "
    "Ejecución desatendida desde tools/translate_runner.py: no preguntes nada, no hagas commit y no "
    "toques otros libros ni otras traducciones. PYTHONIOENCODING=utf-8 ya está definido en el "
    "entorno: no lo antepongas a los comandos. Al terminar responde solo con una línea: "
    "`RESULTADO: ok` o `RESULTADO: fallo <motivo>`."
)

SOURCE_ROW = re.compile(r"^\| `(en/(A1|A2|B1|B2)/[^`]+)` \| ([^|]+?) \| (?:A1|A2|B1|B2) \|.*\| ([^|]+) \|$", re.M)


def sources():
    """Libros del catálogo inglés: {source, level, title, original}, en orden del README general."""
    text = open(README, encoding="utf-8").read()
    return [{"source": m[1], "level": m[2], "title": m[3].strip(), "original": "Standard Ebooks" in m[4]}
            for m in SOURCE_ROW.finditer(text)]


def ensure_control_file(lang, srcs, add_missing, include_originals):
    """Crea el README del idioma si falta (plan de 16) y, con --add-missing, añade filas ⬜."""
    data = graded_langs.parse(lang)
    rows = data["rows"]
    created = not graded_langs.readme_path(lang).exists()
    if created:
        rows = graded_langs.plan_rows()
    if add_missing:
        have = {r["source"] for r in rows}
        for s in srcs:
            if s["source"] not in have and (include_originals or not s["original"]):
                rows += graded_langs.plan_rows([s["source"][3:-4]], start=len(rows) + 1)
    if created or len(rows) != len(data["rows"]):
        graded_langs.write(lang, rows, data["glossary"])
        print(f"{'Creado' if created else 'Ampliado'} content/graded-books/{lang}/README.md: "
              f"{sum(not r['done'] for r in rows)} filas ⬜")
    return rows


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
    """Hecha si su fila del README del idioma está ✅ con fichero, el fichero existe y pasa el checker."""
    row = next((r for r in graded_langs.parse(job["lang"])["rows"] if r["source"] == job["source"]), None)
    if not row or not row["done"]:
        return False, f"la fila #{job['n']} no está ✅ en content/graded-books/{job['lang']}/README.md"
    target = row["target"]
    if not target:
        return False, f"la fila #{job['n']} está ✅ pero sin fichero"
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
    ap.add_argument("--include-originals", action="store_true",
                    help="con --add-missing, incluir también los originales íntegros de B2")
    ap.add_argument("--add-missing", action="store_true",
                    help="añadir filas ⬜ al README del idioma para los libros del catálogo que falten")
    ap.add_argument("--factor", type=float, default=2.5, help="límite adaptativo = factor × mediana")
    ap.add_argument("--budget-usd", type=float, help="tope de gasto por libro (--max-budget-usd)")
    ap.add_argument("--model", default="opus",
                    help="modelo para las sesiones (por defecto opus; p. ej. fable)")
    ap.add_argument("--permission-mode", default="acceptEdits")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    langs = [l.strip() for l in args.lang.split(",") if l.strip()]
    unknown = [l for l in langs if l not in LANG_NAMES]
    if unknown:
        sys.exit(f"Idioma no soportado por translation_check.py: {unknown} (válidos: {', '.join(LANG_NAMES)})")

    srcs = sources()
    by_source = {s["source"]: s for s in srcs}
    jobs = []
    for l in langs:
        for r in ensure_control_file(l, srcs, args.add_missing, args.include_originals):
            s = by_source.get(r["source"])
            if r["done"] or not s:
                continue
            if (not args.level or s["level"] == args.level) and \
                    (not args.title or s["title"].lower() == args.title.lower()):
                jobs.append(dict(s, n=r["n"], lang=l, lang_name=LANG_NAMES[l]))
    if args.max_books:
        jobs = jobs[: args.max_books]
    if not jobs:
        print("No hay traducciones pendientes que coincidan.")
        return 0

    for i, job in enumerate(jobs, 1):
        lim = limits_for(job, args.factor)
        print(f"[{i}/{len(jobs)}] #{job['n']} {job['title']} ({job['level']}) -> {job['lang']}  "
              f"límites: salida {lim['output']:,}, entrada {lim['input']:,}, contexto {lim['context']:,}, "
              f"llamadas {lim['calls']}")
        if args.dry_run:
            continue
        base = {k: job[k] for k in ("source", "title", "level", "lang", "n")}
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
