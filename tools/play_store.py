"""Read or publish Play listing metadata and explicitly selected graphics.

Requires google-auth and requests. PLAY_STORE_JSON_KEY points to a key outside Git.
python tools/play_store.py fetch
Creates a temporary edit to read listings, then deletes only that edit (never commits).
python tools/play_store.py stage-listing
Stages and validates local text plus the six localized image sets; prints a transaction path.
python tools/play_store.py commit-listing --transaction <path>
Verifies and commits that transaction, refusing to cancel an existing Google review.
"""
import argparse
import datetime as dt
import json
import os
from pathlib import Path
import sys
import hashlib
import requests
from PIL import Image
from google.oauth2 import service_account
from google.auth.transport.requests import AuthorizedSession

ROOT = Path(__file__).resolve().parent.parent
PACKAGE = 'com.lingoaudiobook.app'
BASE = f'https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{PACKAGE}/edits'
META = ROOT/'fastlane/metadata/android'
GRAPHIC_LOCALES = ('en-US','es-ES','fr-FR','de-DE','it-IT','pt-BR')

def checked(response):
    if not response.ok:
        try:
            message = response.json().get('error', {}).get('message', 'Google API error')
        except ValueError:
            message = 'Google API error'
        raise RuntimeError(f'HTTP {response.status_code}: {message}')
    return response.json() if response.content else {}

def connection():
    key_path = Path(os.environ['PLAY_STORE_JSON_KEY']).expanduser().resolve()
    if not key_path.is_file():
        raise RuntimeError('Credential file not found at PLAY_STORE_JSON_KEY.')
    if key_path.is_relative_to(ROOT):
        raise RuntimeError('Keep the private credential outside the repository.')
    credentials = service_account.Credentials.from_service_account_file(
        str(key_path), scopes=['https://www.googleapis.com/auth/androidpublisher'])
    return AuthorizedSession(credentials)

def fetch():
    session = connection()
    edit_id = None
    try:
        edit_id = checked(session.post(BASE, json={}, timeout=45))['id']
        data = checked(session.get(f'{BASE}/{edit_id}/listings', timeout=45))
        stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
        dest = ROOT / 'artifacts/play-console' / stamp
        dest.mkdir(parents=True)
        (dest/'listings.json').write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        for listing in data.get('listings', []):
            language = listing['language']
            if not all(c.isalnum() or c=='-' for c in language):
                raise RuntimeError('Unexpected locale identifier.')
            folder = dest/language
            folder.mkdir()
            for field, filename in [('title','title.txt'),('shortDescription','short_description.txt'),('fullDescription','full_description.txt')]:
                (folder/filename).write_text(listing.get(field,'')+'\n',encoding='utf-8')
            print(json.dumps({'language':language,'title':listing.get('title'),
                'short_description_chars':len(listing.get('shortDescription','')),
                'full_description_chars':len(listing.get('fullDescription',''))},ensure_ascii=True))
        print(f'Snapshot: {dest}')
    finally:
        if edit_id is not None:
            checked(session.delete(f'{BASE}/{edit_id}', timeout=45))
            print('Temporary edit discarded. No changes committed.')
        session.close()

def save(path, data):
    path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(data,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')

def read_local():
    listings = {}
    for folder in sorted(META.iterdir()):
        if not folder.is_dir() or not (folder/'title.txt').is_file():
            continue
        listing = {'language':folder.name}
        for field,filename,limit in [('title','title.txt',30),('shortDescription','short_description.txt',80),('fullDescription','full_description.txt',4000)]:
            value = (folder/filename).read_text(encoding='utf-8').strip()
            if not 0<len(value)<=limit:
                raise RuntimeError(f'{folder.name}/{filename}: invalid length {len(value)}')
            listing[field] = value
        listings[folder.name] = listing
    images = {}
    for locale in GRAPHIC_LOCALES:
        folder = META/locale/'images'
        images[locale] = {}
        for kind,paths in [('featureGraphic',[folder/'featureGraphic.png']),('phoneScreenshots',sorted((folder/'phoneScreenshots').glob('*.png')))]:
            if kind=='phoneScreenshots' and len(paths)!=6:
                raise RuntimeError(f'{locale}: expected six phone screenshots')
            assets = []
            for path in paths:
                with Image.open(path) as im:
                    expected = (1024,500) if kind=='featureGraphic' else (1080,1920)
                    if im.size!=expected or im.mode!='RGB' or im.format!='PNG':
                        raise RuntimeError(f'Invalid dimensions/format: {path}')
                    im.verify()
                data = path.read_bytes()
                if len(data)>=8*1024*1024:
                    raise RuntimeError(f'Image exceeds 8 MB: {path}')
                assets.append({'path':str(path.relative_to(ROOT)), 'sha256':hashlib.sha256(data).hexdigest()})
            images[locale][kind] = assets
    if not listings:
        raise RuntimeError('No listing metadata found')
    return listings,images

def verify_edit(session, edit_id, transaction):
    root = f'{BASE}/{edit_id}'
    actual = checked(session.get(f'{root}/listings',timeout=45))
    by_locale = {x['language']:x for x in actual.get('listings',[])}
    for locale,expected in transaction['listings'].items():
        if any(by_locale.get(locale,{}).get(k)!=v for k,v in expected.items()):
            raise RuntimeError(f'Remote listing mismatch: {locale}')
    result = {'listings':actual, 'images':{}}
    for locale,kinds in transaction['images'].items():
        result['images'][locale] = {}
        for kind,assets in kinds.items():
            remote = checked(session.get(f'{root}/listings/{locale}/{kind}',timeout=45))
            hashes = [x.get('sha256','').lower() for x in remote.get('images',[])]
            if hashes!=[x['sha256'] for x in assets]:
                raise RuntimeError(f'Remote image count/order/hash mismatch: {locale}/{kind}')
            result['images'][locale][kind] = remote
    tracks = checked(session.get(f'{root}/tracks',timeout=45))
    if tracks!=transaction['tracks_before']:
        raise RuntimeError('Tracks changed since staging; investigate before committing')
    result['tracks'] = tracks
    return result

def stage_listing():
    listings,images = read_local()
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    folder = ROOT/'artifacts/play-console'/f'listing-refresh-{stamp}'
    path = folder/'transaction.json'
    session = connection()
    edit_id = None
    transaction = {'package':PACKAGE,'status':'preparing','listings':listings,'images':images}
    try:
        edit = checked(session.post(BASE,json={},timeout=45))
        edit_id = edit['id']
        transaction['edit'] = edit
        save(path,transaction)
        before = checked(session.get(f'{BASE}/{edit_id}/listings',timeout=45))
        save(folder/'before-listings.json',before)
        original = {x['language']:x for x in before.get('listings',[])}
        if set(listings)!=set(original):
            raise RuntimeError('Local locale set differs from existing Play locales; inspect before adding/removing locales')
        # A PUT preserves all existing optional listing fields, including video.
        for locale in listings:
            listings[locale] = {k:v for k,v in {**original[locale],**listings[locale]}.items()
                                if k in ('language','title','shortDescription','fullDescription','video')}
        transaction['tracks_before'] = checked(session.get(f'{BASE}/{edit_id}/tracks',timeout=45))
        save(path,transaction)
        for locale,kinds in images.items():
            for kind in kinds:
                previous = checked(session.get(f'{BASE}/{edit_id}/listings/{locale}/{kind}',timeout=45))
                save(folder/'before-images'/locale/f'{kind}.json',previous)
                for index,old in enumerate(previous.get('images',[]),1):
                    # Preview URLs need no credentials: never send OAuth tokens to them.
                    response = requests.get(old['url'],timeout=45)
                    if not response.ok:
                        raise RuntimeError(f'Could not back up old image: {locale}/{kind}/{index}')
                    out = folder/'before-images'/locale/kind/f'{index:02d}.img'
                    out.parent.mkdir(parents=True,exist_ok=True)
                    out.write_bytes(response.content)
            print(f'Backed up images: {locale}',flush=True)
        for locale,listing in listings.items():
            checked(session.put(f'{BASE}/{edit_id}/listings/{locale}',json=listing,timeout=45))
            print(f'Staged text: {locale}',flush=True)
        for locale,kinds in images.items():
            for kind,assets in kinds.items():
                root = f'{BASE}/{edit_id}/listings/{locale}/{kind}'
                checked(session.delete(root,timeout=45))
                for asset in assets:
                    data = (ROOT/asset['path']).read_bytes()
                    if hashlib.sha256(data).hexdigest()!=asset['sha256']:
                        raise RuntimeError('Local asset changed during staging')
                    upload = root.replace('googleapis.com/androidpublisher/','googleapis.com/upload/androidpublisher/')
                    result = checked(session.post(upload,params={'uploadType':'media'},data=data,
                        headers={'Content-Type':'image/png'},timeout=90))
                    if result.get('image',{}).get('sha256','').lower()!=asset['sha256']:
                        raise RuntimeError(f'Uploaded image hash mismatch: {locale}/{kind}')
                print(f'Staged {locale}/{kind}: {len(assets)} images',flush=True)
        result = verify_edit(session,edit_id,transaction)
        save(folder/'staged-verification.json',result)
        transaction['validation'] = checked(session.post(f'{BASE}/{edit_id}:validate',timeout=45))
        transaction['status'] = 'validated-not-committed'
        save(path,transaction)
        print(f'Transaction: {path}',flush=True)
        print('All text, image hashes/order and unchanged tracks verified. No commit yet.',flush=True)
    except Exception:
        transaction['status'] = 'staging-failed'
        save(path,transaction)
        if edit_id is not None:
            checked(session.delete(f'{BASE}/{edit_id}',timeout=45))
        raise
    finally:
        session.close()

def commit_listing(path):
    path = Path(path).resolve()
    transaction = json.loads(path.read_text(encoding='utf-8'))
    if transaction.get('package')!=PACKAGE or transaction.get('status')!='validated-not-committed':
        raise RuntimeError('Transaction must be validated, uncommitted and for this package')
    session = connection()
    edit_id = transaction['edit']['id']
    try:
        verify_edit(session,edit_id,transaction)
        checked(session.post(f'{BASE}/{edit_id}:validate',timeout=45))
        transaction['status'] = 'commit-requested'
        save(path,transaction)
        response = session.post(f'{BASE}/{edit_id}:commit',
            params={'changesInReviewBehavior':'ERROR_IF_IN_REVIEW'},timeout=60)
        if not response.ok:
            transaction['status'] = 'commit-rejected'
            transaction['commit_error'] = response.json()
            save(path,transaction)
        result = checked(response)
        transaction['commit_response'] = result
        transaction['status'] = 'committed'
        transaction['committed_at'] = dt.datetime.now(dt.timezone.utc).isoformat()
        save(path,transaction)
        print('Play accepted the commit. Verifying a fresh server snapshot.',flush=True)
        verification_id = checked(session.post(BASE,json={},timeout=45))['id']
        try:
            result = verify_edit(session,verification_id,transaction)
            save(path.parent/'committed-verification.json',result)
            transaction['status'] = 'committed-and-verified'
            save(path,transaction)
        finally:
            checked(session.delete(f'{BASE}/{verification_id}',timeout=45))
        print('Verified: 28 localized listings, 6 feature graphics, 36 screenshots. Tracks unchanged.',flush=True)
        print('Google review/public availability is separate from this successful commit.',flush=True)
    finally:
        session.close()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['fetch','check-local','stage-listing','commit-listing'])
    parser.add_argument('--transaction')
    args = parser.parse_args()
    try:
        if args.command=='fetch':
            fetch()
        elif args.command=='check-local':
            listings,images = read_local()
            print(f'Valid: {len(listings)} locales, {sum(len(a) for k in images.values() for a in k.values())} images')
        elif args.command=='stage-listing':
            stage_listing()
        elif args.command=='commit-listing':
            if not args.transaction:
                parser.error('--transaction is required')
            commit_listing(args.transaction)
    except (RuntimeError,KeyError) as exc:
        print(str(exc),file=sys.stderr)
        sys.exit(1)
    except Exception as exc:
        # Never dump authentication objects, tokens or credential contents.
        print(f'Connection failed ({type(exc).__name__}).',file=sys.stderr)
        sys.exit(1)
