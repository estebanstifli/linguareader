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

Los libros empaquetados viven en `app/src/main/assets/books/`. Los packs descargables (Fase 4)
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

## Validación

`./gradlew :core:test` ejecuta `SampleContentTest` sobre los libros empaquetados: parseo, carpeta =
id, existencia y sha256 de imágenes, `alt` presente, cobertura de traducción al español y presencia
de cada expresión del glosario en el texto.

Tras cambiar imágenes: `python tools/generate_demo_images.py` (regenera y actualiza los `sha256`).
