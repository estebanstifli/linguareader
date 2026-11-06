# Tłumaczenia na język polski (pl)

Fichero de control de las traducciones a este idioma (lo leen `tools/translate_runner.py`,
`tools/library_meta.py` y la skill `/graded-translate`). Una fila por libro: ⬜ pendiente, ✅
traducido y verificado con `tools/translation_check.py`. Mismo nivel MCER, mismos capítulos y
mismo número de párrafos que el original inglés (1:1, para la vista bilingüe). Licencia de todas
las filas: traducción propia de la adaptación propia (la del libro inglés).

- Variedad: polaco estándar (*ty* en familia y amigos, *pan / pani* con desconocidos).
- Diálogos: pauza (`—`) al inicio del diálogo: `— Siedem! — mówi. — Siedem za jednym zamachem!`; citas y carteles con „…”.
- Cabecera de capítulo: `Rozdział N: Tytuł`; última línea: `Koniec`.
- Legibilidad: FOG-PL (< 7 bardzo łatwy, 7-9 łatwy, 10-12 średni).

Orden de trabajo: `/graded-translate` sin libro (o `tools/translate_runner.py --lang pl`)
toma la primera fila ⬜. Al terminar, la skill pone ✅ y rellena fichero, título y métricas; el
glosario de abajo recibe una entrada con el título traducido.

## Libros

| # | Estado | Nivel | Original (en) | Fichero | Título | Palabras | Palabras/frase | Legibilidad | Notas |
|---|---|---|---|---|---|---|---|---|---|
| 1 | ✅ | A1 | `en/A1/the-brave-little-tailor.txt` | `pl/A1/dzielny-krawczyk.txt` | Dzielny krawczyk | 2.525 | 5,2 | FOG-PL 3 | Lema: «Siedem za jednym zamachem»; al rey, «Najjaśniejszy Panie» + 3.ª persona |
| 2 | ✅ | A1 | `en/A1/hansel-and-gretel.txt` | `pl/A1/jas-i-malgosia.txt` | Jaś i Małgosia | 2.299 | 5,1 | FOG-PL 3 | Rimas: «Chrup, chrup, chrup, jak mała myszka…», «To wiatr, to wiatr, to wiatr z nieba…», «Kaczuszko, kaczuszko, pomóż nam dziś!…» |
| 3 | ✅ | A1 | `en/A1/little-red-riding-hood.txt` | `pl/A1/czerwony-kapturek.txt` | Czerwony Kapturek | 2.510 | 6,0 | FOG-PL 3 | Fórmula: «Babciu, jakie masz wielkie uszy!» / «Żeby cię lepiej słyszeć, moje dziecko»; ella al lobo, «panie Wilku» |
| 4 | ✅ | A1 | `en/A1/snow-white.txt` | `pl/A1/krolewna-sniezka.txt` | Królewna Śnieżka | 2.536 | 5,1 | FOG-PL 4 | Lustro: «Lustereczko, powiedz przecie, kto jest najpiękniejszy w świecie?» / «Moja królowo, ty jesteś najpiękniejsza na świecie»; enanitos: krasnoludki |
| 5 | ✅ | A1 | `en/A1/cinderella.txt` | `pl/A1/kopciuszek.txt` | Kopciuszek | 2.646 | 6,0 | FOG-PL 4 | Apodo de Javotte: «Popielucha»; «Szast!» para la magia; desconocidos y princesa: *pani* / «Wasza Wysokość» |
| 6 | ✅ | A1 | `en/A1/the-three-little-pigs.txt` | `pl/A1/trzy-male-swinki.txt` | Trzy małe świnki | 2.745 | 7,0 | FOG-PL 4 | Estribillo: «Świnko, świnko, wpuść mnie do środka» / «Nie, nie! Na mój mały ryjek, za nic w świecie!» / «To dmucham i chucham, i zdmuchuję twój domek!»; Polly al lobo, «panie Wilku»; Mr Smith → pan Kowalski |
| 7 | ✅ | A2 | `en/A2/puss-in-boots.txt` | `pl/A2/kot-w-butach.txt` | Kot w butach | 3.266 | 7,1 | FOG-PL 5 | Marquis of Carabas → markiz Karabas; ogre → ludożerca; Kot a Pierre: *ty* + «panie»; al rey, «Najjaśniejszy Panie» + 3.ª persona; rey y princesa a Pierre: *pan* |
| 8 | ✅ | A2 | `en/A2/the-emperors-new-clothes.txt` | `pl/A2/nowe-szaty-cesarza.txt` | Nowe szaty cesarza | 3.914 | 7,5 | FOG-PL 6 | Al emperador, «Najjaśniejszy Panie» + 3.ª persona; tejedores al ministro y a Hugo: *pan*; Max/Felix → Maks/Feliks; Sir Hugo → hrabia Hugo; «Stuk, stuk, stuk»; Knights of the Loom → Rycerze Krosna |
| 9 | ✅ | A2 | `en/A2/beauty-and-the-beast.txt` | `pl/A2/piekna-i-bestia.txt` | Piękna i Bestia | 3.752 | 8,5 | FOG-PL 5 | Bestia con concordancia masculina (*Bestia powiedział*), voc. «Bestio»; mercader a Bestia: *pan* + «łaskawy panie»; Bestia y Piękna: *ty*; Owooo → «Auuu!» |
| 10 | ✅ | A2 | `en/A2/the-little-mermaid.txt` | `pl/A2/mala-syrenka.txt` | Mała syrenka | 3.908 | 8,1 | FOG-PL 6 | Sea King → Król Mórz; sea witch → morska wiedźma; foundling → «znajda»; final original: piana y córki powietrza |
| 11 | ✅ | A2 | `en/A2/the-adventures-of-pinocchio.txt` | `pl/A2/przygody-pinokia.txt` | Przygody Pinokia | 4.128 | 7,9 | FOG-PL 5 | Geppetto → Dżeppetto; Master Cherry → majster Wisienka; Polendina → «Mamałyga»; Fire-Eater → Ogniojad; Catch-Fools → Łapigłupy; Candlewick → Knot; Dogfish → Rekin; «Apsik!», «I-o!» |
| 12 | ✅ | B1 | `en/B1/treasure-island.txt` | `pl/B1/wyspa-skarbow.txt` | Wyspa skarbów | 8.993 | 11,7 | FOG-PL 7 | Narración en czas przeszły, sin imiesłowy przysłówkowe; Jim a adultos: *pan*; capitán a piratas: *ty*; squire → dziedzic; Barbecue → Rożen; Black Dog → Czarny Pies; Spyglass → Luneta; Walrus → «Mors»; black spot → czarny znak; pieces of eight → «Piastry!»; canción: «Piętnastu chłopa na trupa skrzyni, jo-ho-ho, i butelka rumu!» |
| 13 | ✅ | B1 | `en/B1/a-christmas-carol.txt` | `pl/B1/opowiesc-wigilijna.txt` | Opowieść wigilijna | 9.739 | 11,5 | FOG-PL 7 | Narración en czas przeszły, sin imiesłowy przysłówkowe; Bah! Humbug! → «Phi! Bzdura!»; Tiny Tim → Mały Tim; «Niech Bóg błogosławi każdemu z nas!»; Duch Minionych/Obecnych/Przyszłych Świąt; Ignorance/Want → Ciemnota/Nędza; Scrooge a Bob: *ty*, Bob a Scrooge: «proszę pana»; Yes and No → «Tak czy nie» |
| 14 | ✅ | B1 | `en/B1/around-the-world-in-eighty-days.txt` | `pl/B1/w-osiemdziesiat-dni-dookola-swiata.txt` | W osiemdziesiąt dni dookoła świata | 7.917 | 9,2 | FOG-PL 7 | Narración en czas przeszły, sin imiesłowy przysłówkowe; Passepartout (indeclinable), Aouda → Auda; Long Noses → Długie Nosy; *pan / pani* en todas las relaciones (también Fogg a Passepartout); Fogg a Auda: «Pani» |
| 15 | ✅ | B2 | `en/B2/frankenstein.txt` | `pl/B2/frankenstein.txt` | Frankenstein | 16.133 | 11,5 | FOG-PL 8 | Narración en czas przeszły; Wiktor, Elżbieta, Henryk, Justyna, Karolina, Feliks, Agata (William, Ernest, Safie sin cambio); Victor–Walton: *pan* (Walton → «Waltonie»); criatura–Victor, familia y Clerval: *ty*; criatura–De Lacey: *pan*; Justyna a Elżbieta: *pani*; estribillo: «będę z tobą w twoją noc poślubną»; libros: „Raj utracony”, „Żywoty” Plutarcha, „Cierpienia młodego Wertera”, „Ruiny imperiów”; relato de la criatura (caps. 9–12) sin comillas externas |
| 16 | ✅ | B2 | `en/B2/pride-and-prejudice.txt` | `pl/B2/duma-i-uprzedzenie.txt` | Duma i uprzedzenie | 16.765 | 11,7 | FOG-PL 8 | Narración en czas przeszły; Elżbieta (Lizzy, Eliza), Lidia, lady Katarzyna; Jane, Mary, Kitty, Charlotte, Georgiana sin cambio; esposos Bennet e hijas–padres: *ty* («mój drogi», «ojcze», «mamo»); Darcy–Elżbieta, Collins, Lady Catherine y Wickham: *pan / pani*; a Lady Catherine, «jaśnie pani»; entail → ordynacja; living → probostwo; «tolerable» → «Ujdzie, ale nie jest dość ładna, żeby mnie skusić»; «Na próżno walczyłem»; «Aż do tej chwili nie znałam samej siebie»; cartas con „…” |

## Vocabulario fuera de nivel (para glosario)

Palabras propias de la historia que pasan del nivel; el contexto las explica, pero conviene
incluirlas en el glosario del libro. Una entrada por libro, con el título traducido.

- **Dzielny krawczyk**: krawczyk/krawiec, igła, nitka/nić, szyć, wyszywać, dżem, słoik, mucha,
  zamach, pas, olbrzym, ściskać, dąb, czereśnia, czubek, jaskinia, żelazny drąg, jednorożec, róg,
  dzik, kły, siekiera, lina, kościółek, linijka, guzik, burmistrz.
- **Jaś i Małgosia**: drwal, macocha, siekiera, kamyk, fartuszek, komin, piernik, czarownica,
  klatka, kłódka, kość, piec, drzwiczki, perły, skrzynia, sowa, jagody, bochenek, kaczka.
- **Czerwony Kapturek**: kapturek, peleryna, aksamit, koszyk, wilk, drwal, siekiera, ścieżka,
  strumień, żywopłot, leszczyna, dąb, klamka, czepek, kołdra, myśliwy, strzelba, nożyce, igła,
  nitka, koryto, wiadro, kiełbaski.
- **Królewna Śnieżka**: heban, igła, płótno, szyć, kropla, krew, usta, zamek, lustro, myśliwy, łuk,
  dzik, ciernie, krasnoludek, broda, kopać, lampka, prześcieradło, modlitwa, tasiemka, jedwab,
  grzebień, perły, trucizna, wieko, kruk, gołąbek, sowa, sługa, wesele, żelazne buty.
- **Kopciuszek**: kopeć, popiół, macocha, słoma, pająk, studnia, miotła, pałac, bal, aksamit,
  jedwab, gorset, sznurówka, matka chrzestna, wróżka, różdżka, dynia, wydrążać, pułapka, stangret,
  powozić, wąsy, konewka, jaszczurka, lokaj, liberia, klejnoty, perły, pantofelek, północ, czary,
  łachmany, kwadrans, wieża, strażnik, trąbka, rynek, przymierzać, księżna, dwór, pięta, klękać,
  pasować jak ulał, wesele.
- **Trzy małe świnki**: słoma, patyki, cegły, wóz, moneta, skrzypce, szarlotka, ryjek, galareta,
  paszcza, cement, kielnia, komin, kominek, żelazny zamek, rzepa, jabłoń, sad, jarmark, maselnica,
  beczka, pokrywka, bulgotać.
- **Kot w butach**: młynarz, młyn, mąka, pszenica, osioł, szewc, worek, sznurek, nora, markiz,
  kareta, tron, ukłonić się, włócznia, tonąć, aksamit, jedwab, kosić, łąka, sad, winnica,
  posiekać, pasztet, ludożerca, grzywa, ryczeć, stajnia, wypastowany.
- **Nowe szaty cesarza**: cesarz, szaty, garderoba, tkacz, krosno, tkanina, jedwab, aksamit, nić,
  szpula, peleryna, krój, gospoda, karczmarka, skarbnik, mistrz ceremonii, hrabia, szambelan,
  baldachim, drążek, przekupka, kuchcik, pajęczyna, nożyce, igła, niewidzialny, nagi, zaszczyt.
- **Piękna i Bestia**: kupiec, kantor, statek, burza, zatonąć, wóz, pszenica, klawesyn, jedwabny,
  aksamitny, pióra, naszyjnik, owies, siano, dziedziniec, wróżka, pazury, futro, niewdzięcznik,
  skrzynia, utuczyć, komnata, nuty, westchnienie, zarumienić się, pierścień, zemdleć, kanał,
  chlusnąć, fajerwerki, czar, posąg, królewicz.
- **Mała syrenka**: kotwica, chaber, koral, bursztyn, muszla, perła, ostryga, ogon, płatek, posąg, marmur,
  katastrofa statku, mielizna, łabędź, góra lodowa, fikołek, wianek, lilia, maszt, kajuta, pokład,
  latarnia, fajerwerki, żagiel, zatoka, piana, pochodnia, rybak, dusza, wodorosty, ksiądz, trytoni,
  róg, wir, wiedźma, ropucha, rozbitek, napój, aksamit, znajda, burta, port, namiot, purpura,
  panna młoda, wschód, promień, córki powietrza, uczynek.
- **Przygody Pinokia**: kłoda/klocek, stolarz, warsztat, siekiera, strug, łaskotać, peruka, mamałyga,
  kukiełka, rzeźbić, świerszcz, młotek, piec, gruszka, kora, elementarz, teatr, scena, bicz, kichać,
  kuleć, gospoda, flaki, złodziej, łapa, dąb, wróżka, sokół, dziób, kareta, wrona, sowa, trumna,
  kłamstwo, dzięcioł, gołąb, dzban, rekin, knot, woźnica, kopyta, sierść, obręcz, medalion, bęben,
  węgorz, paszcza, tuńczyk, żebrak, koło wodne, wiadro, ślimak.
- **Wyspa skarbów**: dziedzic, gospoda, izba gościnna, skrzynia marynarska, luneta, szrama, sędzia
  pokoju, łajdak, udar, czarny znak, wezwanie, gwinea, cerata, smoła, piastry, kula (do chodzenia),
  kambuz, kwatermistrz, sternik, pierwszy oficer, chłopiec okrętowy, spisek, bunt, kotwicowisko,
  porzucić (na wyspie), palisada, dom z bali, biała flaga, szalupa, kordelas, muszkiet, proch,
  suchary, przypływ, odpływ, korak, lina kotwiczna, splot, saling, grzechotnik, lwy morskie,
  kilof, sztabki złota, odźwierny, stryczek, kat.
- **Opowieść wigilijna**: bzdura, kantor, urzędnik, stołek, skrzynia na węgiel, szalik, siostrzeniec,
  przytułek, kołatka, szlafmyca, szlafrok, łańcuch, ogniwo, kłódka, kasetka, sakiewka, szczęka, widmo,
  ostrokrzew, jemioła, bluszcz, gasidło, terminator, skład, okiennice, skrzypek, poncz, pudding,
  kula (do chodzenia), szyna, kaleka, chromy, bożek, zysk, wrzosowisko, latarnia morska, ciuciubabka,
  szpon, Ciemnota, Nędza, przedsiębiorca pogrzebowy, tobołek, praczka, wierzyciel, dług, słupek łóżka,
  dorożka, pół korony, szyling, kaftan bezpieczeństwa.
- **W osiemdziesiąt dni dookoła świata**: zakład, wist, służący, woda do golenia, stopnie, banknoty,
  kasjer, nagroda, parowiec, nabrzeże, konsul, paszport, stempel, nakaz aresztowania, detektyw,
  rysopis, rozkład, zyski, świątynia, kapłani, konduktor, kolejarz, tory/szyny, słoń, trąba, Pars,
  przewodnik, procesja, radża, turban, wdowa, sati, łajdak, opium, pochodnia, stos, płaszcz, duch,
  nieboszczyk, sąd, sędzia, grzywna, kaucja, zbieg okoliczności, kajuta, kotły, palarnia opium, porto,
  łódź pilotowa, tajfun, sygnał, armatka, trupa akrobatów, plakat, piramida, Długie Nosy, wybory,
  pułkownik, obraza, bizon, stado, mormon, misjonarz, dróżnik, maszynista, most, pojedynek, rewolwer,
  wojownicy, Siuksowie, odczepić, fort, jeńcy, sanie z żaglem, płozy, wilki, molo, załoga, ster,
  węgiel, kadłub, maszt, pokład, urząd celny, specjalny pociąg, dorożka, pastor, południk, stopień.
- **Frankenstein**: sanie, kra, pokład, szyper, porucznik, wielorybnik, kajuta, luneta, szkarlatyna,
  alchemia, eliksir życia, filozofia przyrody, prosektorium, kostnica, krypta, rozkład, całun, oczodół,
  gorączka nerwowa, miniatura, syndyk, szafot, spowiednik, ekskomunika, lodowiec, szczelina, szopa,
  chlew, klepisko, wygnanie, nędznik, stwórca, strzecha, wrzosowisko, ława przysięgłych, sztylet,
  mary, stos pogrzebowy, wachta, kości zostały rzucone.
- **Duma i uprzedzenie**: wynająć, majątek, funtów rocznie, bal, ordynacja, dziedzic, posiadłość,
  duchowny, probostwo/beneficjum, parafia, plebania, protektorka, rządca, gospodyni, dzierżawca,
  milicja, pułk, stopień oficerski, kadryl, wykształcona/talenty, przełaz, halka, kazania,
  guwernantka, bywać w towarzystwie, dama do towarzystwa, opiekun, oświadczyny, oświadczyć się,
  zaręczyny, uciec (z kochankiem), hańba, kokietka, posag, pieniądze na drobne wydatki, upór,
  uprzedzenie, wymówka, zagajnik.
