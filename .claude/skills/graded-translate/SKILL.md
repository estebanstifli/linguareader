---
name: graded-translate
description: Traducir un libro graduado ya existente en content/graded-books a otro idioma (es, fr, de, it, pt…) con el mismo nivel MCER, en una versión natural y no literal, y verificarla. Usar cuando se pida traducir un libro graduado o crear su versión en otro idioma.
---

# Traducir un libro graduado

Objetivo: que el libro parezca **escrito originalmente** en el idioma destino por un autor de
lecturas graduadas de ese nivel. El lector es un estudiante de ese idioma, no un hablante de inglés:
lo que importa es que el texto sea natural, ameno y del nivel, no que se parezca al inglés.

## Entrada
Idioma destino (código o nombre: `de`, «alemán»…) + libro opcional (ruta o título del catálogo de
`content/graded-books/README.md`). El **nivel es el del original** y no se cambia.
- **Fichero de control del idioma**: `content/graded-books/<idioma>/README.md` (formato y cabecera por
  idioma en `tools/graded_langs.py`). Tiene una tabla «Libros» con una fila por libro (⬜ pendiente,
  ✅ hecho con fichero, título y métricas) y el glosario de ese idioma. Es la memoria del proyecto: el
  historial se borra entre libros.
- **Sin libro**: toma la primera fila ⬜ de esa tabla. **Con libro**: busca su fila; si está ✅, no lo
  repitas y avísalo; si no tiene fila, añádela al final (⬜) antes de empezar; si el idioma no tiene
  fichero, créalo con `python -I -c "import sys; sys.path.insert(0,'tools'); import graded_langs as g;
  g.write('<idioma>', g.plan_rows(), {})"`.
- Si el libro no está en el catálogo inglés, para y dilo.
- Variedad y tipografía por defecto: las que indica la cabecera del fichero de control (español
  peninsular con *vosotros*, francés de Francia, alemán estándar, italiano estándar, portugués europeo);
  otra solo si se pide.

## Traducir la escena, no la frase
- **Se mantiene**: trama, personajes, orden de las escenas, información de cada párrafo, número de
  capítulos y **número de párrafos por capítulo (1:1)**, que permite la vista bilingüe por bloques de
  la app. Dentro de un párrafo hay libertad total: fusionar, dividir o reordenar frases.
- **Se adapta** todo lo que en el destino no suena natural:
  - Sujetos repetidos: en español e italiano el sujeto se omite cuando se sobreentiende («Abre la
    ventana», no «Él abre la ventana»).
  - `says` en cada línea: en A1/A2 varía con verbos sencillos (*dice, pregunta, grita, responde* /
    *dit, demande, crie, répond*); en francés, inciso con inversión (`dit-il`).
  - Posesivos con partes del cuerpo y ropa: «abre los ojos», « il ouvre les yeux ».
  - Pasiva inglesa → *se* / *on* / activa. Nada de cadenas de gerundios.
  - Onomatopeyas e interjecciones del idioma: *Bang!* → «¡Pum!» / « Paf ! » / „Peng!“;
    *Whoosh!* → «¡Zas!» / « Hop ! »; *Hey!* → «¡Eh!» / « Hé ! »; *Hmm* → «Mmm».
  - Fórmulas, saludos, insultos suaves y exclamaciones: equivalentes de uso real
    («¡Buenos días, amigo!», « Bonjour, l'ami ! »).
  - Lemas, estribillos, canciones y rimas: **recréalos** para que tengan ritmo (y rima, si la había)
    en el destino. Decide la versión una vez y repítela igual en todo el libro.
  - Juegos de palabras: recrea uno equivalente; si no hay, cambia el chiste por otro del mismo tono.
- **Falsos amigos y calcos** que hay que evitar (es/fr): *realise* → darse cuenta / se rendre
  compte; *actually* → en realidad / en fait; *eventually* → al final / finalement; *embarrassed* →
  avergonzado / gêné; *sensible* → sensato / raisonnable; *library* → biblioteca / bibliothèque;
  *assist* → ayudar / aider; *ignore* → no hacer caso / ne pas faire attention; *at last* → por fin /
  enfin. Prefiere siempre la palabra frecuente del destino a la que se parece al inglés.
- **Tratamiento** (tú/usted, tu/vous, du/Sie, tu/Lei, tu/você–o senhor): decídelo por relación antes
  de escribir y mantenlo. Rey → súbdito: tú/tu/du; súbdito → rey: usted/vous/Sie/Lei/Vossa Majestade
  + «Majestad» / « Votre Majesté » / „Majestät“ / «Maestà»; amigos y familia: tú/tu/du; desconocidos
  adultos: usted/vous/Sie/Lei/o senhor–a senhora. En portugués europeo el *você* es menos cortés que
  *o senhor*; en italiano *Lei* se escribe con mayúscula. En neerlandés *jij*/*u* (súbdito → rey: *u* + «Majesteit»); en polaco
  *ty*/*pan, pani* (súbdito → rey: «Wasza Wysokość» / «Najjaśniejszy Panie» con 3.ª persona); en sueco
  *du* casi siempre, «Ers Majestät» al rey.
- **Nombres y títulos**: usa la forma tradicional del destino cuando exista, la de las traducciones
  del original y no la de una película (*El sastrecillo valiente*, *Le Vaillant Petit Tailleur*, *Das
  tapfere Schneiderlein*, *Il piccolo sarto coraggioso*, *O Alfaiate Valente*; *Caperucita Roja*, *Le
  Petit Chaperon rouge*, *Rotkäppchen*, *Cappuccetto Rosso*, *O Capuchinho Vermelho*; *Blancanieves*,
  *Blanche-Neige*, *Schneewittchen*, *Biancaneve*, *Branca de Neve*; *Hansel y Gretel*, *Hänsel und
  Gretel*; *Pinocho*, *Pinocchio*, *Pinóquio*; *Garfio*, *Crochet*, *Hook*, *Uncino*, *Gancho*; *El gato con
  botas*, *Le Chat botté*, *Der gestiefelte Kater*, *Il gatto con gli stivali*, *O Gato das Botas*; en nl/pl/sv: *Het dappere snijdertje*, *Dzielny krawczyk*, *Det tappra lilla
  skräddaren*; *Roodkapje*, *Czerwony Kapturek*, *Rödluvan*; *Sneeuwwitje*, *Królewna Śnieżka*,
  *Snövit*; *Assepoester*, *Kopciuszek*, *Askungen*; *De gelaarsde kat*, *Kot w butach*, *Mästerkatten
  i stövlar*; *Pinokkio*, *Pinokio*, *Pinocchio*). Los
  nombres inventados en nuestra adaptación se conservan, salvo que suenen raros en el destino.
- Si la obra original está escrita en el idioma destino (Perrault, Leprince de Beaumont o Verne al
  francés; Collodi al italiano; Grimm al alemán), puedes usar sus fórmulas célebres cuando la
  adaptación inglesa tenga esa escena y el nivel lo permita (anótalas en el glosario). No copies
  traducciones publicadas: pueden tener derechos del traductor.

## Nivel en el idioma destino
El nivel MCER no cambia, pero la gramática de cada nivel es la del idioma destino:

| | A1 | A2 | B1 | B2 |
|---|---|---|---|---|
| **es** | presente; `ir a` + inf.; `poder`/`querer`/`tener que` + inf.; imperativo. **Sin** pasados, perfectos ni subjuntivo («Quiero que vengas» → «Ven conmigo») | indefinido + imperfecto para narrar; perfecto en el diálogo; futuro. **Sin** pluscuamperfecto ni imperfecto de subjuntivo; presente de subjuntivo solo en fórmulas (*¡Que…!*, *ojalá*) | + pluscuamperfecto, condicional, presente de subjuntivo, *si tuviera…*, estilo indirecto | todo: *hubiera* + participio, subjuntivo pleno, perífrasis |
| **fr** | présent; futur proche; impératif. **Sin** passé composé, imparfait, conditionnel ni subjonctif | passé composé + imparfait para narrar; futur simple. **Sin** passé simple ni plus-que-parfait; subjonctif solo con *il faut que* | + plus-que-parfait, conditionnel, subjonctif présent; se narra en passé composé (sin passé simple) | todo; passé simple admitido como tiempo narrativo literario (elige uno por libro) |
| **de** | Präsens; Modalverben (*kann, will, muss, darf*); Imperativ; *möchte*. **Sin** Präteritum, Perfekt, Plusquamperfekt ni Konjunktiv II (ni *würde*); Nebensätze solo con *weil, dass, wenn* | Präteritum como tiempo narrativo (*sagte, ging, war, hatte*); Perfekt en el diálogo; Futur con *werden*; *würde* + inf. solo en cortesías. **Sin** Plusquamperfekt ni Konjunktiv II pleno; Relativsätze sencillos | + Plusquamperfekt, Konjunktiv II (*hätte, wäre, könnte*), Passiv, indirekte Rede con Indikativ, Nebensätze de todo tipo | todo: Konjunktiv I en estilo indirecto, Partizipialkonstruktionen, Passiv complejo |
| **it** | presente; *stare* + gerundio; *andare a* + inf.; *potere/volere/dovere* + inf.; imperativo. **Sin** passato prossimo, imperfetto, futuro, condizionale ni congiuntivo («Voglio che venga» → «Vieni con me») | passato prossimo + imperfetto para narrar; futuro semplice; condizionale solo *vorrei*. **Sin** passato remoto, trapassato ni congiuntivo (salvo fórmulas: *magari*, *che Dio…*) | + trapassato prossimo, condizionale, congiuntivo presente e imperfetto en *se…*, discorso indiretto; se narra en passato prossimo (sin passato remoto) | todo; passato remoto admitido como tiempo narrativo literario (elige uno por libro) |
| **nl** | presens; *gaan* + inf.; *kunnen/willen/moeten/mogen* + inf.; imperatief. **Sin** imperfectum, perfectum ni *zou*; bijzinnen solo con *omdat, dat, als* | imperfectum como tiempo narrativo (*zei, ging, was, had*); perfectum en el diálogo; *zullen* para el futuro. **Sin** plusquamperfectum ni *zou* (salvo cortesía: *zou je…?*) | + plusquamperfectum, *zou* + inf., lijdende vorm, indirecte rede, bijzinnen de todo tipo | todo |
| **pl** | presente (aspecto imperfectivo); futuro con *będę* + inf.; *chcieć/móc/musieć* + inf.; imperativo. **Sin** czas przeszły ni tryb przypuszczający (*-by*); aspecto perfectivo solo en imperativo y en el futuro simple de verbos muy frecuentes (*zobaczę, zrobię*) | czas przeszły (ambos aspectos) como tiempo narrativo; futuro simple y compuesto. **Sin** tryb przypuszczający (salvo *chciałbym*); participios adjetivales sencillos | + tryb przypuszczający, *gdyby*, mowa zależna, participios (*czytający, przeczytany*), voz pasiva | todo: imiesłowy przysłówkowe (*czytając, przeczytawszy*), registro literario |
| **sv** | presens; *ska* + inf.; *kan/vill/måste* + inf.; imperativ. **Sin** preteritum, perfekt ni *skulle*; bisatser solo con *för att, att, när, om* | preteritum como tiempo narrativo (*sa, gick, var, hade*); perfekt en el diálogo; futuro con *ska/kommer att*. **Sin** pluskvamperfekt ni *skulle* (salvo cortesía: *skulle du kunna…?*) | + pluskvamperfekt, *skulle* + inf., s-passiv, indirekt tal, bisatser de todo tipo | todo |
| **pt** | presente; *ir* + inf.; *estar a* + inf.; *poder/querer/ter de* + inf.; imperativo. **Sin** pretérito perfeito, imperfeito, futuro, condicional ni subjuntivo («Quero que venhas» → «Vem comigo») | pretérito perfeito + imperfeito para narrar; futuro con *ir* + inf. (o futuro simples). **Sin** mais-que-perfeito, condicional ni subjuntivo (presente del subjuntivo solo en fórmulas: *oxalá*, *que…!*) | + mais-que-perfeito composto (*tinha feito*), condicional, presente e imperfeito do subjuntivo, futuro do subjuntivo (*se tiveres*), discurso indireto | todo: mais-que-perfeito simples literario, infinitivo pessoal complejo, subjuntivo pleno |

Vocabulario: el más frecuente del destino y, si una palabra clave pasa del nivel, explícala con el
contexto como hace el original.
Extensión: no se fuerza. En A1/A2 el español sale algo más corto que el inglés (omite sujetos y
auxiliares como *do not*) y el francés, parecido (*l'arbre* cuenta como una palabra); en B1/B2 ambos
tienden a alargarse. Nunca recortes ni añadas escenas para ajustar la cifra.

## Formato del fichero
- Ruta: `content/graded-books/<idioma>/<NIVEL>/<slug>.txt`, con el slug del título traducido en
  minúsculas, sin acentos ni diéresis (ä→a, ß→ss, ç→c), y con guiones
  (`es/A1/el-sastrecillo-valiente.txt`, `de/A1/das-tapfere-schneiderlein.txt`).
- Cabecera de capítulo (la reconoce `TextExtractor`): `Capítulo N: Título` (es, pt),
  `Chapitre N : Titre` (fr), `Kapitel N: Titel` (de, sv), `Capitolo N: Titolo` (it),
  `Hoofdstuk N: Titel` (nl), `Rozdział N: Tytuł` (pl).
- Última línea: `Fin` (es, fr), `Ende` (de), `Fine` (it), `Fim` (pt), `Einde` (nl), `Koniec` (pl),
  `Slut` (sv). Slug sin diacríticos también en pl/sv (ł→l, ą→a, å→a, ö→o).
- Diálogos según la norma del idioma:
  - **es**: raya (`—`), nunca comillas: `—¡Siete! —dice—. ¡Siete de un golpe!` Con `¿?` y `¡!` de
    apertura siempre. Carteles y citas: comillas latinas («SIETE DE UN GOLPE»).
  - **fr**: guillemets con inciso invertido: `« Sept ! dit-il. Sept d'un coup ! »`. Escribe los
    espacios normales y deja que `--fix-typography` ponga los no separables (antes de `; : ! ? »` y
    después de `«`).
  - **de**: „…“ (comillas alemanas, nunca `"` ni « »), inciso con coma: `„Sieben!“, sagt er. „Sieben auf
    einen Streich!“`. Ortografía de 2006 con `ß` (*groß, Straße*); sustantivos con mayúscula.
  - **it**: caporali « » sin espacios interiores: `«Sette!», dice. «Sette in un colpo!»`; también se
    admite la raya al inicio de línea, pero elige una norma para todo el libro. Nunca `"`.
  - **pt**: travessão (`—`) al inicio del diálogo, como en español pero **sin** `¿` ni `¡`:
    `— Sete! — diz ele. — Sete de uma vez!`. Citas y carteles con «…».
  - **nl**: comillas “…” con inciso con coma: `“Zeven!” zegt hij. “Zeven in één klap!”`. Nunca `"`,
    « » ni „.
  - **pl**: pauza (`—`) al inicio del diálogo, como en portugués: `— Siedem! — mówi. — Siedem za
    jednym zamachem!`. Citas y carteles con „…”.
  - **sv**: comillas suecas ”…” (la **misma** comilla ” al abrir y cerrar): `”Sju!” säger han. ”Sju i
    ett slag!”`. Nunca `"`, « », „ ni “.
- Párrafos separados por una línea en blanco, como el original.

## Proceso (cada lectura y reescritura cuesta tokens)
- Si una herramienta o un comando es denegado, no lo reintentes de otra forma: sigue con Read/Write
  y el checker.

1. Saca con `grep -n "<slug>\|<Título>" content/graded-books/README.md` solo la fila del catálogo y
   su entrada del glosario inglés (no leas el README entero); lee las primeras 10 líneas del fichero
   de control del idioma y el libro completo
   (A1/A2 de una vez; B1/B2 por bloques de 3–4 capítulos con `Read` y `offset`).
2. Antes de escribir decide y no cambies: título, nombres, tratamiento, lemas y estribillos, y
   onomatopeyas recurrentes.
3. Escribe:
   - **A1/A2**: el libro completo de una vez con Write, párrafo por párrafo del original.
   - **B1/B2**: por bloques de 3–4 capítulos en el scratchpad (`<slug>.<idioma>.part1.txt`…),
     leyendo solo los capítulos de ese bloque; al final, `cat` al fichero definitivo (solo el último
     bloque lleva el cierre).
4. Verifica:
   `PYTHONIOENCODING=utf-8 python -I tools/translation_check.py <origen> <traducción> --lang <xx> --fix-typography --show-long 5`
   - Capítulos y párrafos por capítulo idénticos al origen, cierre correcto.
   - Ratio de palabras y de media de frase dentro de rango; legibilidad acorde al nivel
     (orientativo: en A1/A2, INFLESZ ≥ 70 o Kandel-Moles ≥ 75).
   - Sin restos en inglés ni avisos de tipografía.
   - Tiempos marcados `REVISAR`: comprueba cada forma, porque hay falsos positivos (adjetivos como
     *assis* o *fatigué*, nombres en *-aba*). Las formas reales se reformulan.
5. **Revisión de naturalidad** (obligatoria): revisa el capítulo 1 y otro al azar **sobre el texto que
   ya escribiste y tienes en el contexto, sin volver a leerlo con Read**, como un lector nativo, y busca calcos, sujetos sobrantes, `dice` repetido, tratamiento incoherente o frases
   que «suenan a traducción». Corrige con un script de reemplazos puntuales (escrito con Write en el
   scratchpad, `assert t.count(a) == 1`), sin retraducir el libro. Vuelve a verificar.
6. Solo cuando todo pasa, actualiza **`content/graded-books/<idioma>/README.md`** (no el README
   general):
   - la fila del libro: ⬜ → ✅ y rellena `Fichero` (`` `<idioma>/<NIVEL>/<slug>.txt` ``), `Título`,
     `Palabras` (con punto de miles: 3.154), `Palabras/frase` (coma decimal: 5,7) y `Legibilidad`
     (índice y valor que imprime el checker: «INFLESZ 92», «Flesch-Amstad 85», «Gulpease 78»,
     «Flesch-Martins 88», «Flesch-Douma 84», «FOG-PL 5», «LIX 24»); `Notas` solo si hay algo que recordar (tratamiento, canción, tiempo
     narrativo elegido). Las celdas vacías se dejan vacías; no toques las demás filas;
   - glosario (sección «Vocabulario fuera de nivel»): una entrada `- **<Título traducido>**: ...` con
     las palabras del destino que pasan del nivel, en el mismo orden que la tabla.
   Comprueba con `python -I tools/graded_langs.py <idioma>` que la fila se lee (cuenta ✅ y glosarios).

## Derechos
La traducción parte de nuestra adaptación (texto propio), así que hereda su licencia. No uses
traducciones publicadas de la obra original ni de películas: solo los nombres y títulos
tradicionales, que no tienen derechos.
