"""Original stitched, blocky backpack collection. Deterministic; standard library only.
All model previews must use these exact JSON cuboids and texture files, not concept art.
"""
from pathlib import Path
import json,struct,zlib,math,random
ROOT=Path(__file__).resolve().parents[1]/'src/main/resources';M='anyastorage'
def js(path,obj):
 p=ROOT/path;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(obj,indent=2)+'\n')
def png(path,w,h,p):
 def chunk(t,d):return struct.pack('>I',len(d))+t+d+struct.pack('>I',zlib.crc32(t+d)&0xffffffff)
 raw=b''.join(b'\0'+bytes(v for pixel in p[y*w:(y+1)*w] for v in pixel) for y in range(h))
 data=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',w,h,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(raw,9))+chunk(b'IEND',b'')
 path=ROOT/path;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
def color(c,a=255):return (*tuple(bytes.fromhex(c)),a)
def shade(c,n):return tuple(max(0,min(255,v+n)) for v in c[:3])+(c[3],)
def rect(p,w,x,y,ww,hh,c):
 for yy in range(y,y+hh):
  for xx in range(x,x+ww):
   if 0<=xx<w and 0<=yy<len(p)//w:p[yy*w+xx]=c

def line(p,w,x0,y0,x1,y1,c):
 n=max(abs(x1-x0),abs(y1-y0),1)
 for i in range(n+1):rect(p,w,round(x0+(x1-x0)*i/n),round(y0+(y1-y0)*i/n),1,1,c)
def stamp(p,w,rows,x,y,c,scale=1):
 for yy,row in enumerate(rows):
  for xx,v in enumerate(row):
   if v!=' ':rect(p,w,x+xx*scale,y+yy*scale,scale,scale,c)
HEART=[' ## ## ','#######','#######',' ##### ','  ###  ','   #   ']
STAR=['   #   ','   #   ','#######',' ##### ','  ###  ',' # # # ','#     #']
LEAF=['    ##','   ###','  ####',' #####','##### ',' ###  ',' #    ']
CAT=['#     #','##   ##','#######','#######',' # # # ','  ###  ']
GHOST=['  ###  ',' ##### ','#######','## # ##','#######','#######','# # # #']
DIAMOND=['   #   ','  ###  ',' ##### ','#######',' ##### ','  ###  ','   #   ']
BOW=['##   ##','### ###',' ##### ','### ###','##   ##']
PALETTES={
'plain':('86523D','DDA772','382B35','FFE4B1'),
'stone':('636C7F','BEC9D1','303D54','CBE1EA'),
'diamond':('3E95B3','A9F6EA','193B60','F5FFFF'),
'magma':('51394F','F8A05E','211E37','FFD88A'),
'water':('277B9B','90DEE5','15365D','D1F2F5'),
'bow_cat':('EADAE7','F0A7C9','765268','FFF8EC'),
'midnight_cat':('343345','B8A8E2','181C30','D6C8FD'),
'spooky':('544469','D5A876','27273F','F6E8C9'),
'cute_spooky':('8E7BA1','E5B3D1','393653','FFF4DC'),
'futuristic':('304D68','7AE0E1','1A263F','D8F8FC'),
'galaxy':('413B72','C2AFE8','242340','F3E1B9'),
'oak':('527956','B5CD85','304B43','E8EBC0'),
'cherry':('B77194','F7C5D5','633D63','FFF0E6'),
'dark_oak':('4B5359','B59380','283945','E1CBB0')}
LEAVES={'oak':('527956','B5CD85'),'spruce':('386E65','8DC5A0'),'birch':('7B995F','E3EAC9'),'jungle':('3D8B5E','B3DB80'),'acacia':('7F975C','D7D296'),'dark_oak':('4B5359','B59380'),'mangrove':('517268','ACCF8C'),'cherry':('B77194','F7C5D5')}
for leaf,(body,accent) in LEAVES.items():PALETTES['leaf_'+leaf]=(body,accent,'304A49' if leaf!='cherry' else '633D63','F9EFD8')
ORDER=['plain','stone','diamond','magma','water']+['leaf_'+s for s in LEAVES]+['bow_cat','midnight_cat','spooky','cute_spooky','futuristic','galaxy','oak','cherry','dark_oak']
# Six distinct cloth/metal faces; silhouette geometry adds flap, stitching, side pockets, buckles and straps.
for name in ORDER:
 body,accent,dark,light=map(color,PALETTES[name]);rng=random.Random(name)
 for face in ['front','side','top','pocket','trim']:
  p=[body]*(32*32)
  for y in range(32):
   for x in range(32):
    grain=((x*17+y*13)%11)-5
    p[y*32+x]=shade(body,grain+(4 if y<9 else -4 if y>24 else 0))
  rect(p,32,0,0,32,2,dark);rect(p,32,0,30,32,2,dark);rect(p,32,0,0,2,32,dark);rect(p,32,30,0,2,32,dark)
  for t in range(4,29,4):
   rect(p,32,t,3,2,1,accent);rect(p,32,t,28,2,1,accent);rect(p,32,3,t,1,2,accent);rect(p,32,28,t,1,2,accent)
  if face=='trim':
   rect(p,32,4,4,24,24,accent);rect(p,32,7,7,18,18,shade(accent,-20));rect(p,32,7,7,18,2,light)
  elif face=='top':
   rect(p,32,5,13,22,6,dark);rect(p,32,6,14,20,2,accent)
   for t in range(6,26,3):rect(p,32,t,17,1,1,light)
  elif face=='side':
   rect(p,32,8,7,16,20,shade(body,-12));rect(p,32,9,8,14,2,accent);rect(p,32,12,18,8,3,dark);rect(p,32,15,17,2,5,light)
  elif face=='pocket':
   rect(p,32,4,6,24,2,accent);rect(p,32,4,9,24,1,dark);rect(p,32,14,9,4,5,light);rect(p,32,15,10,2,3,dark)
   stamp(p,32,HEART,13,20,accent)
  else:
   # Embroidered pictogram with a one-pixel offset shadow.
   emblem=LEAF if 'leaf' in name or name in ('oak','cherry','dark_oak') else CAT if 'cat' in name else GHOST if 'spooky' in name else STAR if name=='galaxy' else DIAMOND if name in ('diamond','stone') else HEART
   stamp(p,32,emblem,10,11,dark,2);stamp(p,32,emblem,9,10,accent,2)
   if 'cat' in name:
    rect(p,32,13,18,2,2,dark);rect(p,32,21,18,2,2,dark);rect(p,32,17,20,2,1,light)
    stamp(p,32,BOW,18,6,color('EE9EBE'))
   if name=='galaxy':
    for x,y in [(5,8),(25,12),(7,23),(23,25),(17,6)]:rect(p,32,x,y,1,1,light)
   if name=='magma':
    for x,y in [(6,6),(25,5),(4,22),(25,24)]:line(p,32,x,y,x+2,y+3,accent);line(p,32,x+2,y+3,x+1,y+5,light)
   if name=='water':
    for y in (6,25):
     for x in range(5,27):rect(p,32,x,y+int(math.sin(x)*1.5),1,1,accent)
   if name=='futuristic':
    for x in (6,25):line(p,32,x,7,x,25,accent)
    for y in (10,22):line(p,32,5,y,10,y,light);line(p,32,22,y,27,y,light)
  png(f'assets/{M}/textures/backpack/{name}_{face}.png',32,32,p)
 textures={face:f'{M}:backpack/{name}_{face}' for face in ['front','side','top','pocket','trim']};textures['particle']=textures['front']
 elements=[]
 def box(a,b,face='side',front=None):
  faces={d:{'texture':'#'+face,'uv':[0,0,16,16]} for d in ['north','south','west','east','up','down']}
  if front:
   faces['north']={'texture':'#'+front,'uv':[0,0,16,16]};faces['south']={'texture':'#'+front,'uv':[0,0,16,16]}
  elements.append({'from':a,'to':b,'faces':faces})
 box([3,2,5],[13,14,10],'side','front')
 box([3,12.5,4.6],[13,14.5,10.5],'top','front')
 box([4,2.5,10],[12,7.5,12],'side','pocket')
 box([1.5,3,6],[3.5,9,10.5],'side','pocket');box([12.5,3,6],[14.5,9,10.5],'side','pocket')
 box([5,14,6],[6,16,8],'trim');box([10,14,6],[11,16,8],'trim');box([6,15,6],[10,16,8],'trim')
 box([4,3,3.5],[5.3,13,5.2],'trim');box([10.7,3,3.5],[12,13,5.2],'trim')
 for x in (5.0,9.5):box([x,8.5,10.0],[x+1.4,11.5,10.7],'trim')
 if 'cat' in name:
  for x in (3,10):box([x,14,6],[x+3,15.5,9],'side','front');box([x+.7,15.5,6.5],[x+2.3,17,8.5],'trim')
 if 'leaf' in name or name in ('oak','cherry','dark_oak'):
  box([12,10.5,7],[15,12,10],'trim');box([13,12,7],[15,14,9],'trim');box([1,9,7],[3,11,9],'trim')
 if name=='galaxy':box([13,8,10],[14,9,11],'trim');box([12.5,7.5,10.2],[14.5,8.5,10.8],'trim')
 display={'gui':{'rotation':[20,-25,0],'translation':[0,-.5,0],'scale':[.9,.9,.9]},'ground':{'translation':[0,2,0],'scale':[.5,.5,.5]},'fixed':{'scale':[.8,.8,.8]},'thirdperson_righthand':{'rotation':[75,45,0],'translation':[0,2,0],'scale':[.55,.55,.55]},'firstperson_righthand':{'rotation':[0,-35,0],'translation':[0,2,0],'scale':[.6,.6,.6]}}
 js(f'assets/{M}/models/item/backpack/{name}.json',{'textures':textures,'elements':elements,'display':display,'gui_light':'front'})
js(f'assets/{M}/models/item/backpack.json',{'parent':f'{M}:item/backpack/plain','overrides':[{'predicate':{f'{M}:appearance':i+1},'model':f'{M}:item/backpack/{name}'} for i,name in enumerate(ORDER)]})
# Themed, stitched interior panels. No user upload is needed for an attractive default.
THEMES={'atelier':('2D263C','F2B9D5','48354D'),'botanical':('213B36','A9CE9C','36554D'),'midnight':('211E32','B6A1D5','38304B'),'galaxy':('24213F','D1B9EE','37305D'),'circuit':('172E3C','84DCDD','274553'),'ocean':('183747','99DCE4','2B5365')}
for name,colors in THEMES.items():
 base,accent,panel=map(color,colors);w,h=304,250;p=[base]*(w*h)
 rect(p,w,0,0,w,2,shade(base,-20));rect(p,w,0,h-2,w,2,shade(base,-20));rect(p,w,0,0,2,h,shade(base,-20));rect(p,w,w-2,0,2,h,shade(base,-20))
 rect(p,w,3,3,w-6,2,accent);rect(p,w,3,h-5,w-6,1,accent)
 for x in range(8,w-8,5):rect(p,w,x,7,2,1,shade(accent,-20));rect(p,w,x,h-8,2,1,shade(accent,-20))
 rect(p,w,8,32,103,107,shade(base,-10));rect(p,w,10,34,99,103,panel)
 rect(p,w,117,32,176,109,shade(base,-12));rect(p,w,119,34,172,105,panel)
 rect(p,w,117,151,176,83,shade(base,-13))
 # Decorative surface pattern is subtle enough for text and items to remain readable.
 rng=random.Random(name)
 for i in range(80):
  x=rng.randrange(121,290);y=rng.randrange(37,137)
  if name in ('galaxy','midnight'):rect(p,w,x,y,1,1,shade(accent,-45))
  elif name=='circuit':line(p,w,x,y,min(290,x+7),y,shade(panel,8));line(p,w,x,y,x,min(138,y+5),shade(panel,8))
  elif name=='botanical':stamp(p,w,LEAF,x,y,shade(panel,7))
  elif name=='ocean':line(p,w,x,y,min(290,x+5),y,shade(panel,6))
  else:rect(p,w,x,y,2,1,shade(panel,7))
 for y in [34,52,70,88,106,124,155,173,191,213]:
  for col in range(9):
   x=125+col*18;rect(p,w,x-1,y-1,18,18,shade(base,-9));rect(p,w,x,y,16,16,shade(panel,-5));rect(p,w,x,y,16,1,shade(panel,20));rect(p,w,x,y,1,16,shade(panel,10))
 stamp(p,w,HEART if name=='atelier' else LEAF if name=='botanical' else STAR,16,38,shade(accent,-10))
 png(f'assets/{M}/textures/gui/{name}.png',w,h,p)
# Distinct patch and upgrade icons: every function gets its own recognizable pictogram.
ICONS={'pickup':['  #  ','#####',' ### ','  #  ','  #  '],'feeding':[' ### ','#####','#####',' ### ','  #  '],'refill':['  #  ',' ### ','#####','  #  ','  #  '],'crafting':['# # #','#####',' # # ','#####','# # #'],'smelting':['  #  ',' # # ','## ##','#####',' ### '],'compacting':['#   #','## ##',' ### ','## ##','#   #'],'resupply':['#### ','  ## ','  #  ',' #   ','#    ']}
PATCHES=['element_'+e for e in ['leaf','stone','diamond','magma','water']]+['skin_'+s for s in ORDER[13:]]
for name in PATCHES+[u+'_upgrade' for u in ICONS]:
 key=name.removeprefix('element_').removeprefix('skin_').removesuffix('_upgrade');palette=PALETTES.get(key,PALETTES.get('leaf_oak') if key=='leaf' else PALETTES['futuristic']);body,accent,dark,light=map(color,palette)
 p=[(0,0,0,0)]*1024;rect(p,32,3,3,26,26,dark);rect(p,32,4,4,24,23,body);rect(p,32,6,5,20,2,accent)
 for t in range(7,27,4):rect(p,32,t,25,2,1,accent)
 emblem=ICONS.get(key,LEAF if 'leaf' in key or key in ('oak','cherry','dark_oak') else CAT if 'cat' in key else GHOST if 'spooky' in key else STAR if key=='galaxy' else DIAMOND if key in ('stone','diamond') else HEART)
 scale=2;x=(32-len(emblem[0])*scale)//2;y=(32-len(emblem)*scale)//2+1;stamp(p,32,emblem,x+1,y+1,dark,scale);stamp(p,32,emblem,x,y,light,scale)
 png(f'assets/{M}/textures/item/{name}.png',32,32,p);js(f'assets/{M}/models/item/{name}.json',{'parent':'minecraft:item/generated','textures':{'layer0':f'{M}:item/{name}'}})
# Three restrained particle sprites, each with a 28-tick maximum lifetime in Java.
for name,shape,c in [('leaf_mote',LEAF,color('A2C787')),('cherry_mote',LEAF,color('F2BBCC')),('star_mote',STAR,color('F7E3BD'))]:
 p=[(0,0,0,0)]*64;stamp(p,8,shape,0,0,c);png(f'assets/{M}/textures/particle/{name}.png',8,8,p);js(f'assets/{M}/particles/{name}.json',{'textures':[f'{M}:{name}']})
# Accessible recipes. No world generation or custom ores.
def shaped(name,pattern,key,result,components=None):
 out={'id':f'{M}:{result}','count':1}
 if components:out['components']={'minecraft:custom_data':components}
 js(f'data/{M}/recipe/{name}.json',{'type':'minecraft:crafting_shaped','category':'equipment','neoforge:conditions':[{'type':f'{M}:recipe_group','group':'backpacks'}],'pattern':pattern,'key':{k:({'tag':v[1:]} if v.startswith('#') else {'item':v}) for k,v in key.items()},'result':out})
shaped('backpack',['LSL','LCL','LSL'],{'L':'minecraft:leather','S':'minecraft:string','C':'minecraft:chest'},'backpack')
for leaf in LEAVES:shaped('leaf_backpack_'+leaf,['FSF','L L','FSF'],{'F':'minecraft:'+leaf+'_leaves','L':'minecraft:leather','S':'minecraft:string'},'backpack',{'Element':'leaf','LeafSpecies':leaf,'Skin':'elemental'})
for key,mat in [('leaf','#minecraft:leaves'),('stone','minecraft:cobblestone'),('diamond','minecraft:diamond'),('magma','minecraft:magma_block'),('water','minecraft:prismarine_shard')]:
 shaped('element_'+key,[' M ','MSM',' M '],{'M':mat,'S':'minecraft:string'},'element_'+key)
SKIN_MATS={'bow_cat':('minecraft:pink_dye','minecraft:white_wool'),'midnight_cat':('minecraft:purple_dye','minecraft:black_wool'),'spooky':('minecraft:gray_dye','minecraft:bone'),'cute_spooky':('minecraft:pink_dye','minecraft:bone'),'futuristic':('minecraft:cyan_dye','minecraft:redstone'),'galaxy':('minecraft:purple_dye','minecraft:amethyst_shard'),'oak':('minecraft:green_dye','minecraft:oak_leaves'),'cherry':('minecraft:pink_dye','minecraft:cherry_leaves'),'dark_oak':('minecraft:brown_dye','minecraft:dark_oak_leaves')}
for skin,(dye,mat) in SKIN_MATS.items():shaped('skin_'+skin,[' D ','PMP',' S '],{'D':dye,'P':'minecraft:paper','M':mat,'S':'minecraft:string'},'skin_'+skin)
js(f'data/{M}/recipe/backpack_style.json',{'type':f'{M}:backpack_style','category':'equipment'})
lang_path=ROOT/f'assets/{M}/lang/en_us.json';lang=json.loads(lang_path.read_text())
for key in PATCHES:lang[f'item.{M}.{key}']=key.removeprefix('element_').removeprefix('skin_').replace('_',' ').title()+(' Binding' if key.startswith('element_') else ' Skin Patch')
js(f'assets/{M}/lang/en_us.json',lang)
print(f'Generated {len(ORDER)} layered backpack models, stitched interiors, skin patches, elemental recipes and budgeted particle sprites.')
