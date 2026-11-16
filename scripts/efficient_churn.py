"""Counterbalanced fresh-world/JVM stress; does not accept profiles or existing saves.
No Minecraft client is launched here: 17 engine fixture players have embedded transport sinks.
Real-client regressions run in separate jobs from the exact same source/experiment JAR.
"""
from pathlib import Path
import csv,hashlib,json,os,platform,shutil,signal,subprocess,time,urllib.request
ROOT=Path.cwd().resolve();BASE=ROOT/'build/efficient-churn';OUT=BASE/'evidence'
EXPECTED='5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11'
assert os.environ.get('GITHUB_ACTIONS')=='true','Disposable GitHub runner only'
OUT.mkdir(parents=True,exist_ok=True)
JAR=ROOT/'build/libs/anya-storage-plus-0.3.0-beta.6.jar';TEST=next((ROOT/'build/ticket-experiment').glob('*.jar'))
assert hashlib.sha256(JAR.read_bytes()).hexdigest()==EXPECTED,'Production JAR changed'
def output(cmd):return subprocess.run(cmd,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True).stdout
def mem():return {k:int(v.split()[0])*1024 for k,v in (l.split(':',1) for l in Path('/proc/meminfo').read_text().splitlines())}
(OUT/'host.json').write_text(json.dumps({'uname':platform.uname()._asdict(),'cpu':output(['lscpu']),'java':output(['java','-version']),'memory':mem(),'cpu_max':Path('/sys/fs/cgroup/cpu.max').read_text() if Path('/sys/fs/cgroup/cpu.max').exists() else None,'order':os.environ['ANYA_CHURN_ORDER'],'runner':{k:os.environ.get(k) for k in ['ImageOS','ImageVersion','GITHUB_SHA','GITHUB_RUN_ID']},'heap_mib':3072,'initial_heap_mib':256,'active_processor_count':2,'view_distance':3,'simulation_distance':2,'real_clients':0,'fixture_players':17,'active_limit':8,'lease_limit':16,'playable_sha256':EXPECTED,'experiment_sha256':hashlib.sha256(TEST.read_bytes()).hexdigest(),'method':'new world/JVM each case; libraries and OS caches reused; no forced GC; RSS 1Hz; tick metrics every tick, readiness 20tick'},indent=2))
RUNTIME=BASE/'runtime';assert not RUNTIME.exists();RUNTIME.mkdir()
installer=BASE/'neoforge-installer.jar';urllib.request.urlretrieve('https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar',installer)
with (OUT/'installer.log').open('w') as log:subprocess.run(['java','-Xmx768m','-jar',str(installer),'--installServer',str(RUNTIME)],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=240)
def run(policy):
    world=BASE/policy;out=OUT/policy;assert not world.exists() and not out.exists();world.mkdir();out.mkdir()
    shutil.copytree(RUNTIME/'libraries',world/'libraries',copy_function=os.link);(world/'mods').mkdir()
    for p in [JAR,TEST]:shutil.copy2(p,world/'mods'/p.name)
    (world/'.anya-churn-fixture').write_text('Generated ephemeral CI fixture. No imported player data.\n')
    (world/'eula.txt').write_text('eula=true\n')
    props='level-name=world\nlevel-type=minecraft:flat\nlevel-seed=860612\ngenerate-structures=false\nonline-mode=false\nview-distance=3\nsimulation-distance=2\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25575\nallow-flight=true\nmax-players=20\nmax-tick-time=120000\n'
    (world/'server.properties').write_text(props);(out/'server.properties').write_text(props)
    cmd=['java','-Xms256m','-Xmx3072m','-XX:+UseG1GC','-XX:ActiveProcessorCount=2','-Danyastorage.pocketPrototype=true','-Danya.efficientChurn=true',f'-Danya.remoteTickets={str(policy=="candidate").lower()}','-Danya.cacheLimit=15000',f'-Danya.control={out}','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui']
    (out/'command.json').write_text(json.dumps(cmd,indent=2))
    with (out/'server.log').open('w') as log,(out/'rss.csv').open('w') as rss:
        writer=csv.writer(rss);writer.writerow(['elapsed_s','tick','phase','rss_bytes','host_available_bytes'])
        p=subprocess.Popen(cmd,cwd=world,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);start=time.monotonic()
        try:
            while p.poll() is None:
                status=Path(f'/proc/{p.pid}/status');v={k:val.strip() for k,val in (l.split(':',1) for l in status.read_text().splitlines())} if status.exists() else {}
                used=int(v.get('VmRSS','0 kB').split()[0])*1024;available=mem()['MemAvailable'];elapsed=time.monotonic()-start
                heartbeat=(out/'heartbeat').read_text().split(',') if (out/'heartbeat').exists() else ['0','startup'];writer.writerow([round(elapsed,3),*heartbeat[:2],used,available]);rss.flush()
                failed=list(out.glob('FAILED*'))
                if failed:raise RuntimeError('\n'.join(q.read_text() for q in failed))
                if used>5*1024**3 or available<2*1024**3 or elapsed>900:raise RuntimeError(f'Protective resource stop rss={used} available={available} elapsed={elapsed}')
                time.sleep(1)
            assert p.returncode==0 and (out/'PASSED').exists(),'No successful stress completion'
            assert 'after=0' in (out/'ticket-stop-audit.txt').read_text(),'No exact engine shutdown audit'
            print('ANYA_EFFICIENT_CHURN_OK '+policy+' '+(out/'PASSED').read_text().replace('\n','; '),flush=True)
        finally:
            if p.poll() is None:
                os.killpg(p.pid,signal.SIGTERM)
                try:p.wait(timeout=20)
                except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)
            for part in ['logs','crash-reports']:
                if (world/part).exists():shutil.copytree(world/part,out/part,dirs_exist_ok=True)
order=['baseline','candidate'] if os.environ['ANYA_CHURN_ORDER']=='baseline-first' else ['candidate','baseline']
for policy in order:run(policy)
print('ANYA_EFFICIENT_PAIR_OK order='+','.join(order),flush=True)
