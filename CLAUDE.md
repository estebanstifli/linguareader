# CLAUDE.md — LinguaReader (nombre provisional)

## Misión
Crear una app Android **nativa, offline-first y vertical (portrait)** para aprender idiomas leyendo y escuchando libros graduados (MCER: A1, A2, B1, B2, C1, C2). La voz lee el texto y la interfaz ilumina la palabra exacta en sincronía. Al pulsar una palabra, aparece su traducción contextual al idioma elegido. Arquitectura modular: sustituir motores TTS, alineación y traducción sin cambiar el lector.

## Objetivos de producto
- Biblioteca integrada de lecturas graduadas por idioma, nivel, género, duración y progreso; también importación EPUB/TXT (PDF más adelante).
- Idioma de **lectura** y de **traducción** configurables e independientes: p. ej., leer francés y traducir al español, leer inglés y traducir al francés.
- Libros con capítulos y **pasajes** que opcionalmente incluyen una fotografía o ilustración, créditos y texto alternativo. Imagen al inicio del pasaje, nunca impuesta para libros sin imágenes.
- Diseño vertical adaptable a distintos tamaños de móvil; navegación accesible, lectura fluida y reproducción con pantalla bloqueada.
- Modos de visualización configurables: (1) solo texto en idioma de aprendizaje; (2) traducción debajo de cada frase/pasaje; (3) traducción al tocar; (4) vista bilingüe por bloques; (5) modo ilustrado con imagen por pasaje.
- Resaltado sincronizado por palabra, con estilo elegible (fondo, subrayado, marcador). Indicador opcional **mano/dedo** como overlay decorativo apuntando a la palabra activa, sin tapar el texto, con reducción de movimiento y posibilidad de desactivarlo.
- Tocar palabra: pausa opcional, ficha emergente con lema, traducción en contexto, audio de palabra, ejemplos y guardar en vocabulario. Pulsación prolongada: seleccionar expresión/frase. Permitir reanudar exactamente donde se pausó.
- Controles: reproducir/pausar, saltar frase, repetir frase, velocidad (cuando la implementación permita conservar calidad), voz y progreso. Marcadores, tamaño tipográfico, interlineado, tema claro/oscuro.
- Precacheo de audio y alineación por fragmentos; reproducción continua sin cortes en cambios de pasaje.

## Stack recomendado
- Kotlin, Jetpack Compose, Material 3, Coroutines/Flow, Navigation Compose, ViewModel.
- Room/SQLite para biblioteca, progreso, glosario, vocabulario, caché de metadatos y alineaciones.
- Media3/ExoPlayer para reproducción, audio focus, notificación y media session.
- Hilt para inyección de dependencias; Gradle version catalog; tests unitarios y de integración.
- EPUB mediante Readium Kotlin Toolkit si la licencia y la integración se verifican; TXT mediante parser propio; no presuponer soporte sencillo para PDF.
- TTS inicial: implementación intercambiable de Android TextToSpeech (prototipo) y adaptador Supertonic ONNX (fase posterior), condicionado a medir CPU/RAM/latencia y revisar licencias del código y pesos. Nunca acoplar la UI a ONNX.
- Traducción inicial: diccionario local y proveedor mock; adaptador opcional de traducción por modelos locales o API remota, sin claves embebidas. La traducción de expresiones requiere contexto.
- Alineación inicial: proveedor basado en callbacks `onRangeStart` cuando el TTS lo soporte; proveedor alternativo de forced alignment para audio sintetizado. Si no hay tiempos fiables, mostrar resaltado por frase y no simular precisión.

## Arquitectura modular (puertos y adaptadores)
Definir interfaces de dominio estables y adaptadores que puedan reemplazarse por configuración/DI:

```kotlin
interface SpeechSynthesizer {
    val id: String
    suspend fun synthesize(request: SpeechRequest): SpeechResult
    suspend fun listVoices(languageTag: String): List<VoiceInfo>
}
interface AlignmentEngine {
    val id: String
    suspend fun align(text: String, audio: AudioAsset, languageTag: String): AlignmentResult
}
interface TranslationEngine {
    val id: String
    suspend fun translate(request: TranslationRequest): TranslationResult
}
interface BookRepository {
    fun observeCatalog(filter: CatalogFilter): Flow<List<BookSummary>>
    suspend fun loadBook(bookId: String): Book
    suspend fun loadPassage(bookId: String, passageId: String): Passage
}
interface ReaderAudioController {
    val playbackState: StateFlow<PlaybackState>
    suspend fun play(passageId: String)
    suspend fun pause()
    suspend fun seekTo(milliseconds: Long)
}
```

Modelos comunes: `Book(id, title, learningLanguage, cefrLevel, cover, chapters, attribution, license)`, `Chapter`, `Passage(id, orderedRuns, image?, translationBlocks?)`, `Token(id, text, rangeStart, rangeEnd, sentenceId)`, `TimedToken(tokenId, startMs, endMs, confidence?)`, `SpeechRequest(text, languageTag, voiceId, speed)`, `TranslationRequest(text, contextBefore, contextAfter, sourceLanguage, targetLanguage)`. Los rangos del texto deben corresponder a una versión normalizada e inmutable por pasaje; conservar puntuación y espacios para que los toques correspondan a tokens.

### Organización sugerida
```
app/
core/model/
core/database/
core/designsystem/
core/media/
core/testing/
domain/reader/
domain/library/
domain/learning/
engine/tts-api/
engine/tts-android/
engine/tts-supertonic/
engine/alignment-api/
engine/alignment-android/
engine/alignment-forced/
engine/translation-api/
engine/translation-dictionary/
engine/translation-remote/
feature/catalog/
feature/reader/
feature/vocabulary/
feature/settings/
content/sample-books/
docs/
```
No crear módulos Gradle vacíos por anticipado: empezar con un número sensato, manteniendo los límites por paquetes o módulos, y separar físicamente cuando aporte valor.

## Flujo de lectura y sincronización
1. Cargar pasaje y obtener tokens con identificadores estables.
2. Resolver audio en caché por hash del texto normalizado + idioma + motor/versión + voz + velocidad + parámetros de generación.
3. Si no está en caché, sintetizar el siguiente fragmento fuera del hilo UI.
4. Resolver alineación confiable (eventos nativos o forced alignment). Validar monotonicidad y cobertura de tokens; persistir junto al hash exacto del audio y texto.
5. Durante reproducción, correlacionar `player.currentPosition` con los intervalos temporales usando búsqueda binaria; actualizar el estado de la UI sin recomponer todo el capítulo ni forzar scroll excesivo.
6. Scroll automático suave hacia la palabra/frase activa, configurable. El dedo ilustrado es opcional y se posiciona usando las coordenadas del token visible; nunca intercepta el toque ni tapa la lectura.
7. Pulsación de palabra abre traducción contextual y puede pausar. Al cerrar, el usuario reanuda desde el mismo punto.
8. Prefetch del pasaje siguiente y limpieza de caché según límite de almacenamiento.

**Decisión clave:** Supertonic no debe darse por supuesto que entrega timestamps por palabra. Medir o integrar alineación específica. No aproximar timings con caracteres como si fueran exactos.

## Contenido: estructura y derechos
- Cada libro empaquetado o descargado usa un manifiesto JSON versionado con `id`, `title`, `learningLanguage` (BCP-47), `cefrLevel`, `authors`, `adaptationCredit`, `license`, `sourceAttribution`, `cover`, `chapters`.
- Cada capítulo contiene pasajes ordenados con texto original, traducciones por idioma opcionales, imagen y texto alternativo opcionales, glosario y metadatos de dificultad.
- Media (imagen/audio) mediante rutas relativas o hashes, con comprobación de integridad; soportar descarga posterior de packs sin necesidad de actualizar APK.
- Incluir solo historias propias, dominio público verificado o contenidos con licencia que permita adaptación y distribución; no asumir que una novela de dominio público convierte automáticamente una adaptación moderna en libre.
- El catálogo inicial puede usar **libros de demostración originales**, breves, sin derechos de terceros.

## Diseño UI inspirada en la referencia visual
- Pantalla reader portrait: imagen ilustrada opcional ocupando la zona superior del pasaje, barra fina de progreso, texto legible de alto contraste, traducción secundaria más tenue debajo de cada oración en modo bilingüe, palabra pronunciada iluminada con acento claro, controles accesibles en la parte inferior.
- Configuraciones persistentes para cinco modos visuales, iluminación, dedo, scroll automático, mostrar ilustraciones, idioma fuente/destino, voz y velocidad.
- Tratar el fotograma compartido solo como referencia conceptual; **no copiar marca, ilustraciones ni diseño exacto**. No usar iconos de Instagram ni reproducir su UI.
- El panel de traducción debe reconocer combinaciones como phrasal verbs y contracciones: token y expresión pueden diferir.

## Idioma de la interfaz (i18n)
- El idioma base de la app es el **inglés** (`app/src/main/res/values/strings.xml`). Las cadenas se guardan como recursos de Android para la globalización de la app; el primer idioma adicional es el español (`values-es/strings.xml`). La app sigue el idioma del dispositivo.
- Nunca escribir textos visibles (incluidos `contentDescription`) directamente en Kotlin. Cada cadena nueva va a `values/` (inglés) y a `values-es/` (español) con la misma clave; plurales con `<plurals>`, argumentos `%1$s`.
- Los textos que generan ViewModels, servicios o motores viajan como `UiText` (`ui/common/UiText.kt`) y se resuelven al mostrarlos.
- Antes de terminar un cambio de UI: `python -I tools/check_hardcoded_strings.py`. Detalles en `docs/I18N.md`.

## Roadmap incremental y criterios de aceptación
**Fase 1 — MVP demostrable:** Android que compila, catálogo local con al menos dos libros originales de prueba en idiomas distintos y distintos niveles, pantalla reader portrait, texto con tokens pulsables, traducciones simuladas o incluidas, marcadores, modos de visualización y controles accesibles. Usar TTS Android donde esté instalado; fallback claro si falta voz/idioma.

**Fase 2 — Sincronización real:** lectura de un pasaje, eventos de palabra donde estén disponibles, resaltado verificable en emulador/dispositivo; fallback por frase. Almacenamiento correcto de progreso tras pausa, reanudación y cierre.

**Fase 3 — Motor Supertonic y alineación:** prototipo ONNX medido en móvil real; carga de modelos desde pack descargable; forced alignment desacoplado; prefetch, caché, manejo de errores/tiempos y aviso del tamaño del modelo; evaluación de licencias antes de distribuir.

**Fase 4 — Contenido e idiomas:** importar EPUB/TXT, packs graduados, traducción contextual, modo ilustrado, vocabulario y más lenguas.

Pruebas mínimas: tokenización con puntuación/Unicode y diacríticos; RTL si más adelante se admite; offsets tras cambios de fuente; coherencia audio/timestamps; modo sin red; accesibilidad TalkBack; rotación bloqueada solo si producto lo exige; cambio entre idiomas; reinicio de app; integración DI con motores mock.

## Normas operativas para Claude Code
- Antes de escribir código: inspeccionar el repo y detallar un plan corto; si está vacío, inicializar proyecto Android Gradle con versiones estables compatibles, sin inventar APIs.
- Implementar en iteraciones pequeñas, compilables y verificables; ejecutar build/test tras cada hito cuando haya Android SDK disponible.
- No intentar implementar todos los motores de IA en el primer commit: interfaces + mocks + Android TTS primero.
- No incluir credenciales, libros con copyright ni modelos binarios grandes en git.
- Documentar licencias concretas de cada motor, SDK, peso de modelo y contenido antes de empaquetarlo.
- Mantener `README.md` (usuarios y puesta en marcha), `docs/ARCHITECTURE.md` (decisiones), `docs/CONTENT_FORMAT.md` (esquema y ejemplos), `docs/ROADMAP.md` (estado/tareas).
- Si faltan Android SDK/Gradle/network, indicar exactamente qué comandos habría que ejecutar; no afirmar compilación o pruebas no realizadas.
- Dar prioridad a experiencia móvil fluida, sencillez del MVP y sustitución independiente de motores.
