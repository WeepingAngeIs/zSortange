"""Bounded ABBA/BAAB allocation investigation; existing full workload, new measurements.
No player inputs accepted. No candidate or production class is edited. Diagnostic GCs are explicit.
"""
from pathlib import Path
import csv,hashlib,json,os,platform,shutil,signal,subprocess,time,urllib.request,xml.etree.ElementTree as ET
ROOT=Path.cwd().resolve(); BASE=ROOT/'build/allocation-investigation'; OUT=BASE/'evidence'
assert os.environ.get('GITHUB_ACTIONS')=='true','Ephemeral GitHub runners only'
BASE.mkdir(parents=True,exist_ok=False);OUT.mkdir()
JAR=ROOT/'build/libs/anya-storage-plus-0.3.0-beta.6.jar'
TEST=next((ROOT/'build/ticket-experiment').glob('*.jar'))
OBSERVER=next((ROOT/'build/allocation').glob('*.jar'))
EXPECTED='5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11'
CANDIDATE='6aa9f312cc2b526a86b2122510d50021845a9b12aa8039298af2012748d30bcd'
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
assert digest(JAR)==EXPECTED and digest(TEST)==CANDIDATE,'Production or prior safety-tested candidate binary changed'
def output(cmd,timeout=20):return subprocess.run(cmd,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=timeout,check=True).stdout
def memory():return {k:int(v.split()[0])*1024 for k,v in (l.split(':',1) for l in Path('/proc/meminfo').read_text().splitlines())}
def optional_text(name):
    try:return Path(name).read_text()
    except OSError as error:return 'unavailable: '+str(error)
def process_text(path,process):
    try:return path.read_text()
    except (FileNotFoundError,ProcessLookupError):
        if process.poll() is None:raise
        return None # Process exited between poll and procfs read. Exit/markers are still verified below.
order=os.environ['ANYA_ALLOCATION_ORDER'];first=['baseline','candidate'] if order=='baseline-first' else ['candidate','baseline']
# Two timing cases followed by a reversed diagnostic pair. No diagnostic operation in lean cases.
cases=[(x,False) for x in first]+[(x,True) for x in reversed(first)]
(OUT/'host.json').write_text(json.dumps({'uname':platform.uname()._asdict(),'cpu':output(['lscpu']),'java':output(['java','-version']),'initial_memory':memory(),'order':order,'cases':cases,'cpu_max':optional_text('/sys/fs/cgroup/cpu.max'),'runner':{k:os.environ.get(k) for k in ['ImageOS','ImageVersion','GITHUB_SHA','GITHUB_RUN_ID']},'heap_xms_xmx_mib':[256,3072],'view':3,'simulation':2,'fixture_players':17,'real_client_jvms':0,'hashes':{p.name:digest(p) for p in [JAR,TEST,OBSERVER]},'method':'fresh world and JVM per case; OS caches reused; zero readiness scans except existing stage safety assertions; 1Hz RSS; 0.2Hz PSS; diagnostic-only JFR/NMT and three live class histograms; no forced GC in lean cases'},indent=2))
RUNTIME=BASE/'runtime';RUNTIME.mkdir(); installer=BASE/'neoforge-installer.jar'
urllib.request.urlretrieve('https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar',installer)
with (OUT/'installer.log').open('w') as f:subprocess.run(['java','-Xmx768m','-jar',str(installer),'--installServer',str(RUNTIME)],stdout=f,stderr=subprocess.STDOUT,timeout=240,check=True)
java_home=Path(os.environ['JAVA_HOME']); config=ET.parse(java_home/'lib/jfr/default.jfc'); root=config.getroot()
def setting(event,key,value):
    el=next((x for x in root.findall('event') if x.attrib.get('name')==event),None)
    if el is None:el=ET.SubElement(root,'event',{'name':event})
    node=next((x for x in el.findall('setting') if x.attrib.get('name')==key),None)
    if node is None:node=ET.SubElement(el,'setting',{'name':key})
    node.attrib.pop('control',None);node.text=value
for ev in ['jdk.ExecutionSample','jdk.NativeMethodSample']:
    setting(ev,'enabled','true');setting(ev,'period','20 ms')
for ev in ['jdk.ThreadPark','jdk.JavaMonitorEnter','jdk.ThreadSleep','jdk.FileRead','jdk.FileWrite']:
    setting(ev,'enabled','true');setting(ev,'threshold','10 ms');setting(ev,'stackTrace','true')
setting('jdk.ObjectAllocationSample','enabled','true');setting('jdk.ObjectAllocationSample','throttle','100/s');setting('jdk.ObjectAllocationSample','stackTrace','true')
for ev in ['jdk.GarbageCollection','jdk.GCPhasePause','jdk.GCHeapSummary','jdk.G1HeapSummary','jdk.HeapSummary']:
    setting(ev,'enabled','true')
config.write(OUT/'bounded.jfc',encoding='utf-8',xml_declaration=True)

def inspect(pid,out,name):
    d=out/('snapshot-'+name);d.mkdir()
    (d/'before-status.txt').write_text(Path(f'/proc/{pid}/status').read_text())
    (d/'before-smaps.txt').write_text(Path(f'/proc/{pid}/smaps_rollup').read_text())
    commands=[('heap-before',['GC.heap_info']),('native-before',['VM.native_memory','summary','scale=KB']),('live-histogram',['GC.class_histogram']),('heap-after',['GC.heap_info']),('native-after',['VM.native_memory','summary','scale=KB'])]
    times=[]
    for label,args in commands:
        began=time.time_ns();text=output(['jcmd',str(pid),*args],timeout=35);ended=time.time_ns();(d/(label+'.txt')).write_text(text);times.append({'label':label,'start_ns':began,'end_ns':ended})
    (d/'after-status.txt').write_text(Path(f'/proc/{pid}/status').read_text());(d/'after-smaps.txt').write_text(Path(f'/proc/{pid}/smaps_rollup').read_text());(d/'commands.json').write_text(json.dumps(times,indent=2))
    (out/('snapshot-'+name+'.done')).write_text('Measured diagnostic pause; live histogram requested full collection; not normal workload\n')

def run(index,policy,diagnostic):
    name=f'{index}-{policy}-'+('diagnostic' if diagnostic else 'lean');world=BASE/name;out=OUT/name;world.mkdir();out.mkdir()
    shutil.copytree(RUNTIME/'libraries',world/'libraries',copy_function=os.link);(world/'mods').mkdir()
    for p in [JAR,TEST,OBSERVER]:shutil.copy2(p,world/'mods'/p.name)
    (world/'.anya-churn-fixture').write_text('Fresh isolated fixture, no imported saves\n');(world/'eula.txt').write_text('eula=true\n')
    props='level-name=world\nlevel-type=minecraft:flat\nlevel-seed=860612\ngenerate-structures=false\nonline-mode=false\nview-distance=3\nsimulation-distance=2\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25575\nallow-flight=true\nmax-players=20\nmax-tick-time=120000\n'
    (world/'server.properties').write_text(props);(out/'server.properties').write_text(props)
    cmd=['java','-Xms256m','-Xmx3072m','-XX:+UseG1GC','-XX:ActiveProcessorCount=2',f'-Xlog:gc*,safepoint:file={out}/gc.log:time,uptimemillis,level,tags:filecount=2,filesize=16M','-Danyastorage.pocketPrototype=true','-Danya.efficientChurn=true',f'-Danya.remoteTickets={str(policy=="candidate").lower()}',f'-Danya.allocationDiagnostic={str(diagnostic).lower()}',f'-Danya.control={out}']
    if diagnostic:cmd+=['-XX:NativeMemoryTracking=summary',f'-XX:StartFlightRecording=settings={OUT}/bounded.jfc,filename={out}/recording.jfr,maxsize=96m,dumponexit=true']
    cmd+=['@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui'];(out/'command.json').write_text(json.dumps(cmd,indent=2))
    handled=set()
    with (out/'server.log').open('w') as log,(out/'rss.csv').open('w') as rss:
        w=csv.writer(rss);w.writerow(['elapsed_s','epoch_ns','tick','phase','diagnostic_pause','rss_bytes','rss_anon','pss_bytes','private_dirty','host_available']);p=subprocess.Popen(cmd,cwd=world,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);start=time.monotonic();sample=0
        try:
            while p.poll() is None:
                sample+=1;elapsed=time.monotonic()-start;available=memory()['MemAvailable']
                status_text=process_text(Path(f'/proc/{p.pid}/status'),p)
                if status_text is None:break
                d={k:v.strip() for k,v in (l.split(':',1) for l in status_text.splitlines())}
                used=int(d.get('VmRSS','0 kB').split()[0])*1024;anon=int(d.get('RssAnon','0 kB').split()[0])*1024;pss=dirty=-1
                if sample%5==0:
                    smaps=process_text(Path(f'/proc/{p.pid}/smaps_rollup'),p)
                    if smaps is None:break
                    vals={k:int(v.split()[0])*1024 for k,v in (l.split(':',1) for l in smaps.splitlines() if ':' in l)};pss=vals.get('Pss',-1);dirty=vals.get('Private_Dirty',-1)
                heartbeat=(out/'light-heartbeat').read_text().split(',') if (out/'light-heartbeat').exists() else ['0','startup','false']
                w.writerow([round(elapsed,3),time.time_ns(),*heartbeat,used,anon,pss,dirty,available]);rss.flush()
                failed=list(out.glob('FAILED*'))
                if failed:raise RuntimeError('\n'.join(f.name+': '+f.read_text() for f in failed))
                if used>5*1024**3 or available<2*1024**3 or elapsed>540:raise RuntimeError('Protective time/memory ceiling reached')
                if sum(f.stat().st_size for f in out.rglob('*') if f.is_file())>200*1024**2:raise RuntimeError('Evidence size ceiling reached')
                request=out/'snapshot-request'
                if request.exists():
                    tag=request.read_text()
                    if tag not in handled:inspect(p.pid,out,tag);handled.add(tag)
                time.sleep(1)
            assert p.returncode==0 and (out/'PASSED').exists(),'No successful workload termination'
            assert 'after=0' in (out/'ticket-stop-audit.txt').read_text(),'No exact engine-ticket cleanup'
            if diagnostic:assert handled=={'empty','loaded','idle'},'Missing retention checkpoint'
            (out/'CASE_PASSED').write_text('Unchanged beta.6/candidate hashes; original churn safety assertions passed\n')
            print('ANYA_ALLOCATION_CASE_OK '+name,flush=True)
        finally:
            if p.poll() is None:
                os.killpg(p.pid,signal.SIGTERM)
                try:p.wait(timeout=15)
                except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)
            for part in ['logs','crash-reports']:
                if (world/part).exists():shutil.copytree(world/part,out/part,dirs_exist_ok=True)
    if diagnostic:
        with (out/'jfr-summary.txt').open('w') as f:subprocess.run(['jfr','summary',str(out/'recording.jfr')],stdout=f,stderr=subprocess.STDOUT,timeout=40,check=True)
        events='anya.*,jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.ThreadSleep,jdk.FileRead,jdk.FileWrite,jdk.GarbageCollection,jdk.GCPhasePause,jdk.GCHeapSummary,jdk.ObjectAllocationSample'
        with (out/'jfr-events.json').open('w') as f:subprocess.run(['jfr','print','--json','--events',events,str(out/'recording.jfr')],stdout=f,stderr=subprocess.STDOUT,timeout=70,check=True)
for i,(policy,diagnostic) in enumerate(cases):run(i,policy,diagnostic)
print('ANYA_ALLOCATION_INVESTIGATION_PASSED '+order,flush=True)
