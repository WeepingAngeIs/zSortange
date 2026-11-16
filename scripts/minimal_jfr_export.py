from pathlib import Path
import subprocess
root=Path('build/durable-runtime/evidence')
for p in root.rglob('drainage.jfr'):
    assert p.stat().st_size<=100*1024*1024,'Recording exceeded diagnostic bound'
    with p.with_suffix('.summary.txt').open('w') as f:subprocess.run(['jfr','summary',str(p)],stdout=f,check=True,timeout=30)
    events='anya.MinimalDrainTick,jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ThreadPark,jdk.JavaMonitorEnter,jdk.FileRead,jdk.FileWrite,jdk.GarbageCollection,jdk.GCPhasePause'
    with p.with_suffix('.json').open('w') as f:subprocess.run(['jfr','print','--json','--stack-depth','64','--events',events,str(p)],stdout=f,check=True,timeout=90)
    print('EXPORTED_DIAGNOSTIC_RECORDING',p)
