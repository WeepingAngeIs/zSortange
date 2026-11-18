package dev.anya.storage.pocket;

import dev.anya.storage.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import java.io.IOException;
import java.util.*;

/** Explicit server-raycast commands, not vanilla mining or mover callbacks. No inventory-bearing block items. */
public final class RoomEditor {
    private static final Set<Block> DECOR=Set.of(Blocks.STONE_BRICKS,Blocks.QUARTZ_BLOCK,Blocks.OAK_PLANKS,Blocks.CHERRY_PLANKS,Blocks.BLACK_CONCRETE,Blocks.PURPLE_CONCRETE,Blocks.CYAN_CONCRETE,Blocks.SEA_LANTERN);
    private static final Map<ServerPlayer,Long> LAST=new WeakHashMap<>();
    private static final Set<UUID> FAILED=new HashSet<>();
    static boolean quarantined(UUID pocket){return FAILED.contains(pocket);}
    private static boolean editing;
    public static boolean enabled(){return PocketService.enabled()&&ExpansionRules.get().pocketEditing;}
    public static boolean supported(Block block){return DECOR.contains(block)||block==Content.CRATE.get();}
    public static String help(){return "Experimental room editing: "+enabled()+". /anya pocket build place uses the plain held block on the face you look at; remove returns a block directly to inventory. Supported: stone bricks, quartz block, oak/cherry planks, black/purple/cyan concrete, sea lantern. Empty Anya chests: four reserved pads only. Build is separate from Visit and storage rights.";}
    // Server commands use the current pose, never a previous-tick interpolated camera.
    public static BlockHitResult looking(ServerPlayer p){
        if(p.pick(5,1.0f,false) instanceof BlockHitResult h&&h.getType()==HitResult.Type.BLOCK)return h;
        throw new IllegalArgumentException("Look at a block within five blocks");
    }
    private static PocketLedger.Pocket record(ServerPlayer p)throws IOException{
        var lease=PocketService.lease(p.getUUID());
        if(lease==null||lease.kind()!=PocketLeases.Kind.VISIT)throw new IOException("An active Visit is required to build");
        var r=PocketService.ledger(p.getServer()).get(lease.pocket());
        if(r==null||lease.epoch()!=r.epoch()||r.stage()!=PocketLedger.Stage.READY)throw new IOException("Pocket identity is stale");
        return r;
    }
    private static boolean sight(ServerPlayer p,BlockHitResult hit){
        if(!(p.pick(5,1.0f,false) instanceof BlockHitResult now)||now.getType()!=HitResult.Type.BLOCK)return false;
        return now.getBlockPos().equals(hit.getBlockPos())&&now.getDirection()==hit.getDirection();
    }
    private static String permission(ServerPlayer p,PocketLedger.Pocket r,BlockPos pos,BlockState state,boolean removal){
        if(!enabled()||!p.getServer().isSameThread())return "Room editing is gated OFF or off-thread";
        if(FAILED.contains(r.id()))return "Room edit fault: further edits quarantined for this server session; inspect logs and preserve the world";
        if(p.gameMode.getGameModeForPlayer()==GameType.ADVENTURE)return "Adventure mode cannot build";
        String denied=PocketService.authorize(p,r,PocketLeases.Kind.VISIT,false,BasePermissions.BUILD);
        if(!denied.isEmpty())return denied;
        if(!p.serverLevel().hasChunkAt(pos)||p.distanceToSqr(Vec3.atCenterOf(pos))>36)return "Build target is unloaded or out of reach";
        int x=pos.getX()-r.x(),y=pos.getY(),z=pos.getZ()-r.z();
        boolean chest=state.is(Content.CRATE.get());
        if(chest){
            if(!RoomBounds.chestPad(x,y,z))return "Supported chests may only occupy the four optional pads";
            denied=PocketService.authorize(p,r,PocketLeases.Kind.VISIT,false,BasePermissions.LEGACY|BasePermissions.BUILD);
            if(!denied.isEmpty())return "Chest editing additionally requires View, Deposit and Withdraw";
        }else if(!DECOR.contains(state.getBlock())||!RoomBounds.decoration(x,y,z))return "Unsupported block or protected room/exit boundary";
        return Safety.buildReason(p,p.serverLevel(),pos,state,removal);
    }
    public static Edit prepare(ServerPlayer p,BlockHitResult hit,boolean removal)throws IOException{
        if(p.getServer()==null||!p.getServer().isSameThread()||editing)throw new IOException("Overlapping or off-thread edit denied");
        if(!sight(p,hit))throw new IOException("Target is not the current server raycast");
        var r=record(p);BlockPos pos=removal?hit.getBlockPos():hit.getBlockPos().relative(hit.getDirection());
        if(!p.serverLevel().hasChunkAt(pos))throw new IOException("Build target is not loaded");
        BlockState proposed;
        if(removal)proposed=p.serverLevel().getBlockState(pos);
        else{
            ItemStack held=p.getMainHandItem();
            if(!(held.getItem() instanceof BlockItem bi)||!supported(bi.getBlock())||!ItemStack.isSameItemSameComponents(held,new ItemStack(held.getItem())))throw new IOException("Use a plain supported block; component-bearing items are not consumed");
            proposed=bi.getBlock().defaultBlockState();
            if(proposed.hasProperty(StorageBlock.FACING))proposed=proposed.setValue(StorageBlock.FACING,p.getDirection().getOpposite());
        }
        String denied=permission(p,r,pos,proposed,removal);if(!denied.isEmpty())throw new IOException(denied);
        var registry=PocketRoomLayout.present(p.serverLevel(),r);
        if(!removal){
            if(!p.serverLevel().isEmptyBlock(pos)||!p.serverLevel().getFluidState(pos).isEmpty())throw new IOException("Target is occupied; replacement is not supported");
            if(!p.serverLevel().isUnobstructed(proposed,pos,net.minecraft.world.phys.shapes.CollisionContext.empty()))throw new IOException("Do not build inside a player or entity");
            if(proposed.is(Content.CRATE.get())&&registry.size()>=Math.min(8,ExpansionRules.get().pocketContainers))throw new IOException("Pocket chest budget reached");
        }else if(proposed.is(Content.CRATE.get())){
            if(!(p.serverLevel().getBlockEntity(pos) instanceof StorageEntity c)||!r.id().equals(c.pocketId)||!r.owner().equals(c.owner)||!c.isEmpty()||Transactions.quarantined(c))throw new IOException("Only an empty registered optional chest may be removed; withdraw its contents first");
        }else if(p.serverLevel().getBlockEntity(pos)!=null)throw new IOException("Block entities are not decorative blocks");
        var hub=PocketService.home(p,r);return new Edit(p,r,hub,hit,pos,proposed,removal,registry);
    }
    public static void command(ServerPlayer p,boolean removal){
        try{String reason=prepare(p,looking(p),removal).commit();AnyaStorage.tell(p,reason.isEmpty()?"Room edit complete; no stored chest contents moved.":reason);}
        catch(IOException|IllegalArgumentException e){AnyaStorage.tell(p,e.getMessage());}
    }
    public static final class Edit {
        private final ServerPlayer p;
        private final PocketLedger.Pocket pocket;
        private final StorageEntity hub;
        private final long policy,config;
        private final BlockHitResult hit;
        private final BlockPos pos;
        private final BlockState proposed;
        private final boolean removal;
        private final List<StorageEntity> registry;
        private final Cell cell;
        private final Transactions.Plan plan;
        private boolean used;
        private Edit(ServerPlayer p,PocketLedger.Pocket r,StorageEntity hub,BlockHitResult hit,BlockPos pos,BlockState proposed,boolean removal,List<StorageEntity> registry)throws IOException{
            this.p=p;this.pocket=r;this.hub=hub;policy=hub.profileRevision;config=Config.get().revision;this.hit=hit;this.pos=pos;this.proposed=proposed;this.removal=removal;this.registry=registry;
            cell=new Cell(p,r,pos,proposed,removal);var main=new MainInventory(p);
            plan=new Transactions.Plan(List.of(cell,main),this::valid);
            int moved=removal?plan.move(cell,0,List.of(main),1):plan.move(main,p.getInventory().selected,List.of(cell),1);
            if(moved!=1)throw new IOException(removal?"Inventory full; block left untouched":"Held input changed; block left untouched");
        }
        private boolean valid(){
            try{
                var r=record(p);
                return r.equals(pocket)&&PocketService.home(p,r)==hub&&hub.profileRevision==policy&&Config.get().revision==config&&sight(p,hit)&&permission(p,r,pos,proposed,removal).isEmpty()&&registry.equals(PocketRoomLayout.present(p.serverLevel(),r))&&cell.originalUnchanged();
            }catch(IOException|RuntimeException e){return false;}
        }
        public String commit(){
            if(p.getServer()==null||!p.getServer().isSameThread()||editing||used)return "Repeated, overlapping or off-thread edit refused";
            used=true;long now=p.serverLevel().getGameTime();
            if(now-LAST.getOrDefault(p,Long.MIN_VALUE/2)<5)return "Room edit rate limit; no changes made";
            if(!plan.current())return "Build rights, view, inventory or world changed; no changes made";
            editing=true;
            try{
                String result=plan.commit(p.getServer());
                if(!result.isEmpty()){
                    if(Transactions.quarantined(cell)){FAILED.add(pocket.id());AnyaStorage.LOG.error("ROOM EDIT QUARANTINE {} at {}: {}. Preserve the world for inspection; do not replay.",pocket.id(),pos,result);}
                    return result;
                }
                LAST.put(p,now);p.inventoryMenu.broadcastChanges();
                return "";
            }finally{editing=false;}
        }
    }
    /** One represented world block participates in the existing snapshot/conservation transaction. */
    private static final class Cell implements Container {
        private final ServerPlayer p;
        private final PocketLedger.Pocket r;
        private final BlockPos pos;
        private final BlockState before,after;
        private final net.minecraft.world.level.block.entity.BlockEntity original;
        private final CompoundTag originalData;
        private final ItemStack input,output;
        Cell(ServerPlayer p,PocketLedger.Pocket r,BlockPos pos,BlockState proposed,boolean removal){
            this.p=p;this.r=r;this.pos=pos;before=p.serverLevel().getBlockState(pos);after=removal?Blocks.AIR.defaultBlockState():proposed;
            original=p.serverLevel().getBlockEntity(pos);originalData=original==null?null:original.saveWithFullMetadata(p.registryAccess());
            input=before.isAir()?ItemStack.EMPTY:new ItemStack(before.getBlock());output=after.isAir()?ItemStack.EMPTY:new ItemStack(after.getBlock());
        }
        boolean originalUnchanged(){return p.serverLevel().hasChunkAt(pos)&&p.serverLevel().getBlockState(pos)==before&&p.serverLevel().getBlockEntity(pos)==original&&(original==null||original instanceof StorageEntity c&&c.isEmpty());}
        public int getContainerSize(){return 1;}
        public boolean isEmpty(){return getItem(0).isEmpty();}
        public ItemStack getItem(int slot){
            var state=p.serverLevel().getBlockState(pos);
            if(state==before)return input.copy();if(state==after)return output.copy();throw new IllegalStateException("World cell changed outside supported edit");
        }
        public boolean canPlaceItem(int slot,ItemStack stack){return slot==0&&stack.getCount()==1&&(Transactions.same(stack,input)||Transactions.same(stack,output));}
        public void setItem(int slot,ItemStack next){
            if(slot!=0||(!Transactions.same(next,input)&&!Transactions.same(next,output)))throw new IllegalArgumentException("Unrecognized block transaction");
            var level=p.serverLevel();var state=level.getBlockState(pos);
            if(state!=before&&state!=after)throw new IllegalStateException("Refusing to overwrite a changed world cell");
            if(level.getBlockEntity(pos) instanceof Container inventory&&!inventory.isEmpty())throw new IllegalStateException("Refusing to remove occupied storage");
            boolean restore=Transactions.same(next,input);BlockState target=restore?before:after;
            if(state!=target&&!level.setBlock(pos,target,3))throw new IllegalStateException("World refused block edit");
            if(target.is(Content.CRATE.get())){
                if(!(level.getBlockEntity(pos) instanceof StorageEntity c))throw new IllegalStateException("Supported chest did not create expected block entity");
                if(restore&&originalData!=null)c.loadWithComponents(originalData,p.registryAccess());
                else{c.owner=r.owner();c.pocketId=r.id();c.storageIdentity();c.setChanged();}
            }
            if(level.getBlockState(pos)!=target)throw new IllegalStateException("World edit failed verification");
        }
        public void setChanged(){}
        public boolean stillValid(Player player){return player==p;}
        public ItemStack removeItem(int i,int amount){throw new UnsupportedOperationException();}
        public ItemStack removeItemNoUpdate(int i){throw new UnsupportedOperationException();}
        public void clearContent(){throw new UnsupportedOperationException();}
    }
    public static void clear(){LAST.clear();FAILED.clear();editing=false;}
    private RoomEditor(){}
}
