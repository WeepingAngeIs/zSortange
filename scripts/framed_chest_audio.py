"""Recreate two original relay sounds. Requires NumPy and FFmpeg with libvorbis, only for authoring."""
import argparse
from pathlib import Path
from shutil import which
from subprocess import run

import numpy as np


SAMPLE_RATE = 44100
DESTINATION = Path(__file__).resolve().parents[1] / "src/main/resources/assets/anyastorage/sounds/frame"


def envelope(t, start, attack, decay):
    age = np.maximum(t - start, 0)
    return -np.expm1(-age / attack) * np.exp(-age / decay)


def band_noise(white, low_hz, high_hz):
    # Difference of two one-pole lowpasses removes both the DC rumble and the sharp white-noise edge.
    def smooth(cutoff):
        lifetime = SAMPLE_RATE / (2 * np.pi * cutoff)
        kernel = np.exp(-np.arange(int(10*lifetime)+1) / lifetime)
        return np.convolve(white, kernel / kernel.sum(), mode="full")[:len(white)]
    noise = smooth(high_hz) - smooth(low_hz)
    return noise / np.sqrt(np.mean(noise**2))


def contact(t, when, white, strength):
    age = np.maximum(t - when, 0)
    vibration = sum(a * np.sin(2*np.pi*hz*age) * envelope(t,when,0.001,decay)
                    for hz, a, decay in [(271, .45, .018), (548, .30, .011), (983, .12, .006)])
    grain = .22 * band_noise(white, 310, 1500) * envelope(t, when, .0007, .0038)
    return strength * (vibration + grain)


def coil(t, start, settled_hz, sweep, decay):
    age = np.maximum(t-start, 0)
    # Integrated exponential glide mimics a short magnetic impulse settling into the cabinet.
    phase = 2*np.pi * (settled_hz*age + sweep*.030 * (-np.expm1(-age/.030)))
    modes = np.sin(phase) + .47*np.sin(2*phase+.2) + .19*np.sin(3*phase+.45)
    return modes * envelope(t, start, .009, decay)


def synthesize(on):
    duration = .53 if on else .35
    t = np.arange(round(duration*SAMPLE_RATE)) / SAMPLE_RATE
    white = np.random.default_rng(0x2861 if on else 0x2862).standard_normal(t.size)
    air = band_noise(white, 90, 825)
    if on:
        audio = contact(t,.006,white,.78) + contact(t,.027,white,.24)
        audio += .75*coil(t,.014,89,59,.066)
        audio += .21*np.sin(2*np.pi*156*t) * envelope(t,.016,.006,.047)
        audio += .14*np.sin(2*np.pi*116*t) * envelope(t,.037,.012,.077)
        audio += .075*air*envelope(t,.009,.009,.042)
        peak = .25
    else:
        audio = contact(t,.007,white,.49) + contact(t,.023,white,.13)
        audio += .60*coil(t,.010,76,35,.047)
        audio += .18*np.sin(2*np.pi*173*t) * envelope(t,.012,.004,.032)
        audio += .055*air*envelope(t,.008,.004,.027)
        peak = .14
    # Finish at zero, including any minute residual energy in the decay.
    audio *= np.clip((duration-t)/.030,0,1)
    return np.rint(audio * (32767*peak / np.max(np.abs(audio)))).astype("<i2")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", default=which("ffmpeg") or "ffmpeg", help="path to the FFmpeg encoder")
    args = parser.parse_args()
    DESTINATION.mkdir(parents=True,exist_ok=True)
    for on, name in [(True,"on"),(False,"off")]:
        run([args.ffmpeg, "-y", "-loglevel","error", "-f","s16le", "-ar",str(SAMPLE_RATE), "-ac","1",
             "-i","pipe:0", "-map_metadata","-1", "-c:a","libvorbis", "-q:a","5", "-fflags","+bitexact",
             "-flags:a","+bitexact", str(DESTINATION/f"{name}.ogg")],
            input=synthesize(on).tobytes(),check=True)
