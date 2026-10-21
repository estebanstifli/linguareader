# Roadmap

Leyenda: ✅ hecho y verificado · 🟡 parcial · ⬜ pendiente

## Fase 1 — MVP demostrable ✅

- ✅ Proyecto Android que compila (AGP 9.4.1, Kotlin 2.4.21, Compose, Hilt, Room, DataStore).
- ✅ Catálogo local con 2 libros originales (en A1, fr A2), filtros de idioma y nivel, portadas y progreso.
- ✅ Biblioteca con menú lateral (apartados = filtros: Leyendo ahora, Libros, Aprender inglés/francés/otros,
  estanterías, colecciones, papelera) y vista de lista o cuadrícula de portadas adaptable.
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
- ✅ Android TTS con el flujo de Supertonic: frase a frase a caché de disco, generación por delante (sin silencios
  entre frases largas) y tiempos por palabra del propio motor guardados junto al audio. Elección de voz desde la
  barra de reproducción.
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
- ✅ Lectura continua entre páginas con Supertonic: precarga de 4 páginas por delante, cambio de página sin la pantalla
  «Preparando la lectura…» y preparación del arranque por duración de audio (15 s, máx. 2 frases) en vez de 2 frases fijas.
  Pendiente: encadenar los clips de la página siguiente en el propio reproductor para eliminar el pequeño hueco al cambiar.
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
- ✅ Alineación en el lector (resaltado por palabra con Supertonic) con wav2vec2 int8 de ~120 MB por idioma: EN base-100h
  (Apache-2.0), FR y ES VoxPopuli base (CC-BY-NC-4.0, app no comercial). Adelanto de inicio por modelo (`startLeadMs`)
  medido con `alignBench`: ~31–34 ms de error medio de inicio. Verificado en emulador en FR y ES ([ALIGNMENT.md §9](ALIGNMENT.md)).
  Publicados en `packs-v1`. Pendiente: si la app se vuelve comercial, sustituir FR/ES por modelos Apache-2.0.
- ✅ Normalización de texto antes del TTS renderizado ([SPEECH_NORMALIZATION.md](SPEECH_NORMALIZATION.md)): números,
  ordinales, horas, %, moneda y abreviaturas (es/en/fr; enteros en el resto) con ICU del sistema, léxico y forma
  hablada del autor en el manifiesto, mapa token → habla para la alineación. Pendiente: escucharlo en dispositivo.
- ✅ Pausas controladas entre frases y párrafos (configurables) y recorte de silencios de Supertonic.
- ⬜ Cuantización int8/fp16 del modelo para reducir descarga y RAM (exige validar la calidad).
- ⬜ Descarga en segundo plano con WorkManager (hoy continúa mientras el proceso vive y se reanuda al volver).

## Fase 4 — Contenido e idiomas ⬜

- ✅ Lector paginado a pantalla completa ([READER.md](READER.md)): páginas exactas, gestos (deslizar, bordes,
  centro, pulsación larga), barras superior e inferior, búsqueda, índice, «Acerca del documento», compartir TXT,
  orientación, tema sepia, modo con o sin voz (pregunta inicial), controles compactos y voz con pantalla apagada.
- ✅ Estanterías (Favoritos, Para leer, Leídos, colecciones propias) y papelera con restaurar/borrar.
- ✅ Justificado con guiones automáticos sin desbordes (dibujo del párrafo entero recortado por líneas) y ajuste de alineación.
- ✅ Índice por capítulos (sin pasajes) con pestañas Capítulos, Marcadores y Vocabulario; EPUB con capítulos partidos entre archivos (Gutenberg) bien importados.
- ⬜ Paginar primero el capítulo visible si algún libro muy largo tarda.

- ✅ Importación de libros del usuario ([IMPORT.md](IMPORT.md)): EPUB, PDF con texto, MOBI/AZW3 sin
  DRM, DOCX, ODT, RTF, FB2, HTML, TXT, Markdown, SRT/VTT, páginas web y texto compartido/pegado.
  Parsers propios (sin Readium), segmentación de frases por locale, «Abrir con…»/«Compartir».
- ⬜ OCR (ML Kit, en el dispositivo) para PDF/DjVu escaneados.
- ⬜ Detección automática del idioma de un libro importado sin metadatos.
- ✅ Decisión: PdfBox-Android va siempre incluido en el APK (no como pack descargable). Pendiente
  opcional: activar R8 en release para reducir su código.
- ✅ Bibliotecas descargables por idioma (zip de EPUB con portada en la release `library-v1`, sha256 por
  libro, ids estables entre versiones): primera apertura «¿Qué idioma quieres aprender?» y Ajustes →
  Bibliotecas. Ver [CONTENT_FORMAT.md](CONTENT_FORMAT.md#bibliotecas-por-idioma-packs-descargables).
- ✅ Traducción automática en el dispositivo con ML Kit tras `FallbackTranslationEngine` (caché en Room,
  configuración inicial, modelos en Ajustes, pretraducción de libros importados). Ver [TRANSLATION.md](TRANSLATION.md).
- ⬜ Pretraducción con WorkManager (hoy sigue mientras el proceso vive y se reanuda al reabrir la app).
- ⬜ Traducción de palabras con contexto (ML Kit traduce el texto aislado; la ficha muestra también la frase).
- ⬜ Expresiones discontinuas ("wake him up"), más idiomas, RTL.
- ⬜ Pantalla de vocabulario con repaso.
- ⬜ Mover los textos de UI a `strings.xml` para localizar la interfaz (hoy, español en código).

## Deuda técnica conocida

- Los ids de pasaje (`ch1-p1`) no son globalmente únicos entre libros. El controlador de audio es
  único y solo hay un lector activo, así que hoy no afecta.
- La release no está minificada (R8 desactivado); habrá que añadir reglas para kotlinx.serialization.
