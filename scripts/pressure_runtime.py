"""Finite sustained-arrival compare. Refuses non-CI and reusing supplied worlds; no real clients."""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
text=Path('scripts/durable_runtime.py').read_text();text=text[:text.index("lane=os.environ['ANYA_DURABLE_LANE']")]
text=text.replace("ROOT/'build/durable'","ROOT/'build/pressure'")
text=text.replace("'fixture_players_max':8","'fixture_players_max':20")
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true','-Danya.serializationBudget=true',f'-Danya.backpressure={str(candidate).lower()}',f'-Danya.pressureScenario={scenario}',f'-Danya.budgetSnapshots={1 if scenario in (\"stop\",\"force\") else 32}',f'-Xlog:gc*:file={out}/gc.log:time,uptime,level,tags'")
text=text.replace('elapsed>180','elapsed>620')
text=text.replace("if exitcode==0:assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()", "if exitcode==0:\n    assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()\n    assert 'pending=0 callbacks=0 defensive_queue=absent' in (out/'minimal-close-audit').read_text()")
exec(compile(text,'scripts/durable_runtime.py','exec'))
lane=os.environ['ANYA_DURABLE_LANE']
if lane in ('baseline-first','candidate-first'):
 for i,candidate in enumerate([False,True] if lane=='baseline-first' else [True,False]):
  w=world(f'{i}-'+('backpressure' if candidate else 'save-budget-baseline'))
  run(w,'stress',candidate,'CUT_AFTER_SUSTAINED_FLUSH',77);run(w,'verify',candidate)
elif lane=='failure':
 for scenario in ('write-fault','flush-fault'):
  w=world(scenario);run(w,scenario,True,'PRESSURE_FAULT_OK');run(w,'verify',True)
elif lane=='stop-force':
 for scenario,marker,code in [('stop','PRESSURE_STOP_WITH_BACKLOG',0),('force','PRESSURE_FORCE_OK',77)]:
  w=world(scenario);run(w,scenario,True,marker,code);run(w,'verify',True)
else:raise ValueError(lane)
print('ANYA_BACKPRESSURE_LANE_PASSED',lane,flush=True)
