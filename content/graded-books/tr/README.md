# Türkçe çeviriler (tr)

Fichero de control de las traducciones a este idioma (lo leen `tools/translate_runner.py`,
`tools/library_meta.py` y la skill `/graded-translate`). Una fila por libro: ⬜ pendiente, ✅
traducido y verificado con `tools/translation_check.py`. Mismo nivel MCER, mismos capítulos y
mismo número de párrafos que el original inglés (1:1, para la vista bilingüe). Licencia de todas
las filas: traducción propia de la adaptación propia (la del libro inglés).

- Variedad: turco estándar de Estambul (*sen* en familia y amigos, *siz* con desconocidos y mayores).
- Diálogos: raya (`—`) al inicio del diálogo: `— Yedi! diye bağırır. — Bir vuruşta yedi!`; citas y carteles con “…”.
- Cabecera de capítulo: `Bölüm N: Başlık`; última línea: `Son`.
- Legibilidad: Ateşman (90-100 çok kolay, 70-89 kolay, 50-69 orta).

Orden de trabajo: `/graded-translate` sin libro (o `tools/translate_runner.py --lang tr`)
toma la primera fila ⬜. Al terminar, la skill pone ✅ y rellena fichero, título y métricas; el
glosario de abajo recibe una entrada con el título traducido.

## Libros

| # | Estado | Nivel | Original (en) | Fichero | Título | Palabras | Palabras/frase | Legibilidad | Notas |
|---|---|---|---|---|---|---|---|---|---|
| 1 | ✅ | A1 | `en/A1/the-brave-little-tailor.txt` | `tr/A1/cesur-kucuk-terzi.txt` | Cesur Küçük Terzi | 2.373 | 4,8 | Ateşman 102 | Şimdiki zaman (-iyor); terzi–dev *sen*, al rey «Efendimiz» + *siz*; lema “BİR VURUŞTA YEDİ” |
| 2 | ✅ | A1 | `en/A1/hansel-and-gretel.txt` | `tr/A1/hansel-ve-gretel.txt` | Hansel ve Gretel | 2.117 | 4,7 | Ateşman 100 | Şimdiki zaman (-iyor); familia *sen*, niños → bruja *siz*; rimas “Kıtır kıtır, fare gibi!” y “Küçük ördek, küçük ördek…” |
| 3 | ✅ | A1 | `en/A1/little-red-riding-hood.txt` | `tr/A1/kirmizi-baslikli-kiz.txt` | Kırmızı Başlıklı Kız | 2.305 | 5,3 | Ateşman 100 | Şimdiki zaman (-iyor); niña → lobo «Kurt Bey» + *siz*, lobo → niña *sen*; “Seni daha iyi … için, yavrum” |
| 4 | ✅ | A1 | `en/A1/snow-white.txt` | `tr/A1/pamuk-prenses.txt` | Pamuk Prenses | 2.464 | 4,9 | Ateşman 102 | Şimdiki zaman (-iyor); ayna → kraliçe «Kraliçem» + *siz*, cüceler ↔ Pamuk Prenses *sen*; estribillo “Duvardaki ayna, söyle bana: kim en güzel bu dünyada?” |
| 5 | ✅ | A1 | `en/A1/cinderella.txt` | `tr/A1/kulkedisi.txt` | Külkedisi | 2.440 | 5,5 | Ateşman 99 | Şimdiki zaman (-iyor); hermanas ↔ Külkedisi *sen*, Külkedisi → peri anne y prens *siz*; “Kül Popo” (Cinderbottom); onomatopeya mágica “Puf!” |
| 6 | ✅ | A1 | `en/A1/the-three-little-pigs.txt` | `tr/A1/uc-kucuk-domuzcuk.txt` | Üç Küçük Domuzcuk | 2.492 | 5,8 | Ateşman 103 | Şimdiki zaman (-iyor); hermanos y madre *sen*, cerditos → vendedores «efendim» + *siz*, Polly → lobo «Bay Kurt» + *siz*, lobo → cerditos *sen*; estribillos “Küçük domuzcuk, küçük domuzcuk, kapını aç, içeri gireyim”, “Çenemdeki kıllar üstüne yemin ederim, açmam!” y “O zaman üflerim, püflerim, evini yerle bir ederim!” |
| 7 | ✅ | A2 | `en/A2/puss-in-boots.txt` | `tr/A2/cizmeli-kedi.txt` | Çizmeli Kedi | 2.948 | 6,3 | Ateşman 90 | Narración en *-di* (sin *-miş* de cuento); kedi → Pierre «efendim» + *siz*, Pierre → kedi *sen* («Çizmeli»); al rey «Majesteleri» + *siz*, a la princesa «Prenses Hazretleri»; «Karabas Markisi»; ogre = *dev* |
| 8 | ✅ | A2 | `en/A2/the-emperors-new-clothes.txt` | `tr/A2/imparatorun-yeni-giysileri.txt` | İmparatorun Yeni Giysileri | 3.529 | 6,9 | Ateşman 88 | Narración en *-di* (hábitos en *-ardı*); súbditos → İmparator «Majesteleri» + *siz*, İmparator → servidores *sen*; dokumacılar → vezirler «efendim» + *siz*; ministers = *vezir*, Prime Minister = *Başvezir*; «Tören Sorumlusu Sör Hugo»; «Tezgâh Şövalyesi», «Saray Dokumacısı»; onomatopeyas “Tak, tak, tak” y “Kırt, kırt, kırt” |
| 9 | ✅ | A2 | `en/A2/beauty-and-the-beast.txt` | `tr/A2/guzel-ve-cirkin.txt` | Güzel ve Çirkin | 3.392 | 7,0 | Ateşman 89 | Narración en *-di* (hábitos en *-ardı*); Beauty = «Güzel», Beast = «Canavar» (nombre propio: *Canavar'ı*); Güzel ↔ Canavar y familia *sen*; tüccar → Canavar «efendim» + *siz*; criado → tüccar «efendim»; lobos “Auuu!” |
| 10 | ✅ | A2 | `en/A2/the-little-mermaid.txt` | `tr/A2/kucuk-deniz-kizi.txt` | Küçük Deniz Kızı | 3.552 | 7,2 | Ateşman 86 | Narración en *-di* (hábitos en *-ardı*); familia, prens ↔ deniz kızı, cadı → deniz kızı *sen*; «Deniz Kralı», «deniz cadısı», «havanın kızları»; foundling = «kumsalda bulduğum küçük kızım»; canción “Korkmayın, gelin aşağı, gelin yanımıza!” |
| 11 | ✅ | A2 | `en/A2/the-adventures-of-pinocchio.txt` | `tr/A2/pinokyonun-maceralari.txt` | Pinokyo'nun Maceraları | 3.767 | 6,7 | Ateşman 90 | Narración en *-di* (hábitos en *-ardı*); Pinokyo ↔ Geppetto, Peri, Tilki y Kedi *sen*, Pinokyo → Ateşyutan «efendim»; Master Cherry = «Kiraz Usta», Polendina = «Mısır Lapası», Fire-Eater = «Ateşyutan», Catch-Fools = «Ahmaklar Kapanı», Candlewick = «Fitil», Dogfish = «Köpekbalığı»; rebuzno “Aaa-iii!”, estornudo “Hapşu!” |
| 12 | ✅ | B1 | `en/B1/treasure-island.txt` | `tr/B1/define-adasi.txt` | Define Adası | 8.051 | 9,6 | Ateşman 79 | Narración en *-di* con *-mişti*; Jim → adultos *siz* + «efendim», adultos → Jim *sen*; squire = «toprak ağası (Bay) Trelawney»; Barbecue = «Mangal»; Black Dog = «Kara Köpek»; black spot = «kara işaret»; gentlemen of fortune = «talih beyefendileri»; loro “Gümüş paralar!”; canción “Ölü adamın sandığında on beş adam, yo-ho-ho, bir şişe de rom!”; “Yetmiş beş kişi açıldı denize, bir tek adam döndü geriye.” |
| 13 | ✅ | B1 | `en/B1/a-christmas-carol.txt` | `tr/B1/bir-noel-sarkisi.txt` | Bir Noel Şarkısı | 8.754 | 9,4 | Ateşman 80 | Narración en *-di* con *-mişti*; Fred ↔ Scrooge «dayı» + *sen*, Bob → Scrooge «efendim» + *siz*, Scrooge → Ruhlar *siz*, Ruhlar → Scrooge *sen*; Bah! Humbug! = “Hıh! Saçmalık!”; Tiny Tim = «Küçük Tim»; “Tanrı hepimizi korusun, her birimizi!”; Founder of the Feast = «Ziyafetin Sahibi»; Ignorance/Want = «Cehalet»/«Yoksulluk»; juego gravy/grave → “mezardan değil, midemden”; juego «Evet mi, Hayır mı» |
| 14 | ✅ | B1 | `en/B1/around-the-world-in-eighty-days.txt` | `tr/B1/seksen-gunde-devrialem.txt` | Seksen Günde Devrialem | 7.008 | 8,0 | Ateşman 82 | Narración en *-di* con *-mişti*; todos *siz* (Fogg también a Passepartout), Passepartout → Fogg «efendim» (y una vez «Mösyö»); Aouda = «Auda» («Auda Hanım»); Kazançlar (Gains); Long Noses = «Uzun Burunlar»; Honourable = «Muhterem»; Sioux = «Siu» |
| 15 | ✅ | B2 | `en/B2/frankenstein.txt` | `tr/B2/frankenstein.txt` | Frankenstein | 13.900 | 9,9 | Ateşman 70 | Narración en *-di* con *-mişti*; Walton ↔ Victor, criatura ↔ De Lacey y Victor → padre: *siz*; Victor ↔ criatura, Elizabeth, Clerval: *sen*; demon = «iblis», fiend = «şeytan», wretch = «zavallı»; Mer de Glace = «Buz Denizi»; relato de la criatura (caps. 9–12) sin comillas externas |
| 16 | ⬜ | B2 | `en/B2/pride-and-prejudice.txt` |  |  |  |  |  |  |

## Vocabulario fuera de nivel (para glosario)

Palabras propias de la historia que pasan del nivel; el contexto las explica, pero conviene
incluirlas en el glosario del libro. Una entrada por libro, con el título traducido.

- **Cesur Küçük Terzi**: terzi, iğne, iplik, dikmek, reçel, kavanoz, sinek, vuruş, kemer, dev,
  sıkmak, meşe, kiraz, avcı, mağara, demir çubuk, ordu, krallık, horlamak, tek boynuzlu at, boynuz,
  balta, yaban domuzu, cetvel, hizmetkâr
- **Hansel ve Gretel**: oduncu, üvey anne, balta, çakıl/taş, önlük, baca, zencefilli kurabiye,
  somun, böğürtlen, baykuş, tilki, kemirmek, baston, kambur, cadı, kafes, kilit, kazan, fırın,
  inci, sandık, ördek, kayık, affetmek
- **Kırmızı Başlıklı Kız**: başlık, pelerin, kadife, sepet, kurt, oduncu, balta, dere, çit, fındık,
  meşe, mandal, gecelik, takke, avcı, tüfek, makas, günahkâr, iğne, post, sosis, yalak, kova
- **Pamuk Prenses**: abanoz, iğne, dikiş dikmek, damla, kan, dudak, şato, ayna, kibirli, avcı, yay,
  yaban domuzu, diken, cüce, sakal, kazmak, çarşaf, dua, kurdele, ipek, tarak, inci, zehir, köylü,
  ısırık, gömmek, tabut, baykuş, kuzgun, güvercin, hizmetkâr, kapak, düğün, demir
- **Külkedisi**: kül, üvey anne, saman, çatı, örümcek, kuyu, süpürge, paçavra, ipek, kadife, saray,
  balo, kolye, ütülemek, bağcık, peri anne, sihirli değnek, balkabağı, fare kapanı, sıçan, arabacı,
  bıyık, sulama kovası, kertenkele, uşak, mücevher, inci, cam ayakkabı, hizmetkâr, muhafız, borazan,
  düşes, topuk, diz çökmek, affetmek, düğün, soylu
- **Üç Küçük Domuzcuk**: saman, çubuk, tuğla, araba, bozuk para, keman, çimento, mala, baca, şömine,
  horlamak, kilit, şalgam, tarla, sepet, panayır, yayık, fıçı, tereyağı, pençe, kapak, kaynamak,
  turta, jöle, canavar
- **Çizmeli Kedi**: değirmenci, değirmen, un, buğday, miras, çuval, deri, marki, muhafız, mızrak,
  taht, yahni, hizmetkâr, kraliyet arabası, ipek, kadife, boğulmak, ot biçmek, kıyma, çayır, bağ,
  dev, şato, iltifat, yele, kükremek, ahır, gelinlik, soylu
- **İmparatorun Yeni Giysileri**: imparator, Majesteleri, hizmetkâr, giyinme odası, ipek, kadife,
  pelerin, tüccar, baharat, han, hancı, önlük, dokumacı, dokuma tezgâhı, kumaş, desen, görünmez,
  vezir, Başvezir, hazinedar, kese, altın iplik, makara, muhafız, tören sorumlusu, bıyık, geçit
  töreni, şövalye, madalya, unvan, makas, iğne, örümcek ağı, iç çamaşırı, gölgelik, direk, borazan,
  masum, fısıltı
- **Güzel ve Çirkin**: tüccar, kıskanmak, kibirli, balo, dük, kont, yazıhane, fırtına, at arabası,
  tarla, buğday, klavsen, ipek, kadife, kolye, liman, borç, ulumak, avlu, ahır, saman, yulaf,
  şömine, peri, kükreme, pençe, nankör, bağışlamak, şart, sandık, bedel, soğan, ödül, kütüphane,
  nota, ayna, iç çekmek, yüzük, gecelik, elmas, alay etmek, kanal, havai fişek, büyü, heykel,
  krallık
- **Küçük Deniz Kızı**: deniz kızı, deniz adamı, peygamber çiçeği, çapa, mercan, kehribar, deniz
  kabuğu, inci, istiridye, mermer, heykel, gemi kazası, kum adacığı, kuğu, kurdele, koy, şato, yunus,
  fıskiye, buzdağı, zambak, taç, direk, kamara, fener, güverte, havai fişek, yelken, alın, köpük,
  meşale, ruh, rahip, sadık, yosun, balo, boru, girdap, solucan, kaygan, dümen, sandık, iksir, kazan,
  buhar, timsah, bedel, zarif, kadife, minder, tapınak, gelin, damat, çadır, ışın, şeffaf, iyilik
- **Pinokyo'nun Maceraları**: kütük, marangoz, atölye, balta, rende, tezgâh, gıdıklamak, peruk,
  mısır lapası, kukla, cırcır böceği, civciv, alfabe kitabı, ağaç kabuğu, afiş, kamçı, fener,
  hapşırmak, topal, han, hancı, işkembe, pençe, meşe, peri, şahin, gaga, tabut, ağaçkakan, sargı,
  yargıç, muhafız, güvercin, kayık, testi, fitil, arabacı, takke, toynak, sirk, çember, madalyon,
  davul, yılan balığı, köpekbalığı, ton balığı, su birikintisi, kibrit, dilenci, kulübe, su çarkı,
  kova, sepet örmek, salyangoz, tahta oymak
- **Define Adası**: toprak ağası, han, salon, denizci sandığı, dürbün, kayalık, yargıç, alçak, inme,
  kara işaret, çağrı, gine, muşamba, sekizlik/gümüş para, koltuk değneği, gemi mutfağı, levazımcı,
  dümenci, ikinci kaptan, miço, isyan, demir yeri, ıssız adaya bırakmak, şarampol, kütük ev,
  ateşkes bayrağı, pala, siper, gelgit, gülle, peksimet, erzak, çıngıraklı yılan, bataklık, korakl,
  demir halatı, deniz aslanı, çapraz direk, darağacı, cellat, külçe, iskelet, kehanet
- **Bir Noel Şarkısı**: Noel arifesi, saçmalık, yazıhane, kâtip, tabure, kömür kutusu, atkı, yeğen,
  düşkünler evi, hapishane, nüfus, anahtar deliği, tokmak, mahzen, fıçı, zincir, asma kilit, para
  kasası, kese, saydam, çene, ölümlü, insanlık, ruh, hayalet, gece takkesi, sabahlık, karyola direği,
  çobanpüskülü, sarmaşık, ökseotu, söndürme külahı, çırak, Gal usulü peruk, kemancı, kepenk, put,
  azat etmek, meşale, tütsü, şilin, kurdele, koltuk değneği, puding, gülle, konyak, ziyafet, öcü,
  bozkır, madenci, deniz feneri, gamze, arp, körebe, pençe, cehalet, paçavra, pelerin, bohça, cenaze
  levazımcısı, broş, tebeşir, ceset, alacaklı, mezarlık, mezar taşı, tavukçu, yarım kron, deli gömleği
- **Seksen Günde Devrialem**: iddia, whist, hizmetkâr, tıraş suyu, derece, banknot, veznedar, ödül,
  buharlı gemi, rıhtım, konsolos, pasaport, damga, tutuklama emri, dedektif, eşkâl, tutuklamak,
  program, kazanç, tapınak, rahip, kondüktör, ray, fil, hortum, Parsi, rehber, tören alayı, raca,
  sarık, dul, sati, afyon, meşale, pelerin, hayalet, mahkeme, yargıç, kefalet, para cezası, tesadüf,
  kamara, kazan, meyhane, kılavuz teknesi, tayfun, akrobat, afiş, piramit, seçim, albay, bizon,
  misyoner, makasçı, makinist, düello, savaşçı, lokomotif, kale, esir, yelkenli kızak, iskele, tayfa,
  dümen, gövde, direk, filika, gümrük, akrep, yelkovan, boylam, mihrap
- **Frankenstein**: kızak, buz parçası, güverte, kamara, dürbün, ikinci kaptan, balina gemisi, tayfa,
  simya, ölümsüzlük iksiri, doğa felsefesi, anatomi, kemiklik, mahzen, kadavra salonu, mezbaha,
  iblis, şeytan, zavallı, sefil, iğrenç, tiksindirici, minyatür, kızıl, darağacı, aforoz, günah
  çıkarmak, buzul, Buz Denizi, fersah, baraka, domuz ahırı, kulübe, peçe, sürgün, kefen, mumya,
  menteşe, sulh yargıcı, büyük jüri, hançer, tabanca, Tataristan, dip dalgası, sal, odun yığını
