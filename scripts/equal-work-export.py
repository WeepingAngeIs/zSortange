from pathlib import Path
import subprocess
for p in Path('build/durable-runtime/evidence').rglob('equal-work.jfr'):
 assert p.stat().st_size<=70*1024*1024
 with p.with_suffix('.summary.txt').open('w') as f:subprocess.run(['jfr','summary',str(p)],stdout=f,check=True,timeout=30)
 with p.with_suffix('.json').open('w') as f:subprocess.run(['jfr','print','--json','--stack-depth','48','--events','anya.EqualWorkTick,jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.GarbageCollection,jdk.GCPhasePause,jdk.FileWrite',str(p)],stdout=f,check=True,timeout=90)
