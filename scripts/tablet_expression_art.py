"""Bake Cloud's body gestures, expressions and head turns for the offline tablet display.

Each row is one continuous visit. The nine cells in an opening row follow the
existing double blink; the idle rows brighten from and settle into dark glass.
"""
import argparse
import hashlib
from pathlib import Path

import numpy as np
from PIL import Image

from cupid_face_render import avatar_triangles, load_model, render_pose
from tablet_guardian_art import GUI, glass, phosphor_input


SOURCE_SHA256 = "2c7ff2b20aabb475df9ffdb6d666cfe752987ffdf51c3e2477b7a5d9dceb736e"
MODEL = "Anya_Cupid_Expressions/models/model.bbmodel"
OUTPUT = GUI / "tablet_expressions.png"
CELL = (224, 128)
GLASS_CROP = (8, 8, 232, 136)
DARK = (8, 16, 26)

# Tile names and their 48x24 face overlays come directly from Cloud's expressions.png.
NEUTRAL, MURMUR, OPEN_MOUTH, BLUSH, SMUG, TONGUE, WINK, GIGGLE, POUT, SMUG_BLINK = range(10)
OPENING_LIDS = (0, .185, .4, .185, 0, .185, .4, .185, 0)
OPENING_TIMES = (0, 260, 310, 390, 440, 610, 660, 760, 810)
IDLE_TIMES = (0, 85, 175, 275, 385, 635, 760, 885, 1010)
IDLE_LIGHT = (.34, .78, 1, 1, 1, 1, 1, .73, .26)

# Each key gives (yaw, pitch, roll, iris X, iris Y). Positive pitch looks from
# above and positive iris X moves toward screen-left. Interpolate the rigid head
# and pupil positions together to preserve perspective on the horns and curls.
OPENINGS = (
    # Near-front, with the idle mouth opening into a shy flush.
    (((3, 2, -2, -.08, .02), (0, 1.4, 0, 0, 0), (-4, 3, 2, .20, -.02)),
     (NEUTRAL, MURMUR, MURMUR, MURMUR, OPEN_MOUTH, BLUSH, BLUSH, BLUSH, BLUSH)),
    # A sideways look, then an open-eyed tongue-out smile.
    (((-18, 4, -6, -.22, .08), (-10, 6, -4, -.04, .08), (-15, 7, -2, .30, .02)),
     (MURMUR, MURMUR, MURMUR, TONGUE, TONGUE, TONGUE, TONGUE, TONGUE, TONGUE)),
    # A high view with an upturned gaze, a pink pout and a last-beat wink.
    (((12, 12, 6, .25, .23), (8, 8, 3, .12, .18), (17, 10, 6, -.13, .10)),
     (POUT, POUT, POUT, POUT, BLUSH, BLUSH, BLUSH, BLUSH, WINK)),
    # Seen from below, Cupid dips her eyes toward the tablet and smirks.
    (((-11, -10, 3, -.06, -.22), (-5, -7, 0, -.16, -.15), (2, -8, -4, -.32, -.17)),
     (SMUG, SMUG, SMUG_BLINK, SMUG, SMUG, SMUG, SMUG_BLINK, SMUG, SMUG)),
)

IDLES = (
    # Notice the viewer, meet their eyes with a blush, then quietly turn aside.
    (((-23, 4, -5, .38, 0), (0, 2, 0, 0, .02), (22, 4, 4, -.43, -.06)),
     (NEUTRAL, MURMUR, MURMUR, BLUSH, BLUSH, BLUSH, BLUSH, MURMUR, NEUTRAL),
     (0, 0, 0, 0, 0, 0, .16, .28, 0)),
    # Lean in from above and share a quick giggle before looking down and away.
    (((17, 10, 7, -.35, -.05), (3, 5, -3, .03, .17), (-24, -4, -6, .43, -.19)),
     (NEUTRAL, MURMUR, OPEN_MOUTH, WINK, GIGGLE, GIGGLE, BLUSH, BLUSH, NEUTRAL),
     (0, 0, 0, 0, 0, 0, 0, .16, 0)),
    # Rise into view, glance up, pull a face and peek away from below the glass.
    (((-19, -11, -5, .30, -.22), (-3, -5, 4, -.03, .10), (19, 7, 7, -.45, .20)),
     (POUT, POUT, MURMUR, TONGUE, TONGUE, TONGUE, OPEN_MOUTH, MURMUR, NEUTRAL),
     (0, .13, .4, .13, 0, 0, 0, 0, 0)),
)


def staging_angles(keys, time, middle, end):
    """Ease to eye contact, then ease out toward the final eyeline."""
    first, second = keys[:2] if time <= middle else keys[1:]
    t = time / middle if time <= middle else (time - middle) / (end - middle)
    t = t*t*(3 - 2*t)
    return np.asarray(first) + t*(np.asarray(second) - first)


# Shoulder angles follow the model's right arm (viewer-left). The wrist has no
# independent bone; use the authored shoulder and forearm to sweep the hand.
WAVE = (9, 24, 51, 91, 47, 89, 51, 34, 12)
TUMBLE_ROLL = (179, 179, 169, 120, 41, -12, 8, 2, 0)
BODY = ("whole", "body")
RIGHT = BODY + ("RightArm",)
LEFT = BODY + ("LeftArm",)


def body_pose(row, column, angles):
    yaw, pitch, roll, _, _ = angles
    beat = column / 8
    lift = np.sin(np.pi * beat)
    # Keep an upper-body crop on most openings for legible expressions. The
    # wider visits show the skirt, two feet and shifting balance against the screen.
    camera_yaw = (7, 4, -18, 12, 9, -3, -14, 14)[row]
    camera_pitch = (3, 5, 9, -5, 3, 5, -3, 2)[row]
    scale = (6.6, 6.15, 5.65, 6.35, 4.45, 5.05, 4.4, 4.0)[row]
    center_y = (17.0, 16.7, 15.9, 16.6, 12.9, 14.1, 12.3, 12.6)[row]
    camera_roll = 0
    pan = (2, -2, 1.0, -1.5, -1.1, 2, -2.2, 0)[row]
    gestures = {
        BODY: (0, -4 + 6*lift, -2 + 4*lift),
        BODY + ("head",): (pitch, -yaw, roll),
        RIGHT: (16, -8, 8 + 4*lift),
        LEFT: (8, 3, -12 - 5*lift),
        ("whole", "Rightleg"): (-3, 2, 9),
        ("whole", "Leftleg"): (2, -4, -8),
    }
    if row in (1, 5):
        # Greeting and giggle. Keep the hand clear of the near twin-tail.
        gestures[RIGHT] = (18 + 5*lift, -10, WAVE[column])
        gestures[RIGHT + ("arn",)] = (6, -3, 30 + 9*lift)
        gestures[BODY + ("head",)] = (pitch, -yaw, roll + 19*lift)
    elif row in (2, 6):
        # Peek up on one toe as the other leg swings out.
        gestures[("whole", "Leftleg")] = (18*lift, -4, -12 - 13*lift)
        gestures[LEFT] = (23, 9, -17 - 19*lift)
        center_y -= .65*lift
    elif row in (0, 4):
        gestures[LEFT] = (13 + 24*lift, 10, -14 - 18*lift)
        gestures[LEFT + ("bone",)] = (14*lift, 0, 12*lift)
    elif row == 7:
        # Two held upside-down beats let the player read the boots overhead.
        # Swing both arms out for balance, overshoot upright, wave, then recede.
        camera_roll = TUMBLE_ROLL[column]
        pan = (-5.4, -4.7, -3.3, -2.2, 0, .5, 1.2, 2.9, 5.6)[column]
        gestures[RIGHT] = (30, -10, (32, 43, 57, 68, 87, 106, 73, 99, 48)[column])
        gestures[LEFT] = (12, 7, -34 - 33*lift)
        gestures[("whole", "Leftleg")] = (16, -8, -18 - 8*lift)
        gestures[("whole", "Rightleg")] = (-10, 4, 12)
    return gestures, (pan, center_y, -1), scale, (camera_yaw, camera_pitch, camera_roll)


def cell(model, textures, angles, expression, eyelids, brightness, row, column):
    gestures, center, scale, camera = body_pose(row, column, angles)
    body = avatar_triangles(model, textures, eyelids, angles[3:], expression, rotations=gestures)
    portrait = render_pose(body, textures, (448, 256), yaw=camera[0], pitch=camera[1], roll=camera[2],
                           center=center, scale=scale)
    mask = np.asarray(portrait.getchannel("A")) > 32
    lit = glass(phosphor_input(portrait), mask).crop(GLASS_CROP)
    return lit if brightness == 1 else Image.blend(Image.new("RGB", CELL, DARK), lit, brightness)


def indexed_glass(atlas):
    """Keep the full blue range, including rare catchlights, within three RGB levels."""
    pixels = np.asarray(atlas).reshape(-1, 3)
    colors, inverse, counts = np.unique(pixels, axis=0, return_inverse=True, return_counts=True)
    colors = colors.astype(np.int32)
    palette = []
    distance = np.full(len(colors), np.iinfo(np.int32).max)
    nearest = np.zeros(len(colors), dtype=np.uint8)
    next_color = counts.argmax()
    for index in range(min(256, len(colors))):
        chosen = colors[next_color]
        palette.append(chosen)
        squared = np.sum((colors - chosen)**2, axis=1)
        nearest[squared < distance] = index
        distance = np.minimum(distance, squared)
        next_color = distance.argmax()
    palette = np.asarray(palette)
    if np.max(np.abs(colors - palette[nearest])) > 3:
        raise ValueError("Expression atlas exceeds its three-level RGB tolerance")
    indices = nearest[inverse].reshape(atlas.height, atlas.width)
    indexed = Image.frombytes("P", atlas.size, indices.tobytes())
    indexed.putpalette(palette.astype(np.uint8).tobytes())
    return indexed


def build(source, output):
    if hashlib.sha256(source.read_bytes()).hexdigest() != SOURCE_SHA256:
        raise ValueError("Expected the supplied Anya Cupid - expressions (Cloud).zip")
    model, textures = load_model(source, MODEL)
    atlas = Image.new("RGB", (9*CELL[0], (len(OPENINGS) + len(IDLES))*CELL[1]))
    for row, (poses, expressions) in enumerate(OPENINGS):
        for column, (ms, tile, lid) in enumerate(zip(OPENING_TIMES, expressions, OPENING_LIDS, strict=True)):
            values = staging_angles(poses, ms, 440, 810)
            atlas.paste(cell(model, textures, values, tile, lid, 1, row, column), (column*CELL[0], row*CELL[1]))
    for row, (poses, expressions, lids) in enumerate(IDLES, len(OPENINGS)):
        for column, (ms, tile, lid, brightness) in enumerate(zip(IDLE_TIMES, expressions, lids, IDLE_LIGHT, strict=True)):
            values = staging_angles(poses, ms, 385, 1010)
            atlas.paste(cell(model, textures, values, tile, lid, brightness, row, column), (column*CELL[0], row*CELL[1]))
    tumble = Image.new("RGB", (9*CELL[0], CELL[1]))
    for column, (tile, lid, brightness) in enumerate(zip(
            (MURMUR, OPEN_MOUTH, POUT, OPEN_MOUTH, MURMUR, BLUSH, WINK, BLUSH, NEUTRAL),
            (0, 0, 0, .13, 0, .18, 0, 0, 0), IDLE_LIGHT, strict=True)):
        angles = staging_angles(((13, -5, -3, .17, .22), (-6, -1, 4, 0, .05),
                                 (16, 4, 2, -.18, 0)), IDLE_TIMES[column], 385, 1010)
        tumble.paste(cell(model, textures, angles, tile, lid, brightness, 7, column), (column*CELL[0], 0))
    output.parent.mkdir(parents=True, exist_ok=True)
    indexed_glass(atlas).save(output, optimize=True)
    indexed_glass(tumble).save(output.with_name("tablet_tumble.png"), optimize=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("--output", type=Path, default=OUTPUT)
    args = parser.parse_args()
    build(args.source, args.output)
