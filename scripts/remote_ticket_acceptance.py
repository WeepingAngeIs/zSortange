"""Counterbalanced packaged baseline/candidate runs on ephemeral CI; no installed profile accepted.
Each case uses a fresh world. Three server processes exercise active shutdown and a forced recovery cut.
"""
from pathlib import Path
import hashlib,json,os,platform,shutil,signal,subprocess,time,urllib.request
ROOT=Path.cwd().resolve(); BASE=ROOT/'build/remote-policy'; OUT=BASE/'evidence'; LAUNCH=ROOT/'build/acceptance'
EXPECTED='5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11'
assert os.environ.get('GITHUB_ACTIONS')=='true','Disposable GitHub infrastructure only'
OUT.mkdir(parents=True,exist_ok=True)
JAR=ROOT/'build/libs/anya-storage-plus-0.3.0-beta.6.jar'
assert hashlib.sha256(JAR.read_bytes()).hexdigest()==EXPECTED,'Do not change production during the experiment'
FIXTURE=next(LAUNCH.glob('anya-storage-acceptance-fixture-*.jar'))
EXPERIMENT=next((ROOT/'build/ticket-experiment').glob('*.jar'))
INPUTS=[JAR,FIXTURE,EXPERIMENT,*sorted((LAUNCH/'dependencies').glob('*.jar'))]
if os.environ.get('ANYA_DURABLE_PROBE')=='true':INPUTS.append(next((ROOT/'build/durable').glob('*.jar')))
(OUT/'runtime-inputs.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in INPUTS},indent=2))
def output(cmd): return subprocess.run(cmd,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True).stdout
def memory(): return {k:int(v.split()[0])*1024 for k,v in (l.split(':',1) for l in Path('/proc/meminfo').read_text().splitlines())}
(OUT/'host.json').write_text(json.dumps({'uname':platform.uname()._asdict(),'lscpu':output(['lscpu']),'java':output(['java','-version']),'initial_memory':memory(),'runner':{k:os.environ.get(k) for k in ['ImageOS','ImageVersion','GITHUB_SHA','GITHUB_RUN_ID']},'server_heap_mib':2048,'server_xms_mib':256,'server_active_processor_count':2,'client_heap_mib':1000,'view_distance':3,'simulation_distance':2,'real_client_jvms':2,'cold_definition':'new world/JVM per case, reused installed libraries; OS cache not cleared'},indent=2))
RUNTIME=BASE/'runtime';assert not RUNTIME.exists();RUNTIME.mkdir()
installer=BASE/'neoforge-installer.jar'
urllib.request.urlretrieve('https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar',installer)
with (OUT/'installer.log').open('w') as log:subprocess.run(['java','-Xmx768m','-jar',str(installer),'--installServer',str(RUNTIME)],stdout=log,stderr=subprocess.STDOUT,timeout=240,check=True)
control=LAUNCH/'control';assert not control.exists(),'Unexpected existing acceptance control directory'

def terminate(p):
    if p.poll() is None:
        os.killpg(p.pid,signal.SIGTERM)
        try:p.wait(timeout=15)
        except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)

def heartbeat_phase(path: Path) -> str:
    """Best-effort telemetry label; missing/partial data never skips resource or timeout checks."""
    try:
        value=path.read_text()
    except FileNotFoundError:
        return 'heartbeat-missing'
    parts=value.strip().split(',',1)
    if len(parts)!=2 or not parts[0].isdigit() or not parts[1] or any(c in parts[1] for c in ',\r\n'):
        return 'heartbeat-incomplete'
    return parts[1]

def run_case(name,candidate):
    case=BASE/name;assert not case.exists();case.mkdir();world=case/'server';world.mkdir();evidence=OUT/name;evidence.mkdir()
    shutil.copytree(RUNTIME/'libraries',world/'libraries',copy_function=os.link);(world/'mods').mkdir()
    for p in INPUTS:shutil.copy2(p,world/'mods'/p.name)
    (world/'.anya-remote-fixture').write_text('Created only by remote_ticket_acceptance.py on disposable CI\n')
    (world/'eula.txt').write_text('eula=true\n')
    props='level-name=world\nlevel-type=minecraft:flat\nlevel-seed=860612\ngenerate-structures=false\nonline-mode=false\nview-distance=3\nsimulation-distance=2\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25577\nallow-flight=true\nmax-tick-time=120000\n'
    (world/'server.properties').write_text(props);(evidence/'server.properties').write_text(props)
    for leg,expected_code,end_marker in [('seed',0,'ACTIVE_STOP_ARMED'),('after-stop',77,'RECOVERY_CUT'),('after-cut',0,'PASSED')]:
        out=evidence/leg;out.mkdir();ctrl=out/'control';ctrl.mkdir()
        if control.is_symlink():control.unlink()
        assert not control.exists();control.symlink_to(ctrl,target_is_directory=True)
        procs=[];logs=[];clients=[];planned_kills=set();native_launches=[]
        def launch(cmd,cwd,label,env=None):
            h=(out/(label+'.log')).open('w');logs.append(h);p=subprocess.Popen(cmd,cwd=cwd,env=env,stdout=h,stderr=subprocess.STDOUT,start_new_session=True);procs.append(p);return p
        def failed():
            files=list(ctrl.glob('FAILED*'))
            if files:raise RuntimeError('\n'.join(p.name+': '+p.read_text() for p in files))
        def client(role,suffix=''):
            cfg=json.loads((LAUNCH/f'client-{role}.json').read_text());gd=Path(cfg['cwd'])
            gd.mkdir(exist_ok=True);(gd/'options.txt').write_text('enableVsync:false\nmaxFps:25\nrenderDistance:3\nsimulationDistance:2\nguiScale:2\npauseOnLostFocus:false\n')
            # Remove only screenshots created by earlier legs of this same disposable harness.
            if (gd/'screenshots').exists():shutil.rmtree(gd/'screenshots')
            # Each case, leg and reconnect gets fresh native/temp directories, never a user's path.
            private=case/'client-native'/leg/(role+(suffix or '-initial'))
            private.mkdir(parents=True,exist_ok=False);tmp=private/'tmp';tmp.mkdir();native=private/'lwjgl';native.mkdir()
            options=f'-Djava.io.tmpdir={tmp} -Dorg.lwjgl.system.SharedLibraryExtractPath={native} -Dorg.lwjgl.util.DebugLoader=true'
            env=os.environ.copy();env['JAVA_TOOL_OPTIONS']=(env.get('JAVA_TOOL_OPTIONS','')+' '+options).strip()
            label='client-'+role+suffix
            native_launches.append({'role':role,'suffix':suffix,'tmp':str(tmp),'extraction':str(native),'options':options})
            (out/'client-native-launches.json').write_text(json.dumps(native_launches,indent=2))
            process=launch(cfg['command'],gd,label,env);clients.append((process,label));return process
        cmd=['java','-Xms256m','-Xmx2048m','-XX:+UseG1GC','-XX:ActiveProcessorCount=2','-Danya.acceptance=true','-Danyastorage.pocketPrototype=true','-Danya.remoteAcceptance=true',f'-Danya.remoteTickets={str(candidate).lower()}',f'-Danya.remoteLeg={leg}',f'-Danya.remoteCut={str(leg=="after-stop").lower()}',f'-Danya.control={control}',f'-Danya.expectedJar={JAR}','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui']
        (out/'command.json').write_text(json.dumps(cmd,indent=2))
        try:
            srv=launch(cmd,world,'server');deadline=time.monotonic()+150
            while not (ctrl/'server-ready').exists():
                failed();assert srv.poll() is None,'Server exited before ready'
                if time.monotonic()>deadline:raise TimeoutError('Server startup')
                time.sleep(1)
            alpha=client('Alpha');beta=client('Beta');killed=set();restarted=set();start=time.monotonic();deadline=start+600
            with (out/'rss.csv').open('w') as rss:
                rss.write('elapsed_s,phase,server_rss_bytes,total_process_groups_rss_bytes,host_available_bytes\n')
                while srv.poll() is None:
                    failed()
                    if leg=='seed':
                        for cycle in (1,2):
                            if (ctrl/f'kill-beta-{cycle}').exists() and cycle not in killed:
                                planned_kills.add(beta.pid);os.killpg(beta.pid,signal.SIGKILL);beta.wait(timeout=15);killed.add(cycle)
                                (out/f'socket-kill-{cycle}.txt').write_text('SIGKILL real Minecraft Beta process group; no API logout substitute\n')
                            if cycle in killed and cycle not in restarted and (ctrl/f'restart-beta-{cycle}').exists():
                                beta=client('Beta',f'-reconnect-{cycle}');restarted.add(cycle)
                    for process,label in clients:
                        code=process.poll()
                        if code is not None and process.pid not in planned_kills:
                            tail='\n'.join((out/(label+'.log')).read_text(errors='replace').splitlines()[-45:])
                            raise RuntimeError(f'Unexpected client exit: {label}, code={code}\n{tail}')
                    available=memory()['MemAvailable'];rows=[r.split() for r in output(['ps','-e','-o','pid=,pgid=,rss=']).splitlines()]
                    groups={p.pid for p in procs if p.poll() is None}
                    total=sum(int(r[2])*1024 for r in rows if int(r[1]) in groups);srss=sum(int(r[2])*1024 for r in rows if int(r[1])==srv.pid)
                    phase=heartbeat_phase(ctrl/'heartbeat')
                    rss.write(f'{time.monotonic()-start:.3f},{phase},{srss},{total},{available}\n');rss.flush()
                    if available<2*1024**3 or total>6*1024**3 or srss>3*1024**3:raise RuntimeError('Protective resource ceiling reached; stop before exhausting runner')
                    if time.monotonic()>deadline:raise TimeoutError('Network scenario ceiling')
                    time.sleep(1)
            failed();assert srv.returncode==expected_code,f'{leg}: expected {expected_code}, got {srv.returncode}'
            assert (ctrl/end_marker).exists(),f'Missing terminal marker {end_marker}'
            if leg=='seed':
                assert killed==restarted=={1,2},'Both actual socket interruptions required'
                audit=(ctrl/'ticket-stop-audit.txt').read_text()
                assert 'before=34 after=0' in audit,'Expected exact-rank removal with 34 active requests at server shutdown'
            elif leg=='after-cut':
                assert 'after=0' in (ctrl/'ticket-stop-audit.txt').read_text(),'Missing shutdown ticket audit'
            print(f'ANYA_REMOTE_POLICY_CASE {name} {leg} PASSED exit={srv.returncode}',flush=True)
        finally:
            for p in procs:terminate(p)
            for h in logs:h.close()
            for role in ['Alpha','Beta']:
                gd=ROOT/f'run-packaged-client-{role}'
                for part in ['logs','screenshots']:
                    if (gd/part).exists():shutil.copytree(gd/part,out/f'client-{role}-{part}',dirs_exist_ok=True)
            native_root=case/'client-native'/leg
            (out/'extracted-native-hashes.json').write_text(json.dumps({str(p.relative_to(native_root)):hashlib.sha256(p.read_bytes()).hexdigest() for p in native_root.rglob('*.so')},indent=2))
            for part in ['logs','crash-reports']:
                if (world/part).exists():shutil.copytree(world/part,out/f'server-{part}',dirs_exist_ok=True)
    (evidence/'CASE_PASSED').write_text('Real network lifecycle passed; production JAR unchanged; candidate is a separate test-only mixin\n')

order=os.environ.get('ANYA_PAIR_ORDER','baseline-first')
for index,candidate in enumerate([False,True] if order=='baseline-first' else [True,False]):run_case(f'{index}-'+('candidate' if candidate else 'baseline'),candidate)
print('ANYA_REMOTE_POLICY_PAIR_COMPLETE '+order,flush=True)
