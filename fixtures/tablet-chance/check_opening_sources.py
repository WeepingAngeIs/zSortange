"""Check the trusted opening routes and presentation boundaries without Java."""

from pathlib import Path
import re
import json
import sys
from zipfile import ZipFile


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
        position = text.find(token, position + 1)
        assert position >= 0, f"Missing or out-of-order source fragment: {token}"


item = source("StorageItem.java")
content = source("Content.java")
network = source("Network.java")
creative = source("CreativeTabletAccess.java")
menu = source("StoreMenu.java")
pockets = source("pocket/PocketService.java")
pocket_view = source("pocket/PocketView.java")
events = source("pocket/PocketEvents.java")
client = source("Client.java")
held_tablet = source("appearance/HeldTablet.java")
policy = source("ExpansionRules.java")
bounds = source("pocket/RoomBounds.java")
guide = source("pocket/ChestPadGuide.java")
admin_commands = source("StorageSettingsCommands.java")

if len(sys.argv)>1:
    with ZipFile(sys.argv[1]) as jar:
        assets = "assets/anyastorage/"
        recipes = "data/anyastorage/recipe/"
        assert recipes+"remote.json" not in jar.namelist()
        assert recipes+"creative_tablet.json" not in jar.namelist()
        outputs = []
        for path in jar.namelist():
            if path.startswith(recipes) and path.endswith(".json"):
                result = json.loads(jar.read(path)).get("result",{})
                outputs.append(result.get("id") if isinstance(result,dict) else result)
        assert "anyastorage:creative_tablet" not in outputs and "anyastorage:remote" not in outputs
        assert "anyastorage:cupid_tablet" in outputs
        assert json.loads(jar.read(assets+"models/item/remote.json"))["parent"]=="anyastorage:item/cupid_tablet_shell"
        assert assets+"models/item/creative_tablet.json" in jar.namelist()
        names = json.loads(jar.read(assets+"lang/en_us.json"))
        assert names["item.anyastorage.creative_tablet"]=="UwU Tablet"
        assert names["item.anyastorage.cupid_tablet"]=="Cupid Tablet"

bound = body(item, "static StorageEntity bound(")
ordered(bound, 'tag.contains("HubPos")', 'tag.getString("HubDimension")',
        "tag.getLong(\"HubPos\")", "hasChunkAt(pos)", "Network.hubReason(p,hub)")
assert "hub.radius()*hub.radius()" in body(network, "boolean inRange(")
ordered(body(network, "String hubReason("), "inRange(p,hub)", "Safety.reason(p,hub,null)")
assert re.search(r'\bREMOTE\s*=\s*ITEMS.register\("remote"', content)
assert re.search(r'\bCUPID_TABLET\s*=\s*ITEMS.register\("cupid_tablet"', content)
assert re.search(r'\bCREATIVE_TABLET\s*=\s*ITEMS.register\("creative_tablet"', content)
assert ".filter(item -> item.get()!=REMOTE.get())" in content
terminal = body(item, "boolean isTerminal(")
assert "Content.REMOTE.get()" in terminal and "isTablet(stack)" in terminal
assert "Content.CUPID_TABLET.get()" in body(item,"boolean isTablet(")
assert "Content.CREATIVE_TABLET.get()" in body(item,"boolean isCreativeTablet(")
assert "CreativeTabletAccess.mayUse(player,stack)" in body(item,"boolean permittedTablet(")
eligibility = body(creative,"boolean mayUse(")
ordered(eligibility, "StorageItem.isCreativeTablet(tablet)", "tablet.getCount()==1", "player.isAlive()",
        "!player.isSpectator()", "getPlayer(player.getUUID())==player", "getConnection().isConnected()")
assert all(gate not in creative for gate in ("GameType.CREATIVE", ".isCreative()", ".instabuild"))
for signature in ("void bind(ServerPlayer", "void bindPocket(ServerPlayer"):
    ordered(body(creative,signature), "!mayUse(p,tablet)", "p.getMainHandItem()!=tablet", "stamp(p,tablet,hub)")
ordered(body(creative,"boolean held()"), "sessions.get(player.getUUID())==this", "mayUse(player,stack)",
        "player.getItemInHand(hand)==stack", "stack.get(DataComponents.CUSTOM_DATA)==components")
for signature in ("ItemStack tabletInHand(", "void inventoryTick("):
    assert "transmuteCopy(Content.CUPID_TABLET.get())" in body(item,signature)
assert "isTerminal(context.getItemInHand())" in body(item, "InteractionResult useOn(")
assert "StorageItem.isTerminal(e.getItemStack())" in body(events, "void useBlock(")
ordered(body(item, "InteractionResultHolder<ItemStack> use("), "tabletInHand(p,hand)", "!permittedTablet(p,stack)",
        "PocketService.openVisitedTablet(p,hand)", "isCreativeTablet(stack)",
        "CreativeTabletAccess.open(p,hand)", "StorageEntity hub=bound(p,stack)",
        "if(hub!=null)", "if(!PocketService.remote(p,stack,hub))",
        "StoreMenu.remote(p,hub,true)")
ordered(body(menu, "void openNetwork("), "opening==Opening.CREATIVE?CreativeTabletAccess.session(p):null",
        "Network.hubReason(p,hub,session)", "if(!reason.isEmpty())",
        "hub.permits(p,BasePermissions.VIEW)")
ordered(body(pockets, "boolean remote("), 'tag.hasUUID("PocketId")', "!StorageItem.isTablet(stack)",
        "!StorageItem.permittedTablet(p,stack)", "home(p,r)!=hub", "PocketLeases.Kind.REMOTE")
ordered(body(pockets, "String authorize("), "!enabled()", "homeAccess(p,pocket,hub",
        "CreativeTabletAccess.pocketSession(p,pocket.id())", "CreativeTabletAccess.permitsPocket(p,pocket,hub)",
        "!Network.inRange(p,hub)")
ordered(body(creative,"void open(ServerPlayer"), "p.getItemInHand(hand)", "!mayUse(p,tablet)",
        "Binding.read(tag)", "MessageDigest.isEqual", "getWorldBorder().isWithinBounds(pos)",
        "isUnderSpawnProtection", "new Session(")
local = body(pockets,"boolean openVisitedTablet(")
ordered(local,"!inPocket(player.level())", "player.getItemInHand(hand)", 'data.hasUUID("PocketId")',
        "lease(player.getUUID())", "!lease.pocket().equals(room.id())", "lease.kind()!=PocketLeases.Kind.VISIT",
        "player.containerMenu!=player.inventoryMenu", "PocketView.localTablet(player,room,hand)",
        "StoreMenu.visitTablet(player,view,StorageItem.isCreativeTablet(tablet))")
ordered(body(pocket_view,"private PocketView("), "if(localTablet())", "!StorageItem.permittedTablet(player,tablet)",
        'data.getUUID("PocketId")', 'data.getLong("HubPos")!=anchor.hub()',
        "!CreativeTabletAccess.priorHubBinding(player,tablet,hub)", "if(!valid())")
ordered(body(pocket_view,"boolean sameLocalVisit("), "lease.kind()==PocketLeases.Kind.VISIT", "id.equals(lease.pocket())",
        "lease.deadline()==visitDeadline", "lease.epoch()==epoch", "anchor.equals(room.anchor())", "player.getItemInHand(hand)==tablet",
        "tablet.get(DataComponents.CUSTOM_DATA)==binding", "StorageItem.permittedTablet(player,tablet)")
assert "new Pending(p,r.id(),kind,tablet)" in body(pockets, "void start(")
ordered(body(pockets, "void arrive("), "authorize(p,pocket,pending.kind(),true,0)",
        "if(!denied.isEmpty())", "pending.kind()==PocketLeases.Kind.REMOTE", "StoreMenu.pocketRemote",
        "pending.tablet()")
ordered(body(pockets, "void openChest("), "if(!physicalAllowed(p,chest))", "StoreMenu.pocketChest")
ordered(body(menu, "void openPocket("), "if(!view.valid())return;", "view.permits(BasePermissions.VIEW)", "p.openMenu")
direct_hub=body(menu, "void network(")
assert "Network.hubReason(p,hub)" in direct_hub and "Page.BASE" in direct_hub and "openNetwork(" not in direct_hub
ordered(body(menu,"void remote("),"heldNetworkTablet(p,hub)==null","openNetwork(p,hub,Opening.TABLET)")
ordered(body(menu,"void creativeNetwork("),"CreativeTabletAccess.session(p)","session.matchesHub(p,hub)","Opening.CREATIVE")
ordered(body(menu,"void pocketRemote("),"CreativeTabletAccess.pocketSession(p,view.id())","Opening.CREATIVE","Opening.TABLET")
ordered(body(menu,"void visitTablet("),"view.localTablet()","openPocket(p,view,creativeTablet?Opening.CREATIVE:Opening.TABLET)")
assert "Opening.CHEST" in body(menu, "void pocketChest(")
assert "Opening.BACKPACK,!upgrades&&bag.valid()" in body(menu, "void bag(")
ordered(body(menu, "void container("), "Safety.reason(p,chest,null).isEmpty()", "Opening.CHEST")

decision = body(menu, "int openingFlags(")
ordered(decision, "if (!mayView", "case TABLET", "REMOTE_OPEN | TABLET_RULE",
        "policy.rollTabletOpening(ThreadLocalRandom.current()) ? TABLET_INTRO : 0",
        "case CHEST -> policy.rollChestCameo")
assert "case TABLET, CREATIVE -> REMOTE_OPEN | TABLET_RULE" in decision
assert "opening == Opening.CREATIVE ? CREATIVE_ACCESS : 0" in decision
assert "case BACKPACK -> policy.backpackCameoEnabled" in decision and "case PLAIN -> 0" in decision
storage = body(menu, "void storage()")
assert "mayView?openingFlags:openingFlags&CREATIVE_ACCESS" in storage
ordered(storage,"pocket.localTablet()", "RoomEditor.active(owner)", "RoomEditor.modeDenial(owner).isEmpty()", "roomFlags|=EDIT_ALLOWED")
ordered(storage, "pocket.permits(BasePermissions.VIEW)", "containers=!mayView?List.of()",
        "pocket.containers()", "for(Container container:containers)", "capacity++",
        "synchronizedInt(6,capacity)", "if (pocket != null) pocketControls(mayView, containers.size())")
ordered(body(menu, "void pocketControls("), "if(pocket.localTablet())", "ControlGlyph.EXIT", "PocketService.recover(owner)",
        "return;", "mayView && (openingFlags & REMOTE_OPEN) != 0",
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
        "if (tabletCoversView)", "tabletIntro.choose(tabletVisits)",
        "!menu.privateView() && !menu.remoteOpening() && menu.showsStorageCameo()")
frame = body(client, "boolean showTabletFrame(")
assert all(flag in frame for flag in ("!menu.privateView()", "menu.remoteOpening()", "menu.hasTabletOpeningRule()"))
ordered(body(client,"boolean updateTabletCover("), "classifyOpening()", "tabletCoversView && !showTabletFrame()",
        "tabletIntro.dismiss()", "tabletCoversView = false", "return tabletCoversView")
observe = body(held_tablet, "Held observe(")
assert observe.count("StorageItem.isTablet(stack)") == 2 and "Content.REMOTE.get()" not in observe
assert "return !activeScreen() || updateTabletCover();" in body(client, "boolean blockTabletInput(")
ordered(body(client,"boolean drawTabletIntro("), "if (!updateTabletCover()) return false;", "tabletIntro.coversInput()",
        "tabletBoot.draw(graphics, width, height, tabletIntro, openingPlayer, now, menu.creativeTabletOpening())")
ordered(body(client, "void render(GuiGraphics g"), "if (showTabletFrame())", "tabletBoot.drawSettled(g,width,height,openingPlayer,menu.creativeTabletOpening())",
        "Inventory: deposit. Wheel: scroll.", "drawStorageCameo(g)", "g.pose().popPose()")

print("PASS: Cupid Tablet requires a nearby bound hub. UwU Tablet requires a signed binding and an eligible holder.")
print("PASS: authorized tablets use the configured intro chance; private storage suppresses the intro")
print("PASS: chest and backpack settings default off, use separate branches, and never gate input")
print("PASS: authorized tablet pocket storage shows the pad summary and gated Room upgrades link")
print("PASS: a matching local tablet reuses the physical Visit, checks its binding and offers the exit")
print("PASS: pocket authorization reads the effective server policy without the old JVM gate")
print("PASS: operator settings and pocket commands use the shared revision-checked policy API")
