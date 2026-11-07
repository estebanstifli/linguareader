"""Comprueba la traducción de un libro graduado frente a su original de content/graded-books.

Uso:
  python -I tools/translation_check.py <origen.txt> <traduccion.txt> --lang es|fr|de|it|pt|nl|pl|sv|ru|ko|hi|tr|el|uk
         [--level A1|A2|B1|B2] [--show-long N] [--fix-typography]

Comprueba:
  - estructura: mismo número de capítulos y de párrafos por capítulo (1:1, para la vista bilingüe);
  - extensión: palabras de la traducción / palabras del origen dentro del rango del idioma;
  - frases: media de palabras por frase comparada con la del origen;
  - legibilidad del idioma destino: INFLESZ (es), Kandel-Moles (fr), Flesch-Amstad (de), Gulpease (it),
    Flesch-Martins (pt), Flesch-Douma (nl), FOG-PL (pl), LIX (sv), Flesch-Oborneva (ru), Ateşman (tr) y
    sílabas por palabra (ko, hi, el, uk: sin índice estándar);
  - tiempos verbales no permitidos en el nivel (es, fr, de, it, pt): listado para revisar a mano;
  - restos en inglés y tipografía (¿¡ y raya en es; « » y espacios no separables en fr; „“ en de;
    « » o raya en it; travessão en pt; comillas rectas " en todos).
--fix-typography (fr): pone espacio no separable antes de ; : ! ? » y después de «, y reescribe el
fichero. El nivel se deduce de la ruta (/A1/, /A2/...) si no se indica.
"""
import argparse
import re
import statistics
import sys

NBSP = " "
# Letras más signos vocálicos/virama del devanagari y diacríticos combinantes (no son \w en Python).
WORD = re.compile(r"(?:[^\W\d_]|[\u0900-\u0963\u0971-\u097f\u0300-\u036f])+(?:['’][^\W\d_]+)*")
UPPER = "A-ZÁÉÍÓÚÑÜÀÂÇÈÊËÎÏÔÛÙŸÆŒÄÖÃÕÌÒĄĆĘŁŃŚŹŻÅА-ЯЁІЇЄҐĞİŞΑ-ΩΆΈΉΊΌΎΏ"
SENTENCE_END = re.compile(rf'(?<=[.!?…])[»"”“]?[\s{NBSP}]*[»"”]?\s+(?=[—–«"“”¡¿„]?[{UPPER}])')
# Griego: la interrogación es «;» (no «?»).
SENTENCE_END_EL = re.compile(rf'(?<=[.!;…])[»"”“]?[\s{NBSP}]*[»"”]?\s+(?=[—–«"“”]?[{UPPER}])')
# Escrituras sin mayúsculas (hangul, devanagari): fin de frase sin mirar la letra siguiente.
SENTENCE_END_CASELESS = re.compile(r'(?<=[.!?…।])[»"”’]?\s+')
CHAPTER_EN = re.compile(r"^chapter\s+\d+\b.{0,60}$", re.I)

LANGS = {
    "es": {"chapter": r"capítulo", "end": "Fin", "ratio": (0.85, 1.25)},  # sin sujetos ni do/does
    "fr": {"chapter": r"chapitre", "end": "Fin", "ratio": (0.85, 1.25)},   # l'arbre, d'un = 1 palabra
    "de": {"chapter": r"kapitel", "end": "Ende", "ratio": (0.85, 1.15)},
    "it": {"chapter": r"capitolo", "end": "Fine", "ratio": (0.85, 1.25)},
    "pt": {"chapter": r"capítulo", "end": "Fim", "ratio": (0.85, 1.25)},
    "nl": {"chapter": r"hoofdstuk", "end": "Einde", "ratio": (0.85, 1.25)},
    # Sin artículos (pl) o con artículo pegado y compuestos (sv): menos palabras y frases más cortas.
    "pl": {"chapter": r"rozdział", "end": "Koniec", "ratio": (0.6, 1.15), "sentence_ratio": (0.7, 1.3)},
    "sv": {"chapter": r"kapitel", "end": "Slut", "ratio": (0.8, 1.2), "sentence_ratio": (0.8, 1.35)},
    "ru": {"chapter": r"глава", "end": "Конец", "ratio": (0.6, 1.1), "sentence_ratio": (0.7, 1.3)},
    # Coreano: palabra = eojeol (raíz + partículas y terminaciones), muchas menos que en inglés.
    "ko": {"chapter": r"제", "heading": r"^제\s*\d+\s*장\s*:.{0,60}$", "end": "끝", "ratio": (0.4, 1.0),
           "sentence_ratio": (0.4, 1.2), "caseless": True},
    # Hindi: posposiciones y auxiliares sueltos (ने, को, है), algo más de palabras que en inglés.
    "hi": {"chapter": r"अध्याय", "end": "समाप्त", "ratio": (0.9, 1.6), "sentence_ratio": (0.9, 1.8),
           "caseless": True},
    # Turco aglutinante (sufijos en vez de artículos, preposiciones y pronombres): bastantes menos palabras.
    "tr": {"chapter": r"bölüm", "end": "Son", "ratio": (0.55, 1.0), "sentence_ratio": (0.6, 1.25)},
    "el": {"chapter": r"κεφάλαιο", "end": "Τέλος", "ratio": (0.85, 1.25)},
    "uk": {"chapter": r"розділ", "end": "Кінець", "ratio": (0.6, 1.1), "sentence_ratio": (0.7, 1.3)},
}
SENTENCE_RATIO = (0.9, 1.4)
ENGLISH = set("the and says said with you they this that what is are of from was were".split())
# Palabras inglesas que también existen en el idioma destino y no cuentan como resto.
ENGLISH_EXCEPT = {"de": {"was"}, "it": set(), "pt": set(), "es": set(), "fr": set(),
                  "nl": {"is", "was", "of"}, "sv": {"is"}, "pl": set(), "ru": set(), "ko": set(), "hi": set(),
                  "tr": set(), "el": set(), "uk": set()}


def _korean_past():
    """Sílabas hangul con batchim ㅆ (갔, 왔, 했, 었…), salvo 있/겠/밌, que no son pasado."""
    chars = [chr(c) for c in range(0xAC00, 0xD7A4) if (c - 0xAC00) % 28 == 20 and chr(c) not in "있겠밌"]
    return "[" + "".join(chars) + "]"


_KO_PAST = _korean_past()
_HB, _HE = r"(?<![\w\u0900-\u097f])", r"(?![\w\u0900-\u097f])"

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
    "de": [
        ("Präteritum", r"\b(?:war|waren|hatte|hatten|wurde|wurden|ging|gingen|kam|kamen|sagte|sagten|sah|sahen|gab|nahm|lief|stand|saß|fand|fiel|rief|schrie|dachte|wusste|konnte|wollte|musste|sollte|durfte|\w{3,}(?:te|ten|test|tet))\b", {"A1"},
         {"bitte", "mitte", "tante", "karte", "torte", "ente", "ernte", "tinte", "kette", "hütte", "ratte", "matte", "sorte", "liste", "kiste", "pforte", "ratten", "katten", "garten", "warten", "hinten", "unten", "zehnte", "letzte", "erste", "zweite", "dritte", "vierte", "fünfte", "sechste", "siebte", "achte", "neunte", "beste", "bunte", "alte", "kalte", "gute", "rote", "weite", "harte", "glatte", "nette", "satte", "fette", "dichte", "rechte", "echte", "leichte", "schlechte", "feuchte", "spitze", "minute", "minuten", "seite", "seiten", "leute", "weste", "geste", "paste", "hute", "haute", "braute"}),
        ("Perfekt", r"\b(?:hat|hast|habe|haben|habt|ist|bist|bin|sind|seid)\s+(?:\w+\s+){0,3}?(?:ge\w+(?:t|en)|\w+iert)\b", {"A1"},
         {"gesund", "gefährlich", "gelb", "genug", "gern", "gerade", "gegen", "gegenüber", "gestern", "gemein", "geheim", "gemütlich", "geduldig", "geschickt", "gewohnt", "gewiss", "geliebt", "gelehrt"}),
        ("Plusquamperfekt", r"\b(?:hatte|hatten|war|waren)\s+(?:\w+\s+){0,3}?ge\w+(?:t|en)\b", {"A1", "A2"}, set()),
        ("Konjunktiv II", r"\b(?:hätte|hätten|wäre|wären|würde|würden|könnte|könnten|möchte|möchten|sollte|sollten|wüsste|käme|ginge|gäbe)\b", {"A1"}, {"möchte", "möchten"}),
    ],
    "it": [
        ("passato prossimo", r"\b(?:ho|hai|ha|abbiamo|avete|hanno|sono|sei|è|siamo|siete)\s+(?:\w+\s+)?\w+(?:ato|ata|ati|ate|uto|uta|uti|ute|ito|ita|iti|ite|so|sa|si|se|sto|sta|sti|ste|tto|tta|tti|tte)\b", {"A1"},
         {"seduto", "seduta", "seduti", "sedute", "stanco", "stanca", "contento", "contenta", "felice", "triste", "questo", "questa", "questi", "queste", "molto", "molta", "molti", "molte", "tutto", "tutta", "tutti", "tutte", "fatto", "cosa", "casa", "testa", "festa", "vista", "basta", "giusto", "giusta", "presto", "posto", "pronto", "pronta", "pronti", "pronte", "alto", "alta", "alti", "alte", "stretto", "stretta", "sotto", "così", "subito", "proprio", "certo", "certa", "corto", "corta", "brutto", "brutta", "brutti", "brutte", "quasi", "spesso", "tanto", "tanta", "tanti", "tante", "lontano", "vicino", "pieno", "piena", "diverso", "diversa", "perso", "persa", "chiuso", "chiusa", "aperto", "aperta"}),
        ("imperfetto", r"\b(?:era|erano|eri|avevo|aveva|avevano|\w{2,}(?:ava|avano|avi|avamo|eva|evano|evi|evamo|iva|ivano|ivi|ivamo))\b", {"A1"},
         {"cava", "lava", "fava", "brava", "bravi", "neva", "leva", "oliva", "riva", "diva", "viva", "arriva", "schiava", "schiavi", "operai", "allievi", "chiavi", "ulivi", "divi", "attiva", "cattiva", "cattivi", "vivi", "nativa", "evitava"}),
        ("passato remoto", r"\b(?:fu|furono|ebbe|ebbero|fece|fecero|disse|dissero|andò|venne|vennero|vide|videro|prese|presero|mise|misero|rispose|\w{3,}(?:arono|irono|erono|ò|ì))\b", {"A1", "A2", "B1"},
         {"però", "ciò", "più", "così", "perché", "lì", "sì", "già", "giù", "può", "farò", "sarò", "avrò", "andrò", "dirò", "verrò", "starò", "vedrò", "potrò", "dovrò", "vorrò", "saprò"}),
        ("trapassato", r"\b(?:avevo|aveva|avevano|ero|era|erano)\s+(?:\w+\s+)?\w+(?:ato|ata|ati|ate|uto|uta|uti|ute|ito|ita|iti|ite|so|sto|tto)\b", {"A1", "A2"}, {"questo", "questa", "molto", "tutto", "tutta", "fatto", "presto", "subito", "tanto", "alto", "contento", "stanco", "pronto"}),
        ("congiuntivo imperfetto / condizionale", r"\b(?:fosse|fossero|avesse|avessero|\w{3,}(?:asse|assero|esse|essero|isse|issero|rei|rebbe|rebbero|remmo))\b", {"A1", "A2"},
         {"classe", "basse", "grasse", "tasse", "casse", "messe", "stesse", "stesso", "interesse", "promesse", "ammesse", "crei", "sei", "dei", "lei", "miei", "tuoi", "suoi"}),
    ],
    "pt": [
        ("pretérito perfeito", r"\b(?:foi|foram|fui|teve|tiveram|fez|fizeram|disse|disseram|veio|vieram|viu|viram|deu|deram|pôs|puseram|quis|houve|esteve|pôde|soube|\w{2,}(?:ou|aram|eu|eram|iu|iram|ei|aste|este|iste|emos|imos))\b", {"A1"},
         {"sou", "estou", "vou", "dou", "seu", "meu", "teu", "céu", "chapéu", "museu", "judeu", "europeu", "hebreu", "ateu", "troféu", "véu", "réu", "mausoléu", "eu", "deus", "sei", "rei", "lei", "hotel", "papel", "temos", "comemos", "bebemos", "vivemos", "podemos", "queremos", "sabemos", "vemos", "lemos", "fazemos", "dizemos", "vamos", "estamos", "somos", "damos", "ficamos", "falamos", "andamos", "chamamos", "chegamos", "olhamos", "gostamos", "moramos", "precisamos"}),
        ("pretérito imperfeito", r"\b(?:era|eram|tinha|tinham|estava|estavam|havia|ia|iam|\w{2,}(?:ava|avam|ávamos|ia|iam|íamos))\b", {"A1"},
         {"dia", "dias", "fria", "alegria", "família", "sabia", "maria", "bia", "via", "guia", "mia", "pia", "tia", "fantasia", "praia", "cavalaria", "padaria", "livraria", "lava", "cava", "fava", "brava", "oitava", "goiaba", "árvore", "polícia", "história", "memória", "glória", "vitória", "harmonia", "melodia", "poesia", "energia", "magia", "companhia", "cortesia", "noticia", "notícia", "fantasia", "sombria", "vazia", "cheia", "feia", "meia", "ideia", "areia", "cadeia", "aldeia", "baía"}),
        ("mais-que-perfeito", r"\b(?:tinha|tinham|havia|haviam)\s+(?:\w+\s+)?\w+(?:ado|ada|ados|adas|ido|ida|idos|idas|to|ta|tos|tas|sto|sta)\b", {"A1", "A2"}, {"muito", "muita", "muitos", "muitas", "tudo", "isto", "esta", "este", "estas", "estes", "certo", "perto", "cedo", "alto", "pronto", "pronta"}),
        ("futuro do subjuntivo / condicional / imperfeito do subjuntivo", r"\b(?:fosse|fossem|tivesse|tivessem|pudesse|quisesse|\w{3,}(?:asse|assem|esse|essem|isse|issem|aria|ariam|eria|eriam|iria|iriam))\b", {"A1", "A2"},
         {"classe", "interesse", "esse", "essa", "esses", "essas", "nesse", "nessa", "desse", "dessa", "disse", "promessa", "pressa", "travessa", "princesa", "mesa", "presa", "surpresa", "defesa", "despesa", "empresa", "aria", "maria", "padaria", "livraria", "feria", "feira", "série", "matéria", "bateria", "galeria", "história", "memória", "sapataria", "mercearia", "confeitaria", "leitaria", "cavalaria", "artilharia", "infantaria", "secretaria"}),
    ],
}


TENSES.update({
    "nl": [
        ("imperfectum", r"\b(?:was|waren|had|hadden|ging|gingen|kwam|kwamen|zei|zeiden|zag|zagen|gaf|gaven|nam|namen|liep|liepen|stond|stonden|zat|zaten|vond|vonden|viel|vielen|riep|riepen|dacht|dachten|wist|wisten|kon|konden|wilde|wilden|moest|moesten|mocht|deed|deden|lag|lagen|keek|keken|sprong|zwom|vloog|at|aten|dronk|sliep|werd|werden|\w{3,}(?:dde|tte)n?)\b", {"A1"},
         {"middel", "midden", "ratten", "katten", "hutten", "zitten", "bidden", "redden", "wedden", "kladde", "platte", "natte", "witte", "dikke", "zatte", "matte", "vette", "zotte", "slechte", "echte", "lichte", "rechte", "at"}),
        ("perfectum", r"\b(?:heb|hebt|heeft|hebben|ben|bent|is|zijn)\s+(?:\w+\s+){0,3}?ge\w+(?:d|t|en)\b", {"A1"},
         {"gezond", "gemeen", "gewend", "gehaast", "gerust", "geest", "gebied", "gedicht", "gezicht", "gevecht", "geschenk", "gerecht", "gebit", "gebed"}),
        ("plusquamperfectum", r"\b(?:had|hadden|was|waren)\s+(?:\w+\s+){0,3}?ge\w+(?:d|t|en)\b", {"A1", "A2"}, {"gezond", "gemeen", "gewend"}),
        ("conditionalis", r"\b(?:zou|zouden|zoudt)\b", {"A1"}, set()),
    ],
    "sv": [
        ("preteritum", r"\b(?:var|hade|gick|kom|sa|sade|såg|gav|tog|sprang|stod|satt|fann|föll|tänkte|visste|kunde|ville|skulle|åt|drack|sov|blev|låg|flög|simmade|\w{3,}ade)\b", {"A1"},
         {"parade", "fasade", "limonade", "promenade", "marmelade", "skade", "glade"}),
        ("perfekt", r"\b(?:har)\s+(?:\w+\s+){0,2}?(?:varit|haft|fått|gått|kommit|sagt|sett|tagit|gett|gjort|blivit|skrivit|ätit|druckit|sovit|\w{2,}(?:at|it))\b", {"A1"},
         {"mat", "hat", "lite", "vit", "hit", "dit", "bit", "tid", "skit", "flit", "split", "kvit"}),
        ("pluskvamperfekt", r"\bhade\s+(?:\w+\s+){0,2}?(?:varit|haft|fått|gått|kommit|sagt|sett|tagit|gett|gjort|blivit|\w{2,}(?:at|it|tt))\b", {"A1", "A2"},
         {"mat", "vit", "hit", "dit", "katt", "natt", "hatt", "lätt", "rätt", "nytt", "gott"}),
        ("konditionalis", r"\b(?:skulle|skulla)\b", {"A1"}, set()),
    ],
    "pl": [
        ("czas przeszły", r"\b\w{2,}(?:łem|łam|łeś|łaś|liśmy|łyśmy|liście|łyście|ł|ła|ło|li|ły)\b", {"A1"},
         {"stół", "kościół", "anioł", "orzeł", "popiół", "osioł", "kozioł", "szkoła", "pszczoła", "skała", "mała", "cała", "biała", "stała", "koło", "czoło", "zło", "szkło", "mało", "ciało", "masło", "wiosło", "krzesło", "hasło", "słońce", "jeśli", "gdyby", "zali", "mały", "cały", "biały", "stały", "ciepły", "wesoły", "goły", "miły", "miła", "miło", "mili", "śmiały", "śmiała", "zły", "zła", "aniołowie", "stoły", "szkoły", "skały", "pszczoły", "anioły", "orły", "osły", "koły", "dziupli", "chwili", "soli", "roli", "woli", "doli", "fali", "sali", "ali", "kroli", "króli", "wiele", "stali", "dali", "tyły", "sokół", "kocioł", "dół", "wół", "pół", "muł", "wiosła", "krzesła", "hasła", "tło", "pomału", "ciała", "dziupla"}),
        ("tryb przypuszczający", r"\b(?:\w+ł(?:a|o)?(?:bym|byś|by)|\w+(?:li|ły)by(?:śmy|ście)?|by(?:m|ś|śmy|ście)?\s+\w+ł[aoy]?)\b", {"A1"}, set()),
    ],
})


TENSES.update({
    "ru": [
        ("прошедшее время", r"\b\w{2,}(?:л|ла|ло|ли|лся|лась|лось|лись)\b", {"A1"},
         {"стол", "пол", "угол", "орёл", "осёл", "козёл", "котёл", "посол", "ангел", "пепел", "дятел", "футбол",
          "если", "сила", "пила", "стрела", "скала", "игла", "мгла", "земли", "корабли", "капли", "мысли", "угли",
          "мало", "тепло", "светло", "весело", "зло", "крыло", "село", "число", "масло", "кресло", "весло", "тело",
          "дело", "стекло", "зеркало", "начало", "дела", "тела", "стола", "стекла", "крыла", "сабли", "тепла",
          "смело", "бело", "кругло", "мило", "умело", "дотла", "пчела", "метла", "вола", "козла", "орла", "пола",
          "угла", "дула", "пили", "неужели", "сил", "сначала", "тяжело",
          "мило", "смело", "тепло", "светло", "кругло", "вола", "скала", "дотла",
          "назло", "добела", "докрасна", "горло", "весло"}),
        ("сослагательное наклонение (бы)", r"\b(?:бы|б)\b", {"A1", "A2"}, set()),
        ("причастия", r"\b\w+(?:вший|вшая|вшее|вшие|вшего|вшей|ющий|ющая|ющее|ющие|ящий|ящая|ящее|ящие|ащий|ащая|ащие|ущий|ущая|ущие|енный|енная|анный|анная)\b",
         {"A1", "A2"}, {"настоящий", "настоящая", "настоящее", "настоящие", "будущий", "будущая", "будущее",
                        "следующий", "следующая", "следующее", "следующие", "подходящий", "блестящий", "старинный",
                        "длинный", "длинная", "странный", "странная", "деревянный", "деревянная", "стеклянный",
                        "песчаный", "оловянный", "оловянная", "румяный", "пьяный", "пряный", "ранний", "туманный",
                        "уверенный", "уверенная", "военный", "военная", "огненный", "огненная", "священный",
                        "дорогой", "современный", "обыкновенный", "обыкновенная", "медленный", "медленная",
                        "кожаный", "кожаная", "серебряный", "серебряная", "соломенный", "соломенная", "бумажный"}),
    ],
    "ko": [
        ("과거형 (-았/었/했-)", _KO_PAST, {"A1"}, set()),
        ("대과거 (-았었/었었-)", _KO_PAST + "었", {"A1", "A2"}, set()),
    ],
    "hi": [
        ("भूतकाल (था, गया, किया…)", _HB + r"(?:था|थी|थे|थीं|गया|गई|गए|गयी|गये|किया|कहा|देखा|आया|आई|आए|आयी|लिया|दिया|बोला|बोली|बोले|पूछा|सोचा|पाया|रखा|चला|चली|चले|मिला|मिली|सुना|लगा|लगी|लगे|उठा|उठी|दौड़ा|दौड़ी|खाया|पिया|सोया|सोई|रोया|रोई|हँसा|हँसी)" + _HE
         # «चली जाती है», «उठा लेता है»: verbo compuesto en presente, no pasado.
         + r"(?!\s+(?:जाता|जाती|जाते|लेता|लेती|लेते|देता|देती|देते|रहता|रहती|रहते|है|हैं|हूँ|हो|कर|के|लो|लेना|देना|सकता|सकती|सकते|पाता|पाती|पाते)" + _HE + ")",
         {"A1"}, set()),
        ("पूर्ण भूतकाल (गया था, चुका था)", _HB + r"(?:\S+\s+)?(?:चुका|चुकी|चुके|गया|गई|गए|किया|लिया|दिया|आया|हुआ)\s+(?:था|थी|थे)" + _HE,
         {"A1", "A2"}, set()),
        ("संभावनार्थ / शर्त (होता, करता… तो)", _HB + r"(?:अगर|यदि)" + _HE + r".*\s(?:होता|होती|होते|करता|करती|करते|जाता|जाती|सकता|सकती)\s+(?:तो|था|थी)?" + _HE,
         {"A1", "A2"}, set()),
    ],
})


TENSES.update({
    "tr": [
        ("geçmiş zaman (-di, -miş)", r"\b\w+(?:[dt][ıiuü]|m[ıiuü]ş)(?:m|n|k|z|nız|niz|nuz|nüz|lar|ler|sın|sin|sun|sün|ım|im|um|üm|ız|iz|uz|üz)?\b",
         {"A1"}, {"kedi", "şimdi", "kendi", "yedi", "hindi", "ordu", "evet", "sadi", "iyi", "kadı", "mavi",
                  "kendim", "kendin", "kendini", "kendisi", "kendileri", "kendimiz", "yedisi", "kedim", "kedin", "kediler",
                  "keşiş", "yemiş", "yemişler", "dut", "artık", "haydi", "hadi", "adı", "adım", "adın", "kadın",
                  "kapıdı", "oldu", "üstü", "ütü", "otu", "tutu", "yurdu", "bütün", "dördü", "kurdu", "derdi",
                  "altı", "üstü", "ötü", "yeti", "seti", "şeftali", "orta", "ördü"}),
        ("miş-li geçmiş / hikâye (-mişti, -dıydı)", r"\b\w+(?:m[ıiuü]şt[ıiuü]|[dt][ıiuü]yd[ıiuü])\w*\b", {"A1", "A2"}, set()),
        ("şart (-se, -seydi)", r"\b\w+(?:s[ae]yd[ıi]|s[ae]ydı|s[ae]ydi)\w*\b", {"A1", "A2"}, set()),
    ],
    "el": [
        ("αόριστος / παρατατικός", r"\b(?:\w+(?:ούσε|ούσαν|ούσα|ούσες|ούσαμε|ησαν|ασαν|ισαν|ωσαν|ευσαν|ξαν|ψαν|σαμε)"
         r"|ήταν|ήμουν|ήσουν|ήμασταν|ήσασταν|είχε|είχαν|είχα|είπε|είπαν|είπα|ήρθε|ήρθαν|ήρθα|πήγε|πήγαν|πήγα|είδε"
         r"|είδαν|είδα|βρήκε|βρήκαν|έφυγε|έφυγαν|μπήκε|βγήκε|έκανε|έκαναν|έδωσε|έδωσαν|πήρε|πήραν|έφαγε|έφαγαν|ήπιε"
         r"|έμεινε|έπεσε|έτρεξε|άκουσε|φώναξε|ρώτησε|απάντησε|κοίταξε|άνοιξε|έκλεισε)\b",
         {"A1"}, {"γούσα"}),
        ("υπερσυντέλικος (είχε + -ει)", r"\bείχ(?:α|ες|ε|αμε|ατε|αν)\s+\w+ει\b", {"A1", "A2"}, set()),
        ("δυνητική (θα + παρατατικός)", r"\bθα\s+(?:ήταν|είχε|είχαν|ήθελ\w+|μπορούσ\w+|\w+ούσ\w+)\b", {"A1"}, set()),
    ],
    "uk": [
        ("минулий час", r"\b\w{2,}(?:ав|ив|ув|ла|ло|ли|лася|лося|лися|вся)\b|\b(?:пішов|прийшов|йшов|знайшов|вийшов|увійшов|зайшов|був|мав|міг|ніс|віз|ліг|сів|їв|пив|сидів|хотів|летів|вмів|умів|горів|терпів|шумів|висів|кипів|лежав|біг)\b", {"A1"},
         {"коли", "ніколи", "інколи", "сила", "сили", "мало", "тепло", "весело", "світло", "зло", "крило", "крила",
          "село", "села", "число", "числа", "масло", "тіло", "тіла", "діло", "скло", "скла", "дзеркало", "бджола",
          "школа", "школи", "стола", "орла", "козла", "ангела", "голова", "стріла", "стріли", "мила", "мило",
          "смело", "сміливо", "земля", "весла", "весло", "крісло", "крісла", "озера", "дотла", "кругло", "хмари",
          "любов", "кров", "слів", "лав", "плав", "ставив", "Київ", "прав", "дав", "пав", "мов", "чотирьох",
          "пилу", "мала", "малі", "стала", "бала", "кола", "коло", "метла", "мла", "гола", "голо", "біла", "біло",
          "цілу", "ціла", "ціло", "вола", "пила", "пили", "стріла", "скелі", "сокола", "дятла", "пекла", "тепла",
          "світла", "початку", "спочатку", "тяжко", "назло", "горло", "долі", "поля", "поле", "мили", "силу"}),
        ("умовний спосіб (би)", r"\b(?:би|б)\b", {"A1", "A2"}, set()),
        ("дієприслівники", r"\b\w+(?:ючи|ячи|учи|ачи|вши|вшись)\b", {"A1", "A2"}, {"вчи", "учи", "хочучи"}),
    ],
})


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
    if lang == "ko":
        return max(len(re.findall(r"[가-힣]", w)), 1)
    if lang == "hi":
        # Aksharas: vocales independientes + consonantes que no llevan virama detrás.
        return max(len(re.findall(r"[\u0904-\u0914]|[\u0915-\u0939\u0958-\u095f](?!\u093c?\u094d)", w)), 1)
    if lang == "el":
        # Dígrafos vocálicos (αι, ει, οι, ου, αυ, ευ) cuentan como una sílaba: grupos de vocales.
        return max(len(re.findall(r"[αεηιουωάέήίόύώϊϋΐΰ]+", w)), 1)
    return max(len(re.findall(r"[aeiouyäöüàèéìòùáíóúâêôãõåąęóıаеёиоуыэюяєії]+", w)), 1)


def readability(words, n_sentences, lang):
    wps = len(words) / n_sentences
    spw = sum(syllables(w, lang) for w in words) / len(words)
    if lang == "es":
        return "INFLESZ", 206.835 - 62.3 * spw - wps, ">80 muy fácil, 65-80 bastante fácil, 55-65 normal"
    if lang == "fr":
        return "Kandel-Moles", 207 - 1.015 * wps - 73.6 * spw, ">80 très facile, 70-80 facile, 60-70 assez facile"
    if lang == "de":
        return "Flesch-Amstad", 180 - wps - 58.5 * spw, ">80 sehr leicht, 70-80 leicht, 60-70 mittel"
    if lang == "it":
        letters = sum(len(w) for w in words)
        return "Gulpease", 89 + (300 * n_sentences - 10 * letters) / len(words), ">80 facile (lettore elementare), 60-80 media"
    if lang == "pt":
        return "Flesch-Martins", 248.835 - 1.015 * wps - 84.6 * spw, ">75 muito fácil, 50-75 fácil"
    if lang == "nl":
        return "Flesch-Douma", 206.84 - 0.93 * wps - 77 * spw, ">80 zeer makkelijk, 60-80 makkelijk, 50-60 vrij moeilijk"
    if lang == "pl":
        hard = sum(1 for w in words if syllables(w, lang) >= 4) / len(words)
        return "FOG-PL", 0.4 * (wps + 100 * hard), "<7 bardzo łatwy, 7-9 łatwy, 10-12 średni (más bajo = más fácil)"
    if lang == "sv":
        long_words = sum(1 for w in words if len(w) > 6) / len(words)
        return "LIX", wps + 100 * long_words, "<30 mycket lätt, 30-40 lätt, 40-50 medel (más bajo = más fácil)"
    if lang == "ru":
        return "Flesch-Oborneva", 206.835 - 1.3 * wps - 60.1 * spw, ">80 очень легко, 60-80 легко, 50-60 средне"
    if lang == "tr":
        return "Ateşman", 198.825 - 40.175 * spw - 2.610 * wps, "90-100 çok kolay, 70-89 kolay, 50-69 orta"
    if lang in ("ko", "hi", "el", "uk"):
        return "Sílabas/palabra", spw, "orientativo, sin índice estándar: comparar con los demás libros del mismo nivel"
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


def sentences_of(chapters, splitter=SENTENCE_END):
    return [s for ch in chapters for p in ch for s in splitter.split(p) if WORD.search(s)]


def fix_french(text):
    lines = []
    for line in text.split("\n"):
        line = re.sub(rf"(?<=[^\s«{NBSP}])[  {NBSP}]*([!?;:»]+)", lambda m: NBSP + m[1], line)
        line = re.sub(rf"«[  {NBSP}]*", "«" + NBSP, line)
        line = re.sub(rf"([!?;:])\{NBSP}?»", rf"\1{NBSP}»", line)
        lines.append(line)
    return "\n".join(lines)


def main():
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")   # ł, å… en consola cp1252
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
    heading = re.compile(cfg.get("heading") or rf"^{cfg['chapter']}\s+\d+\b.{{0,60}}$", re.I)
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

    s_src = sentences_of(src)
    s_dst = sentences_of(dst, SENTENCE_END_EL if args.lang == "el"
                         else SENTENCE_END_CASELESS if cfg.get("caseless") else SENTENCE_END)
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
    s_lo, s_hi = cfg.get("sentence_ratio", SENTENCE_RATIO)
    flag = "" if s_lo <= sr <= s_hi else "  FUERA DE RANGO"
    problems += bool(flag)
    print(f"Frases: {len(s_dst)}  media {m_dst:.1f}  (origen {m_src:.1f}, ratio {sr:.2f}, "
          f"objetivo {s_lo}-{s_hi}){flag}  máx {max(lengths)}")
    name, score, scale = readability(w_dst, len(s_dst), args.lang)
    print(f"Legibilidad {name}: {score:.1f}  ({scale})" if score < 10 else
          f"Legibilidad {name}: {score:.0f}  ({scale})")
    print(f"Palabras distintas: {len(set(w_dst))}")

    leftovers = sorted({w for w in w_dst if w in ENGLISH - ENGLISH_EXCEPT.get(args.lang, set())})
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
    if args.lang == "de":
        if "«" in text or "»" in text:
            typo.append(f"{text.count('«') + text.count('»')} guillemets « »: en alemán se usan „…“")
        if re.search(r"[“”].*[“”]", text) and "„" not in text:
            typo.append("comillas inglesas “…” sin „ de apertura: usa „…“")
        if re.search(r"^[-–] ", text, re.M):
            typo.append("diálogos con guion: en alemán se usan comillas „…“")
    if args.lang in ("it", "pt"):
        if re.search(r"^- ", text, re.M):
            typo.append("diálogos con guion corto: usa la raya (—) o « »")
        if "„" in text or "“" in text:
            typo.append("comillas „ “ ajenas al idioma: usa « » (it) o raya (pt)")
    if args.lang in ("nl", "pl", "sv") and ("«" in text or "»" in text):
        typo.append(f"{text.count('«') + text.count('»')} guillemets « » ajenos al idioma")
    if args.lang == "nl" and "„" in text:
        typo.append("comillas „ : en neerlandés usa “…”")
    if args.lang == "sv" and ("„" in text or "“" in text):
        typo.append("comillas „ “: en sueco se usa ”…” (la misma al abrir y cerrar)")
    if args.lang == "pl":
        if re.search(r"^[-–] ", text, re.M):
            typo.append("diálogos con guion corto: en polaco usa la pauza (—)")
        if "“" in text and "„" not in text:
            typo.append("comillas “…” sin „ de apertura: en polaco usa „…”")
    if args.lang in ("nl", "sv") and re.search(r"^[-—] ", text, re.M):
        typo.append("diálogos con raya: usa comillas (“…” en nl, ”…” en sv)")
    if args.lang == "ru":
        if re.search(r"^[-–] ", text, re.M):
            typo.append("diálogos con guion corto: en ruso usa la raya (—)")
        if "“" in text and "«" not in text:
            typo.append("comillas “…” sin « »: en ruso las citas van con «…»")
    if args.lang in ("ko", "hi"):
        if "«" in text or "»" in text or "„" in text:
            typo.append("comillas « » o „ ajenas al idioma: usa “…”")
    if args.lang == "hi" and text.count(".") > text.count("।"):
        typo.append(f"{text.count('.')} puntos «.» y {text.count('।')} dandas «।»: en hindi la frase acaba en «।»")
    if args.lang in ("tr", "uk") and re.search(r"^[-–] ", text, re.M):
        typo.append("diálogos con guion corto: usa la raya (—)")
    if args.lang == "uk" and "“" in text and "«" not in text:
        typo.append("comillas “…” sin « »: en ucraniano las citas van con «…»")
    if args.lang == "tr" and ("«" in text or "„" in text):
        typo.append("comillas « » o „: en turco las citas van con “…”")
    if args.lang == "el":
        if "?" in text:
            typo.append(f"{text.count('?')} signos «?»: en griego la interrogación es «;»")
        if re.search(r'["“”„]', text):
            typo.append("comillas \"“”„: en griego usa «…»")
    if args.lang == "uk" and re.search(r"[ыэъё]", text, re.I):
        typo.append("letras rusas (ы, э, ъ, ё) en texto ucraniano")
    if args.lang in ("pt", "nl", "pl", "sv", "ru", "ko", "hi", "tr", "el", "uk") and re.search(r"[¿¡]", text):
        typo.append("signos ¿ ¡ (solo español)")
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
