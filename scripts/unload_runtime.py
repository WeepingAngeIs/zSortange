"""Reuse bounded durability fixtures with one changed variable: deferred unload retry.
Both measurement policies use the exact prior nonblocking preparation prototype.
"""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
text=Path('scripts/durable_runtime.py').read_text()
old="f'-Danya.nonblockingPrepare={str(candidate).lower()}'"
assert text.count(old)==1
text=text.replace(old,"'-Danya.nonblockingPrepare=true','-Danya.unloadExperiment=true',f'-Danya.deferUnload={str(candidate).lower()}'")
text=text.replace("if exitcode==0:assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()", "if exitcode==0:\n    assert 'exact_engine_owned_tickets=0 jobs=0' in (out/'stop-audit').read_text()\n    assert 'deferred=0' in (out/'unload-close-audit').read_text()\n    assert 'overflow=0' in (out/'unload-close-audit').read_text()")
exec(compile(text,'scripts/durable_runtime.py','exec'))
