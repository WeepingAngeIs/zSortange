"""Bounded old-minimal vs save-budget comparisons; fixtures are not network clients."""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
text=Path('scripts/durable_runtime.py').read_text()
text=text[:text.index("lane=os.environ['ANYA_DURABLE_LANE']")]
text=text.replace("ROOT/'build/durable'", "ROOT/'build/budget'")
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true','-Danya.minimalOwnership=true',f'-Danya.serializationBudget={str(candidate).lower()}',f'-Danya.drainDiagnostic={str(scenario==\"diagnostic\").lower()}',f'-Danya.minimalDirty={scenario if scenario in (\"seed\",\"verify\") else \"\"}',f'-Danya.budgetBacklog={scenario.split(\"backlog-\")[1] if scenario.startswith(\"backlog-\") else \"\"}',f'-Danya.budgetSnapshots={1 if scenario in (\"backlog-stop\",\"backlog-force\") else 32}',f'-Danya.durableScenario={\"\" if scenario in (\"verify\",\"backlog-verify\") else \"measure\" if scenario in (\"diagnostic\",\"seed\",\"backlog-stop\",\"backlog-force\") else scenario}',f'-Xlog:gc*:file={out}/gc.log:time,uptime,level,tags'")
text=text.replace('elapsed>180','elapsed>(300 if scenario in ("seed","verify") or scenario.startswith("backlog-") else 180)')
text=text.replace("if exitcode==0:assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()", "if exitcode==0:\n    assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()\n    assert 'pending=0 callbacks=0 defensive_queue=absent' in (out/'minimal-close-audit').read_text()\n    assert 'own_queue=none' in (out/'budget-final').read_text()")
exec(compile(text,'scripts/durable_runtime.py','exec'))
lane=os.environ['ANYA_DURABLE_LANE']
if lane in ('baseline-first','candidate-first'):
    policies=[False,True] if lane=='baseline-first' else [True,False]
    for i,candidate in enumerate(policies):run(world(f'{i}-'+('budget' if candidate else 'minimal-baseline')),'measure',candidate)
    run(world('diagnostic-budget'),'diagnostic',True)
elif lane=='dirty-cycles':
    for candidate in (False,True):
        w=world('dirty-'+('budget' if candidate else 'minimal-baseline'))
        run(w,'seed',candidate,'CUT_AFTER_DIRTY_CYCLES',77)
        run(w,'verify',candidate)
elif lane=='backlog-stop-force':
    for scenario,marker,exitcode in [('backlog-stop','STOP_WITH_BACKLOG',0),('backlog-force','CUT_AFTER_BUDGET_FORCE',77)]:
        w=world(scenario);run(w,scenario,True,marker,exitcode);run(w,'backlog-verify',True)
elif lane=='failure-cancel':
    for mode in ('delayed','failwrite','failpoi','failflush','cancel','revoke'):run(world(mode),mode,True)
elif lane=='cuts-restart':
    for mode,marker in [('shutdown','SHUTDOWN_PENDING'),('cut-before-flush','CUT_BEFORE_FLUSH'),('cut-before-ready','CUT_BEFORE_READY'),('cut-after-ready','CUT_AFTER_READY_BEFORE_ENTRY')]:
        w=world(mode);run(w,mode,True,marker,0 if mode=='shutdown' else 77);run(w,'resume-'+mode,True)
else:raise ValueError(lane)
print('ANYA_SAVE_BUDGET_LANE_PASSED',lane,flush=True)
