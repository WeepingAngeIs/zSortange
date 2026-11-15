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
inputs=[jar,fixture,*sorted((base/'dependencies').glob('*.jar'))]
for path in inputs:shutil.copy2(path,mods/path.name)
(logdir/'runtime-inputs.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},indent=2))
installer=base/'neoforge-installer.jar'
url='https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar'
if not installer.exists():urllib.request.urlretrieve(url,installer)
with (logdir/'installer.log').open('w') as log:
    subprocess.run(['java','-Xmx1000m','-jar',str(installer),'--installServer',str(server)],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=240)
(server/'eula.txt').write_text('eula=true\n')
(server/'server.properties').write_text('level-name=world\nlevel-type=minecraft:flat\ngenerate-structures=false\nonline-mode=false\nview-distance=3\nsimulation-distance=2\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25577\nallow-flight=true\nmax-tick-time=120000\n')
processes=[];handles=[]
def launch(cmd,cwd,name):
    log=(logdir/(name+'.log')).open('w');handles.append(log)
    p=subprocess.Popen(cmd,cwd=cwd,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);processes.append(p);return p

def client(role,suffix=''):
    config=json.loads((base/f'client-{role}.json').read_text());gd=Path(config['cwd']);gd.mkdir(exist_ok=True)
    (gd/'options.txt').write_text('enableVsync:false\nmaxFps:25\nrenderDistance:3\nsimulationDistance:2\nguiScale:2\npauseOnLostFocus:false\n')
    return launch(config['command'],config['cwd'],'client-'+role+suffix)

def failure():
    found=list(control.glob('FAILED*'))
    if found:raise RuntimeError('\n'.join(p.read_text() for p in found))
try:
    srv=launch(['java','-Xmx1400m','-Xms256m','-Danyastorage.pocketPrototype=true','-Danya.acceptance=true',f'-Danya.control={control}',f'-Danya.expectedJar={jar}','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui'],server,'packaged-server')
    deadline=time.monotonic()+180
    while not (control/'server-ready').exists():
        failure()
        if srv.poll() is not None:raise RuntimeError('Packaged server exited before ready')
        if time.monotonic()>deadline:raise TimeoutError('Packaged server startup')
        time.sleep(1)
    alpha=client('Alpha');beta=client('Beta');killed=False;restarted=False;deadline=time.monotonic()+720
    while not (control/'PASSED').exists():
        failure()
        if (control/'kill-beta').exists() and not killed:
            os.killpg(beta.pid,signal.SIGKILL);beta.wait(timeout=15);killed=True
            (logdir/'socket-kill.txt').write_text('Killed real Beta Minecraft JVM process group with SIGKILL, not an API logout callback.\n')
        if killed and not restarted and (control/'restart-beta').exists():beta=client('Beta','-reconnect');restarted=True
        if srv.poll() is not None:raise RuntimeError('Packaged server exited before acceptance completed')
        if alpha.poll() is not None:raise RuntimeError('Alpha real client exited')
        if time.monotonic()>deadline:raise TimeoutError('Real client acceptance')
        time.sleep(1)
    assert killed and restarted, 'Socket test not exercised'
    print('ANYA_PACKAGED_NETWORK_ACCEPTANCE_OK')
finally:
    for p in processes:
        if p.poll() is None:
            os.killpg(p.pid,signal.SIGTERM)
    for p in processes:
        try:p.wait(timeout=10)
        except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL)
    for h in handles:h.close()
    for gd in root.glob('run-packaged-client-*'):
        if (gd/'screenshots').exists():shutil.copytree(gd/'screenshots',logdir/gd.name,dirs_exist_ok=True)
    if (server/'logs').exists():shutil.copytree(server/'logs',logdir/'server-logs',dirs_exist_ok=True)
    shutil.copytree(control,logdir/'control',dirs_exist_ok=True)
