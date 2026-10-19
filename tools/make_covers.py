"""Portadas de la biblioteca: una ilustración por obra (OpenAI) y una portada compuesta por idioma (Pillow).

  python -I tools/make_covers.py --dry-run              # prompts y coste estimado, sin llamar a la API
  python -I tools/make_covers.py --only the-brave-little-tailor
  python -I tools/make_covers.py                        # todas las obras que no tienen ilustración
  python -I tools/make_covers.py --force --only rapunzel

Ilustraciones: content/graded-books/covers/<slug-inglés>.webp (1024x1536, sin texto), compartidas por
todas las traducciones; prompts usados en covers/prompts.json. Modelo gpt-image-2.5-flare (--model
para otro), calidad medium, 1024x1536, WebP. La API de imágenes no admite service_tier=flex.
La clave se lee de OPENAI_API_KEY (entorno o .env) y nunca se imprime.

compose_cover(book) -> JPEG 600x900 con el título y el nivel, que usa build_epub.py. Sin ilustración,
genera una portada provisional de color liso.
"""
import argparse
import base64
import io
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
from library_meta import BOOKS_DIR, ROOT, books  # noqa: E402

COVERS = BOOKS_DIR / "covers"
PROMPTS = COVERS / "prompts.json"
FONT = Path(__file__).resolve().parent / "fonts" / "Merriweather.ttf"
FONT_URL = "https://github.com/google/fonts/raw/main/ofl/merriweather/Merriweather%5Bopsz,wdth,wght%5D.ttf"
MODEL, SIZE, QUALITY = "gpt-image-2.5-flare", "1024x1536", "medium"
COST_ESTIMATE = (0.03, 0.06)   # $/imagen, orientativo
W, H = 600, 900
MASTER_W = 768
LEVEL_COLOURS = {"A1": (46, 160, 67), "A2": (0, 137, 123), "B1": (25, 103, 210), "B2": (123, 31, 162)}

# Una escena icónica por obra, descrita a partir del texto original (nunca de una película).
SCENES = {
    "the-brave-little-tailor": "a tiny cheerful tailor with a needle on his hat and a belt, standing proudly in front of a huge friendly-looking giant on a mountain top, a little bird flying free above them",
    "hansel-and-gretel": "a brother and sister holding hands in a dark forest, looking at a small cottage made of bread, cake and sugar windows, warm light from the windows",
    "puss-in-boots": "a clever cat wearing tall leather boots and carrying a sack, bowing in a sunny wheat field beside a country road, a royal carriage far away",
    "the-steadfast-tin-soldier": "a one-legged tin soldier standing very straight in a small paper boat on a stream, a paper ballerina in a castle window in the background",
    "little-red-riding-hood": "a little girl in a red velvet hood carrying a basket along a forest path among flowers, a grey wolf watching from behind an oak tree",
    "the-emperors-new-clothes": "a royal procession in a medieval town square, the emperor walking proudly under a canopy in only his underwear, courtiers pretending to admire, a small child pointing and laughing",
    "jack-and-the-beanstalk": "a boy climbing an enormous twisting beanstalk up through the clouds, a giant's castle on the clouds above, a small cottage far below",
    "beauty-and-the-beast": "a young woman in an eighteenth-century dress holding a single red rose in a moonlit garden of an old palace, a large gentle furry beast in a cloak standing at a distance",
    "rumpelstiltskin": "a young woman at a spinning wheel in a stone tower room full of straw, golden thread glowing on the spool, a small strange man with a long beard dancing by a fire outside the window",
    "the-little-mermaid": "a young mermaid sitting on a rock in the open sea at sunset, looking at a tall sailing ship with lanterns, coral and fish below the waves",
    "the-three-little-pigs": "three little pigs in front of three houses of straw, sticks and bricks on a green hillside, a wolf peeking from behind a hill",
    "the-jungle-book": "a young Indian boy sitting on a rock in the jungle with a black panther and a big brown bear, a wolf pack in the shadows, warm evening light",
    "snow-white": "a girl with black hair and red lips in a forest clearing in front of a tiny cottage with seven small beds visible through the window, an old pedlar woman offering a red apple",
    "the-adventures-of-pinocchio": "a poor old Italian woodcarver with a yellow wig in a tiny bare workshop, holding up a rough log of pine wood with chisel and mallet, a fireplace with a pot merely painted on the wall, a little green cricket on the floor, warm afternoon light",
    "rapunzel": "a tall stone tower without doors in a forest, a girl with very long golden braided hair letting it fall from the only window, a prince on horseback below",
    "cinderella": "a young woman in a shining ball gown running down the wide palace steps at midnight, one glass slipper left on a step, a pumpkin coach waiting below",
    "the-gingerbread-man": "a little gingerbread man with raisin eyes and cherry buttons running along a country road, an old woman, a cow and a pig chasing him, a river and a fox far ahead",
    "peter-pan": "a boy dressed in leaves and a tiny glowing fairy flying with three children over the rooftops of Edwardian London at night, a big clock tower and the moon",
    "treasure-island": "a boy and a one-legged sailor with a crutch and a parrot on his shoulder standing on the deck of an eighteenth-century schooner, a tropical island with three hills on the horizon",
    "a-christmas-carol": "an old miser in a nightgown and nightcap holding a candle in a dark Victorian bedroom, a pale chained ghost appearing at the bed curtains, snow at the window",
    "the-call-of-the-wild": "a big strong dog, half St Bernard and half shepherd, standing on a snowy ridge under the northern lights, a sled team and a log cabin far below in the Yukon",
    "around-the-world-in-eighty-days": "a calm Victorian gentleman in a top hat and his French servant riding an elephant through an Indian forest, a steamship and a train in the distance, a pocket watch motif in the sky",
    "the-canterville-ghost": "an old English manor library at night, a comic medieval ghost in rusty chains looking annoyed at a red stain on the floor, a girl with a lantern on the stairs",
    "the-adventures-of-tom-sawyer": "a barefoot boy in a straw hat painting a long white wooden fence on a sunny summer day, a Mississippi steamboat on the river behind",
    "the-secret-garden": "a girl in a Victorian coat pushing open a wooden door hidden under ivy in an old brick wall, a robin on a branch, a wild garden of roses beyond",
    "the-hound-of-the-baskervilles": "a huge dark hound with glowing eyes on a foggy moor at night, a granite tor and a lonely Victorian manor house with lit windows in the distance",
    "robinson-crusoe": "a castaway in goatskin clothes and a goatskin umbrella standing on a tropical beach, looking at a single human footprint in the sand, a wooden fence and a cave behind",
    "anne-of-green-gables": "a red-haired orphan girl with a straw hat and a carpet bag sitting on a bench at a small country station, a horse and buggy arriving, cherry trees in white blossom",
    "the-strange-case-of-dr-jekyll-and-mr-hyde": "a foggy Victorian London street at night under a gas lamp, a respectable gentleman's shadow on the wall turning into a crooked sinister figure, a laboratory door",
    "the-time-machine": "a Victorian inventor seated in a brass and ivory time machine with glowing quartz rods, far-future ruins and a giant white sphinx statue under a strange sky",
    "the-invisible-man": "an empty suit of clothes with gloves, bandaged head, dark goggles and a hat, standing in a snowy English village street, frightened villagers in the background",
    "the-wonderful-wizard-of-oz": "a girl in a blue and white gingham dress and silver shoes with a little black dog, a scarecrow, a tin woodman and a lion on a yellow brick road, a green city on the horizon",
    "the-happy-prince-and-other-stories": "a tall golden statue of a prince covered in gold leaf with sapphire eyes on a column above a winter city, a small swallow resting at his feet",
    "sherlock-holmes-four-adventures": "a tall detective in a cape coat with a magnifying glass and his friend with a moustache, in a Victorian sitting room at Baker Street, a violin and a fireplace, gaslight",
    "pride-and-prejudice": "a young woman in a Regency empire-waist dress walking across green English fields with muddy hems, a grand country house and a reserved gentleman on the path ahead",
    "frankenstein": "a young scientist with a candle in a stormy attic laboratory with glass jars and electrical apparatus, lightning through the window, an enormous figure lying under a sheet",
    "dracula": "a gloomy castle on a cliff in the Carpathian mountains at night with a full moon, a horse-drawn carriage on a winding road, wolves on the rocks, bats in the sky",
    "the-great-gatsby": "a 1920s Long Island mansion lit up for a party on the shore at night, a man in a white suit on a dock looking at a small green light across the bay",
}
# Obras cuyo título dispara el filtro de seguridad de la salida (personaje asociado a una película):
# se describe la escena sin nombrarla.
UNNAMED = {"the-adventures-of-pinocchio": "a classic Italian children's novel of 1883"}
STYLE = {
    "story": "Warm painted storybook illustration, gouache and ink, soft textures, rich but gentle colours, "
             "friendly characters, suitable for teenage and adult language learners.",
    "novel": "Painted illustration in the manner of classic early twentieth-century book illustration, "
             "atmospheric light, detailed period setting, rich colours.",
}
RULES = ("Vertical book cover composition. Main subject in the upper two thirds; keep the bottom third "
         "simple and uncluttered (sky, ground or shadow) because a title will be added there later. "
         "Absolutely no text, no letters, no numbers, no title, no signature, no watermark, no frame, "
         "no border. Original character designs based on the public-domain book, not on any film, "
         "animation or later adaptation.")


def prompt_for(work):
    kind = "story" if work["level"] in ("A1", "A2") else "novel"
    scene = SCENES.get(work["work"]) or f"an iconic scene from {work['title']} ({work['description'][:300]})"
    origin = UNNAMED.get(work["work"]) or f"\"{work['title']}\" by {work['authors'][0]}"
    return f"{STYLE[kind]} Scene from {origin}: {scene}. {RULES}"


def api_key():
    if os.environ.get("OPENAI_API_KEY"):
        return os.environ["OPENAI_API_KEY"]
    env = ROOT / ".env"
    if env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            k, _, v = line.partition("=")
            if k.strip() == "OPENAI_API_KEY":
                return v.strip().strip('"').strip("'")
    sys.exit("Falta OPENAI_API_KEY (entorno o .env).")


class Blocked(Exception):
    """El filtro de seguridad rechazó la imagen (no se cobra); se reintenta o se salta."""


def generate(prompt, model=MODEL, moderation=None):
    body = {"model": model, "prompt": prompt, "size": SIZE, "quality": QUALITY, "n": 1,
            "output_format": "webp", "output_compression": 82}
    if moderation:
        body["moderation"] = moderation
    req = urllib.request.Request(
        "https://api.openai.com/v1/images/generations", data=json.dumps(body).encode(), method="POST",
        headers={"Authorization": f"Bearer {api_key()}", "Content-Type": "application/json"})
    for attempt in range(6):
        try:
            with urllib.request.urlopen(req, timeout=900) as r:
                data = json.load(r)
            return base64.b64decode(data["data"][0]["b64_json"]), data.get("usage")
        except urllib.error.HTTPError as e:
            detail = e.read().decode(errors="replace")[:500]
            if "moderation_blocked" in detail:
                raise Blocked(detail)
            if e.code in (408, 429, 500, 502, 503) and attempt < 5:
                wait = 30 * 2 ** attempt
                print(f"  {e.code}, reintento en {wait} s: {detail[:160]}")
                time.sleep(wait)
                continue
            sys.exit(f"Error {e.code} de la API: {detail}")
        except (TimeoutError, urllib.error.URLError) as e:
            if attempt < 5:
                print(f"  {e}; reintento")
                time.sleep(30)
                continue
            raise


def shrink(data: bytes) -> bytes:
    """Ilustración maestra a 768x1152 (la portada final es de 600x900): ~100 KB en lugar de ~250 KB."""
    img = Image.open(io.BytesIO(data)).convert("RGB")
    if img.width > MASTER_W:
        img = img.resize((MASTER_W, round(img.height * MASTER_W / img.width)), Image.LANCZOS)
    out = io.BytesIO()
    img.save(out, "WEBP", quality=80, method=6)
    return out.getvalue()


def font(size, weight=b"Bold"):
    if not FONT.exists():
        FONT.parent.mkdir(parents=True, exist_ok=True)
        urllib.request.urlretrieve(FONT_URL, FONT)
    f = ImageFont.truetype(str(FONT), size)
    try:
        f.set_variation_by_name(weight)
    except (OSError, ValueError):
        pass
    return f


def wrap(draw, text, fnt, width):
    lines, line = [], ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if draw.textlength(trial, font=fnt) <= width or not line:
            line = trial
        else:
            lines.append(line)
            line = word
    return lines + [line]


def compose_cover(book, quality=80) -> bytes:
    master = COVERS / f"{book['work']}.webp"
    if master.exists():
        img = Image.open(master).convert("RGB")
        scale = max(W / img.width, H / img.height)
        img = img.resize((round(img.width * scale), round(img.height * scale)), Image.LANCZOS)
        left, top = (img.width - W) // 2, (img.height - H) // 2
        img = img.crop((left, top, left + W, top + H))
    else:   # provisional: degradado del color del nivel
        c = LEVEL_COLOURS[book["level"]]
        img = Image.new("RGB", (W, H), c)
        d = ImageDraw.Draw(img)
        for y in range(H):
            t = y / H
            d.line([(0, y), (W, y)], fill=tuple(int(v * (1 - 0.5 * t)) for v in c))
    img = img.convert("RGBA")

    shade = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    sd = ImageDraw.Draw(shade)
    band = int(H * 0.42)
    for y in range(band):
        sd.line([(0, H - band + y), (W, H - band + y)], fill=(0, 0, 0, int(215 * (y / band) ** 1.3)))
    img = Image.alpha_composite(img, shade)
    d = ImageDraw.Draw(img)

    margin, max_w = 40, W - 80
    for size in range(58, 25, -2):
        title_font = font(size)
        lines = wrap(d, book["title"], title_font, max_w)
        if len(lines) <= 3 and all(d.textlength(l, font=title_font) <= max_w for l in lines):
            break
    author_font = font(24, b"Regular")
    line_h = int(title_font.size * 1.18)
    y = H - 60 - 34 - line_h * len(lines)
    for line in lines:
        d.text((margin + 2, y + 2), line, font=title_font, fill=(0, 0, 0, 160))
        d.text((margin, y), line, font=title_font, fill=(255, 255, 255, 255))
        y += line_h
    d.text((margin, H - 60), ", ".join(book["authors"]), font=author_font, fill=(235, 235, 235, 230))

    badge_font = font(28)
    label = book["level"]
    bw = int(d.textlength(label, font=badge_font)) + 32
    d.rounded_rectangle([W - margin - bw, margin, W - margin, margin + 50], radius=25,
                        fill=LEVEL_COLOURS[label] + (235,))
    d.text((W - margin - bw + 16, margin + 8), label, font=badge_font, fill=(255, 255, 255, 255))

    out = io.BytesIO()
    img.convert("RGB").save(out, "JPEG", quality=quality, optimize=True, progressive=True)
    return out.getvalue()


def works():
    seen = {}
    for b in books():
        if b["lang"] == "en":
            seen[b["work"]] = b
    return list(seen.values())


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", help="slugs ingleses separados por comas")
    ap.add_argument("--force", action="store_true", help="regenerar aunque exista la ilustración")
    ap.add_argument("--model", default=MODEL)
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    only = set(args.only.split(",")) if args.only else None
    todo = [w for w in works() if (not only or w["work"] in only)
            and (args.force or not (COVERS / f"{w['work']}.webp").exists())]
    lo, hi = COST_ESTIMATE
    print(f"{len(todo)} ilustraciones; coste estimado ${len(todo) * lo:.2f}–${len(todo) * hi:.2f}")
    prompts = json.loads(PROMPTS.read_text(encoding="utf-8")) if PROMPTS.exists() else {}
    blocked = []
    for i, w in enumerate(todo, 1):
        prompt = prompt_for(w)
        print(f"[{i}/{len(todo)}] {w['work']}")
        if args.dry_run:
            print(f"  {prompt}")
            continue
        started = time.time()
        try:
            try:
                data, usage = generate(prompt, args.model)
            except Blocked:
                print("  bloqueada por el filtro de seguridad; reintento con moderation=low")
                data, usage = generate(prompt, args.model, moderation="low")
        except Blocked:
            print("  bloqueada otra vez: se salta (revisa la escena en SCENES y repite con --only)")
            blocked.append(w["work"])
            continue
        COVERS.mkdir(parents=True, exist_ok=True)
        (COVERS / f"{w['work']}.webp").write_bytes(shrink(data))
        prompts[w["work"]] = {"model": args.model, "size": SIZE, "quality": QUALITY, "prompt": prompt}
        PROMPTS.write_text(json.dumps(prompts, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"  {len(data) // 1024} KiB en {time.time() - started:.0f} s  uso: {usage}")
    if blocked:
        print(f"Sin ilustración (filtro de seguridad): {', '.join(blocked)}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
