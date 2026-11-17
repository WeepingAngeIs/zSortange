"""Deterministic healthy write/proof/reset ordering, only disposable server fixtures."""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
text=Path('scripts/durable_runtime.py').read_text().split("lane=os.environ['ANYA_DURABLE_LANE']")[0]
text=text.replace("ROOT/'build/durable'","ROOT/'build/healthy'")
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true','-Danya.serializationBudget=true','-Danya.backpressure=true','-Danya.repairJournal=true',f'-Danya.healthyRace={scenario}',f'-Danya.healthyLane={os.environ[\"ANYA_DURABLE_LANE\"]}',f'-Danya.durableScenario={\"healthy-seed\" if scenario==\"seed\" else \"\"}',f'-Xlog:gc*:file={out}/gc.log:time,uptime,level,tags'")
text=text.replace('elapsed>180','elapsed>240')
exec(compile(text,'scripts/durable_runtime.py','exec'))
w=world('healthy-ordering')
run(w,'seed',True,'CUT_NEWEST_HEALTHY_PROOF',77)
run(w,'verify-proof',True,'CUT_HEALTHY_RESET_PUBLICATION',77)
run(w,'verify-reset',True)
print('ANYA_HEALTHY_REPAIR_ORDER_PASSED',os.environ['ANYA_DURABLE_LANE'],flush=True)
