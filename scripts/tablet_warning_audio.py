"""Author the tablet's single quiet glass note using the UwU cube chime's upper partials."""
import argparse
from pathlib import Path
from subprocess import run

import numpy as np

from framed_chest_audio import SAMPLE_RATE, envelope


def blip():
    duration = .145
    time = np.arange(round(SAMPLE_RATE * duration)) / SAMPLE_RATE
    # The same 495 Hz voice as the opening cube, without its latch or first note.
    phase = 2 * np.pi * 495 * time
    tone = np.sin(phase) + .19 * np.sin(2.02 * phase)
    tone *= envelope(time, 0, .007, .030) * np.clip((duration - time) / .012, 0, 1)
    return np.rint(tone * (.20 * 32767 / np.max(np.abs(tone)))).astype("<i2")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    destination = Path(__file__).resolve().parents[1] / "src/main/resources/assets/anyastorage/sounds/tablet/warning.ogg"
    destination.parent.mkdir(parents=True, exist_ok=True)
    run([args.ffmpeg, "-y", "-loglevel", "error", "-f", "s16le", "-ar", str(SAMPLE_RATE), "-ac", "1",
         "-i", "pipe:0", "-map_metadata", "-1", "-c:a", "libvorbis", "-q:a", "5", "-fflags", "+bitexact",
         "-flags:a", "+bitexact", str(destination)], input=blip().tobytes(), check=True)
