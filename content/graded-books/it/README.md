# Traduzioni in italiano (it)

Fichero de control de las traducciones a este idioma (lo leen `tools/translate_runner.py`,
`tools/library_meta.py` y la skill `/graded-translate`). Una fila por libro: ⬜ pendiente, ✅
traducido y verificado con `tools/translation_check.py`. Mismo nivel MCER, mismos capítulos y
mismo número de párrafos que el original inglés (1:1, para la vista bilingüe). Licencia de todas
las filas: traducción propia de la adaptación propia (la del libro inglés).

- Variedad: italiano estándar.
- Diálogos: caporali « » (o raya al inicio de línea); nunca comillas rectas ".
- Cabecera de capítulo: `Capitolo N: Titolo`; última línea: `Fine`.
- Legibilidad: Gulpease (> 80 facile para lector elemental, 60-80 media).

Orden de trabajo: `/graded-translate` sin libro (o `tools/translate_runner.py --lang it`)
toma la primera fila ⬜. Al terminar, la skill pone ✅ y rellena fichero, título y métricas; el
glosario de abajo recibe una entrada con el título traducido.

## Libros

| # | Estado | Nivel | Original (en) | Fichero | Título | Palabras | Palabras/frase | Legibilidad | Notas |
|---|---|---|---|---|---|---|---|---|---|
| 1 | ✅ | A1 | `en/A1/the-brave-little-tailor.txt` | `it/A1/il-piccolo-sarto-coraggioso.txt` | Il piccolo sarto coraggioso | 2.994 | 5,8 | Gulpease 98 | Lemma «SETTE IN UN COLPO»; re → sarto: tu; sudditi/sarto → re: Lei + «Maestà» |
| 2 | ✅ | A1 | `en/A1/hansel-and-gretel.txt` | `it/A1/hansel-e-gretel.txt` | Hansel e Gretel | 2.748 | 6,0 | Gulpease 95 | Filastrocche: «Rosicchia, rosicchia, topolino…», «Paperella, paperella, aiutaci tu!…»; tutti si danno del tu |
| 3 | ✅ | A1 | `en/A1/little-red-riding-hood.txt` | `it/A1/cappuccetto-rosso.txt` | Cappuccetto Rosso | 2.966 | 6,4 | Gulpease 93 | Formula «Nonna, che orecchie grandi hai!» / «Per sentirti meglio, bambina mia»; bambina → lupo: Lei + «signor Lupo»; lupo → bambina: tu |
| 4 | ✅ | A1 | `en/A1/snow-white.txt` | `it/A1/biancaneve.txt` | Biancaneve | 3.028 | 5,9 | Gulpease 96 | Formula «Specchio, specchio delle mie brame, chi è la più bella del reame?»; cacciatore → regina: «mia regina»; Biancaneve → vecchia: Lei; principe → nani: voi |
| 5 | ✅ | A1 | `en/A1/cinderella.txt` | `it/A1/cenerentola.txt` | Cenerentola | 2.955 | 5,7 | Gulpease 95 | Soprannome «Culincenere»; madrina ↔ Cenerentola e principe ↔ Cenerentola: tu; sorelle e gentiluomo → «principessa»: Lei + «Altezza» |
| 6 | ✅ | A1 | `en/A1/the-three-little-pigs.txt` | `it/A1/i-tre-porcellini.txt` | I tre porcellini | 3.179 | 7,0 | Gulpease 91 | Formula «Porcellino, porcellino, fammi entrare!» / «Per il pelo del mio mento, non ti apro!» / «Allora soffio e risoffio, e la tua casa butto giù!»; lupo → porcellini: tu; Polly → lupo: Lei + «signor Lupo»; porcellini ↔ uomini dei carri: Lei/tu |
| 7 | ✅ | A2 | `en/A2/puss-in-boots.txt` | `it/A2/il-gatto-con-gli-stivali.txt` | Il gatto con gli stivali | 4.148 | 8,3 | Gulpease 81 | Narrato al passato prossimo; Gatto ↔ Pierre: tu («padrone», «Micio»); Gatto/Pierre → re: Lei + «Maestà», → principessa: «Altezza»; re e principessa → marchese: Lei; Gatto → orco: Lei + «signore»; cuoca → Gatto: «signor Gatto» |
| 8 | ✅ | A2 | `en/A2/the-emperors-new-clothes.txt` | `it/A2/i-vestiti-nuovi-dell-imperatore.txt` | I vestiti nuovi dell'imperatore | 4.736 | 7,9 | Gulpease 81 | Narrato al passato prossimo; corte e tessitori → Imperatore: Lei + «Maestà»; Imperatore → sudditi: tu/voi; tessitori → ministri: Lei + «signore»; Sir Hugo → «messer Ugo»; «Cavalieri del Telaio», «Tessitori Imperiali»; telai «Tac, tac, tac» |
| 9 | ✅ | A2 | `en/A2/beauty-and-the-beast.txt` | `it/A2/la-bella-e-la-bestia.txt` | La Bella e la Bestia | 4.620 | 8,8 | Gulpease 79 | Narrato al passato prossimo; Bella ↔ Bestia: tu; mercante → Bestia: Lei + «mio signore»; Bestia → mercante: tu; messaggero → mercante: «signore»; serva → Bella: Lei; lupi «Auuu! Auuu!» |
| 10 | ✅ | A2 | `en/A2/the-little-mermaid.txt` | `it/A2/la-sirenetta.txt` | La sirenetta | 4.703 | 9,0 | Gulpease 77 | Narrato al passato prossimo; tutti si danno del tu (famiglia, principe ↔ sirenetta, strega → «mia bella principessa», figlie dell'aria); proverbio «Chi bella vuole apparire, un poco deve soffrire»; finale di Andersen (schiuma e figlie dell'aria) |
| 11 | ✅ | A2 | `en/A2/the-adventures-of-pinocchio.txt` | `it/A2/le-avventure-di-pinocchio.txt` | Le avventure di Pinocchio | 5.253 | 9,2 | Gulpease 77 | Narrato al passato prossimo; nomi di Collodi (Mastro Ciliegia, Polendina, Grillo Parlante, Mangiafuoco, Acchiappacitrulli, Campo dei miracoli, Gambero Rosso, Lucignolo, Paese dei Balocchi, Pescecane); Pinocchio → Geppetto: «babbo»; tutti si danno del tu; formule «le bugie che hanno le gambe corte e le bugie che hanno il naso lungo», «Quando il morto piange, è segno che sta guarendo»; asino «Iò! Iò!», starnuto «Etciù!» |
| 12 | ✅ | B1 | `en/B1/treasure-island.txt` | `it/B1/l-isola-del-tesoro.txt` | L'isola del tesoro | 11.133 | 12,9 | Gulpease 67 | Narrato al passato prossimo; canzone «Quindici uomini sulla cassa del morto, yo-ho-ho, e una bottiglia di rum!» e «Di settantacinque salpati dal porto, uno solo è tornato: ogni altro era morto»; pappagallo «Pezzi da otto!» (Capitan Flint, femminile); Cane Nero, Barbecue, il Cannocchiale, isola dello Scheletro, Baia del Nord; Jim → adulti: Lei; adulti → Jim: tu; Trelawney ↔ Livesey ↔ Smollett: Lei; Smollett ↔ Silver: Lei; pirati fra loro: tu; esclamazioni «per mille tuoni!», «corpo di mille balene!» |
| 13 | ✅ | B1 | `en/B1/a-christmas-carol.txt` | `it/B1/canto-di-natale.txt` | Canto di Natale | 12.142 | 13,0 | Gulpease 67 | Narrato al passato prossimo; «Bah! Fandonie!»; Spirito del Natale Passato / Presente / Futuro, il Fantasma di Marley; «il piccolo Tim», «Dio benedica tutti noi!»; «il Fondatore del banchetto»; gioco «Sì e No»; Ignoranza e Miseria; Scrooge ↔ Fred, Scrooge ↔ spiriti e Marley: tu; Bob → Scrooge: Lei + «signore»; Scrooge → Bob: tu; signori della colletta ↔ Scrooge: Lei; gioco di parole di Marley «vieni più dalla tavola che dalla tomba» |
| 14 | ✅ | B1 | `en/B1/around-the-world-in-eighty-days.txt` | `it/B1/il-giro-del-mondo-in-ottanta-giorni.txt` | Il giro del mondo in ottanta giorni | 9.626 | 10,3 | Gulpease 71 | Narrato al passato prossimo; nomi di Verne (Phileas Fogg, Passepartout, Fix, Auda, Kiouni); tutti si danno del Lei (Fogg ↔ Passepartout, Fogg ↔ Auda, Fix ↔ Passepartout, soci del club); Passepartout → Fogg: «signore», «padrone»; «I Nasi Lunghi»; Fix → Fogg: «In nome della Regina, La dichiaro in arresto»; «Affare fatto!» (Speedy) |
| 15 | ✅ | B2 | `en/B2/frankenstein.txt` | `it/B2/frankenstein.txt` | Frankenstein | 19.161 | 13,5 | Gulpease 64 | Racconto di Victor e della creatura al passato remoto; lettere di Walton al passato prossimo per i fatti recenti; Walton ↔ Victor: Lei; Victor ↔ creatura, famiglia, Clerval ed Elizabeth: tu; Krempe, Kirwin, magistrato → Victor: Lei; creatura ↔ De Lacey: Lei; Justine → Elizabeth: Lei + «cara signorina»; Victor → marinai: voi; Walton ↔ creatura: tu; «È una misera robaccia»; «una tetra notte di novembre»; «Sarò con te la notte delle tue nozze»; Cornelio Agrippa, Paracelso, Alberto Magno; Le rovine degli imperi, Il paradiso perduto, Vite di Plutarco, I dolori del giovane Werther |
| 16 | ✅ | B2 | `en/B2/pride-and-prejudice.txt` | `it/B2/orgoglio-e-pregiudizio.txt` | Orgoglio e pregiudizio | 19.353 | 12,8 | Gulpease 64 | Narrato al passato remoto; coniugi Bennet, sorelle, Charlotte, zii e padre ↔ figlie: tu («papà», «mamma»); Elizabeth ↔ Darcy (anche da fidanzati), Wickham, Collins («cugina»), Fitzwilliam, Lady Catherine e sorelle Bingley: Lei; → Lady Catherine: «Vostra Signoria»; Bingley ↔ Darcy: tu; «signor/signora/signorina» + cognome, Sir William e Lady Lucas/Catherine invariati; «È una verità universalmente riconosciuta…», «È passabile, ma non abbastanza bella da tentarmi», «Ho lottato invano», «Fino a questo momento non conoscevo me stessa», «Si comporti in modo più degno di un gentiluomo» |

## Vocabulario fuera de nivel (para glosario)

Palabras propias de la historia que pasan del nivel; el contexto las explica, pero conviene
incluirlas en el glosario del libro. Una entrada por libro, con el título traducido.

- **Il piccolo sarto coraggioso**: sarto, ago, filo, cucire, marmellata, mosca/mosche, colpo,
  cintura, gigante, quercia, ciliegio, grotta, sbarra di ferro, esercito, regno,
  unicorno, corno, ascia, cinghiale, righello, servo.
- **Hansel e Gretel**: taglialegna, matrigna, ascia, sassolini, grembiule, camino, pagnotta,
  bacca, gufo, volpe, pan di zenzero, rosicchiare, strega, gabbia, lucchetto, ossuto, forno,
  perle, anatra/paperella.
- **Cappuccetto Rosso**: cappuccio, velluto, mantello, cestino, lupo, taglialegna, ascia,
  sentiero, ruscello, siepe, noccioli, quercia, saliscendi, cuffia, camicia da notte, cacciatore,
  fucile, forbici, botte, ago, filo, pelliccia, vasca, secchio, salsicce.
- **Biancaneve**: ebano, ago, cucire, goccia, sangue, labbra, specchio, brame, reame, cacciatore,
  arco, cinghiale, spine, cespuglio, nano/omino, lanterna, scavare, lenzuolo, laccio, seta, pettine,
  perle, veleno, contadina, cesto, morso, cassa di vetro, gufo, corvo, colomba, coperchio, servo,
  matrimonio, roventi.
- **Cenerentola**: cenere, Culincenere, matrigna, paglia, tetto, ragni, pozzo, scopa, palazzo,
  ballo, velluto, seta, stracci, lacci, madrina, fata, bacchetta, zucca, trappola per topi, ratto,
  cocchiere, baffi, annaffiatoio, lucertole, valletti, scarpette di cristallo, mezzanotte,
  scalinata, tromba, gentiluomo, duchesse, tallone, inginocchiarsi.
- **I tre porcellini**: porcellino/maiala, paglia, bastoni, mattoni, carro, moneta, violino,
  cemento, cazzuola, camino, focolare, serratura, pentola, coperchio, rape, melo, fiera,
  zangola, burro, botte, artigli, fessure, soffiare/risoffiare.
- **Il gatto con gli stivali**: mugnaio, mulino, farina, grano, eredità, funerale, pelliccia,
  calzolaio, cuoio, stivali, tane, lacci, lance, trono, inchino, marchese, stufato, pagnotta,
  carrozza, annegare, finestrino, seta, velluto, prato, frutteti, vigne, orco, portone, criniera,
  ruggire, baffi, stalle, occhiolino, schienale, campane.
- **I vestiti nuovi dell'imperatore**: imperatore, Maestà, guardaroba, servitore, seta, velluto,
  piume, mantello, locanda/locandiera, mercante, tessitore, telaio, stoffa, filo d'oro, disegno,
  invisibile, inchino, trono, tesoriere, ministro, Maestro di cerimonie, baffi, sguattera,
  garzone, pescivendola, parata, cavaliere, forbici, ago, cucire, ragnatela, mutande, baldacchino,
  pali, ciambellani, manica, brivido.
- **La Bella e la Bestia**: mercante, nave, tempesta, carro, clavicembalo, grano, seta, velluto,
  piume, mungere, ululare, lupi, palazzo, cortile, stalla, fieno, avena, baule, rosa, ruggito, pelo,
  artigli, bestia, ingrato, condizione, cipolla, specchio, armadio, padrona, sospiro, anello,
  svenire, camicia da notte, gelosia, canale, fuochi d'artificio, incantesimo, fata cattiva,
  statua, regno.
- **La sirenetta**: sirena/sirenetta, tritoni, coda, corallo, ambra, conchiglie, perla, ostriche, ancora,
  fiordaliso, petalo, statua di marmo, naufragio, banco di sabbia, campanile, cigni, delfini, capriole,
  iceberg, gigli, albero maestro, razzi, fuochi d'artificio, scogli, schiuma, anima, sacerdote, alga,
  goffe, corno, strega, vortici, viscide, vermi, timoni, naufraghi, rospo, bevanda, lingua, trovatella,
  velluto, sanguinare, tempio, nozze, tenda, raggio, figlie dell'aria, buone azioni.
- **Le avventure di Pinocchio**: burattino, ciocco, bottega, falegname, ascia, pialla, solletico,
  parrucca, polenta, granturco, tirare di scherma, salti mortali, graffiare, attrezzi, carabiniere,
  grillo, somaro/ciuco/ciuchino, pulcino, braciere, corteccia, mollica, abbecedario, tendone,
  palco, frusta, montone, starnutire, zoppa, cieco, osteria, oste, trippa, lepre, assassini, zampa,
  quercia, falco, becco, carrozza, civetta, lenzuolo, bara, inghiottire, picchi, fasciata,
  dissotterrare, scimmione, giace, colombo, brocca, Pescecane, lucignolo, balocchi, groppa,
  scoiattolina, zoccoli, cerchi, medaglia, tamburo, anguilla, scoglio, caprettina, grotta, buio
  pesto, pozzanghere, stropicciarsi, fiammiferi, asma, mendicanti, capanna, paglia, secchi,
  lumaca, intagliare, penzoloni.
- **L'isola del tesoro**: locanda, lupo di mare, cassa da marinaio, cannocchiale, scogliera,
  magistrato, furfante, colpo apoplettico, macchia nera, convocazione, ghinea, tela cerata,
  pezzi da otto, gruccia, cambusa, quartiermastro, nostromo, primo ufficiale, mozzo,
  ammutinamento, ancoraggio, abbandonare (su un'isola), fortino, casa di tronchi, feritoie,
  bandiera bianca, moschetto, sciabola, polvere da sparo, lancia, coracle, cima dell'ancora,
  albero, crocette, marea, palude, serpente a sonagli, leoni marini, deriva, lingotti, forca.
- **Canto di Natale**: fandonie, avaro, impiegato, cassetta del carbone, sciarpa, nipote, ospizio
  dei poveri, prigioni, battente, serratura, cantina, catena, lucchetto, cassetta per il denaro,
  trasparente, mascella, sugo, umanità, spirito, fantasma, spettro, berretta da notte, vestaglia,
  colonna del letto, tende del letto, agrifoglio, edera, vischio, cappuccio spegnitoio,
  apprendista, parrucca gallese, violinista, idolo, liberare (da una promessa), trono, torcia,
  incenso, scellino, sei pence, mezza corona, stampella, storpio, oca, salsa di mele, budino,
  brandy, focolare, brindisi, orco, brughiera, minatori, faro, mosca cieca, artiglio, Ignoranza,
  Miseria, becchino, lavandaia, fagotto, gesso, cimitero, debito, creditore, pollivendolo,
  tacchino premiato, carrozza, stipendio, camicia di forza.
- **Il giro del mondo in ottanta giorni**: scommessa, whist, servitore, acqua per la barba, gradi,
  banconote, cassiere, ricompensa, piroscafo, banchina, console, passaporto, timbro, mandato
  d'arresto, investigatore, arrestare, orario, guadagni, tempio, sacerdoti, controllore, binari,
  elefante, proboscide, parsi, guida, processione, ragià, turbante, vedova, sati, oppio, catasta,
  rogo, torcia, mantello, fantasma, cauzione, multa, tribunale, coincidenza, cabina, fumeria
  d'oppio, tifone, pilotina, segnale, cannone, acrobati, compagnia, piramide umana, Nasi Lunghi,
  elezioni, colonnello, offesa, bisonti, branco, mormone, missionario, casellante, ponte, duello,
  rivoltella, guerrieri sioux, sganciare, forte, slitta a vela, pattini, lupi, molo, equipaggio,
  timone, carbone, scafo, alberi, ponte (di nave), scialuppe, Dogana, treno speciale, carrozza,
  meridiano, longitudine, accompagnare all'altare.
- **Frankenstein**: slitta, lastrone di ghiaccio, deriva, ponte (di nave), comandante, luogotenente,
  baleniera, cannocchiale, alchimia, elisir di lunga vita, filosofia naturale, anatomia, ossario,
  cripta, sala anatomica, mattatoio, sciagurato, demonio, orrendo, ripugnante, aborrito, sudario,
  livido, ritratto in miniatura, patibolo, scomunica, confessore, ghiacciaio, Mer de Glace,
  crepaccio, lega, baracca, porcile, abitanti della casetta, sindaco, rogo funebre, rimorso,
  vendetta, sacrilego, magistrato, gran giurì, bara, canto funebre, brughiera, Orcadi, zattera di ghiaccio.
- **Orgoglio e pregiudizio**: affittato, patrimonio, rendita (di … sterline l'anno), ballo pubblico, fedecommesso, erede maschio, tenuta, ecclesiastico, beneficio (ecclesiastico), parrocchia, protettrice, canonica, amministratore, milizia, reggimento, grado (nell'esercito), quadriglia, compita/doti, sottoveste, staccionata, governante, fare il suo ingresso in società, conte, fuggire/fuga, Gretna Green, messaggio urgente, centesimo, spillatico, tutore, decoro, cortesia, degnarsi, umiliare/umiliazione, impertinenza, passabile, rancore implacabile, preconcetto, ostinato, seduzioni, infatuazione, boschetto, stagione della caccia.
