package dev.anya.storage.furniture;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.neoforged.bus.api.*;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import java.io.IOException;
import java.util.*;

@EventBusSubscriber(modid=AnyaStorage.ID)
public final class Furniture {
    private Furniture() {}
    public static boolean isFurniture(Block block) { return block instanceof FurnitureBlock; }

    /** A physical ray and live server-side rights are checked again immediately before any transfer. */
    static FurnitureEntity target(ServerPlayer p) {
        if (!p.isAlive() || p.isSpectator() || !p.serverLevel().getServer().isSameThread()) return null;
        try {
            var pos = RoomEditor.looking(p).getBlockPos();
            return p.serverLevel().getBlockEntity(pos) instanceof FurnitureEntity f ? f : null;
        } catch (IllegalArgumentException e) { return null; }
    }
    static boolean allowed(ServerPlayer p, FurnitureEntity f, int rights) {
        if (f == null || !physical(p,f)) return false;
        var pos = f.getBlockPos();
        var level=p.serverLevel();
        var owner=f.owner;
        var pocket=f.pocket;
        if (!Safety.locationReason(p,p.serverLevel(),pos).isEmpty()) return false;
        if (!PocketService.inPocket(level)) return pocket==null && p.serverLevel()==level && physical(p,f) && f.belongsTo(owner,null);
        var lease = PocketService.lease(p.getUUID());
        if (lease == null || lease.kind()!=PocketLeases.Kind.VISIT || !lease.pocket().equals(pocket)) return false;
        try {
            var ledger=PocketService.ledger(p.getServer());
            var record=ledger.get(pocket);
            if(record==null || !record.owner().equals(owner) || record.stage()!=PocketLedger.Stage.READY || record.epoch()!=lease.epoch()
                    || !record.contains(pos.getX(),pos.getY(),pos.getZ())) return false;
            var hub=PocketService.home(p,record);
            if(hub==null) return false;
            long policy=hub.profileRevision;
            if(!PocketService.authorize(p,record,lease.kind(),false,rights).isEmpty()) return false;
            // Claim and addon callbacks can remove a block, move the player, or revoke a grant synchronously.
            return physical(p,f) && p.serverLevel()==level && f.belongsTo(owner,pocket)
                    && PocketService.enabled() && !RoomEditor.quarantined(pocket) && !PocketService.recovering(p.getUUID())
                    && lease.equals(PocketService.lease(p.getUUID())) && record.equals(ledger.get(pocket))
                    && record.contains(p.getBlockX(),p.getBlockY(),p.getBlockZ())
                    && PocketService.home(p,record)==hub && !hub.isRemoved() && !Transactions.quarantined(hub)
                    && hub.profileRevision==policy && hub.permits(p,rights|BasePermissions.VISIT);
        } catch (IOException e) { return false; }
    }
    private static boolean physical(ServerPlayer p,FurnitureEntity f) {
        return p.isAlive() && !p.isRemoved() && !p.isSpectator() && f.owner!=null && f.getLevel()==p.serverLevel()
                && !f.isRemoved() && !Transactions.quarantined(f.inventory) && target(p)==f
                && p.serverLevel().getBlockEntity(f.getBlockPos())==f && p.serverLevel().getWorldBorder().isWithinBounds(f.getBlockPos());
    }
    static boolean canSee(ServerPlayer p,FurnitureEntity f) {
        return f != null && f.mayView(p.getUUID()) && allowed(p,f,BasePermissions.VIEW) && f.mayView(p.getUUID());
    }
    static boolean canSend(ServerPlayer p,FurnitureEntity f) {
        return f.maySend(p.getUUID()) && allowed(p,f,BasePermissions.DEPOSIT) && f.maySend(p.getUUID());
    }
    private static boolean canTake(ServerPlayer p,FurnitureEntity f) {
        return f.ownerMayRemove(p) && allowed(p,f,BasePermissions.VIEW|BasePermissions.WITHDRAW) && f.ownerMayRemove(p);
    }
    private static void tell(ServerPlayer p,String message) { AnyaStorage.tell(p,message); }
    public static void use(ServerPlayer p,FurnitureEntity f) {
        if (p.isShiftKeyDown() && p.getMainHandItem().isEmpty() && f.ownerMayRemove(p)) { collect(p,f); return; }
        if (!p.getMainHandItem().isEmpty()) { deliver(p,f,""); return; }
        if (f.shelf()) {
            if (canSee(p,f)) {
                ItemStack item = f.inventory.getItem(0);
                // Show the ordinary item name. A custom name or hover event can itself contain private data.
                String name = item.isEmpty() ? "Empty keepsake shelf" : item.getItem().getDescription().getString();
                tell(p,name+(f.caption.isBlank() ? "" : " — "+f.caption));
            } else tell(p,"This display is private.");
            if (f.ownerMayRemove(p)) tell(p,"Use a held keepsake to display one. Sneak with an empty hand to take it. Use /anya furniture for captions and visitor settings.");
        } else if (canTake(p,f)) {
            tell(p,"Sneak with an empty hand to collect a parcel and read its note. Invite a sender with /anya furniture invite <player>.");
        } else tell(p,"An invited sender can use a held stack or /anya furniture deliver <note>.");
    }
    static String text(String input) {
        String clean = input.strip();
        if (clean.length()>FurnitureEntity.TEXT_LIMIT || clean.codePoints().anyMatch(c -> Character.isISOControl(c)
                || Character.getType(c)==Character.FORMAT || c==0xa7))
            throw new IllegalArgumentException("Use up to 80 characters of plain text.");
        return clean;
    }
    private static int findSlot(FurnitureEntity f,boolean empty) {
        for (int i=0; i<f.inventory.getContainerSize(); i++)
            if (f.inventory.getItem(i).isEmpty()==empty) return i;
        return -1;
    }
    static void deliver(ServerPlayer p,FurnitureEntity f,String note) {
        if (!canSend(p,f)) { tell(p,"You cannot deposit here."); return; }
        if (f.shelf() && !note.isEmpty()) { tell(p,"Use /anya furniture caption to label a shelf."); return; }
        String receipt = p.getGameProfile().getName() + (note.isEmpty() ? "" : " — "+note);
        var main = new MainInventory(p);
        int selected = p.getInventory().selected;
        var held = main.getItem(selected);
        int slot = findSlot(f,true);
        if (held.isEmpty() || slot<0) { tell(p,"No item was deposited."); return; }
        // Keep parcels separate, including identical items sent with different notes.
        long revision=f.revision();
        var plan = depositPlan(main,selected,f,slot,() -> canSend(p,f) && p.getInventory().selected==selected && f.revision()==revision);
        String error = plan.commit(p.getServer());
        if (!error.isEmpty()) { tell(p,error); return; }
        if (!f.shelf()) f.receipts[slot]=receipt;
        f.setChanged();
        p.inventoryMenu.broadcastChanges();
        tell(p,f.shelf() ? "Keepsake placed." : "Parcel delivered.");
    }
    static Transactions.Plan depositPlan(net.minecraft.world.Container main,int selected,FurnitureEntity f,int slot,
                                         java.util.function.BooleanSupplier permission) {
        var plan=new Transactions.Plan(List.of(main,f.inventory),permission);
        ItemStack source=plan.work(main)[selected];
        if (source.isEmpty() || !plan.work(f.inventory)[slot].isEmpty()) throw new IllegalArgumentException("The parcel slot or held item changed.");
        plan.work(f.inventory)[slot]=source.split(f.shelf()?1:source.getCount());
        return plan;
    }
    static Transactions.Plan collectPlan(net.minecraft.world.Container main,FurnitureEntity f,int slot,
                                         java.util.function.BooleanSupplier permission) {
        var plan=new Transactions.Plan(List.of(f.inventory,main),permission);
        int count=plan.original(f.inventory)[slot].getCount();
        return count>0 && plan.move(f.inventory,slot,List.of(main),count)==count ? plan : null;
    }
    static void collect(ServerPlayer p,FurnitureEntity f) {
        if (!canTake(p,f)) { tell(p,"You cannot collect here."); return; }
        int slot=findSlot(f,false);
        if (slot<0) { tell(p,"There is nothing to collect."); return; }
        var main = new MainInventory(p);
        long revision=f.revision();
        var plan = collectPlan(main,f,slot,()->canTake(p,f) && f.revision()==revision);
        if (plan==null) {
            tell(p,"Make room for the whole stack. It is still here."); return;
        }
        String receipt=f.receipts[slot];
        String error=plan.commit(p.getServer());
        if (!error.isEmpty()) { tell(p,error); return; }
        f.receipts[slot]="";
        if(f.shelf()) f.caption="";
        f.setChanged();
        p.inventoryMenu.broadcastChanges();
        tell(p,f.shelf() ? "Keepsake returned." : "Parcel collected from "+receipt);
    }
    @SubscribeEvent public static void breaking(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer p) || !(event.getLevel().getBlockEntity(event.getPos()) instanceof FurnitureEntity f)) return;
        if (!f.ownerMayRemove(p) || !f.emptyForRemoval() || !Safety.buildReason(p,p.serverLevel(),f.getBlockPos(),f.getBlockState(),true).isEmpty()
                || f.isRemoved() || p.serverLevel().getBlockEntity(f.getBlockPos())!=f || !f.ownerMayRemove(p) || !f.emptyForRemoval()) {
            event.setCanceled(true);
            tell(p,"Only the owner can remove empty furniture.");
        }
    }
    @SubscribeEvent public static void exploding(ExplosionEvent.Detonate event) {
        event.getAffectedBlocks().removeIf(pos -> event.getLevel().getBlockState(pos).getBlock() instanceof FurnitureBlock);
    }
    @FunctionalInterface private interface Action { void run(ServerPlayer p,FurnitureEntity f); }
    private static int at(CommandContext<CommandSourceStack> c,Action action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p=c.getSource().getPlayerOrException();
        var f=target(p);
        if (f==null) { tell(p,"Look at a nearby Keepsake Shelf or Delivery Hatch."); return 0; }
        try { action.run(p,f); } catch(IllegalArgumentException e) { tell(p,e.getMessage()); return 0; }
        return 1;
    }
    private static void configure(ServerPlayer p,FurnitureEntity f,boolean shelf,Runnable edit) {
        if(f.shelf()!=shelf || !f.ownerMayRemove(p) || !allowed(p,f,BasePermissions.BUILD) || !f.ownerMayRemove(p)) { tell(p,"You cannot change these settings here."); return; }
        edit.run();
        f.setChanged();
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        var root=Commands.literal("furniture")
                .executes(c->{tell(c.getSource().getPlayerOrException(),"Look at the furniture. Shelf: caption <words>, show or hide. Hatch: invite <player>, close (remove all invites), deliver [note], collect.");return 1;});
        root.then(Commands.literal("caption").then(Commands.argument("words",StringArgumentType.greedyString())
                .executes(c -> at(c,(p,f) -> configure(p,f,true,()->{
                    f.caption=text(StringArgumentType.getString(c,"words"));tell(p,"Caption saved.");
                })))));
        for (boolean show:List.of(true,false)) root.then(Commands.literal(show?"show":"hide")
                .executes(c -> at(c,(p,f)->configure(p,f,true,()->{
                    f.visible=show;tell(p,show?"Visitors with view permission can see the keepsake.":"Only you can view the keepsake.");
                }))));
        root.then(Commands.literal("invite").then(Commands.argument("player",EntityArgument.player()).executes(c->{
            var recipient=EntityArgument.getPlayer(c,"player");
            return at(c,(p,f)->configure(p,f,false,()->{
                if(f.invited.size()>=FurnitureEntity.GUEST_LIMIT && !f.invited.contains(recipient.getUUID())) throw new IllegalArgumentException("Sender list full.");
                f.invited.add(recipient.getUUID());tell(p,recipient.getGameProfile().getName()+" can now leave parcels.");
            }));
        })));
        root.then(Commands.literal("close").executes(c -> at(c,(p,f)->configure(p,f,false,()->{
            f.invited.clear();tell(p,"All sender invitations removed.");
        }))));
        root.then(Commands.literal("deliver").executes(c -> at(c,(p,f)->deliver(p,f,"")))
                .then(Commands.argument("note",StringArgumentType.greedyString())
                .executes(c->at(c,(p,f)->deliver(p,f,text(StringArgumentType.getString(c,"note")))))));
        root.then(Commands.literal("collect").executes(c->at(c,Furniture::collect)));
        event.getDispatcher().register(Commands.literal("anya").then(root));
    }
}
