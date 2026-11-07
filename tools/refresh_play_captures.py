"""Real-emulator helpers for localized store captures (requires opt-in test APK).

Build :app:assembleDebugAndroidTest -PstoreCapture=true and install both APKs.
Each locale has its own native translation language, including English from French.
"""
import re
import time
import json
import xml.etree.ElementTree as ET
from pathlib import Path
from capture_store_scene import adb, ROOT, LOCALES

BASE = ROOT / 'captures/phone/store-2026-11/raw'
PACKAGE = 'com.lingoaudiobook.app'

def ui():
    for attempt in range(4):
        try:
            result = adb('shell','uiautomator','dump','/sdcard/fresh-store.xml')
            if b'dumped' not in result: raise ValueError(result)
            return ET.fromstring(adb('shell','cat','/sdcard/fresh-store.xml'))
        except Exception:
            if attempt == 3: raise
            time.sleep(1)

def nodes():
    return [n.attrib for n in ui().iter('node') if n.get('text') or n.get('content-desc')]

def tap(x,y):
    adb('shell','input','tap',str(int(x)),str(int(y)))
    time.sleep(.6)

def click(label, description=False):
    key = 'content-desc' if description else 'text'
    for n in ui().iter('node'):
        if n.get(key) == label:
            x1,y1,x2,y2 = map(int,re.findall(r'\d+',n.get('bounds')))
            tap((x1+x2)/2,(y1+y2)/2)
            return
    raise ValueError('Not visible: '+label)

def resource(locale,key):
    tag=LOCALES[locale].split('-')[0]
    directory='values' if tag=='en' else 'values-'+tag
    for node in ET.parse(ROOT/f'app/src/main/res/{directory}/strings.xml').getroot():
        if node.get('name')==key: return ''.join(node.itertext()).replace("\\'", "'")
    raise KeyError(key)

def setup(locale, mode='below', theme='sepia', download=False):
    target=LOCALES[locale].split('-')[0]
    adb('shell','am','force-stop',PACKAGE)
    result=adb('shell','am','instrument','-w','-e','class','com.linguareader.StoreCaptureSetup',
        '-e','storeCapture','true','-e','target',target,'-e','mode',mode,'-e','theme',theme,
        '-e','reset','true','-e','download','all' if download else 'none',
        PACKAGE+'.test/androidx.test.runner.AndroidJUnitRunner').decode('utf-8')
    assert 'OK (1 test)' in result,result
    adb('shell','cmd','locale','set-app-locales',PACKAGE,'--user','0','--locales',LOCALES[locale])
    adb('shell','am','start','-n',PACKAGE+'/com.linguareader.MainActivity')
    time.sleep(5)

def save(locale,scene):
    folder=BASE/locale
    folder.mkdir(parents=True,exist_ok=True)
    xml=ui()
    assert any(n.get('package') == PACKAGE for n in xml.iter('node')), 'App is not visible'
    folder.joinpath(scene+'.xml').write_bytes(ET.tostring(xml,encoding='utf-8'))
    folder.joinpath(scene+'.png').write_bytes(adb('exec-out','screencap','-p'))
    metadata={'ui_locale':locale,'source_language':'fr' if locale=='en-US' else 'en',
        'translation_language':LOCALES[locale].split('-')[0],'scene':scene}
    folder.joinpath(scene+'.json').write_text(json.dumps(metadata,indent=2),encoding='utf-8')
    print(locale,scene,flush=True)

def open_first():
    tap(170,800)
    time.sleep(8)

def capture_locale(locale):
    setup(locale)
    save(locale,'library')
    open_first()
    save(locale,'bilingual')
    setup(locale,mode='tap')
    open_first()
    click(resource(locale,'player_play'),True)
    time.sleep(2)
    click(resource(locale,'player_pause'),True)
    save(locale,'finger')
    # Find a real paragraph and tap its first line, then wait for ML Kit.
    paragraph = next(n for n in nodes() if len(n['text'])>130 and not n['content-desc'])
    x1,y1,x2,y2=map(int,re.findall(r'\d+',paragraph['bounds']))
    tap(410,y1+35)
    time.sleep(3)
    more=resource(locale,'word_more_info')
    if not any(n['content-desc']==more for n in nodes()):
        tap(410,y1+35)
        time.sleep(2)
    assert any(n['content-desc']==more for n in nodes()), 'Word popup missing'
    save(locale,'translate')
    click(more,True)
    time.sleep(2)
    save(locale,'vocabulary')
    setup(locale,mode='tap',theme='dark')
    open_first()
    click(resource(locale,'player_play'),True)
    time.sleep(2)
    click(resource(locale,'player_pause'),True)
    save(locale,'comfort')

if __name__=='__main__':
    import argparse
    p=argparse.ArgumentParser()
    p.add_argument('locale',choices=LOCALES)
    p.add_argument('--mode',default='below')
    p.add_argument('--theme',default='sepia')
    p.add_argument('--download',action='store_true')
    p.add_argument('--capture',action='store_true')
    a=p.parse_args()
    if a.capture:
        capture_locale(a.locale)
    else:
        setup(a.locale,a.mode,a.theme,a.download)
        for n in nodes(): print(n['text'],n['content-desc'],n['bounds'])
