package dev.anya.storage.appearance;

import dev.anya.storage.AnyaStorage;
import dev.anya.storage.Client;
import dev.anya.storage.Content;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;

/** Substitutes the opted-in local player's first-person Remote only, after vanilla selects the hand. */
@EventBusSubscriber(modid = AnyaStorage.ID, value = Dist.CLIENT)
public final class HeldTablet {
    private static final boolean ENABLED = Boolean.getBoolean("anyastorage.tabletIntro");
    private static final TabletIdle IDLE = new TabletIdle(TabletDevice::chooseVisit);
    private static final TabletLift LIFT = new TabletLift();
    private static boolean useLatched;
    private static HandPose pending;

    private record Held(LocalPlayer player, ItemStack stack, InteractionHand hand, int selectedSlot) {
        boolean matches(Held other) {
            return other != null && player == other.player && stack == other.stack && hand == other.hand
                    && selectedSlot == other.selectedSlot;
        }
    }
    private record HandPose(Held held, float swing, float equip, int light) {}

    private static Held observe(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.getCameraEntity() != player || !mc.options.getCameraType().isFirstPerson()
                || mc.options.hideGui || player.isSpectator() || !player.isAlive() || player.isScoping()) {
            reset();
            return null;
        }
        // A second Remote keeps its ordinary item appearance. Only one tablet receives the idle glance.
        InteractionHand hand = InteractionHand.MAIN_HAND;
        ItemStack stack = player.getMainHandItem();
        if (!stack.is(Content.REMOTE.get())) {
            hand = InteractionHand.OFF_HAND;
            stack = player.getOffhandItem();
            if (!stack.is(Content.REMOTE.get())) {
                reset();
                return null;
            }
        }
        int slot = player.getInventory().selected;
        if (IDLE.bind(player, stack, hand == InteractionHand.OFF_HAND, slot)) {
            pending = null;
            LIFT.reset();
            useLatched = false;
            TabletDevice.forgetHeld();
        }
        return new Held(player, stack, hand, slot);
    }

    private static void reset() {
        pending = null;
        LIFT.reset();
        useLatched = false;
        if (IDLE.bind(null, null, false, -1)) TabletDevice.forgetHeld();
    }

    private static boolean busy(Minecraft mc, Held held) {
        return held.player().isUsingItem() || mc.options.keyUse.isDown() || mc.options.keyAttack.isDown();
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        if (ENABLED) reset();
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!ENABLED) return;
        var mc = Minecraft.getInstance();
        var held = observe(mc);
        if (held == null) return;
        if (!mc.options.keyUse.isDown()) useLatched = false;
        if (mc.screen != null || busy(mc, held)) IDLE.quiet();
        if (mc.screen != null && !(mc.screen instanceof Client.Screen)) TabletDevice.forgetHeld();
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void screenOpening(ScreenEvent.Opening event) {
        if (!ENABLED) return;
        observe(Minecraft.getInstance()); // A slot or hand can change between the previous HUD draw and use.
        IDLE.quiet();
        LIFT.reset(); // The authorized screen continues from the last painted hand sample.
        pending = null;
        if (!(event.getNewScreen() instanceof Client.Screen)) TabletDevice.forgetHeld();
    }

    @SubscribeEvent public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!ENABLED || !(event.isUseItem() || event.isAttack())) return;
        IDLE.quiet();
        var mc = Minecraft.getInstance();
        if (mc.screen != null || !event.isUseItem() || useLatched) return;
        var held = observe(mc);
        if (held != null && event.getHand() == held.hand() && !held.player().isShiftKeyDown()) {
            useLatched = true;
            LIFT.use(Util.getMillis());
        }
    }

    @SubscribeEvent public static void beginFrame(RenderFrameEvent.Pre event) {
        pending = null;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void hand(RenderHandEvent event) {
        if (!ENABLED) return;
        var mc = Minecraft.getInstance();
        var held = observe(mc);
        // Vanilla can still be animating an older stack after a hotbar switch.
        if (held == null || event.getHand() != held.hand() || event.getItemStack() != held.stack()) return;
        if (mc.screen != null) {
            if (mc.screen instanceof Client.Screen) event.setCanceled(true);
            return;
        }
        pending = new HandPose(held, event.getSwingProgress(), event.getEquipProgress(), event.getPackedLight());
        event.setCanceled(true);
    }

    @SubscribeEvent public static void hud(RenderGuiEvent.Post event) {
        HandPose pose = pending;
        pending = null;
        if (pose == null) return;
        var mc = Minecraft.getInstance();
        var held = observe(mc);
        if (!pose.held().matches(held) || mc.screen != null) return;
        var graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        if (width <= 0 || height <= 0) return;
        boolean right = TabletDevice.rightSide(held.player(), held.hand());
        var grip = new TabletDevice.Grip(right, pose.swing(), pose.equip(), pose.light());
        long now = Util.getMillis();
        float progress = LIFT.progress(now);
        boolean busy = busy(mc, held) || progress > 0;
        if (busy) IDLE.quiet();
        int column = busy ? -1 : IDLE.paintColumn(now);
        TabletDevice.drawHandling(graphics, width, height, held.player(), grip, progress,
                TabletDevice.visit(IDLE.row(), column));
        TabletDevice.rememberHeld(new TabletDevice.Held(grip, progress, width, height, held.player(), held.stack(),
                held.hand(), held.selectedSlot(), now));
    }

    private HeldTablet() {}
}
