package dev.anya.storage;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.*;
import java.util.*;

final class PlayerViews {
    private PlayerViews(){}
    static void render(StorageMenu m){
        switch(m.mode){
            case HOME -> home(m);
            case CONTENTS -> contents(m);
            case PREFS -> preferences(m);
            case FAVORITES -> favorites(m);
            case WHY -> why(m);
            case UPGRADES -> upgrades(m);
            case AUTO -> automation(m);
            case FILTERS -> filters(m);
            case PROFILE -> profile(m);
            case DESTINATIONS -> destinations(m);
            case FEED -> feed(m);
            case FUEL_REVIEW -> fuelReview(m);
            case RANGE_REVIEW -> rangeReview(m);
            case SORT_REVIEW -> sortReview(m);
            case METRICS -> metrics(m);
            default -> { }
        }
    }
    private static void home(StorageMenu m){
        m.button(10,Items.IRON_BARS,"Protect inventory slots",(b,t)->m.go(StorageMenu.Mode.PREFS),"Hotbar slots are protected by default.","Armor and offhand are always excluded from QuickStack.");
        m.button(12,Items.GOLD_NUGGET,"Favorite items",(b,t)->m.go(StorageMenu.Mode.FAVORITES),"Right-click an item in a storage list to favorite it.","Search * to show only favorites.");
        m.button(14,Items.PAPER,"Why were items skipped?",(b,t)->m.go(StorageMenu.Mode.WHY),"Inspect the most recent QuickStack or category dump.");
        m.button(16,Items.REDSTONE,"Personal automation",(b,t)->m.go(StorageMenu.Mode.AUTO),"These switches apply to your carried backpacks.");
        m.button(28,Items.BOOK,"Recipe guide / Why unavailable?",(b,t)->m.go(StorageMenu.Mode.RECIPES),"Live recipes, exact materials and disabled reasons.","The guide is available to every player.");
        m.button(30,Items.COMPASS,"Nearby base profile",(b,t)->{
            if(m.bag!=null)throw new IllegalArgumentException("Close the backpack before selecting another base.");
            StorageBlockEntity hub=StorageOps.nearestHub(m.owner);
            if(hub==null)throw new IllegalArgumentException("No permitted, loaded base hub is in range.");
            if(m.block!=null&&m.block.kind()==1)m.block.viewing(-1);
            m.block=hub;m.go(StorageMenu.Mode.PROFILE);
        },"Names, trusted players, category destinations and radius.");
        m.button(32,Items.CLOCK,"Performance and permissions",(b,t)->m.go(StorageMenu.Mode.METRICS));
        if(m.admin())m.button(34,Items.COMMAND_BLOCK,"Operator configuration",(b,t)->{if(m.draft==null)m.draft=Rules.current.copy();m.go(StorageMenu.Mode.ADMIN);},"Private draft. Changes require an impact review and Apply.");
        m.button(40,Items.CHEST,"Storage access",null,"Right-click a backpack or storage block to open it.","Sneak-right-click a hub with the storage button to bind it.","Use the bound button within that hub's upgraded radius.");
    }
    private static void contents(StorageMenu m){
        List<StorageOps.Ref> refs=m.refs();
        List<Container> inventories=refs.stream().map(StorageOps.Ref::inventory).toList();
        List<StorageOps.Entry> entries=StorageOps.entries(inventories,m.input,m.prefs().favorites);
        int start=m.offset(entries.size(),45);
        for(int i=start;i<Math.min(entries.size(),start+45);i++){
            StorageOps.Entry e=entries.get(i);ItemStack icon=e.icon().copy();
            m.button(i-start,icon,e.icon().getHoverName().getString()+" x"+e.count(),(b,t)->{if(b==1)m.favorite(e.icon());else m.extract(e.icon(),t==ClickType.QUICK_MOVE?1:e.icon().getMaxStackSize());},List.of(StorageOps.itemId(icon),"Category: "+StorageOps.category(icon),"Left: withdraw a stack. Shift-left: one item.","Right: toggle favorite. Inventory click: deposit.",m.prefs().favorites.contains(StorageOps.itemId(icon))?"Favorite":"Not favorited"));
        }
        if(entries.isEmpty())m.button(22,Items.PAPER,"No matching stored items",null,"The search may be empty, the index may be warming up,","or containers may be unavailable because of permissions.");
        m.button(48,Items.HOPPER,"Preview organization",(b,t)->m.previewSort(),"No items move until you review and confirm.","Preview expires after 10 seconds.");
        if(m.bag!=null)m.button(51,AnyaStorage.CORE.get(),"Backpack upgrades",(b,t)->m.go(StorageMenu.Mode.UPGRADES));
        else if(m.block!=null)m.button(51,Items.NAME_TAG,"Storage profile",(b,t)->m.go(StorageMenu.Mode.PROFILE));
        if(m.block!=null&&m.block.kind()==0)m.button(53,Items.CHEST,"QuickStack / category dump",(b,t)->StorageOps.quickStack(m.owner,b==1),"Left: matching items only.","Right: matching items and matching-category destinations.","Protected inventory slots are never removed.");
    }
    private static void preferences(StorageMenu m){
        for(int i=0;i<36;i++){
            final int slot=i;ItemStack actual=m.owner.getInventory().getItem(i);
            ItemStack icon=actual.isEmpty()?new ItemStack(m.prefs().locked.get(i)?Items.RED_STAINED_GLASS_PANE:Items.LIME_STAINED_GLASS_PANE):actual;
            m.button(i,icon,(m.prefs().locked.get(i)?"PROTECTED ":"AVAILABLE ")+"slot "+(i+1),(b,t)->{m.prefs().locked.flip(slot);m.data().setDirty();},List.of(i<9?"Hotbar":"Main inventory","Click to toggle. This changes no item stack.","Used by QuickStack, dump and pickup/auto-stow.","Direct manual deposit and refill are separate actions."));
        }
        m.button(38,Items.SHIELD,"Protect hotbar",(b,t)->{m.prefs().locked.set(0,9);m.data().setDirty();});
        m.button(40,Items.IRON_BARS,"Protect all slots",(b,t)->{m.prefs().locked.set(0,36);m.data().setDirty();});
        m.button(42,Items.LIME_DYE,"Allow main inventory",(b,t)->{m.prefs().locked.clear(9,36);m.data().setDirty();});
    }
    private static void favorites(StorageMenu m){
        List<String> ids=new ArrayList<>(m.prefs().favorites);int start=m.offset(ids.size(),45);
        for(int i=start;i<Math.min(ids.size(),start+45);i++){String id=ids.get(i);m.button(i-start,item(id),id,(b,t)->{m.prefs().favorites.remove(id);m.data().setDirty();},"Click to remove this favorite.");}
        if(ids.isEmpty())m.button(22,Items.GOLD_NUGGET,"No favorites yet",null,"Right-click an item in the storage contents list.");
    }
    private static void why(StorageMenu m){
        List<Map.Entry<Integer,String>> rows=new ArrayList<>(m.prefs().skipped.entrySet());int start=m.offset(rows.size(),45);
        for(int i=start;i<Math.min(rows.size(),start+45);i++){var row=rows.get(i);m.button(i-start,Items.PAPER,"Slot "+(row.getKey()+1),null,row.getValue());}
        if(rows.isEmpty())m.button(22,Items.PAPER,"No skipped slots recorded",null,"Use the QuickStack or category dump shortcut first.","This diagnostic is for the most recent action in this session.");
    }
    private static void automation(StorageMenu m){
        StorageData.Prefs p=m.prefs();
        m.toggle(10,"Auto-stow after pickup",p.autoStow,()->{p.autoStow=!p.autoStow;m.data().setDirty();},"Requires a pickup upgrade. Uses filters or existing bag items.");
        m.toggle(12,"Safe-food feeding",p.autoFeed,()->{p.autoFeed=!p.autoFeed;m.data().setDirty();},"Requires feeding upgrade; plain whitelisted foods only.");
        m.toggle(14,"Refill existing hotbar stacks",p.autoRefill,()->{p.autoRefill=!p.autoRefill;m.data().setDirty();},"Never replaces an empty hotbar slot or a tool.");
        m.toggle(16,"Vanilla material compacting",p.autoCompact,()->{p.autoCompact=!p.autoCompact;m.data().setDirty();},"Requires compacting upgrade. Explicit 9-to-1 materials only.");
        m.button(31,Items.BOOK,"Automation limits",null,"Runs once per second while no container is open.","No ground-item magnet, voiding, remote chunk loading,","recursive mod crafting, or custom-food effect guessing.");
    }
    static void install(StorageMenu m,int source){
        if(m.bag==null||!Rules.current.upgrades)throw new IllegalArgumentException("Upgrade installation is disabled.");
        ItemStack stack=m.owner.getInventory().getItem(source);String type=StorageUpgrades.upgrade(stack);
        if(type==null)throw new IllegalArgumentException("Click an Anya Storage upgrade in your inventory.");
        if(!Boolean.TRUE.equals(Rules.current.upgradeTypes.get(type)))throw new IllegalArgumentException("This upgrade type is disabled by the server.");
        Rules.Tier tier=Rules.current.tier(m.bag.tier);if(tier==null)throw new IllegalArgumentException("This backpack tier is unavailable.");
        for(int i=0;i<m.bag.upgrades.getContainerSize();i++)if(m.bag.upgrades.getItem(i).is(stack.getItem()))throw new IllegalArgumentException("This upgrade is already installed.");
        int target=-1;for(int i=0;i<tier.upgrades;i++)if(m.bag.upgrades.getItem(i).isEmpty()){target=i;break;}
        if(target<0)throw new IllegalArgumentException("No free upgrade slot in this tier.");
        StorageOps.Plan plan=new StorageOps.Plan().guard(()->m.stillValid(m.owner));
        ItemStack[] from=plan.state(m.owner.getInventory()),to=plan.state(m.bag.upgrades);
        to[target]=from[source].copyWithCount(1);from[source].shrink(1);
        if(!plan.commit())throw new IllegalArgumentException("Inventory changed before the upgrade was installed.");
    }
    private static void upgrades(StorageMenu m){
        if(m.bag==null){m.go(StorageMenu.Mode.HOME);return;}
        Rules.Tier tier=Rules.current.tier(m.bag.tier);int capacity=tier==null?0:tier.upgrades;
        for(int i=0;i<8;i++){
            final int slot=i;ItemStack s=m.bag.upgrades.getItem(i);
            if(s.isEmpty()){m.button(i,Items.GRAY_STAINED_GLASS_PANE,"Upgrade slot "+(i+1)+(i<capacity?" available":" locked"),null,"Click an upgrade item in your inventory to install it.");continue;}
            String id=StorageUpgrades.upgrade(s);
            m.button(i,s,s.getHoverName().getString(),(b,t)->{
                if(b==1&&id!=null){if(!m.bag.disabledUpgrades.remove(id))m.bag.disabledUpgrades.add(id);m.data().setDirty();}
                else {StorageOps.Plan p=new StorageOps.Plan().guard(()->m.stillValid(m.owner));int n=p.move(m.bag.upgrades,slot,m.owner.getInventory(),1);if(n==0||!p.commit())throw new IllegalArgumentException("No inventory room or contents changed.");}
            },List.of("Left: uninstall. Right: toggle this backpack's upgrade.",id!=null&&m.bag.hasUpgrade(id)?"Active":"Inactive or disabled by the server"));
        }
        m.button(20,Items.HOPPER,"Shared automation item filter",(b,t)->m.go(StorageMenu.Mode.FILTERS),"Exact item IDs. Empty pickup filter matches existing bag items.","Empty feeding/refill/compacting filter allows their safe defaults.");
        m.button(24,Items.REDSTONE,"Personal automation switches",(b,t)->m.go(StorageMenu.Mode.AUTO));
        m.button(31,Items.PAPER,"Capacity retained safely",null,"Inventory slots: "+m.bag.inventory.getContainerSize(),"Configured tier upgrade slots: "+capacity,"Existing contents remain accessible when crafting is disabled.");
    }
    private static void filters(StorageMenu m){
        if(m.bag==null)return;List<String> ids=new ArrayList<>(m.bag.filters);int start=m.offset(ids.size(),45);
        for(int i=start;i<Math.min(ids.size(),start+45);i++){String id=ids.get(i);m.button(i-start,item(id),id,(b,t)->{m.bag.filters.remove(id);m.data().setDirty();},"Click to remove. Click your inventory to add an exact item ID.");}
        if(ids.isEmpty())m.button(22,Items.HOPPER,"Empty filter uses safe defaults",null,"Click an inventory item to whitelist its type.","The filter is shared by this backpack's installed upgrades.");
    }
    private static void profile(StorageMenu m){
        if(m.block==null){m.go(StorageMenu.Mode.HOME);return;}StorageBlockEntity b=m.block;
        m.button(4,Items.NAME_TAG,b.name,null,"Owner: "+(b.owner==null?"unassigned":b.owner),"Trusted players: "+b.trusted.size(),"Claim permissions are checked independently.");
        m.button(10,Items.NAME_TAG,"Rename this storage",(button,t)->{manage(m);String name=m.input.strip();if(name.isEmpty()||name.length()>48)throw new IllegalArgumentException("Type a name from 1 to 48 characters first.");b.name=name;b.changed();m.note("Storage renamed to "+name);},"Type the name above, then click.");
        m.button(12,Items.PLAYER_HEAD,"Trust / untrust player",(button,t)->{
            manage(m);String value=m.input.strip();UUID id;
            ServerPlayer online=m.owner.getServer().getPlayerList().getPlayerByName(value);
            if(online!=null)id=online.getUUID();else try{id=UUID.fromString(value);}catch(IllegalArgumentException ex){throw new IllegalArgumentException("Type an online player name or an exact UUID.");}
            if(button==1){b.trusted.remove(id);b.changed();m.note("Removed trusted player "+id);}
            else {if(b.trusted.size()>=64&&!b.trusted.contains(id))throw new IllegalArgumentException("Trusted-player limit reached.");b.trusted.add(id);b.changed();m.note("Trusted player "+id);}
        },"Type an online name or exact UUID.","Left adds trust; right removes it. Does not bypass claims.");
        if(b.kind()==0){
            m.button(14,Items.HOPPER,"Category destinations",(button,t)->{manage(m);m.go(StorageMenu.Mode.DESTINATIONS);});
            m.toggle(16,"Organize overflow into unassigned storage",b.overflow,()->{manage(m);b.overflow=!b.overflow;b.changed();},"When OFF, Organize requires designated or existing-item homes.");
            m.button(28,Items.LAVA_BUCKET,"Ore feeder",(button,t)->{manage(m);m.go(StorageMenu.Mode.FEED);},"Radius: "+b.radius()+" blocks","Progress: "+b.progress+" / "+b.nextCost());
            m.button(30,Items.COMPASS,"Preview next radius upgrade",(button,t)->{manage(m);m.reviewedRadius=b.radius();m.reviewedCost=b.nextCost();m.reviewedRevision=Rules.current.revision;m.go(StorageMenu.Mode.RANGE_REVIEW);});
            m.button(32,Items.CLOCK,"Base diagnostics",(button,t)->m.go(StorageMenu.Mode.METRICS));
            m.button(34,AnyaStorage.REMOTE.get(),"Bind a storage button",null,"Hold a storage button and sneak-right-click this hub.","Access and QuickStack use this same upgraded radius.");
        }else{
            String[] labels={"Heart","Star","Square","Circle","Diamond","Leaf","Gear"};
            m.button(14,Items.PINK_DYE,"Front symbol: "+labels[Math.floorMod(b.label,7)],(button,t)->{manage(m);if(!Rules.current.labels)throw new IllegalArgumentException("Labels are disabled by the server.");b.label=Math.floorMod(b.label+(button==1?-1:1),7);b.changed();},"Left / right cycles the front symbol.",b.kind()==1?"The chest symbol splits across the animated lid and base.":"The UwU cube retains its own face and heart artwork.");
            m.button(31,Items.CHEST,"Physical capacity: "+b.inventory.getContainerSize(),null,"Capacity changes affect newly placed containers.","Existing inventories never shrink automatically.");
        }
        int index=36;for(UUID id:new TreeSet<>(b.trusted)){if(index>=44)break;m.button(index++,Items.PLAYER_HEAD,"Trusted: "+id,null,"Type this UUID into the trust editor to remove it.");}
    }
    static void manage(StorageMenu m){if(m.block==null||!m.managing()||!m.stillValid(m.owner))throw new IllegalArgumentException("Only the owner or an operator can change this storage.");}
    private static void destinations(StorageMenu m){
        if(m.block==null||m.block.kind()!=0)return;
        List<StorageOps.Ref> refs=StorageOps.references(m.owner,m.block);int start=m.offset(refs.size(),45);
        for(int i=start;i<Math.min(refs.size(),start+45);i++){
            StorageOps.Ref ref=refs.get(i);String current=m.block.destinations.getOrDefault(ref.pos().asLong(),"any");
            m.button(i-start,Items.CHEST,ref.pos().toShortString()+": "+current,(button,t)->{manage(m);if(!ref.valid())throw new IllegalArgumentException("Destination is no longer accessible.");int index=Rules.CATEGORIES.indexOf(current);String next=Rules.CATEGORIES.get(Math.floorMod(index+(button==1?-1:1),Rules.CATEGORIES.size()));if(next.equals("any"))m.block.destinations.remove(ref.pos().asLong());else m.block.destinations.put(ref.pos().asLong(),next);m.block.changed();},"Left / right cycles the destination category.","any = unassigned. Designated categories take priority.","Only accessible, loaded containers appear.");
        }
        if(refs.isEmpty())m.button(22,Items.CLOCK,"No accessible destinations yet",null,"The index may still be warming up.","Check allowed container types, claims, locks and range.");
    }
    private static void feed(StorageMenu m){
        if(m.block==null||m.block.kind()!=0)return;StorageBlockEntity b=m.block;
        m.button(4,Items.LAVA_BUCKET,"Ore feeder: "+b.progress+" / "+b.nextCost(),null,"Click a supported material in your inventory to preview feeding.","Click: one. Shift-click: enough to reach the next upgrade.","Nothing is consumed until the second confirmation.");
        List<Map.Entry<String,Integer>> ores=new ArrayList<>(Rules.current.ores.entrySet());int start=m.offset(ores.size(),27);
        for(int i=start;i<Math.min(ores.size(),start+27);i++){var ore=ores.get(i);m.button(9+i-start,item(ore.getKey()),ore.getKey(),null,ore.getValue()+" progress per item");}
        m.button(40,Items.COMPASS,"Radius "+b.radius()+" -> "+b.nextRadius(),(button,t)->{manage(m);m.reviewedRadius=b.radius();m.reviewedCost=b.nextCost();m.reviewedRevision=Rules.current.revision;m.go(StorageMenu.Mode.RANGE_REVIEW);},"Upgrade cost: "+b.nextCost()+" progress");
    }
    static void prepareFuel(StorageMenu m,int slot,boolean bulk){
        manage(m);StorageBlockEntity b=m.block;if(b.kind()!=0)throw new IllegalArgumentException("This is not a storage hub.");
        if(b.radius()>=Rules.current.maxRadius)throw new IllegalArgumentException("The hub is already at the configured radius limit.");
        ItemStack stack=m.owner.getInventory().getItem(slot);Integer value=Rules.current.ores.get(StorageOps.itemId(stack));
        if(stack.isEmpty()||value==null)throw new IllegalArgumentException("This item is not an approved feeder material.");
        if(!stack.getComponentsPatch().isEmpty())throw new IllegalArgumentException("Named or component-modified feeder materials are protected from consumption.");
        int need=Math.max(0,b.nextCost()-b.progress);if(need==0)throw new IllegalArgumentException("Enough progress is stored. Apply the radius upgrade first.");
        int count=bulk?Math.min(stack.getCount(),(need+value-1)/value):1;
        m.fuel=new StorageMenu.Fuel(slot,stack.copy(),count,value,Rules.current.revision);m.go(StorageMenu.Mode.FUEL_REVIEW);
    }
    private static void fuelReview(StorageMenu m){
        if(m.fuel==null||m.block==null)return;StorageMenu.Fuel f=m.fuel;
        m.button(13,f.before(),"Consume "+f.count()+" items",null,List.of("Adds "+((long)f.count()*f.value())+" upgrade progress.","Stored progress: "+m.block.progress,"The whole source stack is rechecked before consumption.","Excess progress is retained for the next radius upgrade."));
        m.button(53,Items.EMERALD,"Confirm feeder deposit",(button,t)->{
            manage(m);if(f.revision()!=Rules.current.revision||!StorageOps.same(f.before(),m.owner.getInventory().getItem(f.slot())))throw new IllegalArgumentException("Material stack or settings changed. Preview the deposit again.");
            int next=Math.addExact(m.block.progress,Math.multiplyExact(f.count(),f.value()));ItemStack old=f.before().copy();int progress=m.block.progress;
            try{ItemStack rest=old.copy();rest.shrink(f.count());m.owner.getInventory().setItem(f.slot(),rest);m.block.progress=next;m.block.changed();}
            catch(RuntimeException ex){m.owner.getInventory().setItem(f.slot(),old);m.block.progress=progress;m.block.changed();throw ex;}
            m.fuel=null;m.go(StorageMenu.Mode.FEED);
        },"This consumes exactly the previewed count once.");
    }
    private static void rangeReview(StorageMenu m){
        if(m.block==null)return;StorageBlockEntity b=m.block;
        m.button(13,Items.COMPASS,"Radius "+m.reviewedRadius+" -> "+Math.min(Rules.current.maxRadius,m.reviewedRadius+Rules.current.radiusStep),null,"Cost: "+m.reviewedCost+" stored progress","Available: "+b.progress,"Storage access, container discovery and base QuickStack","all use the same radius. No chunk force-loading is added.");
        m.button(53,Items.EMERALD,"Confirm radius upgrade",(button,t)->{
            manage(m);if(m.reviewedRevision!=Rules.current.revision||m.reviewedRadius!=b.radius()||m.reviewedCost!=b.nextCost())throw new IllegalArgumentException("The upgrade changed. Open a fresh preview.");
            if(b.nextRadius()<=b.radius())throw new IllegalArgumentException("The hub is already at the maximum radius.");
            if(b.progress<m.reviewedCost)throw new IllegalArgumentException("The feeder needs more progress first.");
            b.progress-=m.reviewedCost;b.earnedRadius=b.nextRadius();b.changed();StorageOps.invalidateAll();m.go(StorageMenu.Mode.FEED);
        });
    }
    private static void sortReview(StorageMenu m){
        if(m.preview==null)return;
        boolean current=m.owner.serverLevel().getGameTime()<=m.previewDeadline&&m.preview.isCurrent();
        m.button(13,Items.HOPPER,"Organization preview",null,"Slots changed: "+m.preview.changes(),"Status: "+(current?"Ready":"Expired or contents/access changed"),"No items have moved. All stack components are preserved.","Preview expires after 10 seconds, or immediately on any change.");
        m.button(53,Items.EMERALD,"Confirm organization",(button,t)->{
            if(!Rules.current.organize||m.owner.serverLevel().getGameTime()>m.previewDeadline||!m.preview.commit())throw new IllegalArgumentException("Preview expired or storage changed. Nothing was committed; preview again.");
            m.preview=null;m.go(StorageMenu.Mode.CONTENTS);
        });
    }
    private static void metrics(StorageMenu m){
        StorageBlockEntity hub=m.block!=null&&m.block.kind()==0?m.block:StorageOps.nearestHub(m.owner);
        List<String> lines=StorageOps.metrics(m.owner,hub);for(int i=0;i<Math.min(40,lines.size());i++)m.button(i,Items.PAPER,lines.get(i),null);
        m.button(42,Items.SHIELD,"Beta safety boundary",null,"Transactions are validated and committed on the server thread.","Abrupt power-loss recovery across Minecraft save files is not","a proven crash-atomic guarantee. Keep tested world backups.","Exact installed-pack and multiplayer testing is still required.");
    }
    static Item item(String id){ResourceLocation location=ResourceLocation.tryParse(id);return location!=null&&BuiltInRegistries.ITEM.containsKey(location)?BuiltInRegistries.ITEM.get(location):Items.BARRIER;}
}
