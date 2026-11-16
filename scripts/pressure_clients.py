from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
name=os.environ['ANYA_PRESSURE_CLIENT_SCENARIO']
script={'room':'scripts/packaged_acceptance.py','cold-recovery':'scripts/cold_recovery_acceptance.py','transitions':'scripts/remote_ticket_acceptance.py'}[name]
text=Path(script).read_text();assert text.count("'build/durable'")==1
text=text.replace("'build/durable'","'build/pressure'")
if name=='transitions':
 marker="order=os.environ.get('ANYA_PAIR_ORDER','baseline-first')";assert text.count(marker)==1
 text=text[:text.index(marker)]+"\nrun_case('candidate',True)\nprint('ANYA_PRESSURE_CLIENT_TRANSITIONS_PASSED',flush=True)\n"
exec(compile(text,script,'exec'))
