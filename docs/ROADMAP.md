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

## Fase 3 — Supertonic y alineación ⬜

- ⬜ Revisar las licencias del código y de los pesos de Supertonic antes de cualquier integración.
- ⬜ Módulo `engine/tts-supertonic` (ONNX Runtime) que implemente `SpeechSynthesizer`; modelos
  descargables como pack con aviso de tamaño; nunca en git.
- ⬜ Medir CPU/RAM/latencia en un móvil real (gama media).
- ⬜ `AlignmentEngine` de forced alignment desacoplado. Validar con `AlignmentValidator` y persistir
  junto a `AudioCacheKey` y el hash del audio.
- ⬜ `RenderedAudioReaderAudioController` con Media3/ExoPlayer + `AlignmentTimeline` (búsqueda binaria ya implementada y testeada).
- ⬜ Prefetch del pasaje siguiente y limpieza de caché por límite de almacenamiento.

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
