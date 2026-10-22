# Importar libros del usuario

La app lee en voz alta y resalta **texto**, así que no necesita un visor para cada formato, solo
un **extractor** que convierta el documento en capítulos → párrafos (+ imágenes). Después,
`ImportedBookBuilder` segmenta frases, reparte pasajes y genera un `manifest.json` del mismo
formato que la biblioteca incluida ([CONTENT_FORMAT.md](CONTENT_FORMAT.md)). El lector, el TTS, el
resaltado, el progreso y los marcadores funcionan igual para un libro importado que para uno incluido.

## Formatos

| Formato | Extensiones | Cómo se lee | Dependencia |
|---|---|---|---|
| EPUB 2/3 | `.epub` | `container.xml` → OPF (metadatos, spine) → índice nav/NCX → XHTML. Imágenes y portada. | jsoup |
| Kindle sin DRM | `.mobi` `.prc` `.azw` `.azw3` | Base de datos Palm + descompresión PalmDOC (LZ77) → HTML. Imágenes por `recindex`/`kindle:embed`. | — |
| PDF con texto | `.pdf` | PdfBox-Android página a página; se quitan cabeceras/pies repetidos y números de página, y se unen párrafos y palabras partidos entre páginas. | PdfBox-Android |
| Word | `.docx` | `word/document.xml`; títulos por estilo («heading 1», en cualquier idioma de Word) o nivel de esquema; ignora texto borrado (control de cambios) y notas. | jsoup (XML) |
| LibreOffice | `.odt` | `content.xml` (`text:h`, `text:p`, listas, tablas) y `meta.xml`. | jsoup (XML) |
| RTF | `.rtf` | Intérprete mínimo de palabras de control (`\par`, `\'xx`, `\uN`, destinos ignorados). | — |
| FictionBook | `.fb2` `.fb2.zip` `.fbz` | Secciones anidadas, poemas, citas, imágenes base64; sin notas al pie. Respeta `encoding` (p. ej. windows-1251). | jsoup (XML) |
| HTML | `.html` `.htm` `.xhtml` | Cuerpo completo (un capítulo o un libro guardado). | jsoup |
| Página web | URL | Descarga explícita y modo lectura: se queda con el artículo principal (sin menús, pies, barras laterales ni comentarios). Sin imágenes remotas. | jsoup |
| Texto | `.txt` | Detección de codificación (BOM, UTF-8, windows-1252), líneas cortadas a mano, capítulos («Chapter IV», «Capítulo 3»...), cabecera y licencia de Project Gutenberg. | — |
| Markdown | `.md` | `#`–`###` = capítulo; se quitan marcas, enlaces y código. Front matter (`title`, `lang`). | — |
| Subtítulos | `.srt` `.vtt` | Sin tiempos ni etiquetas; los cues se agrupan en párrafos. Útil para estudiar el guion de una serie. | — |
| Texto compartido | — | «Compartir» texto desde otra app o «Pegar texto copiado». | — |

El formato se detecta **por el contenido** (firma del fichero) y, si no hay firma, por la
extensión: los gestores de ficheros a menudo envían `.fb2`, `.srt` o `.md` como
`application/octet-stream`.

### No soportados (con mensaje explicativo)

| Formato | Motivo | Alternativa que se sugiere |
|---|---|---|
| `.doc` (Word 97–2003) | Formato binario OLE2; Apache POI pesa más de 10 MB. | Guardar como DOCX. |
| `.djvu` | Casi siempre páginas escaneadas sin texto; DjVuLibre es GPL. | — (OCR en el futuro) |
| PDF escaneado | No hay capa de texto. | — (OCR en el futuro) |
| `.chm` | Compresión LZX; necesitaría chmlib nativo (LGPL). | Convertir a EPUB con Calibre. |
| KFX y cualquier libro con **DRM** | No se rompe ningún DRM. | Libros sin DRM. |
| MOBI con compresión HUFF/CDIC | Poco frecuente; diccionarios Huffman. | Convertir a EPUB. |
| `.cbz`/`.cbr`, audiolibros | No hay texto que leer. | — |

## Flujo

```
Selector del sistema (SAF) / «Abrir con…» / «Compartir» / URL / portapapeles
        │  PendingImports (intents) ─► ImportViewModel
        ▼
BookImportService.read*()   copia a caché + sha256 (id estable "u-<hash>")
        ▼
ExtractorRegistry           firma → extensión → DocumentExtractor
        ▼
ExtractedDocument           capítulos → párrafos/imágenes + metadatos (título, autor, idioma)
        ▼
Diálogo de revisión         título, autor, idioma de lectura, nivel MCER
        ▼
ImportedBookBuilder         SentenceSegmenter + pasajes de ~120–220 palabras → manifest.json
        ▼                   (validado con BookManifestParser, como el contenido incluido)
ImportedBookRepository      filesDir/imported/<id>/{manifest.json, summary.json, media/}
        ▼
CompositeBookRepository     catálogo = importados + incluidos
```

- **Id estable.** `u-` + 16 hex del sha256 del fichero: reimportar lo mismo reemplaza el libro y
  conserva el progreso guardado.
- **Escritura atómica.** Se escribe en una carpeta temporal y se renombra; nunca queda un libro a medias.
- **Memoria.** El catálogo usa `summary.json`; el `manifest.json` completo solo se carga al abrir el libro.
- **Límites.** 200 MB por fichero, 10 MB por página web y 64 MB por entrada de ZIP (protección contra ficheros «bomba»).
- **Red.** Solo la importación por URL usa la red, y siempre la inicia el usuario.

## Segmentación de frases

`core/text/SentenceSegmenter` usa el `BreakIterator` de frases del locale (ICU en Android) y
corrige sus fallos típicos: abreviaturas por idioma («Mr.», «Sra.», «p. ej.», «M.»), iniciales
(«J. R. R.») y cortes seguidos de minúscula. Las frases de más de 400 caracteres se parten por
`;`, `:` o `,`. Las frases sin palabras («* * *») se descartan.

## Limitaciones conocidas

- Los libros importados **no traen traducciones revisadas ni glosario**: se traducen con ML Kit en
  el dispositivo ([TRANSLATION.md](TRANSLATION.md)), mientras se lee o el libro entero al importarlo.
- El **idioma** se toma de los metadatos si existen. Si no, el usuario lo elige en el diálogo
  (no hay detección automática).
- **PDF:** la calidad depende de cómo se generó. Las maquetas a dos columnas o con notas al margen
  pueden mezclar texto. Las imágenes del PDF no se importan al modo lectura (sí se ven con «Ver original»).
- **Texto plano:** codificaciones antiguas distintas de windows-1252 (cp1251, Shift-JIS...) no se
  detectan. FB2, HTML y XML respetan su declaración de codificación.
- Imágenes SVG no se importan (Android no las decodifica con `BitmapFactory`).

## Ver el original

Los PDF importados conservan una **referencia** al fichero (no una copia): `originalUri` en `summary.json`,
con `takePersistableUriPermission` cuando el proveedor lo concede (selector de ficheros, carpetas elegidas).
Al pulsar el libro, `OpenModeSheet` ofrece **Modo lectura** (texto reconstruido: voz, resaltado, traducción) o
**Ver original**; la elección puede recordarse (`UserPreferences.originalOpenMode`, Ajustes › Examinar archivos).

- Visor (`ui/viewer/`): `PdfRenderer` del sistema (sin dependencias). Un único hilo de renderizado, caché LRU
  de páginas (≤ 64 MB), páginas con su proporción exacta antes de dibujarse (sin saltos), zoom de 1× a 4× con
  dos dedos o doble toque (durante el gesto solo se escala la imagen; al soltar se re-maqueta y se re-dibuja nítido)
  y desplazamiento horizontal con zoom. Recuerda la última página.
- Si el fichero se movió, se borró o caducó el permiso («Abrir con…» desde otras apps no suele darlo persistente),
  el visor lo dice y ofrece **Buscar el fichero**, que guarda la nueva referencia.
- Un PDF escaneado (sin texto) no se importa, pero el aviso ofrece **Ver PDF**; en Examinar archivos, un PDF se
  puede **Solo ver** sin importarlo.
- Al borrar el libro se libera su permiso persistente (Android limita cuántos guarda cada app).

### Voz y traducción sobre el PDF

«Ver original» de un libro importado abre el **lector** en modo PDF (`ReaderPdfContent`): el mismo
`ReaderViewModel` (voz, reproductor, progreso, tarjetas de palabra) con las páginas en lugar del texto. El botón
de la barra superior alterna PDF ↔ texto sin perder el punto de lectura.

- `PdfWordExtractor` saca con PdfBox cada palabra con su caja (fracciones de la página, válidas a cualquier zoom),
  con los mismos ajustes que la importación, así que el orden coincide con el del texto del libro.
- `WordBoxAligner` empareja tokens y palabras con una ventana voraz: lo que solo está en el PDF (cabeceras, pies,
  números de página) se salta; las palabras partidas por guion reciben dos cajas; un salto lejano solo se acepta si
  lo confirman las palabras siguientes. Un token sin pareja se queda sin caja (no se inventa su posición).
- `PdfTextLayouts` lo calcula una vez en segundo plano justo tras importar (o al abrir el original si falta) y lo
  guarda en `pdf-words.bin` junto al libro, con una firma del texto. Si menos de la mitad de los tokens tienen
  caja (maquetación compleja), el lector lo avisa y ofrece «Ver como texto».
- Resaltado: banda por línea para la frase y caja para la palabra (si el motor da tiempos), mezclados en modo
  multiplicar sobre el papel blanco. La vista sigue a la voz salvo durante 4 s tras tocar o arrastrar. Con la
  lectura parada, desplazarse a otra página hace que «reproducir» empiece en ella.

## Miniaturas de portada

`CoverThumbnails` decodifica cada portada una sola vez a 480 px y la guarda en `cacheDir/covers/<id>/`; los
arranques siguientes leen ese JPEG pequeño. Los documentos del usuario sin portada propia reciben la primera
página: PDF renderizado al importar (`PdfFirstPage`), miniatura de ODT/DOCX, o una «página de texto» dibujada
con el título y el comienzo del texto. Los libros de las bibliotecas graduadas conservan su portada ilustrada.

## Licencias de las dependencias

| Dependencia | Licencia | Uso | Peso aproximado en el APK |
|---|---|---|---|
| jsoup 1.21.1 | MIT | HTML/XHTML/XML (EPUB, DOCX, ODT, FB2, web) | ~0,5 MB |
| PdfBox-Android 2.0.27.0 (com.tom-roush) | Apache-2.0 | Texto de PDF | ~9 MB en debug sin minificar (~5 MB son tablas CMap CJK y una fuente) |

## Pruebas

- JVM: `./gradlew :core:test :importer:test`. Los EPUB, DOCX, ODT, FB2, MOBI y RTF de prueba se
  generan en los propios tests: no hay contenido de terceros en el repositorio.
- Dispositivo/emulador: `ImportFlowTest` (SAF → catálogo → lector; «Compartir» texto; reimportación;
  formato no soportado) y `PdfImportTest` (PDF real generado con `PdfDocument`; PDF sin texto).
