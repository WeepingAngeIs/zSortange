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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import net.neoforged.bus.api.EventPriority;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.entity.item.ItemExpireEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
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
    private record SourceDrop(UUID owner, String ownerName, String dimension, ItemStack stack) {}
    private static final Map<UUID,SourceDrop> diskSources = new HashMap<>();
    private static final Set<UUID> canceledSources = new HashSet<>();

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
        require("true".equals(System.getenv("GITHUB_ACTIONS")) && List.of("seed", "queue", "deliver", "replay", "receipts", "source-loss", "source-replay",
                        "custody-format", "custody-restart", "pack-storage", "pack-restart").contains(phase),
                "Disposable hosted server required");
        server = event.getServer();
        var actual = ModList.get().getModContainerById("anyastorage").orElseThrow().getModInfo().getOwningFile().getFile().getFilePath();
        require(Files.isRegularFile(actual) && Files.isSameFile(actual, Path.of(System.getProperty("anya.bagHistoryPlayable"))),
                "The server did not load the frozen candidate JAR");
        evidence.add(phase + " " + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(actual))));
        evidence.add("JVM " + ProcessHandle.current().pid());
        if (phase.startsWith("pack-")) {
            RosecourtReview.verifyFullPack(server);
            oracle = phase.equals("pack-storage") ? new CompoundTag() : NbtIo.readCompressed(ORACLE, NbtAccounter.create(16L << 20));
            return; // Actual Dev joins from the separately launched full Minecraft client.
        }
        if (phase.startsWith("custody-")) {
            op = join("HistoryOp", 10, true);
            owner = join("CustodyOwner", 2, false);
            nextOwner = join("EscrowHolder", 4, false);
            oracle = phase.equals("custody-format") ? new CompoundTag() : NbtIo.readCompressed(ORACLE, NbtAccounter.create(16L << 20));
            return;
        }
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
            if ("deliver".equals(phase) || "replay".equals(phase) || "receipts".equals(phase) || phase.startsWith("source-")) {
                owner = join("HistoryOwner", 2, false);
                nextOwner = join("OtherHolder", 5, false);
            }
            if (phase.startsWith("source-") || phase.equals("receipts")) {
                // Ordinary nearby player watch loads the test chunk. Neither bag is in pickup range.
                var floor = new BlockPos(26, 80, 3);
                server.overworld().setBlockAndUpdate(floor, Blocks.STONE.defaultBlockState());
                secondOp.player.teleportTo(server.overworld(), 26.5, 81, 3.5, 0, 0);
            }
        }
    }

    private static void createBag(String name, int index) {
        createBag(owner, name, index);
    }
    private static void createBag(Actor originalOwner, String name, int index) {
        var inv = originalOwner.player.getInventory();
        var host = new ItemStack(Content.BACKPACK.get());
        host.set(DataComponents.CUSTOM_NAME, Component.literal("Fixture " + name));
        inv.setItem(index, host);
        if (index < 9) inv.selected = index;
        var bag = new BagInventory(originalOwner.player, index);
        var nested = new CompoundTag();
        nested.putString("label", name);
        nested.putIntArray("values", new int[]{47, 3, 81});
        var metadata = new CompoundTag();
        metadata.put("nested", nested);
        var item = new ItemStack(Items.AMETHYST_SHARD, 13 + index);
        item.set(DataComponents.CUSTOM_NAME, Component.literal("Etched " + name));
        item.set(DataComponents.CUSTOM_DATA, CustomData.of(metadata));
        bag.setItem(3, item);
        if (name.equals("damage") || name.equals("timer") || name.startsWith("jail-")) {
            var disc = new ItemStack(Content.UPGRADES.get("feeding").get());
            disc.set(DataComponents.CUSTOM_NAME, Component.literal("Personal feeding module " + name));
            disc.set(DataComponents.CUSTOM_DATA, CustomData.of(metadata));
            inv.setItem(9, disc);
            require(Upgrades.change(bag, "feeding", false), "Named upgrade installation failed");
            require(Upgrades.installed(new BagInventory(originalOwner.player, index), "feeding"), "Installed disk missing");
        }
        oracle.put(name, inv.getItem(index).save(originalOwner.player.registryAccess()));
    }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) throws Exception {
        if (phase == null) return;
        if (phase.startsWith("pack-") && owner == null) {
            var client = server.getPlayerList().getPlayerByName("Dev");
            if (client == null) return;
            require(!client.connection.getConnection().isMemoryConnection(), "Expected independent full-pack client");
            owner = op = new Actor(client, new ArrayList<>(), null);
            server.getPlayerList().op(client.getGameProfile());
            nextOwner = join("EscrowHolder", 4, false);
        }
        ticks++;
        switch (phase) {
            case "seed" -> seed();
            case "queue" -> queue();
            case "deliver" -> deliver();
            case "replay" -> replay();
            case "receipts" -> receipts();
            case "source-loss" -> sourceLoss();
            case "source-replay" -> sourceReplay();
            case "pack-storage", "custody-format" -> custody(false);
            case "pack-restart", "custody-restart" -> custody(true);
        }
        if (ticks > 900) throw new IllegalStateException("Backpack history fixture timed out");
    }

    private static void custody(boolean restart) throws Exception {
        if (ticks != 120) return;
        boolean full = phase.startsWith("pack-");
        if (restart) {
            exact(owner, "jail-lost", full ? 1 : 0);
            exact(nextOwner, "jail-held", 0);
            command(op, restoreCommand("jail-lost", oracle.getUUID("lost-action")));
            command(op, restoreCommand("jail-held", oracle.getUUID("held-action")));
            exact(owner, "jail-lost", full ? 1 : 0);
            exact(owner, "jail-held", 0);
            exact(nextOwner, "jail-held", 0);
            RosecourtReview.verifyHeld(server, oracle.getUUID("custody-tx"), nextOwner.player.getUUID(), original("jail-held"), full);
            if (full) evidence.add("PASS Restart preserved both the single exact restored bag and the separate held bag. Replaying both original chat actions produced no second item.");
            else {
                require(row("jail-lost") != null && row("jail-held") != null, "Paused claims lost after restart");
                evidence.add("PASS Without the live jail mod, recovery remained paused across restart and native escrow retained the original bag. Both chat links remained retriable.");
            }
            finish(); return;
        }
        owner.player.getInventory().clearContent();
        nextOwner.player.getInventory().clearContent();
        owner.player.closeContainer();
        owner.player.setGameMode(GameType.SURVIVAL);
        createBag("jail-lost", 1);
        createBag(nextOwner, "jail-held", 2);
        destroy(owner, "jail-lost");
        destroy(nextOwner, "jail-held");
        var regular = row("jail-lost");
        var held = row("jail-held");
        require(regular != null && held != null && regular.restorable() && held.restorable(), "Missing witnessed bag losses");
        oracle.putUUID("lost-action", regular.lossId());
        oracle.putUUID("held-action", held.lossId());
        // Inject only the held bag's earlier original into a typed historical import.
        // The lost bag has no surviving stack anywhere in the current world or custody.
        UUID transaction = UUID.randomUUID();
        oracle.putUUID("custody-tx", transaction);
        RosecourtReview.importHeld(server, nextOwner.player, transaction, original("jail-held"), full);
        require(!held.owner().equals(regular.owner()) && held.owner().equals(nextOwner.player.getUUID())
                && regular.owner().equals(owner.player.getUUID()), "Unexpected original owner");
        if (!full) {
            RosecourtReview.checkRejectedJournals(server, transaction, expectedReason -> {
                var message = BackpackHistory.restore(op.player, id("jail-lost"), regular.lossId());
                var pending = row("jail-lost");
                require(message.startsWith("Return paused") && message.contains(expectedReason)
                                && pending != null && regular.lossId().equals(pending.lossId()),
                        "Invalid evidence must keep the original loss retriable: " + message);
                exact(owner, "jail-lost", 0);
                exact(nextOwner, "jail-held", 0);
            });
            evidence.add("PASS A damaged custody frame, an unindexed escrow, and a Cobblemon player-data extension each paused the same return with its own reason. The original journal bytes and supported player-data shape were retained.");
            for (var pair : List.of(held, regular)) {
                String response = BackpackHistory.restore(op.player, pair.id(), pair.lossId());
                require(response.contains("active and saved custody records could not be confirmed"),
                        "Intact journals must reach the missing-authority guard: " + response);
            }
            exact(nextOwner, "jail-held", 0);
            exact(owner, "jail-lost", 0);
            RosecourtReview.verifyHeld(server, transaction, nextOwner.player.getUUID(), original("jail-held"), false);
            evidence.add("PASS With valid native inventory and index bytes but no live Rosecourt, both owners' links remained eligible and neither received an item. The native beta.3 reader found the held backpack exactly once.");
            finish(); return;
        }
        command(op, restoreCommand("jail-held", held.lossId()));
        if (row("jail-held") != null) throw new IllegalStateException("Held backpack did not close its action: "
                + BackpackHistory.restore(op.player, id("jail-held"), held.lossId()));
        command(op, restoreCommand("jail-held", held.lossId()));
        exact(owner, "jail-held", 0);
        exact(nextOwner, "jail-held", 0);
        command(op, restoreCommand("jail-lost", regular.lossId()));
        if (row("jail-lost") != null) throw new IllegalStateException("Legitimately lost backpack did not return: "
                + BackpackHistory.restore(op.player, id("jail-lost"), regular.lossId()));
        command(op, restoreCommand("jail-lost", regular.lossId()));
        exact(owner, "jail-lost", 1);
        exact(owner, "jail-held", 0);
        exact(nextOwner, "jail-held", 0);
        RosecourtReview.verifyHeld(server, transaction, nextOwner.player.getUUID(), original("jail-held"), full);
        evidence.add("PASS On the previously occupied full-pack server, the exact Jail JAR indexed the separate synthetic historical backpack through its console bind command. A hosted full-pack player's valid Storage recovery was denied for that held bag and returned the independently lost bag to its separate owner with its contents and upgrade once.");
        finish();
    }

    @SubscribeEvent
    public static void extendOnce(ItemExpireEvent event) {
        if (event.getEntity() == extending && !extensionSeen) {
            event.addExtraLife(35);
            extensionSeen = true;
        }
    }

    @SubscribeEvent(priority=EventPriority.HIGHEST, receiveCanceled=true)
    public static void observeSavedSource(EntityJoinLevelEvent event) {
        if (!"source-replay".equals(System.getenv("ANYA_BAG_PHASE")) || !event.loadedFromDisk()
                || !(event.getEntity() instanceof ItemEntity drop) || !drop.getItem().is(Content.BACKPACK.get())) return;
        var data = drop.getPersistentData();
        if (data.hasUUID("AnyaBagDropOwner"))
            diskSources.put(drop.getUUID(), new SourceDrop(data.getUUID("AnyaBagDropOwner"), data.getString("AnyaBagDropName"),
                    event.getLevel().dimension().location().toString(), drop.getItem().copy()));
    }

    @SubscribeEvent(priority=EventPriority.LOWEST, receiveCanceled=true)
    public static void observeQuarantine(EntityJoinLevelEvent event) {
        if (event.loadedFromDisk() && event.isCanceled() && diskSources.containsKey(event.getEntity().getUUID()))
            canceledSources.add(event.getEntity().getUUID());
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
            var backup = Files.createDirectories(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("recipe_books")).resolve(owner.player.getUUID()+".dat_old");
            var book = owner.player.getRecipeBook().toNbt();
            var extra = book.copy();
            extra.putByteArray("unexpected_inventory", new byte[]{1,2,3});
            net.minecraft.nbt.NbtIo.writeCompressed(extra,backup);
            command(op, restoreCommand("voidfall", oracle.getUUID("voidfall-loss")));
            exact(owner, "voidfall", 0);
            require(row("voidfall") != null, "Unknown recipe-book contents consumed the recovery claim");
            net.minecraft.nbt.NbtIo.writeCompressed(book,backup);
            var claims = Files.createDirectories(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                    .resolve("data/openpartiesandclaims/player-configs")).resolve(owner.player.getUUID()+".toml");
            Files.writeString(claims,"[playerConfig]\nversion = 18\n");
            command(op, restoreCommand("voidfall", oracle.getUUID("voidfall-loss")));
            exact(owner, "voidfall", 0);
            require(row("voidfall") != null, "Claim settings with no installed provider consumed the recovery");
            Files.delete(claims);
            evidence.add("PASS Per-player claim settings with no authoritative options provider paused recovery and preserved the loss.");
            RosecourtReview.visibilityControls(owner.player,original("damage"));
            command(op, restoreCommand("voidfall", oracle.getUUID("voidfall-loss")));
            exact(owner, "voidfall", 1);
            evidence.add("PASS Ordinary operator recovery read a native recipe-book backup. An added opaque field paused the return. Accessory slot switches did not hide a held bag, and opaque bytes in an item or an invalid switch still refused clearance.");
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
            // Keep the actual source entities, unique IDs and original owner tags for the chunk rollback.
            for (String name : List.of("voidfall", "damage")) {
                ItemEntity drop = toss(owner, name);
                drop.setPos(name.equals("voidfall") ? 34.5 : 36.5, 82, 3.5);
                drop.setDeltaMovement(Vec3.ZERO);
                drop.setNoGravity(true);
                drop.setNoPickUpDelay();
                oracle.putUUID(name + "-source-entity", drop.getUUID());
                require(drop.getPersistentData().getUUID("AnyaBagDropOwner").equals(owner.player.getUUID()),
                        "Original toss lacks its witnessed holder");
            }
        }
        if (ticks == 270) {
            for (String name : List.of("voidfall", "damage"))
                require(drops(name).size() == 1 && drops(name).get(0).chunkPosition().x == 2
                        && drops(name).get(0).chunkPosition().z == 0, "Source outside checkpoint chunk " + name);
            evidence.add("SAVED Two real, pickup-enabled dropped backpacks, with original entity UUIDs, positions, dimension and player toss ownership in overworld entity chunk 2,0.");
            finish();
        }
    }

    private static ItemEntity source(String name) {
        var entity = server.overworld().getEntity(oracle.getUUID(name + "-source-entity"));
        require(entity instanceof ItemEntity, "Original saved drop did not load: " + name);
        ItemEntity drop = (ItemEntity)entity;
        require(ItemStack.matches(original(name), drop.getItem())
                && drop.getPersistentData().getUUID("AnyaBagDropOwner").equals(owner.player.getUUID()), "Saved source changed: " + name);
        return drop;
    }

    private static void sourceLoss() throws Exception {
        if (ticks == 100) {
            exact(owner, "voidfall", 0);
            exact(owner, "damage", 0);
            var falling = source("voidfall");
            falling.setPos(falling.getX(), server.overworld().getMinBuildHeight() - 100.0, falling.getZ());
            var hurt = source("damage");
            require(hurt.hurt(hurt.damageSources().cactus(), 100) && hurt.isRemoved(), "Saved damage source survived");
        }
        if (ticks == 180) {
            for (String name : List.of("voidfall", "damage")) {
                var lost = row(name);
                require(lost != null && lost.restorable() && lost.owner().equals(owner.player.getUUID()),
                        "Source did not produce a real loss: " + name);
                oracle.putUUID(name + "-source-loss", lost.lossId());
            }
            command(op, restoreCommand("voidfall", oracle.getUUID("voidfall-source-loss")));
            exact(owner, "voidfall", 1);
            exact(owner, "damage", 0);
            HistoryDiskChecks.returned(server, id("voidfall"), oracle.getUUID("voidfall-source-loss"));
            require(server.saveEverything(true, true, true), "Between-returns player snapshot was not saved");
            UUID ownerId = owner.player.getUUID();
            Files.copy(Path.of("history-world/playerdata", ownerId + ".dat"), Path.of("between-returns-player.dat"));
            Files.writeString(Path.of("rollback-owner-id.txt"), ownerId.toString(), StandardOpenOption.CREATE_NEW);
            command(secondOp, restoreCommand("damage", oracle.getUUID("damage-source-loss")));
            exact(owner, "damage", 1);
            HistoryDiskChecks.returned(server, id("damage"), oracle.getUUID("damage-source-loss"));
            evidence.add("PASS The exact saved source UUIDs suffered normal below-world loss and fatal damage. Both were delivered and reached RETURNED. The ordinary player save made between deliveries contains the first bag only.");
            finish();
        }
    }

    private static void sourceReplay() throws Exception {
        if (ticks == 100) {
            exact(owner, "voidfall", 1);
            exact(owner, "damage", 0);
            for (String name : List.of("voidfall", "damage")) {
                UUID entityId = oracle.getUUID(name + "-source-entity");
                var drop = diskSources.get(entityId);
                require(drop != null && drop.owner.equals(owner.player.getUUID()) && drop.ownerName.equals("HistoryOwner")
                        && drop.dimension.equals("minecraft:overworld") && ItemStack.matches(original(name), drop.stack),
                        "Disk loader did not reconstruct exact tagged source " + name);
                require(canceledSources.contains(entityId) && server.overworld().getEntity(entityId) == null && drops(name).isEmpty(),
                        "Replayed source could become available for pickup: " + name);
                command(op, restoreCommand(name, oracle.getUUID(name + "-source-loss")));
            }
            exact(owner, "voidfall", 1);
            exact(owner, "damage", 0);
            // Enter the actual pickup area. Both stored drops originally had zero pickup delay.
            server.overworld().setBlockAndUpdate(new BlockPos(34, 80, 3), Blocks.STONE.defaultBlockState());
            owner.player.teleportTo(server.overworld(), 34.5, 81, 3.5, 0, 0);
        }
        if (ticks == 150) {
            exact(owner, "voidfall", 1);
            exact(owner, "damage", 0);
            command(op, CMD);
            command(op, CMD);
            require(row("voidfall") == null, "Surviving delivered original generated a second offer");
            var newOffer = row("damage");
            UUID stale = oracle.getUUID("damage-source-loss");
            require(newOffer != null && newOffer.restorable() && !newOffer.lossId().equals(stale),
                    "Missing replacement action for the sole surviving rolled-back source");
            command(secondOp, restoreCommand("damage", stale));
            exact(owner, "damage", 0);
            command(op, restoreCommand("damage", newOffer.lossId()));
            command(secondOp, restoreCommand("damage", newOffer.lossId()));
            oracle.putUUID("damage-replay-return", newOffer.lossId());
        }
        if (ticks == 210) {
            exact(owner, "voidfall", 1);
            exact(owner, "damage", 1);
            for (String name : List.of("voidfall", "damage")) {
                require(drops(name).isEmpty(), "Rolled-back source remained in the level");
                exact(nextOwner, name, 0);
                exact(op, name, 0);
                exact(secondOp, name, 0);
            }
            require(row("voidfall") == null && row("damage") == null, "Resolved entity replay remained listed");
            HistoryDiskChecks.returned(server, id("damage"), oracle.getUUID("damage-replay-return"));
            evidence.add("PASS A seventh JVM reloaded the byte-identical pre-loss entity chunk and earlier player save. Actual loadedFromDisk events carried both original UUIDs, owners, dimension and components, and were canceled before pickup. Listing closed the already-delivered original. Only a new recovery link returned the inventory-rolled-back original, exactly once. Prior links could not deliver either bag.");
            finish();
        }
    }

    private static void finish() throws Exception {
        for (Actor actor : new Actor[]{owner, nextOwner, op, secondOp}) {
            if (actor == null || actor.channel == null) continue;
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
