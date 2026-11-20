"""Check the packaged cabinet's glass, fascia, facing variants and idle animation."""

import argparse
from io import BytesIO
import json
from pathlib import Path
from zipfile import ZipFile

from PIL import Image, ImageChops


def check(jar_path, preview):
    base = 'assets/anyastorage/'
    with ZipFile(jar_path) as jar:
        model = json.loads(jar.read(base + 'models/block/hub.json'))
        item = json.loads(jar.read(base + 'models/item/hub.json'))
        variants = json.loads(jar.read(base + 'blockstates/hub.json'))['variants']
        assert item['parent'] == 'anyastorage:block/hub'
        assert len(variants) == 4
        for index, direction in enumerate(('north', 'east', 'south', 'west')):
            variant = variants['facing=' + direction]
            assert variant['model'] == item['parent'] and variant.get('y', 0) == index * 90

        front = [[[] for _ in range(16)] for _ in range(16)]
        for part in model['elements']:
            face = part['faces'].get('north')
            if face is None:
                continue
            x0, y0, z0 = part['from']
            x1, y1, z1 = part['to']
            assert z0 == z1 and face['uv'] == [16-x1, 16-y1, 16-x0, 16-y0]
            for x in range(x0, x1):
                for y in range(y0, y1):
                    front[y][x].append((z0, face['texture']))
        for y, row in enumerate(front):
            for x, surfaces in enumerate(row):
                expected = (1, '#screen') if 2 <= x < 14 and 6 <= y < 13 else (0, '#front')
                assert surfaces == [expected], f'gap or overlapping surfaces at {x}, {y}'
        for part in model['elements']:
            for face in part['faces'].values():
                material = model['textures'][face['texture'].removeprefix('#')]
                assert material.startswith('anyastorage:block/')
                jar.getinfo(base + 'textures/' + material.split(':')[1] + '.png')
        def texture(name):
            data = jar.read(base + f'textures/block/hub_{name}.png')
            with Image.open(BytesIO(data)) as image:
                return image.convert('RGB')

        panel = texture('front')
        assert all(tile.size == (32, 32) for tile in (panel, texture('side'), texture('top')))
        screen = texture('screen')
        meta = json.loads(jar.read(base + 'textures/block/hub_screen.png.mcmeta'))['animation']
        assert screen.size == (32, 192) and meta['frametime'] == 5 and not meta['interpolate']
        frames = []
        aperture = (4, 6, 28, 20)
        for phase in range(6):
            glass = screen.crop((0, phase * 32, 32, (phase + 1) * 32))
            image = panel.copy()
            image.paste(glass.crop(aperture), aperture[:2])
            frames.append(image)
        assert len({frame.tobytes() for frame in frames}) == 6, 'Static or duplicate glass frame'
        for before, after in zip(frames, frames[1:] + frames[:1]):
            delta = ImageChops.difference(before, after)
            changed = sum(pixel != (0, 0, 0) for pixel in delta.getdata())
            assert 1 <= changed <= 16, f'Unexpected idle flash affecting {changed} pixels'
    if preview:
        preview.parent.mkdir(parents=True, exist_ok=True)
        large = [frame.resize((384, 384), Image.Resampling.NEAREST) for frame in frames]
        large[0].save(preview, save_all=True, append_images=large[1:], duration=250, loop=0)
    print('Hub package passed: four facings, single glass surface, six distinct idle frames.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('jar', type=Path)
    parser.add_argument('--preview', type=Path, help='Save a straight-on GIF of the packaged textures')
    args = parser.parse_args()
    check(args.jar, args.preview)
