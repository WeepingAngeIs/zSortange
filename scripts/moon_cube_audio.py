"""Author the UwU Storage Cube's short open and close cues with NumPy and FFmpeg."""

import argparse
from pathlib import Path
from subprocess import run

import numpy as np

from framed_chest_audio import SAMPLE_RATE, band_noise, envelope


def chime(opening):
    duration = .51 if opening else .36
    t = np.arange(round(SAMPLE_RATE*duration))/SAMPLE_RATE
    white = np.random.default_rng(216 if opening else 217).standard_normal(t.size)
    # The latch releases with a small ceramic knock and a two-note, glassy reply.
    pitch = 118 if opening else 89
    out = .42*np.sin(2*np.pi*pitch*t)*envelope(t, .005, .002, .043)
    out += .12*band_noise(white, 190, 2200)*envelope(t, .005, .001, .014)
    notes = [(0.020, 330, .24), (.082, 495, .20)] if opening else [(.011, 330, .16), (.055, 220, .20)]
    for start, hz, level in notes:
        age = np.maximum(t-start, 0)
        glass = np.sin(2*np.pi*hz*age) + .19*np.sin(2*np.pi*2.02*hz*age)
        out += level*glass*envelope(t, start, .006, .094 if opening else .057)
    out *= np.clip((duration-t)/.035, 0, 1)
    return np.rint(out / np.max(np.abs(out)) * (.28 if opening else .23) * 32767).astype('<i2')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', default='ffmpeg')
    args = parser.parse_args()
    destination = Path(__file__).resolve().parents[1]/'src/main/resources/assets/anyastorage/sounds/cube'
    destination.mkdir(parents=True, exist_ok=True)
    for opening, name in [(True, 'open'), (False, 'close')]:
        run([args.ffmpeg, '-y', '-loglevel', 'error', '-f', 's16le', '-ar', str(SAMPLE_RATE), '-ac', '1',
             '-i', 'pipe:0', '-map_metadata', '-1', '-c:a', 'libvorbis', '-q:a', '5', '-fflags', '+bitexact',
             '-flags:a', '+bitexact', str(destination/f'{name}.ogg')],
            input=chime(opening).tobytes(), check=True)
