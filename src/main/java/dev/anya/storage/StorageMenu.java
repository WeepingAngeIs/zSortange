package dev.anya.storage;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemLore;
import java.util.*;
import java.util.function.BiConsumer;

/** Fake display stacks never enter a cursor, a hopper, or the player's inventory. */
public final class StorageMenu extends AbstractContainerMenu {
    public enum Mode { HOME,CONTENTS,PREFS,FAVORITES,WHY,UPGRADES,AUTO,FILTERS,PROFILE,DESTINATIONS,FEED,FUEL_REVIEW,RANGE_REVIEW,SORT_REVIEW,METRICS,ADMIN,NUMBERS,MATERIALS,TIERS,TIER,ORES,CATEGORIES,RECIPES,RECIPE,IMPACT }
    final ServerPlayer owner;
    public final UUID session;
    private final SimpleContainer display=new SimpleContainer(54);
    private final ContainerData ui=new SimpleContainerData(2);
    private final Map<Integer,BiConsumer<Integer,ClickType>> actions=new HashMap<>();
    private final Set<Integer> occupied=new HashSet<>();
    Mode mode=Mode.HOME;
    int page,bagSlot=-1,lastSequence=-1;
    long lastAction=-1,lastRefresh=-1,previewDeadline;
    UUID bagIdentity;
    StorageData.Bag bag;
    StorageBlockEntity block;
    Rules.Config draft;
    String input="",status="",chosenTier="starter",chosenCategory="food",chosenMaterial="";
    StorageRecipe.Definition recipe;
    List<String> conflicts=List.of();
    StorageOps.Plan preview;
    Fuel fuel;
    int reviewedRadius,reviewedCost;
    long reviewedRevision;
    record Fuel(int slot,ItemStack before,int count,int value,long revision){}

    public StorageMenu(int id,Inventory inventory,RegistryFriendlyByteBuf buf){this(id,inventory,null,buf.readUUID());}
    private StorageMenu(int id,Inventory inventory,ServerPlayer owner,UUID session){
        super(AnyaStorage.STORAGE_MENU.get(),id);this.owner=owner;this.session=session;
        for(int row=0;row<6;row++)for(int col=0;col<9;col++)addSlot(locked(display,row*9+col,18+col*18,34+row*18));
        for(int row=0;row<3;row++)for(int col=0;col<9;col++)addSlot(locked(inventory,9+row*9+col,18+col*18,156+row*18));
        for(int col=0;col<9;col++)addSlot(locked(inventory,col,18+col*18,214));
        addDataSlots(ui);
    }
    private static Slot locked(Container inv,int index,int x,int y){return new Slot(inv,index,x,y){@Override public boolean mayPickup(Player p){return false;}@Override public boolean mayPlace(ItemStack s){return false;}};}
    private static void open(ServerPlayer p,java.util.function.Consumer<StorageMenu> initialize){
        UUID nonce=UUID.randomUUID();p.openMenu(new SimpleMenuProvider((id,inventory,ignored)->{StorageMenu menu=new StorageMenu(id,inventory,p,nonce);initialize.accept(menu);menu.render();return menu;},Component.literal("Anya Storage")),buf->buf.writeUUID(nonce));
    }
    public static void openHome(ServerPlayer p){open(p,m->m.go(Mode.HOME));}
    public static void openBag(ServerPlayer p,int slot){
        open(p,m->{m.bagSlot=slot;ItemStack s=p.getInventory().getItem(slot);m.bag=StorageData.get(p.getServer()).bag(s);m.bagIdentity=StorageData.bagId(s);m.go(Mode.CONTENTS);});
    }
    public static void openBlock(ServerPlayer p,StorageBlockEntity block){
        open(p,m->{m.block=block;if(block.kind()==1)block.viewing(1);if(block.kind()==0)StorageOps.request(block);m.go(Mode.CONTENTS);});
    }
    public int epoch(){return ui.get(0);}
    public Mode viewMode(){return Mode.values()[Math.floorMod(ui.get(1),Mode.values().length)];}
    StorageData data(){return StorageData.get(owner.getServer());}
    StorageData.Prefs prefs(){return data().prefs(owner.getUUID());}
    Rules.Config rules(){return draft==null?Rules.current:draft;}
    boolean admin(){return owner!=null&&owner.hasPermissions(2);}
    boolean managing(){return block!=null&&block.canManage(owner);}
    void go(Mode next){mode=next;page=0;input="";status="";ui.set(0,(ui.get(0)+1)%32000);ui.set(1,next.ordinal());}
    void note(String text){status=text;AnyaStorage.tell(owner,text);}
    @Override public boolean stillValid(Player p){
        if(owner==null)return true;
        if(p!=owner||!owner.isAlive()||owner.isSpectator()||draft!=null&&!admin())return false;
        if(bag!=null){ItemStack item=owner.getInventory().getItem(bagSlot);if(!(item.getItem() instanceof BackpackItem)||!Objects.equals(StorageData.bagId(item),bagIdentity))return false;}
        if(block!=null){if(block.isRemoved()||block.getLevel()!=owner.serverLevel()||!owner.serverLevel().hasChunkAt(block.getBlockPos())||owner.serverLevel().getBlockEntity(block.getBlockPos())!=block||!block.allowed(owner)||!Claims.allowed(owner,block.getBlockPos()))return false;
            if(block.kind()==0){if(!block.inRange(owner.blockPosition()))return false;}else if(owner.distanceToSqr(block.getBlockPos().getX()+0.5,block.getBlockPos().getY()+0.5,block.getBlockPos().getZ()+0.5)>64)return false;
        }
        return true;
    }
    @Override public ItemStack quickMoveStack(Player p,int slot){return ItemStack.EMPTY;}
    @Override public boolean canDragTo(Slot s){return false;}
    @Override public boolean canTakeItemForPickAll(ItemStack s,Slot slot){return false;}
    @Override public void clicked(int index,int button,ClickType type,Player player){
        if(!stillValid(player)){if(owner!=null)owner.closeContainer();return;}
        if(owner==null)return;
        long now=owner.serverLevel().getGameTime();
        if((type==ClickType.PICKUP||type==ClickType.QUICK_MOVE)&&(button==0||button==1)&&now!=lastAction){
            lastAction=now;
            try {
                if(index>=0&&index<54){var action=actions.get(index);if(action!=null)action.accept(button,type);}
                else if(index>=54&&index<90)inventoryClick(slots.get(index).getContainerSlot(),button,type);
            }catch(IllegalArgumentException|IllegalStateException ex){note(ex.getMessage()==null?"Action rejected":ex.getMessage());}
        }
        if(owner.containerMenu==this){render();sendAllDataToRemote();}
    }
    public void input(int sequence,String text){
        if(owner==null||!stillValid(owner)||sequence<=lastSequence||text.length()>256)return;
        lastSequence=sequence;input=text.replaceAll("[\\p{Cntrl}]","");page=0;render();sendAllDataToRemote();
    }
    @Override public void broadcastChanges(){
        if(owner!=null&&owner.serverLevel().getGameTime()-lastRefresh>=20){lastRefresh=owner.serverLevel().getGameTime();render();}
        super.broadcastChanges();
    }
    @Override public void removed(Player p){if(owner!=null&&block!=null&&block.kind()==1)block.viewing(-1);super.removed(p);}
    void render(){
        if(owner==null)return;actions.clear();occupied.clear();for(int i=0;i<54;i++)display.setItem(i,ItemStack.EMPTY);
        if(switch(mode){case ADMIN,NUMBERS,MATERIALS,TIERS,TIER,ORES,CATEGORIES,RECIPES,RECIPE,IMPACT->true;default->false;})AdminViews.render(this);else PlayerViews.render(this);
        fallback(45,Items.COMPASS,"Overview",(b,t)->go(Mode.HOME),"Personal settings and operator controls");
        fallback(46,Items.ARROW,"Previous page",(b,t)->page=Math.max(0,page-1));
        fallback(47,Items.ARROW,"Next page",(b,t)->page++);
        fallback(49,Items.IRON_BARS,"Protected inventory slots",(b,t)->go(Mode.PREFS),"QuickStack and dump never remove protected slots");
        fallback(50,Items.CLOCK,"Performance / permissions",(b,t)->go(Mode.METRICS));
        fallback(51,Items.BOOK,"Search and input help",null,"Contents: name, item ID, @mod, #category or * favorites.","Editors: type a value/name, then click its field.","Click your inventory to choose a material.","Left/right arrows change pages. No display icons are obtainable.");
        fallback(52,Items.BARRIER,"Close / discard unsaved draft",(b,t)->owner.closeContainer());
        if(draft!=null&&mode!=Mode.IMPACT)fallback(53,Items.EMERALD,"Review configuration changes",(b,t)->go(Mode.IMPACT),"No changes are live until the review is applied");
        else fallback(53,Items.CHEST,"Return to open storage",bag!=null||block!=null?(b,t)->go(Mode.CONTENTS):null);
        if(!status.isEmpty()&&!occupied.contains(44))button(44,Items.PAPER,status,null);
    }
    void button(int slot,Item item,String name,BiConsumer<Integer,ClickType> action,String...lore){button(slot,new ItemStack(item),name,action,Arrays.asList(lore));}
    void button(int slot,ItemStack item,String name,BiConsumer<Integer,ClickType> action,List<String> lore){
        occupied.add(slot);ItemStack token=item.isEmpty()?new ItemStack(Items.BARRIER):item.copy();token.setCount(Math.max(1,Math.min(64,token.getCount())));
        token.set(DataComponents.CUSTOM_NAME,Component.literal(name.length()>160?name.substring(0,160):name).withStyle(ChatFormatting.AQUA));
        token.set(DataComponents.LORE,new ItemLore(lore.stream().limit(30).map(s->(Component)Component.literal(s).withStyle(ChatFormatting.GRAY)).toList()));
        display.setItem(slot,token);if(action!=null)actions.put(slot,action);
    }
    private void fallback(int slot,Item item,String name,BiConsumer<Integer,ClickType> action,String...lore){if(!occupied.contains(slot))button(slot,item,name,action,lore);}
    void toggle(int slot,String name,boolean on,Runnable action,String...lore){button(slot,on?Items.LIME_DYE:Items.GRAY_DYE,(on?"ON: ":"OFF: ")+name,(b,t)->action.run(),lore);}
    int offset(int count,int perPage){int max=Math.max(0,(count-1)/perPage);page=Math.max(0,Math.min(page,max));return page*perPage;}
    int number(int old,int step,int button){return input.isBlank()?old+(button==1?-step:step):Integer.parseInt(input.strip());}
    String selectedMaterial(){if(!chosenMaterial.isBlank())return chosenMaterial;if(Rules.validId(input)&&Rules.itemExists(input))return input;ItemStack s=owner.getMainHandItem();if(!s.isEmpty())return StorageOps.itemId(s);throw new IllegalArgumentException("Click a material in your inventory first");}
    void chooseRecipe(StorageRecipe.Definition def){recipe=def;conflicts=StorageRecipe.conflicts(owner,def);go(Mode.RECIPE);}
    List<StorageOps.Ref> refs(){
        if(bag!=null)return List.of(new StorageOps.Ref(BlockPos.ZERO,null,bag.inventory,()->stillValid(owner)));
        if(block==null)return List.of();
        if(block.kind()==0)return Rules.current.network?StorageOps.references(owner,block):List.of();
        return List.of(new StorageOps.Ref(block.getBlockPos(),block,block.inventory,()->stillValid(owner)));
    }
    void extract(ItemStack stack,int amount){int n=StorageOps.extract(owner,stack,refs(),amount);if(n==0)note("Nothing moved: inventory full, storage changed, or access expired");}
    void favorite(ItemStack stack){String id=StorageOps.itemId(stack);if(!prefs().favorites.remove(id)){if(prefs().favorites.size()>=512)throw new IllegalArgumentException("Favorite limit reached");prefs().favorites.add(id);}data().setDirty();}
    void previewSort(){preview=StorageOps.organize(owner,block!=null&&block.kind()==0?block:null,refs());previewDeadline=owner.serverLevel().getGameTime()+200;go(Mode.SORT_REVIEW);}
    private void inventoryClick(int slot,int button,ClickType type){
        if(slot<0||slot>=36)return;
        if(mode==Mode.PREFS){prefs().locked.flip(slot);data().setDirty();return;}
        if(mode==Mode.UPGRADES&&bag!=null){PlayerViews.install(this,slot);return;}
        if(mode==Mode.FILTERS&&bag!=null){ItemStack s=owner.getInventory().getItem(slot);if(!s.isEmpty()){String id=StorageOps.itemId(s);if(!bag.filters.remove(id)){if(bag.filters.size()>=128)throw new IllegalArgumentException("Filter limit reached");bag.filters.add(id);}data().setDirty();}return;}
        if(mode==Mode.FEED){PlayerViews.prepareFuel(this,slot,type==ClickType.QUICK_MOVE);return;}
        if(draft!=null&&(mode==Mode.MATERIALS||mode==Mode.TIERS||mode==Mode.TIER||mode==Mode.ORES||mode==Mode.CATEGORIES)){
            if(!admin())return;ItemStack s=owner.getInventory().getItem(slot);if(!s.isEmpty()){chosenMaterial=StorageOps.itemId(s);status="Selected: "+chosenMaterial;}return;
        }
        if(mode!=Mode.CONTENTS)return;
        ItemStack stack=owner.getInventory().getItem(slot);if(stack.isEmpty())return;
        if(bag!=null&&!Rules.current.backpacks)throw new IllegalArgumentException("Backpacks are disabled: recovery withdrawals only");
        if(block!=null&&block.kind()==0){if(!Rules.current.network)return;int moved=StorageOps.deposit(owner,slot,refs(),block,true,true);if(moved==0)note("No allowed destination with capacity");}
        else {
            List<StorageOps.Ref> refs=refs();if(refs.isEmpty())return;StorageOps.Ref ref=refs.getFirst();StorageOps.Plan plan=new StorageOps.Plan().guard(ref::valid);
            int n=plan.move(owner.getInventory(),slot,ref.inventory(),button==1?1:stack.getCount());if(n==0||!plan.commit())note("No room, forbidden nested storage, or stale inventory");
        }
    }
}
