"""Bounded one-client CE control, never a user profile or an Anya live-view adapter."""
from __future__ import annotations
import hashlib,importlib.util,json,os,pathlib,platform,shutil,signal,subprocess,time,zipfile
R=pathlib.Path.cwd().resolve();B=R/'build/ce-control';E=B/'evidence';C=B/'control';S=B/'server';D=B/'client'
sp=importlib.util.spec_from_file_location('previous_control_helpers',R/'fixtures/renderer-prereq/run_baseline.py');helpers=importlib.util.module_from_spec(sp);sp.loader.exec_module(helpers)
SPEC=json.loads((R/'fixtures/renderer-prereq/inputs.json').read_text());AUTH=json.loads((R/'fixtures/ce-control/approval.json').read_text())
sha=helpers.sha

def main():
 assert os.environ.get('GITHUB_ACTIONS')=='true';assert AUTH['commit']=='ad2e7ee5d73e4ff78bd0befade4f797265e65939'
 assert (R/'build/renderer-prereq/provenance/STATIC_VERIFIED').is_file() and (B/'CE_BINARY_VERIFIED').is_file()
 S.mkdir(exist_ok=False);C.mkdir(exist_ok=False);D.mkdir(exist_ok=True);E.mkdir(exist_ok=True)
 (C/'.disposable-approved-ce').write_text('Approved pinned CE control; fresh generated world only\n')
 assert not (D/'saves').exists(),'Existing client saves refused'
 jar=R/'build/libs/anya-storage-plus-0.3.0-beta.6.jar';safety=next((R/'build/operator').glob('*.jar'));fixture=next(B.glob('anya-ce-renderer-control-*.jar'));ce=B/'approved-ce.jar'
 assert sha(jar)==SPEC['anya_sha256'] and sha(safety)==SPEC['safety_sha256'],'Existing safety/playable binary changed'
 assert sha(ce)==(B/'CE_BINARY_VERIFIED').read_text().strip()
 verified=R/'build/renderer-prereq/provenance/verified-jars';cloth=verified/'cloth-config-15.0.140-neoforge.jar'
 mods=S/'mods';mods.mkdir()
 for p in (jar,safety,fixture,ce,cloth):shutil.copy2(p,mods/p.name)
 client_mods=D/'mods';client_mods.mkdir(exist_ok=False)
 for a in SPEC['artifacts']:
  p=verified/a['filename'];assert sha(p)==a['sha256'];shutil.copy2(p,client_mods/p.name)
 shutil.copy2(ce,client_mods/ce.name)
 (E/'input-hashes.json').write_text(json.dumps({str(p.relative_to(R)):sha(p) for p in (jar,safety,fixture,ce,*sorted(verified.glob('*.jar')))},indent=2))
 (E/'host.json').write_text(json.dumps({'platform':platform.uname()._asdict(),'logical_cpus':os.cpu_count(),'java':subprocess.run(['java','-version'],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=True).stdout,'server_heap_mib':2048,'client_heap_mib':1000,'rss_accounting':'sum complete process-group /proc VmRSS, shared pages not deduplicated','ce_source':AUTH['commit'],'real_clients':1,'target_gpu':False},indent=2))
 installer=B/'neoforge-installer.jar';url='https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.249/neoforge-21.1.249-installer.jar'
 data=helpers.bounded_download(url,16*1024*1024);digest=helpers.bounded_download(url+'.sha256',512).decode().split()[0].lower();assert hashlib.sha256(data).hexdigest()==digest;installer.write_bytes(data)
 (E/'installer-provenance.json').write_text(json.dumps({'url':url,'sha256':digest,'bytes':len(data)},indent=2))
 with (E/'installer.log').open('w') as log:subprocess.run(['java','-Xmx1000m','-jar',str(installer),'--installServer',str(S)],stdout=log,stderr=subprocess.STDOUT,check=True,timeout=240)
 (S/'eula.txt').write_text('eula=true\n');(S/'server.properties').write_text('level-name=world\nlevel-type=minecraft:flat\nlevel-seed=860612\ngenerate-structures=false\nonline-mode=false\nview-distance=6\nsimulation-distance=5\nspawn-protection=0\nserver-ip=127.0.0.1\nserver-port=25579\nallow-flight=true\nmax-tick-time=60000\n')
 opts={**SPEC['profile_options'],'pauseOnLostFocus':False,'guiScale':2};(D/'options.txt').write_text(''.join(f'{k}:{str(v).lower()}\n' for k,v in opts.items()))
 ceopts={'maxPortalLayer':1,'indirectLoadingRadiusCap':1,'portalRenderLimit':2,'enableCrossPortalInteraction':False,'enableDatapackPortalGen':False,'enableMirrorCreation':False,'checkModInfoFromInternet':False,'enableUpdateNotification':False,'initialScreenShown':True,'enableCrossPortalSound':False,'netherPortalMode':'vanilla','endPortalMode':'vanilla'}
 for root in (S,D):
  (root/'config').mkdir(exist_ok=True);(root/'config/immersive_portals.json').write_text(json.dumps(ceopts,indent=2))
 (D/'config/iris.properties').write_text(''.join(f'{k}={str(v).lower() if isinstance(v,bool) else v}\n' for k,v in SPEC['iris_options'].items()))
 (E/'config-boundaries.json').write_text(json.dumps({'iris':SPEC['iris_options'],'vanilla':opts,'ce_disposable_only':ceopts,'sodium_options':'fresh captured defaults; not complete target advanced JSON','shader_zip':'not downloaded','no_user_profile_access':True,'fixture_portal':'non-global, one recipient, no teleport/interaction/cross collision','adapter':'not implemented'},indent=2))
 processes=[];logs=[];resources=[];start=time.monotonic();success=False
 def launch(command,cwd,name,env=None):
  (E/(name+'-command.json')).write_text(json.dumps({'command':command,'cwd':str(cwd)},indent=2));log=(E/(name+'.log')).open('w');logs.append(log)
  p=subprocess.Popen(command,cwd=cwd,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True);processes.append((p,name));return p
 def check(finishing=False):
  if (C/'FAILED').exists():raise RuntimeError((C/'FAILED').read_text())
  elapsed=time.monotonic()-start
  if elapsed>480:raise TimeoutError('480-second game runtime bound')
  available=int(next(v for v in pathlib.Path('/proc/meminfo').read_text().splitlines() if v.startswith('MemAvailable:')).split()[1])*1024
  rss={n:helpers.group_rss(p.pid) for p,n in processes};resources.append({'seconds':elapsed,'rss_bytes':rss,'host_available_bytes':available})
  if rss.get('server',0)>3*1024**3 or sum(rss.values())>6*1024**3 or available<2*1024**3:raise RuntimeError('Resource guard')
  for p,n in processes:
   if p.poll() is not None and not finishing and not (C/'client-finished').exists():raise RuntimeError(f'{n} exited {p.returncode} before controls finished')
 try:
  flags=['-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true','-Danya.serializationBudget=true','-Danya.backpressure=true','-Danya.repairJournal=true',f'-Danya.ceControl={C}',f'-Danya.control={C}']
  server=launch(['java','-Xms256m','-Xmx2048m',*flags,'@libraries/net/neoforged/neoforge/21.1.249/unix_args.txt','nogui'],S,'server')
  deadline=time.monotonic()+150
  while not (C/'server-ready').exists():check();assert time.monotonic()<deadline,'Server startup ceiling';time.sleep(.5)
  launchspec=json.loads((B/'client-launch.json').read_text());native=B/'native';native.mkdir(exist_ok=False);env=os.environ.copy();env['JAVA_TOOL_OPTIONS']=f'-Djava.io.tmpdir={native} -Dorg.lwjgl.system.SharedLibraryExtractPath={native}/lwjgl'
  client=launch(launchspec['command'],launchspec['cwd'],'client',env)
  while not (C/'client-finished').exists():check();time.sleep(.5)
  assert client.wait(timeout=30)==0
  deadline=time.monotonic()+90
  while server.poll() is None:check(True);assert time.monotonic()<deadline,'Destination drainage/stop limit';time.sleep(.5)
  assert server.returncode==0 and (C/'destination-drained').is_file() and (C/'server-stopped').is_file();check(True)
  names=['portal-red','portal-green','occluded-blue','restored-green','removed-blue'];pixels={n:helpers.png_rgb(D/'screenshots'/(n+'.png')) for n in names}
  for name,rec in pixels.items():
   r,g,b=rec['center_mean_rgb']
   if name=='portal-red':assert r>g*1.3 and r>b*1.3,(name,rec)
   elif 'green' in name:assert g>r*1.15 and g>b*1.3,(name,rec)
   else:assert b>r*1.3 and b>g*1.15,(name,rec)
  mods=json.loads((C/'runtime-mods.json').read_text());by={m['id']:m for m in mods};assert set(by)==set(AUTH['runtime_allowlist']),set(by)
  for a in SPEC['artifacts']:assert by[a['mod_id']]['version']==a['mod_version']
  for id in ['imm_ptl','immersive_portals_core','q_misc_util']:assert by[id]['version']=='6.1.4'
  assert by['neoforge']['version']=='21.1.249' and by['fabric_renderer_api_v1']['version']=='3.4.1+9125b6dc19'
  (E/'runtime-result.json').write_text(json.dumps({'status':'PASS_PINNED_CE_RENDERER_CONTROL_ONLY','pixels':pixels,'source_world_control':'blue in every stage','source_dimension':'minecraft:overworld','destination_dimension':'anyacecontrol:target','notAnyaPermissionAdapter':True,'ce_sha256':sha(ce),'actual_client_count':1,'target_gpu_proof':False,'resolved_mods':mods},indent=2))
  success=True;print('ANYA_APPROVED_CE_RENDERER_CONTROL_PASSED',sha(ce),flush=True)
 finally:
  for p,n in processes:
   if p.poll() is None:os.killpg(p.pid,signal.SIGTERM)
  for p,n in processes:
   try:p.wait(timeout=10)
   except subprocess.TimeoutExpired:os.killpg(p.pid,signal.SIGKILL);p.wait(timeout=10)
  for h in logs:h.close()
  (E/'resources.json').write_text(json.dumps(resources,indent=2))
  for src,dst in [(C,E/'control'),(D/'screenshots',E/'screenshots'),(D/'logs',E/'client-logs'),(S/'logs',E/'server-logs')]:
   if src.exists():shutil.copytree(src,dst,dirs_exist_ok=True)
  for root,label in ((D,'client'),(S,'server')):
   for n in ('iris.properties','sodium-options.json','immersive_portals.json'):
    p=root/'config'/n
    if p.exists():shutil.copy2(p,E/(label+'-'+n))
  print('CE_CONTROL_SUCCESS='+str(success),flush=True)
if __name__=='__main__':main()
