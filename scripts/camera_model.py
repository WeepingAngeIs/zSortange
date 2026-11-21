"""Original CCTV bracket and swiveling body. Model coordinates are sixteenths, with its lens facing north."""
FACES = ("north", "south", "east", "west", "up", "down")
MOUNT = (
    ("wall plate", [5,2,14], [11,8,16], "mount"),
    ("arm", [7,3,7], [9,5,14], "mount"),
    ("pivot", [6.5,5,6.5], [9.5,5.75,9.5], "mount"),
)
HEAD = (
    ("housing", [4.5,5.75,2.5], [11.5,10.25,11], "body"),
    ("sun shield", [4,10.25,.5], [12,11,11], "shield"),
    ("lens barrel", [5.5,6,1], [10.5,10,2.5], "mount"),
    ("front glass", [6,6.5,0], [10,9.5,1], "mount"),
)


def finish(material, x, y):
    edge = min(x, y, 15-x, 15-y)
    if material == "shield":
        if y == 13 and 2 <= x <= 13:
            return (219,167,191)
        return (180,172,192) if edge == 0 else (217,208,219)
    if material == "mount":
        if x in (2,13) and y in (2,13):
            return (170,176,192)
        return (62,67,86) if edge == 0 else (85,90,110)
    if x in (4,6,8) and 7 <= y <= 10:
        return (43,48,66)
    if x in (11,12) and y == 9:
        return (148,225,229)
    if edge == 0:
        return (73,77,99)
    return (138,142,162) if y < 5 else (109,116,139)


def generate(js, png, mod):
    base = f"assets/{mod}"
    textures = {"particle": f"{mod}:block/cctv_body", "lens": f"{mod}:block/outdoor_camera"}
    for material in ("body","mount","shield"):
        textures[material] = f"{mod}:block/cctv_{material}"
        pixels = [(*finish(material,x,y),255) for y in range(16) for x in range(16)]
        png(f"{base}/textures/block/cctv_{material}.png",16,16,pixels)

    def model(parts):
        elements = []
        for name, start, end, material in parts:
            faces = {side: {"texture": "#"+material, "uv": [0,0,16,16]} for side in FACES}
            if name == "front glass":
                faces["north"]["texture"] = "#lens"
            elements.append({"name":name, "from":start, "to":end, "faces":faces})
        return {"parent":"minecraft:block/block", "textures":textures, "elements":elements}

    for name,parts in (("mount",MOUNT),("head",HEAD)):
        js(f"{base}/models/block/cctv_{name}.json",model(parts))
    js(f"{base}/models/block/outdoor_camera.json",model(MOUNT+HEAD))
    js(f"{base}/models/item/outdoor_camera.json",{"parent":f"{mod}:block/outdoor_camera"})

    horizontal = ("north","east","south","west")
    rotations = {face: {"y": 90*i} for i,face in enumerate(horizontal)}
    rotations.update({"up":{"x":270}, "down":{"x":90}})
    parts = []
    for face,rotation in rotations.items():
        parts.append({"when":{"facing":face}, "apply":{"model":f"{mod}:block/cctv_mount", **rotation}})
        for pan,turn in enumerate((0,1,-1)):
            aim = horizontal[(horizontal.index(face)+turn)%4] if face in horizontal else face
            parts.append({"when":{"facing":face,"pan":str(pan)},
                          "apply":{"model":f"{mod}:block/cctv_head", **rotations[aim]}})
    js(f"{base}/blockstates/outdoor_camera.json",{"multipart":parts})

