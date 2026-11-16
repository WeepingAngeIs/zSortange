"""Bounded lean pairs and repeated dirty cycles; no user worlds or real client in this lane."""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
text=Path('scripts/durable_runtime.py').read_text()
text=text[:text.index("lane=os.environ['ANYA_DURABLE_LANE']")]
text=text.replace("ROOT/'build/durable'", "ROOT/'build/minimal'")
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true',f'-Danya.minimalOwnership={str(candidate).lower()}',f'-Danya.drainDiagnostic={str(scenario==\"diagnostic\").lower()}',f'-Danya.minimalDirty={scenario if scenario in (\"seed\",\"verify\") else \"\"}',f'-Danya.durableScenario={\"\" if scenario==\"verify\" else \"measure\" if scenario in (\"diagnostic\",\"seed\") else scenario}',f'-Xlog:gc*:file={out}/gc.log:time,uptime,level,tags'")
# Dirty cycles use their own 3800-tick cap and bounded 300-second process limit.
text=text.replace('elapsed>180','elapsed>(300 if scenario in ("seed","verify") else 180)')
text=text.replace("if exitcode==0:assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()", "if exitcode==0:\n    assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()\n    assert 'pending=0 callbacks=0 defensive_queue=absent' in (out/'minimal-close-audit').read_text()")
exec(compile(text,'scripts/durable_runtime.py','exec'))
lane=os.environ['ANYA_DURABLE_LANE']
if lane in ('baseline-first','candidate-first'):
    policies=[False,True] if lane=='baseline-first' else [True,False]
    for i,candidate in enumerate(policies):run(world(f'{i}-'+('minimal' if candidate else 'baseline')),'measure',candidate)
    # Separate diagnostic case; never pooled with lean latency comparisons.
    run(world('diagnostic-minimal'),'diagnostic',True)
elif lane=='dirty-cycles':
    for candidate in (False,True):
        w=world('dirty-'+('minimal' if candidate else 'baseline'))
        run(w,'seed',candidate,'CUT_AFTER_DIRTY_CYCLES',77)
        run(w,'verify',candidate)
elif lane=='failure-cancel':
    for mode in ('delayed','failwrite','failpoi','failflush','cancel','revoke'):run(world(mode),mode,True)
elif lane=='cuts-restart':
    for mode,marker in [('shutdown','SHUTDOWN_PENDING'),('cut-before-flush','CUT_BEFORE_FLUSH'),('cut-before-ready','CUT_BEFORE_READY'),('cut-after-ready','CUT_AFTER_READY_BEFORE_ENTRY')]:
        w=world(mode);run(w,mode,True,marker,0 if mode=='shutdown' else 77);run(w,'resume-'+mode,True)
else:raise ValueError(lane)
print('ANYA_MINIMAL_LANE_PASSED',lane,flush=True)
