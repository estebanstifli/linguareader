"""Generate localized Play metadata and six feature graphics from real book art.

python tools/refresh_play_listing_assets.py
Uses Pillow; does not contact Play or alter screenshots.
"""
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
META = ROOT / 'fastlane/metadata/android'
CREAM, TEAL, GOLD = '#FCF7E9', '#073942', '#DDA531'
HEADLINES = {
    'en-US': ('Bilingual\nbooks.', 'Learn languages.\nGet lost in a story.', 'READ  ·  LISTEN  ·  LEARN'),
    'es-ES': ('Libros\nbilingües.', 'Aprende idiomas.\nVive una historia.', 'LEE  ·  ESCUCHA  ·  APRENDE'),
    'fr-FR': ('Livres\nbilingues.', 'Apprenez les langues.\nVivez une histoire.', 'LISEZ  ·  ÉCOUTEZ  ·  APPRENEZ'),
    'de-DE': ('Zweisprachige\nBücher.', 'Sprachen lernen.\nGeschichten erleben.', 'LESEN  ·  HÖREN  ·  LERNEN'),
    'it-IT': ('Libri\nbilingui.', 'Impara le lingue.\nVivi una storia.', 'LEGGI  ·  ASCOLTA  ·  IMPARA'),
    'pt-BR': ('Livros\nbilíngues.', 'Aprenda idiomas.\nViva uma história.', 'LEIA  ·  OUÇA  ·  APRENDA'),
}

def font(size, serif=False):
    file = ROOT/'tools/fonts/Merriweather.ttf' if serif else Path('C:/Windows/Fonts/arial.ttf')
    if not file.exists():
        file = Path('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf')
    return ImageFont.truetype(str(file), size)

def cover(canvas, name, pos, size, angle):
    art = Image.open(ROOT/'content/graded-books/covers'/f'{name}.webp').convert('RGBA')
    art = art.resize(size, Image.Resampling.LANCZOS)
    mask = Image.new('L', size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0,0,size[0]-1,size[1]-1),radius=7,fill=255)
    art.putalpha(mask)
    art = art.rotate(angle, Image.Resampling.BICUBIC, expand=True)
    shadow = Image.new('RGBA', canvas.size)
    silhouette = Image.new('RGBA',art.size,(7,57,66,0))
    silhouette.putalpha(art.getchannel('A').point(lambda a: int(a*.28)))
    shadow.alpha_composite(silhouette,(pos[0]+7,pos[1]+13))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(13)))
    canvas.alpha_composite(art,pos)

def feature(locale):
    title, subtitle, footer = HEADLINES[locale]
    im = Image.new('RGBA',(1024,500),CREAM)
    d = ImageDraw.Draw(im)
    d.ellipse((585,35,1080,530),fill='#F0E5C9')
    d.line([(56,62),(74,67),(92,62),(92,87),(74,92),(56,87),(56,62)],fill=GOLD,width=3)
    d.line((74,67,74,92),fill=GOLD,width=2)
    d.text((105,62),'LingoAudioBook',font=font(24),fill=TEAL)
    f = font(55,True)
    while max(d.textlength(line,font=f) for line in title.splitlines())>525:
        f = font(f.size-1,True)
    for i,line in enumerate(title.splitlines()):
        d.text((52,141+i*74),line,font=f,fill=TEAL)
    for i,line in enumerate(subtitle.splitlines()):
        d.text((56,321+i*34),line,font=font(26),fill=TEAL)
    d.line((56,410,112,410),fill=GOLD,width=3)
    d.text((56,434),footer,font=font(17),fill=TEAL)
    cover(im,'cinderella',(584,103),(174,261),9)
    cover(im,'jack-and-the-beanstalk',(806,99),(174,261),-8)
    cover(im,'little-red-riding-hood',(701,157),(184,276),-2)
    return im.convert('RGB')

def main():
    copy = json.loads((ROOT/'tools/play_listing_copy.json').read_text(encoding='utf-8'))
    for locale,(short,full) in copy.items():
        assert 0<len(short)<=80, (locale,len(short))
        assert 0<len(full)<=4000, (locale,len(full))
        folder = META/locale
        folder.mkdir(parents=True,exist_ok=True)
        for name,value in [('title','LingoAudioBook'),('short_description',short),('full_description',full)]:
            (folder/f'{name}.txt').write_text(value+'\n',encoding='utf-8')
    sheet = Image.new('RGB',(1024,975),CREAM)
    for index,locale in enumerate(HEADLINES):
        im = feature(locale)
        folder = META/locale/'images'
        folder.mkdir(exist_ok=True)
        im.save(folder/'featureGraphic.png',optimize=True)
        thumb = im.resize((512,250),Image.Resampling.LANCZOS)
        sheet.paste(thumb,((index%2)*512,(index//2)*325))
        # Keep locale labels separate from the assets uploaded to Play.
        ImageDraw.Draw(sheet).text(((index%2)*512+20,(index//2)*325+270),locale,font=font(26),fill=TEAL)
    dest = ROOT/'artifacts/play-console/creative-refresh'
    dest.mkdir(parents=True,exist_ok=True)
    sheet.save(dest/'feature-graphics-preview.jpg',quality=92)
    print(f'Validated and generated {len(copy)} listings and {len(HEADLINES)} feature graphics.')
    print(dest/'feature-graphics-preview.jpg')

if __name__=='__main__':
    main()
