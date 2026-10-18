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
  pueden mezclar texto. Las imágenes del PDF no se importan.
- **Texto plano:** codificaciones antiguas distintas de windows-1252 (cp1251, Shift-JIS...) no se
  detectan. FB2, HTML y XML respetan su declaración de codificación.
- Imágenes SVG no se importan (Android no las decodifica con `BitmapFactory`).

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
