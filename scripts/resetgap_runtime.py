"""Bounded reset-publication cuts/faults. Disposable server fixtures only."""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
case=os.environ['ANYA_DURABLE_LANE']
assert case in ['cut','lock-fail','double-fail','latest-durable','valid-reset']
text=Path('scripts/durable_runtime.py').read_text().split("lane=os.environ['ANYA_DURABLE_LANE']")[0]
text=text.replace("ROOT/'build/durable'","ROOT/'build/resetgap'")
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true','-Danya.serializationBudget=true','-Danya.backpressure=true','-Danya.repairJournal=true',f'-Danya.resetGap={scenario}',f'-Danya.resetGapCase={case}',f'-Danya.durableScenario={\"reset-gap-seed\" if scenario==\"seed\" else \"\"}',f'-Xlog:gc*:file={out}/gc.log:time,uptime,level,tags'")
text=text.replace('elapsed>180','elapsed>200')
exec(compile(text,'scripts/durable_runtime.py','exec'))
w=world('reset-gap-'+case)
run(w,'seed',True,'GAP_PROCESS_CUT',77)
if case=='latest-durable':
 run(w,'verify',True,'GAP_LATEST_REPAIRED_CUT',77)
 run(w,'verify-repaired',True)
else:run(w,'verify',True)
print('ANYA_RESET_GAP_PASSED',case,flush=True)
