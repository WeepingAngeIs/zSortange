"""Check the trusted opening routes and presentation boundaries without Java."""

from pathlib import Path
import re


ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "src/main/java/dev/anya/storage"


def source(path):
    return (JAVA / path).read_text(encoding="utf-8")


def body(text, signature):
    start = text.index("{", text.index(signature)) + 1
    depth = 1
    for end in range(start, len(text)):
        depth += (text[end] == "{") - (text[end] == "}")
        if depth == 0:
            return text[start:end]
    raise AssertionError(f"Unclosed method {signature}")


def ordered(text, *tokens):
    position = -1
    for token in tokens:
        position = text.index(token, position + 1)


item = source("StorageItem.java")
content = source("Content.java")
network = source("Network.java")
menu = source("StoreMenu.java")
pockets = source("pocket/PocketService.java")
events = source("pocket/PocketEvents.java")
client = source("Client.java")
held_tablet = source("appearance/HeldTablet.java")
policy = source("ExpansionRules.java")
bounds = source("pocket/RoomBounds.java")
guide = source("pocket/ChestPadGuide.java")
admin_commands = source("StorageSettingsCommands.java")

bound = body(item, "static StorageEntity bound(")
ordered(bound, 'tag.contains("HubPos")', 'tag.getString("HubDimension")',
        "tag.getLong(\"HubPos\")", "hasChunkAt(pos)", "Network.hubReason(p,hub)")
assert "hub.radius()*hub.radius()" in body(network, "boolean inRange(")
ordered(body(network, "String hubReason("), "inRange(p,hub)", "Safety.reason(p,hub,null)")
assert re.search(r'\bREMOTE\s*=\s*ITEMS.register\("remote"', content)
assert re.search(r'\bCUPID_TABLET\s*=\s*ITEMS.register\("cupid_tablet"', content)
terminal = body(item, "boolean isTerminal(")
assert "Content.REMOTE.get()" in terminal and "Content.CUPID_TABLET.get()" in terminal
assert "isTerminal(context.getItemInHand())" in body(item, "InteractionResult useOn(")
assert "StorageItem.isTerminal(e.getItemStack())" in body(events, "void useBlock(")
ordered(body(item, "InteractionResultHolder<ItemStack> use("), "StorageEntity hub=bound(p,stack)",
        "if(hub!=null)", "if(!PocketService.remote(p,stack,hub))",
        "StoreMenu.remote(p,hub,stack.is(Content.CUPID_TABLET.get()))")
ordered(body(menu, "void openNetwork("), "Network.hubReason(p,hub)", "if(!reason.isEmpty())",
        "hub.permits(p,BasePermissions.VIEW)")
ordered(body(pockets, "boolean remote("), 'tag.hasUUID("PocketId")', "home(p,r)!=hub",
        "PocketLeases.Kind.REMOTE", "stack.is(Content.CUPID_TABLET.get())")
assert "new Pending(p,r.id(),kind,tablet)" in body(pockets, "void start(")
ordered(body(pockets, "void arrive("), "authorize(p,pocket,pending.kind(),true,0)",
        "if(!denied.isEmpty())", "pending.kind()==PocketLeases.Kind.REMOTE", "StoreMenu.pocketRemote",
        "pending.tablet()")
ordered(body(pockets, "void openChest("), "if(!physicalAllowed(p,chest))", "StoreMenu.pocketChest")
ordered(body(menu, "void openPocket("), "if(!view.valid())return;", "view.permits(BasePermissions.VIEW)", "p.openMenu")
assert "Opening.PLAIN" in body(menu, "void network(")
for entry in ("remote", "pocketRemote"):
    assert re.search(r"tablet\s*\?\s*Opening.TABLET\s*:\s*Opening.REMOTE", body(menu, f"void {entry}("))
assert "Opening.CHEST" in body(menu, "void pocketChest(")
assert "Opening.BACKPACK,!upgrades&&bag.valid()" in body(menu, "void bag(")
ordered(body(menu, "void container("), "Safety.reason(p,chest,null).isEmpty()", "Opening.CHEST")

decision = body(menu, "int openingFlags(")
assert re.search(r"case REMOTE\s*->\s*REMOTE_OPEN\s*;", decision)
ordered(decision, "if (!mayView", "case TABLET", "REMOTE_OPEN | TABLET_RULE",
        "policy.rollTabletOpening(ThreadLocalRandom.current()) ? TABLET_INTRO : 0",
        "case CHEST -> policy.rollChestCameo", "case BACKPACK -> policy.backpackCameoEnabled",
        "case PLAIN -> 0")
storage = body(menu, "void storage()")
assert "mayView?openingFlags:0" in storage
ordered(storage, "pocket.permits(BasePermissions.VIEW)", "containers=!mayView?List.of()",
        "pocket.containers()", "for(Container container:containers)", "capacity++",
        "synchronizedInt(6,capacity)", "if (pocket != null) pocketControls(mayView, containers.size())")
ordered(body(menu, "void pocketControls("), "mayView && (openingFlags & REMOTE_OPEN) != 0",
        "if (showUpgrades)", "chestCount - RoomBounds.starterChestCount()",
        "ControlGlyph.POCKET_EDIT", 'RoomBounds.PAD_X.size() + " upgrade pads occupied"',
        "ControlGlyph.EXIT")
assert "return STARTER_CHEST_X.size();" in body(bounds, "int starterChestCount(")
ordered(body(client, "void drawPocketPadSummary("), "!menu.storageView()", "!menu.remoteOpening()",
        "menu.privateView()", "!menu.controlSlot(slot)", "face.glyph() != ControlGlyph.POCKET_EDIT",
        "lore.lines().get(0)", "graphics.drawString")
assert 'commandLink("Room upgrades", ClickEvent.Action.RUN_COMMAND, PLAN)' in body(guide, "Component upgradesLink(")
ordered(body(guide, "void offerUpgradesOnArrival("), "canOfferUpgrades(player, room)", "upgradesLink()")
assert "Config.pocketStorageEnabled()" in body(pockets, "boolean enabled()")
for source_code in (pockets, policy):
    assert 'Boolean.getBoolean("anyastorage.pocketPrototype")' not in source_code
ordered(body(pockets, "void validateStorageChange("), "enabling && dimension(server) == null",
        "throw new IllegalArgumentException", "!enabling && currentServer == server && !PREPARING.isEmpty()",
        "throw new IllegalArgumentException")
assert '.then(StorageSettingsCommands.settings())' in source("AnyaStorage.java")
ordered(source("pocket/PocketEvents.java"), "StorageSettingsCommands.pocketCommands()", 'Commands.literal("exit")')
ordered(body(admin_commands, " settings()"), 'Commands.literal("settings").requires(source -> source.hasPermission(2))',
        'Commands.literal("set")', "apply(actor, key, value)")
ordered(body(admin_commands, " pocketCommands()"), 'pocketToggle("enable", true)', 'pocketToggle("disable", false)',
        'Commands.literal("status").requires(source -> source.hasPermission(2))')
ordered(body(admin_commands, " pocketToggle("), "requires(source -> source.hasPermission(2))", "apply(actor, POCKET_STORAGE")
ordered(body(admin_commands, "boolean apply("), "Config.serverSettings(actor)", "Config.setServerSetting(actor, snapshot.revision()")
assert re.search(r"tabletOpeningChancePercent\s*=\s*24;", policy)
assert re.search(r"chestCameoEnabled\s*=\s*false;", policy)
assert re.search(r"chestCameoChancePercent\s*=\s*1;", policy)
assert re.search(r"backpackCameoEnabled\s*=\s*false;", policy)
assert "random.nextInt(100) < tabletOpeningChancePercent" in body(policy, "boolean rollTabletOpening(")
assert "chestCameoEnabled && random.nextInt(100) < chestCameoChancePercent" in body(policy, "boolean rollChestCameo(")

assert "tabletCoversView =" not in body(client, "public Screen(StoreMenu menu")
ordered(body(client, "void classifyOpening("), "if (openingClassified || !menu.storageView()) return;",
        "showTabletFrame() && menu.showsTabletIntro()",
        "!menu.privateView() && !menu.remoteOpening() && menu.showsStorageCameo()")
frame = body(client, "boolean showTabletFrame(")
assert all(flag in frame for flag in ("!menu.privateView()", "menu.remoteOpening()", "menu.hasTabletOpeningRule()"))
observe = body(held_tablet, "Held observe(")
assert observe.count("stack.is(Content.CUPID_TABLET.get())") == 2 and "Content.REMOTE.get()" not in observe
assert "Cameo" not in body(client, "boolean blockTabletInput(")
ordered(body(client, "void render(GuiGraphics g"), "Inventory: deposit. Wheel: scroll.", "drawStorageCameo(g)", "g.pose().popPose()")

print("PASS: both terminals use existing binding, dimension, radius, and access checks")
print("PASS: terminal flags require View, and only the Cupid Tablet rolls for the intro")
print("PASS: chest and backpack settings default off, use separate branches, and never gate input")
print("PASS: both authorized pocket terminals show the pad summary and gated Room upgrades link")
print("PASS: pocket authorization reads the effective server policy without the old JVM gate")
print("PASS: operator settings and pocket commands use the shared revision-checked policy API")
