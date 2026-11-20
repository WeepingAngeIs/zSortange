"""Check the compiled furniture display protocol and candidate JAR resources without launching Minecraft."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import zipfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tablet-lifecycle"))
from run import cached_paths, invoke


def check_archive(jar):
    with zipfile.ZipFile(jar) as archive:
        def read(path):
            return json.loads(archive.read(path))
        lang = read("assets/anyastorage/lang/en_us.json")
        for name in ("keepsake_shelf", "delivery_hatch"):
            model = read(f"assets/anyastorage/models/block/{name}.json")
            assert len(model["elements"]) > 5
            for element in model["elements"]:
                assert len(element["faces"]) == 6
                assert all(face["texture"][1:] in model["textures"] for face in element["faces"].values())
            assert len(read(f"assets/anyastorage/blockstates/{name}.json")["variants"]) == 4
            recipe = read(f"data/anyastorage/recipe/{name}.json")
            assert recipe["result"] == {"id": f"anyastorage:{name}", "count": 1}
            assert recipe["key"].keys() == set("".join(recipe["pattern"])) - {" "}
            loot = read(f"data/anyastorage/loot_table/blocks/{name}.json")
            assert loot["pools"][0]["entries"] == [{"type":"minecraft:item","name":"anyastorage:"+name}]
            assert lang[f"block.anyastorage.{name}"]
        assert set(read("data/carryon/tags/block/block_blacklist.json")["values"]) == {
            "anyastorage:keepsake_shelf", "anyastorage:delivery_hatch"}
    print("PASS furniture placement models, facing states, recipes, names, empty-block loot and mover exclusion")


def check_render_entry(jdk,jar):
    code=subprocess.check_output([jdk/"bin/javap","-p","-c","-classpath",str(jar),
                                  "dev.anya.storage.furniture.ShelfRenderer"],text=True)
    projection=code.split("public static void projection(",1)[1].split("private static org.joml.Vector2f screenPoint(",1)[0]
    assert "getProjectionMatrix:" in projection and "getModelViewMatrix:" in projection
    assert all(word not in projection for word in ("renderItem:","drawString:","Field model:","Field caption:"))
    draw=code.split("public static void draw(",1)[1]
    assert "RenderGuiEvent$Post" in draw and all(k in draw for k in ("ShelfGrant.visible:","glGetInteger:","frameBufferId:","picked:"))
    assert code.count("GuiGraphics.renderItem:")==1 and code.count("GuiGraphics.drawString:")==1
    assert "GuiGraphics.renderItem:" in draw and "GuiGraphics.drawString:" in draw
    print("PASS private drawing occurs only at final HUD composition with a live grant and the main framebuffer")


if __name__ == "__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("--jdk",required=True,type=Path)
    p.add_argument("--classpath-file",required=True,type=Path)
    p.add_argument("--support-jar",required=True,type=Path)
    args=p.parse_args()
    jar=args.support_jar.resolve()
    check_archive(jar)
    check_render_entry(args.jdk.resolve(),jar)
    with tempfile.TemporaryDirectory(prefix="anya-furniture-check-") as d:
        tmp=Path(d)
        cp=os.pathsep.join((str(tmp),str(jar),cached_paths(args.classpath_file.resolve())))
        for task,argv in [
            ("javac",["--release","21","-proc:none","-encoding","UTF-8","-cp",cp,"-d",tmp,HERE/"FurnitureWireCheck.java",
                       HERE/"FurnitureInventoryCheck.java",HERE/"ShelfGeometryCheck.java",HERE.parent/"chest-expansion/OfflineRegistries.java"]),
            ("java",["-cp",cp,"dev.anya.storage.furniture.FurnitureWireCheck"]),
            ("java",["-cp",cp,"dev.anya.storage.furniture.FurnitureInventoryCheck",tmp]),
        ]:
            invoke(args.jdk.resolve(),task,argv,tmp)
