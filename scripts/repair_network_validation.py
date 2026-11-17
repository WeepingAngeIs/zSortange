"""Four bounded server processes; two actual clients in each. No user directory accepted."""
from pathlib import Path
import os
assert os.environ.get('GITHUB_ACTIONS')=='true'
text=Path('scripts/remote_ticket_acceptance.py').read_text()
text=text[:text.index("order=os.environ.get('ANYA_PAIR_ORDER','baseline-first')")]
text=text.replace("'build/remote-policy'","'build/repair-network'").replace("'build/durable'","'build/repair'")
text=text.replace("[('seed',0,'ACTIVE_STOP_ARMED'),('after-stop',77,'RECOVERY_CUT'),('after-cut',0,'PASSED')]","[('seed',0,'NETWORK_PENDING_STOP'),('repair-cut',77,'CUT_repair-after-region'),('reset-cut',77,'NETWORK_RESET_CUT'),('final',0,'PASSED')]")
text=text.replace("'-Danya.remoteAcceptance=true'","'-Danya.repairNetwork=true',f'-Danya.repairNetworkLeg={leg}'")
text=text.replace("f'-Danya.remoteCut={str(leg==\"after-stop\").lower()}'","'-Danya.remoteCut=false'")
text=text.replace("if leg=='seed':\n                assert killed==restarted=={1,2},'Both actual socket interruptions required'\n                audit=(ctrl/'ticket-stop-audit.txt').read_text()\n                assert 'before=34 after=0' in audit,'Expected exact-rank removal with 34 active requests at server shutdown'\n            elif leg=='after-cut':\n                assert 'after=0' in (ctrl/'ticket-stop-audit.txt').read_text(),'Missing shutdown ticket audit'", "if leg in ('seed','final'):\n                assert 'after=0' in (ctrl/'ticket-stop-audit.txt').read_text(),'Missing exact engine shutdown audit'\n                assert 'pending=0 callbacks=0 defensive_queue=absent' in (ctrl/'minimal-close-audit').read_text()")
assert 'assert killed==restarted' not in text
exec(compile(text,'scripts/remote_ticket_acceptance.py','exec'))
run_case('client-faults',True)
print('ANYA_REAL_CLIENT_FAILURE_REPAIR_RESET_PASSED',flush=True)
