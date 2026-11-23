"""Compare the original wall-night observations."""
import json
from pathlib import Path
from PIL import Image
from wall_capture_metrics import (OUT, SIDE, MASK, REGIONS, load, shot, sidecar, straighten, measure, average_color, save_grid)

VERSIONS = {"installed": "sharpcam.3 (installed)", "inherited": "pocketdefault.1", "candidate": "wallnight.1"}
scenes, results, failures = {}, {}, []
for trial, version in VERSIONS.items():
    folder = Path("observations") / f"wall-night-{trial}-evidence"
    if trial=="candidate":
        name = Path((folder/"runtime-jar.txt").read_text().strip().split(maxsplit=1)[1]).name
        version = name.removeprefix("anya-storage-plus-0.3.0-beta.7-").removesuffix(".jar")
        VERSIONS[trial] = version
    if not (folder / "PASS.txt").is_file():
        raise AssertionError(f"Missing completed client record for {version}")
    report = results[trial] = {}
    scenes[trial] = {}
    for state in ("gold", "blue"):
        path = shot(folder, "wall-" + state)
        panel = straighten(path)
        stock = load(shot(folder, "stock-" + state)).resize((SIDE,SIDE), Image.Resampling.BOX)
        texture = load(sidecar(path, ".texture.png"))
        exact = texture.resize((SIDE,SIDE), Image.Resampling.NEAREST)
        receipt = json.loads(sidecar(path, ".scene.json").read_text())
        if trial == "candidate":
            receipt["realRaster"] = json.loads(sidecar(path, ".gpu.json").read_text())
            cpu = load(sidecar(path, ".cpu.png")).resize((SIDE,SIDE), Image.Resampling.BOX)
            contribution = load(sidecar(path, ".gpu-mask.png")).resize((SIDE,SIDE), Image.Resampling.BOX)
            save_grid("actual-raster-" + state + ".png", [
                [("Minecraft stock lens",stock), ("Actual in-game monitor",panel)],
                [("Software fallback / identical input",cpu), ("White: exact GPU colors in monitor",contribution)]])
        for kind, image in (("monitor", panel), ("stock", stock), ("texture", exact)):
            scenes[trial][state, kind] = image
        receipt["sensorCoveragePercent"] = round(100*receipt["visibleRays"]/receipt["rays"], 2)
        receipt["exactNeutralMaskPercent"] = round(100*sum(p == MASK for p in texture.getdata())/(texture.width*texture.height), 2)
        receipt["regions"] = {}
        for label, area in REGIONS.items():
            unmasked = [p for p in area if exact.getpixel(p) != MASK]
            result = {"monitor": measure(panel, stock, area), "texture": measure(exact, stock, area),
                      "unmaskedPercent": round(100*len(unmasked)/len(area), 2)}
            if unmasked:
                result["textureOnVisiblePixels"] = measure(exact,stock,unmasked)
            receipt["regions"][label] = result
        report[state] = receipt
    # Explicitly distinguish scene change on the actual monitor from a changed control texture.
    for kind in ("stock","monitor"):
        gold = scenes[trial]["gold",kind]
        blue = scenes[trial]["blue",kind]
        marker = REGIONS["gold-to-lapis-sign"]
        g = average_color([gold.getpixel(p) for p in marker])
        b = average_color([blue.getpixel(p) for p in marker])
        shift = round((b[2]-b[0])-(g[2]-g[0]), 2)
        report[kind + "SignBlueMinusRedShift"] = shift
        if trial == "candidate" and shift < 8:
            failures.append(f"{version} {kind}: changed outdoor sign was not plainly separable, shift {shift}")
    masked_path = shot(folder,"private-sign")
    masked_texture = load(sidecar(masked_path, ".texture.png")).resize((SIDE,SIDE), Image.Resampling.NEAREST)
    sign = REGIONS["gold-to-lapis-sign"]
    masked_fraction = sum(masked_texture.getpixel(p) == MASK for p in sign)/len(sign)
    report["maskedSignPercent"] = round(100*masked_fraction, 2)
    if masked_fraction < .98:
        failures.append(f"{version}: only {masked_fraction:.2%} of the protected sign was masked")
    scenes[trial]["protected sign","monitor"] = straighten(masked_path)
    redacted = straighten(shot(folder,"private-screen"))
    restored = straighten(shot(folder,"wall-restored"))
    scenes[trial]["private","monitor"] = redacted
    scenes[trial]["restored","monitor"] = restored

for state in ("gold","blue"):
    installed = results["installed"][state]
    candidate = results["candidate"][state]
    if candidate["exactNeutralMaskPercent"] >= installed["exactNeutralMaskPercent"]:
        failures.append(f"{state}: the candidate did not reduce the missing area of the monitor")
    for surface in ("lit-cherry-facade", "pavement-7-to-20-blocks-through-alley"):
        before = installed["regions"][surface]
        after = candidate["regions"][surface]
        if after["unmaskedPercent"] <= before["unmaskedPercent"]:
            failures.append(f"{state} {surface}: the camera did not show more of the actual surface")
        if after["monitor"]["meanAbsRGBError"] >= before["monitor"]["meanAbsRGBError"]:
            failures.append(f"{state} {surface}: the photographed monitor did not get closer to the stock lens")
    save_grid("same-wall-" + state + ".png", [[
        ("Installed monitor", scenes["installed"][state, "monitor"]),
        ("Updated monitor", scenes["candidate"][state, "monitor"]),
        ("Minecraft first-person lens", scenes["candidate"][state, "stock"]),
    ]])
    rows = []
    for trial, version in VERSIONS.items():
        rows.append([(version + " / stock lens", scenes[trial][state,"stock"]),
                     (version + " / in-game monitor", scenes[trial][state,"monitor"]),
                     (version + " / monitor texture", scenes[trial][state,"texture"])])
    save_grid("night-" + state + ".png", rows)
save_grid("claim-revocation.png", [[(state,scenes["candidate"][state,"monitor"]) for state in ("blue","protected sign","private","restored")]])
for label, area in REGIONS.items():
    for state in ("gold", "blue"):
        common = [p for p in area if all(scenes[t][state,"texture"].getpixel(p) != MASK for t in VERSIONS)]
        for trial in VERSIONS:
            if common:
                results[trial][state]["regions"][label]["matchedVisiblePixels"] = measure(
                    scenes[trial][state,"texture"], scenes[trial][state,"stock"], common)
(OUT/"measurements.json").write_text(json.dumps(results, indent=2))
print(json.dumps({trial: {key: results[trial]["gold"][key] for key in ("sensorCoveragePercent","exactNeutralMaskPercent")}
                  for trial in VERSIONS}, indent=2))
if failures:
    raise AssertionError(failures)
