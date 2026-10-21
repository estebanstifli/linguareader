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
| `:core` | Kotlin/JVM | `model` (Book, Passage, Token…), `engine/Ports.kt` (interfaces), `text` (Tokenizer, PassageBuilder, OffsetIndex), `content` (manifiesto, integridad), `translation` (glosario, expresiones), `alignment` (validador, timeline), `audio` (clave de caché), `reader` (ReaderAudioController + implementación streaming) |
| `:engine:supertonic` | Kotlin/JVM | Motor Supertonic 3: port del preprocesado de texto, runtime ONNX (4 sesiones), catálogo del modelo con sha256, descargador con reanudación, métricas. Compila contra la API Java de ONNX Runtime (Android y escritorio). Ver [SUPERTONIC.md](SUPERTONIC.md) |
| `:importer` | Kotlin/JVM | Puerto `DocumentExtractor`, `ExtractorRegistry` (detección por firma), extractores EPUB/MOBI/DOCX/ODT/RTF/FB2/HTML/TXT/Markdown/SRT, `PageTextCleaner` (PDF) e `ImportedBookBuilder` (texto → `manifest.json`). Ver [IMPORT.md](IMPORT.md) |
| `:app` | Android | `data` (assets, Room, DataStore), `engine` (Android TTS, foco de audio, reproductor Media3, gestor del modelo Supertonic), `di`, `ui` (catálogo, lector, ajustes, vocabulario) |

Se empezó con dos módulos para no crear módulos vacíos. Los límites de la organización sugerida en
`CLAUDE.md` se mantienen por paquetes. Separar `engine/tts-supertonic` en módulo propio tiene
sentido cuando lleguen dependencias pesadas (ONNX Runtime).

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

Antes del TTS, `SpeechTextNormalizer` convierte cada frase en su forma hablada (números, abreviaturas,
símbolos) con un mapa token → habla, sin tocar el texto ni los tokens: ver [SPEECH_NORMALIZATION.md](SPEECH_NORMALIZATION.md).

Para resaltado por palabra con Supertonic haría falta un `AlignmentEngine` de forced alignment
sobre el WAV. `AlignmentValidator` y `AlignmentTimeline` ya están listos para ese caso.
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

## Persistencia

- Room (`linguareader.db`, esquema exportado en `app/schemas`): `reading_progress`, `bookmarks`, `vocabulary`.
- DataStore Preferences: preferencias de usuario (`UserPreferences` en `core`).

## Decisiones de dependencias (octubre 2026)

| Dependencia | Versión | Motivo |
|---|---|---|
| AGP | 9.4.1 | Última estable. Las librerías AndroidX actuales exigen AGP ≥ 9.1 y compileSdk 37 (se verificó su `aar-metadata`). Kotlin integrado: no se aplica `kotlin-android`. |
| Gradle | 9.8.1 | Última estable. |
| Kotlin | 2.4.21 | Última estable; plugin Compose con la misma versión. |
| KSP | 2.3.12 | KSP2 (versión desacoplada de Kotlin), compatible con Kotlin integrado de AGP 9. |
| Compose BOM | 2026.09.00 | UI 1.12 / Material3. |
| Hilt / androidx.hilt | 2.60.1 / 1.4.0 | DI recomendada en CLAUDE.md; `hiltViewModel` desde `hilt-lifecycle-viewmodel-compose`. |
| Room / DataStore | 2.8.5 / 1.2.1 | Persistencia local. |
| Navigation Compose | 2.10.2 | Rutas tipadas con kotlinx.serialization. |
| Espresso (solo tests) | 3.7.0 | Las versiones anteriores fallan en API 36 (`InputManager.getInstance`). |
| ONNX Runtime Android | 1.31.0 | Inferencia de Supertonic en CPU. MIT. Natives alineadas a 16 KB. Solo `arm64-v8a` y `x86_64` (`abiFilters`). |
| ONNX Runtime (JVM, solo tests en PC) | 1.18.0 | ≥ 1.19 falla con el `msvcp140.dll` 14.29 del JBR de Android Studio. |
| Media3 ExoPlayer | 1.11.1 | Cola de clips WAV de Supertonic, foco de audio, «audio becoming noisy». |
| ONNX Runtime Android | 1.31.0 | Inferencia de Supertonic en CPU. MIT. Natives alineadas a 16 KB. Solo `arm64-v8a` y `x86_64` (`abiFilters`). |
| ONNX Runtime (JVM, solo tests en PC) | 1.18.0 | ≥ 1.19 falla con el `msvcp140.dll` 14.29 del JBR de Android Studio. |
| Media3 ExoPlayer | 1.11.1 | Cola de clips WAV de Supertonic, foco de audio, «audio becoming noisy». |
| jsoup | 1.21.1 | Importación: HTML/XHTML y XML (EPUB, DOCX, ODT, FB2, páginas web). MIT. |
| PdfBox-Android | 2.0.27.0 | Importación de PDF con capa de texto. Apache-2.0. Siempre incluido en el APK. |
| ML Kit Translate | 17.0.3 | Traducción automática en el dispositivo detrás de las traducciones incluidas. |

- **compileSdk 37 / targetSdk 36.** targetSdk 37 se deja para cuando se revisen los cambios de
  comportamiento de Android 17.
- **Media3** se usa para el audio generado por Supertonic. La notificación/MediaSession para
  pantalla bloqueada sigue pendiente (Fase 2).
- **Permiso `INTERNET`**: solo para acciones explícitas del usuario (descargar el modelo Supertonic,
  los modelos de traducción de ML Kit o importar una página web). Todo lo demás es offline.
- **Sin Readium.** Readium es un motor de maquetado/visor; el lector solo necesita el texto en
  orden de lectura, así que EPUB se lee con un parser propio (ZIP + OPF + XHTML con jsoup).
  Lo mismo para MOBI, DOCX, ODT, RTF y FB2: parsers propios sin dependencias. Ver [IMPORT.md](IMPORT.md).
- **PDF con PdfBox-Android** (Apache-2.0) en `:app`, registrado como extractor extra. El
  `PdfRenderer` nativo solo da texto en API 35+ (minSdk 26) y sin estructura de párrafos.
- **Sin librería de iconos.** 16 vectores con trazados de Material Icons en `res/drawable`.
- **Sin Coil.** Las imágenes son assets locales y se decodifican con `BitmapFactory` tras verificar su sha256.

## Licencias

| Elemento | Licencia | Nota |
|---|---|---|
| AndroidX, Compose, Room, DataStore, Navigation, Hilt/Dagger, kotlinx | Apache-2.0 | Dependencias de compilación. |
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
| jsoup | MIT | Importación de HTML/XHTML/XML. |
| ML Kit Translation (Google) | ML Kit Terms of Service (no es código abierto) | Traducción en el dispositivo. Modelos (~30 MB/idioma) descargados de servidores de Google bajo demanda. Revisar los términos y la declaración de datos de Play antes de publicar. Ver [TRANSLATION.md](TRANSLATION.md). |
| PdfBox-Android | Apache-2.0 | Importación de PDF. Incluye tablas CMap y una fuente Liberation (SIL OFL) como recursos. |
| Libros importados por el usuario | Del usuario | Se guardan solo en el dispositivo (`license.id = user-import`); no se redistribuyen. |


## Paquetes descargables (voces y modelos de alineación)

Lo pesado o generado no va en git ni en el APK: se publica como adjuntos de una **release de GitHub**
(`estebanstifli/linguareader`, etiqueta `packs-v1`; configurable con `-PpacksRepo=` y `-PpacksTag=`). El modelo
Supertonic sigue descargándose de Hugging Face (revisión fijada).

| Adjunto | Contenido | Tamaño |
|---|---|---|
| `voices.zip` | 60 WAV por voz (F1–F5, M1–M5) e idioma (en, fr, es): muestra de prueba y frase «preparando la lectura», + manifest con sha256 | 12 MB |
| `w2v2-en.model.onnx` / `.vocab.json` | wav2vec2 base 100h (Apache-2.0), int8 | 122 MB |
| `vp-fr.model.onnx` / `.vocab.json` | wav2vec2 base VoxPopuli FR (**CC-BY-NC-4.0**), int8 | 122 MB |
| `vp-es.model.onnx` / `.vocab.json` | wav2vec2 base VoxPopuli ES (**CC-BY-NC-4.0**), int8 | 122 MB |

El antiguo `w2v2-fr` (wav2vec2 large 7K FR, Apache-2.0, 355 MB) ya no está en el catálogo; la app borra su carpeta al
arrancar (`AlignmentModelManager.removeRetired`). Ver [ALIGNMENT.md §9](ALIGNMENT.md).

- Generar y publicar: `./gradlew :engine:supertonic:voicePack`, `python tools/make_release.py` (copia los adjuntos a
  `.models/release/` y regenera `assets/aligners/catalog.json` y `assets/packs/voices.json` con tamaños y sha256) y
  `gh release upload packs-v1 .models/release/* --clobber`.
- **Repositorio público**: la app resuelve los adjuntos con la API de GitHub (`GithubPackSource`) sin token ni cuenta.
- **Velocidad**: el audio se genera siempre a 1.0× (clave de caché única); la velocidad del reproductor del lector solo cambia la reproducción (Media3 `setPlaybackParameters`, tono intacto) y no regenera nada.
- El paquete de voces se baja solo cuando el modelo Supertonic queda instalado; «Probar voz» y la frase de preparación
  usan esos WAV sin cargar el modelo.

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
