"""Stage and commit a signed AAB to an existing custom closed-testing track.

Uses PLAY_STORE_JSON_KEY outside the repository. Never publishes to production.
Stage: python tools/play_release.py stage --aab <file> --code 6 --name 0.4.2 --track Test
Commit: python tools/play_release.py commit --transaction <transaction.json>
"""
import argparse
import datetime as dt
import hashlib
import json
from pathlib import Path
import sys
from urllib.parse import quote, urlsplit
import requests
from play_store import BASE, PACKAGE, ROOT, META, connection, checked, save

def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source,'sha256').hexdigest()

def resumable_upload(session, url, aab):
    total = aab.stat().st_size
    response = session.post(url,params={'uploadType':'resumable'},data=b'',headers={
        'Content-Length':'0','X-Upload-Content-Type':'application/octet-stream',
        'X-Upload-Content-Length':str(total)},timeout=60)
    checked(response)
    location = response.headers['Location']
    parsed = urlsplit(location)
    if parsed.scheme!='https' or parsed.hostname not in ('androidpublisher.googleapis.com','www.googleapis.com'):
        raise RuntimeError('Unexpected resumable upload host')
    # The temporary session URL remains in memory only, never in logs or repository artifacts.
    offset = 0
    retries = 0
    with aab.open('rb') as source:
        while True:
            source.seek(offset)
            chunk = source.read(8*1024*1024)
            headers = {'Content-Type':'application/octet-stream',
                       'Content-Range':f'bytes {offset}-{offset+len(chunk)-1}/{total}'}
            try:
                response = session.put(location,data=chunk,headers=headers,timeout=(30,600),allow_redirects=False)
            except (requests.exceptions.Timeout,requests.exceptions.ConnectionError):
                retries += 1
                if retries>3:
                    raise RuntimeError('Resumable upload failed after three status-recovery attempts')
                print('Upload interrupted; querying the acknowledged byte range before resuming.',flush=True)
                response = session.put(location,data=b'',headers={'Content-Length':'0',
                    'Content-Range':f'bytes */{total}'},timeout=(30,120),allow_redirects=False)
            if response.status_code in (200,201):
                return checked(response)
            if response.status_code!=308:
                checked(response)
                raise RuntimeError(f'Unexpected upload status: {response.status_code}')
            last = response.headers.get('Range')
            next_offset = int(last.rsplit('-',1)[1])+1 if last else 0
            if next_offset<offset or next_offset>total:
                raise RuntimeError('Unexpected acknowledged byte range')
            if next_offset==offset:
                retries += 1
                if retries>3:
                    raise RuntimeError('Upload made no progress after three attempts')
            else:
                retries = 0
            offset = next_offset
            print(f'Google received {offset}/{total} bytes ({100*offset/total:.0f}%).',flush=True)
            if offset==total:
                raise RuntimeError('Server acknowledged all bytes without a final bundle result; inspect before retrying')

def snapshot(session, edit):
    return {key:checked(session.get(f'{BASE}/{edit}/{key}',timeout=45))
            for key in ('tracks','bundles','listings')}

def tracks_by_name(snapshot_data):
    return {t['track']:t for t in snapshot_data['tracks'].get('tracks',[])}

def verify(session, transaction, edit):
    data = snapshot(session,edit)
    tracks = tracks_by_name(data)
    expected = transaction['desired_track']
    if tracks.get(transaction['track'])!=expected:
        raise RuntimeError('Closed track read-back differs from the intended release')
    before = tracks_by_name(transaction['before'])
    others = lambda value: {k:v for k,v in value.items() if k!=transaction['track']}
    if others(tracks)!=others(before):
        raise RuntimeError('Another track changed; investigate before proceeding')
    if data['listings']!=transaction['before']['listings']:
        raise RuntimeError('Store listing changed; investigate before proceeding')
    bundle = next((b for b in data['bundles'].get('bundles',[]) if b['versionCode']==transaction['code']),None)
    if not bundle or bundle.get('sha256','').lower()!=transaction['sha256']:
        raise RuntimeError('Uploaded AAB version/hash does not match the local release')
    return data

def stage(args):
    if args.track.lower() in ('production','beta','internal','qa') or ':' in args.track:
        raise RuntimeError('Only an existing custom closed-testing track is allowed')
    aab = Path(args.aab).resolve()
    if not aab.is_file() or aab.suffix.lower()!='.aab':
        raise RuntimeError('An existing signed AAB is required')
    notes = []
    for language in ('en-US','es-ES','fr-FR','de-DE','it-IT','pt-BR'):
        text = (META/language/'changelogs'/f'{args.code}.txt').read_text(encoding='utf-8').strip()
        if not 0<len(text)<=500:
            raise RuntimeError(f'Invalid release note length: {language}')
        notes.append({'language':language,'text':text})
    stamp = dt.datetime.now(dt.timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')
    path = ROOT/'artifacts/play-console'/f'release-{args.code}-{stamp}'/'transaction.json'
    tx = {'package':PACKAGE,'track':args.track,'code':args.code,'name':args.name,
          'aab':str(aab),'sha256':digest(aab),'status':'preparing'}
    session = connection()
    edit = None
    try:
        tx['edit'] = checked(session.post(BASE,json={},timeout=45))
        edit = tx['edit']['id']
        tx['before'] = snapshot(session,edit)
        save(path,tx)
        tracks = tracks_by_name(tx['before'])
        if args.track not in tracks or not tracks[args.track].get('releases'):
            raise RuntimeError('Requested track is not an existing active closed test')
        if any(r['status']!='completed' for r in tracks[args.track]['releases']):
            raise RuntimeError('Track has a draft/staged release; inspect before replacing it')
        codes = [b['versionCode'] for b in tx['before']['bundles'].get('bundles',[])]
        codes += [int(c) for t in tracks.values() for r in t.get('releases',[]) for c in r.get('versionCodes',[])]
        if args.code<=max(codes,default=0):
            raise RuntimeError('Version code is not higher than all existing bundles/releases')
        print(f'Uploading {aab.stat().st_size} bytes to closed track {args.track}; SHA256 {tx["sha256"]}',flush=True)
        url = f'{BASE}/{edit}/bundles'.replace('googleapis.com/androidpublisher/','googleapis.com/upload/androidpublisher/')
        tx['upload'] = resumable_upload(session,url,aab)
        save(path,tx)
        if tx['upload'].get('versionCode')!=args.code or tx['upload'].get('sha256','').lower()!=tx['sha256']:
            raise RuntimeError('Uploaded bundle version/hash mismatch')
        print('Google accepted the bundle; staging the closed-test release.',flush=True)
        tx['desired_track'] = {'track':args.track,'releases':[{'name':f'{args.code} ({args.name})',
            'versionCodes':[str(args.code)],'releaseNotes':notes,'status':'completed'}]}
        checked(session.put(f'{BASE}/{edit}/tracks/{quote(args.track,safe="")}',json=tx['desired_track'],timeout=45))
        save(path.parent/'staged-verification.json',verify(session,tx,edit))
        tx['validation'] = checked(session.post(f'{BASE}/{edit}:validate',timeout=60))
        tx['status'] = 'validated-not-committed'
        save(path,tx)
        print(f'Transaction: {path}',flush=True)
    except Exception:
        tx['status'] = 'staging-failed'
        save(path,tx)
        if edit:
            checked(session.delete(f'{BASE}/{edit}',timeout=45))
        raise
    finally:
        session.close()

def commit(args):
    path = Path(args.transaction).resolve()
    tx = json.loads(path.read_text(encoding='utf-8'))
    if tx.get('package')!=PACKAGE or tx.get('status')!='validated-not-committed':
        raise RuntimeError('Expected a validated, uncommitted transaction for this application')
    if tx['track'].lower() in ('production','beta','internal','qa') or ':' in tx['track']:
        raise RuntimeError('Only a custom closed-testing track is allowed')
    edit = tx['edit']['id']
    session = connection()
    try:
        verify(session,tx,edit)
        checked(session.post(f'{BASE}/{edit}:validate',timeout=60))
        tx['status'] = 'commit-requested'
        save(path,tx)
        response = session.post(f'{BASE}/{edit}:commit',params={'changesInReviewBehavior':'ERROR_IF_IN_REVIEW'},timeout=60)
        if not response.ok:
            tx['status'] = 'commit-rejected'
            tx['commit_error'] = response.json()
            save(path,tx)
        tx['commit_response'] = checked(response)
        tx['status'] = 'committed'
        tx['committed_at'] = dt.datetime.now(dt.timezone.utc).isoformat()
        save(path,tx)
        print('Commit accepted. Checking a new server snapshot.',flush=True)
        verification = checked(session.post(BASE,json={},timeout=45))['id']
        try:
            save(path.parent/'committed-verification.json',verify(session,tx,verification))
            tx['status'] = 'committed-and-verified'
            save(path,tx)
        finally:
            checked(session.delete(f'{BASE}/{verification}',timeout=45))
        print(f'Verified {tx["name"]} ({tx["code"]}) in {tx["track"]}. Other tracks and listings unchanged.',flush=True)
    finally:
        session.close()

if __name__=='__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command',required=True)
    prepare = commands.add_parser('stage')
    prepare.add_argument('--aab',required=True)
    prepare.add_argument('--code',required=True,type=int)
    prepare.add_argument('--name',required=True)
    prepare.add_argument('--track',required=True)
    publish = commands.add_parser('commit')
    publish.add_argument('--transaction',required=True)
    args = parser.parse_args()
    try:
        stage(args) if args.command=='stage' else commit(args)
    except Exception as exc:
        print(str(exc) if isinstance(exc,RuntimeError) else f'Operation failed ({type(exc).__name__}); inspect transaction before retrying.',file=sys.stderr)
        sys.exit(1)
