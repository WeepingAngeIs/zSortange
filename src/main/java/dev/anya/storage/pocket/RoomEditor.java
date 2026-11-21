package dev.anya.storage.pocket;

import dev.anya.storage.*;
import dev.anya.storage.furniture.Furniture;
import dev.anya.storage.furniture.FurnitureEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.bus.api.ICancellableEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.minecraft.world.level.block.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import java.io.IOException;
import java.util.*;

/** The physical room tools and commands share one server-raycast build transaction. */
public final class RoomEditor {
    // A Visit lease is checked separately; chest changes need all four rights at the home hub.
    static final int CHEST_EDIT_RIGHTS = BasePermissions.BUILD | BasePermissions.LEGACY;
    /** Plain, single-cell vanilla construction implementations. Subclasses can add placement side effects. */
    private static final Set<Class<?>> BUILDING_TYPES=Set.of(Block.class,RotatedPillarBlock.class,StairBlock.class,
            SlabBlock.class,TransparentBlock.class,StainedGlassBlock.class,GlazedTerracottaBlock.class,
            AmethystBlock.class,IronBarsBlock.class,StainedGlassPaneBlock.class,WallBlock.class,FenceBlock.class);
    private static boolean buildingBlock(Block block){
        return net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).getNamespace().equals("minecraft")
                && BUILDING_TYPES.contains(block.getClass()) && block.asItem() instanceof BlockItem item
                && item.getClass()==BlockItem.class && !block.defaultBlockState().hasBlockEntity()
                && !(block instanceof net.minecraft.world.level.block.GameMasterBlock);
    }
    private static final Map<ServerPlayer,Long> LAST=new WeakHashMap<>();
    private static final Set<UUID> FAILED=new HashSet<>();
    private static final Map<UUID,Access> MODES=new HashMap<>();
    private static BlockEvent permissionEvent;
    static boolean permissionEvent(BlockEvent event) { return permissionEvent==event; }
    /** Only this exact event bypasses our blanket protection; nested or unrelated edits remain blocked. */
    private static <T extends BlockEvent & ICancellableEvent> boolean vetoed(T event) {
        if(permissionEvent!=null)return true;
        permissionEvent=event;
        try { return NeoForge.EVENT_BUS.post(event).isCanceled(); }
        catch (RuntimeException problem) {
            AnyaStorage.LOG.warn("Room edit denied because a block permission listener failed at {}",event.getPos(),problem);
            return true;
        }
        finally { permissionEvent=null; }
    }
    public static boolean quarantined(UUID pocket){return FAILED.contains(pocket);}
    private static boolean editing;
    public static boolean enabled(){return PocketService.enabled()&&ExpansionRules.get().pocketEditing;}
    public static boolean supported(Block block){return buildingBlock(block)||block==Content.CRATE.get()||Furniture.isFurniture(block);}
    static boolean canAddChest(int registeredChests, int configuredLimit) {
        return registeredChests >= 4 && registeredChests < Math.min(8, configuredLimit);
    }
    public static String help(){return "Room editing enabled = "+enabled()+". Enter with Visit and Build access to edit. Your linked tablet can pause or resume editing. Right-click with a plain supported block in your main hand to place it. Left-click to return a block to your inventory. Place a plain Labelled Storage Chest on clear room floor.";}
    // Server commands use the current pose, never a previous-tick interpolated camera.
    public static BlockHitResult looking(ServerPlayer p){
        BlockHitResult hit = loadedHit(p);
        if (hit != null) return hit;
        throw new IllegalArgumentException("Look at a block within five blocks");
    }
    private static BlockHitResult loadedHit(ServerPlayer p) {
        var blocks = new LoadedPocketBlocks(p.serverLevel());
        Vec3 eye = p.getEyePosition(1.0f);
        var ray = new ClipContext(eye, eye.add(p.getViewVector(1.0f).scale(5)),
                ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p);
        BlockHitResult hit = blocks.clip(ray);
        return blocks.complete() && hit.getType() == HitResult.Type.BLOCK ? hit : null;
    }
    private static PocketLedger.Pocket record(ServerPlayer p)throws IOException{
        var lease=PocketService.lease(p.getUUID());
        if(lease==null||lease.kind()!=PocketLeases.Kind.VISIT)throw new IOException("An active Visit is required to build");
        var r=PocketService.ledger(p.getServer()).get(lease.pocket());
        if(r==null||lease.epoch()!=r.epoch()||r.stage()!=PocketLedger.Stage.READY)throw new IOException("Pocket identity is stale");
        return r;
    }
    /** Captured before invoking claims/addons. A final check uses only current identities and local permission bits. */
    record Access(ServerLevel level, PocketLedger.Pocket room, StorageEntity hub, BlockEntity entrance,
                  long visitDeadline, long policy, long config) {
        static Access capture(ServerPlayer p, PocketLedger.Pocket room) throws IOException {
            var hub = PocketService.home(p,room);
            var lease = PocketService.lease(p.getUUID());
            if (hub == null || lease == null || p.serverLevel() != PocketService.dimension(p.getServer()))
                throw new IOException("The active visit or home changed.");
            var entrance = hub.getLevel().getBlockEntity(BlockPos.of(room.anchor().entrance()));
            return new Access(p.serverLevel(),room,hub,entrance,lease.deadline(),hub.profileRevision,Config.get().revision);
        }
        boolean current(ServerPlayer p, int rights) {
            var lease = PocketService.lease(p.getUUID());
            return enabled() && !quarantined(room.id()) && p.serverLevel() == level && p.isAlive() && !p.isSpectator()
                    && p.gameMode.getGameModeForPlayer() != GameType.ADVENTURE && PocketService.present(p)
                    && room.contains(p.getBlockX(),p.getBlockY(),p.getBlockZ())
                    && lease != null && lease.kind() == PocketLeases.Kind.VISIT && lease.deadline() == visitDeadline
                    && lease.epoch() == room.epoch() && room.id().equals(lease.pocket())
                    && PocketService.loadedPocket(p.getServer(),room.id()) == room && Config.get().revision == config
                    && PocketService.home(p,room) == hub && !hub.isRemoved() && hub.profileRevision == policy
                    && !Transactions.quarantined(hub) && hub.getLevel().getBlockEntity(BlockPos.of(room.anchor().entrance())) == entrance
                    && hub.allowed(p) && hub.permits(p,BasePermissions.VISIT | rights);
        }
    }

    private static Access builderAccess(ServerPlayer p) throws IOException {
        if (p.getServer() == null || !p.getServer().isSameThread() || !PocketService.inPocket(p.level()))
            throw new IOException("Visit the pocket room to edit it.");
        if (!enabled()) throw new IOException("Room editing is turned off in the server settings.");
        if (p.gameMode.getGameModeForPlayer() == GameType.ADVENTURE) throw new IOException("Adventure mode cannot build.");
        var room = record(p);
        var returning = PocketService.ledger(p.getServer()).returning(p.getUUID());
        if (returning == null || !room.id().equals(returning.pocket()) || PocketService.recovering(p.getUUID()))
            throw new IOException("Enter this pocket through its Skygrid Cube to edit.");
        var access = Access.capture(p,room);
        if (!access.hub().permits(p,BasePermissions.VISIT | BasePermissions.BUILD))
            throw new IOException("The pocket owner must grant both Visit and Build.");
        String denied = PocketService.authorize(p,room,PocketLeases.Kind.VISIT,false,BasePermissions.BUILD);
        if (!denied.isEmpty()) throw new IOException(denied);
        if (!access.current(p,BasePermissions.BUILD)) throw new IOException("Your visit or Build access changed.");
        return access;
    }

    public static String modeDenial(ServerPlayer p) {
        try { builderAccess(p); return ""; }
        catch (IOException problem) { return problem.getMessage(); }
    }

    public static String setMode(ServerPlayer p, boolean on) {
        if (p.getServer() == null || !p.getServer().isSameThread()) return "Room controls need the server thread.";
        if (on) {
            try { MODES.put(p.getUUID(),builderAccess(p)); }
            catch (IOException problem) { endVisit(p.getUUID()); return problem.getMessage(); }
            notice(p,"Room editing on. Right-click to place a block. Left-click to take it back.");
        } else {
            endVisit(p.getUUID());
            notice(p,"Room editing off.");
        }
        return "";
    }

    public static boolean active(ServerPlayer p) {
        if (p.getServer() == null || !p.getServer().isSameThread()) return false;
        var access = MODES.get(p.getUUID());
        if (access == null) return false;
        if (access.current(p,BasePermissions.BUILD)
                && PocketService.authorize(p,access.room(),PocketLeases.Kind.VISIT,false,BasePermissions.BUILD).isEmpty()
                && access.current(p,BasePermissions.BUILD) && MODES.get(p.getUUID()) == access) return true;
        if (MODES.remove(p.getUUID(),access)) notice(p,"Room editing ended because your visit or Build access changed.");
        return false;
    }

    static void tick(net.minecraft.server.MinecraftServer server) {
        if (MODES.isEmpty() || server.getTickCount() % 10 != 0) return;
        for (UUID id : List.copyOf(MODES.keySet())) {
            var p = server.getPlayerList().getPlayer(id);
            if (p == null) endVisit(id); else active(p);
        }
    }

    static void endVisit(UUID id) { MODES.remove(id); }
    private static void notice(ServerPlayer p, String text) {
        p.displayClientMessage(net.minecraft.network.chat.Component.literal(text),true);
    }

    /** Event coordinates only identify the click. The server ray and current Visit control every edit. */
    static boolean click(ServerPlayer p, BlockPos clicked, net.minecraft.core.Direction face, boolean removal) {
        var mode = MODES.get(p.getUUID());
        if (mode == null || !active(p)) return false;
        if (p.containerMenu != p.inventoryMenu) return true;
        try {
            var hit = looking(p);
            if (!hit.getBlockPos().equals(clicked) || hit.getDirection() != face)
                throw new IOException("Aim at the face you want to edit within reach.");
            if (!canClick(p,clicked,removal)) throw new IOException("You cannot edit that spot with your current reach, abilities or cooldown.");
            var edit = prepare(p,hit,removal,mode);
            String result = edit.commit();
            if (!result.isEmpty()) notice(p,result);
        } catch (IOException | IllegalArgumentException problem) { notice(p,problem.getMessage()); }
        return true;
    }
    private static boolean canClick(ServerPlayer p, BlockPos support, boolean removal) {
        return p.getAbilities().mayBuild && p.canInteractWithBlock(support,0.0) && p.serverLevel().mayInteract(p,support)
                && (removal || !p.getCooldowns().isOnCooldown(p.getMainHandItem().getItem()));
    }
    private static int rights(BlockState state) {
        return state.is(Content.CRATE.get()) || state.is(Content.HATCH.get()) ? CHEST_EDIT_RIGHTS : BasePermissions.BUILD;
    }
    private static boolean sight(ServerPlayer p,BlockHitResult hit){
        BlockHitResult now = loadedHit(p);
        return now != null && now.getBlockPos().equals(hit.getBlockPos()) && now.getDirection() == hit.getDirection();
    }
    private static boolean unobstructed(ServerPlayer p, BlockPos pos, BlockState state) {
        return p.serverLevel().isUnobstructed(state, pos, CollisionContext.empty());
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
            if(y!=RoomBounds.CHEST_Y || !RoomBounds.decoration(x,y,z))return "Choose room floor away from the walls, starter chests and exit path.";
            if(!removal && (!p.serverLevel().getBlockState(pos.below()).isFaceSturdy(p.serverLevel(),pos.below(),net.minecraft.core.Direction.UP)
                    || !p.serverLevel().isEmptyBlock(pos.above())))return "Choose a solid floor spot with clear space above the chest.";
            denied=PocketService.authorize(p,r,PocketLeases.Kind.VISIT,false,CHEST_EDIT_RIGHTS);
            if(!denied.isEmpty())return "Chest editing additionally requires View, Deposit and Withdraw";
        }else if(!removal && p.serverLevel().getBlockState(pos.below()).is(Content.CRATE.get()))return "Leave the space above the chest clear.";
        else if(!RoomBounds.decoration(x,y,z))return "Keep the room shell, chest approaches and exit path clear.";
        else if(!supported(state.getBlock()))return "This block needs special placement or removal handling.";
        if (state.is(Content.HATCH.get())) {
            denied = PocketService.authorize(p,r,PocketLeases.Kind.VISIT,false,CHEST_EDIT_RIGHTS);
            if (!denied.isEmpty()) return "Furniture needs Build, View, Deposit and Withdraw.";
        }
        // Vanilla may reshape adjoining fences, panes, walls or stairs after the target changes.
        // Treat those neighbours as affected building locations before authorizing the write.
        for(var direction:net.minecraft.core.Direction.values()){
            var near=pos.relative(direction);
            if(!p.serverLevel().hasChunkAt(near))return "A neighbouring build location is unloaded.";
            var adjacent=p.serverLevel().getBlockState(near);
            var block=adjacent.getBlock();
            if(!(block instanceof FenceBlock || block instanceof IronBarsBlock || block instanceof WallBlock || block instanceof StairBlock))continue;
            if(!RoomBounds.decoration(near.getX()-r.x(),near.getY(),near.getZ()-r.z()))return "This edit could reshape a protected neighbouring block.";
            String neighbour=Safety.buildReason(p,p.serverLevel(),near,adjacent,removal);
            if(!neighbour.isEmpty())return neighbour;
        }
        return Safety.buildReason(p,p.serverLevel(),pos,state,removal);
    }
    public static Edit prepare(ServerPlayer p,BlockHitResult hit,boolean removal)throws IOException{
        return prepare(p,hit,removal,null);
    }
    private static Edit prepare(ServerPlayer p,BlockHitResult hit,boolean removal,Access mode)throws IOException{
        if(p.getServer()==null||!p.getServer().isSameThread()||editing)throw new IOException("Overlapping or off-thread edit denied");
        if(!sight(p,hit))throw new IOException("Target is not the current server raycast");
        var r=record(p);BlockPos pos=removal?hit.getBlockPos():hit.getBlockPos().relative(hit.getDirection());
        var access=Access.capture(p,r);
        if(!p.serverLevel().hasChunkAt(pos))throw new IOException("Build target is not loaded");
        BlockState proposed;
        if(removal)proposed=p.serverLevel().getBlockState(pos);
        else{
            ItemStack held=p.getMainHandItem();
            if(!(held.getItem() instanceof BlockItem bi))throw new IOException("Hold a building block in your main hand.");
            if(!supported(bi.getBlock()))throw new IOException("This block needs special placement handling. Your item was kept.");
            if(!ItemStack.isSameItemSameComponents(held,new ItemStack(held.getItem())))throw new IOException("This item has custom data that room placement cannot preserve. Your item was kept.");
            if(buildingBlock(bi.getBlock())){
                var context=new BlockPlaceContext(p,net.minecraft.world.InteractionHand.MAIN_HAND,held,hit);
                if(!context.getClickedPos().equals(pos))throw new IOException("Choose empty space. Combining slabs or replacing a block in place is not supported.");
                proposed=bi.getBlock().getStateForPlacement(context);
                if(proposed==null || !proposed.is(bi.getBlock()) || !proposed.canSurvive(p.serverLevel(),pos))
                    throw new IOException("This block cannot stand in that spot.");
            }else{
                proposed=bi.getBlock().defaultBlockState();
                if(proposed.hasProperty(StorageBlock.FACING))proposed=proposed.setValue(StorageBlock.FACING,p.getDirection().getOpposite());
            }
        }
        if(removal && (proposed.hasProperty(SlabBlock.TYPE) && proposed.getValue(SlabBlock.TYPE)==net.minecraft.world.level.block.state.properties.SlabType.DOUBLE
                || !proposed.getFluidState().isEmpty()))throw new IOException("This spot contains two slabs or a fluid. Single-block removal cannot refund it safely.");
        String denied=permission(p,r,pos,proposed,removal);if(!denied.isEmpty())throw new IOException(denied);
        if (!access.current(p,rights(proposed)) || !sight(p,hit) || removal && p.serverLevel().getBlockState(pos) != proposed)
            throw new IOException("Room or target changed during the build check.");
        var registry=proposed.is(Content.CRATE.get())?PocketRoomLayout.present(p.serverLevel(),r):List.<StorageEntity>of();
        if(!removal){
            if(!p.serverLevel().isEmptyBlock(pos)||!p.serverLevel().getFluidState(pos).isEmpty())throw new IOException("Target is occupied; replacement is not supported");
            if(!unobstructed(p,pos,proposed))throw new IOException("Do not build inside a player or entity");
            if(proposed.is(Content.CRATE.get())&&!canAddChest(registry.size(),ExpansionRules.get().pocketContainers))throw new IOException("Pocket chest budget reached");
        }else if(proposed.is(Content.CRATE.get())){
            if(!(p.serverLevel().getBlockEntity(pos) instanceof StorageEntity c)||!r.id().equals(c.pocketId)||!r.owner().equals(c.owner)||!c.isEmpty()||Transactions.quarantined(c))throw new IOException("Only an empty registered optional chest may be removed; withdraw its contents first");
        }else if (Furniture.isFurniture(proposed.getBlock())) {
            if (!(p.serverLevel().getBlockEntity(pos) instanceof FurnitureEntity furniture)
                    || !furniture.belongsTo(r.owner(), r.id()) || !furniture.ownerMayRemove(p) || !furniture.emptyForRemoval())
                throw new IOException("Empty a globe before removing it. Only the owner can remove an empty delivery hatch.");
        }else if(p.serverLevel().getBlockEntity(pos)!=null)throw new IOException("Block entities are not decorative blocks");
        return new Edit(p,access,hit,pos,proposed,removal,registry,mode);
    }
    public static void command(ServerPlayer p,boolean removal){
        try {
            Edit edit = prepare(p, looking(p), removal);
            String result = edit.commit();
            if (result.isEmpty()) {
                result = !removal && edit.proposed.is(Content.CRATE.get())
                        ? "Added one Labelled Storage Chest and 54 pocket slots. Right-click the chest to open it."
                        : "Room edit complete; no stored chest contents moved.";
            }
            AnyaStorage.tell(p, result);
        } catch (IOException | IllegalArgumentException e) {
            AnyaStorage.tell(p, e.getMessage());
        }
    }

    record Mark(BlockPos pos, BlockState state, BlockHitResult face, BlockState support) {
        boolean addsChest() { return state.is(Content.CRATE.get()); }
    }

    /** Marks came from individual server raycasts. Recheck sight to each marked face from the builder's current position. */
    private static boolean visibleFace(ServerPlayer player, Mark mark) {
        var blocks = new LoadedPocketBlocks(player.serverLevel());
        Vec3 eye = player.getEyePosition(1);
        Vec3 end = mark.face().getLocation().subtract(Vec3.atLowerCornerOf(mark.face().getDirection().getNormal()).scale(.001));
        var hit = blocks.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return blocks.complete() && hit.getType() == HitResult.Type.BLOCK
                && hit.getBlockPos().equals(mark.face().getBlockPos()) && hit.getDirection() == mark.face().getDirection()
                && blocks.getBlockState(hit.getBlockPos()) == mark.support();
    }

    private static String batchDenial(ServerPlayer player, Access access, List<Mark> marks) throws IOException {
        long chestAdds = marks.stream().filter(Mark::addsChest).count();
        if (chestAdds > 1) return RoomPlans.ONE_CHEST;
        int required = BasePermissions.BUILD;
        for (Mark mark : marks) {
            String denied = permission(player, access.room(), mark.pos(), mark.state(), false);
            if (!denied.isEmpty()) return denied;
            required |= rights(mark.state());
        }
        if (!access.current(player, required)) return "The pocket visit or your rights changed.";
        int chests = PocketRoomLayout.present(access.level(),access.room()).size();
        if (chestAdds == 1 && !canAddChest(chests, ExpansionRules.get().pocketContainers))
            return "The planned chest exceeds the room's chest limit.";
        for (Mark mark : marks) {
            if (!access.level().hasChunkAt(mark.pos()) || player.distanceToSqr(Vec3.atCenterOf(mark.pos())) > 36)
                return "Stand within reach of all marked spots.";
            if (!player.serverLevel().isEmptyBlock(mark.pos()) || !player.serverLevel().getFluidState(mark.pos()).isEmpty()
                    || player.serverLevel().getBlockEntity(mark.pos()) != null) return "A planned spot is occupied.";
            if (!unobstructed(player, mark.pos(), mark.state())) return "A player or entity is in a planned spot.";
            if (!visibleFace(player, mark)) return "Stand where you can see and reach the faces you marked.";
        }
        return "";
    }

    static void apply(ServerPlayer player, RoomPlans.Draft draft) throws IOException {
        if (player.getServer() == null || !player.getServer().isSameThread() || editing)
            throw new IOException("Another room edit is in progress.");
        long now = player.serverLevel().getGameTime();
        if (now - LAST.getOrDefault(player, Long.MIN_VALUE / 2) < 5) throw new IOException("Give the room a moment between edits.");
        var room = record(player);
        if (!draft.current(player,room)) throw new IOException("This draft's visit or permissions changed.");
        var marks = List.copyOf(draft.marks.values());
        if (marks.isEmpty() || marks.size() > RoomPlans.LIMIT) throw new IOException("Mark between one and eight spots.");
        String denied = batchDenial(player, draft.access, marks);
        if (!denied.isEmpty()) throw new IOException(denied);
        var cells = marks.stream().map(m -> new Cell(player, draft.access, m.pos(), m.state(), false, null)).toList();
        var main = new MainInventory(player);
        List<Container> affected = new ArrayList<>(cells);
        affected.add(main);
        var plan = new Transactions.Plan(affected, () -> {
            try {
                return draft.current(player,record(player)) && batchDenial(player,draft.access,marks).isEmpty()
                        && cells.stream().allMatch(Cell::originalUnchanged);
            } catch (IOException problem) { return false; }
        });
        stageMaterials(plan, main, cells, marks.stream().map(Mark::state).toList());
        editing = true;
        try {
            String result = plan.commit(player.getServer());
            if (!result.isEmpty()) {
                if (affected.stream().anyMatch(Transactions::quarantined)) {
                    FAILED.add(room.id());
                    AnyaStorage.LOG.error("ROOM PLAN QUARANTINE {}: {}. Preserve the world for inspection.", room.id(), result);
                }
                throw new IOException(result);
            }
            LAST.put(player,now);
            player.inventoryMenu.broadcastChanges();
        } finally { editing = false; }
    }

    static void stageMaterials(Transactions.Plan plan, Container main, List<? extends Container> cells, List<BlockState> states) throws IOException {
        for (int i = 0; i < states.size(); i++) {
            ItemStack wanted = new ItemStack(states.get(i).getBlock());
            boolean paid = false;
            for (int slot = 0; slot < main.getContainerSize(); slot++) {
                if (ItemStack.isSameItemSameComponents(wanted,plan.work(main)[slot])
                        && plan.move(main,slot,List.of(cells.get(i)),1) == 1) { paid = true; break; }
            }
            if (!paid) throw new IOException("Your inventory needs another plain " + wanted.getHoverName().getString() + ".");
        }
    }
    public static final class Edit {
        private final ServerPlayer p;
        private final PocketLedger.Pocket pocket;
        private final Access access;
        private final BlockHitResult hit;
        private final BlockPos pos;
        private final BlockState proposed;
        private final boolean removal;
        private final List<StorageEntity> registry;
        private final Cell cell;
        private final MainInventory main;
        private final Transactions.Plan plan;
        private final int inputSlot;
        private boolean used;
        Mark mark() { return new Mark(pos,proposed,hit,p.serverLevel().getBlockState(hit.getBlockPos())); }
        Access access() { return access; }
        private Edit(ServerPlayer p,Access access,BlockHitResult hit,BlockPos pos,BlockState proposed,boolean removal,List<StorageEntity> registry,Access mode)throws IOException{
            this.p=p;this.pocket=access.room();this.access=access;this.hit=hit;this.pos=pos;this.proposed=proposed;this.removal=removal;this.registry=registry;
            cell=new Cell(p,access,pos,proposed,removal,mode);main=new MainInventory(p);
            inputSlot=p.getInventory().selected;
            plan=new Transactions.Plan(List.of(cell,main),this::valid);
            int moved=removal?plan.move(cell,0,List.of(main),1):plan.move(main,inputSlot,List.of(cell),1);
            if(moved!=1)throw new IOException(removal?"Inventory full; block left untouched":"Held input changed; block left untouched");
        }
        private boolean valid(){
            try{
                var r=record(p);
                if (!removal && proposed.is(Content.CRATE.get()) && !canAddChest(registry.size(), ExpansionRules.get().pocketContainers)) return false;
                return (removal || p.getInventory().selected==inputSlot) && r.equals(pocket)&&access.current(p,rights(proposed))&&permission(p,r,pos,proposed,removal).isEmpty()
                        &&access.current(p,rights(proposed))&&sight(p,hit)&&(!proposed.is(Content.CRATE.get())||registry.equals(PocketRoomLayout.present(access.level(),r)))&&cell.originalUnchanged()
                        && (cell.mode == null || canClick(p,hit.getBlockPos(),removal))
                        && (removal || unobstructed(p, pos, proposed));
            }catch(IOException|RuntimeException e){return false;}
        }
        public String commit(){
            if(p.getServer()==null||!p.getServer().isSameThread()||editing||used)return "Repeated, overlapping or off-thread edit refused";
            used=true;long now=p.serverLevel().getGameTime();
            if(now-LAST.getOrDefault(p,Long.MIN_VALUE/2)<5)return "Give the last edit a moment, then try again.";
            if(!plan.current())return "Build rights, view, inventory or world changed; no changes made";
            editing=true;
            try{
                if (cell.mode != null) {
                    if (removal && vetoed(new BlockEvent.BreakEvent(p.serverLevel(),pos,proposed,p)))
                        return "Another mod denied removing this block.";
                    if (!removal) {
                        String denial = cell.placementDenial(hit.getBlockPos());
                        if (!denial.isEmpty()) return denial;
                    }
                }
                // The permission event has finished. The plan rechecks the room, inventory and rights before any writes.
                String result=plan.commit(p.getServer());
                if(!result.isEmpty()){
                    if(Transactions.quarantined(cell)||Transactions.quarantined(main)){FAILED.add(pocket.id());AnyaStorage.LOG.error("ROOM EDIT QUARANTINE {} at {}: {}. Preserve the world for inspection; do not replay.",pocket.id(),pos,result);}
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
        private final Access access;
        private final ServerLevel level;
        private final int required;
        private final Access mode;
        private final BlockPos pos;
        private final BlockState before,after;
        private final net.minecraft.world.level.block.entity.BlockEntity original;
        private final CompoundTag originalData;
        private final ItemStack input,output;
        Cell(ServerPlayer p,Access access,BlockPos pos,BlockState proposed,boolean removal,Access mode){
            this.mode=mode;
            this.p=p;this.r=access.room();this.access=access;this.level=access.level();this.required=rights(proposed);
            this.pos=pos;before=level.getBlockState(pos);after=removal?Blocks.AIR.defaultBlockState():proposed;
            original=level.getBlockEntity(pos);originalData=original==null?null:original.saveWithFullMetadata(level.registryAccess());
            input=before.isAir()?ItemStack.EMPTY:new ItemStack(before.getBlock());output=after.isAir()?ItemStack.EMPTY:new ItemStack(after.getBlock());
        }
        boolean originalUnchanged(){
            if (!modeCurrent()) return false;
            if (!level.hasChunkAt(pos) || level.getBlockState(pos) != before || level.getBlockEntity(pos) != original) return false;
            if (original == null) return true;
            boolean removable = original instanceof StorageEntity c && c.isEmpty() && !Transactions.quarantined(c)
                    || original instanceof FurnitureEntity f && f.belongsTo(r.owner(),r.id()) && f.emptyForRemoval() && f.ownerMayRemove(p);
            return removable && originalData.equals(original.saveWithFullMetadata(level.registryAccess()));
        }
        public int getContainerSize(){return 1;}
        public boolean isEmpty(){return getItem(0).isEmpty();}
        public ItemStack getItem(int slot){
            var state=level.getBlockState(pos);
            if(state==before)return input.copy();if(state==after)return output.copy();throw new IllegalStateException("World cell changed outside supported edit");
        }
        public boolean canPlaceItem(int slot,ItemStack stack){return slot==0&&stack.getCount()==1&&(Transactions.same(stack,input)||Transactions.same(stack,output));}
        public void setItem(int slot,ItemStack next){
            if(slot!=0||(!Transactions.same(next,input)&&!Transactions.same(next,output)))throw new IllegalArgumentException("Unrecognized block transaction");
            if (!level.hasChunkAt(pos)) throw new IllegalStateException("The target chunk unloaded.");
            var state=level.getBlockState(pos);
            if(state!=before&&state!=after)throw new IllegalStateException("Refusing to overwrite a changed world cell");
            if(level.getBlockEntity(pos) instanceof Container inventory&&!inventory.isEmpty())throw new IllegalStateException("Refusing to remove occupied storage");
            if(level.getBlockEntity(pos) instanceof FurnitureEntity furniture&&!furniture.emptyForRemoval())throw new IllegalStateException("Furniture still contains an item or delivery");
            boolean restore=Transactions.same(next,input);BlockState target=restore?before:after;
            if (!restore && (!access.current(p,required) || !originalUnchanged()))
                throw new IllegalStateException("The room or builder changed.");
            if (!restore && before.isAir() && state != target && !unobstructed(p, pos, target))
                throw new IllegalStateException("Do not build inside a player or entity");
            if(state!=target && !level.setBlock(pos,target,3))throw new IllegalStateException("World refused block edit");
            if(target.is(Content.CRATE.get())){
                if(!(level.getBlockEntity(pos) instanceof StorageEntity c))throw new IllegalStateException("Supported chest did not create expected block entity");
                if(restore&&originalData!=null)c.loadWithComponents(originalData,level.registryAccess());
                else{c.owner=r.owner();c.pocketId=r.id();c.storageIdentity();c.setChanged();}
            }
            if (Furniture.isFurniture(target.getBlock())) {
                if (!(level.getBlockEntity(pos) instanceof FurnitureEntity furniture))
                    throw new IllegalStateException("Missing furniture after placement");
                if (restore && originalData != null) furniture.loadWithComponents(originalData,level.registryAccess());
                else furniture.initialize(r.owner(), r.id());
            }
            if(level.getBlockState(pos)!=target)throw new IllegalStateException("World edit failed verification");
            if (!restore && (!access.current(p,required) || !modeCurrent())) throw new IllegalStateException("Build access changed during placement.");
        }
        /** Let placement listeners inspect the real proposed state before committing any inventory or block changes. */
        private String placementDenial(BlockPos against) {
            if (level.captureBlockSnapshots || level.restoringBlockSnapshots || !level.capturedBlockSnapshots.isEmpty())
                return "Another placement is active.";
            List<BlockSnapshot> captured = new ArrayList<>();
            boolean keepForeignChange = false;
            try {
                boolean placed;
                level.captureBlockSnapshots = true;
                try { placed = level.setBlock(pos,after,Block.UPDATE_ALL); }
                finally {
                    level.captureBlockSnapshots = false;
                    captured.addAll(level.capturedBlockSnapshots);
                    level.capturedBlockSnapshots.clear();
                }
                if (!placed || captured.size() != 1 || !pos.equals(captured.getFirst().getPos()))
                    return "The room cannot place this block here.";

                BlockEntity preview = level.getBlockEntity(pos);
                boolean denied;
                try { denied = vetoed(new BlockEvent.EntityPlaceEvent(captured.getFirst(),level.getBlockState(against),p)); }
                finally {
                    // Ownership annotations on the trial are disposable. Preserve an independent replacement or real deposit.
                    keepForeignChange = !level.getBlockState(pos).is(after.getBlock()) || level.getBlockEntity(pos) != preview;
                    if (!keepForeignChange && (preview instanceof Container inventory && !inventory.isEmpty()
                            || preview instanceof FurnitureEntity furniture && !furniture.emptyForRemoval())) {
                        keepForeignChange = true;
                        FAILED.add(r.id());
                        AnyaStorage.LOG.error("ROOM EDIT QUARANTINE {} at {}: a placement listener deposited contents in the provisional block. Contents preserved.",r.id(),pos);
                    }
                }
                if (FAILED.contains(r.id())) return "The room kept an unexpected deposit. Editing is paused for operator inspection.";
                if (keepForeignChange) return "Another mod changed that spot. No materials were taken.";
                return denied ? "Another mod denied placing this block." : "";
            } finally {
                level.restoringBlockSnapshots = true;
                try {
                    for (var snapshot : captured.reversed()) {
                        if (keepForeignChange && pos.equals(snapshot.getPos())) continue;
                        if (snapshot.getCurrentState() != snapshot.getState() && !snapshot.restore(Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS)) {
                            FAILED.add(r.id());
                            AnyaStorage.LOG.error("ROOM EDIT QUARANTINE {}: could not restore placement check at {}",r.id(),snapshot.getPos());
                        }
                    }
                } finally {
                    level.restoringBlockSnapshots = false;
                }
            }
        }
        private boolean modeCurrent() {
            return mode == null || MODES.get(p.getUUID()) == mode && p.containerMenu == p.inventoryMenu && mode.current(p,required);
        }
        public void setChanged(){}
        public boolean stillValid(Player player){return player==p;}
        public ItemStack removeItem(int i,int amount){throw new UnsupportedOperationException();}
        public ItemStack removeItemNoUpdate(int i){throw new UnsupportedOperationException();}
        public void clearContent(){throw new UnsupportedOperationException();}
    }
    public static void clear(){RoomPlans.clear();MODES.clear();LAST.clear();FAILED.clear();editing=false;}
    private RoomEditor(){}
}
