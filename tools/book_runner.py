"""Orquestador: crea uno tras otro los libros ⬜ del roadmap con la skill /graded-book.

Cada libro se hace en una sesión nueva de `claude -p` (historial limpio por libro). El consumo de
tokens se vigila en directo (stream-json) y la sesión se corta en cuanto es anormal; entonces el
orquestador se detiene sin pasar al siguiente libro.

Uso:
  python -I tools/book_runner.py                     # todos los ⬜, en orden
  python -I tools/book_runner.py --level B1 --max-books 3
  python -I tools/book_runner.py --title "Treasure Island"
  python -I tools/book_runner.py --dry-run           # muestra el plan y los límites, no ejecuta

Detención (código de salida 1) si en un libro:
  - salida > límite, entrada acumulada > límite, contexto de una llamada > límite o llamadas > límite;
  - la sesión acaba con error, o el libro no queda ✅ en el README (la skill no lo verificó).
Los límites son los de LIMITS por nivel/tipo; tras dos libros del mismo nivel y tipo, el límite
pasa a ser `--factor` × la mediana registrada si es menor. Registro: .claude/book-runs.jsonl.
"""
import argparse
import json
import os
import re
import shutil
import statistics
import subprocess
import sys
import time
from datetime import datetime, timezone

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
README = os.path.join(ROOT, "content", "graded-books", "README.md")
LOG = os.path.join(ROOT, ".claude", "book-runs.jsonl")

# Límites duros por libro. Referencia medida: A1 (Little Red Riding Hood) = 10 llamadas,
# ~30.000 de salida, ~711.000 de entrada acumulada (casi toda lectura de caché).
LIMITS = {
    ("A1", "Adaptación"): {"output": 60_000, "input": 1_500_000, "context": 150_000, "calls": 25},
    ("A2", "Adaptación"): {"output": 80_000, "input": 2_500_000, "context": 160_000, "calls": 30},
    ("B1", "Adaptación"): {"output": 180_000, "input": 6_000_000, "context": 220_000, "calls": 50},
    ("B2", "Adaptación"): {"output": 260_000, "input": 10_000_000, "context": 260_000, "calls": 70},
    ("B2", "Original"): {"output": 60_000, "input": 3_000_000, "context": 180_000, "calls": 40},
}

ALLOWED_TOOLS = [
    "Read", "Write", "Edit", "Glob", "Grep", "Skill", "WebSearch", "WebFetch",
    "Bash(python:*)", "Bash(cat:*)", "Bash(curl:*)", "Bash(grep:*)", "Bash(ls:*)", "Bash(head:*)",
    "Bash(tail:*)", "Bash(wc:*)", "Bash(cd:*)", "Bash(mkdir:*)", "Bash(sed:*)", "Bash(cp:*)",
    "Bash(mv:*)", "Bash(echo:*)",
]

PROMPT = (
    "/graded-book {title} {level}\n\n"
    "Ejecución desatendida desde tools/book_runner.py: no preguntes nada, no hagas commit y no "
    "toques otros libros. PYTHONIOENCODING=utf-8 ya está definido en el entorno: no lo antepongas "
    "a los comandos. Al terminar responde solo con una línea: `RESULTADO: ok` o "
    "`RESULTADO: fallo <motivo>`."
)

ROW = re.compile(r"^\| (\d+) \| (✅|⬜) \| (A1|A2|B1|B2) \| (Adaptación|Original) \| ([^|]+?) \|", re.M)


def roadmap():
    text = open(README, encoding="utf-8").read()
    return [
        {"n": int(m[1]), "done": m[2] == "✅", "level": m[3], "kind": m[4], "title": m[5].strip()}
        for m in ROW.finditer(text)
    ]


def history():
    if not os.path.exists(LOG):
        return []
    with open(LOG, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def limits_for(book, factor):
    base = dict(LIMITS[(book["level"], book["kind"])])
    done = [r for r in history() if r["level"] == book["level"] and r["kind"] == book["kind"]
            and r["status"] == "ok"]
    if len(done) >= 2:
        for key in ("output", "input", "calls"):
            adaptive = int(factor * statistics.median(r[key] for r in done))
            base[key] = min(base[key], max(adaptive, 1))
    return base


def content_chars(message):
    n = 0
    for block in message.get("content", []):
        if block.get("type") == "text":
            n += len(block.get("text", ""))
        elif block.get("type") == "thinking":
            n += len(block.get("thinking", ""))
        elif block.get("type") == "tool_use":
            n += len(json.dumps(block.get("input", {}), ensure_ascii=False))
    return n


def run_session(prompt, lim, args, base):
    """Ejecuta `claude -p` con vigilancia de tokens en directo. Devuelve (registro, motivo de parada)."""
    exe = shutil.which("claude")
    if not exe:
        sys.exit("No se encuentra `claude` en el PATH.")
    cmd = [exe, "-p", prompt, "--output-format", "stream-json", "--verbose",
           "--permission-mode", args.permission_mode, "--allowedTools", *ALLOWED_TOOLS]
    if args.model:
        cmd += ["--model", args.model]
    if args.budget_usd:
        cmd += ["--max-budget-usd", str(args.budget_usd)]
    env = dict(os.environ, PYTHONIOENCODING="utf-8")

    calls = {}           # message.id -> {"context": ..., "chars": ...}
    result, reason, session = None, None, None
    started = time.time()
    proc = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", errors="replace")
    for line in proc.stdout:
        try:
            event = json.loads(line)
        except json.JSONDecodeError:
            continue
        if event.get("type") == "system" and event.get("session_id"):
            session = event["session_id"]
        if event.get("type") == "assistant":
            msg = event["message"]
            u = msg.get("usage") or {}
            ctx = u.get("input_tokens", 0) + u.get("cache_creation_input_tokens", 0) + u.get("cache_read_input_tokens", 0)
            c = calls.setdefault(msg.get("id"), {"context": 0, "chars": 0})
            c["context"] = max(c["context"], ctx)
            c["chars"] += content_chars(msg)
            # Salida en directo: el stream infravalora output_tokens; se estima por caracteres
            # (2,4 caracteres/token calibrado con los dos primeros libros: el razonamiento no llega
            # entero al stream).
            live = {"calls": len(calls), "input": sum(x["context"] for x in calls.values()),
                    "output": int(sum(x["chars"] for x in calls.values()) / 2.4),
                    "context": max(x["context"] for x in calls.values())}
            over = [k for k in ("calls", "input", "output", "context") if live[k] > lim[k]]
            if over:
                reason = "consumo anormal: " + ", ".join(f"{k}={live[k]:,} > {lim[k]:,}" for k in over)
                proc.terminate()
                break
            print(f"\r  llamadas {live['calls']:3d}  entrada {live['input']:>10,}  salida≈{live['output']:>8,}"
                  f"  contexto {live['context']:>8,}", end="", flush=True)
        elif event.get("type") == "result":
            result = event
    proc.wait()
    print()

    record = {"time": datetime.now(timezone.utc).isoformat(timespec="seconds"), **base,
              "minutes": round((time.time() - started) / 60, 1), "calls": len(calls),
              "context_max": max((x["context"] for x in calls.values()), default=0), "session": session}
    if result:
        u = result.get("usage", {})
        record.update(input=u.get("input_tokens", 0) + u.get("cache_creation_input_tokens", 0)
                      + u.get("cache_read_input_tokens", 0),
                      cache_read=u.get("cache_read_input_tokens", 0), output=u.get("output_tokens", 0),
                      cost_usd=result.get("total_cost_usd"), session=result.get("session_id"),
                      answer=(result.get("result") or "").strip()[-300:],
                      denials=len(result.get("permission_denials") or []))
        if not reason and (result.get("is_error") or result.get("subtype") != "success"):
            reason = f"la sesión terminó con {result.get('subtype')}: {record['answer'][:200]}"
        if not reason:
            over = [k for k in ("output", "input") if record[k] > lim[k]]
            if over:
                reason = "consumo anormal: " + ", ".join(f"{k}={record[k]:,} > {lim[k]:,}" for k in over)
    elif not reason:
        reason = "la sesión terminó sin resultado"
    return record, reason


def finish(record, reason, done_now, not_done_reason, log):
    """Fija el estado del registro y lo añade al log."""
    if not reason and not done_now:
        reason = not_done_reason
    if reason and done_now and reason.startswith("consumo anormal"):
        # Cortado tras terminar y verificar: el trabajo está hecho; se sigue, pero el registro no
        # entra en la mediana del límite adaptativo (status distinto de "ok").
        record["status"] = "ok-over"
    else:
        record["status"] = "ok" if not reason else "stop"
    record["reason"] = reason
    os.makedirs(os.path.dirname(log), exist_ok=True)
    with open(log, "a", encoding="utf-8") as f:
        f.write(json.dumps(record, ensure_ascii=False) + "\n")
    return record


def run_book(book, lim, args):
    record, reason = run_session(PROMPT.format(**book), lim, args, book)
    done_now = next(b for b in roadmap() if b["n"] == book["n"])["done"]
    return finish(record, reason, done_now,
                  "el libro no quedó ✅ en el README (no pasó la verificación)", LOG)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--level", choices=["A1", "A2", "B1", "B2"])
    ap.add_argument("--title", help="solo este libro del roadmap")
    ap.add_argument("--max-books", type=int, default=0, help="0 = todos")
    ap.add_argument("--factor", type=float, default=2.5, help="límite adaptativo = factor × mediana")
    ap.add_argument("--budget-usd", type=float, help="tope de gasto por libro (--max-budget-usd)")
    ap.add_argument("--model", help="modelo para las sesiones (por defecto, el configurado)")
    ap.add_argument("--permission-mode", default="acceptEdits")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    pending = [b for b in roadmap() if not b["done"]
               and (not args.level or b["level"] == args.level)
               and (not args.title or b["title"].lower() == args.title.lower())]
    if args.max_books:
        pending = pending[: args.max_books]
    if not pending:
        print("No hay libros ⬜ que coincidan.")
        return 0

    for i, book in enumerate(pending, 1):
        lim = limits_for(book, args.factor)
        print(f"[{i}/{len(pending)}] #{book['n']} {book['title']} ({book['level']}, {book['kind']})  "
              f"límites: salida {lim['output']:,}, entrada {lim['input']:,}, contexto {lim['context']:,}, "
              f"llamadas {lim['calls']}")
        if args.dry_run:
            continue
        rec = run_book(book, lim, args)
        cost = f", ${rec['cost_usd']:.2f}" if rec.get("cost_usd") is not None else ""
        print(f"  -> {rec['status']}: {rec['calls']} llamadas, entrada {rec.get('input', 0):,}, "
              f"salida {rec.get('output', 0):,}{cost}, {rec['minutes']} min")
        if rec["status"] == "ok-over":
            print(f"  AVISO: {rec['reason']}, pero el libro ya estaba ✅; se continúa.")
        elif rec["status"] != "ok":
            print(f"  DETENIDO: {rec['reason']}")
            print(f"  Revisa la sesión con: claude --resume {rec.get('session', '')}".rstrip())
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
