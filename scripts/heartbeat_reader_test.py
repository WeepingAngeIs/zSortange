"""Extract only the pure heartbeat reader, avoiding launch-time script side effects."""
from pathlib import Path
import ast,tempfile
source=Path('scripts/remote_ticket_acceptance.py').read_text()
tree=ast.parse(source)
functions=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name=='heartbeat_phase']
assert len(functions)==1
namespace={'Path':Path}
exec(compile(ast.Module(body=functions,type_ignores=[]),'heartbeat-reader-test','exec'),namespace)
read=namespace['heartbeat_phase'];cases=0
with tempfile.TemporaryDirectory(prefix='anya-heartbeat-unit-') as directory:
    path=Path(directory)/'heartbeat'
    assert read(path)=='heartbeat-missing';cases+=1
    for text in ['', '10', '10,', 'bad,prepare', '10,prepare,extra', '10,prepare\npartial']:
        path.write_text(text);assert read(path)=='heartbeat-incomplete',repr(text);cases+=1
    for text,expected in [('0,startup','startup'),('10,prepare','prepare'),('42,after-stop\n','after-stop')]:
        path.write_text(text);assert read(path)==expected;cases+=1
    for text in ['', '30', '30,']:
        path.write_text(text);assert read(path)=='heartbeat-incomplete';cases+=1
    path.write_text('30,remote-steady');assert read(path)=='remote-steady';cases+=1
print(f'ANYA_HEARTBEAT_READER_OK cases={cases}; telemetry-only missing/partial labels, no launch or mutation retries')
