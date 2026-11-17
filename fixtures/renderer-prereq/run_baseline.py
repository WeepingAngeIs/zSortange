"""CI-only disposable direct-world baseline. CE is deliberately absent and never downloaded here."""
from __future__ import annotations
import hashlib,json,os,pathlib,platform,shutil,signal,struct,subprocess,time,urllib.request,urllib.parse,zlib
ROOT=pathlib.Path.cwd().resolve();F=ROOT/'fixtures/renderer-prereq';B=ROOT/'build/renderer-prereq';E=B/'evidence';C=B/'control';S=B/'server';D=B/'client'
SPEC=json.loads((F/'inputs.json').read_text())

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def bounded_download(url,limit):
    with urllib.request.urlopen(url,timeout=30) as r:
        if urllib.parse.urlsplit(r.url).hostname!='maven.neoforged.net':raise RuntimeError('Unapproved installer redirect')
        data=r.read(limit+1)
    if len(data)>limit:raise RuntimeError('Download bound')
    return data

def png_rgb(path):
    b=path.read_bytes();assert b[:8]==b'\x89PNG\r\n\x1a\n';p=8;chunks=[]
    while p<len(b):
        size=struct.unpack('>I',b[p:p+4])[0];typ=b[p+4:p+8];part=b[p+8:p+8+size];p+=12+size
        if typ==b'IHDR':w,h,bit,col,_,_,inter=struct.unpack('>IIBBBBB',part)
        if typ==b'IDAT':chunks.append(part)
        if typ==b'IEND':break
    assert bit==8 and col in (2,6) and inter==0 and w<=1920 and h<=1200
    channels=3 if col==2 else 4;stride=w*channels
    dec=zlib.decompressobj();raw=dec.decompress(b''.join(chunks),h*(stride+1)+1);assert len(raw)==h*(stride+1)
    prior=bytearray(stride);rows=[];i=0
    for _ in range(h):
        mode=raw[i];i+=1;row=bytearray(raw[i:i+stride]);i+=stride
        for x in range(stride):
            a=row[x-channels] if x>=channels else 0;b=prior[x];c=prior[x-channels] if x>=channels else 0
            if mode==0:v=0
            elif mode==1:v=a
            elif mode==2:v=b
            elif mode==3:v=(a+b)//2
            elif mode==4:
                q=a+b-c;ds=[abs(q-a),abs(q-b),abs(q-c)];v=(a,b,c)[ds.index(min(ds))]
            else:raise RuntimeError('PNG filter unsupported')
            row[x]=(row[x]+v)&255
        rows.append(row);prior=row
    sums=[0,0,0];n=0
    for y in range(h*3//8,h*5//8):
        for x in range(w*3//8,w*5//8):
            for k in range(3):sums[k]+=rows[y][x*channels+k]
            n+=1
    return {'width':w,'height':h,'center_mean_rgb':[s/n for s in sums],'sha256':sha(path)}

def group_rss(pgid):
    total=0
    for d in pathlib.Path('/proc').iterdir():
        if not d.name.isdecimal():continue
        try:
            if os.getpgid(int(d.name))!=pgid:continue
            row=next((v for v in (d/'status').read_text().splitlines() if v.startswith('VmRSS:')),None)
            if row:total+=int(row.split()[1])*1024
        except (FileNotFoundError,ProcessLookupError,PermissionError):pass
    return total

def main():
    assert os.environ.get('GITHUB_ACTIONS')=='true','Disposable GitHub only'
    assert (B/'provenance/STATIC_VERIFIED').is_file(),'No execution before publisher and target hash validation'
    assert SPEC['ce']['execution_approved'] is False,'CE authorization must be separately handled; not this lane'
    S.mkdir(exist_ok=False);C.mkdir(exist_ok=False);D.mkdir(exist_ok=True);E.mkdir(exist_ok=True)
    (C/'.disposable-renderer-prereq').write_text('new generated control, no user data\n')
    if (D/'saves').exists():raise RuntimeError('Refusing existing client saves')
    jar=ROOT/'build/libs/anya-storage-plus-0.3.0-beta.6.jar';safety=next((ROOT/'build/operator').glob('*.jar'));fixture=next(B.glob('anya-renderer-prerequisite-fixture-*.jar'))
    assert sha(jar)==SPEC['anya_sha256'] and sha(safety)==SPEC['safety_sha256'],'Existing binaries changed'
    mods=S/'mods';mods.mkdir();verified=B/'provenance/verified-jars';cloth=verified/'cloth-config-15.0.140-neoforge.jar'
    for p in (jar,safety,fixture,cloth):shutil.copy2(p,mods/p.name)
    client_mods=D/'mods';client_mods.mkdir(exist_ok=False)
    for a in SPEC['artifacts']:
        p=verified/a['filename'];assert sha(p)==a['sha256'],'Artifact changed after audit';shutil.copy2(p,client_mods/p.name)
    (E/'input-hashes.json').write_text(json.dumps({str(p.relative_to(ROOT)):sha(p) for p in (jar,safety,fixture,*sorted(verified.glob('*.jar')))},indent=2))
    (E/'host.json').write_text(json.dumps({'platform':platform.uname()._asdict(),'logical_cpus':os.cpu_count(),'java':subprocess.run(['java','-version'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True).stdout,'server_heap_mib':2048,'client_heap_mib':1000,'rss_accounting':'sum of /proc VmRSS for each complete process group; shared pages not deduplicated','ce_executed':False},indent=2))
    installer=B/'neoforge-installer.jar';url='https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar'
    data=bounded_download(url,16*1024*1024);published=bounded_download(url+'.sha256',512).decode().split()[0].lower()
    assert hashlib.sha256(data).hexdigest()==published,'Official NeoForge installer digest mismatch';installer.write_bytes(data)
    (E/'installer-provenance.json').write_text(json.dumps({'url':url,'sha256':published,'bytes':len(data)},indent=2))
    with (E/'installer.log').open('w') as log:subprocess.run(['java','-Xmx1000m','-jar',str(installer),'--installServer',str(S)],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=240)
    (S/'eula.txt').write_text('eula=true\n')
    (S/'server.properties').write_text('level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nonline-mode=false\nview-distance=6\nsimulation-distance=5\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25579\nallow-flight=true\nmax-tick-time=60000\n')
    opts={**SPEC['profile_options'],'pauseOnLostFocus':False,'guiScale':2}
    (D/'options.txt').write_text(''.join(f'{k}:{str(v).lower()}\n' for k,v in opts.items()))
    cfg=D/'config';cfg.mkdir(exist_ok=True)
    (cfg/'iris.properties').write_text(''.join(f'{k}={str(v).lower() if isinstance(v,bool) else v}\n' for k,v in SPEC['iris_options'].items()))
    (E/'config-boundaries.json').write_text(json.dumps({'iris':SPEC['iris_options'],'vanilla':opts,'sodium_options':'fresh defaults, captured after launch; exact user JSON not supplied','shader_zip':'not installed','ce':'not installed','user_profile':'never accessed'},indent=2))
    proc=[];logs=[];started=time.monotonic();success=False
    def launch(cmd,cwd,name,env=None):
        (E/(name+'-command.json')).write_text(json.dumps({'command':cmd,'cwd':str(cwd)},indent=2))
        log=(E/(name+'.log')).open('w');logs.append(log);p=subprocess.Popen(cmd,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);proc.append((p,name));return p
    resources=[]
    def check(expected_exit=False):
        if (C/'FAILED').exists():raise RuntimeError((C/'FAILED').read_text())
        elapsed=time.monotonic()-started
        if elapsed>480:raise TimeoutError('480-second total runtime bound')
        available=int(next(x for x in pathlib.Path('/proc/meminfo').read_text().splitlines() if x.startswith('MemAvailable:')).split()[1])*1024
        values={n:group_rss(p.pid) for p,n in proc};resources.append({'seconds':elapsed,'rss_bytes':values,'host_available_bytes':available})
        if values.get('server',0)>3*1024**3 or sum(values.values())>6*1024**3 or available<2*1024**3:raise RuntimeError('Resource guard')
        for p,n in proc:
            if p.poll() is not None and not expected_exit and not (C/'client-finished').exists():raise RuntimeError(f'{n} exited {p.returncode} before completion')
    try:
        flags=['-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true','-Danya.serializationBudget=true','-Danya.backpressure=true','-Danya.repairJournal=true',f'-Danya.rendererControl={C}',f'-Danya.control={C}']
        srv=launch(['java','-Xms256m','-Xmx2048m',*flags,'@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui'],S,'server')
        deadline=time.monotonic()+150
        while not (C/'server-ready').exists():
            check();assert time.monotonic()<deadline,'Server startup deadline';time.sleep(.5)
        launch_json=json.loads((B/'client-launch.json').read_text());native=B/'native';native.mkdir(exist_ok=False)
        env=os.environ.copy();env['JAVA_TOOL_OPTIONS']=f'-Djava.io.tmpdir={native} -Dorg.lwjgl.system.SharedLibraryExtractPath={native}/lwjgl'
        cli=launch(launch_json['command'],launch_json['cwd'],'client',env)
        while not (C/'client-finished').exists():check();time.sleep(.5)
        assert cli.wait(timeout=30)==0;assert srv.wait(timeout=60)==0;check(True)
        images=D/'screenshots';results={}
        for color in ('red','green'):
            p=images/(color+'.png');assert p.is_file(),'Framebuffer file missing';results[color]=png_rgb(p)
        red=results['red']['center_mean_rgb'];green=results['green']['center_mean_rgb']
        assert red[0]>red[1]*1.2 and green[1]>green[0]*1.15, f'Pixel controls failed: {results}'
        actual=json.loads((C/'runtime-mods.json').read_text());by={m['id']:m for m in actual}
        for a in SPEC['artifacts']:assert by[a['mod_id']]['version']==a['mod_version'],f'Loaded version differs: {a["mod_id"]}'
        resolved=[m for m in actual if m['id'].replace('-','_')=='fabric_renderer_api_v1'];assert len(resolved)==1
        (E/'runtime-result.json').write_text(json.dumps({'status':'PASS_BASELINE_ONLY','ce_live_frame':'NOT_RUN_PENDING_APPROVAL','framebuffer':results,'resolved_renderer_api':resolved,'not_full_profile':True},indent=2))
        success=True;print('ANYA_RENDERER_PREREQUISITE_BASELINE_PASSED',json.dumps(resolved),flush=True)
    finally:
        for p,n in proc:
            if p.poll() is None:os.killpg(p.pid,signal.SIGTERM)
        for p,n in proc:
            try:p.wait(timeout=10)
            except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)
        for h in logs:h.close()
        (E/'resources.json').write_text(json.dumps(resources,indent=2))
        if C.exists():shutil.copytree(C,E/'control',dirs_exist_ok=True)
        if (D/'screenshots').exists():shutil.copytree(D/'screenshots',E/'screenshots',dirs_exist_ok=True)
        if (D/'logs').exists():shutil.copytree(D/'logs',E/'client-logs',dirs_exist_ok=True)
        if (S/'logs').exists():shutil.copytree(S/'logs',E/'server-logs',dirs_exist_ok=True)
        for p in cfg.glob('*'):
            if p.name in ('sodium-options.json','iris.properties'):shutil.copy2(p,E/p.name)
        print('CE_EXECUTED=false BASELINE_SUCCESS='+str(success),flush=True)

if __name__=='__main__':main()
