"""Comprueba la traducción de un libro graduado frente a su original de content/graded-books.

Uso:
  python -I tools/translation_check.py <origen.txt> <traduccion.txt> --lang es|fr|de|it|pt
         [--level A1|A2|B1|B2] [--show-long N] [--fix-typography]

Comprueba:
  - estructura: mismo número de capítulos y de párrafos por capítulo (1:1, para la vista bilingüe);
  - extensión: palabras de la traducción / palabras del origen dentro del rango del idioma;
  - frases: media de palabras por frase comparada con la del origen;
  - legibilidad del idioma destino: INFLESZ (es), Kandel-Moles (fr), Flesch genérico (resto);
  - tiempos verbales no permitidos en el nivel (es, fr): listado para revisar a mano;
  - restos en inglés y tipografía (¿¡ y raya en es; « » y espacios no separables en fr).
--fix-typography (fr): pone espacio no separable antes de ; : ! ? » y después de «, y reescribe el
fichero. El nivel se deduce de la ruta (/A1/, /A2/...) si no se indica.
"""
import argparse
import re
import statistics
import sys

NBSP = " "
WORD = re.compile(r"[^\W\d_]+(?:['’][^\W\d_]+)*")
UPPER = "A-ZÁÉÍÓÚÑÜÀÂÇÈÊËÎÏÔÛÙŸÆŒÄÖ"
SENTENCE_END = re.compile(rf'(?<=[.!?…])[»"”“]?[\s{NBSP}]*[»"”]?\s+(?=[—«"“¡¿„]?[{UPPER}])')
CHAPTER_EN = re.compile(r"^chapter\s+\d+\b.{0,60}$", re.I)

LANGS = {
    "es": {"chapter": r"capítulo", "end": "Fin", "ratio": (0.85, 1.25)},  # sin sujetos ni do/does
    "fr": {"chapter": r"chapitre", "end": "Fin", "ratio": (0.85, 1.25)},   # l'arbre, d'un = 1 palabra
    "de": {"chapter": r"kapitel", "end": "Ende", "ratio": (0.85, 1.15)},
    "it": {"chapter": r"capitolo", "end": "Fine", "ratio": (0.85, 1.25)},
    "pt": {"chapter": r"capítulo", "end": "Fim", "ratio": (0.85, 1.25)},
}
SENTENCE_RATIO = (0.9, 1.4)
ENGLISH = set("the and says said with you they this that what is are of from was were".split())

# (etiqueta, regex sobre la frase, niveles en los que está prohibido, palabras que no cuentan)
TENSES = {
    "es": [
        ("pretérito indefinido", r"\b(\w{2,}(?:aron|ieron|ió)|fue|fueron|fui|tuvo|tuvieron|hizo|hicieron|dijo|dijeron|vino|vinieron|estuvo|pudo|quiso|puso|dio|vio|supo|hubo)\b", {"A1"}, set()),
        ("pretérito imperfecto", r"\b(\w{2,}(?:aba|aban|ábamos)|era|eran|eras|iba|iban|había|habían|tenía|tenían|quería|querían|podía|podían|sabía|decía|hacía|vivía|estaba|estaban)\b", {"A1"}, set()),
        ("pretérito perfecto", r"\b(?:he|has|ha|hemos|habéis|han)\s+\w+(?:ado|ido|to|cho|sto)\b", {"A1"}, set()),
        ("pluscuamperfecto", r"\b(?:había|habías|habíamos|habíais|habían)\s+\w+(?:ado|ido|to|cho|sto)\b", {"A1", "A2"}, set()),
        ("subjuntivo (fórmulas)", r"\b(?:para que|antes de que|ojalá|quiero que|quieres que|quiere que|cuando \w+(?:e|es|en|a|as|an))\b", {"A1"}, set()),
        ("imperfecto de subjuntivo", r"\b\w+(?:aran|áramos|iera|ieras|ieran|iéramos|iese|iesen)\b", {"A1", "A2"}, {"fiera", "fieras", "quiera", "quieras", "quieran"}),
        ("condicional compuesto / hubiera + participio", r"\b(?:habría|habrías|habríamos|habrían|hubiera|hubieras|hubieran|hubiese)\s+\w+(?:ado|ido|to|cho|sto)\b", {"A1", "A2"}, set()),
    ],
    "fr": [
        ("passé composé", r"\b(?:(?:ai|as|a|avons|avez|ont)\s+\w+(?:é|ée|és|ées|i|is|ie|it|u|ue|us|ert|ort)|(?:suis|es|est|sommes|êtes|sont)\s+(?:allée?s?|venue?s?|arrivée?s?|partie?s?|sortie?s?|entrée?s?|montée?s?|descendue?s?|tombée?s?|restée?s?|née?s?|revenue?s?|retournée?s?|devenue?s?|rentrée?s?|passée?s?))\b|\bs'(?:est|sont)\s+\w+(?:é|ée|és|ées|i|is|ie|u|ue|us)\b", {"A1"},
         {"petit", "petite", "ici", "joli", "jolie", "gris", "assis", "assise", "gentil", "fatigué", "fatiguée", "étonné", "surpris", "coincé", "coincée", "fâché", "fâchée", "occupé", "mort", "morte", "perdu", "perdue", "content", "ravi", "ravie", "épais", "lui", "aussi", "fini", "là", "tout", "nu", "seul", "fou", "doux", "roux", "à", "plus", "si", "que", "peur", "faim", "soif", "lu"}),
        ("imparfait", r"\b\w{2,}(?:ais|ait|aient)\b", {"A1"},
         {"mais", "jamais", "fait", "fais", "lait", "palais", "frais", "épais", "souhait", "portrait", "trait", "parfait", "anglais", "français", "mauvais", "désormais", "marais", "relais", "balais", "distrait", "extrait", "satisfait", "bienfait", "méfait", "attrait", "tais", "plais", "vrais", "laids", "niais", "biais", "dais", "jais", "refait", "défait"}),
        ("passé simple", r"\b(?:\w+(?:èrent|irent|(?<!o)urent)|fut|furent|eut|eurent|fit|firent|vint|vinrent|prit|prirent|mit|mirent)\b", {"A1", "A2", "B1"}, set()),
        ("plus-que-parfait", r"\b(?:avais|avait|avions|aviez|avaient|étais|était|étions|étiez|étaient)\s+(?:\w+(?:é|ée|és|ées|i|is|ie|u|ue|us))\b", {"A1", "A2"},
         {"petit", "ici", "joli", "jolie", "gris", "assis", "assise", "gentil", "fatigué", "fatiguée", "content", "mort", "morte", "perdu", "perdue", "lui", "aussi", "là", "tout"}),
        ("subjonctif (fórmulas)", r"\b(?:il faut que|pour que|avant que|bien que|veux que|veut que|voulez que)\b", {"A1"}, set()),
        ("conditionnel", r"\b\w{3,}(?:rais|rait|rions|riez|raient)\b", {"A1"}, {"vrais", "frais", "trait", "portrait", "retrait", "extrait"}),
    ],
}


def syllables(word, lang):
    w = word.lower()
    if lang == "es":
        w = re.sub(r"(?<=[qg])u(?=[eiéí])", "", w)
        w = re.sub(r"y$", "i", w)
        n = 0
        for g in re.findall(r"[aeiouáéíóúü]+", w):
            n += 1 + sum(1 for a, b in zip(g, g[1:])
                         if (a in "aeoáéó" and b in "aeoáéó") or a in "íú" or b in "íú")
        return max(n, 1)
    if lang == "fr":
        groups = re.findall(r"[aeiouyàâäéèêëîïôöùûüœæ]+", w)
        n = len(groups)
        if n > 1 and re.search(r"[^aeiouyàâäéèêëîïôöùûü](e|es)$", w):
            n -= 1
        return max(n, 1)
    return max(len(re.findall(r"[aeiouyäöüàèéìòùáíóú]+", w)), 1)


def readability(words, n_sentences, lang):
    wps = len(words) / n_sentences
    spw = sum(syllables(w, lang) for w in words) / len(words)
    if lang == "es":
        return "INFLESZ", 206.835 - 62.3 * spw - wps, ">80 muy fácil, 65-80 bastante fácil, 55-65 normal"
    if lang == "fr":
        return "Kandel-Moles", 207 - 1.015 * wps - 73.6 * spw, ">80 très facile, 70-80 facile, 60-70 assez facile"
    return "Flesch genérico", 206.835 - 1.015 * wps - 84.6 * spw, "aproximado: no calibrado para este idioma"


def chapters_of(text, heading):
    """Lista de capítulos; cada uno, la lista de sus párrafos (sin la cabecera ni el cierre)."""
    out = []
    for p in re.split(r"\n\s*\n", text.strip()):
        p = " ".join(p.split())
        if heading.match(p):
            out.append([])
        elif out and p:
            out[-1].append(p)
    if out and out[-1] and len(out[-1][-1].split()) <= 2:   # The End / Fin
        out[-1].pop()
    return out


def sentences_of(chapters):
    return [s for ch in chapters for p in ch for s in SENTENCE_END.split(p) if WORD.search(s)]


def fix_french(text):
    lines = []
    for line in text.split("\n"):
        line = re.sub(rf"(?<=[^\s«{NBSP}])[  {NBSP}]*([!?;:»]+)", lambda m: NBSP + m[1], line)
        line = re.sub(rf"«[  {NBSP}]*", "«" + NBSP, line)
        line = re.sub(rf"([!?;:])\{NBSP}?»", rf"\1{NBSP}»", line)
        lines.append(line)
    return "\n".join(lines)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("source")
    ap.add_argument("target")
    ap.add_argument("--lang", required=True, choices=LANGS)
    ap.add_argument("--level", choices=["A1", "A2", "B1", "B2"])
    ap.add_argument("--show-long", type=int, default=0)
    ap.add_argument("--fix-typography", action="store_true")
    args = ap.parse_args()
    cfg = LANGS[args.lang]
    level = args.level or (re.search(r"[/\\](A1|A2|B1|B2)[/\\]", args.target) or [None, "A1"])[1]

    text = open(args.target, encoding="utf-8").read()
    if args.fix_typography and args.lang == "fr":
        fixed = fix_french(text)
        if fixed != text:
            open(args.target, "w", encoding="utf-8", newline="\n").write(fixed)
            print("Tipografía corregida (espacios no separables).")
        text = fixed

    src = chapters_of(open(args.source, encoding="utf-8").read(), CHAPTER_EN)
    heading = re.compile(rf"^{cfg['chapter']}\s+\d+\b.{{0,60}}$", re.I)
    dst = chapters_of(text, heading)
    problems = 0

    print(f"Traducción: {args.target}  ({args.lang}, {level})")
    print(f"Capítulos: {len(dst)}  origen {len(src)}")
    if len(dst) != len(src):
        problems += 1
        print("  ERROR: distinto número de capítulos (revisa las cabeceras «"
              f"{cfg['chapter'].capitalize()} N: ...»)")
    for i, (a, b) in enumerate(zip(src, dst), 1):
        if len(a) != len(b):
            problems += 1
            print(f"  ERROR: capítulo {i}: {len(b)} párrafos, origen {len(a)}")
    if not text.rstrip().endswith(cfg["end"]):
        problems += 1
        print(f"  ERROR: la última línea debe ser «{cfg['end']}»")

    s_src, s_dst = sentences_of(src), sentences_of(dst)
    w_src = [w for s in s_src for w in WORD.findall(s)]
    w_dst = [w.lower() for s in s_dst for w in WORD.findall(s)]
    lengths = [len(WORD.findall(s)) for s in s_dst]
    ratio = len(w_dst) / len(w_src)
    lo, hi = cfg["ratio"]
    flag = "" if lo <= ratio <= hi else "  FUERA DE RANGO"
    problems += bool(flag)
    print(f"Palabras: {len(w_dst)}  origen {len(w_src)}  ratio {ratio:.2f}  objetivo {lo}-{hi}{flag}")
    m_src, m_dst = len(w_src) / len(s_src), statistics.mean(lengths)
    sr = m_dst / m_src
    flag = "" if SENTENCE_RATIO[0] <= sr <= SENTENCE_RATIO[1] else "  FUERA DE RANGO"
    problems += bool(flag)
    print(f"Frases: {len(s_dst)}  media {m_dst:.1f}  (origen {m_src:.1f}, ratio {sr:.2f}, "
          f"objetivo {SENTENCE_RATIO[0]}-{SENTENCE_RATIO[1]}){flag}  máx {max(lengths)}")
    name, score, scale = readability(w_dst, len(s_dst), args.lang)
    print(f"Legibilidad {name}: {score:.0f}  ({scale})")
    print(f"Palabras distintas: {len(set(w_dst))}")

    leftovers = sorted({w for w in w_dst if w in ENGLISH})
    if leftovers:
        problems += 1
        print(f"  ERROR: posibles restos en inglés: {leftovers}")

    for label, rx, banned, allowed in TENSES.get(args.lang, []):
        pattern = re.compile(rx, re.I)
        hits = [(m.group(0), s) for s in s_dst for m in pattern.finditer(s)
                if m.group(0).lower().split()[-1] not in allowed]
        if not hits:
            continue
        mark = "REVISAR (no permitido en " + level + ")" if level in banned else "ok"
        print(f"Tiempos: {label}: {len(hits)}  {mark}")
        if level in banned:
            print(f"  formas: {sorted({h.lower() for h, _ in hits})[:40]}")
            for h, s in hits[:4]:
                print(f"    «{h}» en: {s[:110]}")

    typo = []
    if '"' in text:
        typo.append(f'{text.count(chr(34))} comillas rectas " (usa raya o « »)')
    if args.lang == "es":
        bad_q = [s for s in s_dst if "?" in s and "¿" not in s]
        bad_e = [s for s in s_dst if "!" in s and "¡" not in s]
        if bad_q or bad_e:
            typo.append(f"{len(bad_q)} frases con ? sin ¿ y {len(bad_e)} con ! sin ¡: "
                        + " | ".join(x[:60] for x in (bad_q + bad_e)[:3]))
        if re.search(r"^- ", text, re.M):
            typo.append("diálogos con guion corto: usa la raya (—)")
    if args.lang == "fr":
        miss = re.findall(rf"\S(?<![{NBSP} ])[!?;:»]|«(?![{NBSP} ])", text)
        if miss:
            typo.append(f"{len(miss)} signos sin espacio no separable (ejecuta con --fix-typography)")
    for t in typo:
        problems += 1
        print(f"  TIPOGRAFÍA: {t}")

    if args.show_long:
        print("\nFrases más largas:")
        for n, s in sorted(zip(lengths, s_dst), reverse=True)[: args.show_long]:
            print(f"  [{n}] {s}")
    print(f"\n{'OK' if not problems else f'{problems} problema(s) estructurales o de tipografía'}"
          " (los tiempos marcados REVISAR se comprueban a mano: hay falsos positivos)")
    return 0 if not problems else 1


if __name__ == "__main__":
    sys.exit(main())
