"""Recreate the entirely synthetic, public HTTPS and file-picker test inputs."""
from pathlib import Path
import shutil
import subprocess
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parent / "inputs"
root.mkdir(exist_ok=True)
ffmpeg = shutil.which("ffmpeg")
if not ffmpeg:
    import imageio_ffmpeg
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()


def card(color, number):
    image = Image.new("RGB", (160, 96), color)
    draw = ImageDraw.Draw(image)
    draw.line((0, 0, 159, 0, 159, 95), fill="white", width=4)
    for i in range(number):
        draw.rectangle((5 + 8 * i, 5, 9 + 8 * i, 13), fill="white")
    return image


frames = [card((224, 28, 33), 1), card((22, 208, 40), 2)]
video = b"".join(frames[(i // 10) % 2].tobytes() for i in range(40))
subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "rawvideo", "-pixel_format", "rgb24", "-s", "160x96", "-framerate", "10", "-i", "-",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100:duration=4",
                "-map", "0:v:0", "-map", "1:a:0", "-c:v", "libx264", "-profile:v", "baseline",
                "-level", "2.0", "-pix_fmt", "yuv420p", "-bf", "0", "-g", "10", "-threads", "1",
                "-c:a", "aac", "-profile:a", "aac_low", "-ac", "1", "-b:a", "48k",
                "-movflags", "+faststart", "-map_metadata", "-1", str(root / "color-tone.mp4")], input=video, check=True)
a, b = card((30, 50, 225), 3), card((231, 210, 32), 4)
a.save(root / "cards.gif", save_all=True, append_images=[b], duration=[700, 700], loop=0, disposal=2)

# A longer, low-frame-rate version leaves time for a third player to physically enter
# the real pocket and test late audio, personal volume/Stop and a grant revocation.
subprocess.run([ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-stream_loop", "-1", "-i", str(root / "color-tone.mp4"), "-t", "52",
                "-vf", "fps=4,scale=96:56", "-c:v", "libx264", "-profile:v", "baseline",
                "-pix_fmt", "yuv420p", "-bf", "0", "-g", "4", "-threads", "1",
                "-c:a", "aac", "-profile:a", "aac_low", "-ac", "1", "-ar", "44100", "-b:a", "48k",
                "-movflags", "+faststart", "-map_metadata", "-1", str(root / "room-color-tone.mp4")],check=True)
