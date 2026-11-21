"""Deterministic, original pixel assets and data for the storage beta. Python standard library only."""
from pathlib import Path
import json, struct, zlib, gzip
ROOT=Path(__file__).resolve().parents[1]/'src/main/resources'
MOD='anyastorage'
def js(path,obj):
    p=ROOT/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(obj,indent=2)+'\n')
def png(path,w,h,pixels):
    def chunk(t,data):return struct.pack('>I',len(data))+t+data+struct.pack('>I',zlib.crc32(t+data)&0xffffffff)
    raw=b''.join(b'\0'+bytes(sum(pixels[y*w:(y+1)*w],())) for y in range(h))
    data=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw,9))+chunk(b'IEND',b'')
    p=ROOT/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(data)
def image(w,h,fill=(0,0,0,0)):return [fill]*(w*h)
def rect(p,w,x,y,ww,hh,color):
    for yy in range(y,y+hh):
        for xx in range(x,x+ww):
            if 0<=xx<w and 0<=yy<len(p)//w:p[yy*w+xx]=color
DARK=(39,46,65,255);EDGE=(19,25,38,255);PINK=(242,153,193,255);CYAN=(116,219,231,255);WHITE=(237,239,248,255)
shapes={
 'heart':['00000000','01100110','11111111','11111111','01111110','00111100','00011000','00000000'],
 'star':['00011000','00011000','11111111','01111110','00111100','01111110','01100110','01000010'],
 'square':['00000000','01111110','01000010','01000010','01000010','01000010','01111110','00000000'],
 'circle':['00111100','01100110','11000011','10000001','10000001','11000011','01100110','00111100'],
 'diamond':['00011000','00100100','01000010','10000001','10000001','01000010','00100100','00011000'],
 'leaf':['00000110','00011110','00111110','01111100','01111000','11110000','11000000','10000000'],
 'gear':['00100100','01111110','11011011','01100110','01100110','11011011','01111110','00100100']}
for name,rows in shapes.items():png(f'assets/{MOD}/textures/entity/label_{name}.png',8,8,[PINK if c=='1' else (0,0,0,0) for row in rows for c in row])
from chest_shell import generate as generate_chest_shell
generate_chest_shell(ROOT)
from hub_console import generate as generate_hub_console
generate_hub_console(png, js)
from moon_cube import generate as generate_moon_cube
generate_moon_cube(ROOT)
for name in ['hub','chest','uwu_cube']:
    js(f'assets/{MOD}/blockstates/{name}.json',{'variants':{f'facing={face}':{'model':f'{MOD}:block/{name}','y':rot} for face,rot in [('north',0),('east',90),('south',180),('west',270)]}})
    js(f'assets/{MOD}/models/item/{name}.json',{'parent':f'{MOD}:block/{name}'})
    js(f'data/{MOD}/loot_table/blocks/{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'{MOD}:{name}'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
# The block entity paints the real bag model from its public look. Supply a particle-only
# shell to the block atlas so no second shape is drawn over the backpack.
js(f'assets/{MOD}/blockstates/placed_backpack.json', {'variants': {'': {'model': f'{MOD}:block/placed_backpack'}}})
js(f'assets/{MOD}/models/block/placed_backpack.json', {'parent': 'minecraft:block/block', 'textures': {'particle': f'{MOD}:backpack/plain_front'}})
items=['backpack','remote','storage_core','dragon_victory_token']+[f'{u}_upgrade' for u in ['pickup','feeding','refill','crafting','smelting','compacting','resupply']]
for index,name in enumerate(items):
    pixels=image(16,16)
    if name=='backpack':
        rect(pixels,16,4,1,8,2,EDGE);rect(pixels,16,2,3,12,12,EDGE);rect(pixels,16,3,4,10,10,PINK);rect(pixels,16,4,10,8,3,DARK);rect(pixels,16,7,5,2,4,WHITE)
    elif name=='remote':
        # Retain a model for stacks in old, unopened containers, until a player takes the tablet.
        js(f'assets/{MOD}/models/item/remote.json',{'parent':f'{MOD}:item/cupid_tablet_shell'})
        continue
    else:
        rect(pixels,16,2,2,12,12,EDGE);rect(pixels,16,3,3,10,10,DARK);rect(pixels,16,4,4,8,2,CYAN);rect(pixels,16,5,8,6,3,PINK)
        for bit in range(4):
            if index&(1<<bit):rect(pixels,16,4+bit*2,12,1,1,WHITE)
    png(f'assets/{MOD}/textures/item/{name}.png',16,16,pixels)
    js(f'assets/{MOD}/models/item/{name}.json',{'parent':'minecraft:item/generated','textures':{'layer0':f'{MOD}:item/{name}'}})
lang={f'item.{MOD}.{name}':name.replace('_',' ').title() for name in items}
lang[f'item.{MOD}.cupid_tablet']='Cupid Tablet'
lang[f'item.{MOD}.creative_tablet']='UwU Tablet'
lang[f'item.{MOD}.remote']='Cupid Tablet (Former Remote)'
for name, title in {
    'advanced_pickup_upgrade': 'Advanced Pickup Upgrade',
    'advanced_compacting_upgrade': 'Advanced Compacting Upgrade',
    'manual_smoking_upgrade': 'Manual Smoker Upgrade',
    'auto_smoking_upgrade': 'Auto Smoker Upgrade',
    'manual_blasting_upgrade': 'Manual Blast Furnace Upgrade',
    'auto_blasting_upgrade': 'Auto Blast Furnace Upgrade',
    'manual_smelting_upgrade': 'Manual Smelter Upgrade',
    'auto_smelting_upgrade': 'Auto Smelter Upgrade',
}.items():
    lang[f'item.{MOD}.{name}'] = title
    js(f'assets/{MOD}/models/item/{name}.json', {'parent':'minecraft:item/generated','textures':{'layer0':f'{MOD}:item/{name}'}})
lang.update({'subtitles.anyastorage.chest_open':'Storage chest opens','subtitles.anyastorage.chest_close':'Storage chest closes'})
lang.update({'subtitles.anyastorage.cube_open':'UwU storage cube opens','subtitles.anyastorage.cube_close':'UwU storage cube closes'})
lang.update({'subtitles.anyastorage.frame_on':'Pocket window powers on','subtitles.anyastorage.frame_off':'Pocket window powers down'})
lang.update({f'block.{MOD}.hub':'Anya Storage Hub',f'block.{MOD}.chest':'Labelled Storage Chest',f'block.{MOD}.uwu_cube':'UwU Storage Cube',f'key.{MOD}.quick':'QuickStack matching items',f'key.{MOD}.dump':'Dump broader matching items',f'key.{MOD}.undo_dump':'Undo last nearby dump',f'key.{MOD}.bag':'Open first backpack',f'key.categories.{MOD}':'Anya Storage'})
for source in ('item_help_parts.json', 'item_help_devices.json'):
    help_lines = json.loads(Path(__file__).with_name(source).read_text(encoding='utf-8'))
    if lang.keys() & help_lines.keys():
        raise ValueError(f'Duplicate language keys in {source}')
    lang.update(help_lines)
js(f'assets/{MOD}/lang/en_us.json',lang)
def shaped(name,group,pattern,key,count=1):
    js(f'data/{MOD}/recipe/{name}.json',{'type':'minecraft:crafting_shaped','category':'misc','neoforge:conditions':[{'type':f'{MOD}:recipe_group','group':group}],'pattern':pattern,'key':{k:({'tag':v[1:]} if v.startswith('#') else {'item':v}) for k,v in key.items()},'result':{'id':f'{MOD}:{name}','count':count}})
shaped('backpack','backpacks',['LSL','LCL','LGL'],{'L':'minecraft:leather','S':'minecraft:string','C':'minecraft:chest','G':'minecraft:gold_ingot'})
shaped('hub','hubs',['IEI','ACA','IRI'],{'I':'minecraft:iron_block','E':'minecraft:ender_pearl','A':'minecraft:amethyst_shard','C':'minecraft:comparator','R':'minecraft:redstone_block'})
shaped('chest','containers',['IPI','PCP','IPI'],{'I':'minecraft:iron_ingot','P':'#minecraft:planks','C':'minecraft:chest'})
# These files were previously generated into the source resources and may outlive a Gradle clean.
(ROOT/f'data/{MOD}/recipe/remote.json').unlink(missing_ok=True)
(ROOT/f'assets/{MOD}/textures/item/remote.png').unlink(missing_ok=True)
shaped('cupid_tablet','terminals',['IGI','ARA','IEI'],{
    'I':'minecraft:iron_ingot','G':'minecraft:glass_pane','A':'minecraft:amethyst_shard',
    'R':'minecraft:redstone','E':'minecraft:ender_pearl'})
shaped('storage_core','endgame',['DDD','DND','DDD'],{'D':'minecraft:diamond_block','N':'minecraft:netherite_block'})
for u,ingredient in [('pickup','hopper'),('feeding','golden_carrot'),('refill','dropper'),('crafting','crafting_table'),('smelting','furnace'),('compacting','piston'),('resupply','iron_pickaxe')]:
    shaped(u+'_upgrade','upgrade.'+u,['PIP','IRI','PIP'],{'P':'minecraft:paper','I':'minecraft:'+ingredient,'R':'minecraft:redstone'})
for family, station, part in (
    ('smelting', 'furnace', 'powered_rail'),
    ('smoking', 'smoker', 'copper_grate'),
    ('blasting', 'blast_furnace', 'obsidian'),
):
    manual, auto = f'manual_{family}_upgrade', f'auto_{family}_upgrade'
    shaped(manual,'upgrade.'+family,['CFC',' T '],
           {'C':'minecraft:copper_ingot','F':'minecraft:'+station,'T':'minecraft:redstone_torch'})
    js(f'data/{MOD}/recipe/{auto}.json', {
        'type':'minecraft:crafting_shapeless', 'category':'equipment',
        'neoforge:conditions':[{'type':f'{MOD}:recipe_group','group':'upgrade.'+family}],
        'ingredients':[{'item':f'{MOD}:{manual}'}, {'item':'minecraft:clock'}, {'item':'minecraft:'+part}],
        'result':{'id':f'{MOD}:{auto}','count':1},
    })
for family, parts in {
    'pickup': ['amethyst_shard', 'name_tag', 'copper_ingot'],
    'compacting': ['piston', 'comparator', 'amethyst_shard'],
}.items():
    name = 'advanced_' + family + '_upgrade'
    js(f'data/{MOD}/recipe/{name}.json', {
        'type':'minecraft:crafting_shapeless', 'category':'equipment',
        'neoforge:conditions':[{'type':f'{MOD}:recipe_group','group':'upgrade.'+family}],
        'ingredients':[{'item':f'{MOD}:{family}_upgrade'}, *({'item':'minecraft:'+part} for part in parts)],
        'result':{'id':f'{MOD}:{name}','count':1},
    })
js(f'data/{MOD}/recipe/backpack_upgrade.json',{'type':f'{MOD}:backpack_upgrade','category':'misc'})
js(f'data/{MOD}/recipe/uwu_cube.json',{'type':f'{MOD}:cube','category':'misc'})
js('data/minecraft/tags/block/mineable/pickaxe.json',{'replace':False,'values':[f'{MOD}:hub',f'{MOD}:chest',f'{MOD}:uwu_cube']})
def utf(s):b=s.encode();return struct.pack('>H',len(b))+b
def named(t,name,payload):return bytes([t])+utf(name)+payload
nbt=b'\x0a\0\0'+named(3,'DataVersion',struct.pack('>i',3955))
nbt+=named(9,'size',b'\x03'+struct.pack('>i',3)+struct.pack('>iii',7,7,7))
nbt+=named(9,'entities',b'\x0a'+struct.pack('>i',0))+named(9,'blocks',b'\x0a'+struct.pack('>i',0))
nbt+=named(9,'palette',b'\x0a'+struct.pack('>i',1)+named(8,'Name',utf('minecraft:air'))+b'\0')+b'\0'
p=ROOT/f'data/{MOD}/structure/empty.nbt';p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(gzip.compress(nbt,mtime=0))
print('Generated original storage assets, recipes, loot tables and test structure.')
