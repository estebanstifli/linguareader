# Modelo de Supertonic con tiempos internos en Android

La aplicación conserva los pesos FP32 originales y su revisión fijada. El grafo de inferencia expone `timing_attention`, una matriz pequeña `[frames_latentes, caracteres]` que promedia las cabezas 2 y 7 de la atención del bloque 21, rama condicional 0. El decodificador de tiempos consume esa salida durante la síntesis; no ejecuta reconocimiento de voz ni descarga un alineador.

## Entrega y tamaño

El APK contiene únicamente una descripción comprimida del grafo (37.906 bytes gzip antes del empaquetado del APK). Las matrices entrenadas se descargan de `files.lingoaudiobook.eu/models/supertonic-3/<revisión>/`, una copia en R2 con los mismos bytes y licencia del archivo oficial. `SupertonicModelDir.ensureTimingModel()` descomprime la descripción, comprueba su SHA-256 y la publica atómicamente junto al modelo como `onnx/vector_estimator.timing.onnx`.

El grafo usa `TensorProto.external_data` para reutilizar 351 rangos de pesos FLOAT mediante offsets dentro del `vector_estimator.onnx` original. Los valores no se convierten ni redondean. Los pequeños tensores de forma y constantes permanecen dentro del grafo porque ONNX Runtime los necesita durante la inferencia de formas. No se conserva una segunda copia de los 256 MB de pesos del estimador.

| Elemento | Bytes | MB decimales |
|---|---:|---:|
| Descarga original completa, 17 ficheros | 401.291.751 | 401,29 |
| Grafo derivado adicional | 506.212 | 0,51 |
| Modelo instalado, sin marcador de verificación | 401.797.963 | 401,80 |
| Datos comprimidos del grafo incluidos en el código | 37.906 | 0,038 |

Los pesos base no se han reducido con pérdida de precisión. El ahorro principal del conjunto instalado procede de retirar los alineadores externos: aproximadamente 121,94 MB por idioma anteriormente descargado; EN+ES+FR sumaban 365,82 MB. Esto no aumenta la descarga base de Supertonic: el grafo pequeño se distribuye con la aplicación.

La app descarga desde R2; Hugging Face se conserva como referencia de procedencia. Los SHA-256 de los ficheros originales y la revisión no cambian al mover la descarga, por lo que no se repiten descargas existentes ni se invalida el audio cacheado. Una instalación existente obtiene el grafo durante la siguiente carga del motor; una descarga nueva lo prepara al terminar la verificación. El grafo se comprueba al cargar y se reconstruye si está dañado. El modelo original no se modifica. La clave de caché de la integración de tiempos es `trim1-attn1-unicode1`.

`python -I tools/publish_supertonic.py` valida y muestra el plan sin red; `--upload` publica y verifica, y `--verify-remote` verifica la copia pública sin modificarla. Se incluyen `LICENSE`, `NOTICE.txt`, `SHA256SUMS` y un manifiesto de revisión. El publicador rechaza sustituir contenido distinto bajo una clave inmutable. La prueba Android comprobó la descarga completa de los 17 archivos desde R2, una reanudación con `Range: bytes=512-` y respuesta 206, reparación del grafo y síntesis con marcas.

## Optimización elegida y alternativa descartada

Se comparó el grafo compacto con pesos incorporados frente al grafo de pesos externos sobre tres frases ES/EN/FR, cinco pasos y cuatro hilos CPU, utilizando ONNX Runtime 1.31.0. El audio fue **idéntico muestra por muestra** y las fronteras de palabras también fueron idénticas. El [resumen histórico](SUPERTONIC_RESEARCH_HISTORY.md) conserva los resultados y límites de esas pruebas de escritorio; las [evidencias Android](../artifacts/supertonic-internal-android/VALIDACION.txt) documentan la integración productiva.

También se construyó y ejecutó una variante INT8 dinámica, limitada a MatMul con pesos constantes y cuantización por canal. El estimador pasó de 256.535.187 a 242.030.858 bytes: solo 14,50 MB menos. No aportó una mejora consistente de velocidad en estas tres frases y alteró la forma de onda y algunas fronteras hasta 120 ms respecto a FP32. La diferencia de muestras no prueba por sí sola una pérdida perceptual, pero sí invalida la equivalencia y exigiría otra validación. **La variante INT8 fue descartada; no se instala ni se descarga en Android.** Sus binarios locales están incluidos en la limpieza pendiente.

No se presenta FP16 como una reducción gratuita para la CPU: ONNX Runtime documenta limitaciones de ejecución de operadores FP16 en CPU, y convertir pesos/reintroducir casts necesita mediciones específicas. Mantener FP32 conserva el comportamiento que se estaba validando mientras se elimina el coste de los modelos de alineación.

## Reproducir el grafo

Desde la raíz del repositorio, con Python 3.13 y el modelo original verificado en `.models/`. El entorno se crea solo si se necesita regenerar el grafo; no forma parte de la app:

```powershell
python -m venv .models/.venv-supertonic-graph
.\.models\.venv-supertonic-graph\Scripts\python.exe -m pip install -r tools/requirements-supertonic-graph.txt
.\.models\.venv-supertonic-graph\Scripts\python.exe -X utf8 tools/build_supertonic_timing_graph.py --kotlin
```

El programa exige el SHA-256 del estimador original fijado, calcula los offsets reales de sus tensores, conserva las constantes necesarias y genera el Kotlin comprimido. `onnx.checker` valida el archivo resultante contra los datos originales. No descarga ni cambia los pesos. El manifiesto se escribe en `.models/supertonic-3/<revisión>/timing-graph-manifest.json`; `--manifest` permite elegir otro destino. No depende del antiguo laboratorio.

Identidad del grafo de producción:

```text
3497a339b21f4489487c6a7b3fa25a138bc9afd20e89e847c5558913071a6716
```

Se conserva el [benchmark de candidatos en JSON](supertonic-evidence/candidate-benchmark.json) como evidencia histórica. Los modelos, entornos y scripts experimentales están incluidos en la limpieza autorizada; su borrado automático fue bloqueado por la política de la herramienta. El script local `tools/cleanup_supertonic_lab.ps1` permite revisarla y ejecutarla con `-Apply`. Tras ese borrado, el resumen conservado no permite reproducir el estudio completo sin recuperar o regenerar sus datos.

Pruebas relevantes: `SupertonicTimingGraphTest` comprueba descompresión, hash, tamaño y rechazo de una instalación sin verificar; las pruebas funcionales del motor ejercitan la preparación y carga del grafo real. La revisión humana de precisión sigue pendiente: integrar el extractor no transforma el acuerdo con Whisper en precisión humana certificada.

Fuentes técnicas: [formato ONNX External Data](https://onnx.ai/onnx/repo-docs/ExternalData), [consideraciones FP16 de ONNX Runtime](https://onnxruntime.ai/docs/performance/model-optimizations/float16.html).
