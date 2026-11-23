"""Roll back one already-saved Anvil entity chunk between disposable server processes."""
from hashlib import sha256
import os
from pathlib import Path
import struct


SECTOR = 4096
# Chunk (2, 0) in the disposable world's overworld. The Java probe parks both original drops here.
SLOT = 2


def record(region):
    with region.open("rb") as file:
        file.seek(SLOT * 4)
        location, = struct.unpack(">I", file.read(4))
        offset, sectors = location >> 8, location & 255
        if offset < 2 or not sectors:
            raise ValueError("The original entity chunk was never persisted")
        file.seek(offset * SECTOR)
        header = file.read(5)
        size, codec = struct.unpack(">IB", header)
        if not 1 < size <= sectors * SECTOR - 4 or codec not in (1, 2, 3, 4):
            raise ValueError("Unexpected fixture entity chunk framing")
        packed = file.read(size - 1)
        if len(packed) != size - 1:
            raise ValueError("Truncated entity chunk")
        return header + packed


def capture(game):
    region = game / "history-world/entities/r.0.0.mca"
    original = record(region)
    (game / "earlier-entity-chunk.bin").write_bytes(original)
    return sha256(original).hexdigest()


def reinsert(game):
    region = game / "history-world/entities/r.0.0.mca"
    original = (game / "earlier-entity-chunk.bin").read_bytes()
    sectors = (len(original) + SECTOR - 1) // SECTOR
    if not 1 <= sectors < 255:
        raise ValueError("Expected one small inline entity chunk")
    with region.open("r+b") as file:
        current_size = file.seek(0, os.SEEK_END)
        if not 2 * SECTOR <= current_size < 128 << 20 or current_size % SECTOR:
            raise ValueError("Unexpected disposable entity region size")
        offset = current_size // SECTOR
        file.write(original.ljust(sectors * SECTOR, b"\x00"))
        file.seek(SLOT * 4)
        file.write(struct.pack(">I", offset << 8 | sectors))
        file.flush()
        os.fsync(file.fileno())
    if record(region) != original:
        raise ValueError("Entity chunk did not round-trip byte for byte")
    player_id = (game / "rollback-owner-id.txt").read_text().strip()
    from uuid import UUID
    player_file = game / "history-world/playerdata" / (str(UUID(player_id)) + ".dat")
    previous = (game / "between-returns-player.dat").read_bytes()
    if not player_file.is_file() or not previous or player_file.read_bytes() == previous:
        raise ValueError("No independently saved later player inventory to roll back")
    # This copy contains one delivered bag. Its bytes come from an ordinary player save, not an edited inventory.
    temp = player_file.with_suffix(".fixture-temp")
    with temp.open("xb") as file:
        file.write(previous)
        file.flush()
        os.fsync(file.fileno())
    os.replace(temp, player_file)
    return "INJECTED original entity chunk SHA-256 " + sha256(original).hexdigest() + \
           ", earlier saved playerdata SHA-256 " + sha256(previous).hexdigest()
