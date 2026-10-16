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
| `:app` | Android | `data` (assets, Room, DataStore), `engine` (Android TTS, foco de audio, reproductor Media3, gestor del modelo Supertonic), `di`, `ui` (catálogo, lector, ajustes, vocabulario) |

Se empezó con dos módulos para no crear módulos vacíos. Los límites de la organización sugerida en
`CLAUDE.md` se mantienen por paquetes. Separar `engine/tts-supertonic` en módulo propio tiene
sentido cuando lleguen dependencias pesadas (ONNX Runtime).

## Puertos (`core/.../engine/Ports.kt`, `core/.../reader/ReaderAudioController.kt`)

- `BookRepository`: catálogo filtrable, libro, pasaje y media verificada por sha256.
- `TtsEngine`: base común de todos los motores (`id`, `info: TtsEngineInfo`, `listVoices`, `release`).
  `TtsEngineInfo.providesWordTimings` declara si el motor puede dar tiempos por palabra.
- `TtsEngine`: base común de todos los motores (`id`, `info: TtsEngineInfo`, `listVoices`, `release`).
  `TtsEngineInfo.providesWordTimings` declara si el motor puede dar tiempos por palabra.
- `StreamingSpeechEngine`: motor que reproduce directamente con cola de enunciados y emite
  `SpeechEvent` (`Started`, `WordRange`, `Done`, `Stopped`, `Error`). Lo implementa Android TTS.
- `SpeechSynthesizer`: síntesis a fichero (`AudioAsset` + hash), **sin tiempos**. Lo implementan
  Supertonic (vía `CachingSpeechSynthesizer` + `FileAudioCache`) y Android TTS (`synthesizeToFile`).
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

`SwitchingReaderAudioController` es el único `ReaderAudioController` que ve la UI. Delega en
`StreamingReaderAudioController` (Android TTS) o en `RenderedReaderAudioController` (Supertonic)
según la preferencia `ttsEngineId`, que aplica `TtsEngineCoordinator`. El controlador renderizado:

- sintetiza **un clip por frase** y lo encola en Media3/ExoPlayer (`ClipPlayer`) según va estando listo;
- **precarga** el pasaje siguiente;
- resuelve el audio en caché por `AudioCacheKey` (texto normalizado + idioma + motor@versión de
  modelo + voz + velocidad + parámetros);
- resalta **por frase** (límites exactos de clip). No hay tiempos por palabra y no se inventan.

Para resaltado por palabra con Supertonic haría falta un `AlignmentEngine` de forced alignment
sobre el WAV. `AlignmentValidator` y `AlignmentTimeline` ya están listos para ese caso.
Detalles, métricas y limitaciones: [SUPERTONIC.md](SUPERTONIC.md).

## UI

- `TokenText` dibuja un fragmento del pasaje como un único `Text` con `AnnotatedString`. Los toques
  se resuelven con `getOffsetForPosition` y búsqueda binaria, y se valida que el toque cae dentro de
  la caja del token. El resaltado nunca cambia el ancho de los glifos (sin negrita) para que el texto no se mueva.
- `ActiveTokenAnchor` publica la caja en pantalla del token/frase activos. El **dedo**
  (`FingerPointerOverlay`) y el **auto-scroll** la consumen; ninguno depende del reproductor.
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

- **compileSdk 37 / targetSdk 36.** targetSdk 37 se deja para cuando se revisen los cambios de
  comportamiento de Android 17.
- **Media3** se usa para el audio generado por Supertonic. La notificación/MediaSession para
  pantalla bloqueada sigue pendiente (Fase 2).
- **Permiso `INTERNET`**: solo para la descarga explícita del modelo Supertonic. Todo lo demás es offline.
- **Sin Readium todavía.** La importación EPUB es Fase 4 y antes hay que verificar su licencia (BSD-3).
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
| Media3 | Apache-2.0 | Google. |


## Paquetes descargables (voces y modelos de alineación)

Lo pesado o generado no va en git ni en el APK: se publica como adjuntos de una **release de GitHub**
(`estebanstifli/linguareader`, etiqueta `packs-v1`; configurable con `-PpacksRepo=` y `-PpacksTag=`). El modelo
Supertonic sigue descargándose de Hugging Face (revisión fijada).

| Adjunto | Contenido | Tamaño |
|---|---|---|
| `voices.zip` | 60 WAV por voz (F1–F5, M1–M5) e idioma (en, fr, es): muestra de prueba y frase «preparando la lectura», + manifest con sha256 | 12 MB |
| `w2v2-en.model.onnx` / `.vocab.json` | wav2vec2 base 100h (Apache-2.0), int8 | 122 MB |
| `w2v2-fr.model.onnx` / `.vocab.json` | wav2vec2 large 7K FR (Apache-2.0), int8 | 355 MB |

- Generar y publicar: `./gradlew :engine:supertonic:voicePack`, `python tools/make_release.py` (copia los adjuntos a
  `.models/release/` y regenera `assets/aligners/catalog.json` y `assets/packs/voices.json` con tamaños y sha256) y
  `gh release upload packs-v1 .models/release/* --clobber`.
- **Repositorio privado**: la app resuelve los adjuntos con la API de GitHub (`GithubPackSource`) y necesita un token
  de solo lectura (Ajustes → «Descarga de paquetes»). El token se guarda solo en el almacenamiento privado del móvil
  y solo se envía a `api.github.com`; la redirección a la URL firmada de descarga se sigue a mano para no reenviarlo a otro
  host. Si el repositorio pasa a ser público, el token no es necesario. Nunca va en el APK ni en git.
- El paquete de voces se baja solo cuando el modelo Supertonic queda instalado; «Probar voz» y la frase de preparación
  usan esos WAV sin cargar el modelo.

## Arranque de la lectura con Supertonic

1. Al abrir el libro (o cambiar a Supertonic), `ReaderViewModel.prepareAudio` llama a `ReaderAudioController.warmUp`:
   sintetiza a la caché las 2 primeras frases desde el punto de reanudación. Si tarda (carga del modelo, móvil lento)
   aparece «Preparando la lectura…» con animación y suena la frase pregenerada de la voz elegida, que se deja terminar.
   Con las frases ya en caché no se muestra nada (margen de 300 ms).
2. Al pulsar play, el productor sigue generando frase a frase por delante del reproductor.
3. La precarga del pasaje siguiente tiene menos prioridad: arranca cuando termina la síntesis del pasaje actual y se
   cancela si hay que volver a sintetizarlo (antes competía con él por la CPU y podía intercalarse).
