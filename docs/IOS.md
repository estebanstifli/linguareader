# App iOS sin Mac: compilación en GitHub Actions y pruebas en AWS Device Farm

Estado: **Fase 1 en marcha** (octubre de 2026). La app iOS vive en [`ios/`](../ios/) y consume el framework
`LinguaShared` que produce el módulo Gradle [`:shared`](../shared/build.gradle.kts) (paraguas de `:core`, `:data`,
`:importer` y `:engine:*`). La app Android no cambia.

## Qué hay

| Carpeta | Contenido |
|---|---|
| `shared/` | Módulo KMP solo iOS (`iosArm64`, `iosSimulatorArm64`). `src/iosMain`: `LinguaShared` (arranque del grafo Koin, acceso desde Swift), `AppleSpeechEngine` (voz del sistema con `AVSpeechSynthesizer` y rangos por palabra), `DirectoryBookRepository` (libros en `<Application Support>/imported/<id>/manifest.json`), `BookImporter` (importador compartido) y `FlowWatcher` (observar Flows desde Swift). |
| `ios/project.yml` | Proyecto Xcode descrito para **XcodeGen**: app `LinguaReader` (SwiftUI, iOS 17), `LinguaReaderTests` (XCTest) y `LinguaReaderUITests` (XCTest UI). El `.xcodeproj` se genera en CI y no se versiona. |
| `ios/LinguaReader/` | App mínima: catálogo, lector con resaltado por palabra y controles; importa `Resources/cinderella.epub` en la primera apertura con el importador compartido. |
| `ios/LinguaReaderTests/` | Paridad con la JVM (tokenizador, clave de caché, frases) y el spike de marcas de palabra de las voces de Apple. |
| `ios/LinguaReaderUITests/` | Humo: arranca, abre el libro de muestra, empieza a leer, pasa de página. |
| `ios/Podfile` | Dependencias por CocoaPods: `GoogleMLKit/Translate` (traducción en el dispositivo, mismos modelos que Android; ML Kit no se distribuye de otra forma). Solo en el job de iPhone (`pod install` tras XcodeGen, se compila el `.xcworkspace`): los binarios de ML Kit no traen rebanada arm64 de simulador, así que el job del simulador compila el `.xcodeproj` sin pods, sin traductor y con su test omitido; la traducción se verifica en Device Farm (run XCTest con `LinguaReaderTests.xctest.zip`, o a mano). |
| `ios/LinguaReader/Translation/` | `MlKitTranslator`: adaptador Swift del puente `IosTranslator` (callbacks) que el código compartido envuelve en `IosTranslationEngine` (caché en Room, `MODEL_MISSING` si falta modelo) e `IosTranslationModels` (descarga, «solo Wi-Fi», borrado). Ajustes › Translation gestiona los modelos; la ficha de palabra ofrece descargar el que falte. |
| `ios/scripts/package-ipa.sh` | Empaqueta la salida de `build-for-testing` en los `.ipa`/`.zip` que pide Device Farm. |
| `.github/workflows/ios.yml` | Lo que haría un Mac: tests de los módulos compartidos en simulador, XCFramework, proyecto Xcode, tests de la app en simulador, build para dispositivo y artefactos para Device Farm. |

## Compilación: GitHub Actions (runner macOS)

Xcode solo existe en macOS. El repositorio es público, así que los runners `macos-15` de GitHub son gratuitos
(en un repositorio privado cada minuto de macOS cuenta por 10 sobre los 2000 mensuales del plan gratuito). El
workflow se lanza en cada push que toque los módulos compartidos, `shared/`, `ios/` o el propio workflow, y también
a mano (**Actions › iOS › Run workflow**). Dos jobs en paralelo:

1. **Device Farm packages**: `./gradlew :shared:linkDebugFrameworkIosArm64` (solo la rebanada de iPhone) →
   `xcodebuild -create-xcframework` → `xcodegen generate` → `xcodebuild build-for-testing` para
   `generic/platform=iOS` **sin firma** (`CODE_SIGNING_ALLOWED=NO`) → `package-ipa.sh`. Tarda ~4 min y deja el
   artefacto **`ios-device-farm`** con:
   - `LinguaReader.ipa`: la app (Device Farm: *run type* «iOS app»).
   - `LinguaReaderUITests.ipa`: el runner de XCTest UI (*framework* «XCTest UI»).
   - `LinguaReaderTests.xctest.zip`: el bundle de XCTest (*framework* «XCTest»), con la paridad y el spike de voz.
2. **Shared suites and SwiftUI app tests in the simulator**: una sola invocación de Gradle ejecuta
   `:core:iosSimulatorArm64Test :importer:… :engine:alignment:… :engine:supertonic:… :data:iosSimulatorArm64Test`
   (la misma suite que corre en la JVM, con los `actual` de iOS) y enlaza la rebanada de simulador; después
   `xcodegen generate`, arranque del iPhone 16 del simulador (`ios/scripts/prepare-simulator.sh`, que solo ejecuta
   `xcodebuild -runFirstLaunch` si Xcode lo pide) y `xcodebuild test`. Deja `shared-ios-test-reports` y
   `ios-simulator-results` (logs, `.xcresult`, informes de cierre resumidos en el log del job) para depurar.

Por qué está así (medido el 23-10-2026 sobre el job único anterior, 11,5 min): enlazar las dos rebanadas costaba
2 min y el `.ipa` solo salía al final; `xcodebuild -runFirstLaunch` (80 s) sobra en estos runners; el índice
de código de Xcode se generaba sin que nadie lo usara. Arrancar el simulador en segundo plano mientras Gradle
enlaza **no** funciona: el runner (3 vCPU, 7 GB) se arrastra y el enlace pasa de 22 s a 7 min. Y cada job
restaura ~1 GB de caché (`~/.gradle` con `gradle/actions/setup-gradle`, solo escrita desde `main`, y `~/.konan`,
el toolchain de Kotlin/Native): GitHub limita el egreso de caché por cuenta, con tres jobs ya saltó el aviso
«Egress is over the account limit» y la caché dejó de restaurarse. `DerivedData` de Xcode no se cachea (apenas
ahorra y da problemas al cambiar de Xcode).

En Windows solo se comprueba que compila: `./gradlew :shared:compileKotlinIosArm64`.

## Pruebas en dispositivos reales: AWS Device Farm

Device Farm **no compila**: recibe un `.ipa` compilado para dispositivo (arm64) y lo ejecuta en iPhones reales
de su parque. Lo que hay que saber:

- **Re-firma la app** con un perfil comodín propio: no hace falta cuenta Apple Developer ni registrar UDIDs, y
  acepta el `.ipa` sin firmar que produce CI (es lo que hace el tutorial oficial de AWS). La re-firma elimina
  entitlements (App Groups, Push, etc.) que no usamos; el modo «audio en segundo plano» es una clave de
  `Info.plist` y se conserva.
- Solo existe en la región **us-west-2 (Oregón)**; el proyecto está ahí aunque la consola muestre otra región.
- **Precio**: 1000 minutos de dispositivo gratis (una vez); después 0,17 $/min. Un run de humo son ~2–3 min por
  dispositivo; una sesión manual dura lo que se use (máximo 150 min por sesión).

### Subir un run (a mano, desde la consola)

1. En GitHub, **Actions › iOS**, abre el último run verde y descarga el artefacto `ios-device-farm`.
2. Consola de AWS → Device Farm → *Mobile Device Testing* → *Projects* → tu proyecto → **Create run**.
3. *Run type*: **iOS app**; *Upload own app*: `LinguaReader.ipa`.
4. *Configure test*:
   - **XCTest UI** + `LinguaReaderUITests.ipa` para el humo de la app (vídeo, capturas y logs por dispositivo);
     activa *Generate test report* si quieres el desglose por test.
   - **XCTest** + `LinguaReaderTests.xctest.zip` para la paridad con la JVM y el spike de voz. El resultado
     del spike (voces instaladas y número de marcas de palabra) está en el log del run (`NSLog`).
5. *Select devices*: un iPhone reciente y, si está en el parque, uno de 4 GB (SE o 12 mini) para medir memoria
   más adelante. Empieza por un solo dispositivo: cada uno gasta minutos.
6. **Create run** y espera; el resultado por dispositivo tiene *Suites*, *Logs*, *Screenshots* y *Video*.

### Probar la app a mano (acceso remoto)

Device Farm → *Remote access* → **Start a new session** → elige un iPhone → *Install application* y sube
`LinguaReader.ipa`. Verás y manejarás el iPhone desde el navegador (toques, giro, teclado); la sesión captura vídeo
y logs. Es la forma de «probarlo todo» sin tener un iPhone ni un Mac.

**Diagnóstico en pantalla.** Lo que en Android va a Logcat, en iOS se ve dentro de la app: **Ajustes › Acerca de ›
Diagnostics** (también «Audio diagnostics» en los ajustes de lectura). Lista cada síntesis de la voz de Apple
(duración, formato de los búferes, marcas de palabra brutas y usables), la caché de audio, las descargas y cada
cambio de estado de la reproducción (estado, granularidad del resaltado, motivo de error). Con el botón de copiar
se lleva al portapapeles del iPhone. Las mismas líneas van a `NSLog`, pero en el log de Device Farm quedan con
nivel *Default*: si filtras por *Error* no aparecen; filtra por el proceso `LinguaReader` sin filtrar por nivel.
Líneas que son ruido normal: `AVAudioBuffer.mm … mDataByteSize (0)` (búfer vacío con el que `write(...)` cierra
cada frase) y `Error in destroying pipe` (se libera el sintetizador tras cada frase).

### Automatizar más adelante

Con credenciales de AWS en los secretos del repositorio (mejor OIDC) el job puede subir y lanzar el run él solo:
`aws devicefarm create-upload` (app, test package), `aws devicefarm schedule-run` y `get-run` hasta que termine.
Queda anotado para cuando merezca la pena.

## Qué queda y en qué orden

1. Primer run verde del workflow y primer run en Device Farm (comprueba la re-firma del `.ipa` sin firmar). Si
   Device Farm lo rechazara: firmar en CI con un Apple ID gratuito (certificado y perfil en secretos; caduca cada
   7 días) o con una cuenta Apple Developer.
2. Arreglar lo que saque la suite en simulador (los `actual` de iOS nunca se habían ejecutado).
3. Spike de síntesis a fichero con marcas (`AVSpeechSynthesizer.write(_:toBufferCallback:toMarkerCallback:)`)
   para usar el flujo renderizado con caché, como en Android.
4. ONNX Runtime en iOS (`onnxruntime-c` por cinterop) y medir wav2vec2 y Supertonic en un iPhone real con
   `LinguaReaderTests` en Device Farm.
5. Lector con TextKit 2 (paginación, toque en palabra, traducción), catálogo completo, ajustes, Now Playing,
   `URLSession` en segundo plano para los packs. Ver el plan en [ROADMAP.md](ROADMAP.md#app-ios).
6. Publicar: requiere cuenta Apple Developer (TestFlight / App Store) y un Mac o Xcode Cloud para firmar.
