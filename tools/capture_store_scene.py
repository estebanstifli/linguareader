"""Capture the current Android scene in every supported UI locale via adb.

Usage: python tools/capture_store_scene.py library
The emulator must already display the intended scene. Inspect captures afterward.
This legacy helper changes UI language only. For native-language store assets use
refresh_play_captures.py; this helper deliberately invalidates locale evidence.
"""
from pathlib import Path
import argparse
import subprocess
import time
import os

ROOT = Path(__file__).resolve().parent.parent
ADB = Path(os.environ.get('ANDROID_HOME', Path.home() / 'AppData/Local/Android/Sdk')) / 'platform-tools/adb.exe'
LOCALES = {'en-US':'en','es-ES':'es','fr-FR':'fr','de-DE':'de','it-IT':'it','pt-BR':'pt-BR'}
def adb(*args):
    return subprocess.run([str(ADB), *args], check=True, capture_output=True).stdout

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('scene', choices=['library','bilingual','finger','translate','vocabulary','comfort'])
    parser.add_argument('--locale', choices=LOCALES)
    parser.add_argument('--tap', nargs=2, type=int, metavar=('X','Y'))
    parser.add_argument('--more', nargs=2, type=int, metavar=('X','Y'))
    args = parser.parse_args()
    selected = {args.locale:LOCALES[args.locale]} if args.locale else LOCALES
    for locale, tag in selected.items():
        adb('shell','cmd','locale','set-app-locales','com.lingoaudiobook.app','--user','0','--locales',tag)
        time.sleep(2)
        if args.tap:
            adb('shell','input','tap',*[str(v) for v in args.tap])
            time.sleep(1)
            adb('shell','uiautomator','dump','/sdcard/capture-check.xml')
            ui = adb('shell','cat','/sdcard/capture-check.xml').decode('utf-8')
            if '→' not in ui:
                adb('shell','input','tap',*[str(v) for v in args.tap])
                time.sleep(1)
        if args.more:
            adb('shell','input','tap',*[str(v) for v in args.more])
            time.sleep(2)
        folder = ROOT / 'captures/phone/store-2026-11/raw' / locale
        folder.mkdir(parents=True, exist_ok=True)
        target = folder / (args.scene + '.png')
        target.with_suffix('.json').unlink(missing_ok=True)
        target.write_bytes(adb('exec-out','screencap','-p'))
        print(target.relative_to(ROOT), flush=True)
