"""Cyber moon chest. The starter's bevels, hollow well and split latch share one topology."""

import json

from chest_shell import Tile, atlas, make_shell, obj, png_rgb


# Also used by ChestRenderer to align the hinge and the editable latch symbol.
WIDTH, HEIGHT, DEPTH = 1.10, 1.105, 1.075
ICE, MINT, CYAN = 'EBE9FF', 'ABF8EB', '57C9D1'
PINK, VIOLET = 'FFABDA', 'B9A2EB'
SEAM, NIGHT = '211C35', '28293F'


def finish(*bands):
    tile = Tile(bands[0])
    for i, color in enumerate(bands):
        y = i*32//len(bands)
        tile.rect(0, y, 32, (i+1)*32//len(bands)-y, color)
    return tile


def make_moon_tiles():
    tiles = {
        'shell': finish('4B4A78', '424366', '383958', '302F50', '282941'),
        'flank': finish('56567F', '494C73', '414363', '353951', '2B2D47'),
        'chin': finish('393B56', '37394F', '292C41', SEAM),
        'lid': finish('C7BFEB', 'ABA3D7', '938ABF', '817AA7', '686385'),
        'pearl': finish('DCD5F2', 'D0C7E8', 'B9AFD7', 'A298C5'),
        'trim': finish(ICE, 'A9E4E9', '6EA9C3', '606C96'),
        'rose': finish(MINT, '81E0DE', CYAN, '5294A4'),
        'rubber': finish(SEAM, '222638', NIGHT, '1D1E2E'),
        'liner': finish('3D425F', '343C56', '29324B', '252B40'),
        'well': finish('252D46', '242D49', NIGHT),
        'underlid': finish('60617D', '535575', '4C506C', '41465C'),
        'crest': finish('3A3760', '353653', '30324E', '302E4D'),
        'badge': finish('463B5D', '38304D', '362E4C'),
        'hinge': finish('3D3853', '5D627F', '8D91B1', '5A6179', '39394C'),
        'steel': finish('9194B8', '8286A8', '6A7293', '5E637E', '4D536E'),
        'heart': Tile('38304D'),
    }
    shell = tiles['shell']
    # A little baked-in face below the separately editable split label.
    shell.rect(7, 16, 18, 10, '21243C')
    shell.line([(7, 25), (8, 26), (23, 26), (24, 25)], '555274')
    for x in (9, 19):
        shell.line([(x, 18), (x, 19), (x+1, 20), (x+2, 20), (x+3, 19), (x+3, 18)], MINT)
    shell.line([(13, 21), (14, 22), (15, 22), (16, 21), (17, 22), (18, 22), (19, 21)], PINK)
    shell.rect(4, 9, 2, 2, VIOLET)
    shell.rect(26, 9, 2, 2, VIOLET)
    shell.line([(0, 7), (3, 7), (7, 11), (7, 13)], '8684B1')
    shell.line([(31, 7), (28, 7), (24, 11), (24, 13)], '8684B1')
    flank = tiles['flank']
    flank.line([(1, 7), (7, 7), (11, 11), (18, 11), (22, 7), (30, 7)], '928DB6')
    for x in (5, 9, 13):
        flank.rect(x, 16, 2, 8, SEAM)
        flank.rect(x, 23, 2, 1, '6D7899')
    flank.rect(21, 19, 7, 4, NIGHT)
    flank.rect(22, 20, 3, 1, MINT)
    flank.rect(26, 20, 1, 1, PINK)
    for x in (4, 23):
        tiles['chin'].line([(x, 4), (x, 13), (x+4, 17), (x+4, 23)], '535774')
        tiles['hinge'].rect(x, 0, 2, 32, SEAM)
    tiles['lid'].line([(0, 17), (5, 17), (8, 20), (12, 20)], 'D3CBEB')
    tiles['lid'].line([(31, 17), (26, 17), (23, 20), (19, 20)], 'D3CBEB')
    tiles['badge'].rect(0, 0, 32, 2, CYAN)
    tiles['badge'].rect(0, 30, 32, 2, '8180B1')
    for x in (3, 12, 22):
        tiles['liner'].line([(x, 3), (x, 16), (x+4, 20), (x+4, 27)], '536784')
        tiles['liner'].rect(x, 3, 2, 2, CYAN)

    ring = [(3, 8), (8, 3), (23, 3), (28, 8), (28, 23), (23, 28), (8, 28), (3, 23), (3, 8)]
    for tile_name, ink in (('well', '6294AB'), ('underlid', '9EA2C3'), ('crest', '8585AC')):
        tiles[tile_name].line(ring, ink)
    tiles['well'].line([(4, 19), (8, 19), (12, 15), (18, 15), (22, 11), (26, 11)], CYAN)
    tiles['well'].star(16, 16, 3, MINT)
    tiles['underlid'].line([(7, 12), (10, 9), (21, 9), (24, 12)], 'AFB7D0')
    for x in (10, 14, 18):
        tiles['underlid'].rect(x, 19, 3, 1, MINT)
    crest = tiles['crest']
    for y in range(6, 26):
        for x in range(5, 25):
            if (x-14)**2 + (y-16)**2 <= 73 and (x-18)**2 + (y-12)**2 > 59:
                crest.rect(x, y, 1, 1, ICE if x < 12 or y > 20 else 'B9D5E9')
    crest.star(22, 12, 3, PINK)
    crest.rect(23, 20, 1, 1, MINT)
    crest.rect(9, 7, 1, 1, VIOLET)
    for y, row in enumerate(('00000000', '01100110', '11111111', '11111111',
                              '01111110', '00111100', '00011000', '00000000')):
        for x, bit in enumerate(row):
            if bit == '1':
                tiles['heart'].rect(4*x, 4*y, 4, 4, PINK)
    return tiles


def make_moon_shell():
    shell = make_shell(rear_panel='flank')
    for group in shell.parts:
        shell.parts[group] = [
            ([(8 + (x-8)*WIDTH, y*HEIGHT, 8 + (z-8)*DEPTH) for x, y, z in points], uv)
            for points, uv in shell.parts[group]
        ]
    return shell


def generate(root):
    assets = root/'assets/anyastorage'
    modeldir = assets/'models/block'
    modeldir.mkdir(parents=True, exist_ok=True)
    (modeldir/'uwu_cube_shell.obj').write_text(obj(make_moon_shell(), 'uwu_cube_shell.mtl'), encoding='utf-8')
    (modeldir/'uwu_cube_shell.mtl').write_text(
        'newmtl ceramic\nKd 1 1 1\nKa 0 0 0\nmap_Kd anyastorage:block/uwu_cube_shell\n', encoding='utf-8')
    resource = {
        'parent': 'minecraft:block/block', 'loader': 'neoforge:obj',
        'model': 'anyastorage:models/block/uwu_cube_shell.obj', 'automatic_culling': False,
        'emissive_ambient': False, 'flip_v': False, 'textures': {'particle': 'anyastorage:block/uwu_cube_shell'}
    }
    (modeldir/'uwu_cube.json').write_text(json.dumps(resource, indent=2)+'\n', encoding='utf-8')
    for part in ('base', 'lid'):
        visible = {key: key == part for key in ('base', 'lid', 'item_label')}
        model = {**resource, 'visibility': visible}
        (modeldir/f'uwu_cube_{part}.json').write_text(json.dumps(model, indent=2)+'\n', encoding='utf-8')
    textures = assets/'textures/block'
    textures.mkdir(parents=True, exist_ok=True)
    (textures/'uwu_cube_shell.png').write_bytes(png_rgb(atlas(make_moon_tiles())))
