# Alineación por palabra para Supertonic: estudio comparativo

Objetivo: obtener tiempos por palabra del audio generado por Supertonic (que no los da) para
resaltar la palabra que suena. Ambos motores evaluados son **alineadores forzados**: reciben el
audio y el texto exacto, sin ASR.

## 1. Candidatos

| | MMS Forced Aligner | Qwen3-ForcedAligner |
|---|---|---|
| Modelo | Meta MMS-300M-1130 (wav2vec2 + CTC), 315M | Qwen3-ForcedAligner-0.6B |
| Implementación evaluada | audio.cpp `mms_forced_aligner` (GGUF f16) **y** la misma red en ONNX (onnx-community) con nuestro alineador CTC en Kotlin | audio.cpp `qwen3_forced_aligner` (GGUF Q8_0) |
| Licencia de los pesos | **CC-BY-NC-4.0, no comercial** | **Apache-2.0** |
| Idiomas | 1.130 con texto romanizado (en, fr y es funcionan sin diacríticos) | zh, yue, en, de, es, fr, it, pt, ru, ko, ja |
| Resolución temporal | 20 ms por fotograma | saltos de 80 ms (observado) |
| Tamaño | GGUF f16 631 MB · ONNX uint8 317 MB · ONNX q4 241 MB | GGUF Q8 1.130 MB · f16 1.840 MB |

audio.cpp (<https://github.com/0xShug0/audio.cpp>, v0.9.2) tiene licencia Apache-2.0 y API C.
**No publica builds ni instrucciones para Android**: hay binarios para Windows, Linux y macOS. El
binario `cpu` de Windows no arranca en el Ryzen 9 3900XT (instrucción ilegal: compilado para CPU
más modernas); se usó el `cpu-portable`.

## 2. Método

- **Corpus** (`./gradlew :engine:supertonic:alignCorpus` → `.models/align-corpus/`):
  - 24 frases naturales del primer capítulo de los dos libros (EN y FR) sintetizadas con Supertonic.
  - 5 frases **con verdad de referencia**: cada palabra se sintetiza aparte y se concatena con
    120 ms de silencio, así que se conocen sus límites exactos (±margen de recorte de 40 ms,
    compensado). La prosodia es menos natural, pero permite medir el error en ms.
- **audio.cpp**: `tools/eval_aligners.py` (CLI, 12 hilos, tiempos de inferencia con `--metrics`).
- **MMS-ONNX**: `./gradlew :engine:alignment:alignBench` (Kotlin + ONNX Runtime, el mismo
  código que usaría la app) y, en Android, `MmsAlignOnDeviceTest`.
- **Métricas**: error absoluto de inicio y fin de palabra frente a la referencia, % de palabras
  con error ≤ 50 ms, RTF (tiempo de inferencia / duración del audio), memoria y monotonía.

## 3. Resultados

### Precisión (frases con referencia, 41 palabras)

| | Error inicio (media) | p90 | Error fin (media) | ≤ 50 ms |
|---|---|---|---|---|
| audio.cpp MMS f16 | 31 ms | 60 ms | 55 ms | 83 % |
| **MMS ONNX uint8 (Kotlin)** | **30 ms** | 60 ms | 55 ms | 83 % |
| MMS ONNX q4 (Kotlin) | 31 ms | 60 ms | 59 ms | 78 % |
| audio.cpp Qwen3 Q8 | **29 ms** | **50 ms** | **28 ms** | **88 %** |

- Nuestra implementación ONNX coincide con la de audio.cpp a **2 ms** de media: es el mismo
  modelo con el mismo algoritmo CTC.
- En frases naturales MMS y Qwen3 discrepan 37 ms de media (p90 80 ms).
- Los dos dan tiempos monótonos y cubren todas las palabras (29/29 frases sin error).
- Para resaltar palabras, cualquier error < ~80 ms es imperceptible: **los dos son suficientes**.

### Velocidad y memoria — PC (Ryzen 9 3900XT)

| | RTF inferencia | Carga | Memoria |
|---|---|---|---|
| audio.cpp MMS f16 (portable, 12 hilos) | 1,83 | 0,1 s (mmap) | 1,29 GB pico |
| audio.cpp Qwen3 Q8 (portable, 12 hilos) | 0,93 | 1,45 s | 2,32 GB pico |
| **MMS ONNX uint8 (4 hilos)** | **0,18** | 0,9 s | +0,88 GB |
| **MMS ONNX q4 (4 / 12 hilos)** | **0,18 / 0,11** | 0,6 s | +0,44 GB |

### Velocidad y memoria — Android (emulador x86_64, 4 vCPU, 4 GB; sin AVX/AVX2)

| | RTF | Carga | PSS cargado / pico | Error inicio |
|---|---|---|---|---|
| **MMS ONNX uint8** | **0,39** | 0,8 s | 452 / 587 MB | 30 ms |
| MMS ONNX q4 | 0,88 | 0,6 s | 378 / 509 MB | 31 ms |
| audio.cpp (cualquiera) | — | — | — | no compila para Android sin portar el build |

> Falta medirlo en un **móvil ARM real**: ONNX Runtime tiene kernels NEON para uint8 y q4, y el
> resultado puede cambiar respecto al emulador (que carece de AVX2).

## 4. Conclusiones

1. **Para Android, MMS a través de ONNX Runtime es claramente la opción viable hoy.** Reutiliza el
   runtime que la app ya incluye (sin NDK ni C++), ocupa 317 MB (uint8) o 241 MB (q4), carga en
   < 1 s, alinea una frase en una fracción de su duración y tiene la misma precisión que audio.cpp.
   Implementado en `:engine:alignment` (`MmsOnnxAligner`, `CtcForcedAligner`, `MmsText`).
2. **Qwen3 es ligeramente más preciso**, sobre todo en el final de palabra, y tiene **licencia
   permisiva (Apache-2.0)**. Pero en móvil es inviable a corto plazo: 1,1 GB de modelo, 2,3 GB de
   RAM en el PC y además exigiría compilar audio.cpp/ggml para Android (NDK + JNI), algo que el
   proyecto no soporta oficialmente. Junto a Supertonic (≈ 0,5 GB) superaría la RAM de la mayoría
   de móviles.
3. **Bloqueo de licencia de MMS**: CC-BY-NC-4.0 **prohíbe el uso comercial**. Si LinguaReader va
   a ser comercial, MMS no sirve en producción. Alternativas sin cambiar la arquitectura (el
   alineador CTC en Kotlin es genérico):
   - Un modelo wav2vec2 CTC de caracteres con licencia permisiva por idioma (p. ej. variantes
     Apache-2.0 para inglés/francés), exportado a ONNX. Hay que evaluarlos con este mismo corpus.
   - Qwen3 (Apache-2.0) en una fase posterior, si se acepta su coste de memoria o aparece una
     versión más pequeña o cuantizada a 4 bits que quepa.

## 5. Integración propuesta (siguiente paso)

1. Al sintetizar cada frase, el productor la alinea en segundo plano (mismo hilo de trabajo, después
   de Supertonic) y guarda los `TimedToken` junto al WAV en la caché, con clave
   audio + versión del alineador.
2. `AlignmentValidator` decide: si la cobertura es ≥ 90 %, los tiempos son monótonos y la confianza
   es suficiente → resaltado **por palabra**; si no → por frase (como ahora).
3. Durante la reproducción, la posición de ExoPlayer se traduce a palabra con
   `AlignmentTimeline.tokenAt` (búsqueda binaria), unas 30 veces por segundo.
4. Si la alineación de una frase no ha terminado cuando empieza a sonar, esa frase se resalta por
   frase: nunca se inventan tiempos.
5. Descarga opcional del modelo de alineación (317 MB) en Ajustes, con su licencia visible.

Coste estimado en el emulador: síntesis (RTF ≈ 0,6–0,9 en frases cortas) + alineación (RTF ≈ 0,4).
Con las pausas entre frases el productor mantiene ventaja, pero en móviles lentos puede quedarse
justo. Medir en un teléfono real antes de activarlo por defecto.

## 6. Reproducir

```powershell
.\gradlew.bat :engine:supertonic:alignCorpus      # corpus (requiere el modelo Supertonic en .models/)
python tools\eval_aligners.py --threads 12         # audio.cpp MMS + Qwen3 (requiere .models/audiocpp y aligners/)
.\gradlew.bat :engine:alignment:alignBench         # MMS ONNX (uint8/q4) en JVM
```

Descargas usadas (no versionadas, en `.models/`): audio.cpp v0.9.2
`audio-v0.9.2-bin-windows-x64-cpu-portable.zip`; GGUF de `audio-cpp/audio.cpp-gguf`
(`MMS-Forced-Aligner-GGUF/mms-forced-aligner-f16.gguf`,
`Qwen3-ForcedAligner-0.6B-GGUF/qwen3-forced-aligner-0.6b-q8_0.gguf`); ONNX de
`onnx-community/mms-300m-1130-forced-aligner-ONNX` (revisión `2100fb2`).

## 7. Experimento 1: wav2vec2-base-100h (Apache-2.0) — primera medición (3 frases)

`facebook/wav2vec2-base-100h` (95M parámetros, **Apache-2.0**, **solo inglés**, alfabeto A–Z + `'`).
No hay ONNX publicado: se exporta con `tools/export_wav2vec2_onnx.py` (PyTorch → ONNX opset 17;
fp16 con `convert_float_to_float16`; int8 por cuantización dinámica solo de MatMul, porque ORT CPU
no soporta `ConvInteger`). El alineador MMS no se toca: `Wav2Vec2OnnxAligner` y `Wav2Vec2Text`
reutilizan `CtcForcedAligner`, `Resampler` y la normalización de MmsOnnxAligner; solo cambia el
tokenizador (mayúsculas, blanco `<pad>`). Mismo paso de 20 ms por fotograma.

Medido en PC (Ryzen 9 3900XT, ORT 1.18, JVM) con las frases **en inglés con verdad de referencia**
del corpus (`Wav2Vec2AlignBench`, `alignBench`). **Muestra pequeña: 3 frases, 24 palabras.**

| Modelo | Disco | RTF (4 h.) | Carga | RAM (+JVM) | Error inicio (p90) | Error fin (p90) | ≤ 50 ms |
|---|---|---|---|---|---|---|---|
| MMS uint8 (referencia) | 317 MB | 0,18 | 0,87 s | +883 MB | 22 ms (40) | 71 ms (130) | 96 % |
| w2v2-base fp32 | 377 MB | 0,06 | 0,57 s | +902 MB | 41 ms (90) | 90 ms (130) | 83 % |
| w2v2-base fp16 | 189 MB | 0,10 | 0,66 s | +570 MB | 41 ms (90) | 90 ms (130) | 83 % |
| **w2v2-base int8** | **121 MB** | **0,06** | **0,28 s** | **+372 MB** | 40 ms (90) | 87 ms (130) | 83 % |

Lectura:
- int8 no pierde precisión frente a fp32 y es 2,6× más pequeño que MMS, 3× más rápido y usa ~58 %
  menos RAM. fp16 solo ahorra tamaño: en CPU x86 es más lento (convierte a fp32); en ARM con
  FP16 nativo podría mejorar, sin medir.
- Es menos preciso que MMS en inglés (≈ 18 ms más en el inicio, 83 % vs 96 % dentro de 50 ms), pero
  el error medio sigue por debajo del umbral de ~80 ms imperceptible para resaltar palabras.
- **Limitación**: solo inglés. Para francés/español habría que buscar modelos equivalentes
  (p. ej. wav2vec2 base/large por idioma con licencia permisiva); no se ha probado ninguno.
- **No medido**: Android (emulador o ARM real) ni frases naturales sin referencia. Falta un corpus
  mayor para confirmar la diferencia con MMS.

## 8. Corpus ampliado, emulador e integración (wav2vec2 EN y FR)

Corpus con verdad de referencia ampliado a **65 frases / 642 palabras** (33 EN con 323 palabras, 32 FR con
319; voces M1/F1 alternas; `alignCorpus`). Modelos: EN `facebook/wav2vec2-base-100h` (95M) y FR
`bofenghuang/asr-wav2vec2-ctc-french` (wav2vec2 large 7K, 315M), ambos **Apache-2.0**, exportados con
`tools/export_wav2vec2_onnx.py`. El vocabulario FR conserva acentos (`Wav2Vec2Text`; el blanco es `[PAD]` y `|`
es separador).

| Idioma · modelo | Disco | PC RTF | Emulador RTF | RAM pico emulador (PSS) | Inicio (p90) | Fin (p90) | ≤ 50 ms |
|---|---|---|---|---|---|---|---|
| EN · MMS uint8 (ref.) | 317 MB | 0,18 | 0,40 | 673 MB | 33 (60) | 93 (180) | 80 % |
| EN · w2v2 fp32 | 377 MB | 0,07 | – | – | 45 (100) | 109 (180) | 66 % |
| EN · w2v2 fp16 | 189 MB | 0,11 | 0,26 | 801 MB | 45 (100) | 109 (180) | 66 % |
| **EN · w2v2 int8** | **121 MB** | **0,07** | **0,23** | **461 MB** | 45 (100) | 109 (180) | 66 % |
| FR · MMS uint8 (ref.) | 317 MB | 0,19 | 0,39 | 661 MB | 44 (70) | 50 (120) | 60 % |
| FR · w2v2 fp32 / fp16 | 1262 / 631 MB | 0,15 / 0,26 | – | – | 55 (90) | 80 (140) | 44 % |
| **FR · w2v2 int8** | **355 MB** | 0,14 | 0,39 | 709 MB | 56 (90) | 81 (140) | 44 % |

- Emulador x86_64 (4 vCPU, sin AVX2); ARM real **sin medir**. PSS incluye la app (~115 MB) y no Supertonic.
- Con más datos los errores suben respecto a la primera medición (el corpus incluye voces y palabras sueltas
  menos fáciles), pero todos quedan por debajo de ~80 ms de media.
- **EN**: w2v2 int8 es la mejor opción práctica (121 MB, 2× más rápido que MMS, 30 % menos RAM), ~12 ms menos preciso.
- **FR**: w2v2 int8 es ~10 ms menos preciso que MMS y **no gana en tamaño ni velocidad** (355 MB vs 317 MB,
  mismo RTF); su ventaja es solo la licencia Apache-2.0 frente a CC-BY-NC.
- int8 = cuantización dinámica solo de MatMul: no pierde precisión frente a fp32.

### Integración en la app (opcional, por idioma de lectura)
- `ClipAligner` (puerto, `:core`) + `Wav2Vec2ClipAligner` (`:engine:alignment`): carga el modelo del idioma
  bajo demanda y lo libera tras 30 s de inactividad, al cambiar de idioma y al salir del lector.
- `RenderedReaderAudioController`: tras **encolar** cada clip lo alinea en segundo plano (nunca retrasa el
  audio), valida (monótona, cobertura ≥ 75 %, confianza ≥ 0,3) y resalta por palabra con
  `AlignmentTimeline.tokenAt` (búsqueda binaria, cada 40 ms). Sin modelo, con error o alineación no válida
  → resaltado por frase. La primera frase puede sonar por frase mientras se alinea.
- Ajustes → «Sincronía por palabra»: descarga/eliminación por idioma (catálogo `assets/aligners/catalog.json`,
  sha256 por fichero, reanudable, el idioma de lectura actual primero). Otros idiomas: sin modelo → por frase.
- **Alojamiento**: los `.onnx` los genera el proyecto (no existen publicados) y se publican como adjuntos de la release
  `packs-v1` del repositorio de GitHub; ver «Paquetes descargables» en docs/ARCHITECTURE.md (`tools/make_release.py`).
- Verificado en emulador: descarga de 121 MB con sha256 desde el servidor local, 4 frases alineadas (7/7, 10/10,
  6/6, 6/6 palabras; 0,33–0,64 s cada una), palabra resaltada en pantalla y estado «sincronía por palabra».
- Pendiente: alineaciones sin persistir (se recalculan al releer), medir en ARM real, MMS no se ofrece por su licencia.
