package dev.anya.storage.visual;

import com.mojang.blaze3d.platform.InputConstants;
import dev.anya.storage.BagInventory;
import dev.anya.storage.Client;
import dev.anya.storage.Config;
import dev.anya.storage.Content;
import dev.anya.storage.StorageBlock;
import dev.anya.storage.StorageEntity;
import dev.anya.storage.StoreMenu;
import dev.anya.storage.appearance.TabletDevice;
import dev.anya.storage.ui.StorageGrid;
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
import net.minecraft.client.renderer.item.ItemProperties;
import net.minecraft.resources.ResourceLocation;
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
import net.minecraft.world.phys.BlockHitResult;
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
    private enum Step { TITLE, JOIN, PLACED, GROUND_MODEL, GLOBE_MODEL, CLOSED, OPEN, SHUT, TABLET, EQUIP, AWAY, RESELECT, RIGHT, LEFT, TURN, PAUSE,
        RESUME, USE_TABLET, INTRO, GRID, TOOLTIP_ON, TOOLTIP_OFF, RETURN, CREATIVE_ITEM, CREATIVE_EQUIP, CREATIVE_USE, CREATIVE_INTRO, CREATIVE_GRID, CREATIVE_RETURN, RESTORE, FINISH, DONE }
    private static final boolean EXPRESSIONS="true".equals(System.getenv("GITHUB_ACTIONS")) && "1".equals(System.getenv("ANYA_EXPRESSION_REVIEW"));
    private static final boolean EDITOR="true".equals(System.getenv("GITHUB_ACTIONS")) && "1".equals(System.getenv("ANYA_ROOM_EDITOR_REVIEW"));
    private static final boolean CHESTS="true".equals(System.getenv("GITHUB_ACTIONS")) && "1".equals(System.getenv("ANYA_CHEST_REVIEW"));
    private static final int TABLET_SLOT = 1, CREATIVE_SLOT = 2;
    private static final ResourceLocation FACE = ResourceLocation.fromNamespaceAndPath("anyastorage", "cupid_face");
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
        RootChecks.setup(player);
    }

    private static void giveTablet(ServerPlayer player) {
        var tablet = new ItemStack(Content.CUPID_TABLET.get());
        var binding = BagInventory.data(tablet);
        binding.putString("HubDimension", player.level().dimension().location().toString());
        binding.putLong("HubPos", HUB.asLong());
        BagInventory.data(tablet, binding);
        player.getInventory().setItem(TABLET_SLOT, tablet);
        player.inventoryMenu.broadcastChanges();
    }

    private static void server(Minecraft mc, Consumer<ServerPlayer> action) {
        var server = mc.getSingleplayerServer();
        var id = mc.player.getUUID();
        operation = server.submit(() -> action.accept(server.getPlayerList().getPlayer(id)));
    }

    private static StorageGrid.Layout grid(Minecraft mc, StoreMenu menu) {
        int width = mc.getWindow().getGuiScaledWidth(), height = mc.getWindow().getGuiScaledHeight();
        return TabletDevice.fitMenu(StorageGrid.layout(width, height, menu.resultCount(), menu.storageView()), width, height);
    }

    private static void shot(Minecraft mc, String name) {
        require(shots.add(name), "Duplicate capture " + name);
        Screenshot.grab(mc.gameDirectory, name+".png", mc.getMainRenderTarget(), message -> {});
    }

    // Sample only the inner portion of the resting tablet's glass. The dark glass, metal,
    // sky and skin lack the saturated blue ink of Cupid's authored display.
    private static boolean portraitVisible(Minecraft mc) {
        try (var pixels = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
            int blue = 0, probes = 0;
            for (int y = pixels.getHeight()*81/100; y < pixels.getHeight()*94/100; y += 4) {
                for (int x = pixels.getWidth()*69/100; x < pixels.getWidth()*85/100; x += 4) {
                    int color = pixels.getPixelRGBA(x, y);
                    int r = color & 255, g = (color >>> 8) & 255, b = (color >>> 16) & 255;
                    if (r < 146 && b > 83 && b-r > 54 && g-r > 22 && b-g > 18) blue++;
                    probes++;
                }
            }
            return blue > probes / 10;
        }
    }

    @SubscribeEvent public static void warningSound(net.neoforged.neoforge.client.event.sound.PlaySoundEvent event) {
        if(EXPRESSIONS)WarningCamera.sound(event);
    }
    @SubscribeEvent public static void sound(PlayLevelSoundEvent.AtPosition event) {
        if (!event.getLevel().isClientSide() || !CENTER.equals(event.getPosition()) || event.getSound() == null) return;
        String id = event.getSound().value().getLocation().toString();
        if (id.startsWith("anyastorage:")) chimes.add(id);
    }

    @SubscribeEvent public static void frame(RenderFrameEvent.Post event) throws ReflectiveOperationException {
        if (EDITOR || CHESTS) return;
        var mc = Minecraft.getInstance();
        if(EXPRESSIONS){ExpressionCamera.frame(mc);return;}
        boolean creativeEquip = step == Step.CREATIVE_EQUIP;
        if ((step == Step.EQUIP || creativeEquip) && mc.player != null
                && mc.player.getInventory().selected == (creativeEquip ? CREATIVE_SLOT : TABLET_SLOT)) {
            var stack = mc.player.getMainHandItem();
            var property = ItemProperties.getProperty(stack, FACE);
            require(property != null, "Missing Cupid item override");
            int value = Math.round(4 * property.call(stack, mc.level, mc.player, 0));
            String name = switch (value) {
                case 1 -> "held-squint";
                case 2 -> "held-shut";
                case 3 -> "held-glance";
                case 0 -> "held-dark";
                default -> throw new IllegalStateException("Unexpected tablet state " + value);
            };
            if (creativeEquip) name = "creative-" + name;
            // Wait until vanilla's equip animation actually hands off to the physical model.
            if (!shots.contains(name) && (value != 1 || portraitVisible(mc))) shot(mc, name);
        }
        boolean opening = (step == Step.INTRO || step == Step.CREATIVE_INTRO) && mc.screen instanceof Client.Screen;
        boolean closing = (step == Step.RETURN || step == Step.CREATIVE_RETURN) && mc.screen == null;
        if ((!opening && !closing) || sequence >= SAMPLE_TIMES.length) return;
        long now = Util.getMillis();
        if (firstFrame < 0) firstFrame = now;
        long elapsed = now - firstFrame;
        if (elapsed < SAMPLE_TIMES[sequence]) return;
        String name = (opening ? "lift-" : "return-") + sequence++;
        if (step == Step.CREATIVE_INTRO || step == Step.CREATIVE_RETURN) name = "creative-" + name;
        shot(mc, name);
        frameTimes.add(name + " at " + elapsed + " ms");
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) throws Exception {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || step == Step.DONE) return;
        var mc = Minecraft.getInstance();
        if(CHESTS && step==Step.JOIN){ChestCamera.tick(mc);return;}
        if(EDITOR && step==Step.JOIN){EditorCamera.tick(mc);return;}
        if(EXPRESSIONS && step==Step.JOIN){ExpressionCamera.tick(mc);return;}
        if (operation != null) {
            if (!operation.isDone()) return;
            operation.join();
            operation = null;
        }
        require(++ticks < 3200, "Timed out at " + step);
        switch (step) {
            case TITLE -> {
                if (!(mc.screen instanceof TitleScreen) || mc.getOverlay()!=null) return;
                Path game = mc.gameDirectory.toPath().toAbsolutePath().normalize();
                Path expected=CHESTS ? Path.of("build","chest-review",ChestCamera.withEmi()?"emi":"plain") : Path.of("build","moon-review","client");
                require(game.endsWith(expected), "Expected isolated camera directory: " + expected);
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
                mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(GroundBagCheck.BAG));
                mc.player.getInventory().selected = 0;
                mc.options.hideGui = true;
                mc.getToasts().clear();
                enter(Step.GROUND_MODEL);
            }
            case GROUND_MODEL -> {
                if (ticks < 25 || !(mc.level.getBlockEntity(GroundBagCheck.BAG) instanceof dev.anya.storage.PlacedBackpackEntity bag)
                        || bag.displayStack().isEmpty()) return;
                require(dev.anya.storage.appearance.Looks.of(bag.displayStack()).texture().equals("galaxy"), "Expected the public galaxy bag");
                shot(mc, "display-backpack");
                mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(CUBE.east()));
                enter(Step.GLOBE_MODEL);
            }
            case GLOBE_MODEL -> {
                if (ticks < 15) return;
                shot(mc, "display-globe");
                mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, CENTER);
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
                var stack = mc.player.getInventory().getItem(TABLET_SLOT);
                if (!stack.is(Content.CUPID_TABLET.get()) || ticks < 8) return;
                require(mc.player.getInventory().selected == 0 && !mc.player.getMainHandItem().is(Content.CUPID_TABLET.get()),
                        "The tablet must begin in an unselected hotbar slot");
                var property = ItemProperties.getProperty(stack, FACE);
                require(property != null && property.call(stack, mc.level, mc.player, 0) == .25f, "Missing unselected squint");
                mc.options.mainHand().set(net.minecraft.world.entity.HumanoidArm.RIGHT);
                mc.gui.getChat().clearMessages(true);
                shot(mc,"hotbar-squint");
                mc.player.getInventory().selected = TABLET_SLOT;
                enter(Step.EQUIP);
            }
            case EQUIP -> {
                if (!shots.contains("held-dark") || ticks < 20) return;
                require(shots.containsAll(List.of("held-squint", "held-shut", "held-glance")), "Incomplete equip exit " + shots);
                mc.player.getInventory().selected = 0;
                enter(Step.AWAY);
            }
            case AWAY -> {
                if (ticks < 5) return;
                shot(mc,"hotbar-dark");
                mc.player.getInventory().selected = TABLET_SLOT;
                enter(Step.RESELECT);
            }
            case RESELECT -> {
                if (ticks < 6) return;
                var stack = mc.player.getMainHandItem();
                require(stack.is(Content.CUPID_TABLET.get()) && ItemProperties.getProperty(stack, FACE).call(stack, mc.level, mc.player, 0) == 0,
                        "Selecting the tablet again replayed the visit");
                shot(mc,"held-reselected");
                enter(Step.RIGHT);
            }
            case RIGHT -> {
                if (ticks < 55) return;
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
                mc.player.lookAt(EntityAnchorArgument.Anchor.EYES, new Vec3(CENTER.x, 81.9, CENTER.z));
                enter(Step.USE_TABLET);
            }
            case USE_TABLET -> {
                if (ticks < 4) return;
                KeyMapping.click(InputConstants.Type.MOUSE.getOrCreate(GLFW.GLFW_MOUSE_BUTTON_RIGHT));
                enter(Step.INTRO);
            }
            case INTRO -> {
                if (!(mc.screen instanceof Client.Screen) || !(mc.player.containerMenu instanceof StoreMenu menu) || ticks < 30 || sequence < SAMPLE_TIMES.length) return;
                require(menu.showsTabletIntro(), "Tablet did not receive server presentation flag");
                GLFW.glfwSetCursorPos(mc.getWindow().getWindow(),4,4);
                enter(Step.GRID);
            }
            case GRID -> {
                if (ticks < 12) return;
                shot(mc,"tablet-inventory");
                var menu = (StoreMenu)mc.player.containerMenu;
                require(menu.tooltipsEnabled() && menu.shownSlots() > 0, "Expected usable storage with tooltips initially enabled");
                var layout = grid(mc, menu);
                var slot = layout.storage(0);
                double scale = mc.getWindow().getGuiScale();
                GLFW.glfwSetCursorPos(mc.getWindow().getWindow(),
                        (layout.left() + (slot.x() + 8) * layout.scale()) * scale,
                        (layout.top() + (slot.y() + 8) * layout.scale()) * scale);
                enter(Step.TOOLTIP_ON);
            }
            case TOOLTIP_ON -> {
                if (ticks < 6) return;
                shot(mc, "tooltips-on");
                var menu = (StoreMenu)mc.player.containerMenu;
                var layout = grid(mc, menu);
                require(mc.screen.mouseClicked(layout.left() + 322 * layout.scale(), layout.top() + 19 * layout.scale(), 0),
                        "Visible tooltip control did not accept a primary click");
                enter(Step.TOOLTIP_OFF);
            }
            case TOOLTIP_OFF -> {
                var menu = (StoreMenu)mc.player.containerMenu;
                if (menu.tooltipsEnabled() || ticks < 12) return;
                shot(mc, "tooltips-off");
                mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE,0,0);
                require(mc.screen == null, "Escape must release the inventory immediately");
                enter(Step.RETURN);
            }
            case RETURN -> {
                if (ticks < 15 || sequence < SAMPLE_TIMES.length) return;
                shot(mc,"held-returned");
                server(mc, p -> {
                    p.getInventory().setItem(CREATIVE_SLOT, new ItemStack(Content.CREATIVE_TABLET.get()));
                    p.inventoryMenu.broadcastChanges();
                });
                enter(Step.CREATIVE_ITEM);
            }
            case CREATIVE_ITEM -> {
                var tablet = mc.player.getInventory().getItem(CREATIVE_SLOT);
                if (!tablet.is(Content.CREATIVE_TABLET.get()) || ticks < 8) return;
                var property = ItemProperties.getProperty(tablet, FACE);
                require(property != null && property.call(tablet, mc.level, mc.player, 0) == .25f,
                        "Expected a Creative Tablet squint in its own unselected slot");
                shot(mc, "creative-hotbar-squint");
                mc.player.getInventory().selected = CREATIVE_SLOT;
                enter(Step.CREATIVE_EQUIP);
            }
            case CREATIVE_EQUIP -> {
                if (ticks < 20 || !shots.contains("creative-held-dark")) return;
                require(shots.containsAll(List.of("creative-held-squint", "creative-held-shut", "creative-held-glance")),
                        "Incomplete Creative Tablet equip visit");
                server(mc, p -> {
                    require(p.getMainHandItem().is(Content.CREATIVE_TABLET.get()), "Creative Tablet selection sync");
                    p.setShiftKeyDown(true);
                    try {
                        var hit = new BlockHitResult(Vec3.atCenterOf(HUB).relative(Direction.NORTH, .5), Direction.NORTH, HUB, false);
                        p.gameMode.useItemOn(p, p.serverLevel(), p.getMainHandItem(), InteractionHand.MAIN_HAND, hit);
                    } finally {
                        p.setShiftKeyDown(false);
                    }
                    require(BagInventory.data(p.getMainHandItem()).contains("HubPos"), "Ordinary crouch-bind did not link the Creative Tablet");
                });
                enter(Step.CREATIVE_USE);
            }
            case CREATIVE_USE -> {
                if (ticks < 8) return;
                mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                enter(Step.CREATIVE_INTRO);
            }
            case CREATIVE_INTRO -> {
                if (!(mc.screen instanceof Client.Screen) || !(mc.player.containerMenu instanceof StoreMenu menu)
                        || sequence < SAMPLE_TIMES.length || ticks < 30) return;
                require(menu.creativeTabletOpening() && menu.showsTabletIntro(), "Expected a Creative Tablet opening and blink");
                require(!menu.tooltipsEnabled(), "Personal tooltip preference lost on Creative Tablet");
                enter(Step.CREATIVE_GRID);
            }
            case CREATIVE_GRID -> {
                if (ticks < 10) return;
                shot(mc, "creative-inventory");
                mc.screen.keyPressed(GLFW.GLFW_KEY_ESCAPE, 0, 0);
                require(mc.screen == null, "Creative Tablet Escape");
                enter(Step.CREATIVE_RETURN);
            }
            case CREATIVE_RETURN -> {
                if (ticks < 15 || sequence < SAMPLE_TIMES.length) return;
                shot(mc, "creative-returned");
                mc.player.getInventory().selected = TABLET_SLOT;
                enter(Step.RESTORE);
            }
            case RESTORE -> {
                if (ticks < 8 || !mc.player.getMainHandItem().is(Content.CUPID_TABLET.get())) return;
                enter(Step.FINISH);
            }
            case FINISH -> {
                if (!RootChecks.run(mc)) return;
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
