from pathlib import Path
import hashlib,zipfile
p=Path('build/libs/anya-storage-plus-0.3.0-beta.6.jar')
assert hashlib.sha256(p.read_bytes()).hexdigest()=='5e920929800ac56b0a5640c4992622b9f01d6638e63be4b176c4767c5383ba11'
a=next(Path('build/pressure').glob('*.jar'))
with zipfile.ZipFile(a) as z:
 assert 'anya_pressure.mixins.json' in z.namelist()
 assert not any('/durable/unload/' in n for n in z.namelist())
print('ANYA_PRESSURE_BINARY',a.name,hashlib.sha256(a.read_bytes()).hexdigest(),'playable_beta6_unchanged=true')
