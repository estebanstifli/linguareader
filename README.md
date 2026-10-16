# LinguaReader (nombre provisional)

App Android nativa, vertical y offline-first para aprender idiomas leyendo y escuchando libros
graduados (MCER A1–C2). La voz lee el texto, la palabra pronunciada se ilumina cuando el motor
proporciona tiempos fiables (si no, se ilumina la frase) y al tocar una palabra aparece su
traducción en contexto.

> Estado: **Fase 1 completa + bases de Fase 2** (ver [docs/ROADMAP.md](docs/ROADMAP.md)).

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
- **Resaltado honesto**: por palabra solo con eventos `onRangeStart` válidos; si no, por frase.
  La barra inferior indica qué sincronía está activa.
- Estilos de resaltado (fondo, subrayado, marcador), **dedo indicador** opcional, auto-scroll,
  reducción de movimiento, tamaño de letra, interlineado, tema claro/oscuro.
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

Para usar tu móvil: activa *Opciones de desarrollador > Depuración USB*, conéctalo y ejecuta
`.\run.cmd`; si hay un dispositivo conectado, no arranca el emulador.

## Compilar, probar e instalar a mano

Windows (PowerShell), desde la raíz del repo:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat :core:test                     # 57 tests JVM (dominio, contenido, controlador de audio)
.\gradlew.bat :app:assembleDebug             # APK en app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat :app:installDebug              # instala en el dispositivo/emulador conectado
.\gradlew.bat :app:connectedDebugAndroidTest # 4 tests de integración (Hilt + motor TTS falso)
```

macOS/Linux: `./gradlew` con las mismas tareas. Debe existir `local.properties` con `sdk.dir=…`
(Android Studio lo crea; no se versiona).

Si falta la voz de un idioma, el lector lo indica y ofrece «Instalar voz»; se puede leer y
traducir sin audio.

## Estructura

```
core/   Kotlin/JVM puro: modelos, puertos (TTS, alineación, traducción, contenido),
        tokenizador, parser de manifiestos, controlador de audio, traducción por glosario.
app/    Android: Compose UI, Room, DataStore, Hilt, adaptador Android TTS, libros en assets/books.
docs/   ARCHITECTURE.md, CONTENT_FORMAT.md, content-pack.schema.json, ROADMAP.md.
tools/  generate_demo_images.py (ilustraciones provisionales + sha256 en manifiestos).
```

## Contenido y licencias

Los textos de demostración son originales y se escribieron para este proyecto. Las imágenes las
genera `tools/generate_demo_images.py` (CC0-1.0). Los iconos de UI usan trazados de Material
Icons (Apache-2.0). El resumen de licencias está en [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#licencias).
No hay claves de API ni modelos binarios en el repositorio.
