package dev.anya.storage.visualcheck;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.anya.storage.pocket.EntranceLayoutClient;
import dev.anya.storage.pocket.PocketContent;
import dev.anya.storage.pocket.PocketPadPackets;
import dev.anya.storage.pocket.PocketService;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.opengl.GL11;

/** Two ordinary clients render the shipped entrance. The observer passes every play packet through unchanged. */
@EventBusSubscriber(modid = "anyavisualcheck", value = Dist.CLIENT)
public final class VisualClient {
    private static final BlockPos ENTRANCE = new BlockPos(8, 81, 1);
    private static final String VISIT_DENIAL = "[Anya Storage] Pocket permission denied; Visit is separate from storage rights";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String ROLE = System.getProperty("anya.role");
    private static final boolean STILL = Boolean.getBoolean("anyastorage.stillFramePeek");
    private static final AtomicInteger watches = new AtomicInteger();
    private static final AtomicInteger grants = new AtomicInteger();
    private static final AtomicInteger clears = new AtomicInteger();
    private static final AtomicInteger stillGrants = new AtomicInteger(), stillClears = new AtomicInteger();
    private static volatile PocketPadPackets.StillUpdate lastCyan;
    private static volatile long stillFocusToken;
    private static volatile int positivesAtLastStillClear;
    private static PocketPadPackets.StillUpdate heldFrame;
    private static int clearsAtUse, clearsAtGrant, clearsAtRegrant;
    private static boolean staleRejectedAfterClear, staleRejectedAfterRegrant;
    private static int age, stable, phaseTicks, clearsWhenGranted, initialPocketChunks = -1;
    private static int useTick, walkTicks, contactTicks;
    private static int useDenials;
    private static String denialMessage;
    private static long focusedTicks, phaseStart, lastNotice;
    private static String waiting = "";
    private static Integer observedMask;
    private static String phase = "", completed = "", useResult;
    private static boolean connecting, ready, capture, failed, walking, watchingGrant;
    private static Connection connection;
    private static Field clientWorlds;
    private static Vec3 walkStart;
    private static final Set<String> PHOTOS = Set.of("public", "granted", "revoked");
    private static final Set<String> STILL_PHOTOS = Set.of("public", "granted", "changed", "cell_denied", "resumed",
            "revoked", "regranted", "final_public");

    private static Path control() {
        require("true".equals(System.getenv("GITHUB_ACTIONS")) && Set.of("Alpha", "Beta").contains(ROLE),
                "Disposable GitHub client required");
        return Path.of(System.getProperty("anya.control"));
    }

    private static void json(String filename, Object value) throws Exception {
        Path target = control().resolve(filename);
        Path tmp = target.resolveSibling(filename + ".tmp");
        Files.writeString(tmp, JSON.toJson(value));
        Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void require(boolean ok, String why) {
        if (!ok) throw new IllegalStateException(why);
    }

    private static Direction side() {
        return "Alpha".equals(ROLE) ? Direction.SOUTH : Direction.EAST;
    }

    private static void fail(Minecraft mc, Throwable failure) {
        if (failed) return;
        failed = true;
        mc.options.keyUp.setDown(false);
        dev.anya.storage.AnyaStorage.LOG.error("Visual check failed for {} during {}", ROLE, phase, failure);
        try {
            json("FAILED-client-" + ROLE + ".json", Map.of("phase", phase, "error", failure.toString(), "tick", age,
                    "readiness", status(mc)));
        } catch (Exception nested) {
            dev.anya.storage.AnyaStorage.LOG.error("Could not record visual check failure", nested);
        }
        mc.stop();
    }

    private static Map<String, Object> status(Minecraft mc) {
        var state = new LinkedHashMap<String, Object>();
        state.put("waiting", waiting);
        state.put("world", mc.level == null ? null : mc.level.dimension().location().toString());
        state.put("position", mc.player == null ? null : mc.player.position().toString());
        state.put("screen", mc.screen == null ? null : mc.screen.getClass().getSimpleName());
        state.put("home_column", mc.level != null && mc.level.hasChunkAt(ENTRANCE));
        state.put("hit", mc.hitResult instanceof BlockHitResult h
                ? List.of(h.getType().name(), h.getBlockPos().toShortString(), h.getDirection().name()) : String.valueOf(mc.hitResult));
        state.put("mask", observedMask);
        state.put("focus_requests", watches.get());
        state.put("grants", grants.get());
        state.put("clears", clears.get());
        if (STILL) {
            state.put("still_positive_received", stillGrants.get());
            state.put("still_clears_received", stillClears.get());
        }
        state.put("use_denials", useDenials);
        return state;
    }

    private static void waitFor(Minecraft mc, String condition) {
        waiting = condition;
        long now = System.nanoTime();
        if (lastNotice == 0 || now - lastNotice >= TimeUnit.SECONDS.toNanos(15)) {
            lastNotice = now;
            dev.anya.storage.AnyaStorage.LOG.info("Entrance visual check {} {} {}", ROLE, phase, status(mc));
        }
    }

    private static void observe(Connection current) {
        require(connection == null, "The measured client reconnected");
        connection = current;
        connection.channel().pipeline().addBefore("packet_handler", "anya_visual_focus_audit", new ChannelDuplexHandler() {
            @Override public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                if (msg instanceof ServerboundCustomPayloadPacket packet && packet.payload() instanceof PocketPadPackets.Focus focus
                        && focus.watching() && ENTRANCE.equals(focus.pos())) watches.incrementAndGet();
                if (STILL && msg instanceof ServerboundCustomPayloadPacket packet && packet.payload() instanceof PocketPadPackets.StillFocus focus
                        && focus.watching() && ENTRANCE.equals(focus.pos())) {
                    stillFocusToken = focus.token();
                    watches.incrementAndGet();
                }
                super.write(ctx, msg, promise);
            }

            @Override public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (msg instanceof ClientboundCustomPayloadPacket packet && packet.payload() instanceof PocketPadPackets.Update update
                        && ENTRANCE.equals(update.pos())) {
                    if (update.mask() >= 0) grants.incrementAndGet(); else clears.incrementAndGet();
                }
                if (STILL && msg instanceof ClientboundCustomPayloadPacket packet && packet.payload() instanceof PocketPadPackets.StillUpdate update
                        && ENTRANCE.equals(update.pos())) {
                    if (update.material() == 0) {
                        positivesAtLastStillClear = stillGrants.get();
                        stillClears.incrementAndGet();
                    }
                    else {
                        stillGrants.incrementAndGet();
                        if (update.material() == 1) lastCyan = update;
                    }
                }
                super.channelRead(ctx, msg);
            }
        });
    }

    private static void backend(Minecraft mc) throws Exception {
        VisualInputs.verify(ROLE);
        require(!ModList.get().isLoaded("anyaliveview"), "Unexpected beta.6 adapter");
        var sodium = new ArrayList<String>();
        for (Class<?> type = mc.levelRenderer.getClass(); type != null; type = type.getSuperclass()) {
            for (var field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object actual = field.get(mc.levelRenderer);
                if (actual != null && actual.getClass().getName().startsWith("net.caffeinemc.mods.sodium"))
                    sodium.add(actual.getClass().getName());
            }
        }
        var interfaces = Arrays.stream(mc.levelRenderer.getClass().getInterfaces()).map(Class::getName).toList();
        require(!sodium.isEmpty() && interfaces.stream().anyMatch(name -> name.startsWith("qouteall.imm_ptl")),
                "Sodium or CE renderer integration absent");
        Object pack = Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPack").invoke(null);
        require(pack instanceof Optional<?> value && value.isEmpty(), "Shader pack active");
        json("backend-" + ROLE + ".json", Map.of("role", ROLE, "sodium_renderer_classes", sodium,
                "renderer_interfaces", interfaces, "iris_current_pack", pack.toString(), "ce_version",
                ModList.get().getModContainerById("imm_ptl").orElseThrow().getModInfo().getVersion().toString(),
                "gl_vendor", GL11.glGetString(GL11.GL_VENDOR), "gl_renderer", GL11.glGetString(GL11.GL_RENDERER),
                "gl_version", GL11.glGetString(GL11.GL_VERSION)));
    }

    private static int pocketChunks() throws ReflectiveOperationException {
        // Reading this map does not ask CE to create a remote world or load a column.
        if (clientWorlds == null) {
            clientWorlds = Class.forName("qouteall.imm_ptl.core.ClientWorldLoader").getDeclaredField("CLIENT_WORLD_MAP");
            clientWorlds.setAccessible(true);
        }
        var level = (ClientLevel) ((Map<?, ?>)clientWorlds.get(null)).get(PocketService.DIMENSION);
        return level == null ? 0 : level.getChunkSource().getLoadedChunksCount();
    }

    private static void sourceOnly(Minecraft mc) {
        require(mc.level != null && mc.player != null && mc.player.level() == mc.level && mc.level.dimension().equals(Level.OVERWORLD),
                "Viewer left the home world during " + phase);
        require(mc.player.containerMenu == mc.player.inventoryMenu && mc.screen == null, "Unexpected viewer menu or screen");
    }

    private static BlockHitResult hit(Minecraft mc) {
        return mc.hitResult instanceof BlockHitResult result && result.getType() == HitResult.Type.BLOCK && !result.isInside()
                && result.getDirection() == side() && result.getBlockPos().equals(ENTRANCE) ? result : null;
    }

    private static int expectedMask() {
        return !STILL && ROLE.equals("Alpha") && (phase.equals("granted") || phase.equals("use")) ? 143 : -1;
    }

    private static int expectedStill() {
        if (!STILL || !ROLE.equals("Alpha")) return 0;
        return switch (phase) {
            case "granted", "resumed", "use" -> 1;
            case "changed", "regranted" -> 2;
            default -> 0;
        };
    }

    private static boolean observedClear() {
        if (!ROLE.equals("Alpha")) return true;
        if (!STILL) return !phase.equals("revoked") || clears.get() > clearsWhenGranted;
        return switch (phase) {
            case "cell_denied" -> stillClears.get() > clearsAtGrant;
            case "revoked" -> stillClears.get() > clearsAtUse;
            case "final_public" -> stillClears.get() > clearsAtRegrant;
            default -> true;
        };
    }

    private static void checkLateFrame(PocketContent.EntranceEntity entrance) {
        if (!STILL || !ROLE.equals("Alpha") || !phase.equals("revoked") && !phase.equals("regranted")) return;
        require(heldFrame != null && heldFrame.material() == 1
                        && (!phase.equals("regranted") || heldFrame.token() != stillFocusToken),
                "Deferred work must come from the actual retired cyan focus");
        // Replay a retained client work item from the earlier legal grant. No test packet is sent.
        int before = EntranceLayoutClient.visibleStill(entrance);
        EntranceLayoutClient.receiveStill(connection, heldFrame);
        require(before == expectedStill() && before == EntranceLayoutClient.visibleStill(entrance),
                "Earlier grant changed the displayed frame");
        if (phase.equals("revoked")) staleRejectedAfterClear = true;
        else staleRejectedAfterRegrant = true;
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post ignored) {
        Minecraft mc = Minecraft.getInstance();
        if (failed) return;
        try {
            Path root = control();
            require(++age <= 30000, "Client lifetime exceeded");
            if (!connecting && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                connecting = true;
                String address = "127.0.0.1:25579";
                ConnectScreen.startConnecting(mc.screen, mc, ServerAddress.parseString(address),
                        new ServerData("Disposable entrance visual check", address, ServerData.Type.OTHER), false, null);
            }
            if (!ready) {
                if (mc.level == null || mc.player == null || mc.getConnection() == null || mc.getOverlay() != null ||
                        mc.screen instanceof ReceivingLevelScreen || mc.screen != null) return;
                observe(mc.getConnection().getConnection());
                backend(mc);
                json("client-ready-" + ROLE + ".json", Map.of("role", ROLE, "uuid", mc.player.getUUID().toString(),
                        "name", mc.player.getGameProfile().getName()));
                ready = true;
            }
            // Both graphical players stay home from login. Room preparation runs independently on the server.
            require(mc.level == null || mc.level.dimension().equals(Level.OVERWORLD), "Viewer traveled outside the home world");
            require(pocketChunks() == 0, "The client acquired a destination column");
            Path signal = root.resolve("phase.txt");
            if (!Files.isRegularFile(signal)) return;
            String requested = Files.readString(signal).strip();
            if (!phase.equals(requested)) {
                require(phase.isEmpty() || completed.equals(phase), "Phase changed before client acknowledgement: " + phase);
                phase = requested;
                waiting = "phase start";
                observedMask = null;
                phaseStart = System.nanoTime();
                lastNotice = 0;
                stable = phaseTicks = 0;
                capture = false;
            }
            if (phase.equals("done")) {
                if (!completed.equals(phase)) {
                    json("client-finished-" + ROLE + ".json", Map.of("role", ROLE, "focus_ticks", focusedTicks));
                    completed = phase;
                }
                mc.stop();
                return;
            }
            if (!completed.equals(phase)) {
                require(System.nanoTime() - phaseStart < TimeUnit.SECONDS.toNanos(100),
                        "Timed out waiting for " + waiting);
            }
            if (mc.level == null || mc.player == null || mc.gameMode == null || mc.getOverlay() != null ||
                    mc.screen instanceof ReceivingLevelScreen) {
                require(completed.isEmpty() && phase.equals("public"), "Lost game world during " + phase);
                waitFor(mc, "playable home screen");
                return;
            }
            if (phase.equals("public") && completed.isEmpty() && !mc.level.hasChunkAt(ENTRANCE)) {
                waitFor(mc, "entrance column");
                return;
            }
            sourceOnly(mc);
            require(!ROLE.equals("Beta") || grants.get() == 0, "Untrusted viewer received a private pad layout");
            require(!STILL || grants.get() == 0, "Still negotiation sent a private pad mask");
            require(!ROLE.equals("Beta") || stillGrants.get() == 0, "Untrusted viewer received a marker material");
            if (phase.equals("walk")) {
                if (!completed.equals(phase)) walk(mc);
                return;
            }
            require((STILL ? STILL_PHOTOS : PHOTOS).contains(phase) || phase.equals("use"), "Unknown phase " + phase);
            mc.options.hideGui = true;
            mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(ENTRANCE));
            var picked = hit(mc);
            require(!watchingGrant || picked != null, "Viewer lost focus before the revoked frame");
            if (picked != null) focusedTicks++;
            if (completed.equals(phase)) return;
            require(++phaseTicks < 2200, "No expected view for " + phase + " watches=" + watches + " grants=" + grants
                    + " clears=" + clears + " hit=" + mc.hitResult + " player=" + mc.player.position());
            if (picked == null || mc.getCameraEntity() != mc.player || !(mc.level.getBlockEntity(ENTRANCE) instanceof PocketContent.EntranceEntity entrance)) {
                stable = 0;
                waitFor(mc, "entrance under first-person crosshair on " + side());
                return;
            }
            int mask = EntranceLayoutClient.visibleMask(entrance);
            observedMask = mask;
            if (mask != expectedMask() || watches.get() == 0 || !observedClear()
                    || STILL && EntranceLayoutClient.visibleStill(entrance) != expectedStill()) {
                stable = 0;
                waitFor(mc, "applied mask " + expectedMask() + ", focus request and any expected clear");
                return;
            }
            if (++stable < 16) {
                waitFor(mc, "stable focused image");
                return;
            }
            checkLateFrame(entrance);
            if (phase.equals("use")) use(mc, picked);
            else {
                waitFor(mc, "finished render frame");
                capture = true;
            }
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    @SubscribeEvent
    public static void frame(RenderFrameEvent.Post ignored) {
        if (failed || !capture) return;
        Minecraft mc = Minecraft.getInstance();
        try {
            sourceOnly(mc);
            int appliedMask = mc.level.getBlockEntity(ENTRANCE) instanceof PocketContent.EntranceEntity entrance
                    ? EntranceLayoutClient.visibleMask(entrance) : -1;
            if (hit(mc) == null || !(mc.level.getBlockEntity(ENTRANCE) instanceof PocketContent.EntranceEntity)
                    || appliedMask != expectedMask()) {
                stable = 0;
                capture = false;
                return;
            }
            var stillEntrance = (PocketContent.EntranceEntity) mc.level.getBlockEntity(ENTRANCE);
            int appliedStill = STILL ? EntranceLayoutClient.visibleStill(stillEntrance) : 0;
            if (STILL && appliedStill != expectedStill()) {
                stable = 0;
                capture = false;
                return;
            }
            var rendered = Class.forName("qouteall.imm_ptl.core.render.context_management.RenderStates");
            int portals = ((Number) rendered.getField("portalsRenderedThisFrame").get(null)).intValue();
            var dimensions = (Set<?>) rendered.getField("renderedDimensions").get(null);
            require(portals == 0 && !dimensions.contains(PocketService.DIMENSION), "Pocket terrain was rendered in the measured frame");
            var portal = Class.forName("qouteall.imm_ptl.core.portal.Portal");
            for (var entity : mc.level.entitiesForRendering()) require(!portal.isInstance(entity), "A portal entity appeared at home");
            int chunks = pocketChunks();
            if (phase.equals("public")) initialPocketChunks = chunks;
            require(chunks == 0, "Remote pocket cache contained a destination column in the measured frame");
            require(!(ROLE.equals("Beta") || phase.equals("public")) || grants.get() == 0, "Public control received a private layout");
            if (expectedMask() >= 0) require(grants.get() > 0, "No private layout response observed");
            String label = phase + "-" + ROLE;
            String face = side().name().toLowerCase(Locale.ROOT);
            var camera = mc.gameRenderer.getMainCamera();
            var eye = camera.getPosition();
            var aim = camera.getLookVector();
            var up = camera.getUpVector();
            try (var pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
                var path = control().resolve(label + ".png");
                require(!Files.exists(path), "Existing screenshot " + label);
                pixels.writeToFile(path);
                json(label + "-camera.json", Map.of("eye", List.of(eye.x, eye.y, eye.z), "aim", List.of(aim.x(), aim.y(), aim.z()),
                        "up", List.of(up.x(), up.y(), up.z()), "fov", mc.options.fov().get(), "side", face,
                        "entrance", List.of(ENTRANCE.getX(), ENTRANCE.getY(), ENTRANCE.getZ()), "width", pixels.getWidth(), "height", pixels.getHeight()));
            }
            var state = new LinkedHashMap<String, Object>();
            state.put("role", ROLE);
            state.put("phase", phase);
            state.put("mask", appliedMask);
            state.put("side", face);
            state.put("watches", watches.get());
            state.put("positive_received", grants.get());
            if (STILL) {
                require(appliedStill == 0 || stillGrants.get() > 0, "No entitled still received");
                if (appliedStill == 0) require(stillGrants.get() == positivesAtLastStillClear,
                        "Private marker material arrived after a clear or before the first grant");
                if (ROLE.equals("Alpha") && phase.equals("changed")) clearsAtGrant = stillClears.get();
                if (ROLE.equals("Alpha") && phase.equals("regranted")) clearsAtRegrant = stillClears.get();
                state.put("still_material", appliedStill);
                state.put("still_positive_received", stillGrants.get());
                state.put("still_clears_received", stillClears.get());
                state.put("late_frame_rejected_after_clear", staleRejectedAfterClear);
                state.put("late_frame_rejected_after_new_grant", staleRejectedAfterRegrant);
            }
            state.put("clears_received", clears.get());
            state.put("portals_rendered_this_frame", portals);
            state.put("rendered_dimensions", dimensions.stream().map(Object::toString).sorted().toList());
            state.put("pocket_columns_current", chunks);
            state.put("pocket_columns_before_grant", initialPocketChunks);
            state.put("stable_ticks", stable);
            json(label + "-state.json", state);
            if (ROLE.equals("Alpha") && phase.equals("granted")) watchingGrant = true;
            if (phase.equals("revoked")) watchingGrant = false;
            ack(Map.of("mask", appliedMask));
            capture = false;
        } catch (Throwable failure) {
            fail(mc, failure);
        }
    }

    @SubscribeEvent
    public static void systemChat(ClientChatReceivedEvent.System event) {
        if (!phase.equals("use") || useTick == 0 || event.isOverlay()) return;
        String received = event.getMessage().getString();
        if (VISIT_DENIAL.equals(received)) {
            denialMessage = received;
            if (++useDenials > 1) fail(Minecraft.getInstance(), new IllegalStateException("Repeated Visit denial for a single use"));
        }
    }

    private static void use(Minecraft mc, BlockHitResult picked) throws Exception {
        if (useTick == 0) {
            require(mc.player.getMainHandItem().isEmpty() && mc.player.getOffhandItem().isEmpty(),
                    "Expected empty hands for normal use");
            useTick = age;
            useResult = mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, picked).toString();
        }
        if (useDenials == 0) {
            waitFor(mc, "the mod's Visit denial for the entrance click");
            return;
        }
        if (age - useTick >= 12) {
            // The server waits for this receipt before revoking View.
            if (ROLE.equals("Alpha")) clearsWhenGranted = clears.get();
            if (STILL && ROLE.equals("Alpha")) {
                clearsAtUse = stillClears.get();
                heldFrame = lastCyan;
                require(heldFrame != null && heldFrame.token() == stillFocusToken && heldFrame.material() == 1,
                        "Need an actual current authorized cyan frame to defer past revoke");
            }
            ack(Map.of("home_dimension", mc.level.dimension().location().toString(), "target", picked.getBlockPos().asLong(),
                    "side", side().name(), "interaction_result", useResult, "observed_ticks_after_use", age - useTick,
                    "denial_count", useDenials, "denial_message", denialMessage));
        }
    }

    private static void walk(Minecraft mc) throws Exception {
        require(++phaseTicks < 360, "Walking check timed out at " + mc.player.position());
        if (!walking) {
            Vec3 start = side() == Direction.SOUTH ? new Vec3(8.5,81,3.5) : new Vec3(10.5,81,1.5);
            if (start.distanceToSqr(mc.player.position()) > .08 * .08) {
                waitFor(mc, "walking start at " + start);
                return;
            }
            mc.options.autoJump().set(false);
            mc.player.setYRot(side() == Direction.SOUTH ? 180 : 90);
            mc.player.setXRot(0);
            walkStart = mc.player.position();
            walking = true;
        }
        mc.options.keyUp.setDown(true);
        ++walkTicks;
        var box = mc.player.getBoundingBox();
        double gap = side() == Direction.SOUTH ? box.minZ - ENTRANCE.getZ() - 1 : box.minX - ENTRANCE.getX() - 1;
        require(gap >= -.003, "Player crossed the cube face");
        contactTicks = mc.player.horizontalCollision && gap < .02 ? contactTicks + 1 : 0;
        if (contactTicks < 5) {
            waitFor(mc, "five consecutive source-block collisions");
            return;
        }
        mc.options.keyUp.setDown(false);
        require(walkStart.distanceTo(mc.player.position()) > .5, "Walking control did not approach the source face");
        ack(Map.of("home_dimension", mc.level.dimension().location().toString(), "held_forward_ticks", walkTicks,
                "contact_ticks", contactTicks, "face_gap", gap, "displacement", walkStart.distanceTo(mc.player.position())));
    }

    private static void ack(Object receipt) throws Exception {
        completed = phase;
        json(phase + "-" + ROLE + "-done.json", receipt);
    }
    private VisualClient() {}
}
