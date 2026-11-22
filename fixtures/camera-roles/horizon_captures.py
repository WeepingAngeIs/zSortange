"""Measure photographed, hosted Minecraft monitors against the same outdoor lens and source scene."""
import argparse
import json
import math
import os
import statistics
from pathlib import Path
from PIL import Image, ImageDraw

parser=argparse.ArgumentParser(description="Read existing camera capture PNGs and receipts; never launches Minecraft.")
parser.add_argument("--captures-dir",type=Path)
parser.add_argument("--output",type=Path,default=Path("comparison"))
args=parser.parse_args()
if args.captures_dir is None and os.environ.get("GITHUB_ACTIONS")!="true":
    raise SystemExit("Supply a directory of existing hosted screenshots.")
base=args.captures_dir or Path("observations")
out=args.output
out.mkdir(parents=True,exist_ok=True)
side=384


def game(trial):
    named=base/("observed-"+trial)
    return named if named.is_dir() else base/trial


def image(file):
    with Image.open(file) as frame:
        return frame.convert("RGB")


def file(trial,shot,role="CamOwner"):
    return game(trial)/f"{shot}-{role}.png"


def receipt(trial,shot):
    return json.loads(Path(str(file(trial,shot))+".horizon.json").read_text())


def texture(trial,shot):
    return image(str(file(trial,shot))+".source.png").resize((side,side),Image.Resampling.BOX)


def monitor(trial,shot):
    p=file(trial,shot)
    x0,y0,x3,y3,x2,y2,x1,y1=json.loads(Path(str(p)+".json").read_text())
    ax,ay=x1-x2,y1-y2
    bx,by=x3-x2,y3-y2
    cx,cy=x0-x1+x2-x3,y0-y1+y2-y3
    det=ax*by-ay*bx
    g,h=(cx*by-cy*bx)/det,(ax*cy-ay*cx)/det
    matrix=((x1*(1+g)-x0)/side,(x3*(1+h)-x0)/side,x0,(y1*(1+g)-y0)/side,
            (y3*(1+h)-y0)/side,y0,g/side,h/side)
    return image(p).transform((side,side),Image.Transform.PERSPECTIVE,matrix,Image.Resampling.BILINEAR)


def reference(trial,shot,wide=False):
    name="stock-"+("wide-" if wide else "standard-")+shot
    return image(file(trial,name,"RoomBuilder")).resize((side,side),Image.Resampling.BOX)


def color(rgb,name):
    r,g,b=rgb
    if name=="sky":return b>115 and b>r*1.12 and b>g*1.045
    # Match chroma across daylight and real block lighting. The shaded stock gold can be
    # (39,34,10); the unavailable texture is neutral navy (22,23,34).
    if name=="gold":return r>=20 and g>=16 and r>=.97*g and min(r,g)>b*1.52
    # Lapis has dark, strongly blue facets. The clear atmosphere is bright blue too.
    if name=="lapis":return 20<=b<218 and g<125 and b>r*1.68 and b>g*1.60
    if name=="red":return r>=18 and r>g*1.8 and r>b*1.5
    if name=="emerald":return g>=23 and g>1.4*r and g>1.16*b
    if name=="purple":return b>=24 and b>1.65*g and r>1.3*g and b>r*1.12
    if name=="orange":return r>=30 and r>g*1.45 and g>1.45*b
    if name=="magenta":return min(r,b)>60 and min(r,b)>1.45*g and .74<r/b<1.9
    raise ValueError(name)


def box(x1,y1,x2,y2):
    return max(0,round(side*x1)),max(0,round(side*y1)),min(side,round(side*x2)),min(side,round(side*y2))


def colors_in(im,rect,names):
    px=im.load()
    x1,y1,x2,y2=rect
    return {(x,y):name for x in range(x1,x2) for y in range(y1,y2)
            for name in names if color(px[x,y],name)}


def largest_component(points):
    remaining=set(points)
    maximum=0
    while remaining:
        stack=[remaining.pop()];n=0
        while stack:
            x,y=stack.pop();n+=1
            for pair in ((x-1,y),(x+1,y),(x,y-1),(x,y+1)):
                if pair in remaining:
                    remaining.remove(pair);stack.append(pair)
        maximum=max(maximum,n)
    return maximum


def colored_reference_coverage(sample,stock,rect,names,sample_before=None,stock_before=None):
    normal=colors_in(stock,rect,names)
    drawn=colors_in(sample,rect,names)
    def material_change(rgb,previous):
        return max(abs(a-b) for a,b in zip(rgb,previous))>18
    if stock_before is not None:
        normal={p:v for p,v in normal.items() if material_change(stock.getpixel(p),stock_before.getpixel(p))}
    if sample_before is not None:
        drawn={p:v for p,v in drawn.items() if material_change(sample.getpixel(p),sample_before.getpixel(p))}
    matching=sum(drawn.get(point)==tint for point,tint in normal.items())
    return {"stock_pixels":len(normal),"sample_pixels":len(drawn),"matched_pixels":matching,
            "stock_fraction":round(matching/max(1,len(normal)),4)}


def add_sheet(name,headers,rows,thumb=256):
    result=Image.new("RGB",((thumb+8)*len(headers),(thumb+8)*len(rows)+25),"#181a23")
    draw=ImageDraw.Draw(result)
    for i,title in enumerate(headers):draw.text((4+(thumb+8)*i,6),title,fill="white")
    for j,row in enumerate(rows):
        for i,frame in enumerate(row):
            result.paste(frame.resize((thumb,thumb),Image.Resampling.BOX),(4+i*(thumb+8),23+j*(thumb+8)))
    result.save(out/name)


report={"sky":{},"distance":{},"vista":{},"cost":{}}
for trial in ("old","new","solo"):
    assert (game(trial)/"PASS.txt").is_file()
assert receipt("solo","solo-unloaded-source")["refusals"]>0
assert (game("solo")/"solo-stock-source-unavailable-CamOwner.ok").is_file()
def range_receipt(shot,role="CamOwner"):
    return json.loads(Path(str(file("new",shot,role))+".range.json").read_text())


for shot,mode in (("candidate-perspective-first","FIRST_PERSON"),("candidate-perspective-back","THIRD_PERSON_BACK"),
                  ("candidate-perspective-front","THIRD_PERSON_FRONT")):
    result=range_receipt(shot)
    assert result["perspective"]==mode and result["surfaceCount"]>0 and result["freshScenes"]>=2,result
    pixels=colors_in(monitor("new",shot),box(.04,.28,.96,.92),["emerald","red","gold"])
    assert len(pixels)>100,(shot,"missing visible room screen scenery")
profile=range_receipt("candidate-visible-profile")
assert profile["trackedAppearance"]>0 and profile["profileTexture"].endswith("noor.png"),profile
assert range_receipt("candidate-guest-private-in-front-view","RoomBuilder")["freshRefusals"]>0
actor_box=box(profile["actorX"]-.06,profile["actorY"]-.10,profile["actorX"]+.06,profile["actorY"]+.10)
seen=colors_in(texture("new","candidate-visible-profile"),actor_box,["red"])
hidden=colors_in(texture("new","candidate-hidden-profile"),actor_box,["red"])
assert len(seen.keys()-hidden.keys())>40,(len(seen),len(hidden))
report["profile"]=profile

for trial in ("old","new"):
    rows=[]
    open_record=receipt(trial,"open-sky")
    for low,high in ((1,11),(11,21),(21,31)):
        rect=box(low/32,1/32,high/32,12/32)
        codes=[open_record["primaryTypes"][y*32+x] for y in range(1,12) for x in range(low,high)]
        sky=colored_reference_coverage(monitor(trial,"open-sky"),reference(trial,"open-sky"),rect,["sky"])
        sky["masked_primary"]=codes.count(0);sky["full_sky_primary"]=codes.count(1)
        sky["atmosphere_primary"]=codes.count(2);sky["geometry_primary"]=codes.count(3)
        sky["sampled_primary"]=len(codes);rows.append(sky)
    report["sky"][trial]=rows
    assert receipt(trial,"intervening-claim-sky")["upper"]==0
    assert receipt(trial,"genuine-unloaded-foreground")["upper"]==0
    assert receipt(trial,"after-last-observer-left")["refusals"]>0
    assert "stone_bricks" in receipt(trial,"same-source-restored")["palette"]
    assert "spruce_planks" in receipt(trial,"covered-sky")["palette"]
    near=colored_reference_coverage(monitor(trial,"standard-gold-after"),reference(trial,"standard-gold-after"),
                                    box(.25,.25,.75,.75),["gold"])
    assert near["matched_pixels"]>100,near
    assert receipt(trial,"checker-hidden-behind-claim")["center"]==0
    central=box(.43,.43,.57,.57)
    hidden_gold=colors_in(texture(trial,"checker-hidden-behind-claim"),central,["gold","lapis"])
    assert not hidden_gold,(trial,"occluded checker pixels",len(hidden_gold))
    if receipt(trial,"checker-hidden-behind-claim").get("samePacketRendererComparison"):
        denied_software=image(str(file(trial,"checker-hidden-behind-claim"))+".software.png")
        assert not colors_in(denied_software,central,["gold","lapis"]),"Denied checker visible in software rendering"
    empty_monitor=monitor(trial,"open-sky");empty_texture=texture(trial,"open-sky");empty_stock=reference(trial,"open-sky")
    for d in (24,48,64,80,96,112):
        shot=f"checker-{d}";stock=reference(trial,shot);stats=receipt(trial,shot)
        axial=stats["axialNearFaceBlocks"]
        rect=box(.5-8.1/axial,.5-5.1/axial,.5+8.1/axial,.5+5.1/axial)
        core=box(.5-2.8/axial,.5-1.9/axial,.5+2.8/axial,.5+1.9/axial)
        surface=texture(trial,shot);screen=monitor(trial,shot)
        metrics=colored_reference_coverage(screen,stock,rect,["gold","lapis"],empty_monitor,empty_stock)
        metrics["texture"]=colored_reference_coverage(surface,stock,rect,["gold","lapis"],empty_texture,empty_stock)
        if stats.get("samePacketRendererComparison"):
            software=image(str(file(trial,shot))+".software.png")
            metrics["software"]=colored_reference_coverage(software,stock,rect,["gold","lapis"],empty_texture,empty_stock)
            add_sheet("identical-96-packet.png",["Native terrain pass","Software terrain pass","Stock outdoor lens"],[[surface,software,stock]])
        component=largest_component(colors_in(surface,core,["gold"]))
        stock_component=largest_component(colors_in(stock,core,["gold"]))
        metrics.update({"stock_gold_core":stock_component,"largest_contiguous_gold_core":component,
                        "core_fraction":round(component/max(1,stock_component),4),
                        "primary_hits":stats["primaryCheckerRays"],"near_face_blocks":axial,
                        "nearest_sample":stats.get("nearestPrimaryHitBlocks"),
                        "fine_authorized":stats.get("fineAuthorized",0)})
        assert metrics["stock_pixels"]>35 and stock_component>15,(trial,d,"stock camera did not see checker",metrics)
        report["distance"][f"{trial}/{d}"]=metrics
    for wide in ((False,) if trial=="old" else (False,True)):
        shot="vista-wide" if wide else "vista-standard"
        stock=reference(trial,shot,wide);screen=monitor(trial,shot);surface=texture(trial,shot)
        angles=math.tan(math.radians(52 if wide else 45))
        record=receipt(trial,shot)
        assert abs(record["horizontalFovDegrees"]-(104 if wide else 90))<.01
        key=f"{trial}/{'wide' if wide else 'standard'}"; vista={}
        for i,(d,slope,tint) in enumerate(zip((24,48,64,80,96,112),(-.83,.79,-.46,.46,.03,-.18),
                                                ("red","gold","emerald","lapis","purple","orange"))):
            z=math.floor(d/math.sqrt(1+slope*slope)+.5);x=math.floor(z*slope+.5);radius=2+i
            axial=z-.734375
            rect=box(.5+(x-radius-1)/(2*axial*angles),.5-(radius+7)/(2*axial*angles),
                     .5+(x+radius+1)/(2*axial*angles),.5+4/(2*axial*angles))
            metrics=colored_reference_coverage(screen,stock,rect,[tint])
            metrics["texture"]=colored_reference_coverage(surface,stock,rect,[tint])
            metrics["near_face_center_radial_blocks"]=round(math.hypot(axial,x),2)
            # Each landmark is one block thick. Its near plane gives a conservative count
            # of stock silhouette pixels which could be in range; the far plane proves which
            # rays span the entire thickness in range. This matters for the 96-block crown.
            stock_cells=colors_in(stock,rect,[tint])
            eligible=set();entire=0
            for sx,sy in stock_cells:
                k=math.sqrt(1+(((sx+.5)*2/side-1)*angles)**2+((1-(sy+.5)*2/side)*angles)**2)
                if axial*k<=96:eligible.add((sx,sy))
                entire+=(axial+1)*k<=96
            metrics["stock_pixels_with_near_face_in_96"]=len(eligible)
            metrics["stock_pixels_with_full_thickness_in_96"]=entire
            metrics["displayed_matches_within_near_96"]=len(eligible&colors_in(screen,rect,[tint]).keys())
            assert metrics["stock_pixels"]>8,(key,"missing actual landmark in stock scene",d,metrics)
            vista[str(d)]=metrics
        edge=box(.91,.21,.995,.71)
        vista["magenta_edge"]=colored_reference_coverage(surface,stock,edge,["magenta"])
        if wide:
            assert vista["magenta_edge"]["matched_pixels"]>12,(key,"wide lens missed its edge target",vista["magenta_edge"])
        else:
            assert vista["magenta_edge"]["stock_pixels"]<12,(key,"standard lens unexpectedly includes edge target",vista["magenta_edge"])
        report["vista"][key]=vista
    times=json.loads((game(trial)/"server-capture-times.json").read_text())
    assert len(times)==12
    report["cost"][trial]={"server_capture_ms":times,"median_ms":round(statistics.median(times),2),
                           "max_ms":round(max(times),2),"sample":"authorized production capture, excluding network and client rendering"}
    if trial=="new":
        report["cost"][trial]["server_work_counters"]=json.loads((game(trial)/"server-capture-counters.json").read_text())

# Save the measured evidence even if a visual acceptance check fails.
add_sheet("open-sky-and-roof.png",["Previous wall monitor","Updated wall monitor","Stock outdoor lens"],[
    [monitor("old",s),monitor("new",s),reference("new",s)] for s in ("open-sky","covered-sky")])
add_sheet("landmark-panorama.png",["Previous 90-degree monitor","New 90-degree monitor","Stock 90-degree lens"],[
    [monitor("old","vista-standard"),monitor("new","vista-standard"),reference("new","vista-standard")]],320)
add_sheet("wide-vs-standard.png",["Updated monitor","Stock at the same field of view"],[
    [monitor("new",s),reference("new",s,w)] for w,s in ((False,"vista-standard"),(True,"vista-wide"))],320)
add_sheet("distance-comparison.png",["Previous monitor","Updated monitor","Stock outdoor lens"],[
    [monitor("old",f"checker-{d}"),monitor("new",f"checker-{d}"),reference("new",f"checker-{d}")] for d in (24,48,64,80,96,112)])
(out/"horizon-results.json").write_text(json.dumps(report,indent=2)+"\n")
print(json.dumps(report,indent=2))
failures=[]
if not all(s["stock_fraction"]>.72 for s in report["sky"]["new"]):
    failures.append(("open sky",report["sky"]["new"]))
for d in (48,64,80,96):
    current=report["distance"][f"new/{d}"];prior=report["distance"][f"old/{d}"]
    if current["stock_fraction"]<=prior["stock_fraction"]+.15:
        failures.append((d,"visible surface coverage",current["stock_fraction"],prior["stock_fraction"]))
    if current["core_fraction"]<=.4:
        failures.append((d,"gold center continuity",current["core_fraction"]))
if report["distance"]["new/80"]["stock_fraction"]<=.45:
    failures.append((80,"less than 45 percent of stock checker coverage"))
if report["distance"]["new/112"]["primary_hits"]!=0:
    failures.append((112,"preview exceeded the observation limit"))
for mode in ("standard","wide"):
    crown=report["vista"]["new/"+mode]["96"]
    if crown["matched_pixels"]<12 or crown["texture"]["matched_pixels"]<20:
        failures.append((96,mode,"purple-crown landmark not visibly depicted",crown["matched_pixels"],crown["stock_pixels"]))
assert not failures,failures
