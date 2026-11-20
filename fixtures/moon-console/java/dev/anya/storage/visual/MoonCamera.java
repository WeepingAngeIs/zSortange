package dev.anya.storage.visual;

import com.mojang.blaze3d.platform.InputConstants;
import dev.anya.storage.BagInventory;
import dev.anya.storage.Client;
import dev.anya.storage.Config;
import dev.anya.storage.Content;
import dev.anya.storage.StorageBlock;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.StoreMenu;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import net.minecraft.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.event.PlayLevelSoundEvent;
import org.lwjgl.glfw.GLFW;

@Mod("anyamoonreview")
@EventBusSubscriber(modid = "anyamoonreview", value = Dist.CLIENT)
public final class MoonCamera {
    private enum Step { TITLE, JOIN, PLACED, CLOSED, OPEN, SHUT, TABLET, RIGHT, LEFT, TURN, PAUSE,
        RESUME, USE, INTRO, GRID, RETURN, FINISH, DONE }
    private static final BlockPos CUBE = new BlockPos(0, 80, 0);
    private static final BlockPos HUB = CUBE.west(2);
    private static final Vec3 CENTER = Vec3.atCenterOf(CUBE);
    private static final Set<String> shots = new LinkedHashSet<>();
    private static final List<String> chimes = new ArrayList<>();
    private static Step step = Step.TITLE;
    private static final int[] SAMPLE_TIMES = {0, 55, 115, 190, 330};
    private static final List<String> frameTimes = new ArrayList<>();
    private static int ticks, sequence;
    private static long firstFrame;
    private static CompletableFuture<Void> operation;

    private static void enter(Step next) {
        step = next;
        ticks = 0;
        sequence = 0;
        firstFrame = -1;
        System.out.println("Moon camera " + next);
    }

    private static void require(boolean ok, String reason) {
        if (!ok) throw new IllegalStateException(reason);
    }

    private static void setup(ServerPlayer player) {
        var level = player.serverLevel();
        level.setDayTime(5500);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        for (var pos : BlockPos.betweenClosed(CUBE.offset(-8,-1,-8), CUBE.offset(8,-1,6))) {
            level.setBlockAndUpdate(pos, Blocks.SMOOTH_STONE.defaultBlockState());
        }
        level.setBlockAndUpdate(CUBE.east(2), Content.CRATE.get().defaultBlockState().setValue(StorageBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(CUBE, Content.CUBE.get().defaultBlockState().setValue(StorageBlock.FACING, Direction.NORTH));
        level.setBlockAndUpdate(HUB, Content.HUB.get().defaultBlockState().setValue(StorageBlock.FACING, Direction.NORTH));
        var cube = (StorageEntity) level.getBlockEntity(CUBE);
        var hub = (StorageEntity) level.getBlockEntity(HUB);
        cube.owner = hub.owner = player.getUUID();
        cube.setItem(215, new ItemStack(Items.AMETHYST_SHARD, 17));
        require(cube.getContainerSize() == 216, "Cube capacity");
        require(Config.setTabletOpeningChancePercent(player, Config.get().revision, 100), "Enable a deterministic tablet appearance in this world");
        player.teleportTo(level, -1.25,80,-3.75, -15, 13);
    }

    private static void giveTablet(ServerPlayer player) {
        var tablet = new ItemStack(Content.CUPID_TABLET.get());
        var binding = BagInventory.data(tablet);
        binding.putString("HubDimension", player.level().dimension().location().toString());
        binding.putLong("HubPos", HUB.asLong());
        BagInventory.data(tablet, binding);
        player.setItemInHand(InteractionHand.MAIN_HAND, tablet);
        player.inventoryMenu.broadcastChanges();
    }

    private static void server(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer();
        var id = mc.player.getUUID();
        operation = server.submit(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }

    private static void shot(Minecraft mc, String name) {
        require(shots.add(name), "Duplicate capture " + name);
        Screenshot.grab(mc.gameDirectory, name+".png", mc.getMainRenderTarget(), message -> {});
    }

    @SubscribeEvent public static void sound(PlayLevelSoundEvent.AtPosition event) {
        if (!event.getLevel().isClientSide() || !CENTER.equals(event.getPosition()) || event.getSound() == null) return;
        String id = event.getSound().value().getLocation().toString();
        if (id.startsWith("anyastorage:")) chimes.add(id);
    }

    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) {
        var mc = Minecraft.getInstance();
        boolean opening = step == Step.INTRO && mc.screen instanceof Client.Screen;
        boolean closing = step == Step.RETURN && mc.screen == null;
        if ((!opening && !closing) || sequence >= SAMPLE_TIMES.length) return;
        long now = Util.getMillis();
        if (firstFrame < 0) firstFrame = now;
        long elapsed = now - firstFrame;
        if (elapsed < SAMPLE_TIMES[sequence]) return;
        String name = (opening ? "lift-" : "return-") + sequence++;
        shot(mc, name);
        frameTimes.add(name + " at " + elapsed + " ms");
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || step == Step.DONE) return;
        var mc = Minecraft.getInstance();
        if (operation != null) {
            if (!operation.isDone()) return;
            operation.join();
            operation = null;
        }
        require(++ticks < 2400, "Timed out at " + step);
        switch (step) {
            case TITLE -> {
                if (!(mc.screen instanceof TitleScreen) || mc.getOverlay()!=null) return;
                Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
                require(game.endsWith(Path.of("build","moon-review","client")), "Expected isolated camera directory");
                require(!Files.exists(game.resolve("saves/MoonReview")), "Disposable world must start empty");
                mc.options.guiScale().set(2);
                mc.options.renderDistance().set(5);
                mc.options.simulationDistance().set(5);
                mc.options.fov().set(65);
                mc.options.pauseOnLostFocus = false;
                mc.resizeDisplay();
                enter(Step.JOIN);
                var settings = new LevelSettings("MoonReview", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                    true, new GameRules(), WorldDataConfiguration.DEFAULT);
                mc.createWorldOpenFlows().createFreshLevel("MoonReview", settings, new WorldOptions(3999L,false,false),
                    regs -> regs.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(), mc.screen);
            }
            case JOIN -> {
                if (mc.level == null || mc.player == null || mc.screen != null || mc.getOverlay()!=null) return;
                server(mc, MoonCamera::setup);
                enter(Step.PLACED);
            }
            case PLACED -> {
                if (!(mc.level.getBlockEntity(CUBE) instanceof StorageEntity) || mc.player.getY() < 79.9) return;
                mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, CENTER);
                mc.options.hideGui = true;
                mc.getToasts().clear();
                enter(Step.CLOSED);
            }
            case CLOSED -> {
                if (ticks < 50) return;
                shot(mc,"display-closed");
                server(mc, p -> ((StorageEntity)p.serverLevel().getBlockEntity(CUBE)).openViewer());
                enter(Step.OPEN);
            }
            case OPEN -> {
                var cube = (StorageEntity)mc.level.getBlockEntity(CUBE);
                if (cube.openness < 1 || ticks < 30) return;
                shot(mc,"display-open");
                server(mc, p -> ((StorageEntity)p.serverLevel().getBlockEntity(CUBE)).closeViewer());
                enter(Step.SHUT);
            }
            case SHUT -> {
                if (((StorageEntity)mc.level.getBlockEntity(CUBE)).openness > 0 || ticks < 30) return;
                require(chimes.equals(List.of("anyastorage:cube_open","anyastorage:cube_close")), "Cube sound dispatch " + chimes);
                server(mc, MoonCamera::giveTablet);
                mc.options.hideGui = false;
                enter(Step.TABLET);
            }
            case TABLET -> {
                if (!mc.player.getMainHandItem().is(Content.CUPID_TABLET.get()) || ticks < 15) return;
                mc.options.mainHand().set(net.minecraft.world.entity.HumanoidArm.RIGHT);
                enter(Step.RIGHT);
            }
            case RIGHT -> {
                if (ticks < 20) return;
                shot(mc,"held-right");
                mc.options.mainHand().set(net.minecraft.world.entity.HumanoidArm.LEFT);
                enter(Step.LEFT);
            }
            case LEFT -> {
                if (ticks < 20 || mc.player.getMainArm() != net.minecraft.world.entity.HumanoidArm.LEFT) return;
                shot(mc,"held-left");
                mc.options.mainHand().set(net.minecraft.world.entity.HumanoidArm.RIGHT);
                enter(Step.TURN);
            }
            case TURN -> {
                if (ticks < 12) return;
                mc.player.turn(120,-45);
                if (ticks == 13) { shot(mc,"held-turn"); mc.pauseGame(false); enter(Step.PAUSE); }
            }
            case PAUSE -> {
                if (!(mc.screen instanceof PauseScreen) || ticks < 8) return;
                shot(mc,"held-pause");
                mc.setScreen(null);
                enter(Step.RESUME);
            }
            case RESUME -> {
                if (ticks < 8) return;
                mc.player.setXRot(-24);
                enter(Step.USE);
            }
            case USE -> {
                if (ticks < 4) return;
                KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
                enter(Step.INTRO);
            }
            case INTRO -> {
                if (!(mc.screen instanceof Client.Screen) || !(mc.player.containerMenu instanceof StoreMenu menu) || ticks < 30) return;
                require(menu.showsTabletIntro(), "Tablet did not receive server presentation flag");
                enter(Step.GRID);
            }
            case GRID -> {
                if (ticks < 12) return;
                shot(mc,"tablet-inventory");
                mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);
                require(mc.screen == null, "Escape must release the inventory immediately");
                enter(Step.RETURN);
            }
            case RETURN -> {
                if (ticks < 15 || sequence < SAMPLE_TIMES.length) return;
                shot(mc,"held-returned");
                enter(Step.FINISH);
            }
            case FINISH -> {
                if (ticks < 6 || shots.stream().anyMatch(name -> !Files.isRegularFile(mc.gameDirectory.toPath().resolve("screenshots/"+name+".png")))) return;
                require(shots.contains("lift-4") && shots.contains("return-4"), "Missing transition framebuffer");
                var loaded = ModList.get().getModContainerById("anyastorage").orElseThrow().getModInfo().getOwningFile().getFile().getFilePath();
                require(Files.isRegularFile(loaded) && loaded.toString().endsWith(".jar"), "Expected packaged mod");
                String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(loaded)));
                Files.writeString(mc.gameDirectory.toPath().resolve("camera.txt"), "JAR SHA-256 " + hash + "\n" + chimes
                    + "\nRenderer " + org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER)
                    + "\n" + String.join(", ",shots) + "\n" + String.join("\n",frameTimes) + "\n");
                enter(Step.DONE);
                mc.stop();
            }
            default -> {}
        }
    }
}
