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
| `ios/scripts/package-ipa.sh` | Empaqueta la salida de `build-for-testing` en los `.ipa`/`.zip` que pide Device Farm. |
| `.github/workflows/ios.yml` | Lo que haría un Mac: tests de los módulos compartidos en simulador, XCFramework, proyecto Xcode, tests de la app en simulador, build para dispositivo y artefactos para Device Farm. |

## Compilación: GitHub Actions (runner macOS)

Xcode solo existe en macOS. El repositorio es público, así que los runners `macos-15` de GitHub son gratuitos
(en un repositorio privado cada minuto de macOS cuenta por 10 sobre los 2000 mensuales del plan gratuito). El
workflow se lanza en cada push que toque los módulos compartidos, `shared/`, `ios/` o el propio workflow, y también
a mano (**Actions › iOS › Run workflow**). Dos jobs:

1. **Shared modules on the iOS simulator**: `./gradlew :core:iosSimulatorArm64Test :importer:iosSimulatorArm64Test
   :engine:alignment:iosSimulatorArm64Test :engine:supertonic:iosSimulatorArm64Test :data:iosSimulatorArm64Test`.
   Es la misma suite que corre en la JVM, ejecutada con los `actual` de iOS (normalización Unicode, cortes de
   frase, hashing, charsets…), que hasta ahora solo compilaban.
2. **SwiftUI app**: `./gradlew :shared:assembleLinguaSharedDebugXCFramework` → `xcodegen generate` →
   `xcodebuild test` en el simulador (iPhone 16, iOS 18.5) → `xcodebuild build-for-testing` para
   `generic/platform=iOS` **sin firma** (`CODE_SIGNING_ALLOWED=NO`) → `package-ipa.sh`. Deja el artefacto
   **`ios-device-farm`** con:
   - `LinguaReader.ipa`: la app (Device Farm: *run type* «iOS app»).
   - `LinguaReaderUITests.ipa`: el runner de XCTest UI (*framework* «XCTest UI»).
   - `LinguaReaderTests.xctest.zip`: el bundle de XCTest (*framework* «XCTest»), con la paridad y el spike de voz.

   También deja `ios-simulator-results` (logs y `.xcresult`) para depurar.

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
