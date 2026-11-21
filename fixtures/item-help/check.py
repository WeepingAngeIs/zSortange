"""Verify normal item help inside the playable JAR and compare it with the hosted roomeditor.2 build."""

import argparse
from collections import defaultdict
from decimal import Decimal
from hashlib import sha256
import json
from pathlib import Path
import re
import tomllib
from zipfile import ZipFile


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/dev/anya/storage"
BASELINE_SHA = "b2b5e5ee94d95e74ba4d46cd2d5b4db5ef18506875111d88b054c30000e1f5a8"
LANG = "assets/anyastorage/lang/en_us.json"
METADATA = "META-INF/neoforge.mods.toml"
PREFIX = "tooltip.anyastorage."
DYNAMIC = {PREFIX + "backpack.stats", PREFIX + "backpack.appearance"}
CLASSES = "dev/anya/storage/"


def read_json(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            assert key not in result, f"Duplicate JSON key: {key}"
            result[key] = value
        return result
    return json.loads(data, object_pairs_hook=unique)


def java(path):
    return (JAVA / path).read_text(encoding="utf-8")


def item_ids():
    content = java("Content.java")
    sources = content + java("pocket/PocketContent.java")
    ids = set(re.findall(r'\bITEMS\.register(?:SimpleItem)?\(\s*"([a-z0-9_]+)"\s*[,)]', sources))
    blocks = dict(re.findall(r'\b([A-Z_]+)\s*=\s*(?:Content\.)?BLOCKS\.register\(\s*"([a-z0-9_]+)"', sources))
    ids.update(blocks[ref] for ref in re.findall(r'ITEMS\.registerSimpleBlockItem\(\s*([A-Z_]+)\s*\)', sources))

    def choices(source, name):
        match = re.search(r'\b' + name + r'\s*=\s*List\.of\(([^)]*)\)', java(source))
        assert match, f"Unknown registry source for {name}"
        return set(re.findall(r'"([a-z0-9_]+)"', match[1]))

    for list_name, prefix, skipped in (("ELEMENTS", "element_", "plain"), ("SKINS", "skin_", "elemental")):
        assert f'ITEMS.registerSimpleItem("{prefix}"+' in content and f'.equals("{skipped}")' in content
        ids.update(prefix + value for value in choices("appearance/Looks.java", list_name) - {skipped})
    assert 'ITEMS.registerSimpleItem(id+"_upgrade",' in content
    ids.update(value + "_upgrade" for value in choices("Config.java", "UPGRADE_IDS"))
    assert len(ids) == 34, f"Expected 34 registered items, found {sorted(ids)}"
    return ids


def check_language(previous, candidate, ids):
    old = read_json(previous.read(LANG))
    new = read_json(candidate.read(LANG))
    assert all(new.get(key) == text for key, text in old.items()), "An existing translation changed"
    by_id = defaultdict(dict)
    for key in new.keys() - old.keys() - DYNAMIC:
        match = re.fullmatch(re.escape(PREFIX) + r'([a-z0-9_]+)\.([1-9]\d*)', key)
        assert match and match[1] in ids, f"Unexpected language key: {key}"
        text = new[key]
        assert isinstance(text, str) and text.strip() == text and text and text.isprintable(), key
        assert not re.search(r'\b(?:tooltip|key|block|item)\.[a-z0-9_.]+|%[0-9$]*[sd]', text), key
        by_id[match[1]][int(match[2])] = text
    assert set(by_id) == ids, f"Missing item help: {sorted(ids - by_id.keys())}"
    for item, lines in by_id.items():
        assert sorted(lines) == list(range(1, len(lines) + 1)), f"Gap in {item} numbered help"
        assert len({" ".join(line.casefold().split()) for line in lines.values()}) == len(lines), f"Repeated line in {item}"
    for key in DYNAMIC:
        assert set(re.findall(r'%\d+\$s', new[key])) == {"%1$s", "%2$s"} and new[key].count("%") == 2, key
        assert key.encode() in candidate.read(CLASSES + "StorageItem.class"), f"Missing dynamic translation in bytecode: {key}"
    backpack = java("StorageItem.java")
    for snippet in ('stack.has(net.minecraft.core.component.DataComponents.CUSTOM_NAME)', 'lines.add(getName(stack))',
                    'tag.contains("Capacity") ? tag.getInt("Capacity") : 27',
                    'tag.contains("UpgradeSlots") ? tag.getInt("UpgradeSlots") : 1',
                    '"tooltip.anyastorage.backpack.stats", capacity, upgrades',
                    '"tooltip.anyastorage.backpack.appearance", look.element(), look.skin()'):
        assert snippet in backpack, f"Missing saved backpack information: {snippet}"
    return by_id


def check_magma(lines):
    default = re.search(r'\bmagmaReduction\s*=\s*(\d*\.\d+)', java("ExpansionRules.java"))
    percent = format((Decimal(default[1]) * 100).normalize(), "f") + "%"
    text = " ".join(lines.values()).lower()
    for phrase in ("fire damage", percent, "default", "wear", "backpack", "elemental effects", "enabled"):
        assert phrase in text, f"Magma binding help omits {phrase}"
    assert re.search(r'\bless\b|\breduc\w*', text), "Magma protects against a fraction of fire damage"
    effect = "".join(java("appearance/Wearables.java").split())
    assert 'ExpansionRules.get().elementalEffects&&Config.get().backpacks&&Looks.of(selected(p)).element().equals("magma")' in effect
    assert '!selected(p).isEmpty()&&event.getSource().is(DamageTypeTags.IS_FIRE)' in effect
    assert 'event.getAmount()*(1-ExpansionRules.get().magmaReduction)' in effect


def check_scope(previous, candidate):
    def files(jar):
        names = [info.filename for info in jar.infolist() if not info.is_dir()]
        assert len(names) == len(set(names)), "Duplicate archive members"
        return set(names)

    old, new = files(previous), files(candidate)
    assert new - old == {CLASSES + "ItemHelp.class"}, f"Unexpected added files: {sorted(new - old)}"
    assert old - new == {CLASSES + "pocket/PocketContent$1.class"}, f"Unexpected removed files: {sorted(old - new)}"
    assert candidate.testzip() is None, "Corrupt playable archive"
    for path in sorted(old & new):
        before, after = previous.read(path), candidate.read(path)
        if path == METADATA:
            base = tomllib.loads(before.decode())
            actual = tomllib.loads(after.decode())
            version = re.search(r'^mod_version=(\S+)$', (ROOT / "gradle.properties").read_text(), re.M)[1]
            assert base["mods"][0]["version"] == "0.3.0-beta.7-roomeditor.2" and actual["mods"][0]["version"] == version
            base["mods"][0]["version"] = version
            assert base == actual, "Non-version mod metadata changed"
            continue
        if before == after or path == LANG:
            continue
        if path in {CLASSES+"StorageItem.class", CLASSES+"guide/GuideItem.class", CLASSES+"pocket/PocketContent.class"}:
            continue
        if path.startswith(CLASSES+"pocket/PocketContent$") and path.endswith(".class"):
            continue  # Removing the anonymous tooltip class also moves the nested classes' line numbers.
        if path.endswith((".json", ".mcmeta")) and path.startswith(("assets/", "data/")):
            assert read_json(before) == read_json(after), f"Gameplay data or assets changed: {path}"
        else:
            assert before == after, f"Unexpected changed file: {path}"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("jar", type=Path)
    parser.add_argument("baseline", type=Path)
    args = parser.parse_args()
    assert sha256(args.baseline.read_bytes()).hexdigest() == BASELINE_SHA, "Expected the published roomeditor.2 JAR"
    with ZipFile(args.baseline) as previous, ZipFile(args.jar) as candidate:
        check_scope(previous, candidate)
        lines = check_language(previous, candidate, item_ids())
        check_magma(lines["element_magma"])
    print("PASS ITEM_HELP_PACKAGE: contiguous English hover text for 34 registered items, saved backpack details and Magma conditions.")
    print("PASS ITEM_HELP_SCOPE: packaged recipes, gameplay data, art, sounds and unrelated classes match roomeditor.2.")


if __name__ == "__main__":
    main()
