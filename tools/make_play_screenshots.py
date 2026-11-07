"""Compose real Android screenshots with localized Play Store marketing copy.

python tools/make_play_screenshots.py [--install]
Original pixels are resized/cropped only; UI is never repainted. Pillow required.
"""
from pathlib import Path
import argparse
import json
import shutil
import zipfile
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops

ROOT = Path(__file__).resolve().parent.parent
META = ROOT / 'fastlane/metadata/android'
BASE = ROOT / 'captures/phone/store-2026-11'
OUT = BASE / 'composed'
TEAL, CREAM, GOLD = '#073942', '#FCF7E9', '#DDA531'

def font(size, serif=False):
    path = ROOT / 'tools/fonts/Merriweather.ttf' if serif else Path('C:/Windows/Fonts/arial.ttf')
    if not path.exists():
        path = Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
    return ImageFont.truetype(str(path), size)

def wrap(draw, text, f, width):
    lines = ['']
    for word in text.split():
        trial = (lines[-1] + ' ' + word).strip()
        if draw.textlength(trial, font=f) > width:
            lines.append(word)
        else:
            lines[-1] = trial
    return lines

def compose(locale, data, card, number):
    scene, kicker, title, subtitle = card
    dark = scene in ('bilingual', 'vocabulary')
    bg, ink = (TEAL, CREAM) if dark else (CREAM, TEAL)
    canvas = Image.new('RGB', (1080, 1920), bg)
    d = ImageDraw.Draw(canvas)
    # Small open-book mark, drawn as vector primitives, and a quiet wordmark.
    d.line([(64,67),(91,73),(118,67),(118,101),(91,107),(64,101),(64,67)], fill=GOLD, width=4)
    d.line([(91,73),(91,107)], fill=GOLD, width=3)
    d.text((138,67), 'LingoAudioBook', font=font(30), fill=ink)
    d.text((1016,70), f'{number:02d} / 06', font=font(24), fill=ink, anchor='rt')
    size = 28
    while d.textlength(kicker, font=font(size)) > 950:
        size -= 1
    d.text((64,144), kicker, font=font(size), fill=GOLD if dark else TEAL)
    size = 78
    lines = title.splitlines()
    while max(d.textlength(line, font=font(size, True)) for line in lines) > 952:
        size -= 1
    for i,line in enumerate(lines):
        d.text((60,194+i*98), line, font=font(size, True), fill=ink)
    sublines = wrap(d, subtitle, font(31), 940)
    assert len(sublines) <= 2, (locale, scene, sublines)
    for i,line in enumerate(sublines):
        d.text((64,401+i*41), line, font=font(31), fill=ink)
    source = BASE / 'raw' / locale / f'{scene}.png'
    evidence = json.loads(source.with_suffix('.json').read_text(encoding='utf-8'))
    assert evidence['ui_locale'] == locale
    assert evidence['translation_language'] == locale.split('-')[0]
    assert evidence['source_language'] != evidence['translation_language']
    im = Image.open(source).convert('RGB')
    assert im.size == (1080,1920), (source, im.size)
    # Remove empty system bars only. Entire app content remains real.
    im = im.crop((0,110,1080,1800 if scene == 'finger' else 1860))
    im.thumbnail((944,1280), Image.Resampling.LANCZOS)
    x,y = (1080-im.width)//2, 530
    shadow = Image.new('RGBA', canvas.size)
    ImageDraw.Draw(shadow).rounded_rectangle((x-6,y+12,x+im.width+6,y+im.height+18),radius=22,fill=(0,0,0,70))
    shadow = shadow.filter(ImageFilter.GaussianBlur(18))
    canvas = Image.alpha_composite(canvas.convert('RGBA'),shadow).convert('RGB')
    canvas.paste(im,(x,y))
    assert ImageChops.difference(canvas.crop((x,y,x+im.width,y+im.height)),im).getbbox() is None
    d = ImageDraw.Draw(canvas)
    footer = data['example'] if scene in ('bilingual','translate','vocabulary') else 'LingoAudioBook'
    d.text((540,1840), footer, font=font(25), fill=ink, anchor='mt')
    for i in range(6):
        cx=485+i*22
        d.ellipse((cx,1890,cx+8,1898),fill=GOLD if i==number-1 else ink)
    folder=OUT/locale
    folder.mkdir(parents=True,exist_ok=True)
    target=folder/f'{number:02d}_{scene}.png'
    canvas.save(target,optimize=True)
    assert target.stat().st_size < 8*1024*1024
    return target

def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--install',action='store_true')
    args=parser.parse_args()
    allfiles=[]
    for copy in sorted(META.glob('*/screenshot_copy.json')):
        locale=copy.parent.name
        data=json.loads(copy.read_text(encoding='utf-8'))
        files=[compose(locale,data,card,n) for n,card in enumerate(data['cards'],1)]
        allfiles.extend(files)
        sheet=Image.new('RGB',(1620,480),'#dedbd3')
        for i,p in enumerate(files):
            preview=Image.open(p); preview.thumbnail((270,480),Image.Resampling.LANCZOS)
            sheet.paste(preview,(270*i,0))
        sheet.save(BASE/f'preview-{locale}.jpg',quality=92)
        if args.install:
            target=copy.parent/'images/phoneScreenshots'
            target.mkdir(parents=True,exist_ok=True)
            backup=BASE/'previous-fastlane'/locale
            backup.mkdir(parents=True,exist_ok=True)
            for old in target.iterdir():
                if old.suffix.lower() in ('.png','.jpg','.jpeg'):
                    if not (backup/old.name).exists(): shutil.copy2(old,backup/old.name)
                    old.unlink()
            for p in files: shutil.copy2(p,target/p.name)
        print(locale, '6 screenshots verified',flush=True)
    with zipfile.ZipFile(BASE/'LingoAudioBook-PlayStore-6-languages.zip','w',zipfile.ZIP_DEFLATED) as z:
        for p in allfiles: z.write(p,p.relative_to(OUT))
    print('Created',len(allfiles),'RGB PNGs, 1080 x 1920')

if __name__=='__main__': main()
