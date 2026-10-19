"""Suma el consumo de tokens de una sesión de Claude Code por tramos de tiempo.

Uso:
  python -I tools/session_usage.py <sesion.jsonl> --turns
      lista los mensajes del usuario con su hora (para elegir tramos)
  python -I tools/session_usage.py <sesion.jsonl> NOMBRE=INICIO..FIN [NOMBRE=INICIO..FIN ...]
      INICIO/FIN en ISO UTC, p. ej. 2026-10-18T19:05:44Z

Las transcripciones están en ~/.claude/projects/<proyecto>/<id-sesion>.jsonl. Cada respuesta del
modelo puede aparecer en varias líneas (una por bloque de contenido) con el mismo message.id; se
cuenta una vez.
"""
import json
import sys
from datetime import datetime


def ts(value: str) -> datetime:
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def load(path):
    usage_by_id, user_turns = {}, []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue
            msg = entry.get("message") or {}
            if entry.get("type") == "assistant" and msg.get("usage") and msg.get("id"):
                # Hora de la primera línea del mensaje (cuando empieza a generarse).
                usage_by_id.setdefault(msg["id"], (ts(entry["timestamp"]), msg["usage"]))
            elif entry.get("type") == "user" and isinstance(msg.get("content"), str):
                user_turns.append((entry["timestamp"], msg["content"][:80].replace("\n", " ")))
    return usage_by_id, user_turns


FIELDS = ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens", "output_tokens")


def main(argv):
    usage_by_id, user_turns = load(argv[1])
    if "--turns" in argv:
        for when, text in user_turns:
            print(when, text)
        return 0
    header = f"{'tramo':<14}{'min':>6}{'llamadas':>9}{'entrada':>10}{'cache_w':>10}{'cache_r':>12}{'salida':>9}"
    print(header)
    for spec in argv[2:]:
        name, span = spec.split("=", 1)
        start, end = (ts(x) for x in span.split(".."))
        totals = dict.fromkeys(FIELDS, 0)
        calls = 0
        for when, usage in usage_by_id.values():
            if start <= when < end:
                calls += 1
                for f in FIELDS:
                    totals[f] += usage.get(f, 0) or 0
        minutes = (end - start).total_seconds() / 60
        print(f"{name:<14}{minutes:>6.1f}{calls:>9}{totals['input_tokens']:>10}"
              f"{totals['cache_creation_input_tokens']:>10}{totals['cache_read_input_tokens']:>12}"
              f"{totals['output_tokens']:>9}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
