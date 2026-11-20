"""Pixel art and recessed monitor for the Storage Hub. All screen motion is a baked idle loop."""

from chest_shell import Tile


BASE = 'assets/anyastorage'
FRAME_COUNT = 6


def faceplate():
    tile = Tile('364557')
    tile.rect(0, 0, 32, 2, '93A1AC')
    tile.rect(1, 2, 30, 20, '6D7C87')
    tile.rect(2, 2, 28, 20, 'CDD7D3')
    tile.rect(2, 3, 28, 1, 'E4E9DF')
    tile.rect(3, 5, 26, 16, '52626C')
    tile.rect(3, 5, 26, 1, '263441')
    tile.rect(4, 20, 24, 1, 'A7B2AF')
    # An amber standby lamp and two ceramic keys sit below the sunken glass.
    tile.rect(1, 23, 30, 5, '1D2937')
    tile.rect(2, 23, 28, 1, '8897A1')
    tile.rect(4, 25, 3, 1, 'FAB773')
    tile.rect(9, 25, 7, 1, '47596A')
    for x in (21, 25):
        tile.rect(x, 24, 3, 3, '5F747E')
        tile.rect(x, 24, 3, 1, 'BAC7BF')
    tile.rect(0, 29, 32, 3, '232D3D')
    tile.rect(3, 30, 26, 1, '354353')
    return tile


def cabinet():
    side = Tile('344455')
    side.rect(0, 0, 32, 2, '869AA9')
    side.rect(0, 2, 32, 1, '536778')
    side.rect(1, 4, 30, 24, '263443')
    side.rect(2, 5, 28, 22, '485A69')
    # Louvers and recessed corner fasteners make the access cabinet legible from its flank.
    for y in (10, 13, 16, 19):
        side.rect(7, y, 18, 2, '24313D')
        side.rect(7, y + 2, 18, 1, '627584')
    for x in (3, 27):
        for y in (6, 24):
            side.rect(x, y, 2, 1, '92A2A7')
    side.rect(0, 29, 32, 3, '232D3D')
    top = Tile('596A78')
    top.rect(1, 1, 30, 30, '7C8B94')
    top.rect(2, 2, 28, 28, '566775')
    # An inset service handle, cast into the top so adjacent blocks can still sit flush.
    top.rect(8, 11, 16, 9, '3A4B58')
    top.rect(9, 12, 14, 7, '263440')
    top.rect(10, 14, 12, 3, '8C9D9F')
    top.rect(10, 16, 12, 1, '5F747F')
    for x in (4, 26):
        top.rect(x, 4, 2, 1, 'B0BCBC')
        top.rect(x, 27, 2, 1, '384B59')
    return side, top


def signal_frame(beat):
    # Only the central 24 x 14 texels appear through the aperture.
    glass = Tile('10252E')
    glass.rect(5, 7, 22, 12, '17323B')
    glass.rect(6, 7, 15, 1, '29474F')
    glass.rect(21, 18, 4, 1, '244149')
    paths = (
        [(6, 11), (9, 11), (11, 13), (14, 13)],
        [(25, 9), (22, 9), (20, 11), (18, 11)],
        [(24, 16), (21, 16), (19, 14), (18, 14)],
    )
    for path in paths:
        glass.line(path, '3F6E73')
    # The terminal symbol stays visible as little points converge on it and fade.
    glass.line([(14, 10), (17, 10), (18, 11), (18, 14), (17, 15),
                (14, 15), (13, 14), (13, 11), (14, 10)], '81BFB7')
    positions = (
        [(6, 11), (8, 11), (10, 12), (11, 13), (12, 13)],
        [(25, 9), (23, 9), (21, 10), (20, 11), (19, 11)],
        [(24, 16), (22, 16), (21, 16), (20, 15), (19, 14)],
    )
    for index, route in enumerate(positions):
        step = (beat + 2 * index) % FRAME_COUNT
        if step < len(route):
            glass.rect(*route[step], 1, 1, '8EF0D2')
    glass.rect(15, 12, 2, 2, 'DAF4DB' if beat in (3, 4) else '5E9A96')
    return glass


def model():
    def face(material, uv, cull=None):
        data = {'texture': '#' + material, 'uv': uv}
        if cull is not None:
            data['cullface'] = cull
        return data

    def element(lo, hi, faces):
        return {'from': lo, 'to': hi, 'faces': faces}

    # Outer cube and front surround never share overlapping face areas. The glass is
    # one model unit behind the bezel. Inner reveal quads point into the open recess.
    faces = {direction: face('side', [0, 0, 16, 16], direction)
             for direction in ('east', 'west', 'south', 'down')}
    faces['up'] = face('top', [0, 0, 16, 16], 'up')
    pieces = [element([0, 0, 0], [16, 16, 16], faces)]
    for x0, y0, x1, y1 in ((0, 0, 16, 6), (0, 13, 16, 16), (0, 6, 2, 13), (14, 6, 16, 13)):
        uv = [16 - x1, 16 - y1, 16 - x0, 16 - y0]
        pieces.append(element([x0, y0, 0], [x1, y1, 0],
                              {'north': face('front', uv, 'north')}))
    for lo, hi, direction in (([2, 6, 0], [2, 13, 1], 'east'),
                              ([14, 6, 0], [14, 13, 1], 'west'),
                              ([2, 13, 0], [14, 13, 1], 'down'),
                              ([2, 6, 0], [14, 6, 1], 'up')):
        pieces.append(element(lo, hi, {direction: face('side', [0, 15, 1, 16], 'north')}))
    pieces.append(element([2, 6, 1], [14, 13, 1],
                          {'north': face('screen', [2, 3, 14, 10], 'north')}))
    textures = {name: 'anyastorage:block/hub_' + name for name in ('side', 'front', 'top', 'screen')}
    return {'parent': 'minecraft:block/block', 'textures': {**textures, 'particle': '#side'}, 'elements': pieces}


def generate(write_png, write_json):
    side, top = cabinet()
    textures = {'side': [side], 'front': [faceplate()], 'top': [top],
                'screen': [signal_frame(beat) for beat in range(FRAME_COUNT)]}
    for name, frames in textures.items():
        rgba = [pixel + (255,) for frame in frames for pixel in frame.pixels]
        write_png(f'{BASE}/textures/block/hub_{name}.png', 32, 32 * len(frames), rgba)
    write_json(f'{BASE}/textures/block/hub_screen.png.mcmeta',
               {'animation': {'frametime': 5, 'interpolate': False}})
    write_json(f'{BASE}/models/block/hub.json', model())
