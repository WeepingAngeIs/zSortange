package probe;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.anya.storage.BackpackHistory;
import dev.anya.storage.BagInventory;
import dev.anya.storage.Content;
import dev.anya.storage.PlacedBackpackEntity;
import dev.anya.storage.Upgrades;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@Mod("anyabagreview")
@EventBusSubscriber(modid="anyabagreview")
public final class HistoryReview {
    private static final String[] BAGS = {"carried", "chest", "missing", "damage", "timer", "placed", "ground", "conflict", "voidfall", "cursor"};
    private static final String CMD = "anya backpack history";
    private static final BlockPos CHEST = new BlockPos(7, 80, 3);
    private static final BlockPos SHELF = new BlockPos(1, 81, 3);
    private static final Path ORACLE = Path.of("history-check-oracle.nbt");
    private static final List<String> evidence = new ArrayList<>();
    private static MinecraftServer server;
    private static String phase;
    private static int ticks;
    private static Actor owner, nextOwner, op, secondOp;
    private static CompoundTag oracle;
    private static ItemEntity loose;
    private static ItemEntity extending;
    private static boolean extensionSeen;

    private record Actor(ServerPlayer player, List<Component> chat, EmbeddedChannel channel) {}

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalStateException(message);
    }

    private static UUID playerId(String name) {
        return UUID.nameUUIDFromBytes(("history-test-" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static Actor join(String name, int x, boolean operator) {
        var level = server.overworld();
        var profile = new GameProfile(playerId(name), name);
        var chat = new ArrayList<Component>();
        var connection = new Connection(PacketFlow.SERVERBOUND) {
            @Override public boolean isMemoryConnection() { return true; }
            @Override public void send(Packet<?> packet, PacketSendListener callback, boolean flush) {
                if (packet instanceof ClientboundSystemChatPacket message) chat.add(message.content());
            }
        };
        var player = new ServerPlayer(server, level, profile, ClientInformation.createDefault());
        var channel = new EmbeddedChannel(connection);
        NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection, player, CommonListenerCookie.createInitial(profile, false));
        if (operator) server.getPlayerList().op(profile);
        level.setBlockAndUpdate(new BlockPos(x, 80, 3), Blocks.SMOOTH_STONE.defaultBlockState());
        player.teleportTo(level, x + .5, 81, 3.5, 0, 0);
        player.setGameMode(GameType.SURVIVAL);
        return new Actor(player, chat, channel);
    }

    private static int command(Actor who, String command) throws CommandSyntaxException {
        return server.getCommands().getDispatcher().execute(command, who.player.createCommandSourceStack());
    }

    private static void denied(Actor who, String command) throws Exception {
        try { require(command(who, command) == 0, "Unauthorized command was accepted"); }
        catch (CommandSyntaxException expected) { /* Brigadier hides unauthorized nodes. */ }
    }

    private static UUID id(ItemStack stack) {
        var data = BagInventory.data(stack);
        return data.hasUUID("BagId") ? data.getUUID("BagId") : null;
    }

    private static ItemStack original(String name) {
        return ItemStack.parseOptional(server.registryAccess(), oracle.getCompound(name));
    }

    private static UUID id(String name) { return id(original(name)); }

    private static BackpackHistory.Row row(String name) {
        for (int page = 0; page < 8; page++) {
            var rows = BackpackHistory.page(op.player, page);
            for (var result : rows) if (id(name).equals(result.id())) return result;
            if (rows.isEmpty()) break;
        }
        return null;
    }

    private static void unavailable(String name) {
        var found = row(name);
        require(found == null || !found.restorable(), "Unexpected recoverable " + name);
        UUID attempt = found != null && found.lossId() != null ? found.lossId() : UUID.randomUUID();
        BackpackHistory.restore(op.player, id(name), attempt);
        var after = row(name);
        require(after == null || !after.restorable(), "Rejected recovery changed eligibility for " + name);
    }

    private static int slot(Actor who, String name) {
        var inv = who.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (id(name).equals(id(inv.getItem(i)))) return i;
        return -1;
    }

    private static int copies(Actor who, String name) {
        if (who == null) return 0;
        var inv = who.player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            var stack = inv.getItem(i);
            if (id(name).equals(id(stack))) count += stack.getCount();
        }
        return count;
    }

    private static void exact(Actor who, String name, int count) {
        require(copies(who, name) == count, "Wrong count of " + name + " for " + who.player.getScoreboardName());
        if (count == 1) require(ItemStack.matches(original(name), who.player.getInventory().getItem(slot(who, name))),
                "Components, contents or identity changed for " + name);
    }

    private static List<ItemEntity> drops(String name) {
        var result = new ArrayList<ItemEntity>();
        for (var entity : server.overworld().getAllEntities())
            if (entity instanceof ItemEntity item && item.isAlive() && id(name).equals(id(item.getItem()))) result.add(item);
        return result;
    }

    private static ItemEntity toss(Actor who, String name) {
        int index = slot(who, name);
        var inventory = who.player.getInventory();
        require(index >= 0, "Missing bag to toss: " + name);
        if (index >= 9) {
            int free = inventory.getFreeSlot();
            require(free >= 0 && free < 9, "No free hotbar slot for the fixture toss");
            inventory.setItem(free, inventory.removeItemNoUpdate(index));
            index = free;
        }
        inventory.selected = index;
        require(who.player.drop(true), "Server rejected normal player toss: " + name);
        var entities = drops(name);
        require(entities.size() == 1, "Expected exactly one dropped " + name);
        var item = entities.get(0);
        item.setNeverPickUp();
        return item;
    }

    private static void destroy(Actor who, String name) {
        var item = toss(who, name);
        require(item.hurt(item.damageSources().cactus(), 100) && item.isRemoved(), "Item resisted fatal cactus damage");
    }

    private static String restoreCommand(String name, UUID loss) { return CMD + " restore " + id(name) + " " + loss; }

    private static boolean containsLink(Component component, String command) {
        var click = component.getStyle().getClickEvent();
        if (click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND && click.getValue().equals("/" + command)) return true;
        return component.getSiblings().stream().anyMatch(child -> containsLink(child, command));
    }

    @SubscribeEvent
    public static void start(ServerStartedEvent event) throws Exception {
        phase = System.getenv("ANYA_BAG_PHASE");
        require("true".equals(System.getenv("GITHUB_ACTIONS")) && List.of("seed", "queue", "deliver", "replay", "receipts").contains(phase),
                "Disposable hosted server required");
        server = event.getServer();
        var actual = ModList.get().getModContainerById("anyastorage").orElseThrow().getModInfo().getOwningFile().getFile().getFilePath();
        require(Files.isRegularFile(actual) && Files.isSameFile(actual, Path.of(System.getProperty("anya.bagHistoryPlayable"))),
                "The server did not load the frozen candidate JAR");
        evidence.add(phase + " " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(actual))));
        evidence.add("JVM " + ProcessHandle.current().pid());
        op = join("HistoryOp", 10, true);
        secondOp = join("SecondOp", 14, true);
        server.overworld().getChunkAt(CHEST);
        if ("seed".equals(phase)) {
            oracle = new CompoundTag();
            require(!Files.exists(ORACLE), "Fresh world required");
            owner = join("HistoryOwner", 2, false);
            nextOwner = join("OtherHolder", 5, false);
            owner.player.getInventory().clearContent();
            nextOwner.player.getInventory().clearContent();
            for (int i = 0; i < BAGS.length; i++) createBag(BAGS[i], i);
            owner.player.getInventory().selected = 0;
        } else {
            oracle = NbtIo.readCompressed(ORACLE, NbtAccounter.create(16L << 20));
            if ("deliver".equals(phase) || "replay".equals(phase) || "receipts".equals(phase)) {
                owner = join("HistoryOwner", 2, false);
                nextOwner = join("OtherHolder", 5, false);
            }
        }
    }

    private static void createBag(String name, int index) {
        var inv = owner.player.getInventory();
        var host = new ItemStack(Content.BACKPACK.get());
        host.set(DataComponents.CUSTOM_NAME, Component.literal("Fixture " + name));
        inv.setItem(index, host);
        if (index < 9) inv.selected = index;
        var bag = new BagInventory(owner.player, index);
        var nested = new CompoundTag();
        nested.putString("label", name);
        nested.putIntArray("values", new int[]{47, 3, 81});
        var metadata = new CompoundTag();
        metadata.put("nested", nested);
        var item = new ItemStack(Items.AMETHYST_SHARD, 13 + index);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Etched " + name));
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(metadata));
        bag.setItem(3, item);
        if (name.equals("damage") || name.equals("timer")) {
            var disc = new ItemStack(Content.UPGRADES.get("feeding").get());
            disc.set(DataComponents.CUSTOM_NAME, Component.literal("Personal feeding module " + name));
            disc.set(DataComponents.CUSTOM_DATA, CustomData.of(metadata));
            inv.setItem(9, disc);
            require(Upgrades.change(bag, "feeding", false), "Named upgrade installation failed");
            require(Upgrades.installed(new BagInventory(owner.player, index), "feeding"), "Installed disk missing");
        }
        oracle.put(name, inv.getItem(index).save(owner.player.registryAccess()));
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) throws Exception {
        if (phase == null) return;
        ticks++;
        switch (phase) {
            case "seed" -> seed();
            case "queue" -> queue();
            case "deliver" -> deliver();
            case "replay" -> replay();
            case "receipts" -> receipts();
        }
        if (ticks > 900) throw new IllegalStateException("Backpack history fixture timed out");
    }

    @SubscribeEvent
    public static void extendOnce(ItemExpireEvent event) {
        if (event.getEntity() == extending && !extensionSeen) {
            event.addExtraLife(35);
            extensionSeen = true;
        }
    }

    private static void seed() throws Exception {
        if (ticks == 100) {
            require(command(op, CMD) == 1, "Operator could not list history");
            denied(owner, CMD);
            require(BackpackHistory.page(owner.player, 0).isEmpty(), "History leaked to a non-operator");
            unavailable("carried");
            exact(owner, "carried", 1);
            var level = server.overworld();
            level.setBlockAndUpdate(CHEST, Blocks.CHEST.defaultBlockState());
            var chest = (ChestBlockEntity)level.getBlockEntity(CHEST);
            chest.setItem(0, owner.player.getInventory().removeItemNoUpdate(slot(owner, "chest")));
            chest.setChanged();
            owner.player.getInventory().removeItemNoUpdate(slot(owner, "missing"));
            // This inventory removal has no recorded destruction and must never create a return.
            owner.player.getInventory().selected = slot(owner, "placed");
            level.setBlockAndUpdate(SHELF.below(), Blocks.STONE.defaultBlockState());
            owner.player.setShiftKeyDown(true);
            owner.player.gameMode.useItemOn(owner.player, level, owner.player.getMainHandItem(), InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(SHELF.below()).add(0,.5,0), Direction.UP, SHELF.below(), false));
            owner.player.setShiftKeyDown(false);
            require(level.getBlockEntity(SHELF) instanceof PlacedBackpackEntity, "Normal crouch-placement failed");
            var transfer = toss(owner, "timer");
            transfer.setNoPickUpDelay();
            transfer.playerTouch(nextOwner.player);
            exact(nextOwner, "timer", 1);
            loose = toss(owner, "ground");
            loose.setPos(3.5, 82, 3.5);
            loose.setDeltaMovement(Vec3.ZERO);
            loose.setNoGravity(true);
            destroy(owner, "damage");
            destroy(owner, "conflict");
            destroy(owner, "cursor");
            var falling = toss(owner, "voidfall");
            falling.setPos(falling.getX(), level.getMinBuildHeight() - 100.0, falling.getZ());
            var orphan = new ItemStack(Content.BACKPACK.get());
            BagInventory.initialize(orphan);
            oracle.put("orphan", orphan.save(server.registryAccess()));
            var anonymous = new ItemEntity(level, 4.5, 83, 3.5, orphan);
            require(level.addFreshEntity(anonymous) && anonymous.hurt(anonymous.damageSources().cactus(), 100)
                    && anonymous.isRemoved(), "Anonymous item destruction did not occur");
        }
        if (ticks == 205) {
            for (String name : List.of("carried", "chest", "missing", "ground", "placed", "timer")) unavailable(name);
            require(drops("ground").size() == 1, "A live loose item was consumed by recovery");
            extending = toss(nextOwner, "timer");
            extending.lifespan = 1; // Expire via the real ItemEntity.tick and NeoForge expiry event.
        }
        if (ticks == 220) {
            require(extensionSeen && extending.isAlive(), "Another mod's normal item lifetime extension was ignored");
            unavailable("timer");
            unavailable("orphan");
        }
        if (ticks == 280) {
            command(op, CMD);
            for (String name : List.of("damage", "timer", "voidfall")) {
                var loss = row(name);
                require(loss != null && loss.restorable() && loss.lossId() != null, "Missing witnessed " + name + " loss");
                Actor expectedOwner = name.equals("timer") ? nextOwner : owner;
                require(loss.owner().equals(expectedOwner.player.getUUID())
                        && loss.ownerName().equals(expectedOwner.player.getGameProfile().getName()), "Wrong last holder of " + name);
                require(loss.time() > 0 && loss.bagName().contains(name), "Missing name or timestamp in history");
                oracle.putUUID(name + "-loss", loss.lossId());
                require(drops(name).isEmpty() && copies(owner, name) + copies(nextOwner, name) == 0, "Destroyed original still present");
            }
            op.chat.clear();
            owner.chat.clear();
            command(op, CMD);
            require(op.chat.stream().anyMatch(c -> containsLink(c, restoreCommand("damage", oracle.getUUID("damage-loss")))),
                    "Operator chat omitted the clickable recovery action");
            require(owner.chat.stream().noneMatch(c -> c.getString().contains("Fixture damage")), "Private history was broadcast");
            command(op, restoreCommand("voidfall", oracle.getUUID("voidfall-loss")));
            exact(owner, "voidfall", 1);
            var conflict = row("conflict");
            require(conflict != null && conflict.restorable(), "Live-presence check needs a real listed loss first");
            // Simulate a player's original reappearing after the operator already received a valid link.
            var chest = (ChestBlockEntity)server.overworld().getBlockEntity(CHEST);
            chest.setItem(1, original("conflict"));
            chest.setChanged();
            command(op, restoreCommand("conflict", conflict.lossId()));
            exact(owner, "conflict", 0);
            var cursorLoss = row("cursor");
            require(cursorLoss != null && cursorLoss.restorable(), "Nested cursor test needs its own listed loss");
            var shell = new ItemStack(Items.SHULKER_BOX);
            shell.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(original("cursor"))));
            require(op.player.containerMenu.getCarried().isEmpty(), "Expected an empty menu cursor");
            op.player.containerMenu.setCarried(shell);
            command(op, restoreCommand("cursor", cursorLoss.lossId()));
            exact(owner, "cursor", 0);
            var recoveredShell = op.player.containerMenu.getCarried();
            require(ItemStack.matches(shell, recoveredShell) && row("cursor") == null,
                    "A bag present only inside a cursor stack failed to close its recovery");
            op.player.containerMenu.setCarried(ItemStack.EMPTY);
            chest.setItem(3, recoveredShell);
            chest.setChanged();
            evidence.add("PASS Normal carried, chest-stored, placed, loose, transferred and unproven-missing bags never became returns.");
            evidence.add("PASS Fatal item damage, final lifespan expiry and a dropped bag ticking below the world's lower boundary recorded their original owners. The void bag was returned intact. Extended lifetime and unowned losses created no entitlement. Operator-only clickable history stayed private.");
            HistoryDiskChecks.expiry(owner.player, original("damage"));
            evidence.add("PASS The persisted journal reopened immediately before and exactly at the 30-day UTC cutoff.");
            finish();
        }
    }

    private static void queue() throws Exception {
        if (ticks == 10) {
            var damageLoss = oracle.getUUID("damage-loss");
            require(row("damage") != null && row("damage").restorable(), "Lost damage escrow across restart");
            require(row("timer") != null && row("timer").restorable(), "Lost expiry escrow across restart");
            require(server.getPlayerList().getPlayer(playerId("HistoryOwner")) == null, "Owner should be offline");
            var dataFile = Path.of("history-world", "playerdata", playerId("HistoryOwner") + ".dat");
            byte[] before = Files.readAllBytes(dataFile);
            command(op, restoreCommand("damage", damageLoss));
            command(secondOp, restoreCommand("damage", damageLoss));
            require(java.util.Arrays.equals(before, Files.readAllBytes(dataFile)), "Operator request edited offline playerdata");
            require(drops("damage").isEmpty() && copies(op, "damage") + copies(secondOp, "damage") == 0, "Offline return leaked items");
            nextOwner = join("OtherHolder", 5, false);
            var inv = nextOwner.player.getInventory();
            for (int i = 0; i < 36; i++) inv.setItem(i, new ItemStack(Items.COBBLESTONE, 64));
            denied(nextOwner, restoreCommand("timer", oracle.getUUID("timer-loss")));
            BackpackHistory.restore(nextOwner.player, id("timer"), oracle.getUUID("timer-loss"));
            require(row("timer").restorable(), "Unauthorized API request changed the journal");
            server.getPlayerList().deop(secondOp.player.getGameProfile());
            denied(secondOp, restoreCommand("timer", oracle.getUUID("timer-loss")));
            require(BackpackHistory.page(secondOp.player, 0).isEmpty(), "Revoked operator retained history access");
            command(op, restoreCommand("timer", oracle.getUUID("timer-loss")));
            command(op, restoreCommand("timer", oracle.getUUID("timer-loss")));
            exact(nextOwner, "timer", 0);
            require(drops("timer").isEmpty(), "Full inventory return dropped to ground");
        }
        if (ticks == 90) {
            exact(nextOwner, "timer", 0);
            for (int i = 0; i < 36; i++) {
                var stack = nextOwner.player.getInventory().getItem(i);
                require(stack.is(Items.COBBLESTONE) && stack.getCount() == 64, "Recovery displaced inventory during wait");
            }
            evidence.add("PASS Repeated operator requests in one server tick queued a single return per offline/full owner. Offline playerdata stayed byte-identical, occupied slots retained every filler stack, and no item was dropped.");
            finish();
        }
    }

    private static void deliver() throws Exception {
        if (ticks == 60) {
            exact(nextOwner, "timer", 0);
            require(drops("timer").isEmpty(), "Restart spilled pending full inventory return");
            nextOwner.player.getInventory().removeItemNoUpdate(4);
        }
        if (ticks == 205) {
            exact(owner, "damage", 1);
            exact(owner, "timer", 0);
            exact(nextOwner, "damage", 0);
            exact(nextOwner, "timer", 1);
            require(slot(nextOwner, "timer") == 4 && drops("timer").isEmpty() && drops("damage").isEmpty(), "Bad retry destination");
            // Create a second real loss of the same immutable identity after the original was delivered.
            destroy(owner, "damage");
        }
        if (ticks == 285) {
            command(op, CMD);
            var next = row("damage");
            UUID old = oracle.getUUID("damage-loss");
            require(next != null && next.restorable() && !old.equals(next.lossId()), "Second loss reused a stale action");
            command(op, restoreCommand("damage", old));
            exact(owner, "damage", 0);
            require(row("damage").restorable(), "An old chat link changed the new loss");
            server.getPlayerList().op(secondOp.player.getGameProfile());
            command(secondOp, restoreCommand("damage", next.lossId()));
            command(op, restoreCommand("damage", next.lossId()));
            oracle.putUUID("damage-second-loss", next.lossId());
        }
        if (ticks == 390) {
            exact(owner, "damage", 1);
            exact(nextOwner, "timer", 1);
            evidence.add("PASS Queued offline/full returns survived another server restart. Login or one free slot delivered each exact original without a second request.");
            evidence.add("PASS Nonoperators and a deopped operator were denied; repeated requests from two operators in the same server tick yielded only one return. A stale chat action could not consume a second loss.");
            finish();
        }
    }

    private static void replay() throws Exception {
        if (ticks == 70) {
            command(op, restoreCommand("damage", oracle.getUUID("damage-loss")));
            command(secondOp, restoreCommand("damage", oracle.getUUID("damage-second-loss")));
            command(op, restoreCommand("timer", oracle.getUUID("timer-loss")));
        }
        if (ticks == 170) {
            exact(owner, "damage", 1);
            exact(nextOwner, "timer", 1);
            exact(owner, "carried", 1);
            exact(owner, "voidfall", 1);
            for (String name : BAGS) {
                if (!name.equals("ground")) require(drops(name).isEmpty(), "Unexpected extra loose " + name);
                if (!name.equals("carried") && !name.equals("damage") && !name.equals("voidfall")) exact(owner, name, 0);
                if (!name.equals("timer")) exact(nextOwner, name, 0);
                exact(op, name, 0);
                exact(secondOp, name, 0);
                if (!name.equals("conflict")) unavailable(name);
            }
            require(drops("ground").size() == 1 && ItemStack.matches(original("ground"), drops("ground").get(0).getItem()), "Live dropped bag changed");
            var chest = (ChestBlockEntity)server.overworld().getBlockEntity(CHEST);
            require(ItemStack.matches(original("chest"), chest.getItem(0)), "Moved chest bag changed across restarts");
            require(ItemStack.matches(original("conflict"), chest.getItem(1)), "A live copy in another container was replaced or removed");
            require(ItemStack.matches(original("cursor"), chest.getItem(3)
                    .getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).getStackInSlot(0)),
                    "The original nested cursor item did not survive the restart");
            var ground = (PlacedBackpackEntity)server.overworld().getBlockEntity(SHELF);
            var saved = ground.saveWithoutMetadata(server.registryAccess()).getCompound("Bag");
            require(ItemStack.matches(original("placed"), ItemStack.parseOptional(server.registryAccess(), saved)), "Placed bag changed across two restarts");
            evidence.add("PASS A fourth real server process loaded both delivered player inventories. Replaying old/current actions never produced another stack. Chest and placed originals retained every component. Two separately listed real losses reappeared in a chest and inside a shulker on a live menu cursor. Each valid action was blocked and each original survived.");
            destroy(owner, "damage");
            destroy(nextOwner, "timer");
        }
        if (ticks == 255) {
            command(op, CMD);
            var first = row("damage");
            var second = row("timer");
            require(first != null && first.restorable() && second != null && second.restorable(),
                    "The restart injection requires two new, normally witnessed losses");
            var chest = (ChestBlockEntity)server.overworld().getBlockEntity(CHEST);
            HistoryDiskChecks.interrupt(owner.player, first, null);
            HistoryDiskChecks.interrupt(nextOwner.player, second, chest);
            oracle.putUUID("prepared-loss", first.lossId());
            oracle.putUUID("done-loss", second.lossId());
            evidence.add("INJECTED Two interruption boundaries in the real journal and real playerdata. Both intents were DELIVERING. One inventory save had a prepared receipt and empty slot; the other contained a committed receipt and its one delivered item was subsequently moved to a chest.");
            finish();
        }
    }

    private static void receipts() throws Exception {
        if (ticks == 150) {
            exact(owner, "damage", 1);
            exact(nextOwner, "timer", 0);
            require(nextOwner.player.getInventory().getFreeSlot() < 0, "Committed-receipt fixture lost its full inventory");
            var chest = (ChestBlockEntity)server.overworld().getBlockEntity(CHEST);
            require(ItemStack.matches(original("timer"), chest.getItem(2)), "Committed recovery lost the stack moved to a chest");
            HistoryDiskChecks.returned(server, id("damage"), oracle.getUUID("prepared-loss"));
            HistoryDiskChecks.returned(server, id("timer"), oracle.getUUID("done-loss"));
            command(op, restoreCommand("damage", oracle.getUUID("prepared-loss")));
            command(secondOp, restoreCommand("timer", oracle.getUUID("done-loss")));
        }
        if (ticks == 220) {
            exact(owner, "damage", 1);
            exact(nextOwner, "timer", 0);
            require(drops("damage").isEmpty() && drops("timer").isEmpty(), "Replaying an interrupted delivery dropped a copy");
            evidence.add("PASS A fifth server recovered both injected interruptions. The prepared receipt resumed once. The committed receipt finalized without inserting a second stack after the original moved. Both real disk records reached RETURNED for their original loss IDs.");
            finish();
        }
    }

    private static void finish() throws Exception {
        for (Actor actor : new Actor[]{owner, nextOwner, op, secondOp}) {
            if (actor == null) continue;
            server.getPlayerList().remove(actor.player);
            actor.channel.finishAndReleaseAll();
        }
        NbtIo.writeCompressed(oracle, ORACLE);
        server.saveEverything(true, true, true);
        Files.write(Path.of(phase + ".pass"), evidence, StandardOpenOption.CREATE_NEW);
        phase = null;
        server.halt(false);
    }
}
