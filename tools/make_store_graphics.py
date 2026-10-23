"""Gráficos de la ficha de Google Play a partir del icono vectorial de la app.

  python -I tools/make_store_graphics.py

Genera en fastlane/metadata/android/<idioma>/images/:
  icon.png            512×512, el icono adaptativo (fondo + primer plano) a pantalla completa; Play aplica la máscara.
  featureGraphic.png  1024×500, icono, nombre y el lema de short_description.txt del idioma.
Dibuja los trazados de res/drawable/ic_launcher_foreground.xml (M, m, c, h, H, v, V, z) con Pillow, con
sobremuestreo para suavizar bordes. Fuente: tools/fonts/Merriweather.ttf (SIL OFL), la de make_covers.py.
"""
import pathlib
import re
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
META = ROOT / "fastlane/metadata/android"
FONT = ROOT / "tools/fonts/Merriweather.ttf"
ANDROID = "{http://schemas.android.com/apk/res/android}"
SS = 4  # sobremuestreo


def color(argb: str) -> tuple:
    h = argb.lstrip("#")
    if len(h) == 8:
        a, h = int(h[:2], 16), h[2:]
    else:
        a = 255
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (a,)


def path_polygons(d: str, steps: int = 24) -> list[list[tuple[float, float]]]:
    """Trazado de VectorDrawable → polígonos (las curvas cúbicas se muestrean)."""
    tokens = re.findall(r"[MmCcHhVvZzLl]|-?\d*\.?\d+", d)
    polys, cur, x, y, start, cmd, i = [], [], 0.0, 0.0, (0.0, 0.0), None, 0
    def num():
        nonlocal i
        v = float(tokens[i]); i += 1
        return v
    while i < len(tokens):
        if re.match(r"[A-Za-z]", tokens[i]):
            cmd = tokens[i]; i += 1
            if cmd in "Zz":
                if cur: polys.append(cur)
                cur, (x, y) = [], start
                continue
        if cmd in "Mm":
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if cmd == "m" else (nx, ny)
            if cur: polys.append(cur)
            cur, start = [(x, y)], (x, y)
            cmd = "l" if cmd == "m" else "L"
        elif cmd in "Ll":
            nx, ny = num(), num()
            x, y = (x + nx, y + ny) if cmd == "l" else (nx, ny)
            cur.append((x, y))
        elif cmd in "Hh":
            v = num(); x = x + v if cmd == "h" else v; cur.append((x, y))
        elif cmd in "Vv":
            v = num(); y = y + v if cmd == "v" else v; cur.append((x, y))
        elif cmd in "Cc":
            pts = [num() for _ in range(6)]
            if cmd == "c":
                pts = [pts[0] + x, pts[1] + y, pts[2] + x, pts[3] + y, pts[4] + x, pts[5] + y]
            x0, y0 = x, y
            for s in range(1, steps + 1):
                t = s / steps
                mt = 1 - t
                cur.append((
                    mt ** 3 * x0 + 3 * mt * mt * t * pts[0] + 3 * mt * t * t * pts[2] + t ** 3 * pts[4],
                    mt ** 3 * y0 + 3 * mt * mt * t * pts[1] + 3 * mt * t * t * pts[3] + t ** 3 * pts[5],
                ))
            x, y = pts[4], pts[5]
    if cur: polys.append(cur)
    return polys


def foreground(size: int) -> Image.Image:
    """Primer plano del icono (viewport 108) dibujado a [size] px, fondo transparente."""
    root = ET.parse(RES / "drawable/ic_launcher_foreground.xml").getroot()
    vw = float(root.get(ANDROID + "viewportWidth"))
    big = Image.new("RGBA", (size * SS, size * SS), (0, 0, 0, 0))
    draw = ImageDraw.Draw(big)
    k = size * SS / vw
    for p in root.iter("path"):
        fill = color(p.get(ANDROID + "fillColor"))
        for poly in path_polygons(p.get(ANDROID + "pathData")):
            draw.polygon([(px * k, py * k) for px, py in poly], fill=fill)
    return big.resize((size, size), Image.LANCZOS)


def background_color() -> tuple:
    text = (RES / "values/ic_launcher_background.xml").read_text(encoding="utf-8")
    return color(re.search(r">(#[0-9A-Fa-f]+)<", text).group(1))


def icon(size: int = 512) -> Image.Image:
    img = Image.new("RGBA", (size, size), background_color())
    img.alpha_composite(foreground(size))
    return img


def fit_font(text: str, max_width: int, start: int) -> ImageFont.FreeTypeFont:
    size = start
    while size > 12:
        font = ImageFont.truetype(str(FONT), size)
        if font.getlength(text) <= max_width:
            return font
        size -= 2
    return ImageFont.truetype(str(FONT), size)


def wrap(text: str, font: ImageFont.FreeTypeFont, width: int) -> list[str]:
    lines, line = [], ""
    for word in text.split():
        trial = f"{line} {word}".strip()
        if font.getlength(trial) <= width:
            line = trial
        else:
            lines.append(line); line = word
    return lines + [line]


def feature_graphic(tagline: str) -> Image.Image:
    w, h = 1024, 500
    bg = background_color()
    img = Image.new("RGBA", (w, h), bg)
    draw = ImageDraw.Draw(img)
    img.alpha_composite(foreground(560), (w - 540, -30))
    name_font = fit_font("LingoAudioBook", 560, 64)
    draw.text((56, 150), "LingoAudioBook", font=name_font, fill=(255, 255, 255, 255))
    tag_font = ImageFont.truetype(str(FONT), 30)
    y = 150 + name_font.size + 36
    for line in wrap(tagline, tag_font, 520)[:3]:
        draw.text((56, y), line, font=tag_font, fill=(227, 239, 233, 255))
        y += 44
    return img


def main():
    ico = icon()
    for loc in sorted(p for p in META.iterdir() if p.is_dir()):
        out = loc / "images"
        out.mkdir(exist_ok=True)
        ico.convert("RGB").save(out / "icon.png")
        tagline = (loc / "short_description.txt").read_text(encoding="utf-8").strip()
        feature_graphic(tagline).convert("RGB").save(out / "featureGraphic.png")
        print(loc.name, "OK")


if __name__ == "__main__":
    main()
