"""Cold-home/fallback acceptance on CI only. Never accepts a supplied world/profile.
Two real client JVMs; each reconnect gets a private LWJGL extraction directory.
"""
from pathlib import Path
import hashlib,json,os,platform,shutil,signal,subprocess,time,urllib.request
ROOT=Path.cwd().resolve();BASE=ROOT/'build/cold-recovery';OUT=BASE/'evidence';LAUNCH=ROOT/'build/acceptance'
EXPECTED='5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11'
assert os.environ.get('GITHUB_ACTIONS')=='true','Disposable GitHub runner required'
OUT.mkdir(parents=True,exist_ok=True)
JAR=ROOT/'build/libs/anya-storage-plus-0.3.0-beta.6.jar';assert hashlib.sha256(JAR.read_bytes()).hexdigest()==EXPECTED
FIXTURE=next(LAUNCH.glob('anya-storage-acceptance-fixture-*.jar'));EXPERIMENT=next((ROOT/'build/ticket-experiment').glob('*.jar'))
INPUTS=[JAR,FIXTURE,EXPERIMENT,*sorted((LAUNCH/'dependencies').glob('*.jar'))]
(OUT/'runtime-inputs.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in INPUTS},indent=2))
def output(cmd):return subprocess.run(cmd,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True).stdout
def mem():return {k:int(v.split()[0])*1024 for k,v in (l.split(':',1) for l in Path('/proc/meminfo').read_text().splitlines())}
candidate=os.environ.get('ANYA_COLD_POLICY','baseline')=='candidate'
(OUT/'host.json').write_text(json.dumps({'uname':platform.uname()._asdict(),'lscpu':output(['lscpu']),'java':output(['java','-version']),'memory':mem(),'runner':{k:os.environ.get(k) for k in ['ImageOS','ImageVersion','GITHUB_SHA','GITHUB_RUN_ID']},'heap_mib':2048,'view_distance':3,'simulation_distance':2,'actual_clients':2,'candidate':candidate},indent=2))
WORLD=BASE/'server';assert not WORLD.exists(),'Never reuse a supplied server directory';WORLD.mkdir()
installer=BASE/'neoforge-installer.jar';urllib.request.urlretrieve('https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar',installer)
with (OUT/'installer.log').open('w') as log:subprocess.run(['java','-Xmx768m','-jar',str(installer),'--installServer',str(WORLD)],stdout=log,stderr=subprocess.STDOUT,timeout=240,check=True)
(WORLD/'mods').mkdir(exist_ok=True)
for p in INPUTS:shutil.copy2(p,WORLD/'mods'/p.name)
(WORLD/'.anya-cold-fixture').write_text('New empty disposable directory created by cold_recovery_acceptance.py\n')
(WORLD/'eula.txt').write_text('eula=true\n')
props='level-name=world\nlevel-type=minecraft:flat\nlevel-seed=860612\ngenerate-structures=false\nonline-mode=false\nview-distance=3\nsimulation-distance=2\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25577\nallow-flight=true\nmax-tick-time=120000\n'
(WORLD/'server.properties').write_text(props);(OUT/'server.properties').write_text(props)
CONTROL=LAUNCH/'control';assert not CONTROL.exists()
def terminate(p):
    if p.poll() is None:
        os.killpg(p.pid,signal.SIGTERM)
        try:p.wait(timeout=15)
        except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)
for leg,marker in [('seed','UNSAFE_STOP_ARMED'),('resume','PASSED')]:
    out=OUT/leg;out.mkdir();ctrl=out/'control';ctrl.mkdir()
    if CONTROL.is_symlink():CONTROL.unlink()
    assert not CONTROL.exists();CONTROL.symlink_to(ctrl,target_is_directory=True)
    processes=[];handles=[];expected_dead=set();labels={}
    def launch(cmd,cwd,label,env=None):
        h=(out/(label+'.log')).open('w');handles.append(h)
        p=subprocess.Popen(cmd,cwd=cwd,stdout=h,stderr=subprocess.STDOUT,env=env,start_new_session=True);processes.append(p);labels[p.pid]=label;return p
    def failed():
        fs=list(ctrl.glob('FAILED*'))
        if fs:raise RuntimeError('\n'.join(p.name+': '+p.read_text() for p in fs))
    def client(role,suffix=''):
        cfg=json.loads((LAUNCH/f'client-{role}.json').read_text());gd=Path(cfg['cwd']);gd.mkdir(exist_ok=True)
        (gd/'options.txt').write_text('enableVsync:false\nmaxFps:25\nrenderDistance:3\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\n')
        native=BASE/'native'/leg/(role+(suffix or '-initial'));native.mkdir(parents=True,exist_ok=False);tmp=native/'tmp';tmp.mkdir()
        env=os.environ.copy();env['JAVA_TOOL_OPTIONS']=(env.get('JAVA_TOOL_OPTIONS','')+f' -Djava.io.tmpdir={tmp} -Dorg.lwjgl.system.SharedLibraryExtractPath={native} -Dorg.lwjgl.util.DebugLoader=true').strip()
        return launch(cfg['command'],gd,'client-'+role+suffix,env)
    cmd=['java','-Xms256m','-Xmx2048m','-XX:+UseG1GC','-XX:ActiveProcessorCount=2','-Danya.acceptance=true','-Danyastorage.pocketPrototype=true','-Danya.coldRecoveryAcceptance=true',f'-Danya.remoteTickets={str(candidate).lower()}',f'-Danya.coldResume={str(leg=="resume").lower()}',f'-Danya.control={CONTROL}',f'-Danya.expectedJar={JAR}','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui']
    (out/'command.json').write_text(json.dumps(cmd,indent=2))
    try:
        srv=launch(cmd,WORLD,'server');deadline=time.monotonic()+180
        while not (ctrl/'server-ready').exists():
            failed();assert srv.poll() is None,'Server exited before ready'
            if time.monotonic()>deadline:raise TimeoutError('Cold fixture startup/drain')
            time.sleep(1)
        alpha=client('Alpha');beta=client('Beta');killed=set();restarted=set();start=time.monotonic();deadline=start+720
        with (out/'rss.csv').open('w') as rss:
            rss.write('elapsed_s,server_rss_bytes,process_group_rss_bytes,host_available_bytes\n')
            while srv.poll() is None:
                failed()
                if leg=='seed':
                    for cycle in (1,2,3):
                        if (ctrl/f'kill-alpha-{cycle}').exists() and cycle not in killed:
                            assert alpha.poll() is None,'Alpha died before scheduled socket interruption'
                            expected_dead.add(alpha.pid);os.killpg(alpha.pid,signal.SIGKILL);alpha.wait(timeout=15);killed.add(cycle)
                            (out/f'socket-kill-{cycle}.txt').write_text('SIGKILL actual visiting Alpha client process group. No API logout substitute.\n')
                        if cycle in killed and cycle not in restarted and (ctrl/f'restart-alpha-{cycle}').exists():
                            alpha=client('Alpha',f'-reconnect-{cycle}');restarted.add(cycle)
                for p in processes:
                    if p is not srv and p.poll() is not None and p.pid not in expected_dead:raise RuntimeError(f'Unexpected client exit: {labels[p.pid]} code={p.returncode}')
                available=mem()['MemAvailable'];rows=[r.split() for r in output(['ps','-e','-o','pid=,pgid=,rss=']).splitlines()];groups={p.pid for p in processes if p.poll() is None}
                total=sum(int(r[2])*1024 for r in rows if int(r[1]) in groups);srss=sum(int(r[2])*1024 for r in rows if int(r[1])==srv.pid)
                rss.write(f'{time.monotonic()-start:.3f},{srss},{total},{available}\n');rss.flush()
                if available<2*1024**3 or total>6*1024**3 or srss>3*1024**3:raise RuntimeError('Protective resource cutoff')
                if time.monotonic()>deadline:raise TimeoutError('Bounded cold acceptance deadline')
                time.sleep(1)
        failed();assert srv.returncode==0 and (ctrl/marker).exists(),f'{leg}: exit={srv.returncode}, missing {marker}'
        assert (ctrl/'COLD_ROUTER_ACTIVE').exists(),'Wrong fixture ran'
        if leg=='seed':assert killed==restarted=={1,2,3},'Missing actual reconnect cycle'
        audit=(ctrl/'ticket-stop-audit.txt').read_text();assert 'after=0' in audit,'No actual ticket shutdown audit'
        print(f'ANYA_COLD_RECOVERY_LEG_OK {leg} candidate={candidate}',flush=True)
    finally:
        for p in processes:terminate(p)
        for h in handles:h.close()
        for role in ['Alpha','Beta']:
            gd=ROOT/f'run-packaged-client-{role}'
            for part in ['logs','screenshots']:
                if (gd/part).exists():shutil.copytree(gd/part,out/f'client-{role}-{part}',dirs_exist_ok=True)
        for part in ['logs','crash-reports']:
            if (WORLD/part).exists():shutil.copytree(WORLD/part,out/f'server-{part}',dirs_exist_ok=True)
        if (WORLD/'cold-identities.properties').exists():shutil.copy2(WORLD/'cold-identities.properties',out/'cold-identities.properties')
print('ANYA_COLD_RECOVERY_ACCEPTANCE_OK candidate='+str(candidate),flush=True)
