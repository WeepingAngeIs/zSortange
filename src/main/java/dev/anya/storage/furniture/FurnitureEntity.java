package dev.anya.storage.furniture;

import dev.anya.storage.Content;
import dev.anya.storage.Transactions;
import net.minecraft.core.*;
import net.minecraft.nbt.*;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** No Container implementation, item handler, menu, or chunk data exposes the stores to neighbors. */
public final class FurnitureEntity extends BlockEntity {
    static final int PARCELS = 9;
    static final int TEXT_LIMIT = 80;
    static final int GUEST_LIMIT = 32;
    final SimpleContainer inventory;
    final String[] receipts;
    final Set<UUID> invited = new HashSet<>();
    UUID owner, pocket;
    private long revision;
    String caption = "";
    boolean visible;

    public FurnitureEntity(BlockPos pos, BlockState state) {
        super(Content.FURNITURE.get(), pos, state);
        inventory = new SimpleContainer(state.is(Content.SHELF.get()) ? 1 : PARCELS);
        inventory.addListener(ignored -> setChanged());
        receipts = new String[inventory.getContainerSize()];
        Arrays.fill(receipts, "");
    }
    public void initialize(UUID owner, UUID pocket) {
        if (this.owner != null) throw new IllegalStateException("Furniture already has an owner");
        this.owner = Objects.requireNonNull(owner);
        this.pocket = pocket;
        setChanged();
    }
    public boolean emptyForRemoval() { return inventory.isEmpty() && !Transactions.quarantined(inventory); }
    public boolean ownerMayRemove(ServerPlayer p) { return p.getUUID().equals(owner); }
    public boolean belongsTo(UUID owner, UUID pocket) {
        return owner != null && owner.equals(this.owner) && Objects.equals(pocket,this.pocket);
    }
    boolean shelf() { return getBlockState().is(Content.SHELF.get()); }
    boolean mayView(UUID actor) { return owner!=null && shelf() && (owner.equals(actor) || visible); }
    boolean maySend(UUID actor) { return owner!=null && (owner.equals(actor) || !shelf() && invited.contains(actor)); }
    long revision() { return revision; }
    @Override public void setChanged() { revision++;super.setChanged(); }
    @Override public boolean onlyOpCanSetNbt() { return true; }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider lookup) { return new CompoundTag(); }
    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.saveAdditional(tag,lookup);
        if (owner != null) tag.putUUID("Owner",owner);
        if (pocket != null) tag.putUUID("Pocket",pocket);
        tag.putBoolean("GuestsSeeShelf",visible);
        tag.putString("Caption",caption);
        NonNullList<ItemStack> slots = NonNullList.withSize(receipts.length,ItemStack.EMPTY);
        ListTag notes = new ListTag();
        for (int i=0; i<slots.size(); i++) {
            slots.set(i,inventory.getItem(i));
            notes.add(StringTag.valueOf(receipts[i]));
        }
        ContainerHelper.saveAllItems(tag,slots,lookup);
        tag.put("Receipts",notes);
        ListTag friends = new ListTag();
        invited.forEach(id -> friends.add(NbtUtils.createUUID(id)));
        tag.put("Invited",friends);
    }
    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider lookup) {
        super.loadAdditional(tag,lookup);
        owner = tag.hasUUID("Owner") ? tag.getUUID("Owner") : null;
        pocket = tag.hasUUID("Pocket") ? tag.getUUID("Pocket") : null;
        caption = savedText(tag.getString("Caption"),TEXT_LIMIT);
        visible = tag.getBoolean("GuestsSeeShelf");
        NonNullList<ItemStack> slots = NonNullList.withSize(receipts.length,ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag,slots,lookup);
        ListTag notes = tag.getList("Receipts",Tag.TAG_STRING);
        for (int i=0; i<slots.size(); i++) {
            inventory.setItem(i,slots.get(i));
            receipts[i] = i < notes.size() && !slots.get(i).isEmpty() ? savedText(notes.getString(i),TEXT_LIMIT+24) : "";
        }
        invited.clear();
        for (Tag friend : tag.getList("Invited",Tag.TAG_INT_ARRAY)) {
            if(invited.size()>=GUEST_LIMIT) break;
            if(friend instanceof IntArrayTag uuid && uuid.size()==4) invited.add(NbtUtils.loadUUID(uuid));
        }
    }
    private static String savedText(String raw,int limit) {
        StringBuilder clean=new StringBuilder();
        for(int i=0;i<raw.length() && clean.length()<limit;) {
            int cp=raw.codePointAt(i);
            i+=Character.charCount(cp);
            if(Character.isISOControl(cp) || Character.getType(cp)==Character.FORMAT || cp==0xa7) continue;
            if(clean.length()+Character.charCount(cp)>limit) break;
            clean.appendCodePoint(cp);
        }
        return clean.toString();
    }
}
