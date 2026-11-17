"""Disposable full-snapshot repair and restart cases. Fixture identities are NOT network clients."""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
text=Path('scripts/durable_runtime.py').read_text()
text=text[:text.index("lane=os.environ['ANYA_DURABLE_LANE']")]
text=text.replace("ROOT/'build/durable'", "ROOT/'build/repair'")
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true','-Danya.serializationBudget=true','-Danya.backpressure=true',f'-Danya.repairJournal={str(candidate).lower()}',f'-Danya.repairScenario={scenario}',f'-Danya.durableScenario={\"\" if scenario.startswith(\"verify\") else \"measure\"}',f'-Xlog:gc*:file={out}/gc.log:time,uptime,level,tags'")
text=text.replace('elapsed>180','elapsed>300')
text=text.replace("if exitcode==0:assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()", "if exitcode==0:\n    assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()\n    assert 'pending=0 callbacks=0 defensive_queue=absent' in (out/'minimal-close-audit').read_text()")
exec(compile(text,'scripts/durable_runtime.py','exec'))
lane=os.environ['ANYA_DURABLE_LANE']
if lane in ('baseline-first','candidate-first'):
    policies=[False,True] if lane=='baseline-first' else [True,False]
    for i,candidate in enumerate(policies):
        w=world(f'{i}-'+('journal' if candidate else 'budget-baseline'))
        run(w,'measure',candidate,'CUT_MEASURED_SAVES',77);run(w,'verify',candidate)
elif lane=='failed-write':
    for candidate,mode in [(False,'write'),(True,'write'),(True,'flush')]:
        w=world(mode+'-'+str(candidate));run(w,mode,candidate,'CUT_FAILED_WRITE',77);run(w,'verify',candidate)
elif lane=='recovery-retry':
    w=world('live');run(w,'live',True,'CUT_LIVE_REPAIRED',77);run(w,'verify',True)
elif lane=='cuts-stop':
    w=world('journal-cut');run(w,'cut-after-journal',True,'CUT_after-journal',77)
    run(w,'verify-cut',True,'CUT_repair-after-region',77);run(w,'verify',True)
    w=world('region-cut');run(w,'cut-after-region',True,'CUT_after-region',77);run(w,'verify',True)
    w=world('stop-pending');run(w,'stop',True,'STOP_PENDING_REPAIR',0);run(w,'verify',True)
elif lane=='no-durable-copy':
    w=world('dual');run(w,'dual',True,'CUT_FAILED_WRITE',77);run(w,'verify-dual',True)
else:raise ValueError(lane)
print('ANYA_REPAIR_LANE_PASSED',lane,flush=True)
