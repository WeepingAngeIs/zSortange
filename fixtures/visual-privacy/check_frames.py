"""Compare the two clients' entrance pixels with the materials in the tested Storage Plus JAR."""
import hashlib
import io
import json
from pathlib import Path
import sys
from zipfile import ZipFile

from PIL import Image, ImageDraw, ImageFilter, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
from check_pad_preview_frames import likeness, roi, sample_frame
from pocket_layout_art import compose

ROLES = {"Alpha": "south", "Beta": "east"}
PHASES = ("public", "granted", "revoked")
ART2 = "0a7eb462e4256518b2bed9b52e782b80649a03f4068ebdad0f6742a1a6515ea7"


def materials(jar):
    with ZipFile(jar) as archive:
        def texture(name):
            data = archive.read(f"assets/anyastorage/textures/entity/{name}.png")
            with Image.open(io.BytesIO(data)) as image:
                return image.convert("RGBA")

        public = texture("starter_room_study")
        atlas = texture("pocket_pad_plan")
    images = {"public": public, "diagram": compose(atlas, 143), "empty_eighth": compose(atlas, 15)}
    return {name: image.convert("RGB").resize((96, 96), Image.Resampling.LANCZOS).filter(
        ImageFilter.GaussianBlur(.55)) for name, image in images.items()}


def comparison_sheet(shots, target):
    width, height = 330, 202
    left, top = 108, 56
    sheet = Image.new("RGB", (left + width * 3 + 10, top + 2 * (height + 8)), "#19212a")
    draw = ImageDraw.Draw(sheet)
    try:
        label = ImageFont.truetype("DejaVuSans.ttf", 19)
        small = ImageFont.truetype("DejaVuSans.ttf", 14)
    except OSError:
        label = small = ImageFont.load_default()
    for col, title in enumerate(("Before trust", "Alpha has View", "View revoked")):
        draw.text((left + col * width + 8, 20), title, fill="white", font=label)
    for row, role in enumerate(ROLES):
        y = top + row * (height + 8)
        draw.text((14, y + height // 2 - 20), role, fill="white", font=label)
        draw.text((14, y + height // 2 + 8), ROLES[role] + " face", fill="#d0dae5", font=small)
        for col, phase in enumerate(PHASES):
            source = shots[role, phase]
            # Crop the same fraction of each real framebuffer around the entrance crosshair.
            w, h = source.size
            cw = min(round(w * .43), round(h * .84))
            ch = round(cw * height / (width - 8))
            box = ((w-cw)//2, (h-ch)//2, (w+cw)//2, (h+ch)//2)
            crop = source.crop(box).resize((width-8, height), Image.Resampling.LANCZOS)
            sheet.paste(crop, (left + col * width, y))
    sheet.save(target)


def compare(evidence, jar):
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    if digest != ART2:
        raise ValueError("Frame references require the reviewed Storage Plus art.2 binary")
    refs = materials(jar)
    observations, shots, masks = {}, {}, {}
    directory = evidence / "control"
    inner = (6, 6, 90, 90)
    eighth = (168, 124, 198, 152)
    errors = []
    for role, side in ROLES.items():
        for phase in PHASES:
            name = f"{phase}-{role}"
            mask = 143 if (phase, role) == ("granted", "Alpha") else -1
            expected = "diagram" if mask >= 0 else "public"
            other = "public" if mask >= 0 else "diagram"
            camera = json.loads((directory / f"{name}-camera.json").read_text())
            state = json.loads((directory / f"{name}-state.json").read_text())
            if (state["mask"] != mask or state["role"] != role or state["phase"] != phase
                    or state["side"] != side or camera["side"] != side
                    or camera["entrance"] != [8, 81, 1]):
                raise ValueError(f"{name} has an inconsistent player, camera or client state")
            png = directory / f"{name}.png"
            with Image.open(png) as image:
                if image.format != "PNG" or image.width < 640 or image.height < 400:
                    raise ValueError(f"{name} is missing a full-resolution PNG framebuffer")
                if list(image.size) != [camera["width"], camera["height"]]:
                    raise ValueError(f"{name} has inconsistent framebuffer dimensions")
                shots[role, phase] = image.convert("RGB")
                face = sample_frame(image, camera)
            masks[role, phase] = face.crop(inner)
            scores = {key: likeness(face.crop(inner), refs[key].crop(inner)) for key in ("public", "diagram")}
            observations[name] = {"material": expected, "correlations": scores, "mask": mask,
                                  "raw_sha256": hashlib.sha256(png.read_bytes()).hexdigest()}
            if scores[expected] < .70 or scores[expected] < scores[other] + .15:
                errors.append(f"{name} fails to distinguish {expected} from {other}")
            if mask >= 0:
                occupied = likeness(roi(face, eighth), roi(refs["diagram"], eighth))
                empty = likeness(roi(face, eighth), roi(refs["empty_eighth"], eighth))
                observations[name]["eighth_pad"] = {"occupied": occupied, "empty": empty}
                if occupied < .48 or occupied <= empty + .12:
                    errors.append("The authorized diagram fails to identify the occupied eighth pad")
    repeats = {
        "Alpha_public_after_revoke": likeness(masks["Alpha", "public"], masks["Alpha", "revoked"]),
        "Beta_public_during_grant": likeness(masks["Beta", "public"], masks["Beta", "granted"]),
        "Beta_public_after_revoke": likeness(masks["Beta", "public"], masks["Beta", "revoked"]),
    }
    if min(repeats.values()) < .74:
        errors.append("Two captures expected to show the same public entrance do not match")
    comparison_sheet(shots, evidence / "comparison.png")
    result = {"jar_sha256": digest, "frames": observations, "same_material_within_each_camera": repeats,
              "errors": errors, "passed": not errors}
    (evidence / "frame-check.json").write_text(json.dumps(result, indent=2) + "\n")
    if errors:
        raise ValueError("; ".join(errors))
    print("Verified six entrance framebuffers, including the occupied pad and public image after revocation.")


if __name__ == "__main__":
    compare(Path(sys.argv[1]), Path(sys.argv[2]))
