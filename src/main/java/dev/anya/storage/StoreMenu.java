package dev.anya.storage;

import net.minecraft.core.BlockPos;
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
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/** Display-only slots. The only accepted input is a session/revision-checked intent packet. */
public final class StoreMenu extends AbstractContainerMenu {
    private enum Page { NETWORK, BAG, CHEST, ADMIN, LIMITS, TIERS, TIER, ORES, RECIPES, UPGRADE_GATES, PREFS, BASE, ROUTE, UPGRADES, DIAGNOSTICS, PRESETS, CONFIG_CONFIRM, SORT_CONFIRM }
    private record Click(int button,boolean shift) {}
    private record Entry(Container inventory,int slot,ItemStack snapshot,BlockPos position) {}
    private final SimpleContainer display=new SimpleContainer(54);
    private final ContainerData sync=new SimpleContainerData(4);
    private final Map<Integer,Consumer<Click>> buttons=new HashMap<>();
    private final Map<Integer,Entry> entries=new HashMap<>();
    private final ItemStack[] playerSeen=new ItemStack[36];
    private ServerPlayer owner;
    private Page page=Page.PREFS,returnPage=Page.PREFS;
    private BagInventory bag;
    private StorageEntity hub,chest;
    private BlockPos routePos;
    private Config.Data draft;
    private String input="",selectedTier="netherite";
    private int offset;
    private boolean favoritesOnly;
    private long lastAction=-1,lastRefresh=-1;
    private Transactions.Plan pending;
    private List<Container> pendingContainers=List.of();

    public StoreMenu(int id,Inventory inventory){
        super(Content.MENU.get(),id);
        for(int row=0;row<6;row++)for(int col=0;col<9;col++)addSlot(locked(display,row*9+col,17+col*18,44+row*18));
        for(int row=0;row<3;row++)for(int col=0;col<9;col++)addSlot(locked(inventory,9+row*9+col,17+col*18,169+row*18));
        for(int col=0;col<9;col++)addSlot(locked(inventory,col,17+col*18,227));
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
        p.openMenu(new SimpleMenuProvider((id,inv,ignored)->new StoreMenu(id,inv,p,page,bag,hub,chest,route),Component.literal("Anya Storage")));
    }
    public static void settings(ServerPlayer p){open(p,p.hasPermissions(2)?Page.ADMIN:Page.PREFS,null,null,null,null);}
    public static void preferences(ServerPlayer p){open(p,Page.PREFS,null,null,null,null);}
    public static void bag(ServerPlayer p,BagInventory bag,boolean upgrades){open(p,upgrades?Page.UPGRADES:Page.BAG,bag,null,null,null);}
    public static void network(ServerPlayer p,StorageEntity hub){String reason=Network.hubReason(p,hub);if(reason.isEmpty())open(p,Page.NETWORK,null,hub,null,null);else AnyaStorage.tell(p,reason);}
    public static void container(ServerPlayer p,StorageEntity chest){if(Safety.reason(p,chest,null).isEmpty())open(p,Page.CHEST,null,null,chest,null);}
    public static void route(ServerPlayer p,StorageEntity hub,BlockPos pos){if(hub.ownedBy(p))open(p,Page.ROUTE,null,hub,null,pos.immutable());}
    public int session(){return (sync.get(0)&65535)|((sync.get(1)&65535)<<16);}
    public int revision(){return (sync.get(2)&65535)|((sync.get(3)&65535)<<16);}
    private void bump(){int value=revision()+1;sync.set(2,value&65535);sync.set(3,value>>>16);}
    private boolean adminPage(){return switch(page){case ADMIN,LIMITS,TIERS,TIER,ORES,RECIPES,UPGRADE_GATES,PRESETS,CONFIG_CONFIRM->true;default->false;};}
    @Override public boolean stillValid(Player player){
        if(owner==null)return true;if(player!=owner||!owner.isAlive()||owner.isSpectator())return false;
        if(adminPage()&&!owner.hasPermissions(2))return false;
        if(bag!=null&&!bag.valid())return false;
        if(hub!=null&&!Network.hubReason(owner,hub).isEmpty())return false;
        if(chest!=null&&(!chest.stillValid(owner)||!Safety.reason(owner,chest,null).isEmpty()))return false;
        if((page==Page.BASE||page==Page.ROUTE)&&hub!=null&&!hub.ownedBy(owner))return false;
        return true;
    }
    @Override public ItemStack quickMoveStack(Player p,int slot){return ItemStack.EMPTY;}
    @Override public boolean canDragTo(Slot slot){return false;}
    @Override public boolean canTakeItemForPickAll(ItemStack s,Slot slot){return false;}
    @Override public void clicked(int slot,int button,ClickType type,Player p){if(owner!=null)sendAllDataToRemote();}
    @Override public void removed(Player p){if(chest!=null)chest.closeViewer();pending=null;super.removed(p);}
    @Override public void broadcastChanges(){
        if(owner!=null&&owner.containerMenu==this&&stillValid(owner)&&isStoragePage()&&owner.serverLevel().getGameTime()-lastRefresh>=10){render();bump();lastRefresh=owner.serverLevel().getGameTime();}
        super.broadcastChanges();
    }
    public void action(Packets.Action packet){
        if(owner==null||packet.session()!=session()||packet.revision()!=revision()){sendAllDataToRemote();return;}
        if(!stillValid(owner)){owner.closeContainer();return;}
        long now=owner.serverLevel().getGameTime();if(lastAction==now)return;lastAction=now;
        try {
            if(packet.verb().equals("text")){input=packet.text().strip();if(input.length()>128)input=input.substring(0,128);offset=0;}
            else if(packet.verb().equals("slot")){
                int index=packet.value()&255;Click click=new Click((packet.value()>>8)&3,(packet.value()&65536)!=0);
                if(index>=0&&index<54){Consumer<Click> action=buttons.get(index);if(action!=null)action.accept(click);else if(entries.containsKey(index))take(entries.get(index),click);}
                else if(index>=54&&index<90)inventoryClick(slots.get(index).getContainerSlot(),click);
            }
        } catch(IllegalArgumentException e){AnyaStorage.tell(owner,e.getMessage());}
        if(owner.containerMenu==this){if(!stillValid(owner)){owner.closeContainer();return;}render();bump();sendAllDataToRemote();}
    }
    private boolean isStoragePage(){return page==Page.NETWORK||page==Page.BAG||page==Page.CHEST;}
    private void render(){
        buttons.clear();entries.clear();for(int i=0;i<54;i++)display.setItem(i,icon(new ItemStack(Items.GRAY_STAINED_GLASS_PANE)," ",List.of()));
        for(int i=0;i<36;i++)playerSeen[i]=owner.getInventory().getItem(i).copy();
        switch(page){
            case NETWORK,BAG,CHEST->storage();case ADMIN->admin();case LIMITS->limits();case TIERS->tiers();case TIER->tier();case ORES->ores();case RECIPES->recipes();case UPGRADE_GATES->upgradeGates();case PREFS->prefs();case BASE->base();case ROUTE->routing();case UPGRADES->upgrades();case DIAGNOSTICS->diagnostics();case PRESETS->presets();case CONFIG_CONFIRM->configConfirm();case SORT_CONFIRM->sortConfirm();
        }
    }
    private void navigate(Page next){page=next;offset=0;pending=null;}
    private void button(int slot,Item item,String name,Consumer<Click> action,String... lore){display.setItem(slot,icon(new ItemStack(item),name,List.of(lore)));if(action!=null)buttons.put(slot,action);}
    private static ItemStack icon(ItemStack stack,String name,List<String> lore){
        ItemStack copy=stack.copy();if(copy.isEmpty())copy=new ItemStack(Items.PAPER);
        copy.remove(DataComponents.CONTAINER);if(copy.is(Content.BACKPACK.get()))copy.remove(DataComponents.CUSTOM_DATA);
        if(name!=null)copy.set(DataComponents.CUSTOM_NAME,Component.literal(name).withStyle(s->s.withItalic(false)));
        List<Component> lines=new ArrayList<>();for(String text:lore)for(int i=0;i<text.length();i+=55)lines.add(Component.literal(text.substring(i,Math.min(i+55,text.length()))).withStyle(s->s.withItalic(false).withColor(0xBFC9DE)));
        copy.set(DataComponents.LORE,new ItemLore(lines));return copy;
    }
    private void footer(Page back){button(45,Items.ARROW,"Back",c->navigate(back));button(46,Items.ARROW,"Previous page",c->offset=Math.max(0,offset-45));button(47,Items.ARROW,"Next page",c->offset+=45);button(53,Items.BARRIER,"Close",c->owner.closeContainer());}
    private void toggle(int slot,String title,boolean enabled,Runnable change,String... lore){button(slot,enabled?Items.LIME_DYE:Items.GRAY_DYE,title+(enabled?" [ON]":" [OFF]"),c->change.run(),lore);}
    private List<Network.Target> targets(){return hub==null?List.of():Network.targets(owner,hub);}
    private List<Container> storageContainers(){if(bag!=null)return List.of(bag);if(chest!=null)return List.of(chest);return targets().stream().map(Network.Target::inventory).toList();}
    private MainInventory main(){return new MainInventory(owner,bag==null?-1:bag.playerSlot);}
    private void storage(){
        List<Entry> values=new ArrayList<>();List<Network.Target> targets=targets();
        List<Container> containers=bag!=null?List.of(bag):chest!=null?List.of(chest):targets.stream().map(Network.Target::inventory).toList();
        for(Container container:containers)for(int i=0;i<container.getContainerSize();i++){
            ItemStack item=container.getItem(i);if(item.isEmpty()||!Categories.matches(item,input))continue;
            if(favoritesOnly&&!Profiles.of(owner).favorites.contains(Categories.id(item)))continue;
            BlockPos pos=container instanceof StorageEntity be?be.getBlockPos():null;
            if(pos==null&&bag==null)for(Network.Target target:targets)if(target.inventory()==container){pos=target.pos();break;}
            values.add(new Entry(container,i,item.copy(),pos));
        }
        values.sort(Comparator.comparing((Entry e)->!Profiles.of(owner).favorites.contains(Categories.id(e.snapshot))).thenComparing(Entry::snapshot,Categories.order()));
        offset=Math.min(offset,Math.max(0,((values.size()-1)/45)*45));
        for(int i=0;i<45&&offset+i<values.size();i++){
            Entry entry=values.get(offset+i);entries.put(i,entry);
            String source=entry.position==null?"Backpack":entry.position.toShortString();
            display.setItem(i,icon(entry.snapshot,null,List.of("Source: "+source+" / slot "+(entry.slot+1),"Category: "+Categories.of(entry.snapshot),"Left: take stack | Right: take one","Middle: favorite this item type",Profiles.of(owner).favorites.contains(Categories.id(entry.snapshot))?"Favorite":"")));
        }
        button(45,Items.ARROW,"Previous",c->offset=Math.max(0,offset-45));button(46,Items.ARROW,"Next",c->offset+=45);
        button(47,Items.HOPPER,"Preview organize",c->previewSort(),"Groups by category and exact components.","Nothing moves until you confirm the preview.");
        button(48,Items.CHEST,"QuickStack matching",c->depositAll(false),"Only existing identical item/components.","Protected slots are never swept.");
        button(49,Items.DROPPER,"Dump broader matches",c->depositAll(true),"Same item type or designated category.","Does not empty unrelated items into random chests.");
        button(50,Items.IRON_DOOR,"Protected slots",c->navigate(Page.PREFS));
        if(bag!=null)button(51,Content.BACKPACK.get(),"Backpack upgrades",c->navigate(Page.UPGRADES),bag.name(),"Saved capacity: "+bag.getContainerSize());
        else if(hub!=null)button(51,Content.HUB.get().asItem(),hub.ownedBy(owner)?"Base profile / ore feeder":"Base information",c->navigate(hub.ownedBy(owner)?Page.BASE:Page.DIAGNOSTICS),hub.baseName,"Radius: "+hub.radius());
        else button(51,Items.PINK_DYE,"Cycle front symbol",c->{if(chest.ownedBy(owner)){chest.symbol=Config.SYMBOLS.get((Config.SYMBOLS.indexOf(chest.symbol)+1)%Config.SYMBOLS.size());chest.profileChanged();}},"Owner/operator only",chest.symbol);
        toggle(52,"Favorites only",favoritesOnly,()->favoritesOnly=!favoritesOnly);
        button(53,Items.PAPER,"Storage information",c->navigate(Page.DIAGNOSTICS),"Matching stacks: "+values.size(),"Page: "+(offset/45+1),"Click items in your inventory below to deposit.","Type name, @mod or #tag in the search bar.");
    }
    private void take(Entry entry,Click click){
        if(!isStoragePage())return;
        if(click.button==2){Set<String> favorites=Profiles.of(owner).favorites;String id=Categories.id(entry.snapshot);if(!favorites.remove(id)&&favorites.size()<256)favorites.add(id);Profiles.changed(owner);return;}
        if(!Transactions.same(entry.inventory.getItem(entry.slot),entry.snapshot)){AnyaStorage.tell(owner,"That stack changed. The view has been refreshed.");return;}
        MainInventory main=main();List<Network.Target> targets=targets();List<Container> all=new ArrayList<>();all.add(main);all.add(entry.inventory);
        var guard=hub==null?(java.util.function.BooleanSupplier)()->stillValid(owner):Network.guard(owner,hub,targets);
        Transactions.Plan plan=new Transactions.Plan(all,guard);int moved=plan.move(entry.inventory,entry.slot,List.of(main),click.button==1?1:entry.snapshot.getCount());
        String error=moved==0?"Your main inventory is full":plan.commit(owner.getServer());if(!error.isEmpty())AnyaStorage.tell(owner,error);
    }
    private void inventoryClick(int slot,Click click){
        if(slot<0||slot>=36)return;
        if(page==Page.PREFS){Profiles.of(owner).protectedSlots^=1L<<slot;Profiles.changed(owner);return;}
        if(page==Page.UPGRADES){ItemStack s=owner.getInventory().getItem(slot);if(!s.isEmpty()){input=Categories.id(s);AnyaStorage.tell(owner,"Selected filter item: "+input);}return;}
        if(!isStoragePage())return;
        if(!Transactions.same(owner.getInventory().getItem(slot),playerSeen[slot])){AnyaStorage.tell(owner,"Inventory changed; try the refreshed slot.");return;}
        if(bag!=null&&!Config.get().backpacks){AnyaStorage.tell(owner,"Backpacks are disabled. Existing contents can still be withdrawn.");return;}
        MainInventory main=main();if(main.getItem(slot).isEmpty())return;
        List<Network.Target> targets=targets();List<Container> destinations=bag!=null?List.of(bag):chest!=null?List.of(chest):targets.stream().map(Network.Target::inventory).toList();
        List<Container> all=new ArrayList<>(destinations);all.add(main);
        var guard=hub==null?(java.util.function.BooleanSupplier)()->stillValid(owner):Network.guard(owner,hub,targets);
        Transactions.Plan plan=new Transactions.Plan(all,guard);ItemStack item=plan.work(main)[slot];
        if(hub!=null)destinations=Network.destinations(plan,targets,hub,item,Network.Mode.MANUAL);
        int moved=plan.move(main,slot,destinations,click.button==1?1:item.getCount());String error=moved==0?"No capacity or the destination filter rejects this item":plan.commit(owner.getServer());if(!error.isEmpty())AnyaStorage.tell(owner,error);
    }
    private void depositAll(boolean dump){
        if(!Config.get().quickStack){AnyaStorage.tell(owner,"QuickStack is disabled.");return;}
        if(bag!=null&&!Config.get().backpacks)return;
        MainInventory main=main();List<Network.Target> targets=targets();List<Container> storage=storageContainers(),all=new ArrayList<>(storage);all.add(main);
        var guard=hub==null?(java.util.function.BooleanSupplier)()->stillValid(owner):Network.guard(owner,hub,targets);
        Transactions.Plan plan=new Transactions.Plan(all,guard);int moved=0,locked=0,unmatched=0,full=0;
        for(int i=0;i<36;i++){
            ItemStack stack=plan.work(main)[i];if(stack.isEmpty())continue;if(Profiles.of(owner).protectedSlot(i)){locked+=stack.getCount();continue;}
            List<Container> destinations;
            if(hub!=null)destinations=Network.destinations(plan,targets,hub,stack,dump?Network.Mode.DUMP:Network.Mode.QUICK);
            else {destinations=new ArrayList<>();for(Container c:storage){boolean matching=plan.contains(c,stack);if(dump)for(ItemStack old:plan.work(c))if(!old.isEmpty()&&old.is(stack.getItem()))matching=true;if(matching)destinations.add(c);}}
            if(destinations.isEmpty()){unmatched+=stack.getCount();continue;}moved+=plan.move(main,i,destinations,stack.getCount());full+=plan.work(main)[i].getCount();
        }
        String error=moved>0?plan.commit(owner.getServer()):"";String report=error.isEmpty()?"Moved "+moved+"; protected "+locked+"; no matching destination "+unmatched+"; full/filtered "+full:error;
        Profiles.of(owner).lastReport=report;AnyaStorage.tell(owner,report);
    }
    private void previewSort(){
        if(!Config.get().organize){AnyaStorage.tell(owner,"Organization is disabled.");return;}
        pendingContainers=storageContainers();if(hub!=null)pending=Network.organize(owner,hub,targets());
        else {
            pending=new Transactions.Plan(pendingContainers,()->stillValid(owner));List<ItemStack> stacks=new ArrayList<>();for(Container c:pendingContainers){for(ItemStack s:pending.work(c))if(!s.isEmpty())stacks.add(s.copy());Arrays.fill(pending.work(c),ItemStack.EMPTY);}
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
        button(45,Items.ARROW,"Cancel preview",c->{page=returnPage;pending=null;offset=0;});button(46,Items.ARROW,"Previous",c->offset=Math.max(0,offset-45));button(47,Items.ARROW,"Next",c->offset+=45);
        button(53,Items.EMERALD,"Confirm "+pending.changedSlots()+" slot changes",c->{if(!Config.get().organize)return;String error=pending.commit(owner.getServer());AnyaStorage.tell(owner,error.isEmpty()?"Organization applied.":error);pending=null;page=returnPage;offset=0;},"Every source and destination is rechecked.","Stale snapshots or permissions cancel the operation.");
    }
    private void admin(){
        String[] flags={"compatibility","fallbackMissingTiers","backpacks","network","quickStack","organize","labels","audit","vanillaChests","vanillaBarrels","vanillaShulkers"};
        for(int i=0;i<flags.length;i++){String name=flags[i];try{Field f=Config.Data.class.getField(name);toggle(i,name,f.getBoolean(draft),()->{try{f.setBoolean(draft,!f.getBoolean(draft));}catch(IllegalAccessException e){throw new IllegalStateException(e);}},"Private draft. Review and apply to save.");}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
        button(19,Content.BACKPACK.get(),"Backpack tiers / compatibility doctor",c->navigate(Page.TIERS));
        button(21,Items.COMPASS,"Ranges and performance budgets",c->navigate(Page.LIMITS));
        button(23,Items.RAW_GOLD,"Ore feeder values",c->navigate(Page.ORES));
        button(25,Items.CRAFTING_TABLE,"Recipe gates / endgame choice",c->navigate(Page.RECIPES));
        button(28,Items.NETHER_STAR,"Individual upgrade switches",c->navigate(Page.UPGRADE_GATES));
        button(30,Items.WRITABLE_BOOK,"Operator presets",c->navigate(Page.PRESETS));
        button(32,Items.PAPER,"Diagnostics",c->navigate(Page.DIAGNOSTICS));
        button(34,Items.IRON_DOOR,"My protected slots",c->navigate(Page.PREFS));
        button(45,Items.BARRIER,"Discard and close",c->owner.closeContainer());
        button(53,Items.EMERALD,"Review changes",c->{Config.validate(draft);navigate(Page.CONFIG_CONFIRM);},"Draft revision: "+draft.revision,"Saved revision: "+Config.get().revision,"Saving reloads recipes. Existing items stay registered.");
    }
    private void limits(){
        String[] names={"initialRadius","maxRadius","radiusStep","quickRadius","scanChunksPerTick","maxContainers","maxScanEntries","maxOperationSlots","upgradeBaseCost"};
        int[] steps={1,8,1,1,1,16,32,512,16};
        for(int i=0;i<names.length;i++){String name=names[i];int step=steps[i];try{Field f=Config.Data.class.getField(name);int value=f.getInt(draft);button(i,Items.COMPASS,name+": "+value,c->{try{int next=c.shift?Integer.parseInt(input):f.getInt(draft)+(c.button==1?-step:step);f.setInt(draft,next);}catch(ReflectiveOperationException|NumberFormatException e){throw new IllegalArgumentException("Enter a whole number, then shift-click the setting.");}},"Left increases; right decreases.","Shift-click uses the number in the input bar.","Unsafe values are rejected before saving.");}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
        footer(Page.ADMIN);
    }
    private void tiers(){
        List<Config.Tier> tiers=draft.tiers;offset=Math.min(offset,Math.max(0,((tiers.size()-1)/36)*36));
        for(int i=0;i<36&&offset+i<tiers.size();i++){Config.Tier t=tiers.get(offset+i);String reason=Config.unavailable(draft,t);button(i,t.id.equals("starter")?Content.BACKPACK.get():material(t.material),t.name+(reason.isEmpty()?" [READY]":" [BLOCKED]"),c->{selectedTier=t.id;navigate(Page.TIER);},"ID: "+t.id,"Predecessor: "+t.predecessor,"Material: "+t.material,"Slots: "+t.slots+"; upgrade slots: "+t.upgrades,reason.isEmpty()?"Recipe enabled by this draft":reason);}
        SortedSet<String> found=new TreeSet<>();for(ResourceLocation id:BuiltInRegistries.ITEM.keySet()){String p=id.getPath();if((p.contains("dragonsteel")||p.contains("witherite"))&&p.contains("ingot")&&draft.tiers.stream().noneMatch(t->t.material.equals(id.toString())))found.add(id.toString());}
        int slot=36;for(String id:found){if(slot>44)break;button(slot++,material(id),"Detected: "+id,c->addTier(id),"Create a disabled tier using this exact server item.","Material names do not establish progression balance.");}
        footer(Page.ADMIN);button(51,Items.HOPPER,"Add held material tier",c->{ItemStack held=owner.getMainHandItem();if(held.isEmpty()||held.is(Content.BACKPACK.get()))throw new IllegalArgumentException("Hold the actual ingredient before opening settings.");addTier(Categories.id(held));},"Uses the exact installed item ID.","New tiers start OFF. Set predecessor and review first.");
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
        toggle(10,"Enable "+t.name,t.enabled,()->{if(!t.enabled&&!t.id.equals("starter")&&!Config.installed(t.material))throw new IllegalArgumentException("Material is missing");t.enabled=!t.enabled;});
        button(11,Items.CHEST,"Capacity: "+t.slots,c->t.slots=number(c,t.slots,9),"Multiple of 9; maximum 216.","Existing bags never lose allocated capacity.");
        button(12,Items.NETHER_STAR,"Upgrade slots: "+t.upgrades,c->t.upgrades=number(c,t.upgrades,1),"Range 0..7; cannot be below predecessor.");
        if(!t.id.equals("starter")){
            button(13,Items.IRON_INGOT,"Recipe cells: "+t.cost,c->t.cost=number(c,t.cost,1),"One predecessor backpack plus 1..8 ingredient cells.","Each occupied material cell consumes one item.");
            button(14,Items.ARROW,"Predecessor: "+t.predecessor,c->{List<String> ids=draft.tiers.stream().map(x->x.id).filter(id->!id.equals(t.id)).toList();int index=ids.indexOf(t.predecessor);t.predecessor=ids.get(Math.floorMod(index+(c.button==1?-1:1),ids.size()));},"Click to cycle. Cycles and shrinking upgrades are rejected.");
            button(15,material(t.material),"Material: "+t.material,c->{ItemStack held=owner.getMainHandItem();if(held.isEmpty()||held.is(Content.BACKPACK.get()))throw new IllegalArgumentException("Hold a non-backpack ingredient.");t.material=Categories.id(held);},"Click to use the held item's exact registry ID.");
        }
        button(16,Items.NAME_TAG,"Name: "+t.name,c->{if(input.isBlank()||input.length()>48)throw new IllegalArgumentException("Enter a name up to 48 characters.");t.name=input;},"Enter a name in the input bar, then click.");
        String reason=Config.unavailable(draft,t);
        button(22,Items.CRAFTING_TABLE,"Recipe preview",null,"Effective predecessor: "+(t.id.equals("starter")?"starter recipe":Config.predecessor(draft,t)),"Ingredient: "+t.material,"Occupied ingredient cells: "+t.cost,"Result retains contents, identity and installed upgrades.","Result minimum: "+t.slots+" slots / "+t.upgrades+" upgrades");
        button(24,reason.isEmpty()?Items.LIME_DYE:Items.BARRIER,"Why is this unavailable?",null,reason.isEmpty()?"This draft enables the recipe.":reason,"Compatibility master: "+draft.compatibility,"Save the draft before crafting. Nothing generates ingots.");
        footer(Page.TIERS);
    }
    private int number(Click c,int old,int step){try{return c.shift?Integer.parseInt(input):old+(c.button==1?-step:step);}catch(NumberFormatException e){throw new IllegalArgumentException("Enter a whole number, then shift-click.");}}
    private void ores(){
        List<String> ids=new ArrayList<>(draft.oreValues.keySet());Collections.sort(ids);offset=Math.min(offset,Math.max(0,((ids.size()-1)/45)*45));
        for(int i=0;i<45&&offset+i<ids.size();i++){String id=ids.get(offset+i);button(i,material(id),id+": "+draft.oreValues.get(id)+" points",c->draft.oreValues.put(id,number(c,draft.oreValues.get(id),1)),"Left +1, right -1; shift-click uses typed number.","Accepted values are 1..100000.");}
        footer(Page.ADMIN);button(51,Items.HOPPER,"Add held ore/resource",c->{ItemStack held=owner.getMainHandItem();if(held.isEmpty())throw new IllegalArgumentException("Hold the resource first.");draft.oreValues.putIfAbsent(Categories.id(held),1);},"The actual held registry ID is used.");
    }
    private void recipes(){
        for(int i=0;i<Config.GROUPS.size();i++){String id=Config.GROUPS.get(i);toggle(10+i,id,draft.recipes.get(id),()->draft.recipes.put(id,!draft.recipes.get(id)),"Disabling crafting never unregisters existing items.");}
        String[] options={"OFF / undecided","Dragon's Breath route","Tracked dragon victory-token route"};
        button(22,Content.CUBE.get().asItem(),"Cube recipe: "+options[draft.cubeRecipe],c->draft.cubeRecipe=(draft.cubeRecipe+1)%3,"Choose explicitly; the default is OFF.","Both active routes: 1 storage core + 4 Nether Stars.","Breath route also needs 4 Dragon's Breath.","Token route also needs 4 tracked fight victory tokens.","Breath does NOT prove repeated dragon kills.","Also enable the endgame recipe gate above.");footer(Page.ADMIN);
    }
    private void upgradeGates(){for(int i=0;i<Config.UPGRADE_IDS.size();i++){String id=Config.UPGRADE_IDS.get(i);toggle(10+i,id,draft.upgrades.get(id),()->draft.upgrades.put(id,!draft.upgrades.get(id)),"Controls this upgrade's behavior and crafting recipe.","Installed upgrades are retained when disabled.");}footer(Page.ADMIN);}
    private void presets(){
        button(11,Items.OAK_SAPLING,"Conservative survival",c->{draft.initialRadius=16;draft.maxRadius=48;draft.quickRadius=6;draft.maxContainers=64;draft.scanChunksPerTick=1;},"16 -> 48 range, 6-block nearby QuickStack.","64 containers and 1 scanned chunk per tick.","Only edits the draft. Existing content is untouched.");
        button(15,Items.DIAMOND,"Larger beta-test network",c->{draft.initialRadius=16;draft.maxRadius=96;draft.quickRadius=12;draft.maxContainers=256;draft.scanChunksPerTick=4;},"16 -> 96 range, 12-block nearby QuickStack.","256 containers and 4 scanned chunks per tick.","Safety checks remain mandatory.");footer(Page.ADMIN);
    }
    private void configConfirm(){
        List<String> changes=Config.changes(Config.get(),draft);for(int i=0;i<Math.min(36,changes.size());i++)button(i,Items.PAPER,changes.get(i),null);
        button(40,Items.COMPASS,"Impact preview",null,Network.impact(draft),"Existing containers, contents and occupied bags are not deleted.");
        button(45,Items.ARROW,"Back to draft",c->navigate(Page.ADMIN));button(53,Items.EMERALD_BLOCK,"Apply server settings",c->{if(Config.save(owner,draft))owner.closeContainer();},"Operator permission and revision are rechecked.","Previous config is backed up. Recipes reload on the server.");
    }
    private void prefs(){
        for(int i=0;i<36;i++){int slot=i;ItemStack item=owner.getInventory().getItem(slot);if(item.isEmpty())item=new ItemStack(Items.GRAY_STAINED_GLASS_PANE);boolean locked=Profiles.of(owner).protectedSlot(slot);display.setItem(i,icon(item,"Slot "+(slot+1)+(locked?" [PROTECTED]":" [SWEEPABLE]"),List.of("Click to toggle automatic deposit protection.",slot<9?"Hotbar slot":"Main inventory slot")));buttons.put(i,c->{Profiles.of(owner).protectedSlots^=1L<<slot;Profiles.changed(owner);});}
        button(45,Items.ARROW,"Back",c->navigate(bag!=null?Page.BAG:hub!=null?Page.NETWORK:chest!=null?Page.CHEST:owner.hasPermissions(2)?Page.ADMIN:Page.PREFS));
        button(48,Items.IRON_DOOR,"Protect hotbar",c->{Profiles.of(owner).protectedSlots|=511;Profiles.changed(owner);});
        button(50,Items.PAPER,"Explain skipped items",null,Profiles.of(owner).lastReport,"Armor and offhand are always outside automatic sweeps.");button(53,Items.BARRIER,"Close",c->owner.closeContainer());
    }
    private void base(){
        if(hub==null||!hub.ownedBy(owner))return;
        button(10,Items.NAME_TAG,"Base: "+hub.baseName,c->{if(input.isBlank()||input.length()>48)throw new IllegalArgumentException("Enter a base name up to 48 characters.");hub.baseName=input;hub.profileChanged();},"Enter a name in the input bar, then click.");
        button(12,Items.PLAYER_HEAD,"Trust an online player",c->{ServerPlayer target=owner.getServer().getPlayerList().getPlayerByName(input);if(target==null)throw new IllegalArgumentException("Enter the exact name of an online player.");if(hub.trusted.size()>=64&&!hub.trusted.contains(target.getUUID()))throw new IllegalArgumentException("Trust limit reached.");if(!hub.trusted.remove(target.getUUID()))hub.trusted.add(target.getUUID());hub.profileChanged();},"Enter an online name, then click to add/remove.","Trusted UUID count: "+hub.trusted.size(),"Claim permissions are still required.");
        button(14,Items.LAVA_BUCKET,"Feed ore/resource from inventory",null,"Click an inventory slot while in this base menu.","Left feeds a stack; right feeds one.","Only resources with configured point values are accepted.");
        button(16,Items.ENDER_PEARL,"Radius: "+hub.radius()+" -> "+hub.nextRadius(),c->{if(hub.radius()>=Config.get().maxRadius)throw new IllegalArgumentException("Maximum radius reached.");if(hub.oreProgress<hub.upgradeCost())throw new IllegalArgumentException("Not enough ore progress.");int cost=hub.upgradeCost();hub.oreProgress-=cost;hub.rangeUpgrades++;hub.profileChanged();AnyaStorage.tell(owner,"Spent "+cost+" points. Radius is now "+hub.radius()+".");},"Stored ore points: "+hub.oreProgress,"Next upgrade costs: "+hub.upgradeCost(),"Click explicitly to spend points and upgrade.","One radius controls search, terminal access and QuickStack.");
        button(28,Items.HOPPER,"Container routing",null,"Bind a remote to this hub.","Sneak-use the remote on a supported chest.","Set category, exact-item filters, priority and overflow there.");
        button(31,Items.CLOCK,"Performance",null,Network.performance(hub));button(34,Items.PAPER,"Trusted UUIDs",null,hub.trusted.toString());
        footer(Page.NETWORK);
    }
    private void routing(){
        if(hub==null||routePos==null)return;StorageEntity.Route route=hub.routes.computeIfAbsent(routePos.asLong(),k->new StorageEntity.Route());
        button(10,Items.CHEST,"Container "+routePos.toShortString(),null,"Routing changes apply to this base only.");
        button(12,Items.BOOK,"Category: "+route.category,c->{route.category=Config.CATEGORIES.get(Math.floorMod(Config.CATEGORIES.indexOf(route.category)+(c.button==1?-1:1),Config.CATEGORIES.size()));hub.profileChanged();},"Click to cycle built-in categories.");
        button(14,Items.COMPARATOR,"Priority: "+route.priority,c->{route.priority=Math.floorMod(route.priority+(c.button==1?-1:1),10);hub.profileChanged();},"Higher priorities are filled first.");
        toggle(16,"Allow overflow",route.overflow,()->{route.overflow=!route.overflow;hub.profileChanged();},"Only matching filters/categories are accepted.","A generic overflow chest makes organization more flexible.");
        button(28,Items.HOPPER,"Toggle exact-item filter from input",c->{if(!Config.installed(input))throw new IllegalArgumentException("Enter an installed item ID, such as minecraft:diamond.");if(!route.items.remove(input)){if(route.items.size()>=64)throw new IllegalArgumentException("Filter limit reached.");route.items.add(input);}hub.profileChanged();},"Selected input: "+input,"An empty item list means category-only filtering.",route.items.toString());
        button(30,Items.WATER_BUCKET,"Clear item filters",c->{route.items.clear();hub.profileChanged();});
        button(32,Items.PAPER,"Routing order",null,"Designated category / exact-item filters first.","Then existing identical stacks; then allowed overflow.","Ties use stable block-position ordering.");footer(Page.NETWORK);
    }
    private void upgrades(){
        if(bag==null)return;
        for(int i=0;i<Config.UPGRADE_IDS.size();i++){String id=Config.UPGRADE_IDS.get(i);boolean installed=Upgrades.installed(bag,id);button(10+i,Content.UPGRADES.get(id).get(),id+(installed?" [INSTALLED]":" [EMPTY]"),c->{boolean remove=c.button==1;if(Upgrades.change(bag,id,remove)){int slot=bag.playerSlot;StoreMenu.bag(owner,new BagInventory(owner,slot),true);}},"Left: install from your inventory.","Right: uninstall and return the exact upgrade item.","Server enabled: "+Config.get().upgrades.get(id));}
        button(21,Items.PAPER,"Installed upgrade slots",null,Upgrades.installedItems(bag.stack).size()+" / "+BagInventory.data(bag.stack).getInt("UpgradeSlots"),"Automatic upgrades use the first upgraded backpack.","Automation pauses while a container screen is open.");
        button(23,Items.HOPPER,"Pickup filter",null,"Auto-stores items after normal vanilla pickup.","Empty filter: only existing identical stacks.",BagInventory.data(bag.stack).getList("PickupFilter",Tag.TAG_STRING).toString());
        button(25,Items.FURNACE,"Smelting filter / fuel",null,"No filter means no automatic smelting.","Fuel: ordinary coal/charcoal stored inside the bag.","Fuel ticks: "+BagInventory.data(bag.stack).getInt("FuelTicks"),BagInventory.data(bag.stack).getList("SmeltFilter",Tag.TAG_STRING).toString());
        button(30,Items.GOLDEN_CARROT,"Feeding behavior",null,"Only ordinary safe vanilla food is eaten.","Named/component-modified food is excluded.","Refill tops up existing hotbar stacks without sweeping them.");
        button(45,Items.ARROW,"Back to backpack",c->navigate(Page.BAG));button(46,Items.CRAFTING_TABLE,"Portable crafting",c->Upgrades.craft(bag));
        button(47,Items.IRON_BLOCK,"Compact one safe recipe",c->AnyaStorage.tell(owner,Upgrades.compact(bag)));button(48,Items.IRON_PICKAXE,"Supply a tool to an empty selected hotbar slot",c->Upgrades.resupply(bag));
        button(49,Items.HOPPER,"Toggle pickup filter: "+input,c->Upgrades.filter(bag,"PickupFilter",input),"Type an item ID or click an inventory item below.");
        button(50,Items.FURNACE,"Toggle smelt filter: "+input,c->Upgrades.filter(bag,"SmeltFilter",input));
        button(51,Items.WATER_BUCKET,"Clear both filters / stop smelting",c->{CompoundTag tag=BagInventory.data(bag.stack);tag.remove("PickupFilter");tag.remove("SmeltFilter");BagInventory.data(bag.stack,tag);owner.getInventory().setChanged();});
        button(53,Items.BARRIER,"Close",c->owner.closeContainer());
    }
    private void diagnostics(){
        button(10,Items.PAPER,"Configuration",null,Config.lastStatus,"Revision: "+Config.get().revision,"Settings: /anya storage or /as");
        button(12,Items.IRON_DOOR,"Permissions",null,Safety.status(),"No unloaded chunks are force-loaded.","Only explicitly supported physical inventories are scanned.");
        button(14,Items.HOPPER,"Why were items skipped?",null,Profiles.of(owner).lastReport);
        button(16,Items.CLOCK,"Performance",null,hub==null?"Last transfer: "+Network.lastOperationMicros+" us":Network.performance(hub));
        button(28,Items.BOOK,"Beta safety scope",null,"Server-thread snapshots and component conservation checks.","Permissions, range and inventory identity are rechecked.","Hard-crash durability across vanilla save files is not guaranteed.","Back up your test world; do not remove your existing mods yet.");
        footer(bag!=null?Page.BAG:hub!=null?Page.NETWORK:chest!=null?Page.CHEST:owner.hasPermissions(2)?Page.ADMIN:Page.PREFS);
    }
}
