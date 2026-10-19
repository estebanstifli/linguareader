# Lector paginado

El lector muestra el libro a pantalla completa en páginas que caben exactas en la pantalla, como un lector
de libros clásico. Funciona igual con voz (modo aprendizaje) y sin ella (lector clásico).

## Modos de uso

- **Pregunta inicial** (primera apertura): «Aprender idiomas» (voz, resaltado, controles de reproducción) o
  «Solo leer». Se cambia en Ajustes o con el altavoz de la barra superior del lector (`UserPreferences.ttsMode`).
- **Sin modo TTS** no se genera audio: ni preparación al abrir (Supertonic) ni precarga de páginas.
- **Temas**: Sistema, Claro, Oscuro y **Sepia**.

## Gestos

| Gesto | Acción |
|---|---|
| Deslizar a izquierda/derecha | Página siguiente/anterior (la única forma de pasar página) |
| Toque en el centro | Mostrar/ocultar barras (y las del sistema) |
| Toque en los lados (25 % del ancho) | Nada |
| Pulsación larga sobre una palabra | Ficha de traducción (palabra, expresión y frase) |

TalkBack: acciones «Página siguiente», «Página anterior», «Mostrar u ocultar controles» y «Traducir frase».

## Barras

- **Superior**, en este orden: volver · índice · buscar · altavoz (activa/desactiva el modo TTS; al activarlo lee
  desde la página visible) · ajustes de lectura · ⋮ (compartir archivo, Agregar a… —Favoritos, Para leer, Leídos,
  colecciones propias, nueva colección—, enviar a la papelera y Acerca del documento).
- **Índice** con tres pestañas: Capítulos (solo títulos, con la página donde empieza cada uno), Marcadores (se
  ponen con el icono de marcador de la barra inferior) y Vocabulario guardado de ese libro (lleva a su frase).
- Título, autoría, capítulo y «página n/total» dentro del capítulo.
- **Inferior**: «página de total» del libro, barra para saltar a cualquier página, orientación
  (vertical → horizontal → automática) y marcador de la página.
- **Modo TTS**: controles de reproducción compactos (≈40 % menos de alto), siempre visibles. El icono de la
  cabeza que habla abre la lista de voces del motor (solo si tiene más de una; en Android TTS, «Automática» elige
  la mejor sin conexión).
- «Leer desde aquí» (ficha de una palabra) activa el modo TTS si estaba apagado: aparecen altavoz y controles.
- **Voz con pantalla apagada**: ajuste normal («Seguir leyendo con la pantalla apagada»), activado por defecto. No
  necesita permisos; Android 13+ pide una vez el de notificaciones para mostrar el aviso con «Pausar».

## Paginación (`ui/reader/paging/`)

1. `BookBlocks`: libro → bloques (título de capítulo, ilustración, texto original, traducción) según el modo
   de visualización. «Traducción al tocar» se pagina como «Solo texto»: la traducción sale en la ficha.
2. `paginate` mide cada bloque una sola vez con `TextMeasurer` al ancho de la página (fuera del hilo principal).
3. `PageBuilder` (puro, con tests de JVM) corta por líneas enteras: los pasajes fluyen en la misma página, cada
   capítulo empieza en página nueva y una imagen que no cabe pasa a la siguiente.
4. Cada página guarda dónde empieza en el texto (`TextPos`: pasaje + offset). Al cambiar tamaño de letra,
   interlineado, modo, orientación, ilustraciones o al llegar traducciones, se vuelve a paginar y el lector
   sigue en la misma posición del texto.

Justificación sin descuadres:
- Cada trozo de página dibuja **el bloque entero** (párrafo, frase o traducción), con el mismo estilo y ancho con
  que se midió, y recorta solo sus líneas (`clipToLines`). La maquetación es idéntica a la medida: justificado,
  guiones automáticos (el guion se ve al final de la página) y reparto de espacios coinciden, y la última línea de
  la página queda justificada como en el párrafo real.
- Ajuste «Alineación»: Izquierda, Justificado, Justificado + guiones (por defecto). Con justificado se usa el salto
  de línea «de párrafo» (mejor reparto de espacios); a la izquierda, el simple.
- Recortar a mano un trozo del texto (versión anterior) no sirve: Android encoge espacios al justificar y la última
  línea de un trozo no se justifica, así que se salía del margen.
- Altura de línea fija sin recorte; márgenes con `*IgnoringVisibility` (no re-pagina al mostrar/ocultar barras).
- Ilustraciones enteras (`Fit`), sin recortar.
- Medido: *Pride and Prejudice* (≈120 000 palabras, 62 capítulos, 911 páginas) se pagina en < 2 s en el emulador.

## Voz y páginas

- La página sigue a la voz (palabra activa o, si no hay tiempos por palabra, la frase) si «Pasar página
  automáticamente» está activo.
- Pasar página a mano: leyendo, la lectura salta al principio de esa página; en pausa, se reanudará desde ahí.
  Los cambios de página automáticos (seguir la voz, saltos del índice, re-paginar) no mueven la lectura
  (`PageNavigator.programmaticTarget`).
- La posición guardada es la del principio de la página visible.
- **Voz con pantalla apagada**: `ReadingService` (servicio en primer plano `mediaPlayback`, notificación con
  «Pausar» y wake lock parcial) mientras suena; se para solo tras 5 s sin lectura.

## Biblioteca

- **Menú lateral** (☰): Leyendo ahora · Libros · Aprender inglés · Aprender francés · Aprender otros idiomas ·
  Favoritos · Para leer · Leídos · Colecciones · Papelera · Vocabulario · Ajustes, con el número de libros de cada
  apartado. Cada apartado es la misma biblioteca filtrada, con su título arriba y subfiltros: procedencia (Todos /
  De la app / Importados) en Libros; niveles A1–C2 y «Traducir al» en Aprender…; idioma en otros idiomas; colección
  en Colecciones.
- **Vista**: icono arriba para alternar lista (fichas) y cuadrícula de portadas (`libraryGrid`, persistente). La
  cuadrícula usa columnas adaptables (≥104 dp): 3 en un móvil en vertical, más en horizontal o en tablet. Los
  libros sin portada llevan una generada con el título; cada portada tiene nivel, progreso y un ⋮ con estanterías,
  traducción y papelera.
- Estanterías fijas (Favoritos, Para leer, Leídos) y colecciones propias; papelera. Room v3 (`collections`,
  `collection_books`, `trash`), migración automática.
- En la biblioteca: filtro por estantería y Papelera, con Restaurar y Borrar definitivamente (solo importados;
  los incluidos en la app solo se restauran). El botón de borrar de una tarjeta envía a la papelera.

## Importación de EPUB y capítulos

Los conversores (p. ej. el de Project Gutenberg) parten capítulos entre archivos y el índice apunta a anclas a
mitad de archivo. Lo que hay antes del primer encabezado de un archivo, si el índice apunta más adelante, es la
continuación del capítulo anterior: se une a él (antes salían capítulos falsos con el pie de una lámina como
título). Las letras capitulares dibujadas con puntuación en el `alt` («“I») se conservan como texto.

## Pendiente

- Paginar primero el capítulo visible si algún libro tarda (hoy se pagina entero en segundo plano).
- Renombrar colecciones desde la biblioteca.
