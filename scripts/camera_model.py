"""Original CCTV bracket and swiveling body. Model coordinates are sixteenths, with its lens facing north."""
FACES = ("north", "south", "east", "west", "up", "down")
MOUNT = (
    ("wall plate", [6.125,5,15.5], [9.875,6.875,16], "mount"),
    ("left rail", [6.75,5.5,7.5], [7.375,6.125,15.5], "mount"),
    ("right rail", [8.625,5.5,7.5], [9.25,6.125,15.5], "mount"),
    ("pivot", [6.75,6.125,7.5], [9.25,7,8.5], "mount"),
)
HEAD = (
    ("housing", [6,7,5.875], [10,9.125,10.5], "body"),
    ("sun shield", [5.875,9.125,5.125], [10.125,9.5,10.625], "shield"),
    ("lens barrel", [6.625,7.125,5], [9.375,8.875,5.875], "mount"),
    ("front glass", [6.875,7.25,4.5], [9.125,8.75,5], "mount"),
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



def wall_monitor(js, mod, device):
    base=f"assets/{mod}"
    model=f"{mod}:block/{device}"
    textures={"case":f"{mod}:block/cctv_mount","glass":f"{mod}:block/{device}","particle":f"{mod}:block/cctv_mount"}
    faces={side:{"texture":"#case","uv":[0,0,16,16]} for side in FACES}
    faces["north"]={"texture":"#glass","uv":[0,0,16,16]}
    js(f"{base}/models/block/{device}.json",{"parent":"minecraft:block/block","textures":textures,
           "elements":[{"from":[0,0,15],"to":[16,16,16],"faces":faces}]})
    variants={f"facing={face}":{"model":model,"y":angle} for face,angle in (("north",0),("east",90),("south",180),("west",270))}
    variants.update({f"facing={face}":{"model":model,"x":angle} for face,angle in (("up",270),("down",90))})
    js(f"{base}/blockstates/{device}.json",{"variants":variants})
    js(f"{base}/models/item/{device}.json",{"parent":model})

def tabletop_tv(js,mod):
    base=f"assets/{mod}"
    model=f"{mod}:block/flat_screen_tv"
    case={side:{"texture":"#case"} for side in FACES}
    face={**case,"north":{"texture":"#glass","uv":[0,0,16,16]}}
    js(f"{base}/models/block/flat_screen_tv.json",{"parent":"minecraft:block/block",
       "textures":{"case":f"{mod}:block/cctv_mount","glass":model,"particle":f"{mod}:block/cctv_mount"},
       "elements":[{"from":[3,0,5],"to":[13,1,11],"faces":case},
                   {"from":[7,1,7],"to":[9,4,9],"faces":case},
                   {"from":[0,4,7],"to":[16,14,9],"faces":face}]})
    js(f"{base}/blockstates/flat_screen_tv.json",{"variants":{
       f"facing={face}":{"model":model,**rotation} for face,rotation in
       (("north",{}),("east",{"y":90}),("south",{"y":180}),("west",{"y":270}),("up",{"x":270}),("down",{"x":90}))}})
    js(f"{base}/models/item/flat_screen_tv.json",{"parent":model})

def sky_camera(js,mod):
    base=f"assets/{mod}";model=f"{mod}:block/sky_camera"
    faces={s:{"texture":"#case","uv":[0,0,16,16]} for s in FACES}
    glass={**faces,"up":{"texture":"#glass","uv":[0,0,16,16]}}
    js(f"{base}/models/block/sky_camera.json",{"parent":"minecraft:block/block",
       "textures":{"case":f"{mod}:block/cctv_shield","glass":f"{mod}:block/outdoor_camera","particle":f"{mod}:block/cctv_mount"},
       "elements":[{"from":[4.5,0,4.5],"to":[11.5,3.5,11.5],"faces":faces},{"from":[5.5,3.5,5.5],"to":[10.5,4.5,10.5],"faces":glass}]})
    js(f"{base}/blockstates/sky_camera.json",{"variants":{"":{"model":model}}})
    js(f"{base}/models/item/sky_camera.json",{"parent":model})
