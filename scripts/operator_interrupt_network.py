"""Bounded genuine-client command interruption and persistence failure. Never replay a confirmation after reconnect."""
from pathlib import Path
import hashlib,json,os,platform,shutil,signal,subprocess,time,urllib.request
ROOT=Path.cwd().resolve();BASE=ROOT/'build/operator-interruption';OUT=BASE/'evidence';LAUNCH=ROOT/'build/acceptance'
assert os.environ.get('GITHUB_ACTIONS')=='true','Disposable GitHub infrastructure required'
CASE=os.environ['ANYA_OPERATOR_INTERRUPT_CASE']
assert CASE in ['receipt-disconnect','intent-disconnect','projection-disconnect','completion-disconnect','ack-disconnect','intent-fail','projection-fail','completion-fail']
OUT.mkdir(parents=True,exist_ok=True)
JAR=ROOT/'build/libs/anya-storage-plus-0.3.0-beta.6.jar'
assert hashlib.sha256(JAR.read_bytes()).hexdigest()=='5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11'
FIXTURE=next(LAUNCH.glob('anya-storage-acceptance-fixture-*.jar'));OPERATOR=next((ROOT/'build/operator-disconnect').glob('*.jar'));TICKETS=next((ROOT/'build/ticket-experiment').glob('*.jar'))
INPUTS=[JAR,FIXTURE,OPERATOR,TICKETS,*sorted((LAUNCH/'dependencies').glob('*.jar'))]
(OUT/'runtime-inputs.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in INPUTS},indent=2))
def output(cmd):return subprocess.run(cmd,text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True).stdout
def memory():return {k:int(v.split()[0])*1024 for k,v in (line.split(':',1) for line in Path('/proc/meminfo').read_text().splitlines())}
(OUT/'host.json').write_text(json.dumps({'uname':platform.uname()._asdict(),'lscpu':output(['lscpu']),'java':output(['java','-version']),'initial_memory':memory(),'runner':{k:os.environ.get(k) for k in ['GITHUB_SHA','GITHUB_RUN_ID','ImageOS','ImageVersion']},'server_heap_mib':2048,'client_heap_mib':1000,'view_distance':3,'simulation_distance':2,'real_clients':2,'startup_ticks':3600,'gameplay_step_ticks':900,'case':CASE,'worker_gate_seconds':8,'operator_commands_replayed':False},indent=2))
RUNTIME=BASE/'runtime';assert not RUNTIME.exists();RUNTIME.mkdir();installer=BASE/'installer.jar'
urllib.request.urlretrieve('https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar',installer)
with (OUT/'installer.log').open('w') as f:subprocess.run(['java','-Xmx768m','-jar',str(installer),'--installServer',str(RUNTIME)],stdout=f,stderr=subprocess.STDOUT,check=True,timeout=240)
world=BASE/'disposable-server';world.mkdir();shutil.copytree(RUNTIME/'libraries',world/'libraries',copy_function=os.link);(world/'mods').mkdir()
for p in INPUTS:shutil.copy2(p,world/'mods'/p.name)
(world/'.anya-remote-fixture').write_text('Created by operator_interrupt_network.py only\n');(world/'eula.txt').write_text('eula=true\n')
props='level-name=world\nlevel-type=minecraft:flat\nlevel-seed=860612\ngenerate-structures=false\nonline-mode=false\nview-distance=3\nsimulation-distance=2\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25577\nallow-flight=true\nmax-tick-time=120000\nop-permission-level=4\n'
(world/'server.properties').write_text(props);(OUT/'server.properties').write_text(props)
control=LAUNCH/'control';assert not control.exists(),'Unexpected earlier control directory'
def terminate(p):
 if p.poll() is None:
  os.killpg(p.pid,signal.SIGTERM)
  try:p.wait(timeout=15)
  except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)
for leg,code,terminal in [('seed',77,'INTERRUPT_NETWORK_CUT'),('restart',0,'PASSED')]:
 out=OUT/leg;out.mkdir();ctrl=out/'control';ctrl.mkdir()
 if control.is_symlink():control.unlink()
 assert not control.exists();control.symlink_to(ctrl,target_is_directory=True)
 procs=[];clients=[];handles=[]
 def launch(cmd,cwd,label,env=None):
  f=(out/(label+'.log')).open('w');handles.append(f);p=subprocess.Popen(cmd,cwd=cwd,env=env,stdout=f,stderr=subprocess.STDOUT,start_new_session=True);procs.append(p);return p
 def failed():
  fs=list(ctrl.glob('FAILED*'))
  if fs:raise RuntimeError('\n'.join(p.name+': '+p.read_text() for p in fs))
 def snapshot_client(role,generation):
  gd=ROOT/f'run-packaged-client-{role}'
  for part in ['logs','screenshots']:
   if (gd/part).exists():shutil.copytree(gd/part,out/f'client-{role}-{generation}-{part}',dirs_exist_ok=True)
 cmd=['java','-Xms256m','-Xmx2048m','-XX:+UseG1GC','-XX:ActiveProcessorCount=2','-Danya.acceptance=true','-Danyastorage.pocketPrototype=true','-Danya.repairNetwork=true','-Danya.operatorNetwork=true',f'-Danya.operatorNetworkLeg={leg}',f'-Danya.operatorInterruption={CASE}','-Danya.remoteTickets=true',f'-Danya.control={control}',f'-Danya.expectedJar={JAR}','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui']
 (out/'command.json').write_text(json.dumps(cmd,indent=2))
 try:
  srv=launch(cmd,world,'server');deadline=time.monotonic()+150
  while not (ctrl/'server-ready').exists():
   failed();assert srv.poll() is None,'Server exited before readiness'
   if time.monotonic()>deadline:raise TimeoutError('Server startup ceiling')
   time.sleep(1)
  def start_client(role,generation):
   cfg=json.loads((LAUNCH/f'client-{role}.json').read_text());gd=Path(cfg['cwd']);gd.mkdir(exist_ok=True)
   (gd/'options.txt').write_text('enableVsync:false\nmaxFps:25\nrenderDistance:3\nsimulationDistance:5\nguiScale:2\npauseOnLostFocus:false\nlang:en_us\n')
   if (gd/'screenshots').exists():shutil.rmtree(gd/'screenshots')
   private=BASE/'native'/leg/role/generation;private.mkdir(parents=True);(private/'tmp').mkdir();(private/'lwjgl').mkdir()
   env=os.environ.copy();env['JAVA_TOOL_OPTIONS']=env.get('JAVA_TOOL_OPTIONS','')+f' -Danya.operatorNetwork=true -Danya.operatorInterruption={CASE} -Djava.io.tmpdir={private}/tmp -Dorg.lwjgl.system.SharedLibraryExtractPath={private}/lwjgl -Dorg.lwjgl.util.DebugLoader=true'
   p=launch(cfg['command'],gd,'client-'+role+'-'+generation,env)
   clients.append((p,role,generation));return p
  alpha=start_client('Alpha','initial');beta=start_client('Beta','initial');killed=False;restarted=False
  start=time.monotonic()
  with (out/'resources.csv').open('w') as f:
   f.write('elapsed_s,server_rss_bytes,total_process_group_rss_bytes,host_available_bytes\n')
   while srv.poll() is None:
    failed()
    if (ctrl/'KILL_ALPHA').exists() and not killed:
     assert leg=='seed' and alpha.poll() is None,'Unexpected kill target'
     os.killpg(alpha.pid,signal.SIGKILL);alpha.wait(timeout=5);snapshot_client('Alpha','initial')
     killed=True;(out/'CLIENT_SOCKET_KILLED').write_text(f'{time.monotonic()-start:.3f}s actual Alpha JVM group SIGKILL at {CASE}\n')
    if (ctrl/'RESTART_ALPHA').exists() and not restarted:
     assert killed,'Restart without observed actual kill'
     start_client('Alpha','reconnect');restarted=True
    for p,role,generation in clients:
     if p==alpha and killed:continue
     if p.poll() is not None:raise RuntimeError(f'Unexpected client exit {role}/{generation}: {p.returncode}')
    rows=[r.split() for r in output(['ps','-e','-o','pid=,pgid=,rss=']).splitlines()];groups={p.pid for p in procs if p.poll() is None};total=sum(int(r[2])*1024 for r in rows if int(r[1]) in groups);rss=sum(int(r[2])*1024 for r in rows if int(r[1])==srv.pid);avail=memory()['MemAvailable']
    f.write(f'{time.monotonic()-start:.3f},{rss},{total},{avail}\n');f.flush()
    if rss>3*1024**3 or total>6*1024**3 or avail<2*1024**3:raise RuntimeError('Protective resource ceiling')
    if time.monotonic()-start>600:raise TimeoutError('Bounded actual-client leg ceiling')
    time.sleep(.25)
  failed();assert srv.returncode==code,(leg,srv.returncode,code);assert (ctrl/terminal).exists(),terminal
  if leg=='seed':assert killed and restarted,'Missing actual disconnect/reconnect'
  if leg=='restart':assert 'exact_engine_owned_tickets=0 jobs=0' in (ctrl/'stop-audit').read_text()
  print('INTERRUPTED_OPERATOR_ACTUAL_CLIENT_LEG_PASSED',CASE,leg,code,flush=True)
 finally:
  for p in procs:terminate(p)
  for h in handles:h.close()
  for role in ['Alpha','Beta']:snapshot_client(role,'final')
  for part in ['logs','crash-reports']:
   if (world/part).exists():shutil.copytree(world/part,out/f'server-{part}',dirs_exist_ok=True)
(OUT/'CASE_PASSED').write_text('Genuine-client interruption/confirmation fault and independent restart; no command replay; existing operator and repair implementation unchanged; original world never accessed\n')
