#!/usr/bin/env python3
"""Generate original small pixel textures, JSON assets and an empty GameTest scene.
Only the Python standard library is needed. No assets are downloaded at build time.
"""
import gzip
import json
import math
from pathlib import Path
import struct
import sys
import zlib

ROOT = Path(sys.argv[1] if len(sys.argv) > 1 else 'build/generated/assets')
MOD = 'anyastorage'

def write_json(path, value):
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')

def canvas(n, fill=(0, 0, 0, 0)):
    return [[fill for _ in range(n)] for _ in range(n)]

def rectangle(p, x0, y0, x1, y1, color):
    for y in range(max(0, y0), min(len(p), y1)):
        for x in range(max(0, x0), min(len(p[0]), x1)):
            p[y][x] = color

def line(p, x0, y0, x1, y1, color):
    steps = max(abs(x1-x0), abs(y1-y0), 1)
    for i in range(steps+1):
        x = round(x0+(x1-x0)*i/steps)
        y = round(y0+(y1-y0)*i/steps)
        if 0 <= y < len(p) and 0 <= x < len(p[0]):
            p[y][x] = color

def png(path, pixels):
    h, w = len(pixels), len(pixels[0])
    raw = b''.join(b'\x00'+bytes(c for rgba in row for c in rgba) for row in pixels)
    def chunk(name, data):
        return struct.pack('>I', len(data)) + name + data + struct.pack('>I', zlib.crc32(name+data) & 0xffffffff)
    data = b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))+chunk(b'IDAT', zlib.compress(raw, 9))+chunk(b'IEND', b'')
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)

def inside(x, y, polygon):
    hit = False
    j = len(polygon)-1
    for i, (a, b) in enumerate(polygon):
        c, d = polygon[j]
        if (b > y) != (d > y) and x < (c-a)*(y-b)/(d-b)+a:
            hit = not hit
        j = i
    return hit

INK=(16, 30, 43, 255)
PANEL=(35, 62, 78, 255)
LIGHT=(152, 234, 229, 255)
WHITE=(232, 247, 249, 255)
PINK=(236, 143, 193, 255)
GOLD=(238, 202, 117, 255)

def symbol(index, n=32):
    p = canvas(n)
    star = [(math.sin(i*math.pi/5)*(0.85 if i%2 == 0 else 0.36), -math.cos(i*math.pi/5)*(0.85 if i%2 == 0 else 0.36)) for i in range(10)]
    for y in range(n):
        for x in range(n):
            a, b = (x+0.5-n/2)/(n/2), (y+0.5-n/2)/(n/2)
            if index == 0:
                u, v = a*1.25, -b*1.25
                on = (u*u+v*v-0.7)**3-u*u*v*v*v < 0
            elif index == 1:
                on = inside(a, b, star)
            elif index == 2:
                on = max(abs(a), abs(b)) < 0.72
            elif index == 3:
                on = a*a+b*b < 0.62
            elif index == 4:
                on = abs(a)+abs(b) < 0.9
            elif index == 5:
                on = ((a+b)*0.7)**2/0.15+((a-b)*0.7)**2/0.72 < 1
            else:
                radius = math.hypot(a, b)
                boundary = 0.80 if int((math.atan2(b,a)+math.pi)*8/math.pi)%2 == 0 else 0.60
                on = 0.28 < radius < boundary
            if on:
                p[y][x] = PINK if index == 0 else LIGHT
    return p

for index in range(7):
    png(f'assets/{MOD}/textures/entity/label_{index}.png', symbol(index))

side = canvas(32, PANEL)
rectangle(side, 0, 0, 32, 2, INK)
rectangle(side, 0, 30, 32, 32, INK)
rectangle(side, 0, 0, 2, 32, INK)
rectangle(side, 30, 0, 32, 32, INK)
for y in (5, 25):
    rectangle(side, 5, y, 27, y+1, LIGHT)
for x in (5, 25):
    rectangle(side, x, 6, x+1, 25, (53, 89, 104, 255))
png(f'assets/{MOD}/textures/block/storage_side.png', side)

front = [row[:] for row in side]
rectangle(front, 7, 6, 25, 22, INK)
for x in range(9, 23):
    height = 2 + (x*7)%6
    rectangle(front, x, 20-height, x+1, 21, (245, 154, 74, 255))
rectangle(front, 7, 24, 25, 26, LIGHT)
png(f'assets/{MOD}/textures/block/hub_front.png', front)

cube = [row[:] for row in side]
for x in (6, 21):
    line(cube, x, 10, x+1, 14, WHITE)
    line(cube, x+1, 14, x+4, 14, WHITE)
    line(cube, x+4, 14, x+5, 10, WHITE)
line(cube, 13, 15, 14, 18, WHITE)
line(cube, 14, 18, 16, 16, WHITE)
line(cube, 16, 16, 18, 18, WHITE)
line(cube, 18, 18, 19, 15, WHITE)
heart = symbol(0, 8)
for y in range(8):
    for x in range(8):
        if heart[y][x][3]:
            cube[y+21][x+12] = heart[y][x]
png(f'assets/{MOD}/textures/block/cube_front.png', cube)
chest = [row[:] for row in side]
rectangle(chest, 2, 10, 30, 12, INK)
heart = symbol(0, 12)
for y in range(12):
    for x in range(12):
        if heart[y][x][3]:
            chest[y+6][x+10] = heart[y][x]
png(f'assets/{MOD}/textures/block/chest_front.png', chest)

body = canvas(64)
for y in range(64):
    for x in range(64):
        body[y][x] = LIGHT if x%14 == 0 or y%14 == 0 else PANEL if (x+y)%5 else (43, 76, 89, 255)
png(f'assets/{MOD}/textures/entity/storage_chest.png', body)

items=['backpack','storage_button','storage_core','dragon_victory_token','pickup_upgrade','feeding_upgrade','refill_upgrade','compacting_upgrade']
for index, name in enumerate(items):
    p=canvas(32)
    if name == 'backpack':
        rectangle(p, 10, 3, 22, 8, INK)
        rectangle(p, 12, 5, 20, 8, LIGHT)
        rectangle(p, 5, 8, 27, 29, INK)
        rectangle(p, 7, 9, 25, 27, PANEL)
        rectangle(p, 8, 10, 24, 17, LIGHT)
        rectangle(p, 10, 20, 22, 25, (73, 117, 135, 255))
        rectangle(p, 14, 15, 18, 20, GOLD)
        rectangle(p, 2, 17, 5, 26, PANEL)
        rectangle(p, 27, 17, 30, 26, PANEL)
    elif name == 'storage_button':
        for y in range(32):
            for x in range(32):
                radius=math.hypot(x-15.5,y-15.5)
                if radius < 13:
                    p[y][x]=INK if radius > 11 else WHITE if radius > 6 else LIGHT
        h=symbol(0,10)
        for y in range(10):
            for x in range(10):
                if h[y][x][3]: p[y+11][x+11]=h[y][x]
    elif name == 'dragon_victory_token':
        for y in range(32):
            for x in range(32):
                r=math.hypot(x-15.5,y-15.5)
                if r < 13: p[y][x]=GOLD if r > 10 else (83, 53, 114, 255)
        for x0,y0,x1,y1 in ((8,9,14,14),(18,9,24,14),(11,12,21,23)):
            rectangle(p,x0,y0,x1,y1,(178,143,218,255))
        rectangle(p,12,15,15,17,LIGHT)
        rectangle(p,18,15,21,17,LIGHT)
    else:
        rectangle(p,4,4,28,28,INK)
        rectangle(p,6,6,26,26,PANEL)
        for x in (2,28):
            for y in (8,14,20): rectangle(p,x,y,x+2,y+3,GOLD)
        center=symbol((index+1)%7,16)
        for y in range(16):
            for x in range(16):
                if center[y][x][3]: p[y+8][x+8]=center[y][x]
    png(f'assets/{MOD}/textures/item/{name}.png',p)
    write_json(f'assets/{MOD}/models/item/{name}.json',{'parent':'minecraft:item/generated','textures':{'layer0':f'{MOD}:item/{name}'}})

for name, face in [('storage_hub','hub_front'),('storage_chest','chest_front'),('uwu_cube','cube_front')]:
    write_json(f'assets/{MOD}/models/block/{name}.json',{'parent':'minecraft:block/orientable','textures':{'front':f'{MOD}:block/{face}','side':f'{MOD}:block/storage_side','top':f'{MOD}:block/storage_side'}})
    write_json(f'assets/{MOD}/models/item/{name}.json',{'parent':f'{MOD}:block/{name}'})
    write_json(f'assets/{MOD}/blockstates/{name}.json',{'variants':{f'facing={direction}':{'model':f'{MOD}:block/{name}','y':rotation} for direction,rotation in [('north',0),('east',90),('south',180),('west',270)]}})
    write_json(f'data/{MOD}/loot_table/blocks/{name}.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'{MOD}:{name}'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
write_json(f'data/{MOD}/recipe/storage_crafting.json',{'type':f'{MOD}:storage_crafting','category':'misc'})
write_json('data/minecraft/tags/block/mineable/pickaxe.json',{'replace':False,'values':[f'{MOD}:{x}' for x in ('storage_hub','storage_chest','uwu_cube')]})
write_json('pack.mcmeta',{'pack':{'pack_format':34,'description':"Anya's Storage Plus original assets"}})
lang={f'item.{MOD}.{name}':name.replace('_',' ').title() for name in items}
lang.update({f'block.{MOD}.storage_hub':'Storage Hub',f'block.{MOD}.storage_chest':'Storage Chest',f'block.{MOD}.uwu_cube':'UwU Cube',f'item.{MOD}.storage_button':'Storage Button',f'key.{MOD}.quickstack':'QuickStack matching items',f'key.{MOD}.dump':'Category dump',f'key.categories.{MOD}':"Anya's Storage Plus"})
write_json(f'assets/{MOD}/lang/en_us.json',lang)

# Standard NBT: empty 3x3x3 structure for headless GameTests.
def nbt_name(tag, name, payload):
    encoded=name.encode('utf-8')
    return bytes([tag])+struct.pack('>H',len(encoded))+encoded+payload

def nbt_list(name, item_type, values):
    return nbt_name(9,name,bytes([item_type])+struct.pack('>i',len(values))+b''.join(values))
air=nbt_name(8,'Name',struct.pack('>H',13)+b'minecraft:air')+b'\x00'
root=nbt_name(10,'',nbt_name(3,'DataVersion',struct.pack('>i',3955))+nbt_list('size',3,[struct.pack('>i',3)]*3)+nbt_list('palette',10,[air])+nbt_list('blocks',10,[])+nbt_list('entities',10,[])+b'\x00')
structure=ROOT/f'data/{MOD}/structure/empty.nbt'
structure.parent.mkdir(parents=True,exist_ok=True)
structure.write_bytes(gzip.compress(root,mtime=0))
print(f'Generated original assets under {ROOT}')
