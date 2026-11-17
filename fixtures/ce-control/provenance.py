"""Validate source/build bytes as data. Does not launch CE or optional integration libraries."""
from __future__ import annotations
import hashlib,json,os,pathlib,shutil,subprocess,sys,tomllib,zipfile
R=pathlib.Path.cwd().resolve();F=R/'fixtures/ce-control';B=R/'build/ce-control';E=B/'evidence';CE=R/'build/ce-source'
SPEC=json.loads((R/'fixtures/renderer-prereq/inputs.json').read_text());AUTH=json.loads((F/'approval.json').read_text())
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def git(*args):return subprocess.check_output(['git','-C',str(CE),*args],text=True).strip()
def blob(p):
 b=p.read_bytes();return hashlib.sha1(b'blob '+str(len(b)).encode()+b'\0'+b).hexdigest()
def prepare():
 assert os.environ.get('GITHUB_ACTIONS')=='true'
 assert git('rev-parse','HEAD')==AUTH['commit'] and not git('status','--porcelain'),'CE checkout changed'
 assert blob(CE/'build.gradle')=='cd026d09ab31728e69e513aefea2269af0b1931b','Pinned build script mismatch'
 assert blob(CE/'src/main/resources/META-INF/neoforge.mods.toml')=='71e2a7ff725130e7842ad7c45dfd101ab9c9336c','Pinned runtime metadata mismatch'
 E.mkdir(parents=True,exist_ok=True);c=B/'compile-inputs';c.mkdir(exist_ok=True)
 v=R/'build/renderer-prereq/provenance/verified-jars'
 for a in SPEC['artifacts']:assert sha(v/a['filename'])==a['sha256'],'Exact renderer bytes changed'
 with zipfile.ZipFile(v/'sodium-neoforge-0.8.13+mc1.21.1.jar') as z:
  names=[n for n in z.namelist() if n.endswith('net.caffeinemc.sodium-neoforge-0.8.13+mc1.21.1-mod.jar')]
  assert len(names)==1;data=z.read(names[0]);expected=next(iter(SPEC['expected_nested'].values()))
  assert len(data)==expected['bytes'] and hashlib.sha256(data).hexdigest()==expected['sha256']
  (c/'sodium-target-implementation.jar').write_bytes(data)
 files=git('ls-files').splitlines();manifest={p:sha(CE/p) for p in files if (CE/p).is_file()}
 (E/'ce-source.json').write_text(json.dumps({'source':AUTH['commit'],'repository':AUTH['repository'],'tree':git('rev-parse','HEAD^{tree}'),'files_sha256':manifest,'execution_scope':AUTH},indent=2))
 for n in ('LICENSE','NOTICE','build.gradle','settings.gradle','gradle.properties'):
  shutil.copy2(CE/n,E/('ce-'+n.replace('.','_')+'.txt'))
 print('CE_SOURCE_AND_EXACT_COMPILE_INPUTS_VERIFIED',AUTH['commit'],flush=True)
def verify():
 assert git('rev-parse','HEAD')==AUTH['commit'];subprocess.run(['git','-C',str(CE),'diff','--exit-code','--','src','build.gradle','settings.gradle','gradle.properties'],check=True)
 jars=[p for p in (CE/'build/libs').glob('*.jar') if not any(x in p.name for x in ('sources','javadoc'))];assert len(jars)==1,jars
 jar=jars[0]
 with zipfile.ZipFile(jar) as z:
  names=z.namelist();assert not any(n.endswith('.jar') for n in names),'STOP: additional bundled executable needs review'
  assert 'LICENSE-Immersive-Portals' in names and 'NOTICE' in names
  assert not any('/gametest/' in n for n in names),'Unexpected upstream fixture in release binary'
  m=tomllib.loads(z.read('META-INF/neoforge.mods.toml').decode());ids={v['modId'] for v in m['mods']}
  assert ids=={'imm_ptl','immersive_portals_core','q_misc_util'} and {v['version'] for v in m['mods']}=={'6.1.4'}
  required={v['modId'] for vs in m['dependencies'].values() for v in vs if v.get('type')=='required'}
  assert required<={'minecraft','neoforge','cloth_config'},'STOP: additional required runtime code needs approval'
 dest=B/'approved-ce.jar';shutil.copy2(jar,dest)
 (E/'ce-binary.json').write_text(json.dumps({'source':AUTH['commit'],'original_filename':jar.name,'bytes':jar.stat().st_size,'sha256':sha(jar),'metadata':m,'build_only_overrides':['NeoForge21.1.249','exact target Sodium0.8.13 nested bytes','exact target Iris/Cloth','no annotation processors','no upstream tests/run harness','no integration runtime staging'],'runtime_inputs':'only this binary plus prior verified target/safety and owned diagnostic fixture'},indent=2))
 (B/'CE_BINARY_VERIFIED').write_text(sha(dest)+'\n');print('APPROVED_CE_BINARY_VERIFIED',sha(dest),flush=True)
if __name__=='__main__':
 {'prepare':prepare,'verify':verify}[sys.argv[1]]()
