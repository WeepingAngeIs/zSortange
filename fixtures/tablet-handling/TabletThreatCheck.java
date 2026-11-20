package dev.anya.storage.appearance;

import dev.anya.storage.Client;
import dev.anya.storage.Content;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.client.event.*;

import static dev.anya.storage.appearance.TabletIdle.Face.*;

/** Runs the packaged client's receive, hotbar, detailed hand and use callbacks on the recording desktop. */
public final class TabletThreatCheck {
    private static final Minecraft mc = Minecraft.getInstance();
    private static final ItemStack ordinary = new ItemStack(Content.CUPID_TABLET.get());
    private static final ItemStack creative = new ItemStack(Content.CREATIVE_TABLET.get());
    private static final ItemStack spare = new ItemStack(Content.CUPID_TABLET.get());
    private static final ItemStack packed = new ItemStack(Content.CREATIVE_TABLET.get());

    private static void check(boolean value, String why) { if (!value) throw new AssertionError(why); }
    private static void time(int tick) {
        Util.now = 50L * tick;
        mc.player.tickCount = tick;
        HeldTablet.beginFrame(new RenderFrameEvent.Pre(null));
    }
    private static void expect(TabletIdle.Face face) {
        for (var item : new ItemStack[]{ordinary, creative, spare})
            check(TabletFaces.face(item) == face, "Glance must match both cases, hands and the hotbar");
        check(TabletFaces.face(packed) == TabletFaces.idleFace(packed), "Cue reached a nonvisible inventory slot");
    }
    private static void noCue(int octant) {
        int before = TabletWarningSound.plays;
        TabletThreat.receive(octant);
        check(TabletWarningSound.plays == before && TabletThreat.face(TabletFaces.clock()) == null,
                "Blocked cue was played or queued");
    }
    private static void paintHand(InteractionHand hand, ItemStack item, TabletIdle.Face expected) {
        var e = new RenderHandEvent(hand, null, null, 15728880, .5f, 0f, 0f, 0f, item);
        HeldTablet.hand(e);
        HeldTablet.hud(new RenderGuiEvent.Post(new GuiGraphics(), null));
        check(e.isCanceled() && TabletDevice.faceOf(TabletDevice.face) == expected
                && TabletFaces.face(item) == expected, "Hand and baked hotbar are out of sync");
        check(TabletDevice.faceOf(TabletDevice.saved.portrait()) == TabletFaces.idleFace(item),
                "Escape would replay a stale threat");
    }
    public static void main(String[] args) {
        mc.player = new LocalPlayer();
        mc.camera = mc.player;
        mc.player.main = ordinary;
        mc.player.off = creative;
        mc.player.inv.slots[3] = spare;
        mc.player.inv.slots[12] = packed;
        time(0);
        time(50);
        noCue(-1);
        noCue(8);
        var notices = new TabletIdle.Face[]{SHY_0, SHY_0, NOTICE_4, SMUG_8, NOTICE_1, SMUG_0, NOTICE_4, SHY_0};
        var glances = new TabletIdle.Face[]{SHY_4, GIGGLE_2, GIGGLE_1, PEEK_7, GIGGLE_1, PEEK_1, NOTICE_1, NOTICE_2};
        for (int octant = 0; octant < 8; octant++) {
            int start = 52 + 24 * octant;
            time(start);
            TabletThreat.receive(octant);
            expect(notices[octant]);
            TabletThreat.receive((octant + 1) % 8);
            check(TabletWarningSound.plays == octant + 1, "Duplicate cue played a second sound");
            time(start + 5);
            expect(glances[octant]);
            paintHand(InteractionHand.MAIN_HAND, ordinary, glances[octant]);
            // Transfer the main tablet to the hotbar. The detailed pose now belongs to the Creative offhand.
            mc.player.inv.slots[1] = ordinary;
            mc.player.main = ItemStack.EMPTY;
            time(start + 8);
            paintHand(InteractionHand.OFF_HAND, creative, glances[octant]);
            time(start + 10);
            expect(octant == 4 ? SMUG_4 : glances[octant]);
            mc.player.inv.slots[1] = null;
            mc.player.main = ordinary;
            time(start + 17);
            expect(SHUT);
            time(start + 18);
            check(TabletThreat.face(TabletFaces.clock()) == null && TabletFaces.face(ordinary) == DARK
                    && TabletFaces.face(spare) == TabletFaces.idleFace(spare), "Equip restarted a warning or an idle");
        }
        time(300);
        TabletThreat.receive(6);
        paintHand(InteractionHand.MAIN_HAND, ordinary, NOTICE_4);
        HeldTablet.interaction(new InputEvent.InteractionKeyMappingTriggered(1, mc.options.keyUse, InteractionHand.MAIN_HAND));
        noCue(2);
        var menu = new Client.Screen();
        HeldTablet.screenOpening(new ScreenEvent.Opening(null, menu));
        mc.screen = menu;
        noCue(1);
        time(350);
        mc.screen = null;
        HeldTablet.tick(null);
        var grip = new TabletDevice.Grip(true, 0, 0, 15728880);
        var closed = new TabletDevice.Held(grip, 1f, 640, 360, mc.player, ordinary, InteractionHand.MAIN_HAND,
                mc.player.inv.selected, Util.now, TabletDevice.physicalFace(DARK, false));
        TabletDevice.lowerFrom(closed);
        noCue(3);
        time(351);
        noCue(4);
        time(360);
        mc.options.hideGui = true;
        noCue(0);
        mc.options.hideGui = false;
        mc.player.main = ItemStack.EMPTY;
        mc.player.off = ItemStack.EMPTY;
        mc.player.inv.slots[3] = null;
        time(370);
        noCue(0);
        check(TabletWarningSound.plays == 9, "Unseen warning made a sound");
        mc.player.main = ordinary;
        time(375);
        check(TabletThreat.face(TabletFaces.clock()) == null, "A hidden warning played on pickup");
        TabletThreat.receive(0);
        mc.options.hideGui = true;
        time(376);
        mc.options.hideGui = false;
        time(377);
        check(TabletThreat.face(TabletFaces.clock()) == null, "Hidden warning resumed when the HUD returned");
        System.out.println("PASS: eight owner bearings, both cases and hands, one local sound, no queued or replayed menu warnings");
    }
}
