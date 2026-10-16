"""Genera las ilustraciones provisionales de los libros de demostración (CC0-1.0).

Son dibujos planos hechos con primitivas de Pillow: no usan recursos de terceros.
Tras generarlas, escribe el sha256 de cada imagen en el manifiesto correspondiente
(campo "sha256" del objeto media) sin reformatear el resto del JSON.

Uso:  python tools/generate_demo_images.py
"""
import hashlib
import math
import re
from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
BOOKS = ROOT / "app" / "src" / "main" / "assets" / "books"
W, H = 960, 540


def canvas(top, bottom):
    img = Image.new("RGB", (W, H), top)
    d = ImageDraw.Draw(img)
    for y in range(H):
        t = y / (H - 1)
        c = tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3))
        d.line([(0, y), (W, y)], fill=c)
    return img, d


def kite(d, cx, cy, s, tail_to):
    pts = [(cx, cy - s), (cx + s * 0.7, cy), (cx, cy + s * 1.3), (cx - s * 0.7, cy)]
    d.polygon(pts, fill=(214, 48, 49))
    d.polygon([pts[0], pts[1], (cx, cy)], fill=(253, 203, 110))
    d.polygon([pts[2], pts[3], (cx, cy)], fill=(253, 203, 110))
    d.line([pts[0], pts[2]], fill=(90, 40, 20), width=3)
    d.line([pts[1], pts[3]], fill=(90, 40, 20), width=3)
    # cola azul ondulada
    x0, y0 = pts[2]
    x1, y1 = tail_to
    prev = (x0, y0)
    for i in range(1, 21):
        t = i / 20
        x = x0 + (x1 - x0) * t + math.sin(t * 9) * 18
        y = y0 + (y1 - y0) * t
        d.line([prev, (x, y)], fill=(9, 132, 227), width=5)
        prev = (x, y)
    return pts[2]


def sea(d, horizon, color=(41, 128, 185)):
    d.rectangle([0, horizon, W, H], fill=color)
    for k in range(6):
        y = horizon + 18 + k * 26
        for x in range(-40, W, 80):
            d.arc([x, y, x + 60, y + 18], 200, 340, fill=(214, 234, 248), width=3)


def wind_lines(d, n=5):
    for i in range(n):
        y = 60 + i * 38
        x = 80 + (i % 2) * 140
        d.arc([x, y, x + 220, y + 40], 190, 350, fill=(255, 255, 255), width=4)


def person(d, x, ground, h, shirt, hair=(60, 40, 30)):
    head = h * 0.18
    d.ellipse([x - head, ground - h, x + head, ground - h + head * 2], fill=(241, 196, 160))
    d.pieslice([x - head, ground - h - 2, x + head, ground - h + head * 2], 180, 360, fill=hair)
    d.rounded_rectangle([x - h * 0.16, ground - h + head * 2, x + h * 0.16, ground - h * 0.35], radius=8, fill=shirt)
    d.line([(x - h * 0.08, ground - h * 0.35), (x - h * 0.1, ground)], fill=(52, 73, 94), width=int(h * 0.07))
    d.line([(x + h * 0.08, ground - h * 0.35), (x + h * 0.1, ground)], fill=(52, 73, 94), width=int(h * 0.07))


def en_cover():
    img, d = canvas((116, 185, 255), (223, 240, 255))
    sea(d, 400)
    wind_lines(d)
    kite(d, 620, 170, 80, (520, 430))
    return img


def en_house():
    img, d = canvas((129, 199, 245), (236, 247, 255))
    d.ellipse([780, 50, 880, 150], fill=(255, 234, 167))
    sea(d, 360)
    d.polygon([(0, 360), (520, 360), (440, 420), (0, 440)], fill=(120, 170, 90))
    d.rectangle([160, 230, 380, 380], fill=(250, 250, 245))
    d.polygon([(140, 235), (270, 140), (400, 235)], fill=(192, 57, 43))
    d.rectangle([300, 270, 350, 320], fill=(116, 185, 255))
    d.line([(325, 270), (325, 320)], fill=(255, 255, 255), width=3)
    d.line([(300, 295), (350, 295)], fill=(255, 255, 255), width=3)
    d.rectangle([200, 300, 245, 380], fill=(127, 84, 52))
    wind_lines(d, 4)
    return img


def en_beach():
    img, d = canvas((99, 179, 237), (220, 238, 252))
    sea(d, 300)
    d.polygon([(0, 380), (W, 330), (W, H), (0, H)], fill=(246, 219, 160))
    end = kite(d, 700, 110, 60, (640, 300))
    d.line([end, (430, 410)], fill=(80, 80, 80), width=2)
    person(d, 430, 470, 130, (231, 76, 60))
    person(d, 330, 480, 110, (155, 89, 182), hair=(181, 101, 29))
    return img


def en_lighthouse():
    img, d = canvas((250, 177, 160), (255, 234, 167))
    sea(d, 380, color=(52, 101, 164))
    d.polygon([(0, 380), (W, 400), (W, H), (0, H)], fill=(106, 176, 76))
    d.polygon([(720, 380), (760, 120), (800, 120), (840, 380)], fill=(245, 246, 250))
    for y in (170, 250, 330):
        d.polygon([(760 - (y - 120) * 0.15, y), (800 + (y - 120) * 0.15, y), (806 + (y - 120) * 0.15, y + 30), (754 - (y - 120) * 0.15, y + 30)], fill=(214, 48, 49))
    d.rectangle([760, 90, 800, 120], fill=(253, 203, 110))
    d.polygon([(750, 90), (780, 60), (810, 90)], fill=(45, 52, 54))
    for tx in (90, 200):
        d.rectangle([tx + 30, 300, tx + 50, 420], fill=(110, 76, 50))
        d.ellipse([tx - 20, 200, tx + 100, 320], fill=(46, 134, 70))
    d.rectangle([460, 400, 600, 412], fill=(127, 84, 52))
    d.rectangle([470, 412, 480, 440], fill=(127, 84, 52))
    d.rectangle([580, 412, 590, 440], fill=(127, 84, 52))
    person(d, 400, 470, 150, (99, 110, 114), hair=(220, 220, 220))
    kite(d, 445, 320, 34, (470, 420))
    return img


def market_awning(d, x0, x1, y, colors):
    stripe = (x1 - x0) / 8
    for i in range(8):
        d.rectangle([x0 + i * stripe, y, x0 + (i + 1) * stripe, y + 50], fill=colors[i % 2])
        d.pieslice([x0 + i * stripe, y + 30, x0 + (i + 1) * stripe, y + 70], 0, 180, fill=colors[i % 2])


def tomatoes(d, x, y, n=7):
    for i in range(n):
        cx = x + (i % 4) * 34 + (i // 4) * 17
        cy = y - (i // 4) * 26
        d.ellipse([cx - 18, cy - 18, cx + 18, cy + 18], fill=(214, 48, 49))
        d.polygon([(cx - 6, cy - 18), (cx, cy - 26), (cx + 6, cy - 18)], fill=(39, 174, 96))


def cheese(d, x, y):
    d.polygon([(x, y), (x + 120, y), (x + 120, y - 60)], fill=(253, 203, 110))
    for hx, hy in ((x + 70, y - 12), (x + 95, y - 30), (x + 105, y - 10)):
        d.ellipse([hx - 6, hy - 6, hx + 6, hy + 6], fill=(229, 170, 60))


def flowers(d, x, y, n=5, size=22):
    for i in range(n):
        fx = x + i * size * 2.2
        fy = y - (i % 2) * size
        d.line([(fx, fy), (fx + 10, y + 120)], fill=(39, 174, 96), width=5)
        for a in range(6):
            ang = a * math.pi / 3
            px, py = fx + math.cos(ang) * size * 0.8, fy + math.sin(ang) * size * 0.8
            d.ellipse([px - size * 0.55, py - size * 0.55, px + size * 0.55, py + size * 0.55], fill=(253, 203, 110))
        d.ellipse([fx - size * 0.4, fy - size * 0.4, fx + size * 0.4, fy + size * 0.4], fill=(225, 112, 85))


def fr_cover():
    img, d = canvas((255, 240, 220), (250, 215, 185))
    d.rectangle([180, 260, 780, 460], fill=(160, 110, 70))
    market_awning(d, 160, 800, 120, [(41, 128, 185), (255, 255, 255)])
    tomatoes(d, 230, 250)
    cheese(d, 420, 255)
    flowers(d, 600, 160, n=4)
    return img


def fr_market():
    img, d = canvas((174, 214, 241), (253, 235, 208))
    d.rectangle([0, 380, W, H], fill=(189, 195, 199))
    colors = [[(192, 57, 43), (255, 255, 255)], [(39, 174, 96), (255, 255, 255)], [(41, 128, 185), (255, 255, 255)]]
    for i, x in enumerate((40, 340, 640)):
        d.rectangle([x + 20, 260, x + 260, 400], fill=(160, 110, 70))
        d.rectangle([x + 20, 160, x + 30, 400], fill=(110, 76, 50))
        d.rectangle([x + 250, 160, x + 260, 400], fill=(110, 76, 50))
        market_awning(d, x, x + 280, 130, colors[i])
    tomatoes(d, 80, 250)
    cheese(d, 420, 255)
    flowers(d, 680, 200, n=4, size=18)
    person(d, 250, 500, 130, (155, 89, 182))
    person(d, 560, 505, 140, (243, 156, 18), hair=(30, 30, 30))
    return img


def fr_stall():
    img, d = canvas((253, 245, 230), (240, 220, 190))
    d.rectangle([0, 330, W, H], fill=(160, 110, 70))
    tomatoes(d, 120, 320, n=11)
    cheese(d, 520, 320)
    cheese(d, 660, 320)
    d.rectangle([560, 230, 700, 270], fill=(255, 255, 255))
    d.text((585, 242), "1 kg", fill=(45, 52, 54))
    return img


def fr_flowers():
    img, d = canvas((232, 246, 239), (200, 230, 210))
    d.polygon([(380, 330), (580, 330), (520, 500), (440, 500)], fill=(223, 230, 233))
    flowers(d, 360, 220, n=5, size=26)
    return img


IMAGES = {
    "en-a1-the-red-kite": {
        "images/cover.png": en_cover,
        "images/ch1-p1.png": en_house,
        "images/ch1-p3.png": en_beach,
        "images/ch2-p2.png": en_lighthouse,
    },
    "fr-a2-le-marche-du-samedi": {
        "images/cover.png": fr_cover,
        "images/ch1-p1.png": fr_market,
        "images/ch2-p1.png": fr_stall,
        "images/ch2-p3.png": fr_flowers,
    },
}


def main():
    for book, images in IMAGES.items():
        book_dir = BOOKS / book
        manifest_path = book_dir / "manifest.json"
        manifest = manifest_path.read_text(encoding="utf-8")
        for rel, draw in images.items():
            out = book_dir / rel
            out.parent.mkdir(parents=True, exist_ok=True)
            draw().save(out, format="PNG", optimize=True)
            digest = hashlib.sha256(out.read_bytes()).hexdigest()
            # Inserta o reemplaza "sha256" justo después de "path" sin reformatear el JSON.
            pattern = re.compile(r'("path": "' + re.escape(rel) + r'")(, "sha256": "[0-9a-f]{64}")?')
            manifest, count = pattern.subn(r'\1, "sha256": "' + digest + '"', manifest)
            print(f"{book}/{rel}: {out.stat().st_size // 1024} KiB sha256={digest[:12]}… refs={count}")
        manifest_path.write_text(manifest, encoding="utf-8", newline="\n")


if __name__ == "__main__":
    main()
