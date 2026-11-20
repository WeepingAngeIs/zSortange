"""Rerun only the previously interrupted candidate lifecycle with identical mod binaries.
The baseline leg already passed at the measured source; do not duplicate that case as new evidence.
"""
from pathlib import Path
text=Path('scripts/remote_ticket_acceptance.py').read_text()
marker="order=os.environ.get('ANYA_PAIR_ORDER','baseline-first')"
assert text.count(marker)==1
exec(compile(text[:text.index(marker)],'scripts/remote_ticket_acceptance.py','exec'))
run_case('candidate',True)
print('ANYA_UNLOAD_TRANSITION_REPAIR_OK: candidate full real-client seed/shutdown/restart/recovery-cut/final-cleanup; original baseline case is separate evidence',flush=True)
