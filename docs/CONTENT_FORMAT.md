# Formato de contenido (packs de libros) — `formatVersion: 1`

Esquema formal: [`content-pack.schema.json`](content-pack.schema.json) (JSON Schema 2020-12).
Parser y validación: `core/src/main/kotlin/com/linguareader/core/content/BookManifestParser.kt`.

## Estructura de carpetas

```
books/
  index.json                       { "formatVersion": 1, "books": ["<id>", …] }
  <id>/                            la carpeta se llama exactamente como el id del libro
    manifest.json
    images/…                       rutas relativas a la carpeta del libro
```

Los libros empaquetados viven en `app/src/main/assets/books/`. Las bibliotecas por idioma se
descargan como EPUB (ver «Bibliotecas por idioma» más abajo). Los packs descargables (Fase 4)
usarán la misma estructura en almacenamiento interno, sin necesidad de actualizar el APK.

## Manifiesto

```jsonc
{
  "formatVersion": 1,                    // obligatorio; versiones desconocidas se rechazan
  "id": "en-a1-the-red-kite",            // [A-Za-z0-9][A-Za-z0-9._-]*
  "title": "The Red Kite",
  "description": "…",                    // opcional
  "learningLanguage": "en",              // BCP-47
  "cefrLevel": "A1",                     // A1…C2
  "genre": "everyday",                   // opcional, filtro del catálogo
  "authors": ["…"],
  "adaptationCredit": null,              // quién adaptó/graduó el texto, si aplica
  "license": { "id": "CC-BY-4.0", "name": "…", "url": "…" },   // id SPDX o LicenseRef-*
  "sourceAttribution": "…",              // obligatorio: procedencia del texto
  "cover": { "path": "images/cover.png", "alt": "…", "credit": "…", "sha256": "…" },
  "chapters": [
    {
      "id": "ch1",
      "title": "A Windy Day",
      "titleTranslations": { "es": "Un día de viento" },
      "passages": [
        {
          "id": "ch1-p1",                // único en todo el libro
          "image": { "path": "images/ch1-p1.png", "alt": "…", "credit": "…", "sha256": "…" }, // opcional
          "sentences": [
            { "id": "s1", "text": "Tom is eight years old.",
              "translations": { "es": "Tom tiene ocho años.", "fr": "Tom a huit ans." } },
            { "id": "s2", "text": "He lives at No. 12.",
              "speech": "He lives at number twelve." }   // opcional: lo que debe leer la voz
          ]
        }
      ]
    }
  ],
  "glossary": [
    { "id": "wake-up", "lemma": "wake up", "kind": "phrasal_verb", "pos": "verb",
      "forms": ["wake up", "wakes up", "woke up"],
      "translations": { "es": "despertarse", "fr": "se réveiller" },
      "examples": [ { "text": "I wake up at seven.", "translations": { "es": "Me despierto a las siete." } } ] }
  ],
  "pronunciations": { "Dr.": "doctor", "Ms.": "miz" }   // opcional: léxico para la voz
}
```

### Reglas

- **Frases explícitas.** El texto de un pasaje es la concatenación de sus frases (normalizadas a NFC,
  espacios ASCII colapsados) unidas por un espacio. No se segmenta automáticamente, lo que evita
  ambigüedades del tipo «Mr.». No cambies el texto de una frase publicada sin subir la versión del
  pack: los offsets de progreso y las alineaciones dependen de él.
- **Traducciones opcionales por idioma.** Si falta la traducción a un idioma, la app muestra «no
  disponible» y no inventa nada. El catálogo lista como «Traducciones» los idiomas que cubren todas las frases.
- **Glosario.** `forms` son formas superficiales en minúsculas (los apóstrofos tipográficos `’` se
  normalizan a `'`). Pueden abarcar varios tokens y se buscan contiguas dentro de la frase; gana la
  más larga. `kind`: `word` (por defecto), `phrasal_verb`, `expression`, `contraction`.
- **Tokenización.** Palabra = letras/dígitos/marcas combinantes, con `'`, `’` o `-` internos.
  En `fr`/`it`/`ca` se separan las elisiones (`l'école` → `l'` + `école`, `qu'est-ce` → `qu'` +
  `est-ce`). Una expresión como `qu'est-ce que` se reconoce igualmente porque se compara contra el
  texto original entre tokens.
- **Forma hablada (`speech`, `pronunciations`).** Opcionales y solo para la voz: el texto mostrado,
  sus tokens y offsets no cambian. `speech` sustituye a la frase entera ante el TTS (escríbela con las
  mismas palabras que el texto donde coincidan: así el resaltado por palabra sigue funcionando).
  `pronunciations` aplica en todo el libro (sin distinguir mayúsculas) y tiene prioridad sobre las
  reglas automáticas. Ver [SPEECH_NORMALIZATION.md](SPEECH_NORMALIZATION.md).
- **Media.** `path` relativo, sin `..`, sin `/` inicial ni `\`. Si hay `sha256` (hex, 64 caracteres),
  se verifica al cargar y una imagen alterada no se muestra. `alt` es obligatorio en la práctica (accesibilidad).
- **Derechos.** Solo historias propias, dominio público verificado o contenidos con licencia que
  permita adaptación y distribución. Una adaptación moderna de una obra de dominio público **no** es
  libre automáticamente: declara `adaptationCredit` y la licencia de la adaptación.

## Libros importados por el usuario

La importación ([IMPORT.md](IMPORT.md)) genera manifiestos con este mismo formato, guardados en
`filesDir/imported/<id>/`: `id` = `u-<16 hex del sha256 del fichero>` (el prefijo `u-` queda
reservado), `license.id` = `user-import`, `sourceAttribution` = nombre del fichero o URL, frases
segmentadas automáticamente, sin traducciones ni glosario, y media con `sha256`. No se distribuyen.

## Bibliotecas por idioma (packs descargables)

Los libros graduados de `content/graded-books/` se distribuyen como **un zip por idioma** en la release
`library-v1` (lo genera `tools/build_library.py`):

```
libraries.json                    catálogo: {formatVersion, libraries: {<idioma>: {version, asset, sizeBytes, sha256, books, words}}}
library-<idioma>-v<N>.zip
  library.json                    {formatVersion, language, version, books: [{id, file, title, authors, level, words, sizeBytes, sha256}]}
  books/<NIVEL>/<slug>.epub
```

- El catálogo también va en el APK (`assets/packs/libraries.json`); la app lo refresca desde la
  release para ver bibliotecas nuevas sin actualizarse.
- Cada EPUB 3 (`tools/build_epub.py`) declara `dc:language`, `dc:title`, `dc:creator`,
  `<meta property="schema:educationalLevel">A1</meta>` y `dc:subject` «CEFR A1» (el importador lee el
  nivel de cualquiera de los dos), portada con `properties="cover-image"`, `nav.xhtml` y `toc.ncx`, y un
  XHTML por capítulo con su título en `<h2>`.
- Al instalar, `LibraryPackManager` verifica el sha256 del zip y de cada EPUB y los importa con un id
  **estable** `u-<id>` (`u-lib-es-el-sastrecillo-valiente`): una versión nueva de la biblioteca
  conserva progreso, marcadores y vocabulario, reimporta solo los EPUB cambiados y borra los retirados.
- Portadas: ilustración por obra (`content/graded-books/covers/<slug>.webp`, `tools/make_covers.py`,
  OpenAI `gpt-image-2.5-flare`) compartida por todas las traducciones; el título y el nivel se
  componen por idioma (600×900, JPEG ≈ 90 KB).

## Narración pregrabada (opcional)

Un libro puede declarar una narración de calidad generada fuera del móvil: **una pista de audio continua** (mp3, con
sus voces, pausas y efectos ya grabados) y un **ASS de karaoke** (`{\k}`/`{\kf}` en centésimas) con el tiempo de cada
palabra. La app la descarga en segundo plano al abrir el libro y, cuando está lista, la usa en lugar del motor TTS
(en la siguiente pausa, en el mismo punto). Si no hay red, falla la descarga o el ASS no casa con el texto (< 90 % de
las palabras), se sigue con el TTS sin avisos.

- **En el manifiesto**: `"narration": { "audio": {url, sizeBytes, sha256}, "timings": {url, sizeBytes, sha256} }`.
  Solo URL `https`; una narración mal declarada se ignora sin invalidar el libro.
- **En el EPUB de la biblioteca** (lo escribe `tools/build_epub.py`; el prefijo `linguareader:` se declara en
  `<package prefix>` solo si hay narración, así los demás EPUB no cambian):
  ```xml
  <meta property="linguareader:narration-audio">https://…/narration-en-the-three-little-pigs-v1.mp3</meta>
  <meta property="linguareader:narration-audio-size">23878361</meta>
  <meta property="linguareader:narration-audio-sha256">…</meta>
  <meta property="linguareader:narration-timings">https://…/narration-en-the-three-little-pigs-v1.ass</meta>
  <meta property="linguareader:narration-timings-size">74040</meta>
  <meta property="linguareader:narration-timings-sha256">…</meta>
  ```
- **Texto**: el ASS no necesita ids. Sus palabras se casan con los tokens del libro (`NarrationAligner`: sin
  mayúsculas, puntuación ni diacríticos, con variantes de grafía y palabras omitidas o añadidas). Las palabras sin
  tiempo propio reparten el hueco entre sus vecinas.
- **Solo libros de la biblioteca** (`u-lib-…`): un EPUB importado por el usuario con estos `<meta>` no provoca descargas.
- **En el dispositivo**: `filesDir/narrations/<bookId>/` (nombres con el sha256); se borra con el libro.

Publicar (hoy, adjuntos de la release `library-v1` con nombres planos `narration-<idioma>-<slug>-v<N>.<ext>`):

```powershell
python -I tools\publish_narration.py --lang en --slug the-three-little-pigs --audio narracion.mp3 --ass narracion.ass --upload
python -I tools\build_library.py --lang en --upload      # el EPUB del libro cambia → la biblioteca sube de versión
```

`publish_narration.py` registra tamaño y sha256 en `content/graded-books/narrations.json` (versionado). Para migrar a
otro alojamiento basta con subir allí los mismos ficheros, cambiar `NARRATION_BASE_URL` en `tools/build_epub.py` y
regenerar las bibliotecas: la app reimporta solo los libros cambiados y conserva progreso, marcadores y vocabulario.

## Validación

`./gradlew :core:test` ejecuta `SampleContentTest` sobre los libros empaquetados: parseo, carpeta =
id, existencia y sha256 de imágenes, `alt` presente, cobertura de traducción al español y presencia
de cada expresión del glosario en el texto.

Tras cambiar imágenes: `python tools/generate_demo_images.py` (regenera y actualiza los `sha256`).
