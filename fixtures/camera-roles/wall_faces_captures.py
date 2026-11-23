"""Score the same reproducible alley with a live indoor monitor and two outdoor lens controls."""
import json
from pathlib import Path
from PIL import Image
from wall_capture_metrics import OUT, SIDE, MASK, REGIONS, load, shot, sidecar, straighten, measure, save_grid


def centered_square(image):
    side = min(image.size)
    x, y = (image.width - side) // 2, (image.height - side) // 2
    return image.crop((x, y, x + side, y + side)).resize((SIDE, SIDE), Image.Resampling.LANCZOS)


results, images, failures = {}, {}, []
for runtime in ("baseline", "candidate"):
    folder = Path("observations") / f"wallfaces-{runtime}-captures"
    assert (folder / "PASS.txt").is_file(), f"Missing ordinary player interactions for {runtime}"
    results[runtime] = {"source": (folder / "playable-source.txt").read_text().strip(),
                        "jar": (folder / "runtime-jar.txt").read_text().split()[0]}
    for phase, prefix in (("night", ""), ("day", "day-")):
        wall = shot(folder, prefix+"wall-blue")
        stock = centered_square(load(shot(folder, prefix+"stock-blue")))
        normal = centered_square(load(shot(folder, phase+"-player-view")))
        panel = straighten(wall)
        raw = load(sidecar(wall, ".texture.png"))
        tex = raw.resize((SIDE, SIDE), Image.Resampling.NEAREST)
        gpu = json.loads(sidecar(wall, ".gpu.json").read_text())
        report = {"gpu": gpu, "maskPercent": 100 * sum(px == MASK for px in raw.getdata()) / (raw.width * raw.height)}
        for label, points in REGIONS.items():
            report[label] = {"maskPercent": 100 * sum(tex.getpixel(p) == MASK for p in points)/len(points),
                             "monitorToPlayer": measure(panel, normal, points),
                             "stockToPlayer": measure(stock, normal, points)}
        results[runtime][phase] = report
        images[runtime, phase] = panel, stock, normal, tex
        if gpu["visibleExactGpuColors"] < gpu["pixels"]//10:
            failures.append(f"{runtime} {phase}: actual native terrain pixels absent")
        if report["whole"]["stockToPlayer"]["meanAbsRGBError"] > 15:
            failures.append(f"{runtime} {phase}: lens and real first-person camera do not match")
    claim = load(sidecar(shot(folder, "private-sign"), ".texture.png")).resize((SIDE,SIDE), Image.Resampling.NEAREST)
    points = REGIONS["gold-to-lapis-sign"]
    if sum(claim.getpixel(p) == MASK for p in points) /len(points) < .98:
        failures.append(f"{runtime}: a claimed surface was visible on screen")
    day = results[runtime]["day"]["pavement-3-to-8-blocks"]["monitorToPlayer"]
    night = results[runtime]["night"]["pavement-3-to-8-blocks"]["monitorToPlayer"]
    if day["meanLuma"] - night["meanLuma"] < 10 or day["stockMeanLuma"] - night["stockMeanLuma"] < 10:
        failures.append(f"{runtime}: noon and night did not visibly differ on the monitor and player control")

for phase in ("night", "day"):
    baseline = images["baseline",phase]
    panel, stock, normal, tex = images["candidate",phase]
    save_grid(phase+"-comparison.png", [[("Earlier monitor / wallnight.2",baseline[0]),
              ("Updated indoor monitor",panel),("Normal first-person view",normal)],
              [("Earlier monitor texture",baseline[3]),("Updated monitor texture",tex),("Independent stock lens",stock)]])
    if results["candidate"][phase]["maskPercent"] >= results["baseline"][phase]["maskPercent"]:
        failures.append(f"{phase}: certified terrain coverage did not improve")
(OUT/"measurements.json").write_text(json.dumps(results,indent=2))
print(json.dumps({r:{p:round(results[r][p]["maskPercent"],3) for p in ("night","day")} for r in results},indent=2))
if failures:
    raise AssertionError(failures)
