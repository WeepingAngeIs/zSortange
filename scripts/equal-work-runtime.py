"""One controlled finite-work comparison; identical current safety stack and observers."""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
lane=os.environ['ANYA_DURABLE_LANE']
assert lane in ('baseline-first-force','candidate-first-stop')
text=Path('scripts/durable_runtime.py').read_text().split("lane=os.environ['ANYA_DURABLE_LANE']")[0]
text=text.replace("ROOT/'build/durable'","ROOT/'build/equal-work'")
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true','-Danya.serializationBudget=true','-Danya.backpressure=true','-Danya.repairJournal=true','-Danya.drainDiagnostic=true',f'-Danya.budgetSnapshots={64 if candidate else 32}',f'-Danya.equalWork={scenario}',f'-Danya.durableScenario={\"\" if scenario==\"verify\" else \"measure\"}',f'-Xlog:gc*:file={out}/gc.log:time,uptime,level,tags'")
text=text.replace('elapsed>180','elapsed>360')
text=text.replace("if exitcode==0:assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()", "if exitcode==0:\n    assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()\n    assert 'pending=0 callbacks=0 defensive_queue=absent' in (out/'minimal-close-audit').read_text()")
exec(compile(text,'scripts/durable_runtime.py','exec'))
mode='force' if lane.endswith('force') else 'stop'
order=[False,True] if lane.startswith('baseline') else [True,False]
for i,candidate in enumerate(order):
 w=world(f'{i}-cap'+str(64 if candidate else 32))
 run(w,mode,candidate,'CUT_EQUAL_WORK' if mode=='force' else 'EQUAL_DIRTY_TAIL',77 if mode=='force' else 0)
 run(w,'verify',candidate)
print('ANYA_EQUAL_COMPLETED_WORK_PAIR_PASSED',lane,flush=True)
