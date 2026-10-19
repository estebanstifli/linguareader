---
name: graded-book
description: Crear un libro graduado (MCER A1, A2, B1 o B2) en inglés, en texto plano, para content/graded-books — adaptando un clásico de dominio público o, en B2, preparando el texto original íntegro — y verificar su nivel. Usar cuando se pida crear o adaptar un libro para content/graded-books.
---

# Crear un libro graduado en texto plano

Objetivo: lecturas **amenas** que den ganas de seguir leyendo y enseñen el idioma. Prioriza ritmo,
humor, diálogo y escenas concretas sobre la exposición.

## Entrada
Título + nivel (A1, A2, B1 o B2), ambos opcionales:
- **Sin título**: abre `content/graded-books/README.md`, sección «Siguientes libros (roadmap)», y toma
  el primer libro con estado ⬜ (si se indica solo un nivel, el primero ⬜ de ese nivel). Respeta las
  notas de su fila y su **tipo**: `Adaptación` (se escribe) u `Original` (se prepara el texto íntegro).
- **Con un título que no está en la lista**: añádelo al roadmap antes de empezar.

Si no conoces bien la trama original, búscala antes (WebSearch) y quédate con los episodios clave;
si la conoces, no busques.

## Reglas por nivel (adaptaciones)

| | A1 | A2 | B1 | B2 |
|---|---|---|---|---|
| Palabras (sin títulos) | 3.000–3.500 | 4.000–5.500 (~4.500) | 9.000–12.000 (~10.500) | 15.000–22.000 (~18.000) |
| Palabras/frase (media) | 6–8 | 8–10, alguna de 15–20 | 10–14 | 12–18 |
| Flesch (aprox., lo da el script) | ≥ 95 | ≥ 88 | 70–85 | 60–75 |
| Tiempos | solo presente simple (+ `can`, `must`, `want to`) | pasado simple narrativo; algo de pasado continuo, `going to`, `will` | todos los pasados (incl. pasado perfecto), presente perfecto, 1.º y 2.º condicional, pasiva simple, estilo indirecto | todo: 3.er condicional, modales de deducción (`must have`), pasivas complejas, participios, inversión ocasional |
| Prohibido | pasados, perfectos, condicionales complejos, voz pasiva | pasado perfecto (`had seen`), presente perfecto narrativo, pasiva compleja | 3.er condicional frecuente, giros arcaicos, frases > 30 palabras | arcaísmos opacos sin apoyo del contexto |
| Vocabulario | básico; repetir palabras clave | básico + descriptivo (adjetivos, emociones, lugares) | B1 + léxico de la historia; phrasal verbs frecuentes | amplio; registro de época moderado; modismos |
| Capítulos | 8 de ~400 palabras | 8 de ~500–650 | 10–12 de ~900–1.000 | 14–18 de ~1.100–1.300 |

- **A1/A2**: frases simples (sujeto-verbo-complemento), mucho diálogo con `says`/`said`, repeticiones
  rítmicas típicas del cuento («He walks and walks»).
- **B1/B2**: novelas o novelas cortas. Conserva la trama completa, el final, los nombres y las
  escenas clave del original; resume subtramas secundarias en un párrafo en lugar de eliminarlas si
  explican el final. Varía la longitud de frase (cortas para la tensión, largas para describir) y deja
  que los personajes hablen con voz propia. En B2 puedes citar literalmente frases célebres del
  original (es dominio público) si son comprensibles.
- Las palabras propias de la historia que pasen del nivel (p. ej. *giant*, *witch*, *moor*,
  *harpoon*) se explican con el contexto y se anotan para el glosario.

## Formato del fichero
- Ruta: `content/graded-books/en/<NIVEL>/<slug>.txt`, UTF-8, saltos de línea `\n`.
- Párrafos separados por una línea en blanco; un párrafo por diálogo.
- Cabecera de capítulo: `Chapter N: Título corto` (≤ 70 caracteres, la reconoce `TextExtractor`).
- Última línea: `The End`. Sin metadatos dentro del texto.
- Comillas rectas `"`; evita comillas simples anidadas dentro de diálogos (rompen el conteo de frases).
- Ortografía **británica** en todos los libros (`colour`, `grey`, `neighbour`, `favourite`, `realise`,
  `travelling`, `towards`, `theatre`), salvo nombres propios y textos `Original`, que se dejan como están.

## Proceso: adaptación (sin pasos de más: cada lectura/reescritura cuesta tokens)
1. Antes de escribir, planifica mentalmente los capítulos con 2–3 escenas cada uno. Si el original es
   corto (Andersen, Gingerbread Man, Three Little Pigs), añade escenas fieles al tono (diálogos entre
   personajes, un viaje, un encuentro menor) **en el primer borrador**: ampliar después cuesta 2–3
   pasadas extra (~40 % más de tokens). Si es largo (novela en B1/B2), decide de antemano qué
   episodios entran y cuántas palabras recibe cada capítulo.
2. Escribe:
   - **A1/A2**: el libro completo de una vez con Write (A1 ≈ 450–550 frases; A2 ≈ 500–550 frases).
     Los borradores tienden a salir cortos: apunta al centro-alto del rango.
   - **B1/B2**: por bloques de 3–4 capítulos, cada uno con Write en el scratchpad
     (`<slug>.part1.txt`, `part2`…). Pasa el script al **primer** bloque para calibrar palabras y
     longitud de frase y corrige el rumbo en los siguientes (no reescribas el bloque). Al final,
     concatena: `cat part1 part2 … > content/graded-books/en/<NIVEL>/<slug>.txt` (solo el último
     bloque lleva `The End`).
   - Los reemplazos con apóstrofos o comillas no van en un heredoc de bash: escribe el script con
     Write en el scratchpad y ejecútalo.
3. Verifica:
   `PYTHONIOENCODING=utf-8 python -I tools/graded_check.py <fichero> --level <A1|A2|B1|B2> --show-long 5`
   - Palabras dentro de rango; media de palabras/frase y Flesch dentro del objetivo.
   - A1: pasados = 0 (son falsos positivos `put`, `lost`/`stuck`/`surprised` como adjetivo, `left` = izquierda).
   - A1/A2: busca tiempos perfectos: `grep -noE "\b(had|has|have) (been|seen|done|gone|[a-z]+ed)\b" <fichero>`.
   - B1: revisa que las frases más largas (`--show-long 10`) no pasen de ~30 palabras.
4. Si se sale de rango, corrige con reemplazos puntuales (script Python con `assert count==1`), no
   reescribas el libro entero. Para recortar, fusiona frases cortas consecutivas y elimina escenas
   secundarias completas; para alargar, añade una escena entera, no adjetivos sueltos. Vuelve a verificar.

## Proceso: original íntegro (solo B2, tipo `Original`)
Para obras cuyo texto original ya es legible en B2 (prosa clara, Flesch ≥ 60). No se reescribe nada:
se prepara el texto y se documenta.
1. Fuente preferida: **Standard Ebooks** (ediciones dedicadas al dominio público con CC0, texto
   corregido). Descarga el HTML de una página en una carpeta propia del scratchpad:
   `curl -s https://standardebooks.org/ebooks/<autor>/<obra>/text/single-page -o <dir>/<slug>.html`
   (el slug se comprueba con `curl -s "https://standardebooks.org/ebooks?query=<autor>" | grep -oE 'href="/ebooks/[a-z0-9-]+/[a-z0-9-]+"'`).
   Si la obra no está allí, Project Gutenberg en texto plano: quita la cabecera y la licencia de
   Gutenberg y toda mención a la marca (su licencia lo exige para redistribuir el texto).
2. Lista los capítulos: `python -I tools/se_to_txt.py <dir>/<slug>.html --list`. En colecciones de
   relatos elige una selección (`--only 1,2,3,10`) que sume 20.000–40.000 palabras.
3. Convierte: `python -I tools/se_to_txt.py <dir>/<slug>.html content/graded-books/en/B2/<slug>.txt [--only …]`.
4. Verifica: `… graded_check.py <fichero> --level B2 --original`. Medias de frase de hasta ~20 y Flesch
   60–80 son aceptables en un original. Revisa solo las primeras ~40 líneas y un `grep` de restos
   (`grep -nE "\[|\*|http|Gutenberg" <fichero>`); **no leas el libro entero**.
5. El glosario de un original sale de la lista de palabras largas del script más los términos de época
   que conozcas de la obra (unas 20–30).

## Al terminar (los dos tipos)
Solo cuando el libro está redactado **y** verificado (todo dentro de rango), actualiza
`content/graded-books/README.md`. Es la memoria del proyecto: el historial se borra entre libros.
- Roadmap: cambia ⬜ por ✅ en la fila del libro.
- Catálogo: añade la fila (palabras, palabras/frase, Flesch, obra original, licencia; en los
  originales indica la edición: «Standard Ebooks (CC0)» y su URL en la fila de notas).
- Vocabulario fuera de nivel: añade la línea del libro para el glosario.
Si el libro no pasa la verificación, déjalo ⬜ y anota en «Notas» qué falta.

## Derechos
- El original debe ser de dominio público **en la UE y en EE. UU.**: autor fallecido hace más de 70
  años (antes de 1956) **y** obra publicada hace más de 95 años en EE. UU. Ejemplos válidos: Grimm,
  Perrault, Andersen, Kipling, Collodi, Dickens, Austen, Stevenson, Wells (†1946), Fitzgerald (†1940;
  *Gatsby*, 1925).
- *Peter Pan*: verificar antes (en Reino Unido el Great Ormond Street Hospital conserva un derecho
  especial).
- Escribe la adaptación desde cero a partir del original; no copies adaptaciones graduadas ajenas
  (p. ej. las de MovaReader, Penguin Readers u Oxford Bookworms), ediciones modernas ni películas:
  nada de nombres, escenas o detalles inventados por el cine (Disney, los zapatos de rubí de la
  película de *Oz* de 1939, el «Igor» de *Frankenstein*, etc.).
