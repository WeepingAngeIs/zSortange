package dev.anya.storage.appearance;

import dev.anya.storage.AnyaStorage;
import dev.anya.storage.Client;
import dev.anya.storage.Content;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
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

/** Adds the detailed first-person grip and face to the selected Cupid Tablet. */
@EventBusSubscriber(modid = AnyaStorage.ID, value = Dist.CLIENT)
public final class HeldTablet {
    private static Held owner;
    private static final TabletLift LIFT = new TabletLift();
    private static boolean useLatched;
    private static HandPose pending;
    private static TabletDevice.Held lowering;
    private static long loweringAt;
    private static float frameReturn;
    private static long frozenAt = Long.MIN_VALUE;
    private static long coveredTime;

    private record Held(LocalPlayer player, ItemStack stack, InteractionHand hand, int selectedSlot) {
        boolean matches(Held other) {
            return other != null && player == other.player && stack == other.stack && hand == other.hand
                    && selectedSlot == other.selectedSlot;
        }
    }
    private record HandPose(Held held, TabletDevice.Grip grip) {}

    private static long animationTime(long now, boolean covered) {
        if (covered) {
            if (frozenAt == Long.MIN_VALUE) frozenAt = now;
            return frozenAt - coveredTime;
        }
        if (frozenAt != Long.MIN_VALUE) {
            coveredTime += now - frozenAt;
            frozenAt = Long.MIN_VALUE;
        }
        return now - coveredTime;
    }

    private static TabletHandMotion motion(Minecraft mc, LocalPlayer player, float partial) {
        // These are the vanilla samples used by the hand pass, expressed relative to the view.
        float yaw = Math.clamp(Mth.wrapDegrees(player.getViewYRot(partial)
                - Mth.lerp(partial, player.yBobO, player.yBob)) * .1f, -4f, 4f);
        float pitch = Math.clamp((player.getViewXRot(partial)
                - Mth.lerp(partial, player.xBobO, player.xBob)) * .1f, -4f, 4f);
        if (!mc.options.bobView().get()) return new TabletHandMotion(yaw, pitch, 0, 0, 0);
        float phase = -(player.walkDist + (player.walkDist - player.walkDistO) * partial) * Mth.PI;
        float amplitude = Mth.lerp(partial, player.oBob, player.bob);
        float sway = Mth.sin(phase) * amplitude;
        return new TabletHandMotion(yaw, pitch, sway * .5f,
                -Math.abs(Mth.cos(phase) * amplitude), sway * 3f);
    }

    private static Held observe(Minecraft mc) {
        LocalPlayer player = mc.player;
        if (player == null || mc.getCameraEntity() != player || !mc.options.getCameraType().isFirstPerson()
                || mc.options.hideGui || player.isSpectator() || !player.isAlive() || player.isScoping()) {
            reset();
            return null;
        }
        // Only one tablet receives the detailed first-person pose. A second one uses its baked model.
        InteractionHand hand = InteractionHand.MAIN_HAND;
        ItemStack stack = player.getMainHandItem();
        if (!stack.is(Content.CUPID_TABLET.get())) {
            hand = InteractionHand.OFF_HAND;
            stack = player.getOffhandItem();
            if (!stack.is(Content.CUPID_TABLET.get())) {
                reset();
                return null;
            }
        }
        int slot = player.getInventory().selected;
        var current = new Held(player, stack, hand, slot);
        if (!current.matches(owner)) {
            owner = current;
            pending = null;
            lowering = null;
            LIFT.reset();
            useLatched = false;
            frozenAt = Long.MIN_VALUE;
            coveredTime = 0;
            // The close callback may just have queued a frame for the newly synced stack.
            TabletDevice.forgetWorldFrame();
        }
        return current;
    }

    private static void reset() {
        pending = null;
        lowering = null;
        LIFT.reset();
        useLatched = false;
        frozenAt = Long.MIN_VALUE;
        coveredTime = 0;
        owner = null;
        TabletDevice.forgetHeld();
    }

    @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
        TabletFaces.clear();
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (!TabletDevice.enabled()) {
            reset();
            return;
        }
        var mc = Minecraft.getInstance();
        var held = observe(mc);
        if (held == null) return;
        if (!mc.options.keyUse.isDown()) useLatched = false;
        animationTime(Util.getMillis(), mc.screen instanceof Client.Screen || mc.screen instanceof PauseScreen);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void screenOpening(ScreenEvent.Opening event) {
        if (!TabletDevice.enabled()) return;
        observe(Minecraft.getInstance()); // The selected stack can change before the server menu arrives.
        pending = null;
        var next = event.getNewScreen();
        animationTime(Util.getMillis(), next instanceof Client.Screen || next instanceof PauseScreen);
        if (next instanceof Client.Screen) {
            lowering = null;
            LIFT.reset(); // The storage screen owns the captured rise until the player closes it.
        } else if (next != null && !(next instanceof PauseScreen)) {
            lowering = null;
            LIFT.reset();
            TabletDevice.forgetHeld();
        }
    }

    @SubscribeEvent public static void interaction(InputEvent.InteractionKeyMappingTriggered event) {
        if (!TabletDevice.enabled() || !(event.isUseItem() || event.isAttack())) return;
        var mc = Minecraft.getInstance();
        if (mc.screen != null || !event.isUseItem() || useLatched) return;
        var held = observe(mc);
        if (held != null && event.getHand() == held.hand() && !held.player().isShiftKeyDown()) {
            useLatched = true;
            LIFT.use(animationTime(Util.getMillis(), false));
        }
    }

    @SubscribeEvent public static void beginFrame(RenderFrameEvent.Pre event) {
        pending = null;
        var mc = Minecraft.getInstance();
        boolean paused = mc.screen instanceof PauseScreen;
        // Capture one display state before either the native hotbar or the hand is painted.
        TabletFaces.sample(mc.player, paused || mc.screen instanceof Client.Screen);
        if (!TabletDevice.enabled() || mc.screen != null && !paused) return;
        var held = observe(mc);
        if (held == null) return;
        long time = animationTime(Util.getMillis(), paused);
        var close = TabletDevice.takeClosing();
        if (close != null && close.width() == mc.getWindow().getGuiScaledWidth()
                && close.height() == mc.getWindow().getGuiScaledHeight()) {
            lowering = close.afterMenuSync(held.player());
            loweringAt = time;
        }
        frameReturn = lowering == null ? 0 : remaining(time);
        if (lowering != null && frameReturn > 0 && lowering.stillHeld(held.player())
                && lowering.width() == mc.getWindow().getGuiScaledWidth()
                && lowering.height() == mc.getWindow().getGuiScaledHeight()) {
            TabletFaces.carry(held.stack(), TabletDevice.faceOf(lowering.portrait()));
        } else {
            lowering = null;
        }
    }

    private static float remaining(long time) {
        return lowering.progress() - (time - loweringAt) / (float) TabletLift.RETURN_MS;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void hand(RenderHandEvent event) {
        if (!TabletDevice.enabled()) return;
        var mc = Minecraft.getInstance();
        var held = observe(mc);
        // Vanilla can still be animating an older stack after a hotbar switch.
        if (held == null || event.getHand() != held.hand() || !ItemStack.matches(event.getItemStack(), held.stack())) return;
        boolean paused = mc.screen instanceof PauseScreen;
        if (mc.screen != null && !paused) {
            if (mc.screen instanceof Client.Screen) event.setCanceled(true);
            return;
        }
        var previous = TabletDevice.peekHeld();
        TabletDevice.Grip grip = paused && previous != null && previous.stillHeld(held.player())
                ? previous.grip()
                : new TabletDevice.Grip(TabletDevice.rightSide(held.player(), held.hand()), event.getSwingProgress(),
                        event.getEquipProgress(), event.getPackedLight(), motion(mc, held.player(), event.getPartialTick()));
        pending = new HandPose(held, grip);
        event.setCanceled(true);
    }

    @SubscribeEvent public static void hud(RenderGuiEvent.Post event) {
        HandPose pose = pending;
        pending = null;
        if (pose == null) return;
        var mc = Minecraft.getInstance();
        var held = observe(mc);
        boolean paused = mc.screen instanceof PauseScreen;
        if (!pose.held().matches(held) || (mc.screen != null && !paused)) return;
        var graphics = event.getGuiGraphics();
        int width = graphics.guiWidth();
        int height = graphics.guiHeight();
        if (width <= 0 || height <= 0) return;
        long now = Util.getMillis();
        long visualTime = animationTime(now, paused);
        float progress = LIFT.progress(visualTime);
        TabletDevice.Portrait portrait = TabletDevice.physicalFace(TabletFaces.face(held.stack()));
        TabletDevice.Grip grip = pose.grip();
        if (lowering != null) {
            float remaining = frameReturn;
            if (remaining <= 0 || !lowering.stillHeld(held.player()) || lowering.width() != width || lowering.height() != height) {
                lowering = null;
            } else {
                progress = Math.max(progress, remaining);
                var previous = TabletDevice.peekHeld();
                // Pause reuses the already blended grip. Blending it again each paint would creep.
                grip = paused && previous != null && previous.stillHeld(held.player()) ? previous.grip()
                        : blendGrip(lowering.grip(), grip, TabletHandlingPose.eased(1f - remaining / lowering.progress()));
                portrait = lowering.portrait();
            }
        }
        TabletDevice.drawHandling(graphics, width, height, held.player(), grip, progress, portrait, true);
        TabletDevice.rememberHeld(new TabletDevice.Held(grip, progress, width, height, held.player(), held.stack(),
                held.hand(), held.selectedSlot(), now, portrait));
    }

    private static TabletDevice.Grip blendGrip(TabletDevice.Grip from, TabletDevice.Grip to, float amount) {
        if (amount == 0) return from;
        return new TabletDevice.Grip(to.right(), Mth.lerp(amount, from.swing(), to.swing()),
                Mth.lerp(amount, from.equip(), to.equip()), to.light(), from.motion().toward(to.motion(), amount));
    }

    private HeldTablet() {}
}
