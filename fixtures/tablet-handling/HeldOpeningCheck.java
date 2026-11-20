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

    private static final int[] OPEN_START = {0,260,310,390,440,610,660,760,810};
    private static final int[] IDLE_START = {0,85,175,275,385,635,760,885,1010};
    private static final int[] CLASSIC_ROWS = {0,1,2,1,0,1,2,1,0};

    private static int selected(GuiGraphics first) {
        if (first.row == 0 && first.expressionColumn < 0) return 7;
        check(first.row < 0 && first.expressionColumn == 0 && first.expressionRow >= 0 && first.expressionRow < 7,
                "First paint was not an approved opening cell");
        return first.expressionRow;
    }

    private static int[] starts(int sequence) { return sequence >= 4 && sequence < 7 ? IDLE_START : OPEN_START; }

    private static void verify(GuiGraphics paint, int sequence, int age) {
        int[] times = starts(sequence);
        int column = 0;
        while (column+1 < times.length && times[column+1] <= age) column++;
        if (sequence == 7) check(paint.row==CLASSIC_ROWS[column] && paint.expressionColumn<0,"Classic blink lost beat "+column);
        else check(paint.row<0 && paint.expressionColumn==column && paint.expressionRow==sequence,
                "Wrong Twin Tails expression row or column at "+age);
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
        check(first.armPose == null && selected(first) >= 0,
                "An expired sample or changed hotbar slot must drop the grip and still open authorized storage");
    }

    private static void handoff(int elapsed, boolean right, int id, boolean creative) {
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
        owner.getItemInHand(hand).creative = creative;
        var portrait = id == 1 || creative ? TabletDevice.physicalFace(TabletIdle.Face.GLANCE, creative) : null;
        TabletDevice.rememberHeld(new TabletDevice.Held(grip, shown, WIDTH, HEIGHT, owner,
                owner.getItemInHand(hand), hand, owner.getInventory().selected, lastPaint, portrait));
        int actions = PacketDistributor.sent.size();
        var menu = new StoreMenu(id);
        menu.creativeTablet = creative;
        var screen = open(menu, owner);
        long firstMenuPaint = lastPaint + 23;
        var first = paint(screen, firstMenuPaint);
        same(withMotion(grip, shown, TabletHandlingPose.deviceMatrix(WIDTH, HEIGHT, right, grip.swing(), grip.equip(), shown)),
                first.casePose, "First menu paint must carry the captured case and motion");
        same(withMotion(grip, shown, TabletHandlingPose.armMatrix(WIDTH, HEIGHT, right, false, grip.swing(), grip.equip(), shown)),
                first.armPose, "The same hand position and motion must cross the menu boundary");
        var next = paint(screen, firstMenuPaint + 22);
        int sequence = selected(first);
        float continued = Math.min(1, shown + 22f / TabletLift.RISE_MS);
        same(withMotion(grip, continued,
                TabletHandlingPose.deviceMatrix(WIDTH, HEIGHT, right, grip.swing(), grip.equip(), continued)),
                next.casePose, "Continue the existing raise without another anticipation delay");
        verify(first,sequence,0);
        verify(next,sequence,22);
        var settled = paint(screen, firstMenuPaint + TabletLift.RISE_MS);
        verify(settled,sequence,TabletLift.RISE_MS);
        check(settled.pinkShell == creative && settled.atlasDraws == 0,"Lifting the shell changed its color");
        int[] starts = starts(sequence);
        var client = Minecraft.getInstance();
        int lookups = client.artLookups;
        for (int i = 1; i < starts.length; i++) {
            var previous = paint(screen, firstMenuPaint + starts[i] - 1);
            verify(previous,sequence,starts[i] - 1);
            var beat = paint(screen, firstMenuPaint + starts[i]);
            verify(beat,sequence,starts[i]);
        }
        var last = paint(screen, firstMenuPaint + 1049);
        verify(last,sequence,1049);
        check(!last.events.contains("inventory"), "Last face or reveal time");
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

        check(screen.keyPressed(256, 0, 0), "Escape closes the inventory at once");
        var closing = TabletDevice.takeClosing();
        check(closing != null && closing.progress() == 1 && closing.grip() == grip
                && closing.portrait() == portrait && closing.stillHeld(owner),
                "Escape from slots must hand the same physical tablet and held face back at the centered pose");
        screen.onClose();
        check(TabletDevice.takeClosing() == null, "A second close replayed the tablet");
        var nextRemote = paint(open(id + 10, owner), firstMenuPaint + 1100);
        check(nextRemote.armPose == null && selected(nextRemote) != sequence,
                "An authorized Cupid Tablet without a matching grip still uses Twin Tails and never replays the arm");
    }

    private static void escapeDuringIntro(int age, boolean changedSlot) {
        var owner = new LocalPlayer();
        var hand = InteractionHand.OFF_HAND;
        var grip = new TabletDevice.Grip(false, .1f, .02f, 0xb000d0);
        var face = TabletDevice.physicalFace(TabletIdle.Face.GLANCE, false);
        TabletDevice.rememberHeld(new TabletDevice.Held(grip, .23f, WIDTH, HEIGHT, owner, owner.getItemInHand(hand),
                hand, owner.getInventory().selected, 40_000, face));
        var screen = open(40, owner);
        // Vanilla's initial ClientboundContainerSetContent replaces even an unchanged held stack.
        owner.off = new net.minecraft.world.item.ItemStack();
        int sequence = selected(paint(screen, 40_010));
        var frame = paint(screen, 40_010 + age);
        if (changedSlot) owner.getInventory().selected++;
        int before = PacketDistributor.sent.size();
        check(screen.keyPressed(256, 0, 0) && PacketDistributor.sent.size() == before, "Escape sent storage input");
        var closing = TabletDevice.takeClosing();
        if (changedSlot) {
            check(closing == null, "A replacement stack inherited the old handback");
            return;
        }
        float expected = Math.min(1f, .23f + age / (float) TabletLift.RISE_MS);
        check(closing != null && closing.progress() == expected && closing.grip() == grip && closing.stack() == owner.off,
                "Mid-intro Escape jumped or failed to adopt the synced held slot");
        same(frame.casePose, withMotion(grip, expected, TabletHandlingPose.deviceMatrix(WIDTH, HEIGHT,
                false, grip.swing(), grip.equip(), expected)), "Handback skipped the last painted matrix");
        verify(frame,sequence,age);
        TabletIdle.Face actual = TabletDevice.faceOf(closing.portrait());
        TabletIdle.Face expectedFace = sequence < 7 ? TabletIdle.Face.expression(sequence,frame.expressionColumn)
                : switch (frame.row) { case 0 -> TabletIdle.Face.OPEN; case 1 -> TabletIdle.Face.SQUINT; default -> TabletIdle.Face.SHUT; };
        check(actual == expectedFace, "Closing partway through the visit lost the displayed expression");
    }

    private static void changedBindingCannotCarry() {
        var owner = new LocalPlayer();
        var grip = new TabletDevice.Grip(false, 0, 0, 0xb000d0);
        TabletDevice.rememberHeld(new TabletDevice.Held(grip, .5f, WIDTH, HEIGHT, owner, owner.off,
                InteractionHand.OFF_HAND, owner.getInventory().selected, 41_000));
        var screen = open(41, owner);
        owner.off = new net.minecraft.world.item.ItemStack();
        owner.off.components = "other hub";
        var frame = paint(screen, 41_010);
        check(frame.armPose == null && selected(frame) >= 0, "A changed binding inherited the other tablet's pose");
    }

    private static void allPortraits() {
        var client = Minecraft.getInstance();
        for (var face : TabletIdle.Face.values()) {
            var nativeFace = TabletDevice.physicalFace(face, false);
            check(nativeFace.image().getPath().equals("item/cupid_tablet_face_" + face.sprite)
                    && TabletDevice.faceOf(nativeFace) == face, "The wrong baked close-up was selected");
        }
        client.missingItemFace = true;
        try {
            var graphics = new GuiGraphics();
            TabletDevice.draw(graphics, new TabletDevice.Bounds(0, 0, 1), TabletDevice.physicalFace(TabletIdle.Face.SHUT, false), false, false);
            check(graphics.row == 2 && graphics.atlasDraws == 0, "Missing baked sprite must retain the closed eyes");
            for(int row=0;row<7;row++)for(int col=0;col<9;col++) {
                var face=TabletIdle.Face.expression(row,col);
                var paint=new GuiGraphics();
                TabletDevice.draw(paint,new TabletDevice.Bounds(0,0,1),TabletDevice.physicalFace(face,true),false,true);
                check(paint.pinkShell && paint.expressionRow==row && paint.expressionColumn==col && paint.atlasDraws==0,
                        "Missing item texture must preserve the exact Twin Tails pose");
            }
        } finally {
            client.missingItemFace = false;
        }
        var creative = new StoreMenu(53);
        creative.creativeTablet = true;
        var player = new LocalPlayer();
        var creativeScreen = open(creative, player);
        var opening = paint(creativeScreen, 55_000);
        check(opening.pinkShell && selected(opening)>=0, "Creative opening lost its pink shell without a held pose");
        check(paint(creativeScreen, 56_050).pinkShell, "Settled Creative grid must keep the pink shell");
        var miss = new StoreMenu(54);
        miss.creativeTablet = true;
        miss.openingHit = false;
        var quiet = paint(open(miss, player), 57_000);
        check(quiet.pinkShell && quiet.row < 0 && quiet.expressionColumn < 0 && quiet.events.contains("inventory"), "Creative chance miss must keep its shell and immediately expose slots");
        var owner = new LocalPlayer();
        int last=-1;
        for(int cycle=0;cycle<2;cycle++) {
            var seen=new java.util.HashSet<Integer>();
            for(int i=0;i<8;i++) {
                var menu=new StoreMenu(100+cycle*8+i);
                menu.creativeTablet=(i%2)==1;
                var screen=open(menu,owner);
                long start=100_000L + 2_000L*(cycle*8+i);
                int sequence=selected(paint(screen,start));
                check(seen.add(sequence) && sequence!=last,"Both tablet colors must share one shuffled cycle");
                last=sequence;
                for(int beat:starts(sequence)) verify(paint(screen,start+beat),sequence,beat);
                verify(paint(screen,start+1049),sequence,1049);
                check(paint(screen,start+1050).events.contains("inventory"),"Opening extended the existing delay");
                var skip=new StoreMenu(200+cycle*8+i); skip.openingHit=false;
                expectImmediate(open(skip,owner),skip,start+1060,true);
            }
        }
        var reopened = open(21, owner);
        int sequence=selected(paint(reopened, 62_000));
        reopened.init(client, 680, 380);
        var resized = paint(reopened, 62_445);
        verify(resized,sequence,445);
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
        selected(paint(pending, 67_100));
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
        int sequence=selected(paint(hit, 80_000));
        verify(paint(hit,80_310),sequence,310);
        verify(paint(hit,81_049),sequence,1049);
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
                owner.getItemInHand(hand), hand, owner.getInventory().selected, 81_990, TabletDevice.physicalFace(TabletIdle.Face.GLANCE, false)));
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
        check(fresh.armPose == null && selected(fresh)>=0, "Reopened hit inherited an unpainted miss's grip");
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
                check(selected(authorized)>=0 && authorized.armPose == null, "A direct menu replayed a prior held pose");
            }
        }
        var denied = new StoreMenu(46);
        denied.privateStorage = true;
        expectImmediate(open(denied, owner), denied, 90_110, false);
    }

    public static void main(String[] args) {
        System.setProperty("anyastorage.tabletIntro", "true");
        Util.timeSource = () -> clock * 1_000_000;
        handoff(40, true, 1, false);          // Authorization while raising.
        handoff(210, false, 2, false);        // Authorization during bounded hold.
        handoff(343, true, 3, false);         // Authorization after the declined-use return started.
        handoff(40, false, 6, true);   // A Creative offhand must carry its actual rose case into the menu.
        staleGrip(false, 4);
        staleGrip(true, 5);
        allPortraits();
        serverChance();
        nonRemoteCannotClaimHeldVisuals();
        escapeDuringIntro(30, false);
        escapeDuringIntro(325, false);
        escapeDuringIntro(30, true);
        changedBindingCannotCarry();
        System.out.println("PASS: Escape carries the last partial lift, mid-blink portrait and settled case; swapped items discard it");
        System.out.println("PASS: server hit works with default JVM arguments; heart Remote, miss and opt-out reveal "
                + "immediately, first ready slot dispatches exactly once, and resize/reopening cannot replay the decision");
        System.out.println("PASS: all seven original nine-frame head sequences and the classic blink across two shared shuffle cycles, "
                + "first beat during lift, matching Escape handback, immediate direct inventories, and slots at 1050 ms");
    }
}
