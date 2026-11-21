"""Pixel-art medallions. Marks use a two-pixel grid so they read on a 16px hotbar."""
from math import hypot

# Rim, enamel, lit metal, face marking; symbols are embossed at native pixel boundaries.
DISCS = {
    'pickup': ('a74b65','542c48','ffacc0','ffe4d4', (
        '##   ##','##   ##','++   ++','##   ##',' ## ## ','  ###  ')),
    'feeding': ('b77e3d','593e36','ffca77','ffedac', (
        '    ## ','   #   ',' ## ## ','#######','#######',' ##### ','  # #  ')),
    'refill': ('249484','154753','8ce4bd','e1ffe1', (
        '  #### ',' ##  ##','##    #','++   ##','    ###','   ####','     ##')),
    'crafting': ('a06c45','50353a','e8b886','ffe0a2', (
        '#######','# # # #','#######','# # # #','#######',' #   # ')),
    'smelting': ('ba583a','5e303d','ffa862','ffeab2', (
        '   ##  ','  ###  ','  ### #',' ######','#######','##++###',' ##++# ')),
    # A little furnace with one glowing billet, set inside a smooth manual-tier rim.
    'manual_smelting': ('ae86bb','483952','ffb1d3','c7f2ff', (
        '#######','#     #','#######','#     #','# +++ #','#     #','#######')),
    # Two wisps above an open smoker rack; the blast furnace has stepped bellows and a tuyere.
    'manual_smoking': ('ac84bb','493650','ffb3cf','bef1fa', (
        ' +  +  ','  +  + ',' +  +  ','       ','#######',' # # # ','#######')),
    'manual_blasting': ('9295c7','433851','ffb2ce','bef1ff', (
        '  +    ',' ++  + ',' +++++ ','       ',' ###   ','#####  ',' ### ##')),
    'compacting': ('7d69b8','3f355f','c7a8ee','f3dcff', (
        '#######','  ###  ',' ##### ','       ','>#####<',' ##### ','#######')),
    'resupply': ('417da9','253f60','8dd6eb','e0fbff', (
        '  ###  ',' ##### ','   #   ','## + ##','#######','#     #','#######')),
}
DRAGON = ('  #   # ', '  ## ## ', '  ##### ', ' ## +###', '####### ', ' ##  ## ', '  ##    ', '   #### ')


def rgb(value):
    return (*bytes.fromhex(value),255)


def shift(color, delta):
    return tuple(max(0,min(255,c+delta)) for c in color[:3]) + (255,)


def badge(palette):
    rim, face, edge, ink = map(rgb,palette)
    pixels = [(0,0,0,0)] * 1024
    for y in range(32):
        for x in range(32):
            dx, dy = x-15.5, y-15
            radius = hypot(dx,dy*1.04)
            if radius > 14.6:
                if hypot(dx,(dy-1.5)*1.04) < 14.7:
                    pixels[y*32+x] = shift(rim,-63)
                continue
            light = -dx-dy*1.2
            if radius > 13.6: color = shift(rim,-59)
            elif radius > 11.7: color = edge if light > 7 else rim if light > -10 else shift(rim,-28)
            elif radius > 10.8: color = shift(face,-17)
            elif radius > 9.8: color = shift(rim,9) if light < 1 else shift(face,12)
            else: color = shift(face,8 if light>1 else 0)
            pixels[y*32+x] = color
    for x,y in ((6,9),(8,7),(10,5),(20,4),(26,22)):
        pixels[y*32+x] = shift(edge,20)
    return pixels


def glyph(pixels, rows, ink, accent, shadow):
    w=max(map(len,rows)); left=(32-2*w)//2; top=15-len(rows)
    for ox,oy,outline in ((1,2,True),(0,0,False)):
        for row,line in enumerate(rows):
            for column,c in enumerate(line):
                if c == ' ': continue
                color = shadow if outline else accent if c in '+<>' else ink
                for y in range(2):
                    for x in range(2):
                        pixels[(top+row*2+oy+y)*32+left+column*2+ox+x] = color


def core():
    # A faceted suspended crystal with a gold waist. Its tall cut is unlike the round tokens.
    pixels=[(0,0,0,0)]*1024
    for y in range(2,30):
        half=min((y-1)*1.12, 11.7, (30-y)*.94)
        for x in range(32):
            dx=x-15.5
            if abs(dx)>half: continue
            if abs(dx)>half-1.3 or y in (2,29): c='29364d'
            elif y in (16,17,18): c='ffdb91' if dx<-2 else 'b17450'
            elif dx<-.40*half: c='bff9f0' if y<14 else '5ca7bd'
            elif dx<.2*half: c='70dbe3' if y<14 else '397fa0'
            else: c='448eac' if y<14 else '475482'
            pixels[y*32+x]=rgb(c)
    return pixels


def generate(write):
    for key, (rim, face, edge, ink, rows) in DISCS.items():
        pixels=badge((rim,face,edge,ink))
        glyph(pixels,rows,rgb(ink),rgb(edge),shift(rgb(face),-23))
        write(key+'_upgrade',pixels)
    # Two raised tabs identify the advanced tier in monochrome as well.
    advanced = {
        'advanced_pickup': ('  ###  ', ' ## ## ', ' ##### ', '  ###  ', '   #   ', '       ', '+     +', ' +++++ '),
        # Clock hands above a heating element, housed in the twin-tab automatic casing.
        # Repeat each station's silhouette with a clock dial and the two raised automatic-tier tabs.
        'auto_smoking': ('  ###  ', ' # # # ', ' # ++# ', '  ###  ', ' +  +  ', '  +  + ', '#######', ' # # # '),
        'auto_blasting': ('  ###  ', ' # # # ', ' # ++# ', '  ###  ', '       ', ' ###   ', '#####  ', ' ### ##'),
        'auto_smelting': ('  ###  ', ' # # # ', '#  #  #', '#  ++ #', ' #   # ', '  ###  ', '       ', ' +++++ '),
        'advanced_compacting': ('  ###  ', '   #   ', '+++++++', '       ', ' ## ## ', ' ## ## ', '       ', '+++++++'),
    }
    for key, rows in advanced.items():
        pixels = badge(('ae8bc8','423653','d6c5fd','b6eafe'))
        glyph(pixels, rows, rgb('b7efff'), rgb('ffaed2'), rgb('30273e'))
        for left in (10,18):
            for y in range(0,5):
                for x in range(left,left+4):
                    pixels[y*32+x] = rgb('eddbff' if y < 2 else '846493')
        write(key+'_upgrade',pixels)
    token=badge(('b38251','564354','ffe0a2','ffe7ac'))
    glyph(token,DRAGON,rgb('ffdc88'),rgb('694764'),rgb('3b2b46'))
    write('dragon_victory_token',token)
    write('storage_core',core())
