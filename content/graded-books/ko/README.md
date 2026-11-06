# 한국어 번역 (ko)

Fichero de control de las traducciones a este idioma (lo leen `tools/translate_runner.py`,
`tools/library_meta.py` y la skill `/graded-translate`). Una fila por libro: ⬜ pendiente, ✅
traducido y verificado con `tools/translation_check.py`. Mismo nivel MCER, mismos capítulos y
mismo número de párrafos que el original inglés (1:1, para la vista bilingüe). Licencia de todas
las filas: traducción propia de la adaptación propia (la del libro inglés).

- Variedad: coreano estándar de Seúl; narración en estilo -다 (해라체), diálogos en 해요체 o 반말 según la relación.
- Diálogos: comillas “…” con verbo de cita: `“일곱!” 하고 그가 말해요.`; nunca « » ni ".
- Cabecera de capítulo: `제N장: 제목`; última línea: `끝`.
- Legibilidad: Sílabas/palabra (orientativo; sin índice estándar).

Orden de trabajo: `/graded-translate` sin libro (o `tools/translate_runner.py --lang ko`)
toma la primera fila ⬜. Al terminar, la skill pone ✅ y rellena fichero, título y métricas; el
glosario de abajo recibe una entrada con el título traducido.

## Libros

| # | Estado | Nivel | Original (en) | Fichero | Título | Palabras | Palabras/frase | Legibilidad | Notas |
|---|---|---|---|---|---|---|---|---|---|
| 1 | ⬜ | A1 | `en/A1/the-brave-little-tailor.txt` |  |  |  |  |  |  |
| 2 | ⬜ | A1 | `en/A1/hansel-and-gretel.txt` |  |  |  |  |  |  |
| 3 | ⬜ | A1 | `en/A1/little-red-riding-hood.txt` |  |  |  |  |  |  |
| 4 | ⬜ | A1 | `en/A1/snow-white.txt` |  |  |  |  |  |  |
| 5 | ⬜ | A1 | `en/A1/cinderella.txt` |  |  |  |  |  |  |
| 6 | ⬜ | A1 | `en/A1/the-three-little-pigs.txt` |  |  |  |  |  |  |
| 7 | ⬜ | A2 | `en/A2/puss-in-boots.txt` |  |  |  |  |  |  |
| 8 | ⬜ | A2 | `en/A2/the-emperors-new-clothes.txt` |  |  |  |  |  |  |
| 9 | ⬜ | A2 | `en/A2/beauty-and-the-beast.txt` |  |  |  |  |  |  |
| 10 | ⬜ | A2 | `en/A2/the-little-mermaid.txt` |  |  |  |  |  |  |
| 11 | ⬜ | A2 | `en/A2/the-adventures-of-pinocchio.txt` |  |  |  |  |  |  |
| 12 | ⬜ | B1 | `en/B1/treasure-island.txt` |  |  |  |  |  |  |
| 13 | ⬜ | B1 | `en/B1/a-christmas-carol.txt` |  |  |  |  |  |  |
| 14 | ⬜ | B1 | `en/B1/around-the-world-in-eighty-days.txt` |  |  |  |  |  |  |
| 15 | ⬜ | B2 | `en/B2/frankenstein.txt` |  |  |  |  |  |  |
| 16 | ⬜ | B2 | `en/B2/pride-and-prejudice.txt` |  |  |  |  |  |  |

## Vocabulario fuera de nivel (para glosario)

Palabras propias de la historia que pasan del nivel; el contexto las explica, pero conviene
incluirlas en el glosario del libro. Una entrada por libro, con el título traducido.
