"""Une las partes de una traducción B1/B2 en el fichero definitivo y borra las partes.

  python -I tools/join_parts.py <definitivo.txt> <parte1.txt> <parte2.txt> ...

Las partes se separan con una línea en blanco. Sustituye a `cat … > …; rm …`, que los permisos de las
sesiones automáticas no siempre dejan pasar (llaves, rutas fuera del repo, rm).
"""
import os
import sys


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    target, parts = sys.argv[1], sys.argv[2:]
    missing = [p for p in parts if not os.path.exists(p)]
    if missing:
        sys.exit(f"faltan: {', '.join(missing)}")
    texts = [open(p, encoding="utf-8").read().strip("\n") for p in parts]
    with open(target, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n\n".join(texts) + "\n")
    for p in parts:
        if os.path.abspath(p) != os.path.abspath(target):
            os.remove(p)
    print(f"ok: {target} ({len(parts)} partes, {sum(len(t.split()) for t in texts):,} palabras)")


if __name__ == "__main__":
    main()
