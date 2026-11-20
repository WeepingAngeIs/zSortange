"""Download publisher artifacts as data; verify exact target bytes before any execution.
No archive extraction, external subprocess, installer or imported mod code.
"""
from __future__ import annotations
import hashlib, io, json, os, pathlib, sys, tomllib, urllib.request, urllib.parse, zipfile
ROOT=pathlib.Path(__file__).resolve().parent
ALLOWED={'api.modrinth.com','cdn.modrinth.com'}
MAX=32*1024*1024
class Refusal(RuntimeError): pass

def url_ok(url:str)->bool:
    u=urllib.parse.urlsplit(url)
    return u.scheme=='https' and u.hostname in ALLOWED and not u.username and not u.password and u.port in (None,443)

class Redirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        if not url_ok(newurl): raise Refusal('Unapproved redirect host')
        return super().redirect_request(req,fp,code,msg,headers,newurl)

def get(url:str)->bytes:
    if not url_ok(url): raise Refusal('Unapproved URL '+url)
    req=urllib.request.Request(url,headers={'User-Agent':'AnyaStorage-renderer-prerequisite/1.0 (read-only exact-hash audit)'})
    with urllib.request.build_opener(Redirect()).open(req,timeout=30) as r:
        if not url_ok(r.url): raise Refusal('Unapproved final host')
        b=r.read(MAX+1)
    if len(b)>MAX: raise Refusal('Bounded response exceeded')
    return b

def check_bytes(data:bytes,spec:dict,published:dict)->dict:
    h={a:hashlib.new(a,data).hexdigest() for a in ('sha1','sha256','sha512')}
    if len(data)!=spec['bytes'] or h['sha256']!=spec['sha256'].lower():
        raise Refusal(f'TARGET_BYTES_MISMATCH {spec["filename"]}: bytes={len(data)} sha256={h["sha256"]}')
    for algo in ('sha1','sha512'):
        if published.get(algo)!=h[algo]: raise Refusal(f'Publisher {algo} mismatch')
    return h

def inventory(data:bytes,label:str,depth:int=0,seen=None)->list[dict]:
    if depth>5: raise Refusal('Nested archive depth bound')
    if seen is None: seen={'bytes':0,'entries':0}
    seen['bytes']+=len(data)
    if seen['bytes']>128*1024*1024: raise Refusal('Nested cumulative bytes bound')
    out=[]
    with zipfile.ZipFile(io.BytesIO(data)) as z:
        names=z.namelist()
        if len(names)!=len(set(names)) or len(names)>20000: raise Refusal('Duplicate or excessive ZIP members')
        if any(pathlib.PurePosixPath(n).is_absolute() or '..' in pathlib.PurePosixPath(n).parts for n in names): raise Refusal('Unsafe ZIP member')
        row={'archive':label,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest(),'mods':[],'jarjar':None,'service_providers':{}}
        for p in ('META-INF/neoforge.mods.toml','META-INF/mods.toml'):
            if p in names:
                meta=tomllib.loads(z.read(p).decode()); row['mods']+=meta.get('mods',[]);row.setdefault('dependencies',{}).update(meta.get('dependencies',{}))
        if 'fabric.mod.json' in names: row['fabric_metadata']=json.loads(z.read('fabric.mod.json'))
        if 'META-INF/jarjar/metadata.json' in names: row['jarjar']=json.loads(z.read('META-INF/jarjar/metadata.json'))
        for n in names:
            if n.startswith('META-INF/services/') and not n.endswith('/'):
                row['service_providers'][n]=z.read(n).decode('utf-8')
        out.append(row)
        # Preserve nested JARs as in-memory data only. No extraction or code loading.
        for info in z.infolist():
            if info.filename.endswith('.jar'):
                seen['entries']+=1
                if seen['entries']>128 or info.file_size>MAX: raise Refusal('Nested archive bound')
                out+=inventory(z.read(info),label+'!/'+info.filename,depth+1,seen)
    return out

def audit(out:pathlib.Path)->None:
    spec=json.loads((ROOT/'inputs.json').read_text());out.mkdir(parents=True,exist_ok=True)
    jars=out/'verified-jars';jars.mkdir(exist_ok=True)
    manifest={'schema':1,'status':'IN_PROGRESS','executed_artifacts':False,'inputs':[], 'nested_resolution':'NOT_EXECUTED'}
    try:
        for a in spec['artifacts']:
            raw=get('https://api.modrinth.com/v2/version/'+a['version_id']);(out/(a['project']+'-publisher-version.json')).write_bytes(raw);v=json.loads(raw)
            p_raw=get('https://api.modrinth.com/v2/project/'+a['project_id']);(out/(a['project']+'-publisher-project.json')).write_bytes(p_raw);p=json.loads(p_raw)
            if v['project_id']!=a['project_id'] or p['id']!=a['project_id'] or v['id']!=a['version_id'] or v['version_number']!=a['version']: raise Refusal('Publisher identity/version mismatch')
            if p.get('source_url','').rstrip('/').lower() not in [s.rstrip('/').lower() for s in a['source_repositories']]: raise Refusal('Publisher source repository needs review: '+str(p.get('source_url')))
            if 'neoforge' not in v['loaders'] or '1.21.1' not in v['game_versions']: raise Refusal('Wrong publisher loader/game version')
            fs=[f for f in v['files'] if f['filename']==a['filename']]
            if len(fs)!=1: raise Refusal('Expected one exact publisher file')
            f=fs[0];data=get(f['url']);h=check_bytes(data,a,f['hashes']);rows=inventory(data,a['filename'])
            for name,expected in spec['expected_nested'].items():
                matches=[r for r in rows if r['archive'].endswith('/'+name)]
                if a['project']=='sodium':
                    if len(matches)!=1 or any(matches[0][k]!=expected[k] for k in ('bytes','sha256')):raise Refusal('Nested Sodium bytes differ from target')
            (jars/a['filename']).write_bytes(data)
            manifest['inputs'].append({'project':a['project'],'version_id':v['id'],'version':v['version_number'],'publisher_source':p['source_url'],'download':f['url'],'filename':a['filename'],'bytes':len(data),'hashes':h,'dependencies':v['dependencies'],'archives':rows})
        manifest['status']='VERIFIED_STATIC_ONLY';(out/'STATIC_VERIFIED').write_text('Exact target bytes and publisher SHA1/SHA512 verified. No mod executed; nested candidates are not resolution.\n')
    except Exception as e:
        manifest['status']='REFUSED';manifest['error']=f'{type(e).__name__}: {e}';raise
    finally:(out/'provenance.json').write_text(json.dumps(manifest,indent=2)+'\n')

if __name__=='__main__':
    if os.environ.get('GITHUB_ACTIONS')!='true': raise SystemExit('Network audit runs only on the disposable GitHub runner; offline unit tests are separate')
    audit(pathlib.Path(sys.argv[1]))
