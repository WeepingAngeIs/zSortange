package dev.anya.storage.furniture;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import dev.anya.storage.*;
import dev.anya.storage.pocket.*;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
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
        if (!PocketService.inPocket(level)) return pocket==null && p.serverLevel()==level && physical(p,f)
                && (f.shelf() || f.belongsTo(owner,null));
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
        return p.isAlive() && !p.isRemoved() && !p.isSpectator() && (f.shelf() || f.owner!=null) && f.getLevel()==p.serverLevel()
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
        if (f.shelf()) { useGlobe(p,f,InteractionHand.MAIN_HAND); return; }
        if (p.isShiftKeyDown() && p.getMainHandItem().isEmpty() && f.ownerMayRemove(p)) { collect(p,f); return; }
        if (!p.getMainHandItem().isEmpty()) { deliver(p,f,""); return; }
        if (canTake(p,f)) {
            tell(p,"Sneak with an empty hand to collect a parcel and read its note. Invite a sender with /anya furniture invite <player>.");
        } else tell(p,"An invited sender can use a held stack or /anya furniture deliver <note>.");
    }
    /** Called from a normal block interaction after vanilla and the installed claim mod have processed it.
     * A pocket intercepts block use earlier; its server-side visit and claim checks must still run there. */
    static void useGlobe(ServerPlayer p,FurnitureEntity f,InteractionHand hand) {
        boolean depositing=!p.getItemInHand(hand).isEmpty();
        if (depositing!=f.inventory.isEmpty()) {
            if (depositing) p.displayClientMessage(net.minecraft.network.chat.Component.literal("Use an empty hand to take the keepsake."),true);
            return;
        }
        var inv=p.getInventory();
        int slot=hand==InteractionHand.MAIN_HAND ? inv.selected : net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND;
        long revision=f.revision();
        var plan=globePlan(inv,slot,f,()->{
            if (!f.mayView(p.getUUID()) || f.revision()!=revision || hand==InteractionHand.MAIN_HAND && inv.selected!=slot)
                return false;
            if (PocketService.inPocket(p.level()))
                return allowed(p,f,depositing ? BasePermissions.DEPOSIT : BasePermissions.VIEW|BasePermissions.WITHDRAW);
            return f.pocket==null && !p.isSpectator() && p.isAlive() && !f.isRemoved() && p.serverLevel()==f.getLevel()
                    && p.serverLevel().getBlockEntity(f.getBlockPos())==f
                    && p.canInteractWithBlock(f.getBlockPos(),1.0);
        });
        if (plan==null) return;
        String error=plan.commit(p.getServer());
        if (!error.isEmpty()) {
            tell(p,f.sealedKeepsake() ? "This old private keepsake can be reclaimed by its original owner." : error);
            return;
        }
        if (!depositing) f.finishCollection();
        f.changedDisplay();
        p.inventoryMenu.broadcastChanges();
    }
    static Transactions.Plan globePlan(net.minecraft.world.Container hands,int slot,FurnitureEntity globe,
                                      java.util.function.BooleanSupplier permitted) {
        var plan=new Transactions.Plan(List.of(hands,globe.inventory),permitted);
        ItemStack[] hand=plan.work(hands), display=plan.work(globe.inventory);
        if (hand[slot].isEmpty()==display[0].isEmpty()) return null;
        if (display[0].isEmpty()) display[0]=hand[slot].split(1);
        else {
            hand[slot]=display[0];
            display[0]=ItemStack.EMPTY;
        }
        return plan;
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
        if (f.shelf()) { tell(p,"Right-click the Keepsake Globe with the item you want to display."); return; }
        if (!canSend(p,f)) { tell(p,"You cannot deposit here."); return; }
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
        f.receipts[slot]=receipt;
        f.setChanged();
        p.inventoryMenu.broadcastChanges();
        tell(p,"Parcel delivered.");
    }
    static Transactions.Plan depositPlan(net.minecraft.world.Container main,int selected,FurnitureEntity f,int slot,
                                         java.util.function.BooleanSupplier permission) {
        var plan=new Transactions.Plan(List.of(main,f.inventory),permission);
        ItemStack source=plan.work(main)[selected];
        if (source.isEmpty() || !plan.work(f.inventory)[slot].isEmpty()) throw new IllegalArgumentException("The parcel slot or held item changed.");
        plan.work(f.inventory)[slot]=source.split(source.getCount());
        return plan;
    }
    static Transactions.Plan collectPlan(net.minecraft.world.Container main,FurnitureEntity f,int slot,
                                         java.util.function.BooleanSupplier permission) {
        var plan=new Transactions.Plan(List.of(f.inventory,main),permission);
        int count=plan.original(f.inventory)[slot].getCount();
        return count>0 && plan.move(f.inventory,slot,List.of(main),count)==count ? plan : null;
    }
    static void collect(ServerPlayer p,FurnitureEntity f) {
        if (f.shelf()) { tell(p,"Right-click the Keepsake Globe with an empty hand to take the item."); return; }
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
        f.setChanged();
        p.inventoryMenu.broadcastChanges();
        tell(p,"Parcel collected from "+receipt);
    }
    @SubscribeEvent public static void breaking(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer p) || !(event.getLevel().getBlockEntity(event.getPos()) instanceof FurnitureEntity f)) return;
        if (f.shelf()) {
            if (f.sealedKeepsake() || Transactions.quarantined(f.inventory)) {
                event.setCanceled(true);
                tell(p,"Recover the stored keepsake before breaking this globe.");
            }
            return;
        }
        if (!f.ownerMayRemove(p) || !f.emptyForRemoval() || !Safety.buildReason(p,p.serverLevel(),f.getBlockPos(),f.getBlockState(),true).isEmpty()
                || f.isRemoved() || p.serverLevel().getBlockEntity(f.getBlockPos())!=f || !f.ownerMayRemove(p) || !f.emptyForRemoval()) {
            event.setCanceled(true);
            tell(p,"Only the owner can remove empty furniture.");
        }
    }
    @SubscribeEvent public static void exploding(ExplosionEvent.Detonate event) {
        event.getAffectedBlocks().removeIf(pos -> event.getLevel().getBlockEntity(pos) instanceof FurnitureEntity f
                && (!f.shelf() || f.sealedKeepsake() || Transactions.quarantined(f.inventory)));
    }
    @FunctionalInterface private interface Action { void run(ServerPlayer p,FurnitureEntity f); }
    private static int at(CommandContext<CommandSourceStack> c,Action action) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        var p=c.getSource().getPlayerOrException();
        var f=target(p);
        if (f==null) { tell(p,"Look at a nearby Keepsake Globe or Delivery Hatch."); return 0; }
        try { action.run(p,f); } catch(IllegalArgumentException e) { tell(p,e.getMessage()); return 0; }
        return 1;
    }
    private static void configureHatch(ServerPlayer p,FurnitureEntity f,Runnable edit) {
        if(f.shelf() || !f.ownerMayRemove(p) || !allowed(p,f,BasePermissions.BUILD) || !f.ownerMayRemove(p)) { tell(p,"You cannot change these settings here."); return; }
        edit.run();
        f.setChanged();
    }
    @SubscribeEvent public static void commands(RegisterCommandsEvent event) {
        var root=Commands.literal("furniture")
                .executes(c->{tell(c.getSource().getPlayerOrException(),"Right-click a Keepsake Globe to put in or take out one item. Hatch commands include invite <player>, close, deliver [note] and collect.");return 1;});
        root.then(Commands.literal("invite").then(Commands.argument("player",EntityArgument.player()).executes(c->{
            var recipient=EntityArgument.getPlayer(c,"player");
            return at(c,(p,f)->configureHatch(p,f,()->{
                if(f.invited.size()>=FurnitureEntity.GUEST_LIMIT && !f.invited.contains(recipient.getUUID())) throw new IllegalArgumentException("Sender list full.");
                f.invited.add(recipient.getUUID());tell(p,recipient.getGameProfile().getName()+" can now leave parcels.");
            }));
        })));
        root.then(Commands.literal("close").executes(c -> at(c,(p,f)->configureHatch(p,f,()->{
            f.invited.clear();tell(p,"All sender invitations removed.");
        }))));
        root.then(Commands.literal("deliver").executes(c -> at(c,(p,f)->deliver(p,f,"")))
                .then(Commands.argument("note",StringArgumentType.greedyString())
                .executes(c->at(c,(p,f)->deliver(p,f,text(StringArgumentType.getString(c,"note")))))));
        root.then(Commands.literal("collect").executes(c->at(c,Furniture::collect)));
        event.getDispatcher().register(Commands.literal("anya").then(root));
    }
}
