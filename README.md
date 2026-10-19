# LinguaReader (nombre provisional)

App Android nativa, vertical y offline-first para aprender idiomas leyendo y escuchando libros
graduados (MCER A1–C2). La voz lee el texto, la palabra pronunciada se ilumina cuando el motor
proporciona tiempos fiables (si no, se ilumina la frase) y al tocar una palabra aparece su
traducción en contexto.

> ⚠️ **ALPHA en desarrollo.** Proyecto experimental: funciones incompletas, cambios frecuentes y sin garantías.
> Las voces pregeneradas y los modelos de alineación por palabra se descargan de la release
> [`packs-v1`](https://github.com/estebanstifli/linguareader/releases/tag/packs-v1) de este repositorio (público, **sin token**).
> El modelo Supertonic 3 se descarga de Hugging Face. Aún no hay APK publicado: se compila con `run.ps1` (ver «Probarlo en Windows con un solo comando» o «Compilar, probar e instalar a mano»).
> Licencias de código, modelos y contenido: ver [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) y [docs/ALIGNMENT.md](docs/ALIGNMENT.md).

> Estado: **Fase 1 completa, Fase 2 casi completa, Supertonic 3 integrado (Fase 3)** (ver [docs/ROADMAP.md](docs/ROADMAP.md)).

## Qué incluye

- **Biblioteca local** con dos relatos originales de demostración: *The Red Kite* (inglés, A1) y
  *Le marché du samedi* (francés, A2), con traducciones por frase (es/fr y es/en), glosario
  contextual e ilustraciones propias. Filtros por idioma del texto y nivel. Funciona sin red.
- **Lector portrait** con cinco modos: solo texto, traducción bajo cada frase, traducción al tocar,
  bilingüe por bloques e ilustrado.
- **Tokens tocables**: ficha con palabra, lema, traducción, expresión que la contiene (phrasal
  verbs, contracciones, elisiones francesas), ejemplos, frase traducida, audio de la palabra,
  «leer desde aquí» y guardar en vocabulario. Pulsación larga: traducción de la frase.
- **Audio** con el TTS del sistema Android: reproducir/pausar, frase anterior/siguiente, repetir,
  velocidad 0.5×–1.5×, reanudación exacta desde la última palabra oída y continuación automática
  al siguiente pasaje.
- **Dos motores de voz intercambiables** (Ajustes > Motor de voz):
  - **Android TTS**, el del sistema.
  - **Supertonic 3**, voz neuronal local con ONNX Runtime en CPU y sin red. El modelo de 398 MB se
    descarga bajo demanda, con progreso y opción de borrarlo. El audio se genera por frases con
    precarga del pasaje siguiente y se guarda en caché. Detalles en [docs/SUPERTONIC.md](docs/SUPERTONIC.md).
- **Resaltado honesto**: por palabra solo con eventos `onRangeStart` válidos; si no, por frase.
  Supertonic no da tiempos por palabra, así que con él el resaltado es por frase.
  La barra inferior indica qué sincronía está activa.
- Estilos de resaltado (fondo, subrayado, marcador), **dedo indicador** opcional, auto-scroll,
  reducción de movimiento, tamaño de letra, interlineado, tema claro/oscuro.
- **Importa tus propios libros** (botón + de la biblioteca, «Abrir con…» o «Compartir»): EPUB,
  PDF con texto, MOBI/AZW3 sin DRM, DOCX, ODT, RTF, FB2, HTML, TXT, Markdown, subtítulos SRT/VTT,
  páginas web (modo lectura) y texto pegado. Detalles y limitaciones en [docs/IMPORT.md](docs/IMPORT.md).
- **Traducción automática sin conexión** (ML Kit) para lo que el libro no trae traducido: libros
  importados (mientras se lee o el libro entero al importarlo) y palabras fuera del glosario. Se
  indica «Traducción automática». Detalles en [docs/TRANSLATION.md](docs/TRANSLATION.md).
- **Progreso persistente** (Room), marcadores, vocabulario, preferencias (DataStore). Idioma de
  lectura e idioma de traducción son independientes.

## Requisitos

- Android Studio con soporte para **AGP 9.4** (la versión 2025.2.2 instalada en esta máquina es
  anterior; actualízala para abrir el proyecto en el IDE; la línea de comandos funciona igual).
- JDK 17+ (se usó el JBR 21 de Android Studio).
- Android SDK con **platform 37** (Gradle la descarga automáticamente si las licencias están aceptadas).
- Dispositivo/emulador con **Android 8.0 (API 26)** o superior y un motor TTS (p. ej. Google TTS) con voces de inglés/francés.

## Probarlo en Windows con un solo comando

Desde la raíz del repo, en PowerShell (o doble clic en `run.cmd`):

```powershell
.\run.cmd
```

El script (`run.ps1`) hace lo siguiente:
- localiza el JDK del Android Studio y el SDK, y escribe `local.properties`;
- instala lo que falte del SDK;
- usa el móvil conectado por USB o arranca el emulador (lo crea si no existe ninguno);
- compila, instala y abre la app.

Opciones:

| Comando | Qué hace |
|---|---|
| `.\run.cmd -Tests` | ejecuta antes los tests JVM |
| `.\run.cmd -AllTests` | ejecuta también los tests instrumentados en el dispositivo |
| `.\run.cmd -Reset` | borra progreso, vocabulario y ajustes de la app |
| `.\run.cmd -Clean` | compilación limpia |
| `.\run.cmd -ColdBoot` | arranque en frío del emulador si se queda colgado |
| `.\run.cmd -Avd Nombre` | elige un emulador concreto |
| `.\run.cmd -Supertonic` | además instala el modelo Supertonic 3 en el dispositivo (lo descarga al PC la primera vez) |
| `.\run.cmd -Serial <id>` | usa un dispositivo concreto si hay varios conectados |

Para usar tu móvil: activa *Opciones de desarrollador > Depuración USB*, conéctalo y ejecuta
`.\run.cmd`; si hay un dispositivo conectado, no arranca el emulador.

## Compilar, probar e instalar a mano

Windows (PowerShell), desde la raíz del repo:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :core:test                     # 76 tests JVM (dominio, contenido, controladores de audio, caché)
.\gradlew.bat :engine:supertonic:test        # 17 tests (texto, WAV, descargador, Supertonic real si está en .models/)
.\gradlew.bat :engine:supertonic:smokeTest   # descarga el modelo (si falta) y sintetiza inglés/francés en el PC
.\gradlew.bat :app:assembleDebug             # un APK por arquitectura en app\build\outputs\apk\debug\:
                                             #   app-arm64-v8a-debug.apk (móviles) y app-x86_64-debug.apk (emulador)
.\gradlew.bat :app:installDebug              # instala en el dispositivo/emulador conectado
.\gradlew.bat :app:connectedDebugAndroidTest # 7 tests en dispositivo (lector con motor falso + Supertonic real si está instalado)
```

macOS/Linux: `./gradlew` con las mismas tareas. Debe existir `local.properties` con `sdk.dir=…`
(Android Studio lo crea; no se versiona).

Si falta la voz de un idioma, el lector lo indica y ofrece «Instalar voz»; se puede leer y
traducir sin audio.

**Teléfono real con Supertonic:** ver [docs/SUPERTONIC.md §6](docs/SUPERTONIC.md#6-cómo-probarlo-en-un-teléfono-android-real).

## Estructura

```
core/   Kotlin/JVM puro: modelos, puertos (TTS, alineación, traducción, contenido),
        tokenizador, parser de manifiestos, controlador de audio, traducción por glosario.
importer/ Kotlin/JVM puro: extractores de formatos (EPUB, MOBI, DOCX, ODT, RTF, FB2, HTML, TXT,
        Markdown, SRT/VTT) y constructor de libros importados. Ver docs/IMPORT.md.
app/    Android: Compose UI, Room, DataStore, Hilt, adaptador Android TTS, libros en assets/books,
        importación (SAF, intents, PDF con PdfBox-Android).
docs/   ARCHITECTURE.md, CONTENT_FORMAT.md, IMPORT.md, content-pack.schema.json, ROADMAP.md.
tools/  generate_demo_images.py (ilustraciones provisionales + sha256 en manifiestos).
```

## Contenido y licencias

Los textos de demostración son originales y se escribieron para este proyecto. Las imágenes las
genera `tools/generate_demo_images.py` (CC0-1.0). Los iconos de UI usan trazados de Material
Icons (Apache-2.0). El resumen de licencias está en [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#licencias).
No hay claves de API ni modelos binarios en el repositorio.

Las **bibliotecas de lecturas graduadas** (inglés, español, francés) son adaptaciones propias de obras
de dominio público (`content/graded-books/`). Se publican como zip de EPUB por idioma en la release
`library-v1`:

```
python -I tools/make_covers.py        # ilustraciones (OpenAI; OPENAI_API_KEY en .env), solo las que falten
python -I tools/build_library.py      # EPUB + zips + catálogo (dist/library/ y assets/packs/libraries.json)
python -I tools/build_library.py --upload   # sube a la release library-v1 (gh)
```
