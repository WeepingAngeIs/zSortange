package dev.anya.storage;

import net.minecraft.core.BlockPos;
import dev.anya.storage.ui.StorageGrid;
import dev.anya.storage.ui.ControlGlyph;
import dev.anya.storage.ui.ControlGlyph.State;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemLore;
import java.lang.reflect.Field;
import dev.anya.storage.appearance.*;
import dev.anya.storage.images.InteriorImages;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Display-only slots. The only accepted input is a session/revision-checked intent packet. */
public final class StoreMenu extends AbstractContainerMenu {
    private enum Page { NETWORK, BAG, CHEST, ADMIN, LIMITS, TIERS, TIER, ORES, RECIPES, UPGRADE_GATES, PREFS, BASE, ROUTE, UPGRADES, DIAGNOSTICS, PRESETS, CONFIG_CONFIRM, SORT_CONFIRM, FEED_CONFIRM, CATEGORIES, WARDROBE, EXPANSION, IMAGES, ACCESS, ACTIVITY }
    private record Click(int button,boolean shift) {}
    private record Entry(Container inventory,int slot,ItemStack snapshot,BlockPos position) {}
    private final SimpleContainer display=new SimpleContainer(54);
    private final ContainerData sync=new SimpleContainerData(18);
    private final Map<Integer,Consumer<Click>> buttons=new HashMap<>();
    private final Map<Integer,Entry> entries=new HashMap<>();
    private final ItemStack[] playerSeen=new ItemStack[36];
    private ServerPlayer owner;
    private Page page=Page.PREFS,returnPage=Page.PREFS;
    private BagInventory bag;
    private dev.anya.storage.pocket.PocketView pocket;
    private StorageEntity hub,chest;
    private BlockPos routePos;
    private Config.Data draft;
    private String input="",selectedTier="netherite";
    private int offset,viewportRows=5;
    private UUID selectedImage,deleteImage,selectedMember;
    private boolean clearLogConfirm;
    private String lastAppearance="";
    private boolean favoritesOnly;
    private int feedSlot,feedCount,feedPoints,feedBefore;
    private long feedRevision;
    private ItemStack feedSnapshot=ItemStack.EMPTY;
    private long lastAction=-1,lastRefresh=-1;
    private Transactions.Plan pending;
    private List<Container> pendingContainers=List.of();

    public StoreMenu(int id,Inventory inventory){
        super(Content.MENU.get(),id);
        for(int row=0;row<6;row++)for(int col=0;col<9;col++)addSlot(locked(display,row*9+col,125+col*18,34+row*18));
        for(int row=0;row<3;row++)for(int col=0;col<9;col++)addSlot(locked(inventory,9+row*9+col,125+col*18,155+row*18));
        for(int col=0;col<9;col++)addSlot(locked(inventory,col,125+col*18,213));
        addDataSlots(sync);
    }
    private StoreMenu(int id,Inventory inventory,ServerPlayer owner,Page page,BagInventory bag,StorageEntity hub,StorageEntity chest,BlockPos routePos){
        this(id,inventory);this.owner=owner;this.page=page;this.bag=bag;this.hub=hub;this.chest=chest;this.routePos=routePos;
        if(owner.hasPermissions(2))draft=Config.copy(Config.get());
        int session=ThreadLocalRandom.current().nextInt();sync.set(0,session&65535);sync.set(1,session>>>16);bump();
        if(chest!=null)chest.openViewer();render();
    }
    private static Slot locked(Container container,int index,int x,int y){return new Slot(container,index,x,y){public boolean mayPlace(ItemStack s){return false;}public boolean mayPickup(Player p){return false;}};}
    private static void open(ServerPlayer p,Page page,BagInventory bag,StorageEntity hub,StorageEntity chest,BlockPos route){
        p.openMenu(new SimpleMenuProvider((id,inv,ignored)->new StoreMenu(id,inv,p,page,bag,hub,chest,route),bag==null?Component.literal("Anya Storage"):bag.stack.getHoverName()));
    }
    static StoreMenu bagMenu(int id,ServerPlayer p,BagInventory bag){return new StoreMenu(id,p.getInventory(),p,Page.BAG,bag,null,null,null);}
    public static void settings(ServerPlayer p){open(p,p.hasPermissions(2)?Page.ADMIN:Page.PREFS,null,null,null,null);}
    public static void preferences(ServerPlayer p){open(p,Page.PREFS,null,null,null,null);}
    public static void bag(ServerPlayer p,BagInventory bag,boolean upgrades){open(p,upgrades?Page.UPGRADES:Page.BAG,bag,null,null,null);}
    public static void network(ServerPlayer p,StorageEntity hub){String reason=Network.hubReason(p,hub);if(reason.isEmpty())open(p,Page.NETWORK,null,hub,null,null);else AnyaStorage.tell(p,reason);}
    public static void container(ServerPlayer p,StorageEntity chest){if(chest.pocketId!=null){dev.anya.storage.pocket.PocketService.openChest(p,chest);return;}if(Safety.reason(p,chest,null).isEmpty())open(p,Page.CHEST,null,null,chest,null);}
    public static void pocket(ServerPlayer p,dev.anya.storage.pocket.PocketView view){
        if(!view.valid())return;
        p.openMenu(new SimpleMenuProvider((id,inv,ignored)->{
            StoreMenu menu=new StoreMenu(id,inv,p,Page.CHEST,null,null,null,null);menu.pocket=view;menu.render();menu.bump();return menu;
        },Component.literal("Experimental pocket storage")));
    }
    public boolean hasPocket(UUID id){return pocket!=null&&pocket.id().equals(id);}
    public static void route(ServerPlayer p,StorageEntity hub,BlockPos pos){if(hub.ownedBy(p))open(p,Page.ROUTE,null,hub,null,pos.immutable());}
    public int session(){return (sync.get(0)&65535)|((sync.get(1)&65535)<<16);}
    public int revision(){return (sync.get(2)&65535)|((sync.get(3)&65535)<<16);}
    private int synchronizedInt(int index){return (sync.get(index)&65535)|((sync.get(index+1)&65535)<<16);}
    private void synchronizedInt(int index,int value){sync.set(index,value&65535);sync.set(index+1,value>>>16);}
    /** Display slot classification is server-authored, independent of any item's own model data. */
    public boolean controlSlot(int slot){
        return slot>=0&&slot<54&&(sync.get(14+slot/16)&(1<<(slot%16)))!=0;
    }
    private void resetControlSlots(){for(int i=14;i<18;i++)sync.set(i,0);}
    private void setControlSlot(int slot){
        int index=14+slot/16;
        sync.set(index,sync.get(index)|(1<<(slot%16)));
    }
    public boolean storageView(){return (sync.get(4)&1)!=0;}
    public boolean backpackView(){return (sync.get(4)&2)!=0;}
    public boolean accessView(){return (sync.get(4)&16)!=0;}
    public boolean privateView(){return (sync.get(4)&4)!=0;}
    public boolean filteredView(){return (sync.get(4)&8)!=0;}
    public int visibleRows(){return StorageGrid.clampRows(sync.get(5));}
    public int capacity(){return Math.max(0,synchronizedInt(6));}
    public int resultCount(){return Math.max(0,synchronizedInt(8));}
    public int firstSlot(){return Math.max(0,synchronizedInt(10));}
    public int occupiedSlots(){return Math.max(0,synchronizedInt(12));}
    public int shownSlots(){return storageView()?StorageGrid.visible(resultCount(),firstSlot(),visibleRows()):45;}
    /** Explicit renderer fixture only; cannot be invoked by packets or on a server-owned menu. */
    public void renderFixture(int capacity,int row,int rows){
        if(owner!=null||!Boolean.getBoolean("anyastorage.clientSmoke"))throw new IllegalStateException("Not a development renderer fixture");
        resetControlSlots();sync.set(4,3);sync.set(5,StorageGrid.clampRows(rows));synchronizedInt(6,capacity);synchronizedInt(8,capacity);synchronizedInt(10,StorageGrid.start(capacity,row,rows));synchronizedInt(12,0);
        for(int i=0;i<54;i++)display.setItem(i,ItemStack.EMPTY);
        button(45,ControlGlyph.SCROLL_UP,"Scroll up",null);button(46,ControlGlyph.SCROLL_DOWN,"Scroll down",null);button(47,ControlGlyph.ORGANIZE,"Organize preview",null);button(48,ControlGlyph.QUICKSTACK,"QuickStack",null);button(49,ControlGlyph.DUMP,"Dump matches",null);button(50,ControlGlyph.PROTECT,"Protected slots",null);button(51,ControlGlyph.UPGRADES,"Upgrades",null);button(52,ControlGlyph.FAVORITES,"Favorites",null);button(53,ControlGlyph.INFO,"Information",null);
    }
    private void bump(){int value=revision()+1;sync.set(2,value&65535);sync.set(3,value>>>16);}
    private boolean adminPage(){return switch(page){case ADMIN,LIMITS,TIERS,TIER,ORES,RECIPES,UPGRADE_GATES,PRESETS,CONFIG_CONFIRM,CATEGORIES,EXPANSION->true;default->false;};}
    @Override public boolean stillValid(Player player){
        if(owner==null)return true;if(player!=owner||!owner.isAlive()||owner.isSpectator())return false;
        if(adminPage()&&!owner.hasPermissions(2))return false;
        if(bag!=null&&!bag.valid())return false;
        if(pocket!=null&&!pocket.valid())return false;
        if(hub!=null&&!Network.hubReason(owner,hub).isEmpty())return false;
        if(chest!=null&&(!chest.stillValid(owner)||!Safety.reason(owner,chest,null).isEmpty()))return false;
        if((page==Page.BASE||page==Page.ROUTE||page==Page.FEED_CONFIRM||page==Page.ACCESS)&&hub!=null&&!hub.ownedBy(owner))return false;
        if(page==Page.ACTIVITY&&(hub==null||(!hub.canReadActivity(owner)&&!hub.ownedBy(owner))))return false;
        return true;
    }
    @Override public ItemStack quickMoveStack(Player p,int slot){return ItemStack.EMPTY;}
    @Override public boolean canDragTo(Slot slot){return false;}
    @Override public boolean canTakeItemForPickAll(ItemStack s,Slot slot){return false;}
    @Override public void clicked(int slot,int button,ClickType type,Player p){if(owner!=null)sendAllDataToRemote();}
    @Override public void removed(Player p){if(chest!=null)chest.closeViewer();if(pocket!=null)pocket.close();pending=null;super.removed(p);}
    @Override public void broadcastChanges(){
        if(owner!=null&&owner.containerMenu==this&&stillValid(owner)&&isStoragePage()&&owner.serverLevel().getGameTime()-lastRefresh>=10){render();bump();lastRefresh=owner.serverLevel().getGameTime();}
        super.broadcastChanges();
        if(owner!=null&&owner.containerMenu==this&&stillValid(owner))syncAppearance();
    }
    public void action(Packets.Action packet){
        if(owner==null||packet.session()!=session()||packet.revision()!=revision()){sendAllDataToRemote();return;}
        if(!stillValid(owner)){owner.closeContainer();return;}
        long now=owner.serverLevel().getGameTime();if(lastAction==now)return;lastAction=now;
        try {
            if(packet.verb().equals("text")){input=packet.text().strip();if(input.length()>128)input=input.substring(0,128);offset=0;}
            else if(packet.verb().equals("viewport")&&isStoragePage()){
                int value=packet.value();if(value<0||value>16_000_000)return;
                viewportRows=StorageGrid.clampRows(value&7);offset=StorageGrid.start(resultCount(),value>>>3,viewportRows);
            }
            else if(packet.verb().equals("appearance")&&bag!=null){navigate(Page.WARDROBE);}
            else if(packet.verb().equals("slot")){
                int index=packet.value()&255;Click click=new Click((packet.value()>>8)&3,(packet.value()&65536)!=0);
                if(index>=0&&index<54){Consumer<Click> action=buttons.get(index);if(action!=null)action.accept(click);else if(entries.containsKey(index))take(entries.get(index),click);}
                else if(index>=54&&index<90)inventoryClick(slots.get(index).getContainerSlot(),click);
            }
        } catch(IllegalArgumentException e){AnyaStorage.tell(owner,e.getMessage());}
        if(owner.containerMenu==this){if(!stillValid(owner)){owner.closeContainer();return;}render();bump();sendAllDataToRemote();Wearables.refresh(owner);}
    }
    private boolean isStoragePage(){return page==Page.NETWORK||page==Page.BAG||page==Page.CHEST;}
    private void render(){
        resetControlSlots();buttons.clear();entries.clear();for(int i=0;i<54;i++)display.setItem(i,ItemStack.EMPTY);
        sync.set(4,bag!=null?2:0);sync.set(5,viewportRows);synchronizedInt(6,0);synchronizedInt(8,0);synchronizedInt(10,0);synchronizedInt(12,0);
        for(int i=0;i<36;i++)playerSeen[i]=owner.getInventory().getItem(i).copy();
        switch(page){
            case NETWORK,BAG,CHEST->storage();case ADMIN->admin();case LIMITS->limits();case TIERS->tiers();case TIER->tier();case ORES->ores();case RECIPES->recipes();case UPGRADE_GATES->upgradeGates();case PREFS->prefs();case BASE->base();case ROUTE->routing();case UPGRADES->upgrades();case DIAGNOSTICS->diagnostics();case PRESETS->presets();case CONFIG_CONFIRM->configConfirm();case SORT_CONFIRM->sortConfirm();case FEED_CONFIRM->feedConfirm();case CATEGORIES->categories();case WARDROBE->wardrobe();case EXPANSION->expansion();case IMAGES->images();case ACCESS->access();case ACTIVITY->activity();
        }
    }
    private void navigate(Page next){page=next;offset=0;pending=null;clearLogConfirm=false;}
    private void button(int slot,Item item,String name,Consumer<Click> action,String... lore){display.setItem(slot,icon(new ItemStack(item),name,List.of(lore)));if(action!=null)buttons.put(slot,action);}
    private void button(int slot,ControlGlyph glyph,String name,Consumer<Click> action,String... lore){
        button(slot,glyph,action==null?State.READ_ONLY:State.NORMAL,name,action,lore);
    }
    private void button(int slot,ControlGlyph glyph,State state,String name,Consumer<Click> action,String... lore){
        ItemStack displayItem=icon(new ItemStack(Content.REMOTE.get()),name,List.of(lore));
        glyph.mark(displayItem,state);
        setControlSlot(slot);
        display.setItem(slot,displayItem);
        if(action!=null)buttons.put(slot,action);
    }
    private static ItemStack icon(ItemStack stack,String name,List<String> lore){
        ItemStack copy=stack.copy();if(copy.isEmpty())copy=new ItemStack(Content.REMOTE.get());
        Component originalName=copy.getHoverName().copy();copy.remove(DataComponents.CONTAINER);copy.remove(DataComponents.BUNDLE_CONTENTS);if(copy.is(Content.BACKPACK.get())){copy.remove(DataComponents.CUSTOM_DATA);copy.set(DataComponents.CUSTOM_NAME,originalName);}
        if(name!=null)copy.set(DataComponents.CUSTOM_NAME,Component.literal(name).withStyle(s->s.withItalic(false)));
        List<Component> lines=new ArrayList<>();for(String text:lore)for(int i=0;i<text.length();i+=55)lines.add(Component.literal(text.substring(i,Math.min(i+55,text.length()))).withStyle(s->s.withItalic(false).withColor(0xBFC9DE)));
        copy.set(DataComponents.LORE,new ItemLore(lines));return copy;
    }
    private void footer(Page back){button(45,ControlGlyph.BACK,"Back",c->navigate(back));button(46,ControlGlyph.PREVIOUS,"Previous page",c->offset=Math.max(0,offset-45));button(47,ControlGlyph.NEXT,"Next page",c->offset+=45);button(53,ControlGlyph.CLOSE,"Close",c->owner.closeContainer());}
    private void toggle(int slot,ControlGlyph glyph,String title,boolean enabled,Runnable change,String... lore){button(slot,glyph,enabled?State.ON:State.OFF,title+(enabled?" [ON]":" [OFF]"),c->change.run(),lore);}
    private List<Network.Target> targets(){return hub==null?List.of():Network.targets(owner,hub);}
    private List<Container> storageContainers(){if(pocket!=null)return pocket.containers();if(bag!=null)return List.of(bag);if(chest!=null)return List.of(chest);return targets().stream().map(Network.Target::inventory).toList();}
    private MainInventory main(){return new MainInventory(owner,bag==null?-1:bag.playerSlot);}
    private void storage(){
        boolean mayView=pocket!=null?pocket.permits(BasePermissions.VIEW):hub==null||hub.permits(owner,BasePermissions.VIEW);
        List<Entry> values=new ArrayList<>();List<Network.Target> targets=mayView?targets():List.of();
        List<Container> containers=!mayView?List.of():pocket!=null?pocket.containers():bag!=null?List.of(bag):chest!=null?List.of(chest):targets.stream().map(Network.Target::inventory).toList();
        int capacity=0,occupied=0;boolean filtered=!input.isBlank()||favoritesOnly;
        for(Container container:containers)for(int i=0;i<container.getContainerSize();i++){
            capacity++;ItemStack item=container.getItem(i);if(!item.isEmpty())occupied++;
            if(filtered&&(item.isEmpty()||!Categories.matches(item,input)||favoritesOnly&&!Profiles.of(owner).favorites.contains(Categories.id(item))))continue;
            BlockPos pos=container instanceof StorageEntity be?be.getBlockPos():null;
            if(pos==null&&bag==null)for(Network.Target target:targets)if(target.inventory()==container){pos=target.pos();break;}
            values.add(new Entry(container,i,item.copy(),pos));
        }
        // Unfiltered views retain physical slot order, including every real empty slot.
        // Search narrows the view without inventing slots or changing the source-slot identity.
        offset=StorageGrid.start(values.size(),offset/9,viewportRows);
        sync.set(4,1|(bag!=null?2:0)|(!mayView?4:0)|(filtered?8:0));sync.set(5,viewportRows);
        synchronizedInt(6,capacity);synchronizedInt(8,values.size());synchronizedInt(10,offset);synchronizedInt(12,occupied);
        int shown=StorageGrid.visible(values.size(),offset,viewportRows);
        for(int i=0;i<shown;i++){
            Entry entry=values.get(offset+i);entries.put(i,entry);
            if(entry.snapshot.isEmpty())continue;
            String source=entry.position==null?"Backpack":entry.position.toShortString();
            display.setItem(i,icon(entry.snapshot,null,List.of("Source: "+source+" / real slot "+(entry.slot+1),"Category: "+Categories.of(entry.snapshot),"Left: take stack | Right: take one","Middle: favorite this item type",Profiles.of(owner).favorites.contains(Categories.id(entry.snapshot))?"Favorite":"")));
        }
        button(45,ControlGlyph.SCROLL_UP,"Scroll up",c->offset=Math.max(0,offset-9));button(46,ControlGlyph.SCROLL_DOWN,"Scroll down",c->offset+=9);
        button(47,ControlGlyph.ORGANIZE,"Preview organize",c->previewSort(),"Groups by category and exact components.","Nothing moves until you confirm the preview.");
        button(48,ControlGlyph.QUICKSTACK,"QuickStack matching",c->depositAll(false),"Only existing identical item/components.","Protected slots are never swept.");
        button(49,ControlGlyph.DUMP,"Dump broader matches",c->depositAll(true),"Same item type or designated category.","Does not empty unrelated items into random chests.");
        button(50,ControlGlyph.PROTECT,"Protected slots",c->navigate(Page.PREFS));
        if(bag!=null)button(51,ControlGlyph.UPGRADES,"Backpack upgrades",c->navigate(Page.UPGRADES),bag.name(),"Saved capacity: "+bag.getContainerSize());
        else if(hub!=null)button(51,ControlGlyph.BASE,hub.ownedBy(owner)?"Base profile / ore feeder":"Base information",c->navigate(hub.ownedBy(owner)?Page.BASE:hub.canReadActivity(owner)?Page.ACTIVITY:Page.DIAGNOSTICS),hub.baseName,"Radius: "+hub.radius());
        else if(chest!=null)button(51,ControlGlyph.SYMBOL,chest.ownedBy(owner)?State.NORMAL:State.DISABLED,"Cycle front symbol",c->{if(chest.ownedBy(owner)){chest.symbol=Config.SYMBOLS.get((Config.SYMBOLS.indexOf(chest.symbol)+1)%Config.SYMBOLS.size());chest.profileChanged();}},"Owner/operator only",chest.symbol);
        if(pocket!=null)button(51,ControlGlyph.EXIT,"Pocket exit / information",c->{if(dev.anya.storage.pocket.PocketService.inPocket(owner.level()))dev.anya.storage.pocket.PocketService.recover(owner);else AnyaStorage.tell(owner,"Home-radius storage only. Visit is a separate permission. Physical pocket: "+pocket.id());});
        toggle(52,ControlGlyph.FAVORITES,"Favorites only",favoritesOnly,()->favoritesOnly=!favoritesOnly);
        button(53,ControlGlyph.INFO,"Storage information",c->navigate(Page.DIAGNOSTICS),"Real slots: "+capacity+" | Occupied: "+occupied,"Visible results: "+values.size(),"Click items in your inventory below to deposit.","Type name, @mod or #tag in the search bar.");
    }
    private boolean permission(int required){if(pocket!=null){boolean ok=pocket.permits(required);if(!ok)AnyaStorage.tell(owner,"Pocket permission or policy changed; action denied.");return ok;}String denied=Network.permissionReason(owner,hub,required);if(denied.isEmpty())return true;AnyaStorage.tell(owner,denied);return false;}
    private void take(Entry entry,Click click){
        if(!isStoragePage()||entry.snapshot.isEmpty()||!permission(BasePermissions.VIEW))return;
        if(click.button!=2&&!permission(BasePermissions.WITHDRAW))return;
        if(click.button==2){Set<String> favorites=Profiles.of(owner).favorites;String id=Categories.id(entry.snapshot);if(!favorites.remove(id)&&favorites.size()<256)favorites.add(id);Profiles.changed(owner);return;}
        if(!Transactions.same(entry.inventory.getItem(entry.slot),entry.snapshot)){AnyaStorage.tell(owner,"That stack changed. The view has been refreshed.");return;}
        MainInventory main=main();List<Network.Target> targets=targets();
        if(hub!=null&&targets.stream().noneMatch(t->t.inventory()==entry.inventory)){AnyaStorage.tell(owner,"That source is no longer accessible. Permissions and range have been rechecked.");return;}
        List<Container> all=new ArrayList<>();all.add(main);all.add(entry.inventory);
        var guard=pocket!=null?pocket.guard(BasePermissions.VIEW|BasePermissions.WITHDRAW):hub==null?(java.util.function.BooleanSupplier)()->stillValid(owner):Network.guard(owner,hub,targets,BasePermissions.VIEW|BasePermissions.WITHDRAW);
        Transactions.Plan plan=new Transactions.Plan(all,guard);int moved=plan.move(entry.inventory,entry.slot,List.of(main),click.button==1?1:entry.snapshot.getCount());
        String error=moved==0?"Your main inventory is full":plan.commit(owner.getServer());if(!error.isEmpty())AnyaStorage.tell(owner,error);
        else if(hub!=null)hub.logTransfer(owner,BaseActivity.Action.WITHDRAW,moved);else if(pocket!=null)pocket.log(BaseActivity.Action.WITHDRAW,moved);
    }
    private void inventoryClick(int slot,Click click){
        if(slot<0||slot>=36)return;
        if(page==Page.BASE){selectOre(slot,click);return;}
        if(page==Page.DIAGNOSTICS||(page==Page.PREFS&&click.shift)){explainSlot(slot,click.button==1);return;}
        if(page==Page.PREFS){Profiles.of(owner).protectedSlots^=1L<<slot;Profiles.changed(owner);return;}
        if(page==Page.UPGRADES){ItemStack s=owner.getInventory().getItem(slot);if(!s.isEmpty()){input=Categories.id(s);AnyaStorage.tell(owner,"Selected filter item: "+input);}return;}
        if(!isStoragePage()||!permission(BasePermissions.DEPOSIT))return;
        if(!Transactions.same(owner.getInventory().getItem(slot),playerSeen[slot])){AnyaStorage.tell(owner,"Inventory changed; try the refreshed slot.");return;}
        if(bag!=null&&!Config.get().backpacks){AnyaStorage.tell(owner,"Backpacks are disabled. Existing contents can still be withdrawn.");return;}
        MainInventory main=main();if(main.getItem(slot).isEmpty())return;
        List<Network.Target> targets=targets();List<Container> destinations=pocket!=null?pocket.containers():bag!=null?List.of(bag):chest!=null?List.of(chest):targets.stream().map(Network.Target::inventory).toList();
        List<Container> all=new ArrayList<>(destinations);all.add(main);
        var guard=pocket!=null?pocket.guard(BasePermissions.DEPOSIT):hub==null?(java.util.function.BooleanSupplier)()->stillValid(owner):Network.guard(owner,hub,targets,BasePermissions.DEPOSIT);
        Transactions.Plan plan=new Transactions.Plan(all,guard);ItemStack item=plan.work(main)[slot];
        if(hub!=null)destinations=Network.destinations(plan,targets,hub,item,Network.Mode.MANUAL);
        int moved=plan.move(main,slot,destinations,click.button==1?1:item.getCount());String error=moved==0?"No capacity or the destination filter rejects this item":plan.commit(owner.getServer());if(!error.isEmpty())AnyaStorage.tell(owner,error);
        else if(hub!=null)hub.logTransfer(owner,BaseActivity.Action.DEPOSIT,moved);else if(pocket!=null)pocket.log(BaseActivity.Action.DEPOSIT,moved);
    }
    private void depositAll(boolean dump){
        if(!permission(BasePermissions.VIEW|BasePermissions.DEPOSIT))return;
        if(!Config.get().quickStack){AnyaStorage.tell(owner,"QuickStack is disabled.");return;}
        if(bag!=null&&!Config.get().backpacks)return;
        MainInventory main=main();List<Network.Target> targets=targets();List<Container> storage=storageContainers(),all=new ArrayList<>(storage);all.add(main);
        var guard=pocket!=null?pocket.guard(BasePermissions.VIEW|BasePermissions.DEPOSIT):hub==null?(java.util.function.BooleanSupplier)()->stillValid(owner):Network.guard(owner,hub,targets,BasePermissions.VIEW|BasePermissions.DEPOSIT);
        Transactions.Plan plan=new Transactions.Plan(all,guard);int moved=0,locked=0,unmatched=0,full=0;
        for(int i=0;i<36;i++){
            ItemStack stack=plan.work(main)[i];if(stack.isEmpty())continue;if(Profiles.of(owner).protectedSlot(i)){locked+=stack.getCount();continue;}
            List<Container> destinations;
            if(hub!=null)destinations=Network.destinations(plan,targets,hub,stack,dump?Network.Mode.DUMP:Network.Mode.QUICK);
            else {destinations=new ArrayList<>();for(Container c:storage){boolean matching=plan.contains(c,stack);if(dump)for(ItemStack old:plan.work(c))if(!old.isEmpty()&&old.is(stack.getItem()))matching=true;if(matching)destinations.add(c);}}
            if(destinations.isEmpty()){unmatched+=stack.getCount();continue;}moved+=plan.move(main,i,destinations,stack.getCount());full+=plan.work(main)[i].getCount();
        }
        String error=moved>0?plan.commit(owner.getServer()):"";String report=error.isEmpty()?"Moved "+moved+"; protected "+locked+"; no matching destination "+unmatched+"; full/filtered "+full:error;
        if(error.isEmpty()&&moved>0&&hub!=null)hub.logTransfer(owner,dump?BaseActivity.Action.DUMP:BaseActivity.Action.QUICKSTACK,moved);
        if(error.isEmpty()&&moved>0&&pocket!=null)pocket.log(dump?BaseActivity.Action.DUMP:BaseActivity.Action.QUICKSTACK,moved);
        Profiles.of(owner).lastReport=report;AnyaStorage.tell(owner,report);
    }
    private void previewSort(){
        if(!permission(BasePermissions.LEGACY))return;
        if(!Config.get().organize){AnyaStorage.tell(owner,"Organization is disabled.");return;}
        pendingContainers=storageContainers();if(hub!=null)pending=Network.organize(owner,hub,targets());
        else {
            pending=new Transactions.Plan(pendingContainers,pocket!=null?pocket.guard(BasePermissions.LEGACY):()->stillValid(owner));List<ItemStack> stacks=new ArrayList<>();for(Container c:pendingContainers){for(ItemStack s:pending.work(c))if(!s.isEmpty())stacks.add(s.copy());Arrays.fill(pending.work(c),ItemStack.EMPTY);}
            stacks.sort(Categories.order());for(ItemStack stack:stacks){for(Container c:pendingContainers)pending.insert(c,stack);if(!stack.isEmpty())throw new IllegalArgumentException("Items do not fit the proposed sorted layout");}
        }
        returnPage=page;page=Page.SORT_CONFIRM;offset=0;
    }
    private void sortConfirm(){
        if(pending==null){navigate(returnPage);return;}
        List<String> descriptions=new ArrayList<>();List<ItemStack> icons=new ArrayList<>();
        for(Container c:pendingContainers)for(int i=0;i<c.getContainerSize();i++)if(!Transactions.same(pending.original(c)[i],pending.work(c)[i])){
            ItemStack before=pending.original(c)[i],after=pending.work(c)[i];icons.add(after.isEmpty()?before:after);
            String source=c instanceof net.minecraft.world.level.block.entity.BlockEntity be?be.getBlockPos().toShortString():"Backpack";
            descriptions.add(source+" slot "+(i+1)+": "+before.getCount()+" "+before.getHoverName().getString()+" -> "+after.getCount()+" "+after.getHoverName().getString());
        }
        offset=Math.min(offset,Math.max(0,((descriptions.size()-1)/45)*45));for(int i=0;i<45&&offset+i<icons.size();i++)display.setItem(i,icon(icons.get(offset+i),"Proposed slot change",List.of(descriptions.get(offset+i),"Category: "+Categories.of(icons.get(offset+i)))));
        button(45,ControlGlyph.CANCEL,"Cancel preview",c->{page=returnPage;pending=null;offset=0;});button(46,ControlGlyph.PREVIOUS,"Previous",c->offset=Math.max(0,offset-45));button(47,ControlGlyph.NEXT,"Next",c->offset+=45);
        button(53,ControlGlyph.CONFIRM,"Confirm "+pending.changedSlots()+" slot changes",c->{if(!Config.get().organize)return;String error=pending.commit(owner.getServer());AnyaStorage.tell(owner,error.isEmpty()?"Organization applied.":error);pending=null;page=returnPage;offset=0;},"Every source and destination is rechecked.","Stale snapshots or permissions cancel the operation.");
    }
    private void admin(){
        String[] flags={"compatibility","fallbackMissingTiers","backpacks","network","quickStack","organize","labels","audit","vanillaChests","vanillaBarrels","vanillaShulkers"};
        for(int i=0;i<flags.length;i++){String name=flags[i];try{Field f=Config.Data.class.getField(name);toggle(i,ControlGlyph.setting(name),name,f.getBoolean(draft),()->{try{f.setBoolean(draft,!f.getBoolean(draft));}catch(IllegalAccessException e){throw new IllegalStateException(e);}},"Private draft. Review and apply to save.");}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
        button(19,ControlGlyph.TIERS,"Backpack tiers / compatibility doctor",c->navigate(Page.TIERS));
        button(21,ControlGlyph.LIMITS,"Ranges and performance budgets",c->navigate(Page.LIMITS));
        button(23,ControlGlyph.ORE_VALUES,"Ore feeder values",c->navigate(Page.ORES));
        button(25,ControlGlyph.RECIPES,"Recipe gates / endgame choice",c->navigate(Page.RECIPES));
        button(28,ControlGlyph.UPGRADE_SWITCH,"Individual upgrade switches",c->navigate(Page.UPGRADE_GATES));
        button(30,ControlGlyph.PRESETS,"Operator presets",c->navigate(Page.PRESETS));
        button(32,ControlGlyph.DIAGNOSTICS,"Diagnostics",c->navigate(Page.DIAGNOSTICS));
        button(34,ControlGlyph.PROTECT,"My protected slots",c->navigate(Page.PREFS));
        button(37,ControlGlyph.CATEGORY_RULES,"Category rules: items and tags",c->navigate(Page.CATEGORIES));
        button(38,ControlGlyph.POLICY,"Elemental, cosmetics, image and pocket policy",c->navigate(Page.EXPANSION));
        button(39,ControlGlyph.MODERATION,"Interior moderation library",c->navigate(Page.IMAGES));
        button(45,ControlGlyph.DISCARD,"Discard and close",c->owner.closeContainer());
        button(53,ControlGlyph.REVIEW,"Review changes",c->{Config.validate(draft);navigate(Page.CONFIG_CONFIRM);},"Draft revision: "+draft.revision,"Saved revision: "+Config.get().revision,"Saving reloads recipes. Existing items stay registered.");
    }
    private void limits(){
        String[] names={"initialRadius","maxRadius","radiusStep","quickRadius","scanChunksPerTick","maxContainers","maxScanEntries","maxOperationSlots","upgradeBaseCost"};
        int[] steps={1,8,1,1,1,16,32,512,16};
        for(int i=0;i<names.length;i++){String name=names[i];int step=steps[i];try{Field f=Config.Data.class.getField(name);int value=f.getInt(draft);button(i,ControlGlyph.setting(name),name+": "+value,c->{try{int next=c.shift?Integer.parseInt(input):f.getInt(draft)+(c.button==1?-step:step);f.setInt(draft,next);}catch(ReflectiveOperationException|NumberFormatException e){throw new IllegalArgumentException("Enter a whole number, then shift-click the setting.");}},"Left increases; right decreases.","Shift-click uses the number in the input bar.","Unsafe values are rejected before saving.");}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
        footer(Page.ADMIN);
    }
    private void tiers(){
        List<Config.Tier> tiers=draft.tiers;offset=Math.min(offset,Math.max(0,((tiers.size()-1)/36)*36));
        for(int i=0;i<36&&offset+i<tiers.size();i++){Config.Tier t=tiers.get(offset+i);String reason=Config.unavailable(draft,t);button(i,ControlGlyph.TIER,reason.isEmpty()?State.ON:State.OFF,t.name+(reason.isEmpty()?" [READY]":" [BLOCKED]"),c->{selectedTier=t.id;navigate(Page.TIER);},"ID: "+t.id,"Predecessor: "+t.predecessor,"Material: "+t.material,"Slots: "+t.slots+"; upgrade slots: "+t.upgrades,reason.isEmpty()?"Recipe enabled by this draft":reason);}
        SortedSet<String> found=new TreeSet<>();for(ResourceLocation id:BuiltInRegistries.ITEM.keySet()){String p=id.getPath();if((p.contains("dragonsteel")||p.contains("witherite"))&&p.contains("ingot")&&draft.tiers.stream().noneMatch(t->t.material.equals(id.toString())))found.add(id.toString());}
        int slot=36;for(String id:found){if(slot>44)break;button(slot++,ControlGlyph.MATERIAL,"Detected: "+id,c->addTier(id),"Create a disabled tier using this exact server item.","Material names do not establish progression balance.");}
        footer(Page.ADMIN);button(46,ControlGlyph.PREVIOUS,"Previous tiers",c->offset=Math.max(0,offset-36));button(47,ControlGlyph.NEXT,"Next tiers",c->offset+=36);button(51,ControlGlyph.ADD_TIER,"Add held material tier",c->{ItemStack held=owner.getMainHandItem();if(held.isEmpty()||held.is(Content.BACKPACK.get()))throw new IllegalArgumentException("Hold the actual ingredient before opening settings.");addTier(Categories.id(held));},"Uses the exact installed item ID.","New tiers start OFF. Set predecessor and review first.");
    }
    private void addTier(String material){
        if(draft.tiers.size()>=64)throw new IllegalArgumentException("Tier limit is 64");
        String base=material.replace(':','_').replaceAll("[^a-z0-9_-]","_");if(base.length()>34)base=base.substring(0,34);String id=base;for(int i=2;draft.tier(id)!=null;i++)id=base+"_"+i;
        Config.Tier previous=draft.tier(selectedTier);if(previous==null)previous=draft.tier("netherite");if(previous==null)previous=draft.tier("starter");
        String name=new ItemStack(material(material)).getHoverName().getString();if(name.length()>48)name=name.substring(0,48);
        Config.Tier tier=new Config.Tier(id,name,previous.id,material,Math.min(216,previous.slots+36),Math.min(7,previous.upgrades+1),8,false);draft.tiers.add(tier);selectedTier=id;navigate(Page.TIER);
    }
    private static Item material(String id){return Config.installed(id)?BuiltInRegistries.ITEM.get(ResourceLocation.parse(id)):Items.BARRIER;}
    private void tier(){
        Config.Tier t=draft.tier(selectedTier);if(t==null){navigate(Page.TIERS);return;}
        toggle(10,ControlGlyph.TIER,"Enable "+t.name,t.enabled,()->{if(!t.enabled&&!t.id.equals("starter")&&!Config.installed(t.material))throw new IllegalArgumentException("Material is missing");t.enabled=!t.enabled;});
        button(11,ControlGlyph.CAPACITY,"Capacity: "+t.slots,c->t.slots=number(c,t.slots,9),"Multiple of 9; maximum 216.","Existing bags never lose allocated capacity.");
        button(12,ControlGlyph.UPGRADE_CAPACITY,"Upgrade slots: "+t.upgrades,c->t.upgrades=number(c,t.upgrades,1),"Range 0..7; cannot be below predecessor.");
        if(!t.id.equals("starter")){
            button(13,ControlGlyph.RECIPE_CELLS,"Recipe cells: "+t.cost,c->t.cost=number(c,t.cost,1),"One predecessor backpack plus 1..8 ingredient cells.","Each occupied material cell consumes one item.");
            button(14,ControlGlyph.PREDECESSOR,"Predecessor: "+t.predecessor,c->{List<String> ids=draft.tiers.stream().map(x->x.id).filter(id->!id.equals(t.id)).toList();int index=ids.indexOf(t.predecessor);t.predecessor=ids.get(Math.floorMod(index+(c.button==1?-1:1),ids.size()));},"Click to cycle. Cycles and shrinking upgrades are rejected.");
            button(15,ControlGlyph.MATERIAL,"Material: "+t.material,c->{ItemStack held=owner.getMainHandItem();if(held.isEmpty()||held.is(Content.BACKPACK.get()))throw new IllegalArgumentException("Hold a non-backpack ingredient.");t.material=Categories.id(held);},"Click to use the held item's exact registry ID.");
        }
        button(16,ControlGlyph.NAME,"Name: "+t.name,c->{if(input.isBlank()||input.length()>48)throw new IllegalArgumentException("Enter a name up to 48 characters.");t.name=input;},"Enter a name in the input bar, then click.");
        String reason=Config.unavailable(draft,t);
        button(22,ControlGlyph.RECIPE_PREVIEW,"Recipe preview",null,"Effective predecessor: "+(t.id.equals("starter")?"starter recipe":Config.predecessor(draft,t)),"Ingredient: "+t.material,"Occupied ingredient cells: "+t.cost,"Result retains contents, identity and installed upgrades.","Result minimum: "+t.slots+" slots / "+t.upgrades+" upgrades");
        button(24,ControlGlyph.RECIPE_STATUS,reason.isEmpty()?State.ON:State.OFF,"Why is this unavailable?",null,reason.isEmpty()?"This draft enables the recipe.":reason,"Compatibility master: "+draft.compatibility,"Save the draft before crafting. Nothing generates ingots.");
        footer(Page.TIERS);
    }
    private int number(Click c,int old,int step){try{return c.shift?Integer.parseInt(input):old+(c.button==1?-step:step);}catch(NumberFormatException e){throw new IllegalArgumentException("Enter a whole number, then shift-click.");}}
    private void ores(){
        List<String> ids=new ArrayList<>(draft.oreValues.keySet());Collections.sort(ids);offset=Math.min(offset,Math.max(0,((ids.size()-1)/45)*45));
        for(int i=0;i<45&&offset+i<ids.size();i++){String id=ids.get(offset+i);button(i,ControlGlyph.ORE_VALUES,id+": "+draft.oreValues.get(id)+" points",c->{if(c.button==2)draft.oreValues.remove(id);else draft.oreValues.put(id,number(c,draft.oreValues.get(id),1));},"Left +1, right -1; shift-click uses typed number.","Accepted values are 1..100000. Middle-click removes the rule.");}
        footer(Page.ADMIN);button(51,ControlGlyph.ORE_ADD,"Add held ore/resource",c->{ItemStack held=owner.getMainHandItem();if(held.isEmpty())throw new IllegalArgumentException("Hold the resource first.");draft.oreValues.putIfAbsent(Categories.id(held),1);},"The actual held registry ID is used.");
    }
    private void recipes(){
        for(int i=0;i<Config.GROUPS.size();i++){String id=Config.GROUPS.get(i);toggle(10+i,ControlGlyph.setting(id),id,draft.recipes.get(id),()->draft.recipes.put(id,!draft.recipes.get(id)),"Disabling crafting never unregisters existing items.");}
        String[] options={"OFF / undecided","Dragon's Breath route","Tracked dragon victory-token route"};
        button(22,ControlGlyph.CUBE_RECIPE,"Cube recipe: "+options[draft.cubeRecipe],c->draft.cubeRecipe=(draft.cubeRecipe+1)%3,"Choose explicitly; the default is OFF.","Both active routes: 1 storage core + 4 Nether Stars.","Breath route also needs 4 Dragon's Breath.","Token route also needs 4 tracked fight victory tokens.","Breath does NOT prove repeated dragon kills.","Also enable the endgame recipe gate above.");footer(Page.ADMIN);
    }
    private void upgradeGates(){for(int i=0;i<Config.UPGRADE_IDS.size();i++){String id=Config.UPGRADE_IDS.get(i);toggle(10+i,ControlGlyph.setting(id),id,draft.upgrades.get(id),()->draft.upgrades.put(id,!draft.upgrades.get(id)),"Controls this upgrade's behavior and crafting recipe.","Installed upgrades are retained when disabled.");}footer(Page.ADMIN);}
    private void presets(){
        button(11,ControlGlyph.SURVIVAL,"Conservative survival",c->{draft.initialRadius=16;draft.maxRadius=48;draft.quickRadius=6;draft.maxContainers=64;draft.scanChunksPerTick=1;},"16 -> 48 range, 6-block nearby QuickStack.","64 containers and 1 scanned chunk per tick.","Only edits the draft. Existing content is untouched.");
        button(15,ControlGlyph.LARGE_NETWORK,"Larger beta-test network",c->{draft.initialRadius=16;draft.maxRadius=96;draft.quickRadius=12;draft.maxContainers=256;draft.scanChunksPerTick=4;},"16 -> 96 range, 12-block nearby QuickStack.","256 containers and 4 scanned chunks per tick.","Safety checks remain mandatory.");footer(Page.ADMIN);
    }
    private void configConfirm(){
        List<String> changes=Config.changes(Config.get(),draft);for(int i=0;i<Math.min(36,changes.size());i++)button(i,ControlGlyph.CHANGE,changes.get(i),null);
        button(40,ControlGlyph.IMPACT,"Impact preview",null,Network.impact(draft),"Existing containers, contents and occupied bags are not deleted.");
        button(45,ControlGlyph.BACK,"Back to draft",c->navigate(Page.ADMIN));button(53,ControlGlyph.APPLY,"Apply server settings",c->{if(Config.save(owner,draft))owner.closeContainer();},"Operator permission and revision are rechecked.","Previous config is backed up. Recipes reload on the server.");
    }
    private void prefs(){
        for(int i=0;i<36;i++){
            int slot=i;ItemStack item=owner.getInventory().getItem(slot);
            boolean locked=Profiles.of(owner).protectedSlot(slot);
            String name="Slot "+(slot+1)+(locked?" [PROTECTED]":" [SWEEPABLE]");
            String[] details={"Click: protection. Shift-click: explain QuickStack. Shift-right: explain Dump.",slot<9?"Hotbar slot":"Main inventory slot"};
            Consumer<Click> change=c->{if(c.shift){explainSlot(slot,c.button==1);return;}Profiles.of(owner).protectedSlots^=1L<<slot;Profiles.changed(owner);};
            if(item.isEmpty())button(i,ControlGlyph.SLOT_PREF,locked?State.ON:State.OFF,name,change,details);
            else{display.setItem(i,icon(item,name,List.of(details)));buttons.put(i,change);}
        }
        button(45,ControlGlyph.BACK,"Back",c->navigate(bag!=null?Page.BAG:hub!=null?Page.NETWORK:chest!=null?Page.CHEST:owner.hasPermissions(2)?Page.ADMIN:Page.PREFS));
        button(48,ControlGlyph.HOTBAR,"Protect hotbar",c->{Profiles.of(owner).protectedSlots|=511;Profiles.changed(owner);});
        button(50,ControlGlyph.EXPLAIN,"Explain skipped items",null,Profiles.of(owner).lastReport,"Armor and offhand are always outside automatic sweeps.");button(53,ControlGlyph.CLOSE,"Close",c->owner.closeContainer());
    }
    private void base(){
        if(hub==null||!hub.ownedBy(owner))return;
        button(10,ControlGlyph.BASE_NAME,"Base: "+hub.baseName,c->{if(input.isBlank()||input.length()>48)throw new IllegalArgumentException("Enter a base name up to 48 characters.");hub.baseName=input;hub.profileChanged();},"Enter a name in the input bar, then click.");
        button(12,ControlGlyph.MEMBERS,"Member permissions",c->navigate(Page.ACCESS),"Edit View, Deposit, Withdraw and Activity Log separately.","Existing trusted members keep their previous storage access.","New members begin with View only; claims still apply.");
        button(14,ControlGlyph.FEED_ORE,"Feed ore/resource from inventory",null,"Click an inventory slot while in this base menu.","Left feeds a stack; right feeds one.","Only resources with configured point values are accepted.");
        button(16,ControlGlyph.RADIUS,"Radius: "+hub.radius()+" -> "+hub.nextRadius(),c->{if(hub.radius()>=Config.get().maxRadius)throw new IllegalArgumentException("Maximum radius reached.");if(hub.oreProgress<hub.upgradeCost())throw new IllegalArgumentException("Not enough ore progress.");int cost=hub.upgradeCost();hub.oreProgress-=cost;hub.rangeUpgrades++;hub.profileChanged();AnyaStorage.tell(owner,"Spent "+cost+" points. Radius is now "+hub.radius()+".");},"Stored ore points: "+hub.oreProgress,"Next upgrade costs: "+hub.upgradeCost(),"Click explicitly to spend points and upgrade.","One radius controls search, terminal access and QuickStack.");
        button(28,ControlGlyph.ROUTING,"Container routing",null,"Bind a remote to this hub.","Sneak-use the remote on a supported chest.","Set category, exact-item filters, priority and overflow there.");
        button(31,ControlGlyph.PERFORMANCE,"Performance",null,Network.performance(hub));button(34,ControlGlyph.TRUSTED,"Trusted UUIDs",null,hub.trusted.toString());
        button(40,ControlGlyph.BASE_LOG,"Base activity log",c->navigate(Page.ACTIVITY),"Opt-in counts-only log; actor UUIDs require server opt-in.","Inventory components and private images are never logged.");
        footer(Page.NETWORK);
    }
    private enum MemberRight {
        VIEW(BasePermissions.VIEW, ControlGlyph.VIEW, "View contents"),
        DEPOSIT(BasePermissions.DEPOSIT, ControlGlyph.DEPOSIT, "Deposit"),
        WITHDRAW(BasePermissions.WITHDRAW, ControlGlyph.WITHDRAW, "Withdraw"),
        LOG(BasePermissions.LOG, ControlGlyph.ACTIVITY, "Read activity log"),
        VISIT(BasePermissions.VISIT, ControlGlyph.VISIT, "Visit experimental pocket"),
        BUILD(BasePermissions.BUILD, ControlGlyph.BUILD, "Build in experimental pocket");

        final int flag;
        final ControlGlyph glyph;
        final String title;
        MemberRight(int flag,ControlGlyph glyph,String title){this.flag=flag;this.glyph=glyph;this.title=title;}
    }

    private void access(){
        if(hub==null||!hub.ownedBy(owner))return;
        memberPage(hub.trusted.stream().sorted().toList(), id->{
            ServerPlayer online=owner.getServer().getPlayerList().getPlayer(id);
            return online==null?id.toString():online.getGameProfile().getName();
        },hub::memberMask);
    }

    /** Same display slots as the owner screen. The client smoke renderer cannot apply intents. */
    public void renderAccessFixture(){
        if(owner!=null||!Boolean.getBoolean("anyastorage.clientSmoke"))throw new IllegalStateException("Not a development renderer fixture");
        resetControlSlots();
        display.clearContent();
        buttons.clear();
        selectedMember=new UUID(0,1);
        memberPage(List.of(selectedMember), id->"AnyaQABeta", id->BasePermissions.VIEW);
    }

    private void memberPage(List<UUID> members,java.util.function.Function<UUID,String> nameOf,java.util.function.ToIntFunction<UUID> maskOf){
        sync.set(4,16);
        int perPage=18;
        offset=Math.min(offset,Math.max(0,((members.size()-1)/perPage)*perPage));
        for(int i=0;i<perPage&&offset+i<members.size();i++){
            UUID id=members.get(offset+i);
            button(i,ControlGlyph.MEMBER,Objects.equals(id,selectedMember)?State.SELECTED:State.NORMAL,nameOf.apply(id),
                c->selectedMember=id,id.toString(),BasePermissions.describe(maskOf.applyAsInt(id)),"Click to select this member.");
        }
        if(selectedMember!=null&&members.contains(selectedMember)){
            String name=nameOf.apply(selectedMember);
            button(27,ControlGlyph.MEMBER,name,null,selectedMember.toString());
            int mask=maskOf.applyAsInt(selectedMember);
            for(MemberRight right:MemberRight.values()){
                toggle(28+right.ordinal(),right.glyph,right.title,BasePermissions.allows(mask,right.flag),
                    ()->hub.setMember(selectedMember,hub.memberMask(selectedMember)^right.flag),
                    "Selected member: "+name,"Applies immediately. Queued transfer plans become stale.",
                    switch(right){
                        case LOG->"Also requires server activityMemberLogs and activityLogging.";
                        case BUILD->"Requires Visit and pocketEditing. Chest editing also needs View, Deposit and Withdraw.";
                        case VISIT->"Experimental pocket entry also requires the server's pocket feature.";
                        default->"QuickStack/Dump require View + Deposit. Organize requires all three.";
                    });
            }
            button(34,ControlGlyph.REMOVE_MEMBER,"Remove selected member",c->{hub.removeMember(selectedMember);selectedMember=null;},"Removes all hub-interface access immediately.");
        }
        button(45,ControlGlyph.BACK,"Back",c->navigate(Page.BASE));
        button(46,ControlGlyph.PREVIOUS,offset>0?State.NORMAL:State.DISABLED,"Previous members",c->offset=Math.max(0,offset-perPage));
        button(47,ControlGlyph.NEXT,offset+perPage<members.size()?State.NORMAL:State.DISABLED,"Next members",c->offset+=perPage);
        button(50,ControlGlyph.ADD_MEMBER,"Select/add member from input",c->{
            ServerPlayer target=owner.getServer().getPlayerList().getPlayerByName(input);
            UUID id;
            try{id=target!=null?target.getUUID():UUID.fromString(input);}
            catch(IllegalArgumentException ex){throw new IllegalArgumentException("Enter an online player name or full UUID.");}
            if(Objects.equals(id,hub.owner))throw new IllegalArgumentException("The owner always retains access.");
            if(!hub.trusted.contains(id))hub.setMember(id,BasePermissions.VIEW);
            selectedMember=id;
        },"Enter an online name or full UUID in the input.","New member: View only, with no deposit or withdrawal.");
        button(53,ControlGlyph.CLOSE,"Close",c->owner.closeContainer());
    }
    private void activity(){
        if(hub==null)return;var policy=ExpansionRules.get();
        List<BaseActivity.Entry> list=hub.canReadActivity(owner)?hub.activity.visible(policy):List.of();
        offset=Math.min(offset,Math.max(0,((list.size()-1)/45)*45));
        for(int i=0;i<45&&offset+i<list.size();i++){var e=list.get(offset+i);button(i,ControlGlyph.LOG_LINE,e.action()+": "+e.count()+" items",null,java.time.Instant.ofEpochMilli(e.time()).toString(),e.actor()==null?"Actor identity not collected / redacted":"Actor UUID: "+e.actor(),"Successful hub-interface operation only; not a recovery journal.");}
        footer(hub.ownedBy(owner)?Page.BASE:Page.NETWORK);
        button(49,ControlGlyph.LOG_PRIVACY,policy.activityLogging?"Activity privacy":"Activity logging is OFF",null,"Default: off. Optional actor IDs: "+policy.activityActorIds,"Member visibility: "+policy.activityMemberLogs+" (also needs Log permission)","Limit per base: "+policy.activityLogEntries+" entries; no item data or image data.");
        if(hub.ownedBy(owner))button(51,ControlGlyph.CLEAR_LOG,clearLogConfirm?State.SELECTED:State.NORMAL,clearLogConfirm?"Confirm clearing this base history":"Clear this base history",c->{if(!clearLogConfirm){clearLogConfirm=true;return;}hub.activity.clear();hub.setChanged();clearLogConfirm=false;},"Two clicks. Does not alter containers, items or permissions.");
    }
    private void routing(){
        if(hub==null||routePos==null)return;StorageEntity.Route route=hub.routes.computeIfAbsent(routePos.asLong(),k->new StorageEntity.Route());
        button(10,ControlGlyph.CONTAINER,"Container "+routePos.toShortString(),null,"Routing changes apply to this base only.");
        button(12,ControlGlyph.CATEGORY,"Category: "+route.category,c->{route.category=Config.CATEGORIES.get(Math.floorMod(Config.CATEGORIES.indexOf(route.category)+(c.button==1?-1:1),Config.CATEGORIES.size()));hub.profileChanged();},"Click to cycle built-in categories.");
        button(14,ControlGlyph.PRIORITY,"Priority: "+route.priority,c->{route.priority=Math.floorMod(route.priority+(c.button==1?-1:1),10);hub.profileChanged();},"Higher priorities are filled first.");
        toggle(16,ControlGlyph.OVERFLOW,"Allow overflow",route.overflow,()->{route.overflow=!route.overflow;hub.profileChanged();},"Only matching filters/categories are accepted.","A generic overflow chest makes organization more flexible.");
        button(28,ControlGlyph.FILTER,"Toggle exact-item filter from input",c->{if(!Config.installed(input))throw new IllegalArgumentException("Enter an installed item ID, such as minecraft:diamond.");if(!route.items.remove(input)){if(route.items.size()>=64)throw new IllegalArgumentException("Filter limit reached.");route.items.add(input);}hub.profileChanged();},"Selected input: "+input,"An empty item list means category-only filtering.",route.items.toString());
        button(30,ControlGlyph.CLEAR_FILTER,"Clear item filters",c->{route.items.clear();hub.profileChanged();});
        button(32,ControlGlyph.ROUTE_ORDER,"Routing order",null,"Designated category / exact-item filters first.","Then existing identical stacks; then allowed overflow.","Ties use stable block-position ordering.");footer(Page.NETWORK);
    }
    private void upgrades(){
        if(bag==null)return;
        for(int i=0;i<Config.UPGRADE_IDS.size();i++){String id=Config.UPGRADE_IDS.get(i);boolean installed=Upgrades.installed(bag,id);button(10+i,ControlGlyph.setting(id),installed?State.ON:State.OFF,id+(installed?" [INSTALLED]":" [EMPTY]"),c->{boolean remove=c.button==1;if(Upgrades.change(bag,id,remove)){int slot=bag.playerSlot;StoreMenu.bag(owner,new BagInventory(owner,slot),true);}},"Left: install from your inventory.","Right: uninstall and return the exact upgrade item.","Server enabled: "+Config.get().upgrades.get(id));}
        button(27,ControlGlyph.WARDROBE,"Wardrobe / wear / interiors",c->navigate(Page.WARDROBE),"Element, cosmetic skin and storage tier are independent.");
        button(21,ControlGlyph.UPGRADE_LIST,"Installed upgrade slots",null,Upgrades.installedItems(bag.stack).size()+" / "+BagInventory.data(bag.stack).getInt("UpgradeSlots"),"Automatic upgrades use the first upgraded backpack.","Automation pauses while a container screen is open.");
        button(23,ControlGlyph.PICKUP_FILTER,"Pickup filter",null,"Auto-stores items after normal vanilla pickup.","Empty filter: only existing identical stacks.",BagInventory.data(bag.stack).getList("PickupFilter",Tag.TAG_STRING).toString());
        button(25,ControlGlyph.SMELT_FILTER,"Smelting filter / fuel",null,"No filter means no automatic smelting.","Fuel: ordinary coal/charcoal stored inside the bag.","Fuel ticks: "+BagInventory.data(bag.stack).getInt("FuelTicks"),BagInventory.data(bag.stack).getList("SmeltFilter",Tag.TAG_STRING).toString());
        button(30,ControlGlyph.FEEDING,"Feeding behavior",null,"Only ordinary safe vanilla food is eaten.","Named/component-modified food is excluded.","Refill tops up existing hotbar stacks without sweeping them.");
        button(45,ControlGlyph.BACK,"Back to backpack",c->navigate(Page.BAG));button(46,ControlGlyph.CRAFT,"Portable crafting",c->Upgrades.craft(bag));
        button(47,ControlGlyph.COMPACT,"Compact one safe recipe",c->AnyaStorage.tell(owner,Upgrades.compact(bag)));button(48,ControlGlyph.RESUPPLY,"Supply a tool to an empty selected hotbar slot",c->Upgrades.resupply(bag));
        button(49,ControlGlyph.EDIT_PICKUP,"Toggle pickup filter: "+input,c->Upgrades.filter(bag,"PickupFilter",input),"Type an item ID or click an inventory item below.");
        button(50,ControlGlyph.EDIT_SMELT,"Toggle smelt filter: "+input,c->Upgrades.filter(bag,"SmeltFilter",input));
        button(51,ControlGlyph.STOP_SMELT,"Clear both filters / stop smelting",c->{CompoundTag tag=BagInventory.data(bag.stack);tag.remove("PickupFilter");tag.remove("SmeltFilter");BagInventory.data(bag.stack,tag);owner.getInventory().setChanged();});
        button(53,ControlGlyph.CLOSE,"Close",c->owner.closeContainer());
    }
    private void explainSlot(int slot,boolean dump){
        String report;
        if(bag==null&&chest==null)report=Network.explain(owner,hub,slot,dump);
        else if(Profiles.of(owner).protectedSlot(slot))report="Slot "+(slot+1)+" is protected from automatic deposits.";
        else if(!Config.get().quickStack||(bag!=null&&!Config.get().backpacks))report="The relevant storage feature is disabled by the server.";
        else{
            MainInventory main=main();ItemStack item=main.getItem(slot);Container target=bag!=null?bag:chest;
            if(item.isEmpty())report="That slot is empty or is the locked backpack host slot.";
            else{
                Transactions.Plan plan=new Transactions.Plan(List.of(main,target),()->stillValid(owner));boolean matches=plan.contains(target,item);
                if(dump)for(ItemStack old:plan.work(target))if(!old.isEmpty()&&old.is(item.getItem()))matches=true;
                if(!matches)report="No "+(dump?"item-type":"exact item-and-component")+" match already exists in this container.";
                else{int count=plan.move(main,slot,List.of(target),item.getCount());report=count==0?"Destination is full or rejects nested/unsafe containers.":"Preview only: "+count+" of "+item.getCount()+" items fit. Nothing moved.";}
            }
        }
        Profiles.of(owner).lastReport=report;AnyaStorage.tell(owner,report);
    }
    private void selectOre(int slot,Click click){
        if(hub==null||!hub.ownedBy(owner))return;
        ItemStack item=owner.getInventory().getItem(slot);
        if(!Transactions.same(item,playerSeen[slot]))throw new IllegalArgumentException("Inventory changed; select the refreshed slot.");
        Integer value=Config.get().oreValues.get(Categories.id(item));
        if(item.isEmpty()||value==null)throw new IllegalArgumentException("This item has no configured ore-feeder value.");
        if(item.is(Content.BACKPACK.get())||item.has(DataComponents.BLOCK_ENTITY_DATA)||item.has(DataComponents.BUNDLE_CONTENTS)||!item.getOrDefault(DataComponents.CONTAINER,net.minecraft.world.item.component.ItemContainerContents.EMPTY).equals(net.minecraft.world.item.component.ItemContainerContents.EMPTY))throw new IllegalArgumentException("Containers cannot be burned as ore fuel.");
        feedCount=Math.min(click.button==1?1:item.getCount(),Math.max(0,(1000000-hub.oreProgress)/value));
        if(feedCount==0)throw new IllegalArgumentException("Ore progress is full; upgrade before feeding more.");
        feedSlot=slot;feedPoints=Math.multiplyExact(feedCount,value);feedBefore=hub.oreProgress;feedRevision=Config.get().revision;feedSnapshot=item.copy();navigate(Page.FEED_CONFIRM);
    }
    private void feedConfirm(){
        button(13,feedSnapshot.getItem(),"Consume "+feedCount+" "+feedSnapshot.getHoverName().getString(),null,"Adds "+feedPoints+" upgrade points.","Stored progress: "+feedBefore+" -> "+(feedBefore+feedPoints),"Current radius: "+hub.radius()+"; next radius: "+hub.nextRadius(),"Next upgrade costs "+hub.upgradeCost()+" points.","Feeding does not automatically buy the radius upgrade.");
        button(45,ControlGlyph.CANCEL,"Cancel feeding",c->navigate(Page.BASE));
        button(53,ControlGlyph.CONFIRM_FEED,"Confirm feeding",c->{
            if(Config.get().revision!=feedRevision||hub.oreProgress!=feedBefore||!Transactions.same(owner.getInventory().getItem(feedSlot),feedSnapshot))throw new IllegalArgumentException("Ore, progress or settings changed. Select the resource again.");
            MainInventory inventory=new MainInventory(owner);Transactions.Plan plan=new Transactions.Plan(List.of(inventory),()->stillValid(owner)&&hub.ownedBy(owner)&&Config.get().revision==feedRevision&&hub.oreProgress==feedBefore);
            ItemStack consumed=plan.work(inventory)[feedSlot].copyWithCount(feedCount);plan.work(inventory)[feedSlot].shrink(feedCount);plan.authorizeRecipe(List.of(consumed),List.of());
            String error=plan.commit(owner.getServer());if(!error.isEmpty())throw new IllegalArgumentException(error);
            hub.oreProgress=feedBefore+feedPoints;hub.profileChanged();AnyaStorage.tell(owner,"Added "+feedPoints+" ore points. Choose the radius upgrade button separately to spend them.");navigate(Page.BASE);
        });
    }
    private void categories(){
        List<String> keys=new ArrayList<>(draft.categoryRules.keySet());offset=Math.min(offset,Math.max(0,((keys.size()-1)/45)*45));
        List<String> categories=Config.CATEGORIES.stream().filter(s->!s.equals("any")).toList();
        for(int i=0;i<45&&offset+i<keys.size();i++){
            String key=keys.get(offset+i),category=draft.categoryRules.get(key);
            button(i,ControlGlyph.CATEGORY_RULES,key+" -> "+category,c->{if(c.button==2)draft.categoryRules.remove(key);else draft.categoryRules.put(key,categories.get(Math.floorMod(categories.indexOf(category)+(c.button==1?-1:1),categories.size())));},"Left/right cycles categories; middle-click removes.","Exact item rules override tag rules.","Tag rules are evaluated in displayed insertion order.");
        }
        footer(Page.ADMIN);
        button(50,ControlGlyph.HELD_RULE,"Add held item rule",c->{ItemStack held=owner.getMainHandItem();if(held.isEmpty())throw new IllegalArgumentException("Hold an item before opening settings.");draft.categoryRules.putIfAbsent(Categories.id(held),"misc");});
        button(51,ControlGlyph.TYPE_RULE,"Add rule from input",c->{String key=input;if(!Config.validId(key.startsWith("#")?key.substring(1):key))throw new IllegalArgumentException("Enter namespace:item or #namespace:tag.");if(!key.startsWith("#")&&!Config.installed(key))throw new IllegalArgumentException("That item is not installed.");draft.categoryRules.putIfAbsent(key,"misc");},"For example minecraft:diamond or #c:ores.","Rules are only active after reviewing and applying the draft.");
    }
    private void diagnostics(){
        button(10,ControlGlyph.SETTINGS,"Configuration",null,Config.lastStatus,"Revision: "+Config.get().revision,"Settings: /anya storage or /as");
        button(12,ControlGlyph.PERMISSIONS,"Permissions",null,Safety.status(),"Ordinary storage scans are loaded-only. Pocket test sessions use bounded temporary leases.","Only explicitly supported physical inventories are scanned.");
        button(14,ControlGlyph.EXPLAIN,"Why were items skipped?",null,Profiles.of(owner).lastReport,"Click an inventory item below for a fresh preview.","Left checks QuickStack; right checks Dump. Nothing moves.");
        button(16,ControlGlyph.PERFORMANCE,"Performance",null,hub==null?"Last transfer: "+Network.lastOperationMicros+" us":Network.performance(hub));
        button(28,ControlGlyph.SAFETY,"Beta safety scope",null,"Server-thread snapshots and component conservation checks.","Permissions, range and inventory identity are rechecked.","Hard-crash durability across vanilla save files is not guaranteed.","Back up your test world; do not remove your existing mods yet.");
        footer(bag!=null?Page.BAG:hub!=null?Page.NETWORK:chest!=null?Page.CHEST:owner.hasPermissions(2)?Page.ADMIN:Page.PREFS);
    }
    public BagInventory imageBag(){return owner!=null&&bag!=null&&bag.valid()?bag:null;}
    public boolean wardrobeOpen(){return page==Page.WARDROBE;}
    public boolean moderatingImage(UUID id){return owner!=null&&owner.hasPermissions(2)&&page==Page.IMAGES&&Objects.equals(selectedImage,id);}
    public void refreshAppearance(){if(owner!=null){render();bump();lastAppearance="";syncAppearance();sendAllDataToRemote();}}
    private void syncAppearance(){
        Looks.Look look=bag==null?new Looks.Look("plain","elemental","oak","atelier"):Looks.of(bag.stack);
        CompoundTag data=bag==null?new CompoundTag():BagInventory.data(bag.stack);
        UUID id=data.hasUUID("InteriorImage")?data.getUUID("InteriorImage"):ExpansionPackets.NONE;
        if(page==Page.IMAGES&&selectedImage!=null&&owner.hasPermissions(2))id=selectedImage;
        UUID bagId=data.hasUUID("BagId")?data.getUUID("BagId"):ExpansionPackets.NONE;
        String signature=look.toString()+id+bagId+page;
        if(signature.equals(lastAppearance))return;lastAppearance=signature;
        PacketDistributor.sendToPlayer(owner,new ExpansionPackets.Look(-1,bagId,containerId,session(),look.texture(),look.element(),look.interior(),id,page==Page.WARDROBE));
    }
    private void wardrobe(){
        if(bag==null)return;Looks.Look look=Looks.of(bag.stack);
        for(int i=0;i<Looks.ELEMENTS.size();i++){
            String element=Looks.ELEMENTS.get(i);boolean unlocked=Looks.unlocked(bag.stack,element,true);
            button(i,ControlGlyph.valueOf("ELEMENT_"+element.toUpperCase(Locale.ROOT)),look.element().equals(element)?State.SELECTED:unlocked?State.NORMAL:State.DISABLED,Looks.title(element)+(look.element().equals(element)?" [ACTIVE]":unlocked?" [UNLOCKED]":" [CRAFT BINDING]"),c->{
                if(!unlocked)throw new IllegalArgumentException("Craft this elemental binding, then combine it with the backpack in a crafting grid.");Looks.apply(bag.stack,element,true);owner.getInventory().setChanged();
            },"One elemental path at a time. Appearance does not grant stats.","Defaults: stone +2 armor; diamond +4; magma 20% less fire damage.","Water +10% swim speed in water; no water breathing or immunity.","Server rules may change these values. Armor/Origins effects are not replaced.");
        }
        for(int i=0;i<Looks.SKINS.size();i++){
            String skin=Looks.SKINS.get(i);boolean unlocked=Looks.unlocked(bag.stack,skin,false);
            button(18+i,ControlGlyph.valueOf("SKIN_"+skin.toUpperCase(Locale.ROOT)),look.skin().equals(skin)?State.SELECTED:unlocked?State.NORMAL:State.DISABLED,Looks.title(skin)+(look.skin().equals(skin)?" [WORN LOOK]":unlocked?" [UNLOCKED]":" [CRAFT PATCH]"),c->{
                if(!unlocked)throw new IllegalArgumentException("Craft this skin patch, then combine it with the backpack. Unlocked looks can be switched freely.");Looks.apply(bag.stack,skin,false);owner.getInventory().setChanged();
            },"Cosmetic slot only. No armor or capacity changes.","Tier upgrades preserve this look and all unlocked skins.");
        }
        button(36,ControlGlyph.RECIPE_PREVIEW,"Accessible starter recipes",null,"Leather: 6 leather + 2 string + 1 chest.","Leaf: 4 matching leaves + 2 leather + 2 string.","All eight vanilla leaf species have recipes.","Bindings: stone/cobblestone, diamond, magma, prismarine, leaves.","Skin patches use paper, dyes and themed vanilla materials.","Recipes are supplied as normal crafting JSON; no new ore generation.");
        button(38,ControlGlyph.PROGRESSION,"Independent progression",null,"Element: "+look.element(),"Skin: "+look.skin(),"Storage tier: "+BagInventory.data(bag.stack).getString("TierName"),"Capacity: "+bag.getContainerSize(),"Netherite and descendants may grant a small knockback bonus.");
        button(45,ControlGlyph.BACK,"Back to backpack",c->navigate(Page.BAG));
        boolean worn=BagInventory.data(bag.stack).getUUID("BagId").equals(Profiles.of(owner).wornBag);
        button(46,ControlGlyph.WEAR,bag.playerSlot<0?State.DISABLED:worn?State.ON:State.OFF,bag.playerSlot<0?"Equipped in Accessories back slot":worn?"Unequip worn backpack":"Wear this backpack",c->{if(bag.playerSlot>=0)Wearables.toggle(owner,bag.stack);},"References this inventory item; no duplicate copy is created.","Only one backpack can grant benefits. Duplicate IDs disable benefits.","Dropping, death or losing the item removes the bonus.");
        button(48,ControlGlyph.PERSONAL_IMAGES,"My private image library",c->navigate(Page.IMAGES),"Drag ONE PNG file onto this Wardrobe screen to upload.","32..256 px per side; RGB/RGBA 8-bit; at most 64 KiB.","No URLs, filenames or local paths are sent to the server.","Uploads are OFF until your operator permits them.");
        button(49,ControlGlyph.INTERIOR,"Built-in interior: "+Looks.title(look.interior()),c->{CompoundTag t=BagInventory.data(bag.stack);int i=Looks.INTERIORS.indexOf(look.interior());if(c.shift)t.remove("Interior");else t.putString("Interior",Looks.INTERIORS.get(Math.floorMod(i+(c.button==1?-1:1),Looks.INTERIORS.size())));t.remove("InteriorImage");BagInventory.data(bag.stack,t);owner.getInventory().setChanged();},"Cycles built-in interiors. Shift-click restores automatic theming.","Custom images can be reattached from your library.");
        button(51,ControlGlyph.IMAGE_PRIVACY,"Image privacy",null,"Owner-only by default, including before moderation approval.","Other viewers need approval, sharing enabled and an explicit UUID grant.","Operators may inspect/reject images in the moderation library.","Images never appear on the public backpack model.","Recipients could still save screenshots or copies they already received.");
        button(53,ControlGlyph.CLOSE,"Close",c->owner.closeContainer());
    }
    private void expansion(){
        Field[] fields=ExpansionRules.Options.class.getFields();int start=offset;offset=Math.min(offset,Math.max(0,((fields.length-1)/45)*45));
        for(int i=0;i<45&&offset+i<fields.length;i++){
            Field f=fields[offset+i];Object value;try{value=f.get(draft.expansion);}catch(IllegalAccessException e){throw new IllegalStateException(e);}
            button(i,ControlGlyph.setting(f.getName()),value instanceof Boolean enabled?(enabled?State.ON:State.OFF):State.NORMAL,f.getName()+": "+value,c->{
                try{
                    if(f.getType()==boolean.class)f.setBoolean(draft.expansion,!f.getBoolean(draft.expansion));
                    else if(f.getType()==int.class)f.setInt(draft.expansion,c.shift?Integer.parseInt(input):f.getInt(draft.expansion)+(c.button==1?-1:1));
                    else if(f.getType()==double.class)f.setDouble(draft.expansion,c.shift?Double.parseDouble(input):Math.round((f.getDouble(draft.expansion)+(c.button==1?-.01:.01))*10000)/10000.0);
                    else if(f.getType()==String.class){if(input.isBlank())throw new IllegalArgumentException("Enter an exact material registry ID first.");f.set(draft.expansion,input);}
                    else if(f.getType()==List.class){@SuppressWarnings("unchecked") List<String> values=(List<String>)f.get(draft.expansion);String key=input;if(key.isBlank())throw new IllegalArgumentException("Enter an exact registry ID, or full UUID for upload permissions.");if(!values.remove(key))values.add(key);}
                }catch(ReflectiveOperationException|NumberFormatException e){throw new IllegalArgumentException("Enter a valid number, then shift-click the setting.");}
            },"Boolean: toggle. Number: +/-; shift-click uses the input.","String: type an exact ID and click. Lists: toggle one ID/UUID.","Changes remain a private draft until Review and Apply.","Hard image/particle safety limits cannot be disabled.");
        }
        footer(Page.ADMIN);
    }
    private void images(){
        List<InteriorImages.Entry> values=InteriorImages.list(owner,bag==null&&owner.hasPermissions(2));
        offset=Math.min(offset,Math.max(0,((values.size()-1)/45)*45));
        for(int i=0;i<45&&offset+i<values.size();i++){
            var e=values.get(offset+i);
            button(i,ControlGlyph.IMAGE,Objects.equals(e.id,selectedImage)?State.SELECTED:State.NORMAL,(e.approved?"Approved":"Private / pending")+" "+e.width+" x "+e.height,c->{
                selectedImage=e.id;deleteImage=null;
                if(bag!=null&&e.owner.equals(owner.getUUID()))try{InteriorImages.attach(owner,bag,e.id);}catch(java.io.IOException ex){throw new IllegalArgumentException(ex.getMessage());}
            },"Image ID: "+e.id,"Owner UUID: "+e.owner,"Stored bytes: "+e.bytes,"Explicit shared viewers: "+e.viewers.size(),"Click to select/preview. Your image attaches to the open bag.");
        }
        footer(bag==null?Page.ADMIN:Page.WARDROBE);
        button(48,ControlGlyph.IMAGE_VIEWER,"Toggle selected image viewer",c->{if(selectedImage==null)return;ServerPlayer target=owner.getServer().getPlayerList().getPlayerByName(input);UUID id;try{id=target!=null?target.getUUID():UUID.fromString(input);}catch(Exception e){throw new IllegalArgumentException("Enter an online player name or full UUID.");}AnyaStorage.tell(owner,InteriorImages.manage(owner,selectedImage,"share",id));},"Enter a player name or UUID. Only the owner can share.","Sharing must be enabled and the image approved.");
        if(bag!=null)button(49,ControlGlyph.BUILTIN_INTERIOR,"Use built-in interior",c->InteriorImages.clearAttachment(owner,bag));
        if(owner.hasPermissions(2))button(50,ControlGlyph.APPROVE_IMAGE,"Approve / revoke selected image",c->{if(selectedImage!=null)AnyaStorage.tell(owner,InteriorImages.manage(owner,selectedImage,"approve",null));},"Moderation changes shared access; owners keep their private preview.","To reject and erase an image, use Delete twice.");
        button(51,ControlGlyph.DELETE_IMAGE,Objects.equals(deleteImage,selectedImage)&&selectedImage!=null?State.SELECTED:State.NORMAL,Objects.equals(deleteImage,selectedImage)&&selectedImage!=null?"CONFIRM DELETE selected image":"Delete selected image (two clicks)",c->{
            if(selectedImage==null)return;if(!Objects.equals(deleteImage,selectedImage)){deleteImage=selectedImage;return;}
            AnyaStorage.tell(owner,InteriorImages.manage(owner,selectedImage,"delete",null));deleteImage=null;selectedImage=null;
        },"Deletes only this image. No backpack contents are affected.","All authorized clients are told to discard their cached copy.");
    }

}
