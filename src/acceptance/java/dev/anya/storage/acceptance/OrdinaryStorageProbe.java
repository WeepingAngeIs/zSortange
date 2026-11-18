package dev.anya.storage.acceptance;

import com.mojang.authlib.GameProfile;
import dev.anya.storage.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import xaero.pac.common.server.api.OpenPACServerAPI;

/** Packaged CI fixture. Exercises ordinary storage without a client or an enabled pocket. */
@EventBusSubscriber(modid = Acceptance.ID)
public final class OrdinaryStorageProbe {
    private static final BlockPos HUB = new BlockPos(48, 80, 48);
    private static final BlockPos CHEST = HUB.east();
    private static final BlockPos CUBE = HUB.east(2);
    private static final UUID OWNER = UUID.fromString("6311935e-6135-4a0e-b39b-392046680e11");
    private static final UUID GUEST = UUID.fromString("c9af9558-1977-41da-8be4-f91678517752");
    private static boolean ran;

    static boolean active() { return System.getProperty("anya.ordinaryStorage") != null; }

    @SubscribeEvent
    public static void tick(ServerTickEvent.Post event) throws Exception {
        var server = event.getServer();
        if (!active() || ran || server.getTickCount() < 30) return;
        ran = true;
        String phase = System.getProperty("anya.ordinaryStorage");
        try {
            Acceptance.require("true".equals(System.getenv("GITHUB_ACTIONS")), "Hosted disposable fixture only");
            Acceptance.require(!ExpansionRules.get().pocketStorage && !Boolean.getBoolean("anyastorage.pocketPrototype"), "Pockets must be off");
            Acceptance.verifyJar("ordinary-" + phase);
            var level = server.overworld();
            level.getChunkAt(HUB);
            if ("seed".equals(phase)) seed(level);
            else if (!"verify".equals(phase)) throw new IllegalArgumentException("Unknown fixture phase");
            verify(level);
            server.saveEverything(true, true, true);
            Acceptance.marker("ordinary-" + phase, "Named stacks, bag identity and nested item, rights and exact counts verified.");
        } finally {
            server.halt(false);
        }
    }

    private static StorageEntity storage(ServerLevel level, BlockPos pos) {
        if (level.getBlockEntity(pos) instanceof StorageEntity storage) return storage;
        throw new IllegalStateException("Missing storage at " + pos);
    }

    private static ItemStack mineral(int count) {
        var stack = new ItemStack(Items.AMETHYST_SHARD, count);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal("Violet relay"));
        return stack;
    }

    private static void seed(ServerLevel level) throws Exception {
        for (var pos : List.of(HUB, CHEST, CUBE)) Acceptance.require(level.isEmptyBlock(pos), "Fresh test area required");
        level.setBlockAndUpdate(HUB, Content.HUB.get().defaultBlockState());
        level.setBlockAndUpdate(CHEST, Content.CRATE.get().defaultBlockState());
        level.setBlockAndUpdate(CUBE, Content.CUBE.get().defaultBlockState());
        var hub = storage(level, HUB);
        var chest = storage(level, CHEST);
        var cube = storage(level, CUBE);
        hub.owner = chest.owner = cube.owner = OWNER;
        hub.setMember(GUEST, BasePermissions.VIEW);
        chest.setItem(0, mineral(8));
        var bag = new ItemStack(Content.BACKPACK.get());
        BagInventory.initialize(bag);
        var contents = NonNullList.withSize(27, ItemStack.EMPTY);
        contents.set(26, new ItemStack(Items.TOTEM_OF_UNDYING));
        bag.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
        cube.setItem(215, bag);
        Acceptance.marker("ordinary-bag-id", BagInventory.data(bag).getUUID("BagId").toString());

        var guest = new FakePlayer(level, new GameProfile(GUEST, "StorageGuest")) {
            @Override public boolean hasPermissions(int permission) { return false; }
        };
        guest.setPos(HUB.getX() + .5, HUB.getY() + 1, HUB.getZ() + .5);
        var wallet = new SimpleContainer(9);
        var targets = List.of(new Network.Target(CHEST, chest));
        var guard = Network.guard(guest, hub, targets, BasePermissions.VIEW | BasePermissions.WITHDRAW);
        Acceptance.require(Network.hubReason(guest, hub).isEmpty() && !guard.getAsBoolean(), "View-only access boundaries");
        var refused = new Transactions.Plan(List.of(chest, wallet), guard);
        refused.move(chest, 0, List.of(wallet), 1);
        Acceptance.require(!refused.commit(level.getServer()).isEmpty() && wallet.isEmpty() && chest.getItem(0).getCount() == 8,
                "View-only withdrawal mutated storage");
        hub.setMember(GUEST, BasePermissions.VIEW | BasePermissions.WITHDRAW);
        var allowed = Network.guard(guest, hub, targets, BasePermissions.VIEW | BasePermissions.WITHDRAW);
        Acceptance.require(allowed.getAsBoolean(), "Explicit withdrawal permission must work before claim");
        var claims = OpenPACServerAPI.get(level.getServer()).getServerClaimsManager();
        var dimension = level.dimension().location();
        claims.claim(dimension, OWNER, 0, HUB.getX() >> 4, HUB.getZ() >> 4, false);
        try {
            Acceptance.require("Claim permission denied".equals(Safety.locationReason(guest, level, CHEST))
                    && !allowed.getAsBoolean(), "Foreign claim must deny the permitted guest");
        } finally {
            claims.unclaim(dimension, HUB.getX() >> 4, HUB.getZ() >> 4);
        }
        var move = new Transactions.Plan(List.of(chest, wallet), allowed);
        Acceptance.require(move.move(chest, 0, List.of(wallet), 1) == 1 && move.commit(level.getServer()).isEmpty(),
                "Permitted transfer failed");
        Acceptance.require(Transactions.same(wallet.getItem(0), mineral(1)) && Transactions.same(chest.getItem(0), mineral(7)),
                "Component/count conservation after permitted withdrawal");
        var stale = new Transactions.Plan(List.of(chest, wallet), allowed);
        stale.move(chest, 0, List.of(wallet), 1);
        hub.setMember(GUEST, BasePermissions.VIEW);
        Acceptance.require(!stale.commit(level.getServer()).isEmpty() && Transactions.same(wallet.getItem(0), mineral(1))
                && Transactions.same(chest.getItem(0), mineral(7)), "Revoked transfer changed counts");
        cube.setItem(0, wallet.removeItem(0, 1));
    }

    private static void verify(ServerLevel level) throws Exception {
        var hub = storage(level, HUB);
        var chest = storage(level, CHEST);
        var cube = storage(level, CUBE);
        Acceptance.require(OWNER.equals(chest.owner) && OWNER.equals(hub.owner) && OWNER.equals(cube.owner)
                && hub.memberMask(GUEST) == BasePermissions.VIEW, "Wrong persisted owner or guest rights");
        for (int i = 0; i < chest.getContainerSize(); i++)
            Acceptance.require(Transactions.same(chest.getItem(i), i == 0 ? mineral(7) : ItemStack.EMPTY), "Chest slot " + i);
        Acceptance.require(cube.getContainerSize() == 216 && Transactions.same(cube.getItem(0), mineral(1)), "Cube count");
        for (int i = 1; i < 215; i++) Acceptance.require(cube.getItem(i).isEmpty(), "Unexpected occupied cube slot");
        var bag = cube.getItem(215);
        String original = java.nio.file.Files.readString(Acceptance.control().resolve("ordinary-bag-id"));
        Acceptance.require(bag.is(Content.BACKPACK.get()) && bag.getCount() == 1
                && original.equals(BagInventory.data(bag).getUUID("BagId").toString()), "Bag identity");
        var contents = NonNullList.withSize(27, ItemStack.EMPTY);
        bag.getOrDefault(DataComponents.CONTAINER, ItemContainerContents.EMPTY).copyInto(contents);
        for (int i = 0; i < contents.size(); i++)
            Acceptance.require(Transactions.same(contents.get(i), i == 26 ? new ItemStack(Items.TOTEM_OF_UNDYING) : ItemStack.EMPTY),
                    "Nested backpack slot " + i);
    }

    private OrdinaryStorageProbe() {}
}
