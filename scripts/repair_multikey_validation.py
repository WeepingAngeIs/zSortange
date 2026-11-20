"""New multikey, stale-token, quota and corruption tests, not connected-client coverage."""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
text=Path('scripts/durable_runtime.py').read_text().split("lane=os.environ['ANYA_DURABLE_LANE']")[0]
text=text.replace("ROOT/'build/durable'","ROOT/'build/repair'")
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true','-Danya.serializationBudget=true','-Danya.backpressure=true','-Danya.repairJournal=true',f'-Danya.multiRepair={scenario}',f'-Danya.durableScenario={\"\" if scenario.startswith(\"verify\") else \"measure\"}'")
text=text.replace('elapsed>180','elapsed>360')
exec(compile(text,'scripts/durable_runtime.py','exec'))
lane=os.environ['ANYA_DURABLE_LANE']
if lane=='multi':
    w=world('nine-keys');run(w,'seed',True,'CUT_RESET_PUBLISHED',77);run(w,'verify',True)
elif lane=='full':
    w=world('full');run(w,'full',True,'CUT_FULL_FALLBACK',77);run(w,'verify-full',True)
elif lane=='corrupt':
    w=world('corrupt');run(w,'corrupt',True,'CUT_CORRUPT_TARGET',77)
    marker=(OUT/'corrupt-corrupt/CUT_CORRUPT_TARGET').read_text();key=marker.split('key=')[1].split()[0]
    rec=w/'world/anya_repair_experiment'/f'{key}.wal';data=rec.read_bytes();bad=data[:-1]+bytes([data[-1]^1]);rec.write_bytes(bad)
    (OUT/'corruption.json').write_text(json.dumps({'fixture_only':True,'key':key,'original_sha256':hashlib.sha256(data).hexdigest(),'corrupt_sha256':hashlib.sha256(bad).hexdigest(),'length':len(data)},indent=2))
    run(w,'verify-corrupt',True);assert rec.read_bytes()==bad,'Corrupt evidence was modified/deleted by startup'
else:raise ValueError(lane)
print('ANYA_MULTI_REPAIR_VALIDATION_PASSED',lane,flush=True)
