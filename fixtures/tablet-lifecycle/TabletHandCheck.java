import dev.anya.storage.Content;
import dev.anya.storage.appearance.HeldTablet;
import dev.anya.storage.appearance.TabletDevice;
import net.minecraft.Util;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.*;

/** Calls the real hand and HUD handlers. The fixture records draws in place of a GPU. */
public final class TabletHandCheck {
    private static final Minecraft MC = Minecraft.getInstance();
    private static final InteractionHand MAIN = InteractionHand.MAIN_HAND, OFF = InteractionHand.OFF_HAND;
    private static final ItemStack MAIN_TABLET = new ItemStack(Content.CUPID_TABLET.get());
    private static final ItemStack OFF_TABLET = new ItemStack(Content.CUPID_TABLET.get());
    private static final ItemStack OTHER = new ItemStack(new Item());

    private static void require(boolean condition, String reason) {
        if (!condition) throw new AssertionError(reason);
    }

    private static boolean hand(InteractionHand side, ItemStack stack) {
        var event = new RenderHandEvent(side, null, null, 0, 0, 0, 0, 0, stack);
        HeldTablet.hand(event);
        return event.isCanceled();
    }
    private static void begin() { HeldTablet.beginFrame(new RenderFrameEvent.Pre(null)); }
    private static void hud() { HeldTablet.hud(new RenderGuiEvent.Post(new GuiGraphics(), null)); }
    private static void mainFrame(long time) {
        Util.now = time;
        begin();
        require(hand(MAIN, MAIN_TABLET), "Expected selected first-person main hand");
        hud();
    }
    private static int draws() { return TabletDevice.draws; }

    public static void main(String[] args) {
        MC.player = new LocalPlayer();
        MC.player.main = MAIN_TABLET;
        MC.player.off = OFF_TABLET;
        MC.camera = MC.player;
        boolean enabled = Boolean.parseBoolean(args[0]);
        begin();
        require(hand(MAIN, MAIN_TABLET) == enabled, "Opt-in");
        require(!hand(OFF, OFF_TABLET), "Two tablets claimed the same frame");
        hud();
        hud();
        require(draws() == (enabled ? 1 : 0), "Repeated HUD callback");
        if (!enabled) {
            require(TabletDevice.saved == null, "Opt-out captured an opening pose");
            System.out.println("PASS: client opt-out keeps the native Cupid Tablet model");
            return;
        }
        require(TabletDevice.saved.stack() == MAIN_TABLET && TabletDevice.saved.grip().right(), "Right hand geometry");
        MC.player.arm = HumanoidArm.LEFT;
        mainFrame(1);
        require(!TabletDevice.saved.grip().right(), "Left-handed pose");
        MC.player.arm = HumanoidArm.RIGHT;

        int before = draws();
        begin(); hand(MAIN, MAIN_TABLET); begin(); hud();
        require(draws() == before, "Replayed previous frame's hand");
        begin(); hand(MAIN, MAIN_TABLET); MC.player.inv.selected++; hud();
        require(draws() == before && TabletDevice.saved == null, "Changed slot between callbacks");
        begin(); hand(MAIN, MAIN_TABLET); MC.player.main = OTHER; hud();
        require(draws() == before, "Old hand after swap");
        begin();
        require(!hand(MAIN, MAIN_TABLET) && hand(OFF, OFF_TABLET), "Offhand selection");
        hud();
        require(TabletDevice.saved.stack() == OFF_TABLET && !TabletDevice.saved.grip().right(), "Offhand pose");
        MC.options.camera = CameraType.THIRD_PERSON_BACK;
        begin(); require(!hand(OFF, OFF_TABLET), "Third-person substitution"); hud();
        require(TabletDevice.saved == null && draws() == before + 1, "Third-person painted");
        MC.options.camera = CameraType.FIRST_PERSON;
        MC.options.hideGui = true;
        begin(); require(!hand(OFF, OFF_TABLET), "Hidden HUD replaced native item"); hud();
        MC.options.hideGui = false;
        MC.player.main = MAIN_TABLET;

        // A real randomly scheduled idle interval, accelerated only by the fixture clock.
        int peeks = TabletDevice.peeks;
        long t = 100;
        mainFrame(t);
        for (; t < 111_200 && TabletDevice.peeks == peeks; t += 100) mainFrame(t);
        require(TabletDevice.peeks == peeks + 1 && t >= 55_100 && t <= 110_300, "Rare idle scheduling");
        var heldSample = TabletDevice.saved;
        var use = new InputEvent.InteractionKeyMappingTriggered(1,MC.options.keyUse,MAIN);
        HeldTablet.interaction(use);
        require(!use.isCanceled() && use.shouldSwingHand() && TabletDevice.saved == heldSample, "Use changed vanilla action or lost lift origin");
        mainFrame(t);
        require(TabletDevice.peeks == peeks + 2 && TabletDevice.face != null,
                "Use must keep painting the ongoing visit");
        var attack = new InputEvent.InteractionKeyMappingTriggered(0, MC.options.keyAttack, MAIN);
        HeldTablet.interaction(attack);
        MC.options.keyAttack.down = true;
        mainFrame(++t);
        require(!attack.isCanceled() && attack.shouldSwingHand() && TabletDevice.face != null,
                "Attack changed the click, swing or ongoing visit");
        MC.options.keyAttack.down = false;
        mainFrame(++t);
        var storage = new dev.anya.storage.Client.Screen();
        HeldTablet.screenOpening(new ScreenEvent.Opening(null,storage));
        MC.screen = storage;
        before = draws();
        begin(); require(hand(MAIN, MAIN_TABLET),"Double paint behind storage screen"); hud();
        require(draws() == before && TabletDevice.saved.stack() == MAIN_TABLET, "Opening lost its pose or painted a second hand");
        var settings = new Screen();
        HeldTablet.screenOpening(new ScreenEvent.Opening(storage,settings));
        MC.screen = settings;
        begin(); require(!hand(MAIN, MAIN_TABLET),"Unrelated screen replaced the hand"); hud();
        require(draws() == before && TabletDevice.saved == null, "Unrelated GUI kept the lift origin");
        MC.screen = null;
        mainFrame(t + 1);
        MC.player.inv.selected++;
        HeldTablet.screenOpening(new ScreenEvent.Opening(null, storage));
        require(TabletDevice.saved == null, "Hotbar changed immediately before open");
        MC.player = null;
        HeldTablet.logout(null);
        begin(); hud();
        require(TabletDevice.saved == null, "Disconnect left an opening pose");
        System.out.println("PASS: actual first-person and HUD callbacks, dual and left hands, stale slots/frames, idle gaze, ordinary use, GUI handoff, hidden HUD and disconnect");
    }
}
