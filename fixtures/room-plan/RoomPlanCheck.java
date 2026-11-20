package dev.anya.storage.pocket;

import dev.anya.storage.Content;
import dev.anya.storage.Transactions;
import io.netty.buffer.Unpooled;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.registries.OfflineRegistries;

/** Uses the packaged planner and real registered items without creating a world. */
public final class RoomPlanCheck {
    static void require(boolean ok, String why) { if (!ok) throw new AssertionError(why); }
    public static void main(String[] args) throws Exception {
        var registry = OfflineRegistries.setup(Path.of(args[0]));
        var commands = RoomPlans.commands().build().getChildren().stream().map(c -> c.getName()).collect(Collectors.toSet());
        require(commands.equals(Set.of("add", "apply", "cancel", "undo")), "Commands");
        var shelf = Content.SHELF.get().defaultBlockState();
        var hatch = Content.HATCH.get().defaultBlockState();
        require(RoomEditor.supported(shelf.getBlock()) && RoomEditor.supported(hatch.getBlock())
                && !RoomEditor.supported(Blocks.HOPPER), "Approved furnishings");
        var crate = Content.CRATE.get().defaultBlockState();
        var firstPad = new RoomEditor.Mark(new BlockPos(4102, 65, 4110), crate, null, Blocks.STONE.defaultBlockState());
        var lastPad = new RoomEditor.Mark(new BlockPos(4122, 65, 4110), crate, null, Blocks.STONE.defaultBlockState());
        var furnishing = new RoomEditor.Mark(new BlockPos(4121, 65, 4102), shelf, null, Blocks.STONE.defaultBlockState());
        require(RoomPlans.canMark(Map.of(), lastPad), "A player can start at any vacant pad");
        var planWithChest = Map.of(firstPad.pos(), firstPad);
        require(!RoomPlans.canMark(planWithChest, lastPad) && RoomPlans.canMark(planWithChest, firstPad),
                "Block a second chest, but allow remarking the same pad");
        require(RoomPlans.canMark(planWithChest, furnishing)
                && RoomPlans.canMark(Map.of(furnishing.pos(), furnishing), lastPad),
                "A plan may include one chest and ordinary furnishings");
        var wire = new RegistryFriendlyByteBuf(Unpooled.buffer(),registry);
        try {
            var outline = new RoomPlanPacket.Outline(new BlockPos(4121,65,4102),Block.getId(shelf));
            for (int n=0; n<=RoomPlans.LIMIT; n++) {
                var packet = new RoomPlanPacket(java.util.Collections.nCopies(n,outline));
                RoomPlanPacket.CODEC.encode(wire,packet);
                require(packet.equals(RoomPlanPacket.CODEC.decode(wire)) && wire.readableBytes()==0,"Outline roundtrip");
            }
            wire.writeByte(RoomPlans.LIMIT+1);
            try { RoomPlanPacket.CODEC.decode(wire); throw new AssertionError("Oversized preview accepted"); }
            catch(IllegalArgumentException expected) {}
        } finally { wire.release(); }
        var main = new SimpleContainer(4);
        var first = new SimpleContainer(1);
        var second = new SimpleContainer(1);
        var cells = List.of(first,second);
        var states = List.of(shelf,hatch);
        var entitled = new AtomicBoolean(true);
        main.setItem(1,new ItemStack(shelf.getBlock(),2));
        var renamed = new ItemStack(hatch.getBlock());
        renamed.set(DataComponents.CUSTOM_NAME,Component.literal("private delivery"));
        main.setItem(0,renamed);
        var plan = new Transactions.Plan(List.of(first,second,main),entitled::get);
        try { RoomEditor.stageMaterials(plan,main,cells,states); throw new AssertionError("Consumed renamed item"); }
        catch(IOException expected) {}
        require(first.isEmpty() && second.isEmpty() && main.getItem(1).getCount()==2,"Preview spent items");
        main.setItem(3,new ItemStack(hatch.getBlock()));
        plan = new Transactions.Plan(List.of(first,second,main),entitled::get);
        RoomEditor.stageMaterials(plan,main,cells,states);
        entitled.set(false);
        var commit = Transactions.Plan.class.getDeclaredMethod("commitOnServerThread");
        commit.setAccessible(true);
        require(!((String)commit.invoke(plan)).isEmpty() && first.isEmpty() && second.isEmpty(),"Revoked commit");
        entitled.set(true);
        second.setItem(0,new ItemStack(Blocks.COBBLESTONE));
        require(!((String)commit.invoke(plan)).isEmpty() && main.getItem(3).getCount()==1 && first.isEmpty(),"Partial stale commit");
        second.clearContent();
        plan = new Transactions.Plan(List.of(first,second,main),entitled::get);
        RoomEditor.stageMaterials(plan,main,cells,states);
        require(plan.conserved() && ((String)commit.invoke(plan)).isEmpty(),"Funded commit failed");
        require(first.getItem(0).is(shelf.getBlock().asItem()) && second.getItem(0).is(hatch.getBlock().asItem())
                && main.getItem(1).getCount()==1 && main.getItem(3).isEmpty() && main.getItem(0)==renamed,"Inventory conservation");
        require(!((String)commit.invoke(plan)).isEmpty(),"Applied a second time");
        var failsOnce = new SimpleContainer(1) {
            boolean fail = true;
            @Override public void setItem(int slot, ItemStack value) {
                if (fail && !value.isEmpty()) { fail=false; throw new IllegalStateException("Injected failure placing second furnishing"); }
                super.setItem(slot,value);
            }
        };
        first.clearContent();
        main.setItem(3,new ItemStack(hatch.getBlock()));
        plan = new Transactions.Plan(List.of(first,failsOnce,main),entitled::get);
        RoomEditor.stageMaterials(plan,main,List.of(first,failsOnce),states);
        require(!((String)commit.invoke(plan)).isEmpty() && first.isEmpty() && failsOnce.isEmpty()
                && main.getItem(1).getCount()==1 && main.getItem(3).getCount()==1,"Failed second write did not roll back");
        System.out.println("PASS room outline packet limits, commands, one chest mark, furniture registry, no-spend preview, exact materials, stale cell, permission callback, batch conservation, second-write rollback and replay");
    }
}
