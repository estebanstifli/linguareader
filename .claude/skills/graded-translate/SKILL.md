---
name: graded-translate
description: Traducir un libro graduado ya existente en content/graded-books a otro idioma (es, fr, de, it, pt…) con el mismo nivel MCER, en una versión natural y no literal, y verificarla. Usar cuando se pida traducir un libro graduado o crear su versión en otro idioma.
---

# Traducir un libro graduado

Objetivo: que el libro parezca **escrito originalmente** en el idioma destino por un autor de
lecturas graduadas de ese nivel. El lector es un estudiante de ese idioma, no un hablante de inglés:
lo que importa es que el texto sea natural, ameno y del nivel, no que se parezca al inglés.

## Entrada
Libro (ruta o título del catálogo de `content/graded-books/README.md`) + idioma destino (código o
nombre: `fr`, «francés»…). El **nivel es el del original** y no se cambia.
- Si el libro no está en el catálogo, para y dilo.
- Si ya hay fila de ese libro en ese idioma en la tabla «Traducciones», no lo repitas: avísalo.
- Variedad por defecto: español peninsular estándar (con *vosotros*, sin regionalismos marcados),
  francés de Francia, alemán estándar, italiano estándar, portugués europeo; otra si se pide.

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
- **Tratamiento** (tú/usted, tu/vous, du/Sie): decídelo por relación antes de escribir y mantenlo.
  Rey → súbdito: tú/tu; súbdito → rey: usted/vous + «Majestad» / « Votre Majesté »; amigos y
  familia: tú/tu; desconocidos adultos: usted/vous.
- **Nombres y títulos**: usa la forma tradicional del destino cuando exista, la de las traducciones
  del original y no la de una película (*El sastrecillo valiente*, *Le Vaillant Petit Tailleur*,
  *Caperucita Roja*, *Le Petit Chaperon rouge*, *Blancanieves*, *Blanche-Neige*, *Hansel y Gretel*,
  *Pinocho*, *Garfio*, *Crochet*). Los nombres inventados en nuestra adaptación se conservan, salvo que
  suenen raros en el destino.
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

Otros idiomas: sigue el mismo criterio (A1 presente; A2 el pasado narrativo habitual del idioma, como
el *Präteritum* en alemán o el *passato prossimo* en italiano). Vocabulario: el más frecuente del
destino y, si una palabra clave pasa del nivel, explícala con el contexto como hace el original.
Extensión: no se fuerza. En A1/A2 el español sale algo más corto que el inglés (omite sujetos y
auxiliares como *do not*) y el francés, parecido (*l'arbre* cuenta como una palabra); en B1/B2 ambos
tienden a alargarse. Nunca recortes ni añadas escenas para ajustar la cifra.

## Formato del fichero
- Ruta: `content/graded-books/<idioma>/<NIVEL>/<slug>.txt`, con el slug del título traducido en
  minúsculas, sin acentos y con guiones (`es/A1/el-sastrecillo-valiente.txt`).
- Cabecera de capítulo (la reconoce `TextExtractor`): `Capítulo N: Título` (es, pt),
  `Chapitre N : Titre` (fr), `Kapitel N: Titel` (de), `Capitolo N: Titolo` (it).
- Última línea: `Fin` (es, fr), `Ende` (de), `Fine` (it), `Fim` (pt).
- Diálogos según la norma del idioma:
  - **es**: raya (`—`), nunca comillas: `—¡Siete! —dice—. ¡Siete de un golpe!` Con `¿?` y `¡!` de
    apertura siempre. Carteles y citas: comillas latinas («SIETE DE UN GOLPE»).
  - **fr**: guillemets con inciso invertido: `« Sept ! dit-il. Sept d'un coup ! »`. Escribe los
    espacios normales y deja que `--fix-typography` ponga los no separables (antes de `; : ! ? »` y
    después de `«`).
  - **de**: „…“; **it**: «…» o raya; **pt**: raya.
- Párrafos separados por una línea en blanco, como el original.

## Proceso (cada lectura y reescritura cuesta tokens)
1. Lee la fila del libro en el catálogo, su línea del glosario y el libro completo (A1/A2 de una
   vez; B1/B2 por bloques de 3–4 capítulos con `Read` y `offset`).
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
5. **Revisión de naturalidad** (obligatoria y barata): relee el capítulo 1 y otro al azar como un
   lector nativo y busca calcos, sujetos sobrantes, `dice` repetido, tratamiento incoherente o frases
   que «suenan a traducción». Corrige con un script de reemplazos puntuales (escrito con Write en el
   scratchpad, `assert t.count(a) == 1`), sin retraducir el libro. Vuelve a verificar.
6. Solo cuando todo pasa, actualiza `content/graded-books/README.md`:
   - tabla **Traducciones** del catálogo: fichero, título, nivel, palabras, palabras/frase,
     legibilidad (índice y valor), fichero de origen y licencia («Traducción propia de la adaptación
     propia»);
   - glosario: una línea `**<Título traducido>** (<idioma>)` con las palabras del destino que pasan
     del nivel.

## Derechos
La traducción parte de nuestra adaptación (texto propio), así que hereda su licencia. No uses
traducciones publicadas de la obra original ni de películas: solo los nombres y títulos
tradicionales, que no tienen derechos.
