"""New sustained queued/executing repair races; all player connections are server fixtures."""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
text=Path('scripts/durable_runtime.py').read_text().split("lane=os.environ['ANYA_DURABLE_LANE']")[0]
text=text.replace("ROOT/'build/durable'","ROOT/'build/churn'")
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true','-Danya.serializationBudget=true','-Danya.backpressure=true','-Danya.repairJournal=true',f'-Danya.repairChurn={scenario}',f'-Danya.churnReverse={str(os.environ[\"ANYA_DURABLE_LANE\"]==\"reverse\").lower()}',f'-Danya.durableScenario={\"measure\" if scenario==\"seed\" else \"\"}',f'-Xlog:gc*:file={out}/gc.log:time,uptime,level,tags'")
text=text.replace('elapsed>180','elapsed>480')
exec(compile(text,'scripts/durable_runtime.py','exec'))
w=world('bounded-churn')
run(w,'seed',True,'CHURN_STOP_PENDING',0)
run(w,'resume',True,'CUT_repair-after-region',77)
run(w,'after-repair-cut',True,'CHURN_CUT_RESET',77)
run(w,'final',True)
print('ANYA_SUSTAINED_REPAIR_CHURN_PASSED',os.environ['ANYA_DURABLE_LANE'],flush=True)
