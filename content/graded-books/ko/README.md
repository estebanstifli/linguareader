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
| 1 | ✅ | A1 | `en/A1/the-brave-little-tailor.txt` | `ko/A1/the-brave-little-tailor.txt` | 용감한 꼬마 재봉사 | 2.277 | 3,5 | Sílabas/palabra 2,6 | Narración en -ㄴ다; el sastre usa 반말 con gigantes y 합쇼체 con el rey (전하); el rey habla en 해라체; lema «한 방에 일곱» |
| 2 | ✅ | A1 | `en/A1/hansel-and-gretel.txt` | `ko/A1/hansel-and-gretel.txt` | 헨젤과 그레텔 | 2.129 | 3,6 | Sílabas/palabra 2,6 | Narración en -ㄴ다; padres y hermanos en 반말 (Gretel → 오빠); niños al padre y a la bruja en 해요체; rima «사각사각 갉작갉작…» |
| 3 | ✅ | A1 | `en/A1/little-red-riding-hood.txt` | `ko/A1/little-red-riding-hood.txt` | 빨간 모자 | 2.389 | 4,0 | Sílabas/palabra 2,6 | Narración en -ㄴ다; madre, abuela, leñador y lobo en 반말; la niña en 해요체 (늑대 아저씨); fórmula «할머니, 귀가 왜 이렇게 커요?» / «너를 더 잘 들으려고 그렇지» |
| 4 | ✅ | A1 | `en/A1/snow-white.txt` | `ko/A1/snow-white.txt` | 백설공주 | 2.452 | 3,7 | Sílabas/palabra 2,8 | Narración en -ㄴ다; la reina al espejo y al cazador en 반말/해라체; espejo y cazador a la reina en 합쇼체 (왕비님); enanos a Blancanieves en 반말, ella en 해요체; rima «거울아, 거울아, 벽에 걸린 거울아, 세상에서 누가 제일 아름답니?» |
| 5 | ✅ | A1 | `en/A1/cinderella.txt` | `ko/A1/cinderella.txt` | 신데렐라 | 2.345 | 3,6 | Sílabas/palabra 2,7 | Narración en -ㄴ다; madrastra y hermanas a Cinderella en 반말, ella en 해요체 (언니, 대모님); hada en 반말 (얘야); príncipe y Cinderella en 해요체; criados y guardias al príncipe en 합쇼체 (왕자님); Cinderbottom → «재엉덩이»; magia «휙!», campanadas «댕!» |
| 6 | ✅ | A1 | `en/A1/the-three-little-pigs.txt` | `ko/A1/the-three-little-pigs.txt` | 아기 돼지 삼 형제 | 2.504 | 4,3 | Sílabas/palabra 2,5 | Narración en -ㄴ다; madre y lobo en 반말, los cerditos a ella, al lobo y a los vendedores en 해요체 (늑대 아저씨, 아저씨); Polly → 오빠; estribillo «아기 돼지야, 아기 돼지야. 나 좀 들어가게 해 줘» / «안 돼요, 안 돼요! 내 턱수염을 걸고 절대 안 돼요!» / «그럼 후우 불고 푸우 불어서 네 집을 날려 버릴 거야!» |
| 7 | ✅ | A2 | `en/A2/puss-in-boots.txt` | `ko/A2/puss-in-boots.txt` | 장화 신은 고양이 | 2.983 | 4,9 | Sílabas/palabra 2,8 | Narración en pasado -었다; hermanos, padre y Pierre al gato en 반말 (고양이야); el gato a Pierre en 해요체 (주인님), al rey (전하) y al ogro (나리) en 합쇼체; el rey en 해라체 (후작); princesa en 해요체; Marquis of Carabas → «카라바 후작»; sin estilo indirecto (citas directas) |
| 8 | ✅ | A2 | `en/A2/the-emperors-new-clothes.txt` | `ko/A2/the-emperors-new-clothes.txt` | 벌거벗은 임금님 | 3.646 | 4,5 | Sílabas/palabra 2,8 | Narración en pasado -었다; Emperor → «임금님», al dirigirse a él «폐하» + 합쇼체; el emperador en 해라체/반말; Otto–Bruno y Max–Felix en 반말; gente del pueblo en 해요체; la niña al padre en 해요체 («아빠»); telar «철커덕», tijeras «싹둑»; Knights of the Loom → «베틀 기사», Imperial Weavers → «궁정 직조공»; sin estilo indirecto (citas directas) |
| 9 | ✅ | A2 | `en/A2/beauty-and-the-beast.txt` | `ko/A2/beauty-and-the-beast.txt` | 미녀와 야수 | 3.341 | 4,4 | Sílabas/palabra 2,7 | Narración en pasado -었다; Beauty → «미녀» (apodo «꼬마 미녀»), Beast → «야수»; padre a hijos y hermanas entre sí en 반말; hijas al padre en 해요체; mercader al Beast en 합쇼체 («나리»); Beast al mercader en 해라체 («노인»), a Beauty en 해요체; Beauty al Beast en 해요체 («야수님»); hada a Beauty y a las hermanas en 반말/해라체; letreros con 「…」; lobos «아우우», rugido «어흥»; sin estilo indirecto (citas directas) |
| 10 | ✅ | A2 | `en/A2/the-little-mermaid.txt` | `ko/A2/the-little-mermaid.txt` | 인어 공주 | 3.644 | 5,9 | Sílabas/palabra 2,7 | Narración en pasado -었다 (presente -ㄴ다 en la descripción inicial del mar); abuela, hermanas, bruja, príncipe, pescadores e hijas del aire en 반말; la sirenita a la abuela y a la bruja en 해요체; pensamientos con ‘…’; foundling → «내가 바닷가에서 찾은 귀여운 아이»; sin -시- ni estilo indirecto (citas directas) |
| 11 | ✅ | A2 | `en/A2/the-adventures-of-pinocchio.txt` | `ko/A2/the-adventures-of-pinocchio.txt` | 피노키오의 모험 | 3.676 | 4,9 | Sílabas/palabra 2,8 | Narración en pasado -었다; Geppetto, Hada, Grillo, Candlewick y animales a Pinocchio en 반말; Pinocchio a Geppetto («아버지») y al Hada («요정님») en 해요체; Cherry y Geppetto entre sí en 해요체; Zorro en 해요체 cortés; Master Cherry → «체리 아저씨», Fire-Eater → «불먹보», Candlewick → «램프심지», Catch-Fools → «바보잡이 마을», Dogfish → «거대한 상어»; rebuzno «히힝», estornudo «에취»; sin -시- ni estilo indirecto (citas directas) |
| 12 | ✅ | B1 | `en/B1/treasure-island.txt` | `ko/B1/treasure-island.txt` | 보물섬 | 8.026 | 6,5 | Sílabas/palabra 2,7 | Narración en primera persona en pasado -었다; Jim a los adultos en 해요체 (선생님, 지주님, 아저씨); capitán Bill, Silver, Hands, Ben Gunn y el doctor a Jim en 반말; Livesey y Trelawney entre sí en 반말 (자네); squire → Smollett en 하오체, Smollett → squire en 합쇼체 (나리); Smollett → Silver en 해라체, Silver → Smollett en 합쇼체; canción «죽은 자의 궤짝 위에 열다섯 사내, 요호호, 럼주 한 병!»; loro «스페인 은화!»; Spyglass → «망원경 산», Walrus → «해마호», gentlemen of fortune → «행운의 신사» |
| 13 | ✅ | B1 | `en/B1/a-christmas-carol.txt` | `ko/B1/a-christmas-carol.txt` | 크리스마스 캐럴 | 8.863 | 6,8 | Sílabas/palabra 2,8 | Narración en pasado -었다; Scrooge a Fred, Bob y al niño en 반말 (a Bob «자네»); Fred a Scrooge en 해요체 (외삼촌); Bob a Scrooge en 합쇼체 (사장님); Marley a Scrooge en 하게체 (자네); los espíritus en 해라체, Scrooge a ellos en 해요체 (유령님); Scrooge y los caballeros en 합쇼체; Belle y el joven Scrooge en 해요체/하오체; «흥! 허튼소리!»; «Yes and No» → «예, 아니요 놀이»; Ignorance/Want → «무지»/«궁핍»; Tiny Tim → «꼬마 팀», «하느님, 우리 모두에게 축복을 내려 주세요!» |
| 14 | ✅ | B1 | `en/B1/around-the-world-in-eighty-days.txt` | `ko/B1/around-the-world-in-eighty-days.txt` | 80일간의 세계 일주 | 6.707 | 6,2 | Sílabas/palabra 2,9 | Narración en pasado -었다; Fogg a Passepartout y al guía en 하게체 (자네, -네, -게); Passepartout a Fogg en 합쇼체 (나리 → 주인님); Fogg con los socios del club, Sir Francis, Fix y Speedy en 합쇼체; Fogg ↔ Aouda: él en 합쇼체 (부인), ella en 해요체 (포그 씨); Fix ↔ Passepartout en 해요체; Proctor y Speedy bruscos (반말/하오체); Long Noses → «긴코 곡예단», Gains → «이익»; pensamientos con ‘…’ |
| 15 | ✅ | B2 | `en/B2/frankenstein.txt` | `ko/B2/frankenstein.txt` | 프랑켄슈타인 | 14.602 | 8,8 | Sílabas/palabra 2,8 | Narración en pasado -었다 (cartas de Walton a Margaret en 해라체, «사랑하는 마거릿에게»); master → «항해장», Walton → «선장»; Victor ↔ Walton en 합쇼체 («친구여»); Victor con Elizabeth, Clerval y Ernest en 반말 (Ernest: «형», «누나»); padre a Victor en 해라체, Victor al padre en 해요체; Krempe en 하게체 (자네); la criatura a Victor en 합쇼체 hasta la compañera destruida, luego 반말/해라체; Victor a la criatura en 해라체 («악마 놈»); criatura ↔ De Lacey en 합쇼체/하오체; estribillo «네 결혼식 날 밤에 내가 너와 함께 있을 것이다»; el relato de la criatura (caps. 9–12) sin comillas externas, como el original |
| 16 | ✅ | B2 | `en/B2/pride-and-prejudice.txt` | `ko/B2/pride-and-prejudice.txt` | 오만과 편견 | 14.535 | 8,2 | Sílabas/palabra 2,8 | Narración en pasado -었다. Sesión cortada por el límite de contexto (243k) tras unir las partes; fila rellenada a mano tras pasar translation_check |

## Vocabulario fuera de nivel (para glosario)

Palabras propias de la historia que pasan del nivel; el contexto las explica, pero conviene
incluirlas en el glosario del libro. Una entrada por libro, con el título traducido.

- **용감한 꼬마 재봉사**: 재봉사, 바늘, 실, 바느질, 수놓다, 잼, 단지, 파리, 한 방에, 허리띠, 금실, 거인,
  덤불, 짜다, 참나무, 헐떡이다, 쇠막대, 잠꼬대, 기지개, 신하, 전하, 유니콘, 뿔, 멧돼지, 사냥꾼, 하인
- **헨젤과 그레텔**: 나무꾼, 새엄마, 도끼, 조약돌, 은화, 앞치마, 굴뚝, 부스러기, 부엉이, 덤불, 생강 과자,
  갉아 먹다, 지팡이, 마녀, 앙상하다, 철창, 자물쇠, 계집애, 솥, 쇠문, 진주, 보석, 넉넉하다
- **빨간 모자**: 망토, 벨벳, 참나무, 난롯가, 나무꾼, 찬장, 바구니, 도끼, 시냇물, 개암나무, 울타리, 꽃다발,
  다람쥐, 통나무, 걸쇠, 잠자리 모자, 사냥꾼, 털가죽, 꿰매다, 양동이, 구유, 삶다
- **백설공주**: 흑단, 창틀, 바느질, 바늘, 방울, 거만하다, 마법 거울, 진실, 정원사, 사냥꾼, 활, 멧돼지,
  심장, 가시, 할퀴다, 산짐승, 해치다, 난쟁이, 수염, 등불, 식탁보, 포도주, 기도, 옷끈, 비단, 조이다, 빗,
  진주, 독, 농부, 반쪽, 깨물다, 유리관, 부엉이, 까마귀, 비둘기, 뚜껑, 하인, 결혼식, 쇠 신발, 켤레
- **신데렐라**: 난롯가, 재, 재투성이, 거만하다, 우물, 짚, 거미, 누더기, 비단, 벨벳, 궁전, 무도회, 빗자루,
  금단추, 목걸이, 다리다, 끈, 대모, 요정, 요술 지팡이, 호박, 파내다, 생쥐 덫, 쥐덫, 마부, 콧수염, 물뿌리개,
  도마뱀, 하인, 진주, 유리 구두, 자정, 깃털, 경비병, 성문, 나팔, 광장, 백성, 공작 부인, 귀부인, 뒤꿈치,
  무릎을 꿇다, 용서하다, 귀족, 결혼식
- **아기 돼지 삼 형제**: 짚, 수레, 동전, 바이올린, 자물쇠, 잠그다, 시멘트, 흙손, 굴뚝, 벽난로, 솥, 순무, 밭, 바구니,
  장터, 버터 통, 술통, 발톱, 뚜껑, 입맛을 다시다, 턱수염, 속임수, 괴물
- **장화 신은 고양이**: 방앗간, 방앗간 주인, 밀가루, 밀, 유산, 장례식, 자루, 난롯가, 구둣방, 장화, 깃털, 굴,
  궁전, 경비병, 창, 왕좌, 전하, 후작, 너그럽다, 낟알, 땔감, 왕실 마차, 비단, 벨벳, 다지다, 과수원, 포도밭,
  오거, 갈기, 으르렁거리다, 하인, 마구간, 찡긋하다, 소박하다, 결혼식, 귀족, 백성, 공정하다
- **벌거벗은 임금님**: 폐하, 외투, 망토, 비단, 벨벳, 깃털, 향신료, 양탄자, 잠꼬대, 나그네, 여관, 앞치마,
  직조공, 옷감, 투명하다, 대신, 재상, 재무관, 금실, 베틀, 의전관, 콧수염, 거만하다, 생선 장수, 귀족, 행진,
  칭호, 임명하다, 기사, 차양, 장대, 시종, 끝자락, 속옷, 순진하다, 웅성거리다, 맨살
- **미녀와 야수**: 상인, 질투하다, 거만하다, 무도회, 공작, 백작, 청혼하다, 폭풍, 마차, 수레, 하프시코드, 밀,
  비단, 벨벳, 깃털, 빚, 항구, 마구간, 건초, 귀리, 요정, 울부짖음, 발톱, 배은망덕하다, 나리, 조건, 금화,
  양파, 서재, 악보, 옷장, 한숨, 군대, 기절하다, 잠옷, 말솜씨, 형부, 운하, 오솔길, 불꽃, 마법, 조각상, 왕비
- **인어 공주**: 인어, 닻, 산호, 호박, 조개껍데기, 진주, 굴, 귀부인, 꽃잎, 대리석, 조각상, 수레국화, 모래톱,
  백조, 공중제비, 빙산, 선원, 돛대, 선실, 갑판, 폭죽, 불꽃놀이, 돛, 화관, 백합, 만, 거품, 어부, 횃불, 영혼,
  신부님, 바닷말, 무도회, 뿔피리, 소용돌이, 마녀, 두꺼비, 물뱀, 악어, 무용수, 벨벳, 방석, 뱃전, 신전, 항구,
  천막, 자정, 햇살, 공기의 딸들
- **피노키오의 모험**: 통나무, 목수, 작업실, 작업대, 도끼, 대패, 찬장, 가발, 옥수수죽, 공중제비, 연장, 벽난로,
  화로, 귀뚜라미, 나무망치, 꽃무늬, 나무껍질, 외투, 천막, 화덕, 등불, 채찍, 장작, 재채기, 금화, 여관, 소 내장,
  산토끼, 떡갈나무, 매, 부리, 마차, 부엉이, 관, 딱따구리, 붕대, 재판관, 경비병, 항아리, 마부, 열병, 발굽,
  서커스 단장, 고리, 장어, 물웅덩이, 거지, 오두막, 짚, 물레방아, 양동이, 달팽이
- **보물섬**: 지주, 여관, 응접실, 궤짝, 망원경, 절벽, 치안 판사, 악당, 뇌졸중, 검은 딱지, 소환장, 기니 금화,
  방수포, 범포, 스페인 은화, 목발, 주방, 조타장, 키잡이, 일등 항해사, 사환, 반란, 정박지, 버림받다, 방책,
  통나무집, 휴전 깃발, 단검, 머스킷 총, 화약, 졸리 보트, 코러클, 닻줄, 돛대 가로대, 해임, 해골, 곡괭이, 금괴, 은괴
- **크리스마스 캐럴**: 허튼소리, 구두쇠, 회계 사무소, 서기, 동업자, 구빈원, 견습생, 웨일스식 가발, 덧문, 무도회장,
  민스파이, 우상, 호랑가시나무, 겨우살이, 담쟁이덩굴, 플럼 푸딩, 펀치, 횃불, 목발, 보조기, 걸상, 커스터드, 황무지,
  등대, 무지, 궁핍, 멸망, 장의사, 누더기, 빚쟁이, 비석, 교회 묘지, 침대 기둥, 실링, 크라운, 닭 가게, 구속복
- **80일간의 세계 일주**: 내기, 휘스트, 하인, 출납원, 현상금, 기선, 부두, 영사, 영사관, 여권, 체포 영장, 인상착의,
  기관사, 승려, 차장, 선로, 파르시교도, 행렬, 라자, 터번, 사티, 아편, 아편굴, 횃불, 망토, 보석금, 벌금, 법정, 선고,
  선실, 태풍, 수로 안내선, 대포, 곡예단, 곡예사, 물구나무, 선거, 대령, 모욕, 들소, 선교사, 신호수, 결투, 수족,
  요새, 포로, 썰매, 상선, 증기 기관, 선체, 돛대, 우편선, 세관, 경도
- **프랑켄슈타인**: 썰매, 얼음덩어리, 갑판, 항해장, 부관, 포경선, 이등 항해사, 망원경, 선실, 원고, 시의원, 관,
  연금술, 영약, 횡사, 그루터기, 전기, 성홍열, 임종, 자연철학, 해부학, 지하 묘지, 납골당, 구더기, 섬유, 혈관,
  해부실, 도살장, 덧창, 수의, 미라, 역마차, 신경성 열병, 세밀화, 만가, 재판, 상복, 증인, 파문, 고해 신부, 교수대,
  빙하, 타락 천사, 헛간, 돼지우리, 회칠, 개똥지빠귀, 나이팅게일, 실낙원, 쓸개즙, 일지, 영양, 유배, 도토리,
  초가지붕, 경첩, 치안 판사, 대배심, 기소, 단검, 관대, 독방, 타타르, 털가죽, 얼음 뗏목, 당직, 화장 장작더미
- **오만과 편견**: 독신, 재산, 상속, 한정, 무도회, 응접실, 청혼, 편견, 오만, 장교, 연대, 사촌, 마차, 저택, 영지, 목사, 교구, 후원자, 하녀, 가정교사, 사교계, 체면, 품위, 사과, 거절, 고백, 편지, 오해, 자존심, 숙모, 외삼촌, 정원, 초상화, 결투, 빚
