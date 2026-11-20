"""Disposable packaged preparation/fault probe. No real client in this fixture lane."""
from pathlib import Path
import csv,hashlib,json,os,platform,shutil,signal,subprocess,time,urllib.request
ROOT=Path.cwd().resolve();BASE=ROOT/'build/durable-runtime';OUT=BASE/'evidence'
assert os.environ.get('GITHUB_ACTIONS')=='true'
BASE.mkdir(parents=True,exist_ok=False);OUT.mkdir()
JAR=ROOT/'build/libs/anya-storage-plus-0.3.0-beta.6.jar';MOD=next((ROOT/'build/durable').glob('*.jar'))
assert hashlib.sha256(JAR.read_bytes()).hexdigest()=='5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11'
def output(cmd):return subprocess.run(cmd,check=True,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=20).stdout
def mem():return {k:int(v.split()[0])*1024 for k,v in (l.split(':',1) for l in Path('/proc/meminfo').read_text().splitlines())}
(OUT/'host.json').write_text(json.dumps({'cpu':output(['lscpu']),'java':output(['java','-version']),'uname':platform.uname()._asdict(),'memory':mem(),'heap_mib':2048,'view':3,'simulation':2,'real_clients':0,'fixture_players_max':8,'initial_worlds':'new','OS_cache':'not cleared','source':os.environ['GITHUB_SHA'],'hashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [JAR,MOD]},'mode':os.environ['ANYA_DURABLE_LANE']},indent=2))
RUNTIME=BASE/'runtime';RUNTIME.mkdir();installer=BASE/'installer.jar';urllib.request.urlretrieve('https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar',installer)
with (OUT/'installer.log').open('w') as f:subprocess.run(['java','-Xmx768m','-jar',str(installer),'--installServer',str(RUNTIME)],stdout=f,stderr=subprocess.STDOUT,check=True,timeout=240)
def world(name):
 w=BASE/name;w.mkdir();shutil.copytree(RUNTIME/'libraries',w/'libraries',copy_function=os.link);(w/'mods').mkdir()
 for p in [JAR,MOD]:shutil.copy2(p,w/'mods'/p.name)
 (w/'.anya-durable-fixture').write_text('Generated isolated fixture; never supplied player data')
 (w/'eula.txt').write_text('eula=true\n')
 (w/'server.properties').write_text('level-name=world\nlevel-type=minecraft:flat\nlevel-seed=860612\ngenerate-structures=false\nonline-mode=false\nview-distance=3\nsimulation-distance=2\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25574\nallow-flight=true\nmax-tick-time=120000\n')
 return w

def run(w,scenario,candidate,expected='PASSED',exitcode=0):
 out=OUT/(w.name+'-'+scenario);out.mkdir()
 cmd=['java','-Xms256m','-Xmx2048m','-XX:+UseG1GC','-XX:ActiveProcessorCount=2','-Danyastorage.pocketPrototype=true',f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}',f'-Danya.control={out}','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui']
 (out/'command.json').write_text(json.dumps(cmd));shutil.copy2(w/'server.properties',out/'server.properties')
 with (out/'server.log').open('w') as log,(out/'rss.csv').open('w') as rss:
  writer=csv.writer(rss);writer.writerow(['elapsed_s','rss_bytes','host_available']);p=subprocess.Popen(cmd,cwd=w,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);start=time.monotonic()
  try:
   while p.poll() is None:
    status=Path(f'/proc/{p.pid}/status')
    try:v=dict(l.split(':',1) for l in status.read_text().splitlines())
    except (FileNotFoundError,ProcessLookupError):
     if p.poll() is not None:break
     raise
    used=int(v.get('VmRSS','0 kB').split()[0])*1024;free=mem()['MemAvailable'];elapsed=time.monotonic()-start;writer.writerow([round(elapsed,3),used,free]);rss.flush()
    if (out/'FAILED').exists():raise RuntimeError((out/'FAILED').read_text())
    if used>4*1024**3 or free<2*1024**3 or elapsed>180:raise RuntimeError('Predefined resource/time stop')
    time.sleep(1)
   assert p.returncode==exitcode,(scenario,p.returncode,exitcode)
   assert (out/expected).exists(),(scenario,'missing '+expected)
   if exitcode==0:assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()
   print('ANYA_DURABLE_CASE_OK',w.name,scenario,'candidate='+str(candidate),flush=True)
  finally:
   if p.poll() is None:
    os.killpg(p.pid,signal.SIGTERM)
    try:p.wait(timeout=15)
    except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)
   for folder in ['logs','crash-reports']:
    if (w/folder).exists():shutil.copytree(w/folder,out/folder,dirs_exist_ok=True)

lane=os.environ['ANYA_DURABLE_LANE']
if lane in ['baseline-first','candidate-first']:
 policies=[False,True] if lane=='baseline-first' else [True,False]
 for i,candidate in enumerate(policies):run(world(str(i)+('-candidate' if candidate else '-baseline')),'measure',candidate)
elif lane=='failure-cancel':
 for mode in ['delayed','failwrite','failpoi','failflush','cancel','revoke']:run(world(mode),mode,True)
elif lane=='cuts-restart':
 for mode,marker in [('shutdown','SHUTDOWN_PENDING'),('cut-before-flush','CUT_BEFORE_FLUSH'),('cut-before-ready','CUT_BEFORE_READY'),('cut-after-ready','CUT_AFTER_READY_BEFORE_ENTRY')]:
  w=world(mode);run(w,mode,True,marker,0 if mode=='shutdown' else 77);run(w,'resume-'+mode,True)
else:raise ValueError(lane)
print('ANYA_DURABLE_LANE_PASSED',lane,flush=True)
