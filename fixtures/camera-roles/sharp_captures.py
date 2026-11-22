"""Compare pixels from the presented monitor against the previous mod and the native camera reference."""
import json
import math
from pathlib import Path
from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageStat

base = Path("build/role-check")
out = Path("upload/captures")
out.mkdir(parents=True, exist_ok=True)
old, new, solo = "sharp-old", "sharp-new", "sharp-solo"


def original(trial, shot, role="CamOwner"):
    with Image.open(base / trial / "control" / f"{shot}-{role}.png") as image:
        return image.convert("RGB")


def panel(trial, shot, role="CamOwner"):
    source = base / trial / "control" / f"{shot}-{role}.png.json"
    quad = json.loads(source.read_text())
    width = math.hypot(quad[6] - quad[0], quad[7] - quad[1])
    height = math.hypot(quad[2] - quad[0], quad[3] - quad[1])
    # Common sample density below the projected panel width, without synthesizing detail.
    side = 62 if shot == "small-day" else 192
    assert min(width, height) >= side * .88, (trial, shot, quad)
    with original(trial, shot, role) as source:
        return source.transform((side, side), Image.Transform.QUAD, quad, Image.Resampling.NEAREST)


def interior(trial, shot):
    image = panel(trial, shot)
    margin = max(2, int(image.width * .08))
    return image.crop((margin, margin, image.width-margin, image.height-margin))


def detail(image):
    w, h = image.size
    pixels = image.load()
    # Omit the room player's center reticle.
    return sum(max(abs(a-b) for a, b in zip(pixels[x, y], pixels[x+1, y])) >= 6
               for y in range(2, h-2) for x in range(2, w-3)
               if abs(x-w//2) > 8 or abs(y-h//2) > 8)


def difference(a, b, limit=13):
    return sum(max(pixel) > limit for pixel in ImageChops.difference(a, b).getdata())


def mean_upper(trial, shot):
    image=panel(trial,shot)
    return ImageStat.Stat(image.crop((91,11,101,18))).mean


def streak_region(shot):
    # Bare upper bricks, log and bookshelf around the stationary staged subjects.
    return panel(new,shot).crop((13,45,179,65))


def subject_x_delta(before,after):
    size=before.width
    delta=ImageChops.difference(before.convert("RGB"),after.convert("RGB"))
    xs=[x+.5 for y in range(size//3,int(size*.81)) for x in range(size//2,int(size*.97))
        if max(delta.getpixel((x,y)))>22]
    assert len(xs)>size*.25,"No visible outdoor actor change"
    return sum(xs)/len(xs)/size


def collect(trial):
    root = base / trial / "control"
    for file in root.glob("*.png*"):
        (out / (trial + "-" + file.name)).write_bytes(file.read_bytes())
    acknowledgements = [f"{receipt.stem}  {receipt.read_text().strip()}"
                        for receipt in sorted(root.glob("*.ok"))]
    (out / f"{trial}-observations.txt").write_text("\n".join(acknowledgements) + "\n")
    print(trial, (root / "PASS.txt").read_text())


def contact(name, columns, rows):
    width = max(image.width for row in rows for image in row) + 8
    height = max(image.height for row in rows for image in row) + 10
    canvas = Image.new("RGB", (width * len(columns), height * len(rows) + 25), "#191a21")
    draw = ImageDraw.Draw(canvas)
    for x, title in enumerate(columns):
        draw.text((x*width+4, 6), title, fill="white")
    for y, row in enumerate(rows):
        for x, image in enumerate(row):
            canvas.paste(image, (x*width+4, y*height+25))
    canvas.save(out / name)


for trial in (old, new, solo):
    collect(trial)
a, b = interior(old, "large-day"), interior(new, "large-day")
data = {
    "old_detail_edges": detail(a),
    "new_detail_edges": detail(b),
    "small_old_detail_edges": detail(interior(old, "small-day")),
    "small_new_detail_edges": detail(interior(new, "small-day")),
    "old_same_map_color_edit": difference(a, interior(old, "large-brick")),
    "new_same_map_color_edit": difference(b, interior(new, "large-brick")),
    "masked_source_cell": difference(interior(new, "source-cell-clear"), interior(new, "source-cell-masked")),
    "cow_enters": difference(interior(new, "large-cow"), interior(new, "large-brick")),
    "visible_player_enters": difference(interior(new, "large-player"), interior(new, "large-cow")),
    "player_hidden": difference(interior(new, "large-player"), interior(new, "large-invisible")),
    "source_rain": difference(interior(new, "large-player"), interior(new, "large-rain"), 7),
    "source_night": difference(interior(new, "large-rain"), interior(new, "large-night")),
    "solo_detail_edges": detail(interior(solo, "large-day")),
    "solo_unloaded": difference(interior(solo, "source-unloaded"), interior(solo, "restored-after-claim")),
}
# Actual terrain textures should yield detail that the old single color per sample cannot show.
assert data["new_detail_edges"] > data["old_detail_edges"] * 1.4 + 80, data
assert data["small_new_detail_edges"] > data["small_old_detail_edges"] + 22, data
assert data["new_same_map_color_edit"] > data["old_same_map_color_edit"] + 90, data
for key in ("masked_source_cell", "cow_enters", "visible_player_enters", "player_hidden",
            "source_rain", "source_night", "solo_detail_edges", "solo_unloaded"):
    assert data[key] > 30, (key, data)
contact("comparison.png", ["Installed monitor", "New monitor"], [
    [panel(old, "large-brick"), panel(new, "large-brick")],
    [panel(old, "small-day"), panel(new, "small-day")],
])
rows = []
references = {}
displayed = {}
for condition, shot in (("day", "large-player"), ("rain", "large-rain"), ("night", "large-night")):
    name = "reference-" + condition
    base_path = base / new / "control"
    reference = original(new, name, "RoomBuilder")
    with Image.open(base_path/(name+"-outside-before.png")) as before, Image.open(base_path/(name+"-outside-after.png")) as after:
        assert before.size == after.size and difference(before.convert("RGB"), after.convert("RGB"), 2) < before.width * before.height * .01, "Reference changed outside player's view"
    assert len(reference.getcolors(reference.width * reference.height)) > 20, name
    references[condition] = reference.resize((192, 192), Image.Resampling.BOX)
    displayed[condition] = panel(new, shot)
    rows.append([panel(old, shot), displayed[condition], references[condition]])
    # Quantify the difference to stock rendering without calling the two renderers equivalent.
    data["stock_vs_screen_mean_rgb_error_" + condition] = ImageStat.Stat(
        ImageChops.difference(references[condition], displayed[condition])).mean
data["stock_reference_day_to_night"] = difference(references["day"], references["night"])
data["stock_reference_clear_to_rain"] = difference(references["day"], references["rain"], 7)
assert data["stock_reference_day_to_night"] > 400 and data["stock_reference_clear_to_rain"] > 100, data
contact("source-comparison.png", ["Installed monitor", "New monitor", "Stock renderer at lens"], rows)
data["clear_sky_rgb"]=mean_upper(new,"day-open-atmosphere")
data["denied_upper_rgb"]=mean_upper(new,"upper-foreground-denied")
data["unloaded_upper_rgb"]=mean_upper(new,"unloaded-foreground")
data["roof_rgb"]=mean_upper(new,"rain-under-roof")
r,g,b=data["clear_sky_rgb"]
assert b>80 and b>r+25 and b>g+6,("The open daytime monitor never showed blue sky",data)
for key in ("denied_upper_rgb","unloaded_upper_rgb"):
    assert max(data[key])<65,(key,data)
assert max(abs(a-b) for a,b in zip(data["roof_rgb"],data["clear_sky_rgb"]))>40,data
data["clear_temporal_pixels"]=difference(streak_region("large-player"),streak_region("clear-repeat"),8)
whole_rain=[streak_region(k) for k in ("large-rain","rain-repeat","rain-third")]
data["rain_temporal_pixels"]=max(difference(a,b,8) for i,a in enumerate(whole_rain) for b in whole_rain[i+1:])
assert data["clear_temporal_pixels"]<25 and data["rain_temporal_pixels"]>data["clear_temporal_pixels"]+25,data
contact("sky-and-precipitation.png",["Clear lens","Exposed to rain","Roof over lens"],[[
    panel(new,"day-open-atmosphere"),panel(new,"large-rain"),panel(new,"rain-under-roof")]])
contact("private-foreground.png",["Open sky","Air along ray denied","Foreground chunk unloaded"],[[
    panel(new,"day-open-atmosphere"),panel(new,"upper-foreground-denied"),panel(new,"unloaded-foreground")]])
native=original(new,"reference-day","RoomBuilder")
data["stock_oblique_player_x"]=subject_x_delta(native,original(new,"reference-invisible","RoomBuilder"))
data["inside_oblique_player_x"]=subject_x_delta(panel(new,"large-player"),panel(new,"large-invisible"))
actor_names=["reference-day-RoomBuilder","large-player-CamOwner"]
centers=[json.loads((base/new/"control"/(name+".png.actor.json")).read_text()) for name in actor_names]
assert math.dist(*centers)<.03,("Observers disagree on the source player coordinates",centers)
data["expected_oblique_player_uv"]=centers[0]
for key in ("stock_oblique_player_x","inside_oblique_player_x"):
    assert abs(data[key]-centers[0][0])<.15,(key,data)
transferred=panel(new,"stock-lens-on-the-indoor-wall")
stock=references["day"]
def matched_center(image):
    return image.filter(ImageFilter.GaussianBlur(3)).crop((10,15,182,171))
transferred_error=ImageStat.Stat(ImageChops.difference(matched_center(transferred),matched_center(stock))).mean
data["synthetic_stock_transfer_mean_rgb_error"]=transferred_error
assert max(transferred_error)<29,("Wrong picture on the fixture's transferred monitor",data)
contact("stock-transfer-experiment.png",["Playable wall feed","Test-only stock transfer","Stock lens source"],[[
        displayed["day"],transferred,stock]])

# Compare flat moss immediately outside the actual cow/player silhouette against the identical
# unoccupied floor. These patches avoid their feet, any legitimate contact shadow and all UI.
floor_patches={
    "large-cow":[(6,153,25,160),(53,153,83,162),(13,165,25,173)],
    "large-player":[(138,127,164,137),(142,140,154,147)],
}
def floor_proof(shot):
    return json.loads((base/new/"control"/f"{shot}-CamOwner.png.floor.json").read_text())
def floor_panel(shot):
    # Use the inverse mapping of a flat perspective display. A bilinear corner interpolation
    # can move a 32x32 witness into the neighboring cell near the bottom of this tilted screen.
    data_path=base/new/"control"/f"{shot}-CamOwner.png.json"
    x0,y0,x1,y1,x2,y2,x3,y3=json.loads(data_path.read_text())
    dx1,dx2,dx3=x3-x2,x1-x2,x0-x3+x2-x1
    dy1,dy2,dy3=y3-y2,y1-y2,y0-y3+y2-y1
    det=dx1*dy2-dx2*dy1
    g=(dx3*dy2-dx2*dy3)/det
    h=(dx1*dy3-dx3*dy1)/det
    side=192
    inverse=((x3-x0+g*x3)/side,(x1-x0+h*x1)/side,x0,
             (y3-y0+g*y3)/side,(y1-y0+h*y1)/side,y0,g/side,h/side)
    return original(new,shot).transform((side,side),Image.Transform.PERSPECTIVE,inverse,Image.Resampling.NEAREST)
bare,known=floor_panel("large-brick"),floor_proof("large-brick")
for shot,patches in floor_patches.items():
    drawn,observed=floor_panel(shot),floor_proof(shot)
    checked=dark=0
    for x0,y0,x1,y1 in patches:
        for y in range(y0,y1):
            for x in range(x0,x1):
                gx,gy=x//6,y//6
                cell=gy*32+gx
                nearby=[(gy+j)*32+gx+i for j in (-1,0,1) for i in (-1,0,1)]
                # Respect opaque/unknown cells. Require an actually published upward moss face
                # within one sample of the authorized foreground in both observations.
                if not known[cell] or not observed[cell] or not all(any(proof[k]==2 for k in nearby) for proof in (known,observed)):
                    continue
                a,b=bare.getpixel((x,y)),drawn.getpixel((x,y))
                checked+=1
                dark+=min(a[0]-b[0],a[1]-b[1])>14 and b[1]<a[1]*.7
    total=sum((x1-x0)*(y1-y0) for x0,y0,x1,y1 in patches)
    data[shot+"_verified_moss_pixels"]=checked
    data[shot+"_false_dark_moss_pixels"]=dark
    assert checked>total*.70 and dark<=checked*.025,(shot,"New dark seams on authorized exposed moss",checked,dark,total)
hardware, fallback = panel(new, "same-scene-world-renderer"), panel(new, "same-scene-software")
contact("same-observation.png", ["World block renderer", "Software fallback"], [[hardware, fallback]])
data["same_observation_renderer_difference"] = difference(hardware, fallback, 3)
(out / "measurements.json").write_text(json.dumps(data, indent=2))
print("PASS matched monitor texture, subjects, weather/light, stock camera comparisons and integrated server", data)
