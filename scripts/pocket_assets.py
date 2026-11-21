"""Additive 16px home entrance assets and an empty, non-generating pocket dimension."""
import json
from pathlib import Path
# Import runs the unchanged original asset pipeline; pocket files remain separate.
ROOT=Path(__file__).resolve().parents[1]/'src/main/resources'
def js(path,obj):
 p=ROOT/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(obj,indent=2)+'\n')
js('data/anyastorage/dimension_type/pockets.json',{
 'ultrawarm':False,'natural':False,'coordinate_scale':1.0,'has_skylight':False,'has_ceiling':False,
 'ambient_light':0.8,'fixed_time':18000,'piglin_safe':True,'bed_works':False,'respawn_anchor_works':False,
 'has_raids':False,'min_y':0,'height':256,'logical_height':128,'infiniburn':'#minecraft:infiniburn_end',
 'effects':'minecraft:the_end','monster_spawn_block_light_limit':0,'monster_spawn_light_level':0})
js('data/anyastorage/dimension/pockets.json',{'type':'anyastorage:pockets','generator':{'type':'minecraft:flat','settings':{'biome':'minecraft:the_void','features':False,'lakes':False,'layers':[],'structure_overrides':[]}}})
js('data/anyastorage/recipe/home_void_cube.json', {
    'type': 'minecraft:crafting_shapeless', 'category': 'misc',
    'ingredients': [{'item': 'minecraft:diamond'}, {'item': 'minecraft:diamond'}, {'item': 'minecraft:ancient_debris'}],
    'result': {'id': 'anyastorage:home_void_cube', 'count': 1},
})
js('data/anyastorage/advancement/recipes/misc/home_void_cube.json', {
    'parent': 'minecraft:recipes/root',
    'criteria': {
        'has_diamond': {'trigger': 'minecraft:inventory_changed', 'conditions': {'items': [{'items': 'minecraft:diamond'}]}},
        'has_the_recipe': {'trigger': 'minecraft:recipe_unlocked', 'conditions': {'recipe': 'anyastorage:home_void_cube'}},
    },
    'requirements': [['has_diamond', 'has_the_recipe']], 'rewards': {'recipes': ['anyastorage:home_void_cube']},
})
js('assets/anyastorage/blockstates/home_void_cube.json',{'variants':{'':{'model':'anyastorage:block/home_void_cube'}}})
js('assets/anyastorage/models/block/home_void_cube.json',{'parent':'minecraft:block/cube_all','textures':{'all':'anyastorage:block/home_void_cube'}})
js('assets/anyastorage/models/item/home_void_cube.json',{'parent':'anyastorage:block/home_void_cube'})
js('data/anyastorage/loot_table/blocks/home_void_cube.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'anyastorage:home_void_cube'}]}]})
# Animated abstract window, not a live world. Four native-size frames, no contents or destination coordinates.
import struct,zlib
pixels=[]
for frame in range(4):
 for y in range(16):
  for x in range(16):
   if x<2 or y<2 or x>13 or y>13:c=(35,37,54,255)
   elif x==2 or y==2 or x==13 or y==13:c=(119,201,214,255)
   elif x in (3,12) or y in (3,12):c=(117,91,160,255)
   elif (x+2*y+frame*3)%17==0:c=(212,190,238,255)
   else:c=(29+frame*2,24,52+frame*3,255)
   pixels.append(c)
def chunk(t,d):return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
raw=b''.join(b'\0'+bytes(v for p in pixels[y*16:(y+1)*16] for v in p) for y in range(64))
path=ROOT/'assets/anyastorage/textures/block/home_void_cube.png';path.parent.mkdir(parents=True,exist_ok=True)
path.write_bytes(b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',16,64,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw,9))+chunk(b'IEND',b''))
js('assets/anyastorage/textures/block/home_void_cube.png.mcmeta',{'animation':{'frametime':12,'interpolate':False}})
lang=ROOT/'assets/anyastorage/lang/en_us.json';data=json.loads(lang.read_text());data['block.anyastorage.home_void_cube']='Skygrid Cube (Experimental)';js('assets/anyastorage/lang/en_us.json',data)
print('Generated isolated void dimension and abstract non-live home entrance preview (16x16 per frame).')
from furniture_assets import generate as generate_furniture
generate_furniture(ROOT)


# The starter shell is generated only for new pockets. Keep the original dimension key for saves.
data=json.loads(lang.read_text())
data.update({'dimension.anyastorage.pockets':'Skygrid','block.anyastorage.skygrid_panel':'Skygrid Wall Panel','block.anyastorage.skygrid_floor':'Skygrid Floor Tile','block.anyastorage.skygrid_light':'Skygrid Light Panel'})
js('assets/anyastorage/lang/en_us.json',data)
