"""Check both surfaces in the real Minecraft framebuffer, including the authorized opening."""
from io import BytesIO
from pathlib import Path
import sys
from zipfile import ZipFile

from PIL import Image, ImageChops


def main(jar_path, folder):
    images = {}
    def image(name):
        if name not in images:
            with Image.open(folder / (name + ".png")) as shot:
                assert shot.size == (960, 640), f"Unexpected framebuffer size {shot.size}"
                images[name] = shot.convert("RGB")
        return images[name]

    # Scale 2, selected hotbar slot index 1. Both regions lie inside the glass, clear of the
    # selected-slot outline and the item name. The wearable's glass is unlit; the icon is shaded.
    icon = (349, 610, 371, 627)
    hand = (650, 515, 835, 610)

    def ink(name, area):
        return sum(n for n, (r, g, b) in image(name).crop(area).getcolors(960 * 640)
                   if r < 170 and b > 54 and b - r > 30 and g - r > 12 and b - g > 11)

    dark_icon, dark_hand = ink("held-dark", icon), ink("held-dark", hand)
    for name in ("held-squint", "held-shut", "held-glance"):
        counts = (ink(name, icon), ink(name, hand))
        print(name, "blue glass pixels (icon, hand)", counts)
        assert counts[0] > dark_icon + 45 and counts[1] > dark_hand + 500, "Missing face on " + name
    assert ink("hotbar-squint", icon) > ink("hotbar-dark", icon) + 45
    for name in ("held-reselected", "held-right", "held-returned"):
        assert ink(name, icon) < dark_icon + 20 and ink(name, hand) < dark_hand + 400, "Face replayed on " + name

    def icon_pixels(name):
        return image(name).crop(icon).tobytes()

    # Identical physical item, same slot and camera. Sampling the interior excludes selection
    # highlight changes. Only the baked expression should change during those three frames.
    assert len({icon_pixels(name) for name in ("held-squint", "held-shut", "held-glance", "held-dark")}) == 4
    def color_distance(left, right):
        return sum(abs(a - b) for a, b in zip(icon_pixels(left), icon_pixels(right)))
    assert color_distance("hotbar-squint", "held-squint") < color_distance("hotbar-squint", "held-dark")
    assert color_distance("hotbar-dark", "held-dark") < color_distance("hotbar-dark", "held-squint")

    # The physical tablet was dark when used. An explicitly authorized 100% opening first
    # carries the dark hardware, then paints the usual portrait and reveals the inventory.
    inks = set()
    with ZipFile(jar_path) as jar:
        for path in ("gui/tablet_guardian", "gui/tablet_gaze_left"):
            with Image.open(BytesIO(jar.read("assets/anyastorage/textures/" + path + ".png"))) as texture:
                inks.update(color for n, color in texture.convert("RGB").getcolors(texture.width * texture.height)
                            if color[0] < 146 and color[2] - color[0] > 54 and color[1] - color[0] > 22)
    def approved_ink(name):
        return sum(n for n, color in image(name).getcolors(960 * 640) if color in inks)
    opens = [approved_ink(f"lift-{i}") for i in range(5)]
    print("Authorized opening, authored blue pixels per sample", opens)
    assert max(opens) > approved_ink("held-right") + 3000, "Authorized tablet opening lost Cupid"
    for name in ("tablet-inventory", "held-turn", "held-left", "held-pause", *(f"return-{i}" for i in range(5))):
        image(name)
    creative_icon = tuple(p + (40 if i % 2 == 0 else 0) for i, p in enumerate(icon))
    creative_dark = (ink("creative-held-dark", creative_icon), ink("creative-held-dark", hand))
    for name in ("creative-held-squint", "creative-held-shut", "creative-held-glance"):
        assert ink(name, creative_icon) > creative_dark[0] + 45 and ink(name, hand) > creative_dark[1] + 500, name
    assert len({image("creative-held-" + face).crop(creative_icon).tobytes()
                for face in ("squint", "shut", "glance", "dark")}) == 4
    assert ink("creative-hotbar-squint", creative_icon) > creative_dark[0] + 45
    assert ink("creative-returned", hand) < creative_dark[1] + 400
    assert max(approved_ink("creative-lift-" + str(i)) for i in range(5)) > approved_ink("held-right") + 3000
    # Compare rose-silver shades actually painted on the Creative hardware, across every pose.
    with ZipFile(jar_path) as jar:
        with Image.open(BytesIO(jar.read("assets/anyastorage/textures/gui/creative_tablet_device.png"))) as shell:
            rose = {(r, g, b) for _, (r, g, b, a) in shell.convert("RGBA").getcolors(shell.width * shell.height)
                    if a == 255 and r - g > 25 and b - g > 12 and r > b + 6}
    def rose_pixels(name):
        return sum(n for n, rgb in image(name).getcolors(960 * 640) if rgb in rose)
    for name, plain in [("creative-held-dark", "held-dark"), ("creative-inventory", "tablet-inventory"),
                        ("creative-returned", "held-returned"),
                        *((f"creative-lift-{i}", f"lift-{i}") for i in range(5)),
                        *((f"creative-return-{i}", f"return-{i}") for i in range(5))]:
        assert rose_pixels(name) > rose_pixels(plain) + 250, "Lost the pink-silver shell in " + name
    # Native GUI item lighting changes its palette. The two item rims share geometry.
    def pink_icon(name, slot):
        colors = image(name).crop((302 + 40 * slot, 600, 338 + 40 * slot, 638)).getcolors(36 * 38)
        return sum(n for n, (r, g, b) in colors if r - g > 19 and b - g > 8 and r > b + 4)
    assert pink_icon("creative-held-dark", 2) > pink_icon("held-dark", 1) + 5, "Missing Creative hotbar outline"
    # With the pointer left over the same slot, the setting removes the real vanilla/custom
    # tooltip from the storage grid. Exclude the toggle itself, hotbar, and exterior scene.
    crop = (135, 136, 650, 295)
    delta = ImageChops.difference(image("tooltips-on").crop(crop), image("tooltips-off").crop(crop))
    changed = sum(n for n, rgb in delta.getcolors(960 * 640) if max(rgb) > 15)
    print("Tooltip overlay changed pixels at the same hovered slot", changed)
    assert changed > 250, "Tooltip switch did not remove the visible hover overlay"
    print("PASS: hotbar and hand squint, blink, glance, hide and stay hidden on reselect; authorized blink and Escape")


if __name__ == "__main__":
    main(Path(sys.argv[1]), Path(sys.argv[2]))
