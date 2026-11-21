package dev.anya.storage;

import dev.anya.storage.appearance.Looks;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** The one private slot is never exposed as a container or item capability to automation. */
public final class PlacedBackpackEntity extends BlockEntity {
    private final SimpleContainer slot = new SimpleContainer(1);
    private UUID owner;
    private Tag unreadable;
    private ItemStack publicAppearance = ItemStack.EMPTY;
    private String lastPublished = "";

    public PlacedBackpackEntity(BlockPos pos, BlockState state) {
        super(Content.PLACED_BAG_ENTITY.get(),pos,state);
        slot.addListener(ignored -> changedBag());
    }
    private void changedBag() {
        setChanged();
        if (level == null || level.isClientSide || isRemoved()) return;
        String look = slot.isEmpty() ? "" : Looks.of(slot.getItem(0)).texture();
        if (!look.equals(lastPublished)) {
            lastPublished = look;
            level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),2);
        }
    }
    void place(ServerPlayer player, ItemStack bag) {
        owner = player.getUUID();
        BagInventory.initialize(bag);
        slot.setItem(0,bag);
    }
    private boolean attached(ServerPlayer p) {
        return !isRemoved() && level == p.level() && p.isAlive() && !p.isSpectator()
                && Objects.equals(owner,p.getUUID()) && unreadable == null && BackpackAccess.isBag(slot.getItem(0))
                && p.canInteractWithBlock(worldPosition,0) && level.hasChunkAt(worldPosition)
                && level.getBlockEntity(worldPosition) == this;
    }
    boolean allowed(ServerPlayer p, boolean removing) {
        if (!attached(p)) return false;
        String reason = removing ? Safety.buildReason(p,p.serverLevel(),worldPosition,getBlockState(),true)
                : Safety.locationReason(p,p.serverLevel(),worldPosition);
        return reason.isEmpty() && attached(p) && BackpackAccess.carriedCopies(p,slot.getItem(0),0);
    }
    static void hint(ServerPlayer p,String text) { p.displayClientMessage(Component.literal(text),true); }

    void interact(ServerPlayer p, InteractionHand hand) {
        if (p.containerMenu != p.inventoryMenu) return;
        if (hand==InteractionHand.MAIN_HAND && p.getMainHandItem().isEmpty() && !p.getOffhandItem().isEmpty())
            hand=InteractionHand.OFF_HAND;
        boolean pickup = p.isShiftKeyDown() && p.getItemInHand(hand).isEmpty();
        if (!allowed(p,pickup)) { hint(p,"You cannot access this placed backpack."); return; }
        if (!p.getItemInHand(hand).isEmpty() && apply(p,hand)) return;
        if (pickup) {
            var bag = slot.removeItemNoUpdate(0);
            if (!level.removeBlock(worldPosition,false) && level.getBlockEntity(worldPosition) == this) {
                slot.setItem(0,bag);
                return;
            }
            if (p.getItemInHand(hand).isEmpty()) p.setItemInHand(hand,bag);
            else p.getInventory().placeItemBackInInventory(bag);
            p.getInventory().setChanged();
        } else {
            var source = new BackpackAccess.Source(slot,0,BackpackAccess.GROUND_SLOT,() -> allowed(p,false));
            try { StoreMenu.bag(p,new BagInventory(p,source),false); }
            catch (IllegalArgumentException ex) { hint(p,"Cannot open this backpack. Pick it up to recover the original item."); }
        }
    }

    private boolean apply(ServerPlayer p, InteractionHand hand) {
        var input = p.getItemInHand(hand);
        var bag = slot.getItem(0);
        var result = BackpackAttachment.preview(p,bag,input);
        if (!result.recognized()) return false;
        if (result.output().isEmpty()) { hint(p,result.message()); return true; }
        // preview is pure. No callback or third-party permission hook between this check and the commit.
        if (!attached(p) || p.getItemInHand(hand)!=input || slot.getItem(0)!=bag || input.getCount()<result.cost()) return true;
        int returnSlot=-1;
        ItemStack refund=result.refund();
        if(!refund.isEmpty()){
            returnSlot=refundSlot(p,hand,input,result.cost(),refund);
            if(returnSlot<0){hint(p,"Make room for "+refund.getHoverName().getString()+" first.");return true;}
        }
        if (!p.getAbilities().instabuild) input.shrink(result.cost());
        if(returnSlot>=0){
            var destination=p.getInventory().getItem(returnSlot);
            if(destination.isEmpty())p.getInventory().setItem(returnSlot,refund);
            else destination.grow(1);
        }
        slot.setItem(0,result.output());
        if (!p.getAbilities().instabuild)
            for (var remainder : result.remainders()) p.getInventory().placeItemBackInInventory(remainder.copy());
        p.getInventory().setChanged();
        hint(p,result.message());
        return true;
    }

    private static int refundSlot(ServerPlayer p,InteractionHand hand,ItemStack input,int cost,ItemStack refund){
        var inventory=p.getInventory();
        int held=hand==InteractionHand.MAIN_HAND?inventory.selected:40;
        if(!p.getAbilities().instabuild && input.getCount()==cost)return held;
        for(int i=0;i<inventory.items.size();i++){
            var stack=inventory.getItem(i);
            if(stack.isEmpty() || (ItemStack.isSameItemSameComponents(stack,refund)
                    && stack.getCount()<inventory.getMaxStackSize(stack)))return i;
        }
        return -1;
    }

    boolean drop() {
        if (slot.isEmpty() || level == null || level.isClientSide || level.restoringBlockSnapshots) return false;
        ItemStack bag = slot.removeItemNoUpdate(0);
        var entity = new ItemEntity(level,worldPosition.getX()+.5,worldPosition.getY()+.25,worldPosition.getZ()+.5,bag);
        entity.setDefaultPickUpDelay();
        entity.setTarget(owner);
        if (level.addFreshEntity(entity)) return true;
        // An addon may deny item spawns. A normal break must leave the original bag available for pickup.
        slot.setItem(0,bag);
        return false;
    }

    public ItemStack displayStack() { return publicAppearance; }
    @Override public boolean onlyOpCanSetNbt() { return true; }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.saveAdditional(tag,lookup);
        if (owner!=null) tag.putUUID("Owner",owner);
        if (unreadable!=null) tag.put("Bag",unreadable.copy());
        else if (!slot.isEmpty()) tag.put("Bag",slot.getItem(0).save(lookup));
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.loadAdditional(tag,lookup);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        var saved = tag.get("Bag");
        ItemStack bag = saved == null ? ItemStack.EMPTY : ItemStack.parse(lookup,saved).orElse(ItemStack.EMPTY);
        unreadable = saved!=null && !BackpackAccess.isBag(bag) ? saved.copy() : null;
        slot.setItem(0,unreadable==null ? bag : ItemStack.EMPTY);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider lookup) {
        CompoundTag tag = new CompoundTag();
        if (!slot.isEmpty()) {
            var look=Looks.of(slot.getItem(0));
            tag.putString("Element",look.element()); tag.putString("Skin",look.skin()); tag.putString("LeafSpecies",look.leaves());
        }
        return tag;
    }
    @Override public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider lookup) {
        publicAppearance = ItemStack.EMPTY;
        if (!tag.contains("Element")) return;
        var publicLook = new CompoundTag();
        publicLook.putString("Element",Looks.known(Looks.ELEMENTS,tag.getString("Element"),"plain"));
        publicLook.putString("Skin",Looks.known(Looks.SKINS,tag.getString("Skin"),"elemental"));
        publicLook.putString("LeafSpecies",Looks.known(Looks.LEAVES,tag.getString("LeafSpecies"),"oak"));
        publicAppearance = new ItemStack(Content.BACKPACK.get());
        BagInventory.data(publicAppearance,publicLook);
    }
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket() { return ClientboundBlockEntityDataPacket.create(this); }
    @Override public void onDataPacket(Connection connection,ClientboundBlockEntityDataPacket packet,HolderLookup.Provider lookup) {
        handleUpdateTag(packet.getTag(),lookup);
    }
}
