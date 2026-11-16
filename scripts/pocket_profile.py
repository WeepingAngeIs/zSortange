"""Bounded, test-only packaged server profiling. No user data or existing world is accepted.
A separate fixture instruments the unchanged production JAR; candidate ticket settings are not shipped.
"""
from pathlib import Path
import csv, hashlib, json, os, platform, shutil, signal, subprocess, time, urllib.request
ROOT=Path.cwd().resolve();BASE=ROOT/'build/profiling';OUT=BASE/'evidence'
EXPECTED='5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11'
assert os.environ.get('GITHUB_ACTIONS')=='true','Ephemeral CI only'
OUT.mkdir(parents=True,exist_ok=True)
JAR=next(p for p in (ROOT/'build/libs').glob('anya-storage-plus-*.jar') if not p.name.endswith('-sources.jar'))
FIXTURE=next(BASE.glob('anya-storage-profile-fixture-*.jar'))
assert hashlib.sha256(JAR.read_bytes()).hexdigest()==EXPECTED,'Production must remain exact beta.6'

def command_output(command):
    return subprocess.run(command,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True).stdout

def meminfo():
    return {k:int(v.split()[0])*1024 for k,v in (l.split(':',1) for l in Path('/proc/meminfo').read_text().splitlines())}

host={'uname':platform.uname()._asdict(),'lscpu':command_output(['lscpu']),'java':command_output(['java','-version']),
      'meminfo_initial':meminfo(),'cpu_max':Path('/sys/fs/cgroup/cpu.max').read_text() if Path('/sys/fs/cgroup/cpu.max').exists() else None,
      'memory_max':Path('/sys/fs/cgroup/memory.max').read_text() if Path('/sys/fs/cgroup/memory.max').exists() else None,
      'runner':{k:os.environ.get(k) for k in ('RUNNER_OS','RUNNER_ARCH','ImageOS','ImageVersion','GITHUB_SHA','GITHUB_RUN_ID')},
      'heap_mib':3072,'xms_mib':256,'jvm_active_processor_count':2,'view_distance':3,'simulation_distance':2,
      'clients':0,'fixture_identities':17,'lease_ceiling':16,'active_pocket_ceiling':8,
      'instrumentation':'server pre/post event wall/thread CPU; process CPU; required tickChunk mixin; futures ready every20 ticks; RSS each1s',
      'production_sha256':EXPECTED,'fixture_sha256':hashlib.sha256(FIXTURE.read_bytes()).hexdigest()}
(OUT/'host.json').write_text(json.dumps(host,indent=2))
# Install once, then reuse libraries only. Every case has an independently new world and JVM.
install=BASE/'runtime';assert not install.exists(),'Refusing to reuse installed profiling runtime';install.mkdir()
installer=BASE/'neoforge-installer.jar'
urllib.request.urlretrieve('https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar',installer)
with (OUT/'installer.log').open('w') as log:
    subprocess.run(['java','-Xmx768m','-jar',str(installer),'--installServer',str(install)],stdout=log,stderr=subprocess.STDOUT,timeout=240,check=True)

def percent(values,p):
    if not values:return None
    s=sorted(values);return s[min(len(s)-1,int((len(s)-1)*p))]

def summarize(out):
    rows=list(csv.DictReader((out/'ticks.csv').open()));phases={}
    hs=list(csv.DictReader((out/'holders.csv').open()));rs=list(csv.DictReader((out/'rss.csv').open()))
    for name in dict.fromkeys(r['phase'] for r in rows):
        group=[r for r in rows if r['phase']==name];holder=[r for r in hs if r['phase']==name];rss=[r for r in rs if r['phase']==name]
        ns=[int(r['event_tick_ns'])/1e6 for r in group];period=sum(int(r['period_ns']) for r in group)
        phases[name]={'ticks':len(group),'elapsed_s':period/1e9,'tick_ms_p50':percent(ns,.5),'tick_ms_p95':percent(ns,.95),'tick_ms_p99':percent(ns,.99),'tick_ms_max':max(ns),
         'event_tick_mean_ms':sum(ns)/len(ns),'server_thread_cpu_ms_mean':sum(int(r['thread_cpu_ns']) for r in group)/len(group)/1e6,
         'process_cpu_core_equivalents':sum(int(r['process_cpu_ns']) for r in group)/period if period else 0,
         'service_ms_mean':sum(int(r['service_ns']) for r in group)/len(group)/1e6,'service_ms_max':max(int(r['service_ns']) for r in group)/1e6,
         'measurement_ms_mean':sum(int(r['measurement_ns']) for r in group)/len(group)/1e6,'cache_peak':max(int(r['cache']) for r in group),
         'heap_used_peak_bytes':max(int(r['heap_used']) for r in group),'rss_peak_bytes':max([int(r['rss_bytes']) for r in rss],default=None),
         'requests_peak':max(int(r['requests']) for r in group),'actual_tickChunk_calls_mean':sum(int(r['actual_tickChunk_calls']) for r in group)/len(group),
         'shell_writes_sum':sum(int(r['shell_writes']) for r in group),'shell_writes_max':max(int(r['shell_writes']) for r in group),
         'ready_full_peak':max([int(r['full_ready']) for r in holder],default=None),'ready_block_ticking_peak':max([int(r['block_ticking_ready']) for r in holder],default=None),
         'ready_entity_ticking_peak':max([int(r['entity_ticking_ready']) for r in holder],default=None),
         'last':{k:int(group[-1][k]) for k in ('active','leases','requests','cache','heap_used','heap_committed','gc_count','gc_ms')}}
    (out/'summary.json').write_text(json.dumps(phases,indent=2))

def run_case(name,candidate):
    world=BASE/name;assert not world.exists(),'Refusing an existing world directory';world.mkdir();out=OUT/name;out.mkdir()
    shutil.copytree(install/'libraries',world/'libraries',copy_function=os.link)
    (world/'mods').mkdir()
    for p in (JAR,FIXTURE):shutil.copy2(p,world/'mods'/p.name)
    (world/'.anya-profile-fixture').write_text('Generated disposable CI fixture; no user data\n')
    (world/'eula.txt').write_text('eula=true\n')
    properties='level-name=world\nlevel-type=minecraft:flat\nlevel-seed=860612\ngenerate-structures=false\nonline-mode=false\nview-distance=3\nsimulation-distance=2\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25575\nallow-flight=true\nmax-players=20\nmax-tick-time=120000\n'
    (world/'server.properties').write_text(properties);(out/'server.properties').write_text(properties)
    cmd=['java','-Xms256m','-Xmx3072m','-XX:+UseG1GC','-XX:ActiveProcessorCount=2','-Danya.profile=true','-Danyastorage.pocketPrototype=true',f'-Danya.profileFullOnly={str(candidate).lower()}',f'-Danya.profileOut={out}',f'-Danya.expectedHash={EXPECTED}','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui']
    (out/'command.json').write_text(json.dumps(cmd,indent=2))
    with (out/'server.log').open('w') as log,(out/'rss.csv').open('w') as rss:
        writer=csv.writer(rss);writer.writerow(['elapsed_s','tick','phase','rss_bytes','threads','host_available_bytes'])
        p=subprocess.Popen(cmd,cwd=world,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);start=time.monotonic();stop_at=None;reason=''
        try:
            while p.poll() is None:
                elapsed=time.monotonic()-start;status={}
                path=Path(f'/proc/{p.pid}/status')
                if path.exists():status={k:v.strip() for k,v in (line.split(':',1) for line in path.read_text().splitlines())}
                current_rss=int(status.get('VmRSS','0 kB').split()[0])*1024;available=meminfo()['MemAvailable']
                heartbeat=(out/'heartbeat').read_text().split(',') if (out/'heartbeat').exists() else ['0','startup','0']
                writer.writerow([round(elapsed,3),heartbeat[0],heartbeat[1] if len(heartbeat)>1 else 'startup',current_rss,status.get('Threads','0'),available]);rss.flush()
                if stop_at is None and (current_rss>5*1024**3 or available<1536*1024**2 or elapsed>900):
                    reason=f'watchdog: rss={current_rss} host_available={available} elapsed={elapsed:.1f}';(out/'STOP_REQUESTED').write_text(reason);stop_at=time.monotonic();print(name,reason,flush=True)
                if stop_at is not None and time.monotonic()-stop_at>35:
                    p.terminate();break
                time.sleep(1)
            try:p.wait(timeout=25)
            except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait();(out/'WATCHDOG_KILL').write_text('No graceful stop before protective timeout; no user data involved')
        finally:
            if p.poll() is None:os.killpg(p.pid,signal.SIGKILL);p.wait()
            if (world/'logs').exists():shutil.copytree(world/'logs',out/'logs',dirs_exist_ok=True)
            if (world/'crash-reports').exists():shutil.copytree(world/'crash-reports',out/'crash-reports',dirs_exist_ok=True)
    if (out/'ticks.csv').exists():summarize(out)
    check='PASSED' if (out/'PASSED').exists() else 'LIMITED' if (out/'LIMITED').exists() else 'FAILED'
    print(f'ANYA_PROFILE_CASE {name} {check} exit={p.returncode}',flush=True)
    if check=='FAILED':raise RuntimeError(name+': no passing/limited marker; inspect precise server log')

run_case('baseline',False)
run_case('full-only-test',True)
print('ANYA_PROFILE_EVIDENCE_COMPLETE: no shipped ticket change; cases and safe ceilings are in markers')
