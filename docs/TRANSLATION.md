# Traducción

## Proveedores (por orden)

`FallbackTranslationEngine` prueba cada proveedor y se queda con el primero que traduce:

1. **Incluido en el libro** (`BundledTranslationEngine`): traducciones por frase y glosario del
   manifiesto. Revisadas, instantáneas y sin red. Solo existen en la biblioteca incluida.
2. **ML Kit en el dispositivo** (`MlKitTranslationEngine`): para todo lo demás, es decir libros
   importados, palabras fuera del glosario e idiomas que el libro no trae. La UI lo indica con la
   etiqueta «Traducción automática».

Ningún proveedor inventa. Si no hay traducción, se muestra el motivo: no disponible, mismo idioma,
par no soportado o **falta el modelo**.

## Modelos de ML Kit

- **Un modelo por idioma, emparejado con inglés** (en el dispositivo, carpeta `en_<idioma>`): unos
  40 MB cada uno (44 MB medidos para español). El inglés es el puente y no se descarga aparte.
  Para traducir de francés a español pesan **los dos**: el del idioma original (francés) y el del
  idioma de traducción (español), unos 80 MB en total. Si el libro ya está en el idioma de
  traducción, no se traduce nada. Se descargan una sola vez; después todo funciona sin conexión.
- **Nunca se descargan sin avisar.** Si falta un modelo, el traductor responde `MODEL_MISSING` y la
  UI ofrece descargarlo: aviso en el lector, Ajustes o pretraducción de un libro.
- Preferencia «solo con Wi-Fi», activada por defecto.
- `TranslationModelManager`: lista, descarga (en el scope de la app, sigue aunque se cierre la
  pantalla) y borra modelos.

## Primera apertura

`TranslationSetupGate` muestra una pantalla para elegir el idioma de traducción, por defecto el
del sistema si ML Kit lo admite y si no inglés, y descargar su modelo. Se puede omitir («Ahora no»)
y cambiar después en **Ajustes → Traducción automática**, donde también se gestionan los modelos.

## Cuándo se traduce

| Situación | Qué ocurre |
|---|---|
| Modo «traducción al tocar» (por defecto) | No se traduce nada en segundo plano. Solo al tocar una palabra o frase. |
| Modo «traducción bajo cada frase» | Se traduce cada pasaje al llegar a él. |
| Importar un libro → «Mientras leo» | Como el punto anterior. |
| Importar un libro → «Traducir el libro entero ahora» | `BookTranslationService` traduce todas las frases en segundo plano, con barra de progreso y botón «Cancelar traducción» en la tarjeta del catálogo. Al terminar, la tarjeta muestra «Traducción automática: Español». |
| Libro importado sin traducir entero | La tarjeta ofrece «Traducir el libro entero al …» cuando se quiera. |

### Cancelar y continuar

«Cancelar traducción» no pierde nada: guarda hasta dónde llegó (`summary.json`) y la tarjeta pasa a
«Traducción al español pausada: 45 % (x/y frases)» con el botón **Continuar traducción**, que puede
pulsarse días después. Lo ya traducido sale de la caché, así que solo se traduce lo que faltaba.
Una pausa del usuario **no se reanuda sola** al abrir la app; una interrupción por cierre del
proceso sí.

## Caché

Tabla Room `machine_translations` (migración automática 1→2), con clave
`(motor, origen, destino, sha256 del texto)`. La comparten el lector y la pretraducción, así que una
frase ya traducida sale al instante. La pretraducción es **reanudable**: el idioma pendiente se
guarda en el `summary.json` del libro y al reabrir la app continúa desde donde iba, porque las
frases ya hechas salen de la caché.

## Limitaciones

- ML Kit traduce cada texto **sin el contexto** de las frases vecinas. Una palabra suelta puede
  traducirse mal; la ficha muestra también la traducción de la frase entera.
- Un libro largo puede tardar minutos en pretraducirse (depende del dispositivo). Si Android
  cierra la app, se reanuda al volver a abrirla (sin WorkManager todavía).
- **Licencia y datos:** ML Kit se rige por los términos de servicio de Google ML Kit y no es
  código abierto. Antes de publicar en Play hay que revisar esos términos y declarar en la ficha de
  datos lo que recoge el SDK.

## Pruebas

`MlKitTranslationTest` (en dispositivo o emulador con red; sin red se omite) cubre la descarga real
de modelos FR/ES, la traducción, el acierto de caché, los pares no soportados y la pretraducción
completa de un libro importado. `TranslationSetupTest` cubre la pantalla inicial.
`ReaderFlowTest.tappingAWordWithoutGlossaryEntryFallsBackToMachineTranslation` cubre una palabra
sin glosario: se traduce con ML Kit o se pide su modelo.
