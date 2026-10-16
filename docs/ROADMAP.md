# Roadmap

Leyenda: ✅ hecho y verificado · 🟡 parcial · ⬜ pendiente

## Fase 1 — MVP demostrable ✅

- ✅ Proyecto Android que compila (AGP 9.4.1, Kotlin 2.4.21, Compose, Hilt, Room, DataStore).
- ✅ Catálogo local con 2 libros originales (en A1, fr A2), filtros de idioma y nivel, portadas y progreso.
- ✅ Lector portrait: imagen opcional por pasaje con crédito y `alt`, barra fina de progreso, texto serif de alto contraste.
- ✅ Cinco modos de visualización.
- ✅ Tokens tocables; ficha con palabra, expresión, ejemplos y frase; guardar en vocabulario; pulsación larga = frase.
- ✅ Traducciones incluidas (es/fr, es/en); «no disponible» explícito, sin inventar.
- ✅ Idioma de lectura y de traducción independientes y persistentes.
- ✅ Marcadores, tamaño de letra, interlineado, tema claro/oscuro/sistema.
- ✅ Android TTS con aviso claro e «Instalar voz» si falta la voz.
- ✅ Tests: 57 JVM en `:core`, 4 instrumentados (Hilt + motor falso) en emulador API 36.

## Fase 2 — Sincronización real 🟡

- ✅ Eventos de palabra (`onRangeStart`) validados → resaltado por palabra; fallback por frase.
  Verificado en emulador con Google TTS en inglés y francés.
- ✅ Pausa y reanudación desde la última palabra oída; progreso persistente tras pausa y cierre.
- ✅ Saltar/repetir frase, velocidad, continuación automática de pasaje, foco de audio.
- ✅ Dedo indicador desacoplado (consume la caja del token visible), desactivable, con reducción de movimiento.
- ⬜ Reproducción con pantalla bloqueada: foreground service + Media3 `MediaSession` + notificación.
  Hoy el audio sigue mientras el proceso vive, sin servicio que lo garantice.
- ⬜ Pausar en pérdida transitoria de foco (llamadas) de forma compatible con los motores TTS que piden foco propio.
- ⬜ Dedo: hoy se dibuja bajo la palabra, pequeño y semitransparente, pero puede tapar parte de la
  línea siguiente o de la traducción. Opciones: reservar hueco con interlineado mayor o colocarlo en el margen.
- ⬜ Test instrumentado de reanudación tras reinicio de la app y de offsets tras cambio de tamaño de fuente.
- ⬜ Prueba manual completa con TalkBack.

## Fase 3 — Supertonic y alineación 🟡

- ✅ Licencias revisadas: código MIT, pesos OpenRAIL-M (descarga bajo demanda con aceptación; no se empaquetan).
- ✅ Módulo `:engine:supertonic` (ONNX Runtime, CPU, offline) que implementa `SpeechSynthesizer`;
  descarga con tamaño, progreso, reanudación, sha256, cancelación y borrado; nunca en git.
- ✅ Interfaz común `TtsEngine` + `TtsEngineRegistry` + `SwitchingReaderAudioController`: Android TTS
  y Supertonic intercambiables desde Ajustes, sin cambios en la UI del lector.
- ✅ `RenderedReaderAudioController` con Media3: un clip por frase, reproducción mientras se
  sintetiza el resto del párrafo y precarga del pasaje siguiente.
- ✅ Caché de audio reutilizable (LRU 200 MB, clave con modelo, versión, voz, idioma, velocidad, pasos y texto).
- ✅ Métricas: carga, verificación, síntesis, duración, RTF, PSS e hilos (logcat + Ajustes > Probar voz).
- ✅ Medido en PC (RTF 0,16–0,24) y en emulador x86_64 (RTF 0,45–1,05). Ver [SUPERTONIC.md](SUPERTONIC.md).
- ⬜ **Medir en un móvil real de gama media (arm64)** y ajustar los pasos por defecto.
- ✅ Estudio de alineadores forzados ([ALIGNMENT.md](ALIGNMENT.md)): MMS (audio.cpp y ONNX) y Qwen3 (audio.cpp).
  MMS-ONNX en Kotlin (`:engine:alignment`) funciona en Android: ~30 ms de error, RTF 0,39 en el emulador.
- ⬜ Integrar la alineación en el lector (resaltado por palabra con Supertonic) — **decidir la licencia**:
  MMS es CC-BY-NC (no comercial); Qwen3 es Apache-2.0 pero pesado para móvil.
- ✅ Pausas controladas entre frases y párrafos (configurables) y recorte de silencios de Supertonic.
- ⬜ Cuantización int8/fp16 del modelo para reducir descarga y RAM (exige validar la calidad).
- ⬜ Descarga en segundo plano con WorkManager (hoy continúa mientras el proceso vive y se reanuda al volver).

## Fase 4 — Contenido e idiomas ⬜

- ⬜ Importación TXT (parser propio con segmentación de frases) y EPUB (Readium, tras verificar licencia).
- ⬜ Packs descargables con verificación de integridad (formato ya preparado: sha256 por media).
- ⬜ Proveedor de traducción contextual local o remoto (sin claves embebidas) tras `FallbackTranslationEngine`.
- ⬜ Expresiones discontinuas ("wake him up"), más idiomas, RTL.
- ⬜ Pantalla de vocabulario con repaso.
- ⬜ Mover los textos de UI a `strings.xml` para localizar la interfaz (hoy, español en código).

## Deuda técnica conocida

- Los ids de pasaje (`ch1-p1`) no son globalmente únicos entre libros. El controlador de audio es
  único y solo hay un lector activo, así que hoy no afecta.
- La release no está minificada (R8 desactivado); habrá que añadir reglas para kotlinx.serialization.
