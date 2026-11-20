"""Model and pixel atlas for the Labelled Storage Chest. Units below are block sixteenths."""

import json
from pathlib import Path
import struct
import zlib

NAMESPACE = 'anyastorage'
TILES = ('shell', 'flank', 'chin', 'lid', 'pearl', 'trim', 'rose', 'rubber',
         'liner', 'well', 'underlid', 'crest', 'badge', 'hinge', 'steel', 'heart')
SIZE = 128


def rgb(value):
    return tuple(bytes.fromhex(value))


class Tile:
    def __init__(self, color):
        self.pixels = [rgb(color)] * (32 * 32)

    def rect(self, x, y, w, h, color):
        color = rgb(color)
        for j in range(max(y, 0), min(y + h, 32)):
            for i in range(max(x, 0), min(x + w, 32)):
                self.pixels[32 * j + i] = color

    def line(self, points, color):
        for (x, y), (u, v) in zip(points, points[1:]):
            steps = max(abs(u-x), abs(v-y), 1)
            for n in range(steps+1):
                self.rect(round(x+(u-x)*n/steps), round(y+(v-y)*n/steps), 1, 1, color)

    def star(self, x, y, radius, color):
        for dy in range(-radius, radius+1):
            width = max(0, radius-2*abs(dy))
            self.rect(x-width, y+dy, 2*width+1, 1, color)


def make_tiles():
    tiles = {}
    def material(name, bands):
        tile = tiles[name] = Tile(bands[0])
        for i,col in enumerate(bands):
            y = round(i*32/len(bands))
            tile.rect(0,y,32,round((i+1)*32/len(bands))-y,col)
        return tile
    shell = material('shell',['655781','70638E','665B81','615677','544D6D','484460'])
    shell.line([(0,8),(4,8),(8,12),(8,22),(6,24),(0,24)],'463D5D')
    shell.line([(0,7),(5,7),(9,11),(9,22)],'9A8BAF')
    shell.line([(31,8),(27,8),(23,12),(23,22),(25,24),(31,24)],'463D5D')
    shell.line([(31,7),(26,7),(22,11),(22,22)],'9A8BAF')
    for x in (3,27):
        shell.rect(x,13,2,2,'CCC1CD'); shell.rect(x,15,2,1,'584965')
    flank = material('flank',['847596','8B7CA1','827596','796B89','665E7D','504B66'])
    flank.line([(0,9),(5,9),(8,6),(25,6),(29,10),(31,10)],'B6A8BC')
    flank.line([(0,11),(5,11),(8,8),(24,8),(28,12),(31,12)],'514962')
    for x in (7,11,15):
        flank.rect(x,18,2,5,'53485E'); flank.rect(x,23,2,1,'CCADC0')
    flank.rect(21,19,6,3,'423B50'); flank.rect(22,19,4,1,'9CE6D9')
    chin = material('chin',['5B546F','534D69','393748','38384D'])
    chin.line([(0,15),(7,15),(10,19),(21,19),(24,15),(31,15)],'716781')
    lid = material('lid',['F1D8E0','DAC2D1','C9B0CB','B8A3BE','A28CA9','957E9B'])
    lid.line([(0,20),(6,20),(8,18),(12,18)],'CBB4C5')
    lid.line([(31,20),(25,20),(23,18),(19,18)],'CBB4C5')
    material('pearl',['ECDCE7','E5D4E2','DACBDD','D5C2D5'])
    material('trim',['E7DCE4','B0A7BE','AA9BB1','8A7A95'])
    material('rose',['F9DFDF','EBA9BC','CE839D','B47591'])
    material('rubber',['453B52','2B263C','383349','272537'])
    liner = material('liner',['474360','323247','302E42','252637'])
    for x in (2,13,24):
        liner.line([(x,30),(x,17),(x+2,15),(x+2,4)],'59506C')
    well = material('well',['3C3A55']*3)
    well.line([(3,7),(7,3),(24,3),(28,7),(28,24),(24,28),(7,28),(3,24),(3,7)],'615671')
    well.line([(7,12),(12,7),(21,7)],'8F7896'); well.star(16,17,3,'AA889F')
    under = material('underlid',['676178','61596E','5C536B'])
    under.line([(3,8),(8,3),(23,3),(28,8),(28,23),(23,28),(8,28),(3,23),(3,8)],'A18C98')
    under.line([(6,9),(9,6),(22,6),(25,9)],'C7A7AC')
    for x in (7,21):
        under.rect(x,17,4,2,'534858'); under.rect(x+1,17,2,1,'D49AAA')
    crest = material('crest',['4F476B','534C72','504969','49435E'])
    # An asymmetric lunar inlay and its single companion star, worked into a small enamel crown.
    crest.line([(1,9),(7,3),(24,3),(30,9),(30,22),(24,28),(7,28),(1,22),(1,9)],'AE8DA7')
    crest.line([(4,10),(9,5),(24,5)],'D5B1BF')
    for y in range(8,24):
        for x in range(6,23):
            if (x-14)**2+(y-16)**2<=57 and (x-18)**2+(y-12)**2>45:
                crest.rect(x,y,1,1,'FFE5D9' if x<12 or y>20 else 'E8B2CA')
    crest.star(21,12,3,'C2FAEC'); crest.rect(24,18,1,2,'D8C2DD')
    badge = material('badge',['6F5D73','55485D','514356','4A3E50'])
    badge.rect(0,0,32,2,'F1C3C9'); badge.rect(0,30,32,2,'9A6F85')
    hinge = material('hinge',['4D4960','706C85','B1A3B8','736B83','474558'])
    for x in (3,25): hinge.rect(x,0,3,32,'42394E')
    material('steel',['A89BB5','998EAA','8B839C','796F89','6C6079'])
    heart = Tile('514356')
    for y,row in enumerate(('00000000','01100110','11111111','11111111','01111110','00111100','00011000','00000000')):
        for x,v in enumerate(row):
            if v=='1': heart.rect(4*x,4*y,4,4,'F299C1')
    tiles['heart'] = heart
    return tiles


def atlas(tiles):
    return bytes(channel for y in range(SIZE) for x in range(SIZE)
                 for channel in tiles[TILES[(y//32)*4+x//32]].pixels[(y%32)*32+x%32])


def png_rgb(data):
    def chunk(kind, payload):
        return struct.pack('>I',len(payload))+kind+payload+struct.pack('>I',zlib.crc32(kind+payload))
    raw = b''.join(b'\0'+data[y*SIZE*3:(y+1)*SIZE*3] for y in range(SIZE))
    return (b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',SIZE,SIZE,8,2,0,0,0))
            +chunk(b'IDAT',zlib.compress(raw,9))+chunk(b'IEND',b''))


def ring(y, rx, rz, cut, cx=8, cz=8):
    outline=[(-rx+cut,-rz),(rx-cut,-rz),(rx,-rz+cut),(rx,rz-cut),
             (rx-cut,rz),(-rx+cut,rz),(-rx,rz-cut),(-rx,-rz+cut)]
    return [(cx+x,y,cz+z) for x,z in outline]


class Shell:
    def __init__(self):
        self.parts = {'base': [], 'lid': [], 'item_label': []}

    def face(self, part, xyz, tile, uv=((0,1),(0,0),(1,0),(1,1))):
        index = TILES.index(tile)
        # Inset each UV rectangle half a texel to keep neighboring atlas cells out of its edges.
        coords = [((index%4*32+.5+x*31)/SIZE,(index//4*32+.5+y*31)/SIZE) for x,y in uv]
        self.parts[part].append((xyz,coords))

    def band(self,part,low,high,materials):
        for i in range(8):
            j=(i+1)%8
            tile = materials if isinstance(materials,str) else materials[i]
            self.face(part,[low[i],high[i],high[j],low[j]],tile)

    def cap(self,part,outline,tile,up):
        xmin=min(p[0] for p in outline); xmax=max(p[0] for p in outline)
        zmin=min(p[2] for p in outline); zmax=max(p[2] for p in outline)
        for indices in ((0,7,2,1),(7,6,3,2),(6,5,4,3)):
            points=[outline[i] for i in (indices if up else indices[::-1])]
            self.face(part,points,tile,[((x-xmin)/(xmax-xmin),(z-zmin)/(zmax-zmin)) for x,_,z in points])

    def badge_half(self,part,bottom,top,uvtop,uvbottom):
        # Half of the split latch sits on either side of the moving gasket.
        x0,x1=5.65,10.35
        self.face(part,[(x0,bottom,.86),(x0,top,.86),(x1,top,.86),(x1,bottom,.86)],'badge')
        self.face(part,[(x0,top,.86),(x0,top,1.30),(x1,top,1.30),(x1,top,.86)],'rose')
        self.face(part,[(x1,bottom,.86),(x1,top,.86),(x1,top,1.30),(x1,bottom,1.30)],'rose')
        self.face(part,[(x0,bottom,1.30),(x0,top,1.30),(x0,top,.86),(x0,bottom,.86)],'rose')
        self.face(part,[(x0,bottom,1.30),(x0,bottom,.86),(x1,bottom,.86),(x1,bottom,1.30)],'rose')
        points=[(6,bottom+.10,.84),(6,top-.1,.84),(10,top-.1,.84),(10,bottom+.1,.84)]
        self.face('item_label',points,'heart',[(0,uvbottom),(0,uvtop),(1,uvtop),(1,uvbottom)])


def make_shell():
    s = Shell()
    # Continuous octagonal surfaces use only the outer faces. The lower well is hollow.
    foot=ring(0,5.45,5.45,1.35); sole=ring(.82,6.22,6.05,1.65)
    hip=ring(2.45,6.88,6.72,2.05); waist=ring(7.63,7,7,2.25)
    edge=ring(8.04,6.92,6.93,2.14); seal=ring(8.43,6.66,6.70,2.03)
    s.cap('base',foot,'rubber',False)
    s.band('base',foot,sole,'rubber'); s.band('base',sole,hip,'chin')
    s.band('base',hip,waist,('shell','steel','flank','steel','shell','steel','flank','steel'))
    s.band('base',waist,edge,'rose'); s.band('base',edge,seal,'rubber')
    mouth=ring(8.43,5.66,5.72,1.9); floor=ring(4.10,4.85,4.86,1.6)
    s.band('base',seal,mouth,'trim'); s.band('base',mouth,floor,'liner'); s.cap('base',floor,'well',True)
    lip=ring(8.54,6.85,6.85,2.22); belt=ring(9.02,7,7,2.25)
    shoulder=ring(10.97,6.75,6.82,2.18); slope=ring(12.64,5.90,6.10,1.95)
    crown=ring(13.65,4.70,4.75,1.65); inset=ring(13.77,4.12,4.12,1.35)
    s.cap('lid',lip,'underlid',False)
    s.band('lid',lip,belt,'rubber'); s.band('lid',belt,shoulder,'lid')
    s.band('lid',shoulder,slope,'pearl'); s.band('lid',slope,crown,'lid')
    s.band('lid',crown,inset,'rose'); s.cap('lid',inset,'crest',True)
    s.badge_half('base',6.25,8.42,.5,1); s.badge_half('lid',8.55,10.72,0,.5)
    for cx in (4.35,11.65):
        bottom=ring(7.2,1.02,.7,.26,cx,14.58); top=ring(9.04,1.02,.7,.26,cx,14.58)
        s.band('base',bottom,top,'hinge'); s.cap('base',top,'steel',True)
    return s


def obj(shell):
    vertices={}; uvs={}
    for faces in shell.parts.values():
        for xyz,uv in faces:
            for point in xyz: vertices.setdefault(point,len(vertices)+1)
            for point in uv: uvs.setdefault(point,len(uvs)+1)
    lines=['mtllib chest_shell.mtl']
    lines += ['v '+' '.join(f'{n/16:.6f}' for n in point) for point in vertices]
    lines += ['vt '+' '.join(f'{n:.7f}' for n in point) for point in uvs]
    for part,faces in shell.parts.items():
        lines += ['g '+part,'usemtl ceramic']
        for xyz,uv in faces:
            lines.append('f '+' '.join(f'{vertices[p]}/{uvs[t]}' for p,t in zip(xyz,uv)))
    return '\n'.join(lines)+'\n'


def generate(root):
    assets = root/'assets'/NAMESPACE
    modeldir=assets/'models/block'
    modeldir.mkdir(parents=True,exist_ok=True)
    def write_model(name,data):
        (modeldir/(name+'.json')).write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
    modeldir.joinpath('chest_shell.obj').write_text(obj(make_shell()),encoding='utf-8')
    modeldir.joinpath('chest_shell.mtl').write_text(
        'newmtl ceramic\nKd 1 1 1\nKa 0 0 0\nmap_Kd anyastorage:block/chest_shell\n',encoding='utf-8')
    parent={'parent':'minecraft:block/block','loader':'neoforge:obj','model':'anyastorage:models/block/chest_shell.obj',
            'automatic_culling':False,'emissive_ambient':False,'flip_v':False,
            'textures':{'particle':'anyastorage:block/chest_shell'}}
    write_model('chest',parent)
    for moving_part in ('base','lid'):
        write_model('chest_'+moving_part,{**parent,'visibility':{
            'base':moving_part=='base','lid':moving_part=='lid','item_label':False}})
    path=assets/'textures/block/chest_shell.png'; path.parent.mkdir(parents=True,exist_ok=True)
    path.write_bytes(png_rgb(atlas(make_tiles())))


if __name__ == '__main__':
    generate(Path(__file__).resolve().parents[1]/'src/main/resources')
