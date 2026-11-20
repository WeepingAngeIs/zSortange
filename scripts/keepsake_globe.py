"""Generate a single-surface faceted crystal globe, walnut foot and rose-gold bezel."""
from math import cos, pi, sin
from pathlib import Path
import struct
import zlib

from chest_shell import obj


WIDTH, HEIGHT, SIDES = 128, 64, 16
BASE = [(0, 4.65, "wood"), (.4, 5.4, "metal"), (.8, 5.5, "wood"),
        (1.65, 5.3, "inlay"), (1.95, 5.35, "wood"), (2.45, 4.65, "metal"),
        (2.9, 4.6, "metal"), (3.15, 4.25, "felt")]
GLASS = [(3.15, 3.25), (4.5, 4.7), (6.25, 5.9), (8.5, 6.5), (10.5, 6.3),
         (12.5, 5.5), (13.9, 4.1), (14.85, 2.35), (15.25, 0)]
FINISH = {"wood": 0, "metal": 1, "inlay": 2, "felt": 3}


def texture():
    def pixel(x, y):
        if y < 48:
            # Clear window with two painterly specular arcs and a faint amethyst lower rim.
            u = x % 64
            if 7 <= y < 25 and abs(u - (10 + (y - 8)**2 // 46)) < (2 if y < 18 else 1):
                return (228, 251, 255, 165)
            if 12 <= y < 22 and abs(u - (15 + (y - 9)**2 // 48)) < 1:
                return (174, 235, 251, 95)
            if 32 <= y < 39 and abs(u - (49 - (y - 32))) <= 1:
                return (245, 210, 247, 98)
            return (141 + y//5, 211 - y//2, 238, 16 + abs(y-22)//2)
        u, v = x % 32, y - 48
        if x < 32:
            band = (u//4 + v//3 + (u//11)) % 5
            return (85 + 5*band, 46 + 3*band, 61 + 3*band, 255)
        if x < 64:
            shine = (0, 11, 32, 14, 0, -12, -18, -24)[v//2]
            return (218 + shine, 157 + shine, 120 + shine, 255)
        if x < 96:
            d = abs(u - 16) + abs(v - 8)
            return (255, 218, 167, 255) if d < 4 else (102, 167, 168, 255) if d < 7 else (76, 74, 93, 255)
        return (116 + (u+v)%3*3, 67, 96, 255)

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    rows = b"".join(b"\0" + bytes(c for x in range(WIDTH) for c in pixel(x, y//2)) for y in range(2*HEIGHT))
    header = struct.pack(">IIBBBBB", WIDTH, 2*HEIGHT, 8, 6, 0, 0, 0)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", header) + chunk(b"IDAT", zlib.compress(rows, 9)) + chunk(b"IEND", b"")


def ring(y, radius):
    return [(round(8 + radius*cos(2*pi*i/SIDES), 5), y, round(8 + radius*sin(2*pi*i/SIDES), 5)) for i in range(SIDES)]


def finish_uv(name, uv):
    return [((FINISH[name]*32 + .5 + u*31)/WIDTH, (48.5 + v*15)/HEIGHT) for u,v in uv]


class Mesh:
    def __init__(self):
        self.parts = {"pedestal": [], "crystal": []}

    def band(self, bottom, top, texture_name):
        for i in range(SIDES):
            j = (i+1) % SIDES
            points = [bottom[i], top[i], top[j], bottom[j]]
            if texture_name == "glass":
                uv = [(i/SIDES, (.5 + 47*(15.25-bottom[i][1])/12.1)/HEIGHT),
                      (i/SIDES, (.5 + 47*(15.25-top[i][1])/12.1)/HEIGHT),
                      ((i+1)/SIDES, (.5 + 47*(15.25-top[i][1])/12.1)/HEIGHT),
                      ((i+1)/SIDES, (.5 + 47*(15.25-bottom[i][1])/12.1)/HEIGHT)]
                if top[i] == top[j]:
                    # Keep the apex triangle nondegenerate.
                    del points[2], uv[2]
                self.parts["crystal"].append((points, uv))
            else:
                self.parts["pedestal"].append((points, finish_uv(texture_name, [(0,1),(0,0),(1,0),(1,1)])))

    def cap(self, rim, name, up):
        center = (8, rim[0][1], 8)
        for i in range(SIDES):
            points = [center, rim[(i+1)%SIDES], rim[i]]
            if not up:
                points.reverse()
            self.parts["pedestal"].append((points, finish_uv(name, [(.5+(x-8)/12,.5+(z-8)/12) for x,y,z in points])))


def generate(assets: Path):
    shell = Mesh()
    shell.cap(ring(*BASE[0][:2]), "wood", False)
    for low, high in zip(BASE, BASE[1:]):
        shell.band(ring(*low[:2]), ring(*high[:2]), low[2])
    shell.cap(ring(*BASE[-1][:2]), "felt", True)
    for low, high in zip(GLASS, GLASS[1:]):
        shell.band(ring(*low), ring(*high), "glass")
    folder = assets/"models/block"
    folder.mkdir(parents=True, exist_ok=True)
    (folder/"keepsake_globe.obj").write_text(obj(shell, "keepsake_globe.mtl"), encoding="utf-8")
    (folder/"keepsake_globe.mtl").write_text("newmtl ceramic\nKd 1 1 1\nKa 0 0 0\nmap_Kd anyastorage:block/keepsake_globe\n", encoding="utf-8")
    folder = assets/"textures/block"
    folder.mkdir(parents=True, exist_ok=True)
    (folder/"keepsake_globe.png").write_bytes(texture())
    return {"parent": "minecraft:block/block", "loader": "neoforge:obj",
            "model": "anyastorage:models/block/keepsake_globe.obj", "render_type": "minecraft:translucent",
            "automatic_culling": False, "emissive_ambient": False, "flip_v": False,
            "textures": {"particle": "minecraft:block/cherry_planks"}}
