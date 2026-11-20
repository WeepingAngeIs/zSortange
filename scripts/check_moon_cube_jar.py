"""Check the shipped cube's model groups, single surfaces, lid sweep, sounds and existing IDs."""

import json
import math
from pathlib import Path
import struct
import sys
from zipfile import ZipFile


ASSETS = 'assets/anyastorage/'


def read_shell(data):
    vertices, groups = [], {}
    seen = set()
    for line in data.decode().splitlines():
        words = line.split()
        if words[0] == 'v':
            vertices.append(tuple(map(float, words[1:])))
        elif words[0] == 'g':
            faces = groups[words[1]] = []
        elif words[0] == 'f':
            points = [vertices[int(corner.split('/')[0])-1] for corner in words[1:]]
            key = tuple(sorted(points))
            assert len(points) == 4 and len(set(points)) == 4 and key not in seen, line
            seen.add(key)
            faces.append(points)
    return groups


def check_sweep(groups):
    actual = [p for name in ('base', 'lid') for face in groups[name] for p in face]
    assert .955 < max(x for x, _, _ in actual)-min(x for x, _, _ in actual) < .98
    assert .94 < max(y for _, y, _ in actual) < .97
    assert all(0 <= component <= 1 for vertex in actual for component in vertex)
    # Drawn bounds in ChestRenderer include the open hinge in all horizontal facings.
    hinge_y = (8.54 / 16) * 1.105
    hinge_z = .5 + (14.58/16 - .5) * 1.075
    for degrees in range(73):
        c, s = math.cos(math.radians(degrees)), math.sin(math.radians(degrees))
        for face in groups['lid']:
            for _, y, z in face:
                py = hinge_y + (y-hinge_y)*c - (z-hinge_z)*s
                pz = hinge_z + (y-hinge_y)*s + (z-hinge_z)*c
                assert 0 <= py <= 1.65 and -.25 <= pz <= 1.25, (degrees, py, pz)
    top = max(v[1] for f in groups['lid'] for v in f)
    assert max(hinge_y+(y-hinge_y)*c-(z-hinge_z)*s for f in groups['lid'] for _, y, z in f) > top+.4


def main(path):
    with ZipFile(path) as jar:
        def resource(name):
            return jar.read(ASSETS + name)

        for part in ('uwu_cube', 'uwu_cube_base', 'uwu_cube_lid'):
            model = json.loads(resource(f'models/block/{part}.json'))
            assert model['loader'] == 'neoforge:obj'
            assert model['model'] == 'anyastorage:models/block/uwu_cube_shell.obj'
            if part == 'uwu_cube':
                assert 'visibility' not in model
            else:
                group = part.removeprefix('uwu_cube_')
                assert model['visibility'] == {g: g == group for g in ('base', 'lid', 'item_label')}
        model = resource('models/block/uwu_cube_shell.obj')
        assert model.startswith(b'mtllib uwu_cube_shell.mtl')
        assert b'map_Kd anyastorage:block/uwu_cube_shell' in resource('models/block/uwu_cube_shell.mtl')
        groups = read_shell(model)
        assert set(groups) == {'base', 'lid', 'item_label'} and len(groups['item_label']) == 2
        check_sweep(groups)
        atlas = resource('textures/block/uwu_cube_shell.png')
        assert struct.unpack('>II', atlas[16:24]) == (128, 128)
        assert atlas != resource('textures/block/chest_shell.png')
        item = json.loads(resource('models/item/uwu_cube.json'))
        assert item['parent'] == 'anyastorage:block/uwu_cube'
        states = json.loads(resource('blockstates/uwu_cube.json'))['variants']
        assert all(states['facing='+face] == {'model': item['parent'], 'y': angle}
                   for face, angle in zip(('north', 'east', 'south', 'west'), (0, 90, 180, 270)))
        assert json.loads(jar.read('data/anyastorage/recipe/uwu_cube.json'))['type'] == 'anyastorage:cube'
        sounds = json.loads(resource('sounds.json'))
        language = json.loads(resource('lang/en_us.json'))
        cues = []
        for cue in ('open', 'close'):
            entry = sounds['cube_' + cue]
            assert entry['sounds'] == ['anyastorage:cube/' + cue] and language[entry['subtitle']]
            audio = resource(f'sounds/cube/{cue}.ogg')
            assert audio.startswith(b'OggS') and b'\x01vorbis' in audio
            assert audio != resource(f'sounds/chest/{cue}.ogg')
            cues.append(audio)
        assert cues[0] != cues[1]
    print('PASS: packaged cube geometry has separate base/lid groups, a bounded 72-degree swing, '
          'distinct chimes and the original item, orientation and recipe links.')


if __name__ == '__main__':
    main(Path(sys.argv[1]))
