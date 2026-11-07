# Antecedentes de los tiempos internos de Supertonic

Resumen conservado el 6 de noviembre de 2026 al preparar la retirada del laboratorio después de integrar el extractor en Android. Se mantienen el código de producción, su generador del grafo, las pruebas de regresión y las [evidencias de integración](../artifacts/supertonic-internal-android/VALIDACION.txt). La limpieza incluye copias de modelos, Whisper, entornos Python, prototipos C++, audios y capturas de atención. Este resumen conserva las conclusiones; después de borrar los datos originales no permite repetir íntegramente el estudio ASR sin recuperarlos o regenerarlos.

**Estado de la limpieza:** autorizada por el usuario, pero el borrado fue bloqueado por la política de ejecución de la herramienta. Los 12,49 GB todavía están en disco. Se ha preparado `tools/cleanup_supertonic_lab.ps1` con simulación por defecto y ejecución local mediante `-Apply`; el resultado se registra en `artifacts/supertonic-internal-android/lab-cleanup.json`.

## Receta incorporada

El modelo usa cinco pasos de síntesis. El extractor promedia las cabezas 2 y 7 del bloque 21, rama condicional, durante los pasos 1–4. Suma la atención por palabra, normaliza por la raíz de su longitud y obtiene una trayectoria monótona tras interpolar a un cuarto del paso temporal. Refina los bordes con energía RMS en ventanas de 10 ms a −25 dB y desplazamientos globales de −40 ms. El paso latente original equivale a 69,66 ms; la interpolación no demuestra esa mayor resolución acústica.

La [configuración congelada original](supertonic-evidence/frozen-config.json) tenía SHA-256 `ff5cca265e75bd1f7ef35ff31cb9d4ecd63f990ffa982759eb13fc8ff1cc4705`. Conserva como procedencia nombres y hashes de antiguos archivos, que ya no se distribuyen. Los valores productivos están en `SupertonicWordTimings.kt`, y ocho casos de regresión con atención, energía y fronteras esperadas permanecen en `engine/supertonic/src/jvmTest/resources/word-timing-golden.json` (267.067 bytes).

## Evaluación automática previa

El criterio solicitado fue inicio **y** final a ≤100 ms de la referencia, incluyendo omisiones y unidades sin correspondencia en el denominador. No se obtuvieron anotaciones humanas.

| Muestra | Referencia | Aciertos conjuntos | Acuerdo |
|---|---|---:|---:|
| 24 frases reservadas ES/EN/FR, 229 palabras | Whisper small + VAD | 181/229 | 79,04 % |
| Los mismos audios y predicciones reservados | Whisper medium + VAD | 27/229 | 11,79 % |
| 248 frases nuevas en 31 idiomas, extractor original | Whisper small + VAD | 1.825/2.580 | 70,74 % |
| Las mismas 248 frases, extractor original | Whisper medium + VAD | 187/2.580 | 7,25 % |
| Corpus de 31 idiomas, corrección Unicode hindi | Whisper small + VAD | 1.837/2.580 | 71,20 % |
| Corpus de 31 idiomas, corrección Unicode hindi | Whisper medium + VAD | 190/2.580 | 7,36 % |

El conjunto inicial reservaba ocho frases por idioma después de elegir parámetros con otras ocho. El intervalo exploratorio del acuerdo reservado con small, remuestreando frases, fue 71,6–86,2 %. No incluye sesgo de la referencia automática. En el corpus multilingüe, medium situaba las fronteras una mediana de 100 ms después de small en las 2.097 unidades comparables por ambos. El extractor se calibró contra small: la mayor concordancia con esa referencia no prueba que sea más exacta que medium.

Se generaron inicialmente 247/248 frases, con tiempos en los 31 idiomas y cero intervalos inválidos. La restante contenía `؛`, desconocido para el índice original; su normalización a `;` se comprobó aparte y luego se incorporó al frontend productivo. La corrección de hindi conserva letras, marcas vocálicas y orden Unicode. El cribado multilingüe usa unidades explícitas para japonés, eojeol para coreano y unidades ortográficas para vietnamita; no todas equivalen a palabras léxicas. Android conserva el tokenizador del lector.

**No se demostró un 80 % de precisión humana en los 31 idiomas.** La trayectoria monótona tampoco es un detector fiable de omisiones del TTS. La revisión humana sigue siendo la referencia pendiente.

## Decisiones de integración y tamaño

El grafo con pesos externos reutiliza 351 tensores del estimador original. Ocupa 506.212 bytes (37.906 comprimidos) y conserva el audio y los límites exactamente frente a la variante FP32 con pesos incorporados en tres controles ES/EN/FR. Su identidad y construcción están en [SUPERTONIC_TIMINGS_MODEL.md](SUPERTONIC_TIMINGS_MODEL.md).

Se descartó INT8 dinámica de MatMul: reducía el estimador experimental de 256.535.187 a 242.030.858 bytes, solo 14,50 MB, sin acelerar consistentemente. Cambiaba el audio y algunas fronteras hasta aproximadamente 120 ms. Se conserva el [resultado numérico de candidatos](supertonic-evidence/candidate-benchmark.json), sin sus binarios ni audios. La ruta FP32 de Android mantiene los pesos oficiales.

El cribado de escritorio produjo 20,69 minutos de audio en 219,13 s de inferencia CPU (Ryzen 9 3900XT, cuatro hilos); el decodificador sumó 0,809 s, mediana 2,80 ms/frase. Excluía carga y escritura. El prototipo C++ solo se validó en ES/EN/FR y no se incorporó a Android. Estas mediciones no se atribuyen a un teléfono: la [validación Android posterior](../artifacts/supertonic-internal-android/VALIDACION.txt) conserva sus propias pruebas y medidas.
