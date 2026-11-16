from pathlib import Path
import hashlib,zipfile,json
jar=next(Path('build/budget').glob('*.jar'))
with zipfile.ZipFile(jar) as z:
    names=z.namelist()
    assert not any('/durable/unload/' in n or n.endswith('UnloadAuditMixin.class') for n in names),'Old deferred queue/observer was bundled'
    assert 'SerializationBudgetMixin' in json.loads(z.read('anya_budget.mixins.json'))['mixins']
    assert 'dev/anya/storage/budget/BacklogProbe.class' in names
play=Path('build/libs/anya-storage-plus-0.3.0-beta.6.jar')
assert hashlib.sha256(play.read_bytes()).hexdigest()=='5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11'
print('ANYA_BUDGET_MODULE_VERIFIED',jar.name,hashlib.sha256(jar.read_bytes()).hexdigest(),'unchanged_beta6=true')
