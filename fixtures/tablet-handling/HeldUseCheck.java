package dev.anya.storage.appearance;

import dev.anya.storage.Client;
import dev.anya.storage.Content;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Only player, clock and presentation are substituted. Use and render events are NeoForge objects. */
public final class HeldUseCheck {
    private static final Minecraft CLIENT = Minecraft.getInstance();
    private static final InteractionHand MAIN = InteractionHand.MAIN_HAND, OFF = InteractionHand.OFF_HAND;

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static float paint(long now) {
        int before = TabletDevice.draws;
        Util.now = now;
        CLIENT.player.tickCount = (int)(now / 50);
        HeldTablet.beginFrame(new RenderFrameEvent.Pre(null));
        var render = new RenderHandEvent(MAIN, null, null, 15728880, .5f, 0, .12f, .17f, CLIENT.player.main);
        HeldTablet.hand(render);
        check(render.isCanceled(), "The selected held tablet takes over this render");
        HeldTablet.hud(new RenderGuiEvent.Post(new GuiGraphics(), null));
        var held = TabletDevice.saved;
        check(TabletDevice.draws == before + 1 && held != null && held.grip() == TabletDevice.painted
                && held.progress() == TabletDevice.progress && held.stack() == CLIENT.player.main
                && held.player() == CLIENT.player && held.hand() == MAIN && held.slot() == CLIENT.player.inv.selected
                && held.time() == now && held.grip().light() == 15728880
                && held.portrait() == TabletDevice.face && TabletDevice.liveAtlas
                && TabletDevice.faceOf(held.portrait()) == TabletFaces.face(held.stack()),
                "The bridge records exactly the grip and lift actually painted this frame");
        return held.progress();
    }

    private static void input(long now, InteractionHand hand, boolean use) {
        Util.now = now;
        var e = new InputEvent.InteractionKeyMappingTriggered(use ? 1 : 0,
                use ? CLIENT.options.keyUse : CLIENT.options.keyAttack, hand);
        boolean swing = e.shouldSwingHand(), canceled = e.isCanceled();
        HeldTablet.interaction(e);
        check(swing == e.shouldSwingHand() && canceled == e.isCanceled(), "Handling changed the actual use/attack event");
    }

    private static void release() {
        CLIENT.options.keyUse.down = false;
        HeldTablet.tick(null);
    }

    private static void inventoryFaces() {
        var player = new LocalPlayer();
        var a = new ItemStack(Content.CUPID_TABLET.get());
        var b = new ItemStack(Content.CUPID_TABLET.get());
        player.main = a;
        player.inv.slots[1] = b;
        TabletFaces.sample(player, false);
        player.tickCount = 11;
        TabletFaces.sample(player, false);
        check(TabletFaces.face(a) == TabletIdle.Face.GLANCE && TabletFaces.face(b) == TabletIdle.Face.SQUINT,
                "Two identical tablets must have their own visits");
        player.main = b;
        player.inv.slots[1] = a;
        TabletFaces.sample(player, false);
        player.tickCount = 15;
        TabletFaces.sample(player, false);
        check(TabletFaces.face(a) == TabletIdle.Face.DARK && TabletFaces.face(b) == TabletIdle.Face.SQUINT,
                "Moving identical stacks must preserve the first tablet's departure");
        var copy = new ItemStack(Content.CUPID_TABLET.get());
        player.main = copy;
        player.tickCount = 18;
        TabletFaces.sample(player, false);
        check(TabletFaces.face(copy) == TabletIdle.Face.SHUT, "Server-synced same-slot copy restarted the exit");
        player.off = copy;
        player.main = a;
        player.inv.slots[1] = null;
        player.tickCount = 26;
        TabletFaces.sample(player, false);
        check(TabletFaces.face(a) == TabletIdle.Face.DARK && TabletFaces.face(copy) == TabletIdle.Face.DARK,
                "Moving a caught tablet to the other hand must not reveal it again");
        TabletFaces.clear();
    }

    public static void main(String[] args) {
        inventoryFaces();
        CLIENT.player = new LocalPlayer();
        CLIENT.camera = CLIENT.player;
        CLIENT.player.main = new ItemStack(Content.REMOTE.get());
        CLIENT.player.off = new ItemStack(Content.REMOTE.get());
        HeldTablet.beginFrame(new RenderFrameEvent.Pre(null));
        var remote = new RenderHandEvent(MAIN, null, null, 15728880, 0, 0, 0, 0, CLIENT.player.main);
        HeldTablet.hand(remote);
        HeldTablet.hud(new RenderGuiEvent.Post(new GuiGraphics(), null));
        check(!remote.isCanceled() && TabletDevice.draws == 0 && TabletDevice.saved == null,
                "The heart Remote must keep its native rendering");
        CLIENT.player.main = new ItemStack(Content.CUPID_TABLET.get());
        CLIENT.player.off = new ItemStack(Content.CUPID_TABLET.get());
        if (args.length != 0) {
            HeldTablet.beginFrame(new RenderFrameEvent.Pre(null));
            var render = new RenderHandEvent(MAIN, null, null, 15728880, 0, 0, 0, 0, CLIENT.player.main);
            HeldTablet.hand(render);
            HeldTablet.hud(new RenderGuiEvent.Post(new GuiGraphics(), null));
            check(!render.isCanceled() && TabletDevice.draws == 0 && TabletDevice.saved == null,
                    "The opt-out must keep the held Cupid Tablet's native rendering");
            return;
        }
        check(paint(0) == 0, "Initially resting");
        var rest = TabletDevice.painted.motion();
        check(rest.yaw() == 0 && rest.pitch() == 0 && rest.walkX() == 0 && rest.walkY() == 0 && rest.roll() == 0,
                "A still player introduced hand motion");
        CLIENT.player.viewYRot = 12;
        CLIENT.player.viewXRot = -8;
        CLIENT.player.walkDist = CLIENT.player.walkDistO = .25f;
        CLIENT.player.bob = CLIENT.player.oBob = .4f;
        paint(1);
        var motion = TabletDevice.painted.motion();
        check(motion.yaw() > 1 && motion.pitch() < -.7f && motion.walkX() < -.1f
                && motion.walkY() < -.2f && motion.roll() < -.8f, "Hand pass ignored look and walk motion");
        CLIENT.options.bobView().set(false);
        paint(2);
        var noBob = TabletDevice.painted.motion();
        check(noBob.yaw() == motion.yaw() && noBob.pitch() == motion.pitch() && noBob.walkX() == 0
                && noBob.walkY() == 0 && noBob.roll() == 0, "Disabling view bob should only remove walk motion");
        CLIENT.options.bobView().set(true);
        input(10, OFF, true);
        check(paint(65) == 0, "Wrong hand cannot anticipate");
        input(70, MAIN, false);
        check(paint(125) == 0, "Attack cannot anticipate");
        CLIENT.player.sneak = true;
        input(130, MAIN, true);
        check(paint(185) == 0, "Sneaking use cannot anticipate");
        CLIENT.player.sneak = false;

        input(200, MAIN, true);
        float rising = paint(260), peak = paint(350), returning = paint(550);
        check(rising > 0 && rising < peak && peak < 1 && returning > 0 && returning < peak && paint(650) == 0,
                "Matching use rises and relaxes without an authorized storage open");
        release();
        input(700, MAIN, true);
        check(paint(750) > 0, "Second use starts");
        var unrelated = new Screen();
        HeldTablet.screenOpening(new ScreenEvent.Opening(null, unrelated));
        CLIENT.screen = unrelated;
        input(755, MAIN, true);
        check(TabletDevice.saved == null, "Unrelated GUI clears the bridge");
        CLIENT.screen = null;
        check(paint(800) == 0, "Unrelated GUI cancels lift");
        release();
        input(850, MAIN, true);
        check(paint(905) > 0, "Third use starts");
        CLIENT.player.main = new ItemStack(Content.CUPID_TABLET.get());
        check(paint(910) == 0, "Replacement Cupid Tablet cannot inherit the old lift");
        release();
        input(930, MAIN, true);
        check(paint(980) > 0, "Replacement can initiate its own lift");
        CLIENT.player.inv.selected++;
        check(paint(985) == 0, "A new slot cannot inherit the old lift");

        long visiting = 1_000;
        check(paint(visiting) == 0 && TabletFaces.face(CLIENT.player.main) == TabletIdle.Face.DARK,
                "An equipped tablet must finish hiding even if a matching stack was synced");
        // A genuinely new tablet in an unused slot has its own shy exit.
        CLIENT.player.inv.selected = 5;
        CLIENT.player.main = new ItemStack(Content.CUPID_TABLET.get());
        paint(visiting);
        check(TabletDevice.faceOf(TabletDevice.face) == TabletIdle.Face.SQUINT, "Fresh held squint");
        input(visiting + 5, MAIN, true);
        check(paint(visiting + 25) > 0 && TabletDevice.faceOf(TabletDevice.face) == TabletIdle.Face.SQUINT,
                "Use keeps the physical face already on the glass");
        paint(visiting + 80);
        var beforePause = TabletDevice.saved;
        var pause = new PauseScreen();
        HeldTablet.screenOpening(new ScreenEvent.Opening(null, pause));
        CLIENT.screen = pause;
        CLIENT.player.viewYRot = 28;
        CLIENT.player.walkDist = 2;
        long resumed = visiting + 5_080;
        check(paint(resumed) == beforePause.progress()
                && TabletDevice.saved.grip() == beforePause.grip()
                && TabletDevice.face.equals(beforePause.portrait()), "Pause changed the held grip, lift or face");
        CLIENT.screen = null;
        HeldTablet.tick(null);
        check(paint(resumed + 5) > beforePause.progress() && TabletDevice.face.equals(beforePause.portrait()),
                "Unpausing must continue from the prior frame");

        var storage = new Client.Screen();
        HeldTablet.screenOpening(new ScreenEvent.Opening(null, storage));
        CLIENT.screen = storage;
        Util.now = resumed + 8_005;
        CLIENT.player.tickCount = (int)((resumed + 8_005) / 50);
        HeldTablet.beginFrame(new RenderFrameEvent.Pre(null));
        CLIENT.screen = null;
        HeldTablet.tick(null);
        check(paint(resumed + 8_010) == 0 && TabletDevice.face.equals(beforePause.portrait()),
                "A screen that never painted a tablet must resume the held face without inventing a lowering animation");
        var closedFace = new TabletDevice.Portrait(3, 2);
        var captured = TabletDevice.saved;
        var closingGrip = new TabletDevice.Grip(true, .7f, .3f, captured.grip().light(), TabletHandMotion.STILL);
        TabletDevice.lowerFrom(new TabletDevice.Held(closingGrip, 1f, captured.width(), captured.height(), captured.player(),
                captured.stack(), captured.hand(), captured.slot(), Util.now, closedFace));
        CLIENT.player.main = new ItemStack(Content.CUPID_TABLET.get()); // A late full inventory sync on closing.
        long closeAt = resumed + 8_020;
        check(paint(closeAt) == 1f && TabletDevice.painted == closingGrip && TabletDevice.face == closedFace,
                "The first world frame after Escape must be the exact last painted case, grip and face");
        float halfway = paint(closeAt + 95);
        var middleGrip = TabletDevice.painted;
        check(halfway == .5f && middleGrip.swing() < closingGrip.swing() && middleGrip.swing() > .12f
                && TabletDevice.face == closedFace, "The return must carry the face and blend the updated world motion");
        HeldTablet.screenOpening(new ScreenEvent.Opening(null, pause));
        CLIENT.screen = pause;
        check(paint(closeAt + 1_095) == halfway && TabletDevice.painted == middleGrip && TabletDevice.face == closedFace,
                "Pausing during the return must preserve its pose");
        CLIENT.screen = null;
        HeldTablet.tick(null);
        check(paint(closeAt + 1_192) == 0 && TabletDevice.face != closedFace, "The return must finish after 190 unpaused ms");
        HeldTablet.screenOpening(new ScreenEvent.Opening(null, unrelated));
        CLIENT.screen = unrelated;
        CLIENT.screen = null;
        check(paint(closeAt + 1_202) == 0 && TabletDevice.faceOf(TabletDevice.face) == TabletFaces.face(CLIENT.player.main),
                "Unrelated screens must release the lowering override");
        System.out.println("PASS: shared hand motion, view bob option, carried face through lift and Escape, smooth 190 ms return with pause/resume");
    }
}
