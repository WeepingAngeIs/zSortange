"""Check the packaged private warning cue and its duration using the Vorbis stream metadata."""
import json
from pathlib import Path
import struct
import sys
from zipfile import ZipFile


resource = "assets/anyastorage/"
sample = resource + "sounds/tablet/warning.ogg"
with ZipFile(sys.argv[1]) as jar:
    event = json.loads(jar.read(resource + "sounds.json"))["tablet_warning"]
    assert event == {"sounds": ["anyastorage:tablet/warning"]}, "Warning needs one sample and no subtitle"
    audio = jar.read(sample)
    assert audio == (Path(__file__).resolve().parents[2] / "src/main/resources" / sample).read_bytes()

# The first packet is the Vorbis identification header, including the channel count and sample rate.
offset = 0
while offset < len(audio):
    assert audio[offset:offset+5] == b"OggS\x00"
    count = audio[offset+26]
    body = offset + 27 + count
    if offset == 0:
        assert audio[body:body+7] == b"\x01vorbis"
        assert audio[body+11] == 1, "Only mono data belongs in this centered private cue"
        rate, = struct.unpack_from("<I",audio,body+12)
        assert rate == 44100
    final_frame, = struct.unpack_from("<q",audio,offset+6)
    end = body + sum(audio[offset+27:body])
    assert offset < end <= len(audio)
    if end == len(audio):
        assert audio[offset+5] & 4, "Missing Ogg end-of-stream"
        assert .144 <= final_frame/rate <= .146, "The warning should end after one short blip"
    offset = end
print("PASS: one 145 ms mono warning sample in the JAR, no subtitle or directional cues")
