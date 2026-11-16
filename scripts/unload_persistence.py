"""Named dirty chest data must survive actual unload, explicit flush, forced termination and restart.
Artificial generation reference is explicitly enabled only in these test cases, not measurement/client tests.
"""
from pathlib import Path
text=Path('scripts/durable_runtime.py').read_text()
text=text[:text.index("lane=os.environ['ANYA_DURABLE_LANE']")]
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}',f'-Danya.durableScenario={scenario}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true','-Danya.unloadExperiment=true','-Danya.deferUnload=true',f'-Danya.unloadPersistence={scenario}',f'-Danya.durableScenario={\"\" if scenario.startswith(\"verify\") else \"measure\"}'")
text=text.replace("if exitcode==0:assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()", "if exitcode==0:\n    assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()\n    assert 'deferred=0' in (out/'unload-close-audit').read_text()\n    assert 'overflow=0' in (out/'unload-close-audit').read_text()")
exec(compile(text,'scripts/durable_runtime.py','exec'))
for mode in ['hold','resurrect']:
 w=world(mode)
 run(w,mode,True,'CUT_AFTER_DIRTY_UNLOAD',77)
 run(w,'verify-'+mode,True)
print('ANYA_DIRTY_UNLOAD_PERSISTENCE_PASSED: owned wait/rearm and resurrection/retirement; 104 named items, forced termination, new server; no arbitrary power-loss guarantee',flush=True)
