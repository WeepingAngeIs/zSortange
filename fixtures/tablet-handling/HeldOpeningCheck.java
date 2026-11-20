package dev.anya.storage.appearance;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.anya.storage.Client;
import dev.anya.storage.Packets;
import dev.anya.storage.StoreMenu;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Matrix4f;

/** Paints the actual storage screen using a simulated menu, player, clock and graphics boundary. */
public final class HeldOpeningCheck {
    private static final int WIDTH = 480, HEIGHT = 312;
    private static long clock;

    private static void check(boolean ok, String reason) {
        if (!ok) throw new AssertionError(reason);
    }

    private static Client.Screen open(int id, LocalPlayer owner) {
        return open(new StoreMenu(id), owner);
    }

    private static Client.Screen open(StoreMenu menu, LocalPlayer owner) {
        var client = Minecraft.getInstance();
        owner.containerMenu = menu;
        client.player = owner;
        var screen = new Client.Screen(menu, owner.getInventory(), Component.literal("Fixture storage"));
        client.screen = screen;
        screen.init(client, WIDTH, HEIGHT);
        return screen;
    }

    private static GuiGraphics paint(Client.Screen screen, long now) {
        clock = now;
        var graphics = new GuiGraphics();
        try {
            screen.render(graphics, -1, -1, 0);
        } catch (GuiGraphics.StoragePaint firstStorageDraw) {
            // The paint recorder ends at the inventory panel, before native text/slot rendering.
        }
        return graphics;
    }

    private static void same(Matrix4f expected, Matrix4f actual, String reason) {
        check(actual != null && expected.equals(actual, 0.001f), reason);
    }

    private static Matrix4f withMotion(TabletDevice.Grip grip, float progress, Matrix4f local) {
        var pose = new PoseStack();
        grip.motion().apply(pose, WIDTH, HEIGHT, progress);
        return new Matrix4f(pose.last().pose()).mul(local);
    }

    private static void staleGrip(boolean swapSlot, int id) {
        var owner = new LocalPlayer();
        var hand = InteractionHand.MAIN_HAND;
        long lastPaint = 10000L * id;
        var grip = new TabletDevice.Grip(true, 0, 0, 0xb000d0);
        TabletDevice.rememberHeld(new TabletDevice.Held(grip, 0.42f, WIDTH, HEIGHT, owner,
                owner.getItemInHand(hand), hand, owner.getInventory().selected, lastPaint));
        if (swapSlot) owner.getInventory().selected++;
        var first = paint(open(id, owner), lastPaint + (swapSlot ? 15 : 401));
        check(first.armPose == null && first.row == 0,
                "An expired sample or changed hotbar slot must drop the grip and still open authorized storage");
    }

    private static void handoff(int elapsed, boolean right, int id) {
        LocalPlayer owner = new LocalPlayer();
        InteractionHand hand = right ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        var grip = new TabletDevice.Grip(right, 0.25f, 0.07f, 0xb000d0,
                new TabletHandMotion(2, -1, -.05f, -.08f, .4f));
        var lift = new TabletLift();
        long start = 10000L * id;
        lift.use(start);
        long lastPaint = start + elapsed;
        float shown = lift.progress(lastPaint);
        check(shown > 0 && shown < 1, "Sample must be an actual partial raise");
        var portrait = id == 1 ? TabletDevice.visit(4, 5) : null;
        TabletDevice.rememberHeld(new TabletDevice.Held(grip, shown, WIDTH, HEIGHT, owner,
                owner.getItemInHand(hand), hand, owner.getInventory().selected, lastPaint, portrait));
        int actions = PacketDistributor.sent.size();
        var screen = open(id, owner);
        long firstMenuPaint = lastPaint + 23;
        var first = paint(screen, firstMenuPaint);
        same(withMotion(grip, shown, TabletHandlingPose.deviceMatrix(WIDTH, HEIGHT, right, grip.swing(), grip.equip(), shown)),
                first.casePose, "First menu paint must carry the captured case and motion");
        same(withMotion(grip, shown, TabletHandlingPose.armMatrix(WIDTH, HEIGHT, right, false, grip.swing(), grip.equip(), shown)),
                first.armPose, "The same hand position and motion must cross the menu boundary");
        var next = paint(screen, firstMenuPaint + 22);
        float continued = Math.min(1, shown + 22f / TabletLift.RISE_MS);
        same(withMotion(grip, continued,
                TabletHandlingPose.deviceMatrix(WIDTH, HEIGHT, right, grip.swing(), grip.equip(), continued)),
                next.casePose, "Continue the existing raise without another anticipation delay");
        for (var frame : new GuiGraphics[]{first, next}) {
            check(frame.row < 0, "The blink replaced the carried face before the tablet settled");
            if (portrait != null) {
                check(frame.expressionRow == 4 && frame.expressionColumn == 5 && frame.atlasDraws == 0,
                        "The raise dropped the expression that was already visible");
            } else {
                check(frame.atlasDraws == 1 && frame.atlasSprite.toString().equals("anyastorage:item/cupid_tablet_face"),
                        "The raise should keep using the native item's shared animation sprite");
            }
        }
        var settled = paint(screen, firstMenuPaint + TabletLift.RISE_MS);
        check(settled.row == 0 && settled.atlasDraws == 0 && settled.expressionColumn < 0,
                "The authorized opening should switch to Twin Tails once the raise settles");
        int[] starts = {0,260,310,390,440,610,660,760,810};
        int[] blinkRows = {0,1,2,1,0,1,2,1,0};
        var client = Minecraft.getInstance();
        int lookups = client.artLookups;
        for (int i = 1; i < starts.length; i++) {
            var previous = paint(screen, firstMenuPaint + starts[i] - 1);
            check(previous.row == blinkRows[i - 1], "Prior blink beat ended early");
            var beat = paint(screen, firstMenuPaint + starts[i]);
            check(beat.row == blinkRows[i] && beat.expressionColumn < 0,
                    "The Twin Tails double blink skipped its next beat");
        }
        var last = paint(screen, firstMenuPaint + 1049);
        check(last.row == 0 && !last.events.contains("inventory"), "Last face or reveal time");
        check(client.artLookups == lookups, "The render loop kept looking up the texture");
        var revealed = paint(screen, firstMenuPaint + 1050);
        check(revealed.events.indexOf("arm") == 0 && revealed.events.indexOf("case") > 0
                && revealed.events.indexOf("inventory") > revealed.events.lastIndexOf("case"),
                "Settled arm and case must be drawn before the inventory");
        same(TabletHandlingPose.deviceMatrix(WIDTH, HEIGHT, right, grip.swing(), grip.equip(), 1),
                revealed.casePose, "Final same-size menu device");
        check(PacketDistributor.sent.size() == actions, "Opening visuals sent an action");
        screen.mouseClicked(revealed.storageX + 109 * revealed.storageScale, revealed.storageY + 56 * revealed.storageScale, 0);
        check(PacketDistributor.sent.size() == actions + 1, "Expected one immediately usable existing slot");
        var routed = (Packets.Action) PacketDistributor.sent.get(actions);
        check(routed.verb().equals("slot") && routed.value() == 0 && routed.menu() == id,
                "Existing inventory slot and menu identity must be preserved");

        var nextRemote = paint(open(id + 10, owner), firstMenuPaint + 1100);
        check(nextRemote.armPose == null && nextRemote.expressionColumn < 0 && nextRemote.row == 0,
                "An authorized Cupid Tablet without a matching grip still uses Twin Tails and never replays the arm");
    }

    private static void allPortraits() {
        var clock = new GuardianBlink(true);
        int[] starts = {0,260,310,390,440,610,660,760,810};
        for (int i = 0; i < starts.length; i++) {
            clock.present(starts[i], true, true);
            for (int row = 0; row < 7; row++) {
                var tile = row < 4 ? TabletDevice.opening(row, clock) : TabletDevice.visit(row, i);
                check(tile.image().getPath().endsWith("/tablet_expressions.png") && tile.atlasWidth() == 2016
                        && tile.atlasHeight() == 896 && tile.u() == 224*i && tile.v() == 128*row,
                        "Unexpected source region in the expression sheet");
            }
            var tumble = TabletDevice.visit(7, i);
            check(tumble.image().getPath().endsWith("/tablet_tumble.png") && tumble.atlasWidth() == 2016
                    && tumble.atlasHeight() == 128 && tumble.u() == 224*i && tumble.v() == 0,
                    "Wrong tumble frame");
        }
        check(clock.present(1050, true, true) == GuardianBlink.Frame.DONE && !clock.coversInput()
                && TabletDevice.opening(0, clock) == null, "The clock or portrait extended past 1050 ms");
        int previous = -1;
        for (int i = 0; i < 24; i++) {
            int current = TabletDevice.chooseVisit(1);
            check(current != previous && current >= 4 && current < 7, "Idle choices repeated or used an opening");
            previous = current;
        }
        var client = Minecraft.getInstance();
        for (int roll = 4; roll < 8; roll++) {
            int row = TabletDevice.chooseVisit(roll);
            check(row == -1 && TabletDevice.visit(row, 0).image().getPath().endsWith("/tablet_gaze_left.png"),
                    "The classic visit should grant the Twin Tails left glance with the Cloud sheet installed");
        }
        check(TabletDevice.chooseVisit(0) == 7 && TabletDevice.chooseVisit(0) < 7,
                "Rare tumble should play once and never repeat immediately");
        client.tumbleArt = false;
        check(TabletDevice.chooseVisit(0) < 7, "Missing tumble sheet must use a regular visit");
        client.tumbleArt = true;
        client.expressionArt = false;
        check(TabletDevice.chooseVisit(0) == -1, "Missing both body sheets must use the classic glance");
        var owner = new LocalPlayer();
        var fallback = open(20, owner);
        check(paint(fallback, 60_000).row == 0 && paint(fallback, 60_310).row == 2, "Twin Tails without Cloud art");
        client.expressionArt = true;
        var reopened = open(21, owner);
        var face = paint(reopened, 62_000);
        check(face.row == 0 && face.expressionColumn < 0, "Cloud art displaced the approved opening portrait");
        reopened.init(client, 680, 380);
        var resized = paint(reopened, 62_445);
        check(resized.row == 0 && resized.expressionColumn < 0,
                "Resize replayed the old appearance or restarted the gesture");
        var pending = open(22, owner);
        StoreMenu menu = (StoreMenu)owner.containerMenu;
        menu.storage = false;
        menu.version = 0;
        int before = client.artLookups;
        var waiting = paint(pending, 67_000);
        check(waiting.events.contains("inventory") && waiting.row < 0 && waiting.expressionColumn < 0
                && !waiting.events.contains("case") && client.artLookups == before,
                "Unconfirmed storage picked or displayed a face");
        menu.storage = true;
        menu.version = 1;
        check(paint(pending, 67_100).row == 0, "Confirmed Cupid Tablet hit must open after synchronization");
    }

    private static void expectImmediate(Client.Screen screen, StoreMenu menu, long now, boolean framed) {
        int lookups = Minecraft.getInstance().artLookups;
        var visible = paint(screen, now);
        check(visible.events.contains("inventory") && visible.expressionColumn < 0 && visible.row < 0
                && visible.events.contains("case") == framed, "Expected immediately visible storage with its correct frame");
        int count = PacketDistributor.sent.size();
        screen.mouseClicked(visible.storageX + 109 * visible.storageScale, visible.storageY + 56 * visible.storageScale, 0);
        check(PacketDistributor.sent.size() == count + 1, "First ready frame swallowed a storage click");
        var action = (Packets.Action)PacketDistributor.sent.get(count);
        check(action.verb().equals("slot") && action.value() == 0 && action.menu() == menu.containerId
                && action.revision() == menu.revision() && action.session() == menu.session(), "Miss misrouted the first click");
        check(Minecraft.getInstance().artLookups == lookups, "A skipped opening picked a portrait");
    }

    private static void serverChance() {
        var owner = new LocalPlayer();
        var mc = Minecraft.getInstance();
        System.clearProperty("anyastorage.tabletIntro");
        var hit = open(30, owner);
        check(paint(hit, 80_000).row == 0 && paint(hit, 80_310).row == 2 && paint(hit, 81_049).row == 0,
                "Server hit must work without a JVM option and use the original clock");
        var ready = paint(hit, 81_050);
        check(ready.events.contains("inventory"), "Hit extended the existing reveal time");

        // The same storage flag word that identifies the page also contains the decision.
        var missed = new StoreMenu(31);
        missed.openingHit = false;
        missed.storage = false;
        missed.rows = 1;
        missed.version = 0;
        var heldGrip = new TabletDevice.Grip(true, 0, 0, 0xb000d0);
        var hand = InteractionHand.MAIN_HAND;
        TabletDevice.rememberHeld(new TabletDevice.Held(heldGrip, .42f, WIDTH, HEIGHT, owner,
                owner.getItemInHand(hand), hand, owner.getInventory().selected, 81_990, TabletDevice.visit(4, 5)));
        var screen = open(missed, owner);
        var pending = paint(screen, 82_000);
        check(pending.events.contains("inventory") && pending.row < 0 && pending.expressionColumn < 0,
                "Pending Cupid Tablet miss covered the inventory");
        missed.storage = true;
        missed.version = 1;
        missed.rows = 5;
        var layout = TabletDevice.fitMenu(dev.anya.storage.ui.StorageGrid.layout(WIDTH, HEIGHT, missed.resultCount(), true),
                WIDTH, HEIGHT);
        int beforeFirstPaint = PacketDistributor.sent.size();
        screen.mouseClicked(layout.left() + 109 * layout.scale(), layout.top() + 56 * layout.scale(), 0);
        check(PacketDistributor.sent.size() == beforeFirstPaint + 1, "Miss blocked input between readiness and paint");
        expectImmediate(screen, missed, 82_001, true);
        screen.init(mc, 650, 350);
        missed.openingHit = true;
        expectImmediate(screen, missed, 82_002, true); // Update or resize cannot start it late.

        System.setProperty("anyastorage.tabletIntro", "true");
        var oldServer = new StoreMenu(32);
        oldServer.remote = false; // Legacy server still supplies the old shared flags.
        expectImmediate(open(oldServer, owner), oldServer, 82_100, false);
        var heartRemote = new StoreMenu(37);
        heartRemote.openingRule = false;
        expectImmediate(open(heartRemote, owner), heartRemote, 82_150, false);
        var missEvenWhenOptedIn = new StoreMenu(33);
        missEvenWhenOptedIn.openingHit = false;
        expectImmediate(open(missEvenWhenOptedIn, owner), missEvenWhenOptedIn, 82_200, true);
        System.setProperty("anyastorage.tabletIntro", "false");
        var optOut = new StoreMenu(34);
        expectImmediate(open(optOut, owner), optOut, 82_300, false);

        System.clearProperty("anyastorage.tabletIntro");
        var settings = new StoreMenu(35);
        settings.storage = false;
        settings.remote = false;
        settings.openingRule = false;
        var settingsScreen = open(settings, owner);
        var preferences = paint(settingsScreen, 82_400);
        check(!preferences.events.contains("case") && preferences.events.contains("inventory"), "Settings opened an intro");
        settings.storage = settings.openingRule = true;
        expectImmediate(settingsScreen, settings, 82_410, false);

        var grip = new TabletDevice.Grip(true, 0, 0, 0xb000d0);
        TabletDevice.rememberHeld(new TabletDevice.Held(grip, .42f, WIDTH, HEIGHT, owner,
                owner.getMainHandItem(), InteractionHand.MAIN_HAND, owner.getInventory().selected, 83_000));
        var earlyClose = new StoreMenu(36);
        earlyClose.openingHit = false;
        open(earlyClose, owner); // Superseded before the first paint.
        var fresh = paint(open(36, owner), 83_020);
        check(fresh.armPose == null && fresh.row == 0, "Reopened hit inherited an unpainted miss's grip");
    }

    private static void nonRemoteCannotClaimHeldVisuals() {
        var owner = new LocalPlayer();
        var grip = new TabletDevice.Grip(true, 0, 0, 0xb000d0);
        for (boolean backpack : new boolean[]{false, true}) {
            for (boolean cameo : new boolean[]{false, true}) {
                var direct = new StoreMenu(44);
                direct.remote = direct.openingRule = direct.openingHit = false;
                direct.backpack = backpack;
                direct.cameo = cameo;
                TabletDevice.rememberHeld(new TabletDevice.Held(grip, .42f, WIDTH, HEIGHT, owner,
                        owner.getMainHandItem(), InteractionHand.MAIN_HAND, owner.getInventory().selected, 90_000));
                expectImmediate(open(direct, owner), direct, 90_010, false);
                var authorized = paint(open(45, owner), 90_020);
                check(authorized.row == 0 && authorized.armPose == null, "A direct menu replayed a prior held pose");
            }
        }
        var denied = new StoreMenu(46);
        denied.privateStorage = true;
        expectImmediate(open(denied, owner), denied, 90_110, false);
    }

    public static void main(String[] args) {
        System.setProperty("anyastorage.tabletIntro", "true");
        Util.timeSource = () -> clock * 1_000_000;
        handoff(40, true, 1);          // Authorization while raising.
        handoff(210, false, 2);        // Authorization during bounded hold.
        handoff(343, true, 3);         // Authorization after the declined-use return started.
        staleGrip(false, 4);
        staleGrip(true, 5);
        allPortraits();
        serverChance();
        nonRemoteCannotClaimHeldVisuals();
        System.out.println("PASS: server hit works with default JVM arguments; heart Remote, miss and opt-out reveal "
                + "immediately, first ready slot dispatches exactly once, and resize/reopening cannot replay the decision");
        System.out.println("PASS: Twin Tails double blink and left glance, Cloud body tiles and tumble, "
                + "first actionable slot at 1050 ms, continuous lift, immediate direct inventories, private and legacy isolation");
    }
}
