"""Check the candidate's two players' captured entrance faces against its packaged materials."""
import hashlib
import io
import json
from pathlib import Path
from statistics import median
import sys
from zipfile import ZipFile

from PIL import Image, ImageDraw, ImageFilter, ImageFont
from check_frames import ROLES, likeness, roi, sample_frame


# Expected results from the independent server edits and permission changes.
ALPHA = {
    "public": 0, "granted": 1, "changed": 2, "cell_denied": 0,
    "resumed": 1, "revoked": 0, "regranted": 2, "final_public": 0,
}
ASSETS = ("starter_room_study", "synthetic_peek_cyan", "synthetic_peek_purple")
TILE = (119, 66, 134, 80)


def read(path):
    return json.loads(path.read_text())


def require(condition, message):
    if not condition:
        raise ValueError(message)


def reference(archive, name):
    raw = archive.read(f"assets/anyastorage/textures/entity/{name}.png")
    with Image.open(io.BytesIO(raw)) as img:
        return img.convert("RGB").resize((96, 96), Image.Resampling.LANCZOS).filter(ImageFilter.GaussianBlur(.55))


def swatch(face):
    return [median(channel) for channel in zip(*roi(face, TILE).getdata())]


def write_sheet(captures, target):
    phases = {"public": "Before grant", "granted": "Cyan marker", "changed": "Purple marker", "revoked": "View revoked"}
    w, h = 250, 165
    sheet = Image.new("RGB", (88 + w * len(phases), 48 + 2 * (h + 6)), "#19212a")
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype("DejaVuSans.ttf", 18)
    except OSError:
        font = ImageFont.load_default()
    for col, (phase, label) in enumerate(phases.items()):
        draw.text((90 + col*w, 12), label, font=font, fill="white")
        for row, role in enumerate(ROLES):
            image = captures[role, phase]
            x, y = image.width // 2, image.height // 2
            width = image.width // 4
            height = width * h // w
            crop = image.crop((x-width, y-height, x+width, y+height))
            sheet.paste(crop.resize((w-5, h), Image.Resampling.LANCZOS), (88+col*w, 48+row*(h+6)))
            if col == 0:
                draw.text((8, 48+row*(h+6)+h//2), role, font=font, fill="white")
    sheet.save(target)


def compare(evidence, jar):
    control = evidence / "control"
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    for role in (*ROLES, "server"):
        require(read(control / f"runtime-mods-{role}.json")["anyastorage"]["sha256"] == digest,
                f"{role} did not load this candidate JAR")
    scene = read(control / "server-result.json")
    writes = scene["marker_writes"]
    require({k: v["block"] for k, v in writes.items()} == {
        "setup": "minecraft:cyan_concrete", "changed": "minecraft:purple_concrete",
        "cell_denied": "minecraft:cyan_concrete", "revoked": "minecraft:purple_concrete",
    } and scene["exact_cell_vetoes"] > 0, "Missing real material readbacks or exact-cell denial")
    require(read(evidence / "use-server-proof.json") == {"Alpha": 1, "Beta": 1}
            and scene["dimension_changes_during_observation"] == 0
            and scene["alpha_stopped_at_south_face"] and scene["beta_stopped_at_east_face"]
            and scene["owned_tickets_after_observation"] == scene["forced_columns_after_cleanup"] == 0,
            "Independent normal interaction, collision, or cleanup proof missing")
    with ZipFile(jar) as archive:
        refs = [reference(archive, asset) for asset in ASSETS]
    observations = {}
    captures = {}
    inner = (6, 6, 90, 90)
    for role, side in ROLES.items():
        for phase, alpha_material in ALPHA.items():
            name = f"{phase}-{role}"
            expected = alpha_material if role == "Alpha" else 0
            state = read(control / f"{name}-state.json")
            camera = read(control / f"{name}-camera.json")
            require(state["role"] == role and state["phase"] == phase and state["side"] == camera["side"] == side
                    and camera["entrance"] == [8, 81, 1] and state["still_material"] == expected,
                    f"{name}: client and camera mismatch")
            require(state["mask"] == -1 and state["positive_received"] == 0 and state["pocket_columns_current"] == 0
                    and state["portals_rendered_this_frame"] == 0 and state["stable_ticks"] >= 16,
                    f"{name}: received pad data or rendered destination columns")
            if role == "Beta":
                require(state["still_positive_received"] == 0, "Denied player received a private marker")
            if name == "revoked-Alpha":
                require(state["late_frame_rejected_after_clear"], "Missing retired frame disposition")
            if name == "regranted-Alpha":
                require(state["late_frame_rejected_after_new_grant"], "Earlier legal frame survived the new grant")
            png = control / f"{name}.png"
            with Image.open(png) as img:
                require(img.format == "PNG" and img.width >= 640 and img.height >= 400
                        and img.size == (camera["width"], camera["height"]), f"{name}: incomplete framebuffer")
                captures[role, phase] = img.convert("RGB")
                face = sample_frame(img, camera)
            whole = [likeness(face.crop(inner), ref.crop(inner)) for ref in refs]
            other = max(whole[1:]) if not expected else whole[0]
            require(whole[expected] >= .70 and whole[expected] > other + .15,
                    f"{name}: actual entrance pixels do not distinguish the expected illustration, {whole}")
            r, g, b = swatch(face)
            # The two public reference illustrations have identical geometry. Classify
            # only the marker's hue here; normalize for the face's in-game shading.
            scale = max(r, g, b, 1)
            if expected == 1:
                require((g-r)/scale >= .30 and (b-r)/scale >= .25,
                        f"{name}: expected cyan marker pixels, got {(r,g,b)}")
            elif expected == 2:
                require((r-g)/scale >= .15 and (b-g)/scale >= .27,
                        f"{name}: expected purple marker pixels, got {(r,g,b)}")
            observations[name] = {"material": ASSETS[expected], "correlations": whole, "tile_median_rgb": [r,g,b],
                                  "raw_sha256": hashlib.sha256(png.read_bytes()).hexdigest()}
    write_sheet(captures, evidence / "comparison.png")
    report = {"jar_sha256": digest, "frames": observations, "passed": True,
              "scope": "Static public corner and chest illustration plus one actual authorized material; no camera or room capture."}
    (evidence / "frame-check.json").write_text(json.dumps(report, indent=2) + "\n")
    print("Verified sixteen still-preview entrance framebuffers from the two graphical clients.")


if __name__ == "__main__":
    compare(Path(sys.argv[1]), Path(sys.argv[2]))
