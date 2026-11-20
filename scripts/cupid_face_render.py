"""Project the polished Figura avatar's head into four aligned, uncolored tablet portraits.

Only Blockbench JSON and embedded PNGs are read. Model coordinates and native texel UVs follow
the avatar's original offline front-view renderer. No Figura runtime or avatar scripts are used.
"""

import base64
import io
import json
from zipfile import ZipFile

import numpy as np
from PIL import Image


MODEL_PATH = "Anya_Cupid_Twin_Tails/models/model.bbmodel"
HEAD_PATH = ("whole", "body", "head")
AXES = "xyz"


def bone_matrix(origin, rotation, position=(0, 0, 0), scale=1):
    x, y, z = np.radians(rotation)
    cx, cy, cz = np.cos((x, y, z))
    sx, sy, sz = np.sin((x, y, z))
    rotate = np.array([
        [cz*cy, cz*sy*sx - sz*cx, cz*sy*cx + sz*sx],
        [sz*cy, sz*sy*sx + cz*cx, sz*sy*cx - cz*sx],
        [-sy, cy*sx, cy*cx],
    ]) * scale
    transform = np.eye(4)
    transform[:3, :3] = rotate
    transform[:3, 3] = np.asarray(origin) + position - rotate @ origin
    return transform


def apply_matrix(points, matrix):
    return np.asarray(points) @ matrix[:3, :3].T + matrix[:3, 3]


def element_faces(element):
    """Yield authored polygon positions, UVs and texture indices in Blockbench model space."""
    if element["type"] == "mesh":
        origin = np.array(element["origin"])
        for face in element["faces"].values():
            ids = face["vertices"]
            if face.get("texture") is not None and len(ids) in (3, 4):
                points = np.array([element["vertices"][i] for i in ids]) + origin
                yield points, np.array([face["uv"][i] for i in ids]), face["texture"]
        return
    if element["type"] != "cube":
        return
    lo = np.asarray(element["from"]) - element.get("inflate", 0)
    hi = np.asarray(element["to"]) + element.get("inflate", 0)
    vertices = [(hi[0] if i & 4 else lo[0], hi[1] if i & 2 else lo[1], hi[2] if i & 1 else lo[2])
                for i in range(8)]
    corners = {"north": (6, 2, 0, 4), "south": (3, 7, 5, 1), "west": (2, 3, 1, 0),
               "east": (7, 6, 4, 5), "up": (3, 2, 6, 7), "down": (0, 1, 5, 4)}
    for side, face in element["faces"].items():
        if face.get("texture") is None:
            continue
        u, v, u2, v2 = face["uv"]
        uv = np.array([(u, v), (u2, v), (u2, v2), (u, v2)])
        uv = np.roll(uv, int(face.get("rotation", 0) // 90), axis=0)
        yield np.array([vertices[i] for i in corners[side]]), uv, face["texture"]


def eyelid_positions(model, time):
    """Read the supplied linear position tracks for the actual upper and lower lid meshes."""
    blink = next(clip for clip in model["animations"] if clip["name"] == "blink")
    result = {}
    for track in blink["animators"].values():
        if track["name"] not in ("lashes", "blink2"):
            continue
        keys = sorted((k for k in track["keyframes"] if k["channel"] == "position"), key=lambda k: k["time"])
        times = [k["time"] for k in keys]
        result[track["name"]] = np.array([
            np.interp(time, times, [float(key["data_points"][0][axis]) for key in keys])
            for axis in AXES
        ])
    if set(result) != {"lashes", "blink2"}:
        raise ValueError("Expected both Cupid eyelid tracks")
    return result


def head_triangles(model, textures, time, gaze=(0, 0), expression_tile=0):
    return avatar_triangles(model, textures, time, gaze, expression_tile, head_only=True)


def avatar_triangles(model, textures, time, gaze, expression_tile, *, head_only=False, rotations=None):
    """Bake authored meshes in their original hierarchy, with pose offsets at real bone pivots."""
    rotations = rotations or {}
    groups = {group["uuid"]: group for group in model["groups"]}
    elements = {element["uuid"]: element for element in model["elements"]}
    offsets = eyelid_positions(model, time)
    # Positive model X moves both pupils toward viewer-left at neutral yaw.
    offsets.update({"iris": (*gaze, 0), "iris2": (*gaze, 0)})

    def walk(nodes, matrix, path):
        for node in nodes:
            if isinstance(node, dict):
                group = groups[node["uuid"]]
                name = group["name"]
                child_path = path + (name,)
                if group.get("visibility") is False or group.get("export") is False:
                    continue
                if head_only and child_path[:len(HEAD_PATH)] != HEAD_PATH[:len(child_path)]:
                    continue
                # Fixed proportions from cupid_proportions.lua, including the author's 1.075 head scale.
                scale = 0.8 if child_path == ("whole",) else 1.075 if child_path == HEAD_PATH else 1
                rotation = np.asarray(group.get("rotation", (0, 0, 0))) + rotations.get(child_path, np.zeros(3))
                transform = bone_matrix(group["origin"], rotation,
                                        offsets.get(name, (0, 0, 0)), scale)
                yield from walk(node.get("children", ()), matrix @ transform, child_path)
                continue
            if head_only and path[:len(HEAD_PATH)] != HEAD_PATH:
                continue
            element = elements[node]
            if element.get("visibility") is False or element.get("export") is False:
                continue
            transform = matrix @ bone_matrix(element["origin"], element.get("rotation", (0, 0, 0)))
            for points, uv, material in element_faces(element):
                points = apply_matrix(points, transform)
                info, pixels = model["textures"][material], textures[material]
                uv = uv * (pixels.shape[1] / info["uv_width"], pixels.shape[0] / info["uv_height"])
                if element["name"] == "expression_face":
                    # Figura's expression atlas has three columns of 48-by-24 texel overlays.
                    uv = uv + (expression_tile % 3 * 48, expression_tile // 3 * 24)
                for triangle in ([0, 1, 2], [0, 2, 3]) if len(points) == 4 else ([0, 1, 2],):
                    yield points[triangle], uv[triangle], material

    return list(walk(model["outliner"], np.eye(4), ()))


def draw_triangle(canvas, zbuffer, xy, depth, uv, texture, light):
    """Orthographic barycentric sampling with alpha-tested texels and per-pixel depth."""
    height, width = canvas.shape[:2]
    x0, y0 = np.maximum(0, np.floor(xy.min(axis=0)).astype(int))
    x1, y1 = np.minimum((width, height), np.ceil(xy.max(axis=0)).astype(int))
    if x0 >= x1 or y0 >= y1:
        return
    a, b, c = xy
    d, e = b-a, c-a
    det = d[0]*e[1] - d[1]*e[0]
    if abs(det) < 1e-8:
        return
    yy, xx = np.ogrid[y0:y1, x0:x1]
    xx, yy = xx + 0.5 - a[0], yy + 0.5 - a[1]
    s = (xx*e[1] - yy*e[0]) / det
    t = (d[0]*yy - d[1]*xx) / det
    z = depth[0] + (depth[1]-depth[0])*s + (depth[2]-depth[0])*t
    current = zbuffer[y0:y1, x0:x1]
    inside = (s >= -1e-7) & (t >= -1e-7) & (s+t <= 1+1e-7) & (z >= current-1e-6)
    iy, ix = np.nonzero(inside)
    sample = uv[0] + s[iy, ix, None]*(uv[1]-uv[0]) + t[iy, ix, None]*(uv[2]-uv[0])
    lookup = np.floor(sample).astype(int)
    texels = texture[lookup[:, 1] % texture.shape[0], lookup[:, 0] % texture.shape[1]]
    opaque = texels[:, 3] > 32
    iy, ix, texels = iy[opaque], ix[opaque], texels[opaque].copy()
    texels[:, :3] = np.floor(texels[:, :3]*light + 0.5)
    current[iy, ix] = z[iy, ix]
    canvas[y0+iy, x0+ix] = texels


def render_pose(triangles, textures, size, *, yaw=0, pitch=1.4, roll=0, center=(0, 20.35, 0),
                scale=13.6):
    width, height = size
    yaw, pitch, roll = np.radians((yaw, pitch, roll))
    right = np.array([-np.cos(yaw), 0, -np.sin(yaw)])
    viewer = np.array([np.sin(yaw)*np.cos(pitch), np.sin(pitch), -np.cos(yaw)*np.cos(pitch)])
    up = np.cross(viewer, right)
    right, up = np.cos(roll)*right + np.sin(roll)*up, np.cos(roll)*up - np.sin(roll)*right
    scale *= min(width / 224, height / 128)
    points, uvs, materials = zip(*triangles)
    points = np.array(points)
    centered = points - center
    xy = np.stack((width/2 + scale*(centered @ right), height/2 - scale*(centered @ up)), axis=2)
    depth = centered @ viewer
    normals = np.cross(points[:, 1]-points[:, 0], points[:, 2]-points[:, 0])
    normals /= np.maximum(np.linalg.norm(normals, axis=1)[:, None], 1e-9)
    normals = np.where((normals @ viewer < 0)[:, None], -normals, normals)
    light = 0.86 + 0.14 * np.clip(normals @ (viewer*0.5 + up*0.75 - right*0.35), 0, 1)
    zbuffer = np.full((height, width), -np.inf)
    canvas = np.zeros((height, width, 4), dtype=np.uint8)
    for i in np.argsort(depth.mean(axis=1), kind="stable"):
        draw_triangle(canvas, zbuffer, xy[i], depth[i], uvs[i], textures[materials[i]], light[i])
    return Image.fromarray(canvas)


def load_model(archive_path, model_path):
    with ZipFile(archive_path) as archive:
        model = json.loads(archive.read(model_path))
    textures = []
    for info in model["textures"]:
        encoded = info["source"].split(",", 1)[1]
        with Image.open(io.BytesIO(base64.b64decode(encoded, validate=True))) as image:
            textures.append(np.array(image.convert("RGBA")))
    return model, textures


def render_faces(archive_path, size=(448, 256)):
    """Return aligned OPEN, HALF, CLOSED and GAZE RGBA images. The full image is the inner glass."""
    model, textures = load_model(archive_path, MODEL_PATH)
    times = {"OPEN": 0, "HALF": 0.185, "CLOSED": 0.4, "GAZE": 0}
    return {name: render_pose(head_triangles(model, textures, time, (.5 if name == "GAZE" else 0, 0)), textures, size)
            for name, time in times.items()}
