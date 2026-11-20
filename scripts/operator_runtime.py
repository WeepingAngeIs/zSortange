"""Read-only operator inspection and bounded explicit resolution, no actual clients."""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
case=os.environ['ANYA_DURABLE_LANE']
assert case in ['resolve','uncertain','disagreement','intent-fail','completion-fail','cut-projection','cut-complete']
text=Path('scripts/durable_runtime.py').read_text().split("lane=os.environ['ANYA_DURABLE_LANE']")[0]
text=text.replace("ROOT/'build/durable'","ROOT/'build/operator'")
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true','-Danya.serializationBudget=true','-Danya.backpressure=true','-Danya.repairJournal=true',f'-Danya.operatorProbe={scenario}',f'-Danya.operatorCase={case}',f'-Danya.durableScenario={\"reset-gap-seed\" if scenario==\"seed\" else \"\"}',f'-Xlog:gc*:file={out}/gc.log:time,uptime,level,tags'")
text=text.replace('elapsed>180','elapsed>200')
exec(compile(text,'scripts/durable_runtime.py','exec'))
w=world('operator-'+case)
run(w,'seed',True,'OPERATOR_SEED_CUT',77)
if case in ['uncertain','disagreement']:run(w,'inspect',True)
else:
 run(w,'inspect',True,'OPERATOR_RESOLUTION_CUT',77)
 run(w,'finish',True)
print('ANYA_OPERATOR_RESOLUTION_PASSED',case,flush=True)
