from pathlib import Path
import hashlib,json,tomllib,zipfile
jar=next(Path('build/healthy').glob('*.jar'))
with zipfile.ZipFile(jar) as z:
    names=set(z.namelist())
    metadata=tomllib.loads(z.read('META-INF/neoforge.mods.toml').decode())
    for entry in metadata['mixins']:
        assert entry['config'] in names,entry
        cfg=json.loads(z.read(entry['config']))
        for name in cfg['mixins']:
            assert cfg['package'].replace('.','/')+'/'+name.replace('.','/')+'.class' in names,name
    assert 'dev/anya/storage/healthy/HealthyProbe.class' in names
    assert 'dev/anya/storage/repairchurn/ChurnProbe.class' not in names
play=Path('build/libs/anya-storage-plus-0.3.0-beta.6.jar')
assert hashlib.sha256(play.read_bytes()).hexdigest()=='5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11'
print('ANYA_HEALTHY_PACKAGE_VERIFIED',jar.name,hashlib.sha256(jar.read_bytes()).hexdigest(),'beta6_unchanged=true')
