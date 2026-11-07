# Arquitectura

## Principios

1. **Puertos y adaptadores.** La UI solo conoce interfaces de `core`. Los motores (TTS, alineación,
   traducción, contenido) se eligen en la DI (`app/.../di/AppModule.kt`) y se sustituyen sin tocar la UI.
   El test instrumentado `ReaderFlowTest` reemplaza el motor TTS por uno falso vía `@TestInstallIn`
   y la UI funciona igual.
2. **Offline-first.** La biblioteca está en `assets/books`; no se declara el permiso `INTERNET`.
   El adaptador TTS prefiere voces que no requieren red.
3. **Honestidad en la sincronía.** Solo se resalta por palabra con eventos/tiempos validados. Nunca
   se aproximan tiempos por número de caracteres.
4. **Texto inmutable por pasaje.** Los rangos de tokens se calculan sobre el texto normalizado (NFC,
   espacios ASCII colapsados, frases unidas por un espacio). Los ids `"<pasaje>:t<n>"` son estables mientras el texto no cambie.

## Módulos

| Módulo | Tipo | Contenido |
|---|---|---|
| `:core` | Kotlin Multiplatform (jvm, iosArm64, iosSimulatorArm64) | `model` (Book, Passage, Token…), `engine/Ports.kt` (interfaces), `text` (Tokenizer, SentenceSegmenter, normalizador de habla), `content` (manifiesto, integridad), `translation` (glosario, expresiones), `alignment` (validador, timeline, GapFiller), `audio` (clave y caché de audio, sidecars), `narration`, `reader` (controladores de audio), `platform` (puerto expect/actual: Unicode, frases, hashing, charset, fechas) |
| `:engine:onnx` | Kotlin Multiplatform | Puerto mínimo de ONNX Runtime (`OnnxRuntime`, `OnnxSession`, `OnnxTensor`). jvm: API Java de ONNX Runtime; iOS: stub hasta enlazar `onnxruntime-c` en un Mac |
| `:engine:alignment` | Kotlin Multiplatform | Laboratorio histórico de alineación forzada: Viterbi CTC, remuestreo y wav2vec2/MMS. No es dependencia de producción de Android; se conserva para benchmarks. Ver [ALIGNMENT.md](ALIGNMENT.md) |
| `:engine:supertonic` | Kotlin Multiplatform | Motor Supertonic 3: preprocesado Unicode con offsets, runtime ONNX (4 sesiones), atención interna compacta, tiempos por palabra, catálogo con sha256 y métricas. Grafo derivado local con pesos compartidos. Descargador reanudable tras `FileDownloader`. Ver [SUPERTONIC.md](SUPERTONIC.md) |
| `:importer` | Kotlin Multiplatform | Puerto `DocumentExtractor`, `ExtractorRegistry` (detección por firma), extractores EPUB/MOBI/DOCX/ODT/RTF/FB2/HTML/TXT/Markdown/SRT (Ksoup + Okio), `PageTextCleaner` (PDF) e `ImportedBookBuilder` (texto → `manifest.json`). Ver [IMPORT.md](IMPORT.md) |
| `:data` | Kotlin Multiplatform | Persistencia compartida: base de datos Room (`AppDatabase`: progreso, marcadores, vocabulario, caché de traducciones, estanterías, papelera, correcciones, documentos del dispositivo; esquemas exportados en `data/schemas`) y `UserPreferencesRepository` sobre DataStore. Room genera la implementación para jvm e iOS (`@ConstructedBy`); tests JVM con `sqlite-bundled` |
| `:app` | Android | `data` (assets, fuentes de importación de Android, escaneo, `DataStore` de Android), `engine` (Android TTS, foco de audio, reproductor Media3, ML Kit, PDF), `di` (grafo Koin), `ui` (catálogo, lector, ajustes, vocabulario) |
| `:domain` | Kotlin Multiplatform | Gestores que antes vivían en la app Android: libros importados (`ImportedBookRepository`, `LocalBookImporter`), catálogo compuesto, estanterías y papelera (`LibraryRepository`), bibliotecas descargables (`LibraryPackManager`, `LibraryArchive`, `PackSource`), packs (`SupertonicModelManager`, `VoicePackManager`), narraciones y pretraducción. Sobre Okio y el puerto `HttpTransport`; errores tipados (`PackError`). Conserva los paquetes originales |
| `:shared` | Kotlin Multiplatform (solo iOS) | Framework `LinguaShared` para la app iOS: exporta los módulos compartidos y añade los adaptadores de Apple (`AppleSpeechEngine`, `DirectoryBookRepository`, `BookImporter`, arranque Koin). Ver [IOS.md](IOS.md) |
| `ios/` | Xcode (XcodeGen) | App SwiftUI, tests XCTest/XCTest UI y empaquetado para AWS Device Farm. No es un módulo Gradle. Ver [IOS.md](IOS.md) |

Los módulos compartidos no contienen `android.*` ni `java.*`: lo que depende de la plataforma pasa por
`core/platform` (expect/actual) o por puertos (`OnnxRuntime`, `FileDownloader`, `FileSystem` de Okio). Ver
«Kotlin Multiplatform y la app iOS» más abajo.

## Puertos (`core/.../engine/Ports.kt`, `core/.../reader/ReaderAudioController.kt`)

- `BookRepository`: catálogo filtrable, libro, pasaje y media verificada por sha256.
- `TtsEngine`: base común de todos los motores (`id`, `info: TtsEngineInfo`, `listVoices`, `release`).
  `TtsEngineInfo.providesWordTimings` declara si el motor puede dar tiempos por palabra.
- `StreamingSpeechEngine`: motor que reproduce directamente con cola de enunciados y emite
  `SpeechEvent` (`Started`, `WordRange`, `Done`, `Stopped`, `Error`). Lo implementa Android TTS.
- `SpeechSynthesizer`: síntesis a fichero (`AudioAsset` + hash). Lo implementan Supertonic y Android TTS
  (`synthesizeToFile`), los dos vía `CachingSpeechSynthesizer` + `FileAudioCache`. Un `FileSpeechSynthesizer`
  puede devolver **marcas por palabra** del propio motor (`SynthesisOutcome.Written.marks`), que se guardan junto
  al WAV (`<audio>.wav.marks.json`, `SpokenMarksSidecar`).
- `AlignmentEngine`: texto + audio → `TimedToken` o `Unavailable`. Hoy: `UnavailableAlignmentEngine`.
- `TranslationEngine`: `TranslationRequest(text, contextBefore, contextAfter, source, target, unit, ref)`
  → `Translated` o `Unavailable(reason)`. Hoy: `BundledTranslationEngine` (glosario + traducciones
  del manifiesto) dentro de un `FallbackTranslationEngine` preparado para más proveedores.
- `ReaderAudioController`: lo que consume la UI (`playbackState`, `load`, `play`, `pause`,
  `seekToSentence`, `seekToOffset`, `seekTo(ms)`, `setSpeed`, `speakSnippet`, `stop`).
  Se separó `load` de `play` respecto al boceto de `CLAUDE.md` para restaurar el punto de lectura
  sin reproducir. `seekTo(ms)` devuelve `false` si el origen no admite posición temporal (TTS directo).

## Flujo de audio y resaltado (Fase 1/2)

`StreamingReaderAudioController`:

1. `load()` prepara el motor para el idioma/voz (`EngineReadiness`). Si falta la voz, el estado lo
   indica y la UI muestra «Instalar voz»; leer y traducir siguen funcionando.
2. `play()` encola **una frase por enunciado** desde el punto de reanudación. Si se retoma a mitad de
   frase, el primer enunciado empieza en el inicio de la última palabra oída. El id del enunciado
   codifica `generación|frase|offset base`, de modo que los eventos tardíos de una generación
   anterior (tras pausar o saltar) se descartan.
3. `Started` → resaltado por frase. `WordRange(start,end)` se valida: dentro del enunciado, `end > start`
   y monótono. Si es válido, se mapea a token por búsqueda binaria y la granularidad pasa a `WORD`.
   El primer evento incoherente desactiva el resaltado por palabra para esa frase.
4. `Done` de la última frase → `COMPLETED`. El ViewModel avanza al siguiente pasaje si
   «Continuar con el siguiente pasaje» está activo.
5. El progreso (`pasaje + offset`) se guarda en Room en cada cambio de frase/estado y al salir.

Verificado en emulador (API 36, Google TTS, en/fr): el motor emite `onRangeStart` y el resaltado por
palabra funciona. Otros motores pueden no emitirlos; entonces la barra muestra «Sincronía por frase».

### Motores que generan audio (Supertonic 3, implementado)

`SwitchingReaderAudioController` es el único `ReaderAudioController` que ve la UI. Delega en un
`RenderedReaderAudioController` por motor (Android TTS y Supertonic) según la preferencia `ttsEngineId`, que
aplica `TtsEngineCoordinator`. `StreamingReaderAudioController` queda para motores que solo saben reproducir
(el motor falso de los tests instrumentados). El controlador renderizado:

- sintetiza **un clip por frase** y lo encola en Media3/ExoPlayer (`ClipPlayer`) según va estando listo;
- **precarga** el pasaje siguiente;
- resuelve el audio en caché por `AudioCacheKey` (texto normalizado + idioma + motor@versión de
  modelo + voz + velocidad + parámetros);
- resalta **por frase** (límites exactos de clip) y, si hay tiempos fiables, **por palabra**: primero las marcas
  del propio motor (`MarkTimings`), si no el alineador forzado. Nunca se inventan.

### Android TTS con caché en disco

Antes Android TTS hablaba en streaming (`speak`) y cada frase larga se generaba justo cuando tocaba: había
silencios entre frases. Ahora usa el mismo flujo que Supertonic:

1. `AndroidTtsEngine.synthesizeTo` genera cada frase con `synthesizeToFile` (una a la vez; la voz se fija por
   petición) mientras suena la anterior, y la publica en `FileAudioCache`: lo ya leído no se regenera.
2. Durante la generación recoge `onRangeStart` con la posición en frames del audio (frecuencia de
   `onBeginSynthesis`) → `SpokenMark(inicio, fin, ms)`. En el camino de `synthesizeToFile` Android entrega los
   argumentos desordenados (frame, inicio, fin) en muchas versiones; se detecta por los valores.
3. `WavSilenceTrim` recorta el silencio inicial y final (como Supertonic) y las marcas se desplazan.
4. `MarkTimings` sitúa cada token en el texto hablado (por su forma hablada si se normalizó) y convierte las
   marcas en tiempos; `GapFiller` completa lo que falte. Verificado en el emulador: «Sincronía por palabra».
5. La clave de caché incluye el paquete y la versión del motor del sistema (`modelVersion`).
6. Primera detección (`SystemTtsAutoSelect`, al arrancar y en el resumen de la primera apertura): si el usuario no
   eligió motor, se guarda Speech Services by Google (`com.google.android.tts`) si está instalado; si no, el primer
   motor con voz para el idioma de lectura. Evita quedarse con un predeterminado sin voces (p. ej. RHVoice). En
   Ajustes › Voz la elección se guarda siempre con su paquete, también la del predeterminado.

Antes del TTS, `SpeechTextNormalizer` convierte cada frase en su forma hablada (números, abreviaturas,
símbolos) con un mapa token → habla, sin tocar el texto ni los tokens: ver [SPEECH_NORMALIZATION.md](SPEECH_NORMALIZATION.md).

Supertonic produce marcas de inicio/final mediante atención interna. `SpokenMarksSidecar`, `MarkTimings` y
`AlignmentTimeline` las conectan con el lector y su reloj real. No se descarga ni ejecuta un alineador externo;
las marcas inválidas pasan a sincronía por frase. La API de timelines se conserva para narraciones pregrabadas.
Detalles, métricas y limitaciones: [SUPERTONIC.md](SUPERTONIC.md).

## UI

- **Lector paginado a pantalla completa** (`PagedReaderContent`, `ui/reader/paging/`): páginas exactas medidas
  con `TextMeasurer`, carrusel horizontal, toques en los bordes para pasar página, toque central para las barras
  y pulsación larga para la ficha de traducción. Detalles y decisiones en [READER.md](READER.md).
- `TokenText` dibuja un fragmento del pasaje como un único `Text` con `AnnotatedString`. Los toques
  se resuelven con `getOffsetForPosition` y búsqueda binaria, y se valida que el toque cae dentro de
  la caja del token. El resaltado nunca cambia el ancho de los glifos (sin negrita) para que el texto no se mueva.
- `ActiveTokenAnchor` publica la caja en pantalla del token/frase activos. El **dedo**
  (`FingerPointerOverlay`) la consume; no depende del reproductor. En páginas, `TokenHitRegistry` localiza el
  token bajo el dedo sin que el texto capture los toques.
  El dedo no tiene `pointerInput` (no intercepta toques) y está oculto para TalkBack.
- Accesibilidad: descripciones en todos los botones, acción personalizada «Traducir frase» en cada
  bloque de texto (TalkBack), región viva con el estado de sincronía, título de pasaje como encabezado.
- Orientación bloqueada a portrait en el manifiesto (requisito de producto).
- **Idioma de la interfaz** ([I18N.md](I18N.md)): recursos estándar de Android, inglés en `values/` (base) y español
  en `values-es/`; la app sigue el idioma del dispositivo y el «Idioma de la app» de Android 13+
  (`generateLocaleConfig`). Los textos que nacen en ViewModels o motores viajan como `UiText` y se resuelven al
  dibujarlos. Se descartó un JSON propio: perdería plurales, lint, el selector del sistema y la compatibilidad con
  las herramientas de traducción.

## Persistencia

- Room (`linguareader.db`, módulo `:data`, esquema exportado en `data/schemas`): `reading_progress`, `bookmarks`,
  `vocabulary`, `machine_translations`, `collections`, `collection_books`, `trash`, `book_overrides`, `device_documents`.
- DataStore Preferences: preferencias de usuario (`UserPreferences` en `core`, `UserPreferencesRepository` en `:data`;
  el `DataStore` lo crea cada plataforma: Android en `AndroidDataStores.kt`).

## Kotlin Multiplatform y la app iOS

Desde octubre de 2026 los módulos `:core`, `:importer`, `:engine:onnx`, `:engine:alignment` y
`:engine:supertonic` son Kotlin Multiplatform con targets `jvm` (lo que consume la app Android, mismo bytecode
que antes), `iosArm64` e `iosSimulatorArm64`. Es la **Fase 0** del plan de app iOS (resumen y estado en
[ROADMAP.md](ROADMAP.md#app-ios-análisis-hecho-fase-0-en-marcha-)):

- **Fuentes:** `src/commonMain` (todo el código), `src/jvmMain` / `src/iosMain` (solo `actual`s y adaptadores),
  `src/jvmTest` (la suite existente, JUnit 4). Los tests siguen corriendo en JVM; en un Mac se ejecutarán también en
  simulador (`iosSimulatorArm64Test`) para detectar divergencias entre plataformas.
- **Puerto de plataforma (`core/platform`):** `normalizeUnicode` (NFC/NFD/NFKC/NFKD), `sentenceBreaks`
  (BreakIterator / `enumerateSubstrings(.bySentences)`), clases de puntos de código fuera del BMP, `Sha256`
  incremental (MessageDigest / CommonCrypto), `decodeBytes` por nombre de charset (`Charset.forName` /
  `CFStringConvertIANACharSetNameToEncoding`), `epochMillis`, `setFileLastModified`, `availableProcessors`.
  Los `actual` de iOS están escritos pero **sin validar hasta tener un Mac**.
- **Ficheros:** Okio (`FileSystem`, `Path`, `openZip`) en lugar de `java.io.File`. Los puertos de `core`
  (`FileSpeechSynthesizer.synthesizeTo`, `FileAudioCache`, `GapFiller.wavDurationMs`, `WavSilenceTrim`,
  `DocumentSource`, `QuickMetadata`) reciben `okio.Path`; en Android se convierte con `File.toOkioPath()`.
- **Clave de caché de audio:** `"%.2f".format(Locale.ROOT, speed)` se sustituyó por `formatFixed` (misma salida,
  verificada contra el formateador de Java en `DecimalFormatParityTest`) para que las claves no cambien.
- **Ruido de Supertonic:** `kotlin.random.Random(seed)` en lugar de `java.util.Random(seed)`. El audio de una misma
  frase será distinto al de antes (otra secuencia de ruido), pero igual de válido; las cachés existentes siguen
  siendo válidas porque la versión de modelo no cambia.
- **Qué compila dónde:** en Windows/Linux se compilan las klib de iOS (`./gradlew :core:compileKotlinIosArm64`),
  no se enlaza ningún framework ni se ejecutan tests nativos. El XCFramework, los tests en simulador y la app
  SwiftUI los compila un runner macOS de GitHub Actions (`.github/workflows/ios.yml`) y las pruebas en iPhone real
  se hacen en AWS Device Farm: ver [IOS.md](IOS.md). El binding de ONNX Runtime (`onnxruntime-c`) y el descargador
  con `URLSession` siguen pendientes.
- **Persistencia (`:data`):** Room y DataStore son multiplataforma. `AppDatabase` (entidades, DAOs, automigraciones)
  y `UserPreferencesRepository` están en `data/commonMain`; el compilador de Room (`kspJvm`, `kspIosArm64`,
  `kspIosSimulatorArm64`) genera la implementación de cada destino y el `actual` de `AppDatabaseConstructor`.
  Android abre la misma base de siempre con el SQLite del sistema (`Room.databaseBuilder(context, …)`) y el mismo
  fichero de preferencias; iOS usará `BundledSQLiteDriver` y `PreferenceDataStoreFactory.createWithPath`. Los
  tests de `:data` abren la base con `sqlite-bundled` en JVM, sin emulador.
- **Inyección de dependencias: Koin** en lugar de Hilt (que es solo Android). El grafo está en `app/di/AppModule.kt`
  (`dataModule`, `translationModule`, `speechEngineModule`, `ttsEngineModule`, `viewModelModule`); las clases ya no
  llevan anotaciones de DI. `LinguaReaderApp` arranca Koin y los tests instrumentados superponen `testModule`
  (motor de voz falso, base en memoria) desde `TestApp` (`KoinTestRunner`). Lo que Hilt verificaba al compilar lo
  comprueba `KoinModulesTest` (`module.verify()`) en los tests unitarios.
- **Gestores compartidos (`:domain`, octubre de 2026):** la lógica de libros importados, bibliotecas descargables,
  packs, estanterías, narraciones y pretraducción se escribe una vez. Lo que dependía de Android se sustituyó por
  puertos: `java.io.File` → `okio.Path`/`FileSystem`, `HttpURLConnection` → `HttpTransport` (`actual` JVM con
  `HttpURLConnection`, iOS con `NSURLSession` en streaming), `Log` → lambda, `UiText` → `PackError`/`TranslationError`
  (la UI de cada plataforma los traduce; Android en `ui/common/PackErrors.kt`), catálogos JSON de `assets` → texto que
  aporta la plataforma, espacio libre → `freeBytes` (`core/platform`). `ModelDownloader` está en `commonMain` con el
  mismo protocolo (`.part`, `Range`, sha256, `.verified`). Android construye estas clases en `AppModule.kt` con
  `filesDir.toOkioPath()`; iOS en `LinguaShared.kt`.
- **Pendiente de la Fase 0:** mover `ReaderViewModel`/`CatalogViewModel` a `commonMain`. Depende de sacar de ellos
  `R.string` (`UiText` con claves de recurso), `ImageBitmap` y los servicios de Android que usan (ver ROADMAP).

## Decisiones de dependencias (octubre 2026)

| Dependencia | Versión | Motivo |
|---|---|---|
| AGP | 9.4.1 | Última estable. Las librerías AndroidX actuales exigen AGP ≥ 9.1 y compileSdk 37 (se verificó su `aar-metadata`). Kotlin integrado: no se aplica `kotlin-android`. |
| Gradle | 9.8.1 | Última estable. |
| Kotlin | 2.4.21 | Última estable; plugin Compose con la misma versión. |
| KSP | 2.3.12 | KSP2 (versión desacoplada de Kotlin), compatible con Kotlin integrado de AGP 9. |
| Compose BOM | 2026.09.00 | UI 1.12 / Material3. |
| Koin | 4.2.2 | Inyección de dependencias multiplataforma (sustituye a Hilt desde octubre de 2026): `koin-android`, `koin-androidx-compose`, `koin-compose-viewmodel` (`koinViewModel`), `koin-test` (verificación del grafo). Apache-2.0. |
| Room / DataStore | 2.8.5 / 1.2.1 | Persistencia local, multiplataforma, en `:data`. `sqlite-bundled` 2.6.2 solo en los tests JVM. |
| Navigation Compose | 2.10.2 | Rutas tipadas con kotlinx.serialization. |
| Espresso (solo tests) | 3.7.0 | Las versiones anteriores fallan en API 36 (`InputManager.getInstance`). |
| ONNX Runtime Android | 1.31.0 | Inferencia de Supertonic en CPU. MIT. Natives alineadas a 16 KB. Solo `arm64-v8a` y `x86_64` (`abiFilters`). |
| ONNX Runtime (JVM, solo tests en PC) | 1.18.0 | ≥ 1.19 falla con el `msvcp140.dll` 14.29 del JBR de Android Studio. |
| Media3 ExoPlayer | 1.11.1 | Cola de clips WAV de Supertonic, foco de audio, «audio becoming noisy». |
| ONNX Runtime Android | 1.31.0 | Inferencia de Supertonic en CPU. MIT. Natives alineadas a 16 KB. Solo `arm64-v8a` y `x86_64` (`abiFilters`). |
| ONNX Runtime (JVM, solo tests en PC) | 1.18.0 | ≥ 1.19 falla con el `msvcp140.dll` 14.29 del JBR de Android Studio. |
| Media3 ExoPlayer | 1.11.1 | Cola de clips WAV de Supertonic, foco de audio, «audio becoming noisy». |
| Ksoup | 0.2.6 | Importación: HTML/XHTML y XML (EPUB, DOCX, ODT, FB2, páginas web). Port Kotlin Multiplatform de jsoup, misma API. MIT. Sustituye a jsoup (JVM). |
| Okio | 3.18.2 | Sistema de ficheros y ZIP multiplataforma en los módulos compartidos. Apache-2.0. |
| kotlinx-atomicfu (biblioteca) | 0.33.0 | Cerrojos reentrantes en código común (sin plugin del compilador). Apache-2.0. |
| PdfBox-Android | 2.0.27.0 | Importación de PDF con capa de texto. Apache-2.0. Siempre incluido en el APK. |
| ML Kit Translate | 17.0.3 | Traducción automática en el dispositivo detrás de las traducciones incluidas. |

- **compileSdk 37 / targetSdk 36.** targetSdk 37 se deja para cuando se revisen los cambios de
  comportamiento de Android 17.
- **Media3** se usa para el audio generado por Supertonic. La notificación/MediaSession para
  pantalla bloqueada sigue pendiente (Fase 2).
- **Permiso `INTERNET`**: solo para acciones explícitas del usuario (descargar el modelo Supertonic,
  los modelos de traducción de ML Kit o importar una página web). Todo lo demás es offline.
- **Sin Readium.** Readium es un motor de maquetado/visor; el lector solo necesita el texto en
  orden de lectura, así que EPUB se lee con un parser propio (ZIP con Okio + OPF + XHTML con Ksoup).
  Lo mismo para MOBI, DOCX, ODT, RTF y FB2: parsers propios sin dependencias. Ver [IMPORT.md](IMPORT.md).
- **PDF con PdfBox-Android** (Apache-2.0) en `:app`, registrado como extractor extra. El
  `PdfRenderer` nativo solo da texto en API 35+ (minSdk 26) y sin estructura de párrafos.
- **Sin librería de iconos.** 16 vectores con trazados de Material Icons en `res/drawable`.
- **Sin Coil.** Las imágenes son assets locales y se decodifican con `BitmapFactory` tras verificar su sha256.

## Licencias

| Elemento | Licencia | Nota |
|---|---|---|
| AndroidX, Compose, Room, DataStore, Navigation, Koin, kotlinx | Apache-2.0 | Dependencias de compilación. |
| Iconos (`res/drawable/ic_*.xml`) | Apache-2.0 | Trazados de Material Icons (Google). |
| Icono de la app (`ic_launcher_foreground`) | Propio | Dibujo vectorial original. |
| Textos de los libros demo | `LicenseRef-LinguaReader-Demo` | Originales del proyecto; **el titular debe fijar la licencia definitiva** (p. ej. CC-BY-4.0) antes de distribuir. |
| Ilustraciones demo | CC0-1.0 | Generadas por `tools/generate_demo_images.py`. |
| Android TTS / Google TTS | Del sistema | No se redistribuye; lo usa el dispositivo. |
| Supertonic 3 — código de referencia (portado a Kotlin) | MIT | Supertone Inc. Repositorio archivado. |
| Supertonic 3 — pesos ONNX | BigScience OpenRAIL-M | **No se empaquetan.** Descarga bajo demanda tras aceptar la licencia en la app; restricciones de uso (Anexo A); se indica «voz sintética». Ver [SUPERTONIC.md](SUPERTONIC.md). |
| ONNX Runtime | MIT | Microsoft. |
| Alineador EN `facebook/wav2vec2-base-100h` (pesos) | Apache-2.0 | No se empaqueta: descarga opcional (release `packs-v1`). |
| Alineadores FR/ES `facebook/wav2vec2-base-10k-voxpopuli-ft-fr` / `-es` (pesos) | **CC-BY-NC-4.0** | **Solo uso no comercial.** No se empaquetan: descarga opcional; la app muestra el aviso. Si la app pasa a ser comercial (o con complementos de pago), hay que sustituirlos (p. ej. FR large Apache-2.0 de bofenghuang, 355 MB) — ver [ALIGNMENT.md §9](ALIGNMENT.md). |
| Media3 | Apache-2.0 | Google. |
| Ksoup | MIT | Importación de HTML/XHTML/XML (port multiplataforma de jsoup). |
| Okio | Apache-2.0 | Ficheros y ZIP en los módulos compartidos. |
| ML Kit Translation (Google) | ML Kit Terms of Service (no es código abierto) | Traducción en el dispositivo. Modelos (~30 MB/idioma) descargados de servidores de Google bajo demanda. Revisar los términos y la declaración de datos de Play antes de publicar. Ver [TRANSLATION.md](TRANSLATION.md). |
| PdfBox-Android | Apache-2.0 | Importación de PDF. Incluye tablas CMap y una fuente Liberation (SIL OFL) como recursos. |
| Libros importados por el usuario | Del usuario | Se guardan solo en el dispositivo (`license.id = user-import`); no se redistribuyen. |


## Paquetes descargables (voces, modelo Supertonic, bibliotecas y narraciones)

Lo pesado o generado no va en git ni en el APK: se publica como **ficheros estáticos en un bucket R2 público de
Cloudflare** con dominio propio, `https://files.lingoaudiobook.eu` (`PackSource`; configurable con `-PpacksBaseUrl=`).
Los prefijos de ruta conservan las antiguas etiquetas de release: `packs-v1/` (voces, `-PpacksTag=`) y
`library-v1/` (bibliotecas EPUB, `libraries.json` y narraciones, `-PlibraryTag=`). R2 sirve con CDN y `Range`
(reanudación), sin token ni cuenta; hasta la 0.4.2 eran adjuntos de releases de GitHub (`estebanstifli/linguareader`).
El modelo Supertonic se descarga del mismo dominio R2, bajo `models/supertonic-3/<revision>/`, con los 17 archivos
originales y sus SHA-256 fijados en `SupertonicModel`. La licencia y la atribución acompañan a la copia; Hugging Face
queda como referencia de procedencia. Cambiar el servidor no altera la revisión, la carpeta del modelo ni la clave
de caché: las instalaciones completas siguen funcionando sin descargar de nuevo. No hay fallback automático a HF.

| Objeto (`packs-v1/`) | Contenido | Tamaño |
|---|---|---|
| `voices-<idioma>-v1.zip` × 31 | Por voz (F1–F5, M1–M5): muestra de «Probar voz» y frase «preparando la lectura» en ese idioma (`SupertonicSampleTexts`), + `manifest.json` con sha256 | 4–7 MB cada uno |
| `voices.json` | Catálogo `{version: 2, languages: {<idioma>: {asset, sizeBytes, sha256}}}`; la app lo refresca al arrancar (idiomas nuevos sin actualizar la app) | 5 KB |

Los paquetes de alineación se han retirado de la preparación y publicación predeterminadas; Android no los descarga.
Al arrancar, `RetiredAlignmentModels` elimina solo `files/models/alignment`, sin seguir enlaces ni tocar audio,
libros o narraciones. Los experimentos anteriores se conservan en [ALIGNMENT.md](ALIGNMENT.md).

- **Paquete de voces por idioma** (`VoicePackManager`): se descarga **solo el idioma que hace falta**. El del idioma que
  se aprende (`readingLanguage`) baja solo cuando el modelo Supertonic queda instalado y cada vez que ese idioma cambia
  (onboarding incluido); en Ajustes › Voz, «Probar voz» en un idioma sin paquete lo descarga y reproduce la muestra al
  llegar (y sintetiza con el modelo si no hay paquete o falla). En disco, `voice-pack/<idioma>/<voz>/{sample,ready}.wav`
  con un `.installed` (sha256) por idioma; el paquete único anterior (`voice-pack/.installed`) se borra al arrancar.
- **Generar y publicar**: `./gradlew :engine:supertonic:voicePack` (`-PvoiceLanguages=de,it` para unos pocos; ≈9 min los
  31 en PC), `python tools/make_release.py` (copia solo voces a `.models/release/` y regenera
  `assets/packs/voices.json` y `release/voices.json`) y `python tools/publish_packs.py` (sube con `wrangler r2 object put`
  desde `../book_web`; `--library` y `--narrations` para `dist/library` y `dist/narrations`). Un índice local
  (`.models/r2-index.json`) evita resubir lo que no cambió. Límite de wrangler: 300 MiB por objeto.
- **Velocidad**: el audio se genera siempre a 1.0× (clave de caché única); la velocidad del reproductor del lector solo cambia la reproducción (Media3 `setPlaybackParameters`, tono intacto) y no regenera nada.

## Arranque de la lectura con Supertonic

1. Al abrir el libro (o cambiar a Supertonic), `ReaderViewModel.prepareAudio` llama a `ReaderAudioController.warmUp`:
   sintetiza a la caché las primeras frases desde el punto de reanudación **por duración, no por número**: para en
   cuanto el audio generado suma 15 s (≈ 38 palabras), con un máximo de 2 frases (`WarmUpPolicy`). Una primera frase
   larga basta sola, porque mientras suena hay tiempo de generar la siguiente; con frases cortas se esperan las 2. La
   duración es la real del audio (`AudioAsset.durationMs`); si el motor no la informa se estima a 400 ms por palabra.
   Si tarda (carga del modelo, móvil lento)
   aparece «Preparando la lectura…» con animación y suena la frase pregenerada de la voz elegida, que se deja terminar.
   Con las frases ya en caché no se muestra nada (margen de 300 ms).
2. Al pulsar play, el productor sigue generando frase a frase por delante del reproductor.
3. La precarga del pasaje siguiente tiene menos prioridad: arranca cuando termina la síntesis del pasaje actual y se
   cancela si hay que volver a sintetizarlo (antes competía con él por la CPU y podía intercalarse).

## Flujo de lectura con Supertonic: tres etapas independientes

```
Etapa 1  TTS → disco          texto ─► WAV en la caché            (hilo del motor, va por delante de todo)
Etapa 2  disco → alineación   WAV ─► tiempos por palabra ─► .align.json junto al WAV   (alineDispatcher, de uno en uno)
Etapa 3  reproducción         ExoPlayer ◄─ clip + tiempos         (hilo principal; sigue la posición para iluminar)
```

- Las etapas 1 y 2 se comunican por un canal sin límite y no se esperan entre sí: la 1 sigue generando frases mientras
  la 2 alinea la anterior. Empiezan **antes de reproducir**, en la pantalla «Preparando la lectura» (`warmUp`: TTS +
  alineación de las primeras frases, según `WarmUpPolicy`) y siguen en segundo plano con los pasajes siguientes (`prefetch`, de
  menor prioridad): una **ventana de 4 páginas** por delante de la que suena (`PREFETCH_PAGES_AHEAD`, `PrefetchWindow`). La
  generación no se detiene al final de la página; el tope evita seguir hasta el final del libro. Dentro de la precarga, TTS y
  alineación van en paralelo (como en la lectura). Mientras la lectura está en pausa no se genera nada (y se retoma al reanudar).
- **Cambio de página.** Al terminar la última frase, el lector pasa solo a la primera de la siguiente (`autoContinue`) como una
  *continuación*: no se muestra «Preparando la lectura…» ni suena su aviso (eso es solo para el arranque o un salto manual). Con la
  página ya en caché empieza al instante; si no (el motor no puede ir por delante de la lectura), la barra indica «Generando
  audio…» y la voz arranca con la primera frase lista. `PageFlipTest` lo comprueba con Supertonic real.
- Un clip solo se encola en el reproductor cuando su alineación está lista (o se sabe que no la habrá: sin modelo, o no
  fiable → esa frase se resalta entera). Así, al sonar, **siempre tiene todas sus palabras** (antes se alineaba después
  de encolar y las primeras frases podían sonar sin iluminar nada o con una sola palabra).
- **Caché persistente** (`filesDir/audio-cache`, no `cacheDir`, que Android puede vaciar): cada segmento es
  `<hash>.wav` + `<hash>.wav.align.json` (tiempos, modelo de alineación y tamaño del WAV con los que se calculó). Si la
  frase se vuelve a leer (volver de página, reabrir la app) no se repite ni el TTS ni la alineación; si cambia el modelo
  de alineación se recalcula solo la alineación. El LRU expulsa WAV y alineación juntos al superar el límite
  (Ajustes → Caché: 128/256/512/1024 MB, 256 por defecto). Un minuto de audio ≈ 5 MB (44,1 kHz, 16 bits, mono), así
  que 64 MB serían ~12 min (un libro demo entero ya los supera); 256 MB ≈ 50 min.
- Clave por contenido (texto normalizado + idioma + motor/versión + voz + velocidad + pasos), compartida entre libros: la
  misma frase en dos libros se reutiliza. No hay carpeta por libro; el LRU protege lo que se está leyendo.
- Las etapas TTS → disco y disco → alineación viven en `SentenceClipPipeline` (core), que el controlador solo encadena.
  La misma instancia la usa la generación de libros enteros (abajo): lo que genera una lo encuentra la otra.

## Audio de un libro entero en segundo plano (ficha del libro)

Para móviles de gama media/baja, donde Supertonic no va por delante de la lectura, la ficha del libro ofrece
**Generar audio**: `BookAudioService` (`:domain`) recorre todas las frases con el mismo `SentenceClipPipeline` del
lector (TTS a la caché y, si hay modelo para el idioma, alineación por palabra persistida en el sidecar), con progreso
por frases, cancelar y continuar. Es el mismo patrón que `BookTranslationService`: ámbito de aplicación, sobrevive a las
pantallas y cede el paso al lector (no sintetiza mientras hay una lectura sonando, para no robarle el único hilo del
motor). Al abrir el libro, cada frase ya está en caché y suena al instante con todas sus palabras.

### Por qué no «un MP3 por libro»

Se valoró guardar el audio generado como un único fichero por libro y se descartó, al menos por ahora:

| | Un fichero por libro | Clips por frase (elegido) |
|---|---|---|
| Codificación | Android no trae codificador MP3; habría que usar AAC/Opus con `MediaCodec` (específico de plataforma) | WAV ya generado, sin paso extra |
| Reanudar una generación cancelada | Contenedores con cabecera final (M4A) no se pueden ampliar; haría falta ADTS/OGG crudo y un índice aparte | Cada frase es independiente: continuar = saltar las que ya existen |
| Reproducción | Nuevo reproductor con `ClippingConfiguration` por frase y seek en VBR (imprecisión sin tabla de seek) | El pipeline actual (un clip = una frase, pausas que inserta el reproductor) no cambia |
| Alineación | El alineador lee PCM: habría que decodificar antes de alinear, o alinear antes de codificar y guardar los tiempos en el índice | El sidecar `.align.json` ya vive junto al WAV |
| Compartir frases entre libros / lector | Imposible: el audio está dentro del fichero del libro | La clave por contenido se reutiliza entre lector y generación |
| Cambiar de voz o de pasos | Regenerar el fichero entero | Solo cambian las claves afectadas |

El problema real del esquema por frases era que el **LRU** expulsaba lo generado: a 44,1 kHz / 16 bits un libro de
60 min ocupa ≈ 300 MB, más que el límite por defecto (256 MB). La solución es **fijar por grupo**
(`FileAudioCache.pin`): las claves del libro (`audiobook:<id>`, en `audio-cache/pins/`) no cuentan en el límite ni las
expulsa el LRU; se borran desde la ficha (**Borrar audio**) o al borrar el libro definitivamente, y «Vaciar caché» en
Ajustes no las toca. El estado de la ficha («x/total frases, N MB») se calcula contando las claves presentes en la caché,
de modo que no hay un estado persistido que pueda desincronizarse del disco.

**Libros con narración pregrabada** (`Book.narration`, ver CONTENT_FORMAT.md): la ficha no ofrece generar nada. Si la
narración está descargada y casa con el texto (`NarrationLibrary.cached`), la tarjeta muestra «Audio listo: narración de
estudio con tiempos por palabra» al 100 %; si no, «Descargar ahora» (`NarrationLibrary.download(retry = true)`, con
`downloading` para el progreso) además de la descarga automática al abrir el libro. Una narración descargada que no casa
con el texto (< 90 %) se trata como si no existiera y se vuelve a la generación local.

**Un libro a la vez y pantalla apagada.** `BookAudioService.start` detiene cualquier otra generación en marcha (el
motor tiene un solo hilo; la ficha avisa antes con el título del otro libro). Como el proceso se congela con la pantalla
apagada, el diálogo de «Generar audio» ofrece la casilla «Seguir generando aunque se apague la pantalla»
(`generateAudioScreenOff` / `...Chosen` con «No volver a preguntar», también en Ajustes › Voz) y, si está marcada,
mientras hay una generación activa corre `AudioGenerationService`: servicio en primer plano (`mediaProcessing` desde
Android 15, `dataSync` antes; ambos con límite de 6 h diarias, al agotarse `onTimeout` cancela; se llama a
`Service.startForeground` de plataforma porque `ServiceCompat` enmascara los tipos que no conoce y pide «none», que
Android 14+ rechaza con `InvalidForegroundServiceTypeException`)
con wake lock parcial, notificación de progreso (x de total frases, «en espera» mientras se lee) y botón «Detener». El
servicio observa `BookAudioService.states` y se para solo cuando no queda nada activo. Sin aceptar, la generación sigue
mientras el proceso viva y se continúa desde la ficha.

**Siguiente paso si el tamaño pesa** (no implementado): comprimir cada clip fijado **después** de alinearlo (AAC 64 kb/s
mono con `MediaCodec`, ≈ 8 KB/s frente a 88 KB/s del WAV; un libro de 60 min pasaría de ~300 MB a ~30 MB), guardando la
duración en el sidecar porque `GapFiller.wavDurationMs` lee la cabecera WAV. Media3 reproduce AAC/ADTS sin cambios en el
reproductor; el alineador seguiría recibiendo el WAV antes de la compresión. Eso mantiene todo lo anterior y solo añade
una etapa opcional, específica de Android, al final del pipeline.
