"""Three real clients; two production pockets; explicit bounded source/packet isolation and independent restart."""
from __future__ import annotations
import sys,hashlib,importlib.util,json,os,pathlib,platform,shutil,signal,subprocess,time
SAFETY='--safety' in sys.argv
assert set(sys.argv[1:])<= {'--safety'},'Unknown fixture arguments'
R=pathlib.Path.cwd().resolve();B=R/'build/live-view';E=B/'evidence';C=B/'control';S=B/'server'
spec=importlib.util.spec_from_file_location('helpers',R/'fixtures/renderer-prereq/run_baseline.py');H=importlib.util.module_from_spec(spec);spec.loader.exec_module(H)
SPEC=json.loads((R/'fixtures/renderer-prereq/inputs.json').read_text());sha=H.sha

def main():
 assert os.environ.get('GITHUB_ACTIONS')=='true'
 assert (R/'build/renderer-prereq/provenance/STATIC_VERIFIED').is_file() and (R/'build/ce-control/CE_BINARY_VERIFIED').is_file()
 S.mkdir(exist_ok=False);C.mkdir(exist_ok=False);E.mkdir(exist_ok=True);(C/'.disposable-live-view').write_text('No supplied worlds, local profiles or persisted view sessions\n')
 jar=R/'build/libs/anya-storage-plus-0.3.0-beta.6.jar';safety=next((R/'build/operator').glob('*.jar'));adapter=next(B.glob('anya-live-view-experiment-*.jar'));fixture=next(B.glob('anya-live-view-fixture-*.jar'));ce=R/'build/ce-control/approved-ce.jar'
 assert sha(jar)==SPEC['anya_sha256'] and sha(safety)==SPEC['safety_sha256'],'Existing playable/safety binary changed'
 assert sha(ce)=='6c58d232328146c9b76ceee1eb53969a149ce97ace5af4b50424a30581bf6187','Previously approved CE binary differs'
 verified=R/'build/renderer-prereq/provenance/verified-jars';mods=S/'mods';mods.mkdir()
 for f in (jar,safety,adapter,fixture,ce,verified/'cloth-config-15.0.140-neoforge.jar'):shutil.copy2(f,mods/f.name)
 launches=json.loads((B/'client-launches.json').read_text())
 ceopts={'maxPortalLayer':1,'indirectLoadingRadiusCap':1,'portalRenderLimit':4,'enableCrossPortalInteraction':False,'enableDatapackPortalGen':False,'enableMirrorCreation':False,'checkModInfoFromInternet':False,'enableUpdateNotification':False,'initialScreenShown':True,'enableCrossPortalSound':False,'netherPortalMode':'vanilla','endPortalMode':'vanilla'}
 for role,v in launches.items():
  d=pathlib.Path(v['cwd']);d.mkdir(exist_ok=True);assert not (d/'saves').exists();m=d/'mods';m.mkdir(exist_ok=False)
  for a in SPEC['artifacts']:
   f=verified/a['filename'];assert sha(f)==a['sha256'];shutil.copy2(f,m/f.name)
  shutil.copy2(ce,m/ce.name);(d/'config').mkdir(exist_ok=True)
  # Mod versions and world distances retained; FPS bound is fixture-only for three software clients.
  opts={**SPEC['profile_options'],'maxFps':25,'pauseOnLostFocus':False,'guiScale':2}
  (d/'options.txt').write_text(''.join(f'{k}:{str(v).lower()}\n' for k,v in opts.items()))
  (d/'config/iris.properties').write_text(''.join(f'{k}={str(v).lower() if isinstance(v,bool) else v}\n' for k,v in SPEC['iris_options'].items()))
  (d/'config/immersive_portals.json').write_text(json.dumps(ceopts))
 (E/'input-hashes.json').write_text(json.dumps({str(p.relative_to(R)):sha(p) for p in (jar,safety,adapter,fixture,ce,*sorted(verified.glob('*.jar')))},indent=2))
 (E/'host.json').write_text(json.dumps({'platform':platform.uname()._asdict(),'logical_cpus':os.cpu_count(),'java':subprocess.run(['java','-version'],stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,check=True).stdout,'real_clients':3,'server_heap_mib':2048,'client_heap_mib':1000,'shader_enabled':False,'renderer':'software Mesa; not target GPU','target_config_differences':['three-client FPS cap 25','fresh captured Sodium advanced defaults'],'view':6,'simulation':5},indent=2))
 url='https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar';data=H.bounded_download(url,16*1024*1024);published=H.bounded_download(url+'.sha256',512).decode().split()[0].lower();assert hashlib.sha256(data).hexdigest()==published
 installer=B/'neoforge-installer.jar';installer.write_bytes(data)
 with (E/'installer.log').open('w') as log:subprocess.run(['java','-Xmx1000m','-jar',str(installer),'--installServer',str(S)],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=240)
 (S/'eula.txt').write_text('eula=true\n');(S/'server.properties').write_text('level-name=world\nlevel-type=minecraft:flat\nlevel-seed=860612\ngenerate-structures=false\nonline-mode=false\nview-distance=6\nsimulation-distance=5\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25579\nallow-flight=true\nmax-tick-time=90000\n')
 (S/'config').mkdir(exist_ok=True);(S/'config/immersive_portals.json').write_text(json.dumps(ceopts))
 (E/'scope.json').write_text(json.dumps({'ce_source':'ad2e7ee5d73e4ff78bd0befade4f797265e65939','adapter':'experimental view-only; no deployment','ce_options':ceopts,'profile_access':False,'allowed_runtime_extra':['anyaliveview','anyalivecheck'],'prior_baseline':'44092751116 preserved not rerun'}))
 proc=[];logs=[];expected=set();samples=[];start=time.monotonic();roles={};success=False
 def launch(command,cwd,name,env=None):
  (E/(name+'-command.json')).write_text(json.dumps({'command':command,'cwd':str(cwd)}));f=(E/(name+'.log')).open('w');logs.append(f);p=subprocess.Popen(command,cwd=cwd,env=env,stdout=f,stderr=subprocess.STDOUT,start_new_session=True);proc.append((p,name));return p
 def client(role,suffix=''):
  v=launches[role];private=B/('native-'+role+suffix);private.mkdir(exist_ok=False);tmp=private/'tmp';tmp.mkdir();native=private/'lwjgl';native.mkdir();env=os.environ.copy();env['JAVA_TOOL_OPTIONS']=f'-Djava.io.tmpdir={tmp} -Dorg.lwjgl.system.SharedLibraryExtractPath={native}'
  p=launch(v['command'],v['cwd'],'client-'+role+suffix,env);roles[role]=p;return p
 def check():
  if (C/'FAILED').exists():raise RuntimeError((C/'FAILED').read_text())
  crashes=list((S/'crash-reports').glob('crash-*.txt'))
  if crashes:raise RuntimeError('Dedicated server crash before acceptance completed; retained crash report: '+crashes[-1].name)
  elapsed=time.monotonic()-start
  if elapsed>1100:raise TimeoutError('1100-second game/restart wall limit')
  available=int(next(v for v in pathlib.Path('/proc/meminfo').read_text().splitlines() if v.startswith('MemAvailable:')).split()[1])*1024
  rss={n:H.group_rss(p.pid) for p,n in proc};samples.append({'seconds':elapsed,'rss_bytes':rss,'host_available_bytes':available})
  if max([v for n,v in rss.items() if n.startswith('server')],default=0)>3*1024**3 or sum(rss.values())>8*1024**3 or available<2*1024**3:raise RuntimeError('Bounded resource guard')
  for p,n in proc:
   if p.poll() is not None and p.pid not in expected:raise RuntimeError(f'Unexpected exit {n}: {p.returncode}')
 def wait(marker,seconds):
  until=time.monotonic()+seconds
  while not (C/marker).exists():check();assert time.monotonic()<until,'Bounded wait '+marker;time.sleep(.5)
 flags=['-Danyastorage.pocketPrototype=true','-Danyastorage.liveViewPrototype=true','-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true','-Danya.serializationBudget=true','-Danya.backpressure=true','-Danya.repairJournal=true',f'-Danya.liveControl={C}',f'-Danya.control={C}']
 if SAFETY:flags.append('-Danya.liveSafety=true')
 try:
  srv=launch(['java','-Xms256m','-Xmx2048m',*flags,'@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui'],S,'server-main');wait('server-ready',180)
  for role in ('Alpha','Beta','Gamma'):client(role)
  killed=restarted=False
  while not (C/'PASSED').exists():
   check()
   if (C/'kill-beta').exists() and not killed:expected.add(roles['Beta'].pid);os.killpg(roles['Beta'].pid,signal.SIGKILL);roles['Beta'].wait(timeout=15);killed=True
   if killed and not restarted and (C/'restart-beta').exists():client('Beta','-reconnect');restarted=True
   time.sleep(.5)
  assert killed and restarted,'Real socket cycle did not run';expected.add(srv.pid);assert srv.wait(timeout=60)==0
  for p,n in proc:
   if n.startswith('client') and p.poll() is None:expected.add(p.pid);os.killpg(p.pid,signal.SIGTERM)
  for p,n in proc:
   if n.startswith('client'):
    try:p.wait(timeout=15)
    except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)
  if SAFETY:
   assert (C/'SAFETY_PASSED').exists(),'Safety scenario missing evidence'
   resume=launch(['java','-Xms256m','-Xmx2048m',*flags,'-Danya.liveSafetyVerify=true','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui'],S,'server-safety-restart');wait('RESTART_SAFETY_PASSED',180);expected.add(resume.pid);assert resume.wait(timeout=45)==0
   success=True;print('ANYA_SCOPED_LIVE_VIEW_PERSISTENCE_SAFETY_PASSED',flush=True)
  else:
   resume=launch(['java','-Xms256m','-Xmx2048m',*flags,'-Danya.liveResume=true','@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui'],S,'server-restart');wait('RESTART_PASSED',180);expected.add(resume.pid);assert resume.wait(timeout=45)==0
   assert (C/'RESTART_ITEMS').exists();success=True;print('ANYA_SCOPED_LIVE_VIEW_NETWORK_ACCEPTANCE_PASSED',flush=True)
 finally:
  for p,n in proc:
   if p.poll() is None:os.killpg(p.pid,signal.SIGTERM)
  for p,n in proc:
   try:p.wait(timeout=10)
   except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)
  for f in logs:f.close()
  (E/'resources.json').write_text(json.dumps(samples,indent=2));(E/'runtime-result.json').write_text(json.dumps({'passed':success,'ce_source':'ad2e7ee5d73e4ff78bd0befade4f797265e65939','real_clients':3,'production':False}))
  shutil.copytree(C,E/'control',dirs_exist_ok=True)
  for role,v in launches.items():
   d=pathlib.Path(v['cwd'])
   for name in ['logs','crash-reports','screenshots','config']:
    if (d/name).exists():shutil.copytree(d/name,E/('client-'+role)/name,dirs_exist_ok=True)
  if (S/'logs').exists():shutil.copytree(S/'logs',E/'server-logs',dirs_exist_ok=True)
  if (S/'crash-reports').exists():shutil.copytree(S/'crash-reports',E/'server-crash-reports',dirs_exist_ok=True)
  if SAFETY and (S/'world').exists():
   # Preserve the entire disposable failure world, including WAL and primary
   # regions. Never replace it with a repaired/reset projection.
   shutil.copytree(S/'world',E/'failed-world',dirs_exist_ok=True)
   (E/'failed-world-hashes.json').write_text(json.dumps({str(p.relative_to(E/'failed-world')):sha(p) for p in sorted((E/'failed-world').rglob('*')) if p.is_file()},indent=2))
if __name__=='__main__':main()
