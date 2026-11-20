from pathlib import Path
import hashlib,zipfile,json
jar=next(Path('build/minimal').glob('*.jar'))
with zipfile.ZipFile(jar) as z:
    names=z.namelist()
    assert not any('/durable/unload/' in n or n.endswith('UnloadAuditMixin.class') for n in names),'Old queue/observer was bundled'
    cfg=json.loads(z.read('anya_minimal.mixins.json'))
    assert 'MinimalOwnershipMixin' in cfg['mixins']
    assert not any('UnloadAudit' in s for s in cfg['mixins'])
    assert 'dev/anya/storage/minimal/DirtyCycles.class' in names
play=Path('build/libs/anya-storage-plus-0.3.0-beta.6.jar')
assert hashlib.sha256(play.read_bytes()).hexdigest()=='5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11'
print('ANYA_QUEUE_FREE_MODULE_VERIFIED',jar.name,hashlib.sha256(jar.read_bytes()).hexdigest(),'unchanged_beta6=true')
