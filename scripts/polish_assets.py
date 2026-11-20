"""Readable embroidered front badges and refined ear/leaf silhouettes, built from the same original asset palette."""
import generate_expansion_assets as g
from generate_expansion_assets import ROOT,M,ORDER,PALETTES,color,shade,rect,line,stamp,HEART,STAR,LEAF,CAT,GHOST,DIAMOND,BOW,png,js
import json,math
for name in ORDER:
 body,accent,dark,light=map(color,PALETTES[name])
 for face in ['front','pocket']:
  p=[body]*1024
  for y in range(32):
   for x in range(32):p[y*32+x]=shade(body,(1 if (x*17+y*13)%31==0 else 0)+(3 if y<9 else -3 if y>24 else 0))
  rect(p,32,0,0,32,2,dark);rect(p,32,0,30,32,2,dark);rect(p,32,0,0,2,32,dark);rect(p,32,30,0,2,32,dark)
  for t in range(6,27,7):
   rect(p,32,t,3,1,1,shade(body,17));rect(p,32,t,28,1,1,shade(body,17))
  if face=='pocket':
   rect(p,32,4,5,24,1,accent);rect(p,32,4,7,24,1,dark)
   if 'cat' in name:
    # A large readable face, not a tiny emblem hidden behind the buckles.
    rect(p,32,8,13,17,10,light);rect(p,32,6,15,21,6,light)
    rect(p,32,8,10,4,5,light);rect(p,32,21,10,4,5,light)
    rect(p,32,9,11,2,2,accent);rect(p,32,22,11,2,2,accent)
    rect(p,32,11,17,2,3,dark);rect(p,32,21,17,2,3,dark);rect(p,32,16,19,3,2,color('D6A75D'))
    for y in (17,20):rect(p,32,4,y,4,1,dark);rect(p,32,25,y,4,1,dark)
    stamp(p,32,BOW,19,10,color('E78BB4'))
   elif 'spooky' in name:
    stamp(p,32,GHOST,9,10,light,2);rect(p,32,13,16,2,3,dark);rect(p,32,19,16,2,3,dark)
    if name=='cute_spooky':rect(p,32,10,20,3,1,color('F0A9C3'));rect(p,32,21,20,3,1,color('F0A9C3'))
   elif name=='galaxy':
    stamp(p,32,STAR,9,10,light,2)
    for x,y in [(6,11),(24,24),(5,23),(24,10)]:rect(p,32,x,y,2,1,accent)
   elif 'leaf' in name or name in ('oak','cherry','dark_oak'):
    stamp(p,32,LEAF,9,10,accent,2);line(p,32,12,24,20,13,dark);line(p,32,16,19,15,15,dark);line(p,32,18,17,23,16,dark)
   elif name=='water':
    stamp(p,32,['   #   ','  ###  ',' ##### ','#######','#######',' ##### ','  ###  '],9,10,accent,2)
    line(p,32,13,18,13,22,light);rect(p,32,14,22,3,1,light)
   elif name=='magma':
    stamp(p,32,['   #   ','  ##   ','  ###  ',' ##### ','## # ##','##   ##',' ##### '],9,10,accent,2);rect(p,32,15,20,3,4,light)
   elif name in ('diamond','stone','futuristic'):
    stamp(p,32,DIAMOND,9,10,accent,2);line(p,32,16,12,11,18,light);line(p,32,10,18,23,18,dark);line(p,32,16,12,19,18,light)
   else:stamp(p,32,HEART,9,12,accent,2)
  else:
   # Embroidered pictogram with a one-pixel offset shadow.
   emblem=LEAF if 'leaf' in name or name in ('oak','cherry','dark_oak') else CAT if 'cat' in name else GHOST if 'spooky' in name else STAR if name=='galaxy' else DIAMOND if name in ('diamond','stone') else HEART
   stamp(p,32,emblem,13,7,dark);stamp(p,32,emblem,12,6,accent)
   if emblem==LEAF:
    vein=shade(accent,-35);line(p,32,13,13,17,8,vein);line(p,32,15,10,14,8,vein);line(p,32,16,9,18,9,vein)
   if 'cat' in name:
    rect(p,32,14,9,1,1,dark);rect(p,32,17,9,1,1,dark)
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
 path=f'assets/{M}/models/item/backpack/{name}.json'; model=json.loads((ROOT/path).read_text());elements=model['elements']
 for d in ['north','south']:elements[1]['faces'][d]['texture']='#top'
 elements[2]['to'][1]=8.2
 for index,x in [(10,3.4),(11,11.4)]:elements[index]['from']=[x,9.5,10];elements[index]['to']=[x+1.2,11.5,10.7]
 def box(a,b,front='trim'):
  faces={d:{'texture':'#side','uv':[0,0,16,16]} for d in ['north','south','west','east','up','down']}
  for d in ['north','south']:faces[d]['texture']='#'+front
  elements.append({'from':a,'to':b,'faces':faces})
 if 'cat' in name:
  del elements[12:]
  for x in (3,10):
   box([x,14,9],[x+3,14.8,10.5]);box([x+.6,14.8,9.2],[x+2.4,16,10.4]);box([x+1.1,16,9.4],[x+1.9,17,10.2])
 if 'leaf' in name or name in ('oak','cherry','dark_oak'):
  del elements[12:]
  box([12,11,9],[15,11.8,9.6]);box([13,11.8,9],[15,13.3,9.6]);box([13.6,13.3,9],[14.5,14,9.6]);box([1,9,8],[3,9.8,8.6])
 js(path,model)
print('Refined readable front badges, clasp placement and blocky ear/leaf shapes.')
