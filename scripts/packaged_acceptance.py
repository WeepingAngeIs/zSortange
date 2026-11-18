"""Launch only newly created CI fixtures, never an installed profile or supplied world.
Real clients are separate Minecraft JVMs over loopback TCP. A process SIGKILL tests socket loss.
"""
from pathlib import Path
import hashlib,json,os,shutil,signal,subprocess,time,urllib.request
root=Path.cwd().resolve(); base=root/'build/acceptance'; control=base/'control'; server=base/'server'
assert os.environ.get('GITHUB_ACTIONS')=='true', 'This harness requires an isolated GitHub runner'
assert not server.exists(), 'Refusing to reuse a server/world'
server.mkdir(parents=True);control.mkdir(parents=True,exist_ok=True)
logdir=base/'evidence';logdir.mkdir(exist_ok=True)
jar=next(p for p in (root/'build/libs').glob('anya-storage-plus-*.jar') if not p.name.endswith('-sources.jar'))
fixture=next(base.glob('anya-storage-acceptance-fixture-*.jar'))
mods=server/'mods';mods.mkdir()
efficient=os.environ.get('ANYA_ROOM_TICKET_EXPERIMENT')=='true'
extra=[next((root/'build/ticket-experiment').glob('*.jar'))] if efficient else []
inputs=[jar,fixture,*extra,*sorted((base/'dependencies').glob('*.jar'))]
if os.environ.get('ANYA_DURABLE_PROBE')=='true':inputs.append(next((root/'build/durable').glob('*.jar')))
for path in inputs:shutil.copy2(path,mods/path.name)
(logdir/'runtime-inputs.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},indent=2))
installer=base/'neoforge-installer.jar'
url='https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar'
if not installer.exists():urllib.request.urlretrieve(url,installer)
with (logdir/'installer.log').open('w') as log:
    subprocess.run(['java','-Xmx1000m','-jar',str(installer),'--installServer',str(server)],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=240)
(server/'eula.txt').write_text('eula=true\n')
(server/'server.properties').write_text('level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nonline-mode=false\nview-distance=3\nsimulation-distance=2\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25577\nallow-flight=true\nmax-tick-time=120000\n')
room=os.environ.get('ANYA_ACCEPTANCE_SCENARIO')=='room'
multi=os.environ.get('ANYA_ACCEPTANCE_SCENARIO')=='multi'
art=os.environ.get('ANYA_ACCEPTANCE_SCENARIO')=='art'
preview=os.environ.get('ANYA_ACCEPTANCE_SCENARIO')=='pad-preview'
processes=[];handles=[];expected_dead=set();process_names={};native_launches=[]
def launch(cmd,cwd,name,env=None):
    log=(logdir/(name+'.log')).open('w');handles.append(log)
    p=subprocess.Popen(cmd,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
    processes.append(p);process_names[p.pid]=name;return p

def client(role,suffix=''):
    config=json.loads((base/f'client-{role}.json').read_text());gd=Path(config['cwd']);gd.mkdir(exist_ok=True)
    options_text = 'enableVsync:false\nmaxFps:25\nrenderDistance:3\nsimulationDistance:2\nguiScale:2\npauseOnLostFocus:false\n'
    if art or preview:
        options_text += 'tutorialStep:none\n'
    if preview:
        options_text += 'bobView:false\nfovEffectScale:0.0\nentityShadows:false\n'
    (gd/'options.txt').write_text(options_text)
    # LWJGL 3.3.3 supports this explicit extraction path. Never share extracted natives
    # between simultaneously booting JVMs or reuse a killed client's directory.
    private=base/'client-native'/(role+(suffix or '-initial'))
    private.mkdir(parents=True,exist_ok=False);tmp=private/'tmp';tmp.mkdir();native=private/'lwjgl';native.mkdir()
    options=f'-Djava.io.tmpdir={tmp} -Dorg.lwjgl.system.SharedLibraryExtractPath={native} -Dorg.lwjgl.util.DebugLoader=true'
    env=os.environ.copy();env['JAVA_TOOL_OPTIONS']=(env.get('JAVA_TOOL_OPTIONS','')+' '+options).strip()
    native_launches.append({'role':role,'suffix':suffix,'tmp':str(tmp),'extraction':str(native),'options':options})
    (logdir/'client-native-launches.json').write_text(json.dumps(native_launches,indent=2))
    return launch(config['command'],config['cwd'],'client-'+role+suffix,env)

def failure():
    found=list(control.glob('FAILED*'))
    if found:raise RuntimeError('\n'.join(p.read_text() for p in found))
    # Previously only Alpha was checked: a Beta native-loader crash became phase-0 timeout.
    for p in processes:
        code=p.poll()
        if code is not None and p.pid not in expected_dead:
            name=process_names[p.pid]
            tail='\n'.join((logdir/(name+'.log')).read_text(errors='replace').splitlines()[-45:])
            raise RuntimeError(f'Unexpected process exit: {name}, code={code}\n{tail}')
try:
    pocket_flag='-Danya.artAcceptance=true' if art else '-Danyastorage.pocketPrototype=true'
    srv=launch(['java','-Xmx1400m','-Xms256m',pocket_flag,'-Danya.acceptance=true',f'-Danya.remoteTickets={str(efficient).lower()}',f'-Danya.roomAcceptance={str(room).lower()}',f'-Danya.multiAcceptance={str(multi).lower()}',f'-Danya.control={control}',f'-Danya.expectedJar={jar}','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui'],server,'packaged-server')
    deadline=time.monotonic()+180
    while not (control/'server-ready').exists():
        failure()
        if time.monotonic()>deadline:raise TimeoutError('Packaged server startup')
        time.sleep(1)
    alpha=client('Alpha');beta=client('Beta');killed=False;restarted=False;cycles_killed=set();cycles_restarted=set();deadline=time.monotonic()+720
    while not (control/'PASSED').exists():
        failure()
        if multi:
            for cycle in (1,2):
                if (control/f'kill-beta-{cycle}').exists() and cycle not in cycles_killed:
                    expected_dead.add(beta.pid);os.killpg(beta.pid,signal.SIGKILL);beta.wait(timeout=15);cycles_killed.add(cycle)
                    (logdir/f'socket-kill-{cycle}.txt').write_text('SIGKILL real Beta Minecraft client process group; not an API logout.\n')
                if cycle in cycles_killed and cycle not in cycles_restarted and (control/f'restart-beta-{cycle}').exists():
                    beta=client('Beta',f'-reconnect-{cycle}');cycles_restarted.add(cycle)
        if (control/'kill-beta').exists() and not killed:
            expected_dead.add(beta.pid);os.killpg(beta.pid,signal.SIGKILL);beta.wait(timeout=15);killed=True
            (logdir/'socket-kill.txt').write_text('Killed real Beta Minecraft JVM process group with SIGKILL, not an API logout callback.\n')
        if killed and not restarted and (control/'restart-beta').exists():beta=client('Beta','-reconnect');restarted=True
        if time.monotonic()>deadline:raise TimeoutError('Real client acceptance')
        time.sleep(1)
    assert (cycles_killed=={1,2} and cycles_restarted=={1,2}) if multi else (killed and restarted), 'Socket test not exercised'
    expected_dead.add(srv.pid)
    if multi:
        assert srv.wait(timeout=45)==0,'Multi-pocket server did not stop cleanly'
        resume=launch(['java','-Xmx1400m','-Xms256m','-Danyastorage.pocketPrototype=true','-Danya.acceptance=true','-Danya.multiAcceptance=true','-Danya.multiResume=true',f'-Danya.control={control}',f'-Danya.expectedJar={jar}','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui'],server,'multi-restart-server')
        assert resume.wait(timeout=150)==0,'Multi-pocket restart process failed';expected_dead.add(resume.pid)
        failure();assert (control/'MULTI_RESTART_PASSED').exists(),'Multi-pocket restart verification missing'
        print('ANYA_PACKAGED_MULTI_POCKET_ACCEPTANCE_OK',flush=True)
    elif room:
        assert srv.wait(timeout=45)==0,'Room server did not stop cleanly'
        resume=launch(['java','-Xmx1400m','-Xms256m','-Danyastorage.pocketPrototype=true','-Danya.acceptance=true',f'-Danya.remoteTickets={str(efficient).lower()}','-Danya.roomAcceptance=true','-Danya.roomResume=true',f'-Danya.control={control}',f'-Danya.expectedJar={jar}','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui'],server,'room-restart-server')
        assert resume.wait(timeout=150)==0,'Room restart process failed';expected_dead.add(resume.pid)
        failure();assert (control/'ROOM_RESTART_PASSED').exists(),'Room restart verification missing'
        print('ANYA_PACKAGED_ROOM_ACCEPTANCE_OK',flush=True)
    elif preview:
        assert srv.wait(timeout=45)==0,'Pad preview server did not stop cleanly'
        print('ANYA_PAD_PREVIEW_CLIENT_CHECKS_OK',flush=True)
    elif art:
        assert srv.wait(timeout=45)==0,'Visual fixture server did not stop cleanly'
        print('ANYA_STORAGE_ART_RECONNECT_OK',flush=True)
    else:print('ANYA_PACKAGED_NETWORK_ACCEPTANCE_OK')
finally:
    for p in processes:
        if p.poll() is None:os.killpg(p.pid,signal.SIGTERM)
    for p in processes:
        try:p.wait(timeout=10)
        except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL)
    for h in handles:h.close()
    for gd in root.glob('run-packaged-client-*'):
        if (gd/'screenshots').exists():shutil.copytree(gd/'screenshots',logdir/gd.name,dirs_exist_ok=True)
    if (server/'logs').exists():shutil.copytree(server/'logs',logdir/'server-logs',dirs_exist_ok=True)
    shutil.copytree(control,logdir/'control',dirs_exist_ok=True)
    native_files={str(p.relative_to(base)):hashlib.sha256(p.read_bytes()).hexdigest() for p in (base/'client-native').rglob('*.so')}
    (logdir/'extracted-native-hashes.json').write_text(json.dumps(native_files,indent=2))
