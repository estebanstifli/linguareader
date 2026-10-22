# Supertonic 3 en LinguaReader

Motor TTS local alternativo, ejecutado con ONNX Runtime en la CPU del móvil y sin red.
Convive con el TTS del sistema (Android TTS), que no cambia. Se elige en **Ajustes > Motor de voz**.

## 1. Qué es y de dónde sale

| | |
|---|---|
| Código de referencia | <https://github.com/supertone-oss-archive/supertonic> (MIT). **Archivado**: sin parches ni soporte. |
| Pesos | <https://huggingface.co/supertone-oss-archive/supertonic-3>, revisión fijada `aafc6e32416a594460b32413efc49d7fe4ce6d46` |
| Licencia de los pesos | **BigScience OpenRAIL-M**: uso y redistribución permitidos con restricciones de uso (Anexo A) |
| Arquitectura | 4 ONNX: `duration_predictor` → `text_encoder` → `vector_estimator` (flow matching, N pasos) → `vocoder` |
| Tamaño | 398 MB en fp32 (`vector_estimator` 257 MB, `vocoder` 101 MB, `text_encoder` 36 MB) + 10 voces (≈0,3 MB cada una) |
| Audio | 44,1 kHz, mono, PCM 16 bits |
| Idiomas | 31, incluidos `en`, `fr` y `es` (todas las voces son multilingües) |

### Licencias y obligaciones

- **Código** (port Kotlin de `java/Helper.java`): MIT. Se conserva la atribución en este documento.
- **ONNX Runtime Android 1.31.0**: MIT. Librerías nativas alineadas a 16 KB (requisito de Play en Android 15+).
- **Pesos (OpenRAIL-M)**: **no se empaquetan en el APK**. Se descargan bajo demanda desde el
  archivo oficial y solo después de mostrar la licencia y que el usuario la acepte
  (`supertonicLicenseAccepted`). En la UI se indica "voz sintética", para cumplir la restricción (e)
  de no presentar contenido generado como no generado por máquina. Si en el futuro se
  redistribuyen los pesos (pack propio o CDN), hay que incluir la licencia completa y sus
  restricciones de uso en los términos de la app (§4 de la licencia).
- **Estado del proyecto**: archivado. Fijamos la revisión y los sha256; no se esperan
  actualizaciones de seguridad del código de referencia (nuestro port no depende de él en ejecución).

## 2. Cómo se integra

```
                     ┌────────────── UI (sin cambios de contrato) ───────────────┐
                     │ ReaderViewModel → ReaderAudioController (PlaybackState)   │
                     └──────────────────────────────┬────────────────────────────┘
                                 SwitchingReaderAudioController  (TtsEngineSelector)
                       ┌─────────────────────────┴────────────────────────┐
     StreamingReaderAudioController                         RenderedReaderAudioController
       (StreamingSpeechEngine)                               (SpeechSynthesizer + ClipPlayer)
         AndroidTtsEngine                          CachingSpeechSynthesizer ──► FileAudioCache
         (eventos onRangeStart)                         │                       (LRU 200 MB)
                                                  SupertonicEngine (hilo dedicado)
                                                        │
                                                  SupertonicRuntime (4 sesiones ONNX)
                                                  Media3ClipPlayer (ExoPlayer, cola de WAV)
```

- **Interfaz común `TtsEngine`** (`core/engine/Ports.kt`): `id`, `info` (`TtsEngineInfo`: streaming,
  `providesWordTimings`, `requiresModelDownload`, idiomas), `listVoices`, `release`.
  `StreamingSpeechEngine` (Android TTS) y `SpeechSynthesizer` (Supertonic y futuros) la extienden.
- **Añadir otro motor** = implementar `FileSpeechSynthesizer` (o `StreamingSpeechEngine`), registrarlo
  en `TtsEngineRegistry` y en `SwitchingReaderAudioController` (DI: `TtsEngineModule`).
- **Módulo `:engine:supertonic`** (Kotlin Multiplatform): la inferencia pasa por el puerto `:engine:onnx`, que en JVM/Android usa la API Java de ONNX Runtime.
  En Android la aporta `onnxruntime-android`; en el PC, `onnxruntime` de escritorio. Así la
  misma implementación se prueba en Windows y en el móvil.

### Síntesis por párrafo, precarga y caché

1. Al reproducir un pasaje (párrafo), `RenderedReaderAudioController` sintetiza **una frase tras otra**
   y encola cada WAV en ExoPlayer en cuanto está listo: la reproducción empieza con la primera frase
   y el resto del párrafo se genera mientras suena.
2. Al terminar el pasaje actual, **precarga el siguiente** (`prefetch`) en la caché. El paso entre
   pasajes sale de la caché sin esperar.
3. **Caché** (`FileAudioCache` + `CachingSpeechSynthesizer`): clave = sha256 de
   `texto normalizado + idioma + motor@versión de modelo (revisión) + voz + velocidad + parámetros (pasos)`.
   Cualquier cambio produce otra clave. Escritura atómica, LRU con límite de 200 MB, sobrevive a
   reinicios. Se puede vaciar desde Ajustes.
4. **Pausas controladas**: cada clip se recorta (Supertonic deja 0,3–0,5 s de silencio al principio y
   unos 0,6 s al final; se conservan 40 ms de margen) y el reproductor inserta un silencio entre frases
   (por defecto 550 ms, ≈ 0,65 s efectivos con la transición de ExoPlayer) y al final del párrafo
   (1,0 s). Se configuran en Ajustes > Pausas de lectura y no se graban en el audio cacheado.
   Medido en el emulador: síntesis por delante de la reproducción (hasta 5 frases en cola), pausa real
   entre frases ≈ 0,7 s con 600 ms configurados y ≈ 1,07 s entre párrafos.
5. Si la síntesis va por detrás de la reproducción, el estado pasa a `buffering` ("Generando audio…")
   y continúa sola cuando llega el clip.

### Resaltado y traducción

- **Supertonic no da tiempos por palabra.** El `duration_predictor` devuelve una sola duración por
  enunciado, y ningún ONNX expone alineación carácter/palabra. **No se inventan tiempos**: con
  Supertonic el resaltado es **por frase**, con límites exactos (un clip = una frase) y movidos por
  el evento real de inicio de cada clip en ExoPlayer.
- La barra del lector lo indica: *"Voz sintética (Supertonic) · sincronía por frase: el motor no da tiempos por palabra"*.
- «Leer desde aquí» empieza en el inicio de la frase que contiene la palabra.
- La pausa y reanudación es exacta al milisegundo (ExoPlayer conserva la posición). Al reabrir el
  libro, se reanuda desde el inicio de la frase guardada.
- Tocar una palabra, la ficha de traducción, el glosario, las expresiones y el vocabulario no
  cambian. «Escuchar palabra» usa el motor activo.
- **Resaltado por palabra**: ver el estudio de alineadores forzados en [ALIGNMENT.md](ALIGNMENT.md)
  (MMS vía ONNX funciona en Android con ~30 ms de error; pendiente de integrar en el lector).
- **Alternativas consideradas inicialmente**:
  (a) forced alignment CTC (p. ej. wav2vec2/MMS en ONNX) sobre el WAV generado, validado con
  `AlignmentValidator` y persistido con el hash del audio; (b) exportar las atenciones cruzadas del
  `vector_estimator` (requiere reexportar el modelo, imposible sin el checkpoint PyTorch).
  La opción (a) encaja en `AlignmentEngine` sin tocar la UI.

### Recursos, hilos y memoria

- Toda la inferencia ocurre en un hilo dedicado (`supertonic-tts`); nunca en el hilo principal.
  `intraOpNumThreads` = mín(4, núcleos); optimización `EXTENDED_OPT`.
- El modelo se carga bajo demanda y se **libera** tras 120 s sin síntesis, al cambiar a otro motor,
  al borrar el modelo o con `onTrimMemory(RUNNING_CRITICAL)`. Todos los tensores y resultados
  ONNX se cierran (`use`/`close`).
- La cancelación entre pasos de difusión permite abandonar una síntesis al saltar o pausar.
- El primer uso verifica sha256 de los 17 ficheros (marcador `.verified`).

## 3. Descarga e instalación del modelo

- Ubicación: almacenamiento **interno** de la app, `files/models/supertonic-3/<revisión>/`
  (siempre pertenece a la app y se borra al desinstalar). No se usa `Android/data`: si `adb push` crea allí las carpetas, quedan como usuario `shell` y la app no puede leerlas (EACCES).
- **Ajustes > Motor de voz > Modelo Supertonic 3**: tamaño, aceptación de licencia, descarga con
  progreso por bytes y fichero, cancelación, **reanudación** (HTTP Range), verificación sha256 por
  fichero, comprobación de espacio libre, «Verificar» y «Eliminar».
- Desarrollo: `.\run.cmd -Supertonic` descarga el modelo una vez al PC (`.models/`, ignorado por git)
  y lo copia al dispositivo (`adb push` a `/data/local/tmp` + `run-as` a la carpeta interna; solo builds de depuración).
- El permiso `INTERNET` se añadió **solo** para esta descarga explícita. La biblioteca, la traducción
  y ambos motores (con el modelo instalado) funcionan sin conexión.

## 4. Mediciones

Métricas que registra la app (logcat `Supertonic` y Ajustes > Probar voz): tiempo de carga y de
verificación, tiempo de síntesis, duración del audio, **RTF** (síntesis/audio; < 1 = más rápido que
tiempo real), memoria del proceso (**PSS** en Android, incluye la memoria nativa de ONNX Runtime) e hilos.

### PC — prueba funcional JVM (`./gradlew :engine:supertonic:smokeTest`)
Ryzen 9 3900XT, 4 hilos, ONNX Runtime 1.18 (JVM).

| Caso | Síntesis | Audio | RTF |
|---|---|---|---|
| EN, 84 caracteres, 5 pasos | 1025 ms | 6,3 s | 0,16 |
| EN, 84 caracteres, 8 pasos | 1416–1537 ms | 6,3 s | 0,22–0,24 |
| FR, 38 caracteres, 5 pasos | 529 ms | 2,9 s | 0,18 |
| Carga de las 4 sesiones | 1,0–1,2 s | | |
| Memoria comprometida al cargar | +≈530 MB (vuelve al nivel base tras `release`) | | |
| Acierto de caché | 0 ms | | |

### Emulador Android 16 (x86_64, 4 vCPU, 4 GB) — app real, ONNX Runtime Android 1.31.0

| Caso | Valor |
|---|---|
| Carga del modelo | 1,7–3,0 s |
| Verificación sha256 completa (398 MB) | ≈ 5,7 s (solo la primera vez o con «Verificar») |
| PSS antes → después de cargar | 106 → 527 MB; ≈ 560 MB durante la síntesis |
| «Probar voz» EN (83 caracteres, 5 pasos) | 2,8 s para 6,2 s de audio (RTF 0,46) |
| Frases del libro (11–58 caracteres) | RTF 0,48–1,05 (las frases muy cortas tienen coste fijo relativo mayor) |
| Paso entre pasajes con precarga | sin espera (clips desde caché) |

> **No se ha medido en un teléfono real.** Un emulador x86_64 sobre un Ryzen de escritorio no
> representa un ARM de gama media. Hay que medir en dispositivo (ver §6) antes de decidir los pasos
> por defecto. Si el RTF supera 1, la reproducción tendrá esperas entre frases: usar la voz
> del sistema o el motor de pasos «Normal (5)».

### Incidencia encontrada en el PC (no afecta a Android)

ONNX Runtime ≥ 1.19 para Windows se cuelga (`EXCEPTION_ACCESS_VIOLATION` en `msvcp140.dll`) con
el `msvcp140.dll` 14.29 que trae el JBR de Android Studio, porque necesita ≥ 14.40. Alternativas
evaluadas: otra JDK, parchear el JBR o usar ORT 1.18 en las pruebas de escritorio. Se eligió
**ORT 1.18 solo para los tests JVM** (misma API Java); la app usa 1.31.0.

## 5. Limitaciones conocidas

- Sin tiempos por palabra (ver §2): resaltado por frase.
- 398 MB de descarga y ≈ 450–550 MB de RAM con el modelo cargado. En móviles de 3 GB o menos
  puede fallar (se informa como «sin memoria») o el sistema puede cerrar la app en segundo plano.
- Solo `arm64-v8a` y `x86_64` (`abiFilters`); los móviles de 32 bits usan la voz del sistema.
- Primera síntesis tras cargar: +2–3 s de latencia (carga del modelo).
- Cambiar velocidad o calidad regenera el audio desde la frase actual (forma parte de la clave).
- El ruido inicial se siembra con la clave de la petición: el mismo texto da el mismo audio.
- La reproducción con pantalla bloqueada sigue pendiente (foreground service + MediaSession; ver roadmap).
- Proyecto archivado por el autor: sin mejoras futuras del modelo.

## 6. Cómo probarlo en un teléfono Android real

1. Requisitos: Android 8.0+ de **64 bits** (arm64), ≥ 4 GB de RAM recomendados, ≈ 450 MB libres.
2. En el teléfono: *Ajustes > Información > pulsar 7 veces «Número de compilación»*; luego
   *Opciones de desarrollador > Depuración USB* activada. Conéctalo por USB y acepta la huella del PC.
3. En el PC, desde la raíz del repo:
   ```powershell
   .\run.cmd -Supertonic            # compila, instala, copia el modelo y abre la app
   ```
   Si hay varios dispositivos: `adb devices` y `.\run.cmd -Supertonic -Serial <id>`.
   Sin cable de datos rápido, o para probar la descarga real: `.\run.cmd` y en la app
   *Ajustes > Motor de voz > Descargar* (Wi-Fi recomendado).
4. En la app: *Ajustes > Motor de voz > Supertonic 3*, y luego *Probar voz > EN*. Anota carga, RTF y PSS.
5. Abre *A Day at the Museum* (o cualquier libro) y pulsa reproducir: debe sonar con resaltado por frase y avanzar entre pasajes.
6. Métricas detalladas: `adb logcat -s Supertonic` (cada síntesis registra caracteres, duración,
   tiempo, RTF, pasos y memoria).
7. Prueba instrumentada en el teléfono (desinstala la app al terminar):
   `$env:ANDROID_SERIAL="<id>"; .\gradlew.bat :app:connectedDebugAndroidTest`
