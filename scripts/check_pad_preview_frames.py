"""Compare real front and side framebuffers with both packaged entrance materials."""
import hashlib
import io
import json
import math
import sys
from pathlib import Path
from statistics import correlation, mean
from zipfile import ZipFile

from PIL import Image, ImageFilter

from check_storage_art_frames import cross, dot
from pocket_layout_art import compose


def sample_frame(screen, camera):
    x,y,z = camera["entrance"]
    pixels = screen.convert("RGB").filter(ImageFilter.GaussianBlur(.55))
    right = cross(camera["aim"], camera["up"])
    w,h = pixels.size
    scale = h/2/math.tan(math.radians(camera["fov"]/2))
    # Rectify both horizontal faces to the same UVs. Compare only inside the physical surface.
    face = Image.new("RGB", (96, 96))
    for row in range(96):
        v = (row+.5)/96
        for col in range(96):
            u = (col+.5)/96
            point = (x+u,y+1-v,z+1.001) if camera["side"] == "south" else (x+1.001,y+1-v,z+1-u)
            rel = tuple(a-b for a,b in zip(point,camera["eye"]))
            distance = dot(rel,camera["aim"])
            assert distance > 0
            sx = round(w/2 + scale*dot(rel,right)/distance-.5)
            sy = round(h/2 - scale*dot(rel,camera["up"])/distance-.5)
            assert 0 <= sx < w and 0 <= sy < h
            face.putpixel((col,row),pixels.getpixel((sx,sy)))
    return face


def likeness(a,b):
    return round(min(correlation(x,y) for x,y in zip(zip(*a.getdata()),zip(*b.getdata()))),4)


def roi(image, box):
    return image.crop(tuple(round(v*image.width/256) for v in box))


def compare(evidence,jar):
    control = evidence/"control"
    assert (control/"PASSED").is_file() and (evidence/"socket-kill.txt").is_file()
    digest = hashlib.sha256(jar.read_bytes()).hexdigest()
    assert json.loads((evidence/"runtime-inputs.json").read_text()).get(jar.name)==digest
    with ZipFile(jar) as archive:
        def texture(name):
            data=archive.read("assets/anyastorage/textures/entity/"+name+".png")
            with Image.open(io.BytesIO(data)) as image:
                return image.convert("RGBA")
        atlas=texture("pocket_pad_plan")
        public=texture("starter_room_study")
    def reference(texture):
        return texture.convert("RGB").resize((96,96),Image.Resampling.LANCZOS).filter(ImageFilter.GaussianBlur(.55))
    refs={"public":reference(public),"base":reference(compose(atlas,15)),"layout":reference(compose(atlas,143))}
    report={"runtime_sha256":digest,"sides":{}}
    for side in ("south","east"):
        shots={}
        for name,mask in (("public",-1),("base",15),("layout",143)):
            label=f"{name}-{side}"
            state=json.loads((control/f"pad-{label}-state.json").read_text())
            assert state["mask"]==mask and state["side"]==side and state["role"]=="Alpha", state
            camera=json.loads((control/f"pad-{label}-camera.json").read_text())
            assert camera["side"]==side
            with Image.open(evidence/"run-packaged-client-Alpha"/f"pad-{label}.png") as image:
                assert image.format=="PNG" and image.width>=640 and image.height>=400
                shots[name]=sample_frame(image,camera)
        fit={name:likeness(shot.crop((6,6,90,90)),refs[name].crop((6,6,90,90))) for name,shot in shots.items()}
        # The only differing component is the optional eighth chest on the right.
        # Score that physical target separately so displaying a fixed diagram cannot pass.
        rect=(168,124,198,152)
        off=roi(shots["base"],rect)
        on=roi(shots["layout"],rect)
        target_off=roi(refs["base"],rect)
        target_on=roi(refs["layout"],rect)
        local={"empty_matches_empty":likeness(off,target_off),"empty_matches_chest":likeness(off,target_on),
               "chest_matches_chest":likeness(on,target_on),"chest_matches_empty":likeness(on,target_off)}
        delta=mean(max(abs(a-b) for a,b in zip(before,after)) for before,after in zip(off.getdata(),on.getdata()))
        report["sides"][side]={"face_correlations":fit,"pad_eight":local,"pad_eight_mean_change":round(delta,2)}
    (evidence/"pad-frame-check.json").write_text(json.dumps(report,indent=2))
    print(json.dumps(report,indent=2))
    for side,values in report["sides"].items():
        assert min(values["face_correlations"].values())>=.70,(side,"wrong face material",values)
        local=values["pad_eight"]
        assert local["chest_matches_chest"]>.48 and local["chest_matches_chest"]>local["chest_matches_empty"]+.12,(side,"missing chest",local)
        assert local["empty_matches_empty"]>local["empty_matches_chest"]+.12,(side,"empty pad depicted as occupied",local)
        assert values["pad_eight_mean_change"]>=9,(side,"unchanged optional pad",values)


if __name__=="__main__":
    compare(Path(sys.argv[1]),Path(sys.argv[2]))
