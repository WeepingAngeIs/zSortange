import dev.anya.storage.Client;
import dev.anya.storage.ExpansionPackets;
import dev.anya.storage.Packets;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.appearance.ClientInteriors;
import dev.anya.storage.appearance.TabletDevice;
import dev.anya.storage.ui.StorageGrid;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Exercises the compiled production screen; mock desktop types are supplied only by the fixture. */
public final class ScreenDispatchCheck {
    private static long millis;
    private static final int WIDTH = 480, HEIGHT = 320;
    private static final int STALE = -3, STORAGE = -2;
    private static final int OPEN = 0, HALF = 1, CLOSED = 2;

    private final Minecraft mc;
    private final StoreMenu menu;
    private final Client.Screen screen;
    private final boolean tablet;
    private GuiGraphics lastMenuPaint;
    private GuiGraphics latestPaint;

    private ScreenDispatchCheck(int id, boolean enabled) {
        this(new Minecraft(), id, enabled);
    }

    private ScreenDispatchCheck(Minecraft mc, int id, boolean enabled) {
        this.mc = mc;
        tablet = enabled;
        System.setProperty("anyastorage.tabletIntro", Boolean.toString(enabled));
        menu = new StoreMenu(id);
        if (mc.player == null) mc.player = new LocalPlayer();
        mc.player.containerMenu = menu;
        screen = new Client.Screen(menu, new Inventory(mc.player), Component.literal("Offline storage"));
        mc.screen = screen;
        screen.init(mc, WIDTH, HEIGHT);
    }

    private int paint(long time) {
        millis = time;
        var graphics = new GuiGraphics();
        latestPaint = graphics;
        try {
            screen.render(graphics, -1, -1, 0f);
        } catch (GuiGraphics.StoragePaint reached) {
            lastMenuPaint = graphics;
            return STORAGE;
        }
        return graphics.fills == 0 && graphics.blits == 0 ? STALE : graphics.row;
    }

    private void click(int logicalX, int logicalY) {
        if (lastMenuPaint != null) {
            // Use the transform that actually reached the first storage panel drawing call.
            screen.mouseClicked(lastMenuPaint.storageX + logicalX * lastMenuPaint.storageScale,
                    lastMenuPaint.storageY + logicalY * lastMenuPaint.storageScale, 0);
            return;
        }
        var layout = StorageGrid.layout(WIDTH, HEIGHT, menu.resultCount(), true);
        if (tablet && menu.remoteOpening() && !menu.privateView()) {
            layout = TabletDevice.fitMenu(layout, WIDTH, HEIGHT);
        }
        screen.mouseClicked(layout.left()+logicalX*layout.scale(), layout.top()+logicalY*layout.scale(), 0);
    }

    private void clickSlot() { click(109, 56); }

    private void requireSlotSent(String caseName) {
        int before = PacketDistributor.sent.size();
        clickSlot();
        require(PacketDistributor.sent.size() == before + 1, caseName + ": one immediate action");
        var action = (Packets.Action) PacketDistributor.sent.get(before);
        require(action.menu() == menu.containerId && action.verb().equals("slot") && action.value() == 0
                && action.revision() == menu.revision() && action.session() == menu.session(),
                caseName + ": slot zero routed to existing menu");
    }

    private static void require(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private void expectBlocked(String scenario) {
        int packets = PacketDistributor.sent.size(), typed = EditBox.typed, drops = ClientInteriors.drops;
        clickSlot();
        click(109, 32);
        screen.charTyped('z', 0);
        screen.keyPressed(257, 0, 0);
        screen.mouseScrolled(100, 80, 0, 1);
        screen.mouseDragged(100, 80, 0, 0, 5);
        screen.mouseReleased(100, 80, 0);
        screen.onFilesDrop(List.of(Path.of("never-read.png")));
        require(PacketDistributor.sent.size() == packets && EditBox.typed == typed && ClientInteriors.drops == drops,
                scenario + ": an event reached a hidden control");
    }

    private static void defaultAndReveal() {
        var off = new ScreenDispatchCheck(1, false);
        require(off.paint(0) == STORAGE, "Explicit opt-out skips intro");
        off.requireSlotSent("Explicit opt-out");
        var on = new ScreenDispatchCheck(2, true);
        require(on.paint(10) == OPEN, "Enabled opens at mascot");
        on.expectBlocked("Original opening");
        require(on.paint(1060) == STORAGE, "First visible storage frame");
        on.requireSlotSent("First revealed frame");
        int actions = PacketDistributor.sent.size();
        on.click(109, 32);
        on.screen.charTyped('s', 0);
        on.screen.keyPressed(257, 0, 0);
        require(PacketDistributor.sent.size() == actions + 1, "Search sent exactly once");
        var search = (Packets.Action) PacketDistributor.sent.get(actions);
        require(search.verb().equals("text") && search.text().equals("s") && search.menu() == 2,
                "Search field immediately usable on the same menu");
        on.screen.init(on.mc, 270, 198);
        require(on.paint(1061) == STORAGE, "Resize does not restart");
        require(on.lastMenuPaint.storageScale < 0.9f, "Resize exercised reduced scale");
        on.requireSlotSent("Small-window drawn slot");
    }

    private static void closeEveryPhase() {
        int[] offsets = {-1,0,260,310,390,440,610,660,760,810,1050};
        int[] frames = {STORAGE,OPEN,HALF,CLOSED,HALF,OPEN,HALF,CLOSED,HALF,OPEN,STORAGE};
        for (int phase = 0; phase < offsets.length; phase++) {
            var visit = new ScreenDispatchCheck(10 + phase, true);
            if (phase == 0) {
                visit.menu.storage = false;
                visit.menu.version = 0;
                visit.menu.rows = 1;
                visit.menu.remote = false;
            }
            visit.paint(0);
            require(visit.paint(Math.max(0, offsets[phase])) == frames[phase], "Close setup phase " + phase);
            int closes = AbstractContainerScreen.closes;
            int packets = PacketDistributor.sent.size();
            visit.screen.keyPressed(256, 0, 0);
            require(AbstractContainerScreen.closes == closes + 1, "Escape must close active phase " + phase);
            visit.mc.setScreen(null);
            visit.expectBlocked("Removed phase " + phase);
            visit.screen.keyPressed(256, 0, 0);
            require(visit.paint(9999) == STALE && AbstractContainerScreen.closes == closes + 1
                    && PacketDistributor.sent.size() == packets, "Late callbacks revived closed phase " + phase);
            var reopened = new ScreenDispatchCheck(visit.mc, 100 + phase, true);
            require(reopened.paint(10_000) == OPEN, "Fresh screen after close phase " + phase);
            require(reopened.paint(11_050) == STORAGE, "Reopened screen reveals its own menu");
            reopened.requireSlotSent("Fresh menu " + phase);
        }
    }

    private static void replacement(boolean revealed) {
        var old = new ScreenDispatchCheck(48, true);
        old.paint(0);
        if (revealed) require(old.paint(1050) == STORAGE, "Started revealed");
        var replacement = new StoreMenu(old.menu.containerId);
        old.mc.player.containerMenu = replacement;
        int closes = AbstractContainerScreen.closes;
        old.expectBlocked("Reused menu id, different object");
        int ticks = ClientInteriors.ticks;
        old.screen.fixtureTick();
        require(ClientInteriors.ticks == ticks, "Stale screen kept ticking");
        require(old.paint(1100) == STALE, "Old screen painted stale contents");
        old.screen.keyPressed(256, 0, 0);
        require(AbstractContainerScreen.closes == closes && old.mc.player.containerMenu == replacement,
                "Stale Escape closed replacement menu");
        var newer = new ScreenDispatchCheck(old.mc, 48, true);
        old.screen.keyPressed(69, 0, 0);
        old.screen.removed();
        require(newer.mc.screen == newer.screen && newer.mc.player.containerMenu == newer.menu,
                "Old callback displaced newer screen");
        require(newer.paint(1200) == OPEN && newer.paint(2250) == STORAGE, "Replacement opens itself");
        newer.requireSlotSent("Replacement");
    }

    private static void overlaysAndOwnerChanges() {
        var covered = new ScreenDispatchCheck(53, true);
        require(covered.paint(20) == OPEN, "Initial real open");
        covered.mc.screen = new net.minecraft.client.gui.screens.Screen();
        int closeCalls = AbstractContainerScreen.closes;
        int ticks = ClientInteriors.ticks;
        require(covered.paint(90) == STALE, "Covered screen rendered through overlay");
        covered.expectBlocked("Temporary overlay");
        covered.screen.fixtureTick();
        covered.screen.keyPressed(256, 0, 0);
        require(AbstractContainerScreen.closes == closeCalls && ClientInteriors.ticks == ticks,
                "Covered screen closed or ticked through overlay");
        covered.mc.screen = covered.screen;
        require(covered.paint(1069) == OPEN && covered.paint(1070) == STORAGE, "Uncovered same screen safely");
        covered.requireSlotSent("Uncovered storage");

        for (boolean disconnected : new boolean[]{true, false}) {
            var old = new ScreenDispatchCheck(54, true);
            old.paint(0);
            var original = old.mc.player;
            old.mc.player = disconnected ? null : new LocalPlayer();
            if (!disconnected) old.mc.player.containerMenu = old.menu;
            old.expectBlocked("Owner changed");
            require(old.paint(2000) == STALE, "Logged-out/replaced owner still painted");
            old.mc.player = original;
            old.expectBlocked("Restored stale player");
            require(old.paint(2001) == STALE, "Old owner reference revived a retired screen");
        }
    }

    private static void cleanupAndCloseBindings() {
        for (int binding : new int[]{256, 69, 4}) {
            var visit = new ScreenDispatchCheck(63, true);
            // Close before first paint for Escape, during the blink for both inventory bindings.
            if (binding != 256) visit.paint(0);
            int count = AbstractContainerScreen.closes;
            if (binding == 4) visit.screen.mouseClicked(5, 5, 4);
            else visit.screen.keyPressed(binding, 0, 0);
            visit.screen.onClose();
            require(AbstractContainerScreen.closes == count + 1, "Repeated close or ignored binding " + binding);
        }
        var old = new ScreenDispatchCheck(69, true);
        var next = new ScreenDispatchCheck(old.mc, 69, true);
        var look = new ExpansionPackets.Look(-1, UUID.randomUUID(), next.menu.containerId, next.menu.session(),
                "plain", "none", "atelier", null, false);
        ClientInteriors.current = look;
        int cleared = ClientInteriors.clears, removed = AbstractContainerScreen.removals;
        old.screen.removed();
        old.screen.removed();
        require(ClientInteriors.current == look && ClientInteriors.clears == cleared
                && AbstractContainerScreen.removals == removed + 1, "Late removal clobbered successor appearance");
        next.mc.setScreen(null);
        next.screen.removed();
        require(ClientInteriors.clears == cleared + 1 && AbstractContainerScreen.removals == removed + 2,
                "Successor failed to clean itself exactly once");
    }

    private static void directCameosNeverBlock() {
        for (boolean backpack : new boolean[]{false, true}) {
            for (boolean cameo : new boolean[]{false, true}) {
                var manual = new ScreenDispatchCheck(backpack ? 79 : 78, true);
                manual.menu.remote = false;
                manual.menu.openingRule = false;
                manual.menu.openingHit = false;
                manual.menu.backpack = backpack;
                manual.menu.cameo = cameo;
                String interaction = (backpack ? "Backpack" : "Chest") + " cameo " + cameo;
                require(manual.paint(10) == STORAGE && manual.latestPaint.shells == 0, interaction);
                manual.requireSlotSent(interaction);
            }
        }
        var legacy = new ScreenDispatchCheck(80, true);
        legacy.menu.remote = false; // Old servers sent only the two shared intro bits.
        require(legacy.paint(10) == STORAGE && legacy.latestPaint.shells == 0, "Legacy server must open ordinary storage");
        legacy.requireSlotSent("Legacy first frame");

        var denied = new ScreenDispatchCheck(81, true);
        denied.menu.privateStorage = true; // Reject even inconsistent/stale authored flags.
        require(denied.paint(10) == STORAGE && denied.latestPaint.shells == 0, "Private view must skip the Remote intro");
    }

    public static void main(String[] args) {
        Util.timeSource = () -> millis * 1_000_000;
        defaultAndReveal();
        closeEveryPhase();
        replacement(false);
        replacement(true);
        overlaysAndOwnerChanges();
        cleanupAndCloseBindings();
        directCameosNeverBlock();
        System.out.println("PASS: actual client screen routes default/opt-in, first painted slot/search, "
                + "scaled resize, Escape before synchronization/all nine blink phases/after reveal, "
                + "replacement/overlay/disconnect, owned appearance cleanup and immediate direct storage");
    }
}
