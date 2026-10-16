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
| `:app` | Android | `data` (assets, Room, DataStore), `engine` (Android TTS, foco de audio), `di`, `ui` (catálogo, lector, ajustes, vocabulario) |

Se empezó con dos módulos para no crear módulos vacíos. Los límites de la organización sugerida en
`CLAUDE.md` se mantienen por paquetes. Separar `engine/tts-supertonic` en módulo propio tiene
sentido cuando lleguen dependencias pesadas (ONNX Runtime).

## Puertos (`core/.../engine/Ports.kt`, `core/.../reader/ReaderAudioController.kt`)

- `BookRepository`: catálogo filtrable, libro, pasaje y media verificada por sha256.
- `StreamingSpeechEngine`: motor que reproduce directamente con cola de enunciados y emite
  `SpeechEvent` (`Started`, `WordRange`, `Done`, `Stopped`, `Error`). Lo implementa Android TTS.
- `SpeechSynthesizer`: síntesis a fichero (`AudioAsset` + hash), **sin tiempos**. Android TTS lo
  implementa con `synthesizeToFile`; Supertonic lo implementará en Fase 3.
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

### Fase 3 (preparado, no implementado)

`RenderedAudioReaderAudioController` (por crear) haría: `SpeechSynthesizer.synthesize` →
`AudioCacheKey` (hash de texto normalizado + idioma + motor@versión + voz + velocidad + parámetros)
→ `AlignmentEngine.align` → `AlignmentValidator` (monotonía, cobertura ≥ 90 %, confianza) →
`AlignmentTimeline.tokenAt(player.currentPosition)` con Media3/ExoPlayer. La UI no cambia, porque
consume el mismo `PlaybackState`.

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

- **compileSdk 37 / targetSdk 36.** targetSdk 37 se deja para cuando se revisen los cambios de
  comportamiento de Android 17.
- **Sin Media3 todavía.** El TTS directo no produce un fichero que reproducir. Media3 entra con el
  audio sintetizado (Fase 3) y para la notificación/MediaSession (Fase 2).
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
| Supertonic (código y pesos) | **Pendiente** | Revisar las licencias del código y de los pesos antes de la Fase 3. No hay ningún modelo en el repo. |
