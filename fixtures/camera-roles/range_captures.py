"""Verify the screen pixels that the synthetic Minecraft clients presented on the hosted runner."""
import json
import os
from pathlib import Path
from PIL import Image, ImageDraw, ImageFilter

if os.environ.get("GITHUB_ACTIONS") != "true":
    raise SystemExit("Hosted fixture only")
root = Path("observations")
out = Path("comparison")
size = 192


def path(trial, shot, role="CamOwner"):
    return root / ("observed-" + trial) / f"{shot}-{role}.png"


def read(file):
    with Image.open(file) as source:
        return source.convert("RGB")


def receipt(trial, shot, role="CamOwner"):
    return json.loads(Path(str(path(trial, shot, role)) + ".range.json").read_text())


def panel(trial, shot, role="CamOwner"):
    p = path(trial, shot, role)
    quad = json.loads(Path(str(p) + ".json").read_text())
    # Use the homogeneous depth relationship of planar quadrilateral corners.
    # World width/height and screen pixel aspect vary with F5 and oblique viewing.
    x0,y0,x3,y3,x2,y2,x1,y1 = quad
    dx1,dx2 = x1-x2,x3-x2
    dy1,dy2 = y1-y2,y3-y2
    dx3,dy3 = x0-x1+x2-x3,y0-y1+y2-y3
    det = dx1*dy2-dx2*dy1
    assert abs(det)>10, (trial,shot,quad)
    g,h = (dx3*dy2-dx2*dy3)/det,(dx1*dy3-dx3*dy1)/det
    transform = ((x1*(1+g)-x0)/size,(x3*(1+h)-x0)/size,x0,
                 (y1*(1+g)-y0)/size,(y3*(1+h)-y0)/size,y0,g/size,h/size)
    return read(p).transform((size,size),Image.Transform.PERSPECTIVE,transform,Image.Resampling.BILINEAR)


def source(trial, shot):
    return read(str(path(trial,shot)) + ".source.png").resize((size,size),Image.Resampling.BOX)


def stock(trial, shot):
    return read(path(trial,shot,"RoomBuilder")).resize((size,size),Image.Resampling.BOX)


def sheet(name, titles, rows):
    w,h = rows[0][0].size
    result = Image.new("RGB",((w+8)*len(titles),(h+8)*len(rows)+24),"#1c1d24")
    draw = ImageDraw.Draw(result)
    for col,title in enumerate(titles):
        draw.text((col*(w+8)+4,5),title,fill="white")
    for y,row in enumerate(rows):
        for x,picture in enumerate(row):
            result.paste(picture,(x*(w+8)+4,y*(h+8)+23))
    result.save(out/name)


def matched_scene(trial, shot):
    screen = panel(trial,shot).filter(ImageFilter.GaussianBlur(1.5))
    expected = source(trial,shot).filter(ImageFilter.GaussianBlur(1.5))
    matched,samples = 0,0
    for y in range(14,size-14,2):
        for x in range(14,size-14,2):
            # Exclude the sky where a monochrome blank could look similar.
            # Side margins show terrain around the ordinary F5 player.
            if y<size*.26 or size*.35<x<size*.65:
                continue
            observed,reference=screen.getpixel((x,y)),expected.getpixel((x,y))
            if max(reference)<30 or max(reference)-min(reference)<12:
                continue
            samples+=1
            matched+=max(abs(a-b) for a,b in zip(observed,reference))<=40
    assert samples>=300 and matched/samples>.27,(trial,shot,matched,samples)
    return round(matched/samples,3)


def actor_hues(image, absent, cx, cy):
    red=cyan=changed=0
    for y in range(round((cy-.13)*size),round((cy+.13)*size)):
        for x in range(round((cx-.065)*size),round((cx+.065)*size)):
            r,g,b=image.getpixel((x,y))
            if max(abs(a-b) for a,b in zip((r,g,b),absent.getpixel((x,y))))<=18:
                continue
            changed+=1
            red+=r>g*1.45 and r>b*1.3 and r>38
            cyan+=g>r*1.5 and b>r*1.4 and g>35
    return {"changed":changed,"red":red,"cyan":cyan}


def actor_images(trial, kind):
    if kind=="stock":
        return stock(trial,"stock-skin"),stock(trial,"stock-invisible")
    get = panel if kind=="monitor" else source
    return get(trial,"visible-skin"),get(trial,"invisible-skin")


def changed_gold_pixels(image, beyond):
    # Distinguish bright gold from an olive moss floor and the stationary player reticle.
    # Use the observer's matching empty aperture at the same camera and head pose.
    low,high=round(image.width*.42),round(image.width*.58)
    count=0
    for y in range(low,high):
        for x in range(low,high):
            r,g,b=image.getpixel((x,y))
            prior_r,prior_g,_=beyond.getpixel((x,y))
            if (r>=g*1.04 and r>=90 and g>85 and min(r,g)>b*1.7
                    and r-prior_r>20 and g-prior_g>15):
                count+=1
    return count


report = {"renderedPanelMatch":{}, "skin":{}, "range":{}}
for trial in ("old","new","solo"):
    assert (root/("observed-"+trial)/"PASS.txt").is_file()
    for shot in ("perspective-first","perspective-back","perspective-front"):
        record=receipt(trial,shot)
        if record["condition"]=="visible":
            report["renderedPanelMatch"][f"{trial}/{shot}"]=matched_scene(trial,shot)
        else:
            assert trial=="old" and record["condition"]=="inactive"
    for distance in (24,25,32,48,64,65):
        shot=f"range-{distance}"
        record=receipt(trial,shot)
        limit=24 if trial=="old" else 64
        expected=distance<=limit
        assert (record["goldCells"]>0)==expected
        if expected:
            assert distance-1<record["nearestGoldRadialBlocks"]<=limit
        pixels=changed_gold_pixels(panel(trial,shot),panel(trial,"range-65"))
        native=changed_gold_pixels(read(str(path(trial,shot))+".source.png"),
                                   read(str(path(trial,"range-65"))+".source.png"))
        # The previous renderer's wire reach and actually visible distance are separate observations.
        if expected and trial!="old":
            assert pixels>4 and native>8,(trial,distance,pixels,native)
        if not expected:
            assert pixels==0 and native==0,(trial,distance,pixels,native)
        report["range"][f"{trial}/{distance}"]={**record,"presentedGoldPixels":pixels,"screenTextureGoldPixels":native}
    if trial=="old":
        continue
    for name in ("face-away-from-screen","front-camera-facing-away","screen-behind-opaque-wall"):
        assert receipt(trial,name)["condition"]=="inactive"
    for name in ("source-revoked-in-back-view","source-unloaded-in-front-view"):
        assert receipt(trial,name)["freshRefusals"]>0
    assert receipt(trial,"range-48-hidden-by-claim")["goldCells"]==0
for trial in ("old","new"):
    actor=receipt(trial,"visible-skin")
    for kind in ("monitor","source","stock"):
        present,absent=actor_images(trial,kind)
        hues=actor_hues(present,absent,actor["actorX"],actor["actorY"])
        report["skin"][f"{trial}/{kind}"]=hues
        assert hues["changed"]>65,(trial,kind,hues)
    assert receipt(trial,"invisible-skin")["condition"]=="noactor"
    assert receipt(trial,"denied-actor")["condition"]=="noactor"
assert receipt("new","visible-skin")["trackedAppearance"]>0
for kind in ("monitor","source"):
    legacy,improved,physical=(report["skin"][key] for key in ("old/"+kind,"new/"+kind,"new/stock"))
    # This exact fixture profile's torso is red; the old generic Steve has a cyan shirt.
    assert legacy["cyan"]>12 and improved["red"]>legacy["red"]+20,(kind,legacy,improved)
    assert physical["red"]>35 and improved["red"]>improved["cyan"],(kind,improved,physical)
for name in ("guest-denied-back","guest-denied-front"):
    assert receipt("new",name,"RoomBuilder")["freshRefusals"]>0
assert receipt("new","foreground-unloaded-in-third-person")["upper"]==0

sheet("f5-monitor.png",["First person","Third person (rear)","Third person (front)"],[
    [read(path(t,shot)).resize((360,240),Image.Resampling.LANCZOS)
     for shot in ("perspective-first","perspective-back","perspective-front")]
    for t in ("old","new","solo")])
sheet("player-and-stock-lens.png",["Previous wall monitor","Updated wall monitor","Stock camera at outdoor lens"],[
    [panel("old","visible-skin"),panel("new","visible-skin"),stock("new","stock-skin")],
    [panel("old","invisible-skin"),panel("new","invisible-skin"),stock("new","stock-invisible")]])
sheet("range-course.png",["24-block marker","25-block marker","32-block marker","48-block marker","64-block marker","65-block marker"],[
    [panel(t,f"range-{n}") for n in (24,25,32,48,64,65)] for t in ("old","new")])
(out/"observations.json").write_text(json.dumps(report,indent=2)+"\n")
print(json.dumps(report,indent=2))
