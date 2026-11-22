"""Original book binding, illuminated page frame, decorative linings and a recipe for recovery."""
import polish_assets
from generate_expansion_assets import ROOT,M,color,rect,line,stamp,HEART,STAR,LEAF,BOW,png,js
import json,math
# Warm ivory pages, plum leather, antique brass corners, a deliberate center fold.
w,h=460,286
p=[color('000000',0)]*(w*h)
rect(p,w,4,6,w-8,h-8,color('161C2B'));rect(p,w,2,2,w-4,h-6,color('463247'))
rect(p,w,7,7,w-14,h-16,color('66435D'));rect(p,w,10,9,w-20,23,color('3E2D43'))
for y in range(34,h-37):
 for x in range(13,w-13):
  grain=((x*13+y*7)%5)-2
  base=(238,223,199) if x<w//2 else (246,233,211)
  p[y*w+x]=tuple(max(0,min(255,c+grain)) for c in base)+(255,)
for x in range(15,w-16,4):rect(p,w,x,4,2,1,color('D3B38A'));rect(p,w,x,h-7,2,1,color('D3B38A'))
for y in range(12,h-12,4):rect(p,w,5,y,1,2,color('C39E79'));rect(p,w,w-6,y,1,2,color('C39E79'))
for dx,c in [(-5,'DDC6A8'),(-3,'CCB598'),(-1,'B99F89'),(1,'D5BE9E'),(3,'E4D1B2'),(5,'F0E0C7')]:rect(p,w,w//2+dx,34,2,h-71,color(c))
# Subtle illuminated corners. The main reading area remains quiet.
for x,y,sx,sy in [(15,35,1,1),(w-16,35,-1,1),(15,h-40,1,-1),(w-16,h-40,-1,-1)]:
 for a,b in [(0,16),(0,12),(4,8),(8,4),(12,0)]:
  line(p,w,x,y,x+sx*16,y,color('AA8A6C'));line(p,w,x,y,x,y+sy*16,color('AA8A6C'))
  rect(p,w,x+sx*a,y+sy*b,2,2,color('C3A67A'))
# Ribbon tails behind the footer, not behind text.
rect(p,w,28,h-38,11,7,color('8B5D79'));rect(p,w,30,h-37,7,5,color('BB8397'))
png(f'assets/{M}/textures/gui/ancient_technology.png',w,h,p)
# Inventory book cover: stitched leather, gilded spine, a small moon-and-star insignia.
w=h=32;p=[color('000000',0)]*1024
rect(p,32,5,3,23,26,color('252B3B'));rect(p,32,4,4,22,26,color('704E6A'));rect(p,32,7,4,2,26,color('D7B884'))
rect(p,32,10,6,14,20,color('4C3958'));rect(p,32,11,7,12,1,color('C6A578'));rect(p,32,11,24,12,1,color('C6A578'))
rect(p,32,7,28,18,1,color('F2E2C5'));rect(p,32,7,30,18,1,color('342C44'))
for y in range(8,24,4):rect(p,32,4,y,2,1,color('E1BF8D'))
stamp(p,32,STAR,14,11,color('EFCCA2'));stamp(p,32,HEART,14,20,color('C391AE'))
rect(p,32,22,26,3,5,color('C88BA6'));png(f'assets/{M}/textures/item/ancient_technology.png',32,32,p)
js(f'assets/{M}/models/item/ancient_technology.json',{'parent':'minecraft:item/generated','textures':{'layer0':f'{M}:item/ancient_codex'}})
langpath=ROOT/f'assets/{M}/lang/en_us.json';lang=json.loads(langpath.read_text());lang[f'item.{M}.ancient_technology']='Ancient Technology';js(f'assets/{M}/lang/en_us.json',lang)
js(f'data/{M}/recipe/ancient_technology.json',{'type':'minecraft:crafting_shapeless','category':'misc','neoforge:conditions':[{'type':f'{M}:recipe_group','group':'guide'}],'ingredients':[{'item':'minecraft:book'},{'item':'minecraft:paper'}],'result':{'id':f'{M}:ancient_technology','count':1}})
# Distinct fine motifs on the default lining. Re-decode our own simple RGBA generator output.
import struct,zlib
palettes={'atelier':('30263E','D8A8C2'),'botanical':('203C38','A9C99B'),'midnight':('28213D','B7A1D4'),'galaxy':('292348','D7C3EE'),'circuit':('183949','8BCED8'),'ocean':('183F50','A3D4E5')}
for name,(base,accent) in palettes.items():
 path=ROOT/f'assets/{M}/textures/gui/{name}.png';b=path.read_bytes();at=8;data=b''
 while at<len(b):
  n=struct.unpack('>I',b[at:at+4])[0];kind=b[at+4:at+8]
  if kind==b'IDAT':data+=b[at+8:at+8+n]
  at+=n+12
 raw=zlib.decompress(data);w,h=304,250;p=[tuple(raw[y*(w*4+1)+1+x*4:y*(w*4+1)+1+x*4+4]) for y in range(h) for x in range(w)]
 ac=color(accent);sub=color(base)
 # Left display panel: a repeating botanical/astral/stitched pattern behind the 3-D bag.
 for y in range(36,127):
  for x in range(10,107):
   if name=='galaxy' and (x*79+y*47)%487==0:rect(p,w,x,y,1,1,ac)
   elif name=='circuit' and ((x%22==0 and 48<y<110) or (y%21==0 and 20<x<95)):rect(p,w,x,y,1,1,sub)
   elif name=='ocean' and (y+round(3*math.sin(x/7)))%23==0:rect(p,w,x,y,1,1,sub)
   elif name in ('atelier','midnight') and (x+y)%18==0:rect(p,w,x,y,1,1,sub)
 if name=='botanical':
  for x,y in [(13,40),(89,111),(12,101),(88,49)]:stamp(p,w,LEAF,x,y,ac)
 if name in ('atelier','midnight'):stamp(p,w,BOW,84,43,ac)
 if name=='galaxy':
  for x,y in [(14,48),(89,104),(81,40)]:stamp(p,w,STAR,x,y,ac)
 if name=='circuit':
  for x,y in [(18,49),(86,111),(83,48)]:rect(p,w,x,y,3,3,ac)
 if name=='ocean':
  for x,y in [(16,47),(89,106),(78,42)]:rect(p,w,x,y,3,1,ac);rect(p,w,x,y,1,3,ac)
 # Slim seam and metal corner caps: retains contrast behind every inventory slot.
 for y in range(37,126,5):rect(p,w,8,y,1,2,ac);rect(p,w,108,y,1,2,ac)
 for x in (5,296):
  line(p,w,x,7,x,15,ac);line(p,w,x,242,x,235,ac)
 png(f'assets/{M}/textures/gui/{name}.png',304,250,p)
print('Generated illustrated Ancient Technology book, recovery recipe and six embroidered interior linings.')
