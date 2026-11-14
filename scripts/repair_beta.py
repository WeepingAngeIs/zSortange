"""Final reviewed beta corrections, committed before testing. This file removes itself after application."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]; pending={}
def edit(path,old,new):
 p=root/path;t=pending.get(p,p.read_text())
 if t.count(old)!=1:raise RuntimeError(f'{path}: expected one match for {old[:70]!r}; found {t.count(old)}')
 pending[p]=t.replace(old,new,1)
def java(name,old,new):edit(f'src/main/java/dev/anya/storage/{name}.java',old,new)
java('StorageEntity','@Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}','''@Override public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
    @Override public void onDataPacket(net.minecraft.network.Connection connection,ClientboundBlockEntityDataPacket packet,HolderLookup.Provider lookup){if(packet.getTag()!=null)handleUpdateTag(packet.getTag(),lookup);}''')
java('Network','    public static String performance(StorageEntity hub){','''    public static String explain(ServerPlayer p,StorageEntity requestedHub,int slot,boolean dump){
        if(slot<0||slot>=36)return "Only main inventory slots participate.";
        ItemStack item=p.getInventory().getItem(slot);if(item.isEmpty())return "That inventory slot is empty.";
        if(!Config.get().quickStack)return "QuickStack and Dump are disabled by the server.";
        if(Profiles.of(p).protectedSlot(slot))return "Slot "+(slot+1)+" is protected from automatic deposits.";
        StorageEntity hub=requestedHub==null?nearest(p):requestedHub;List<Target> targets=targets(p,hub);
        if(targets.isEmpty()){
            if(hub!=null){Index index=INDEX.get(hub);if(index!=null)for(BlockPos pos:index.candidates){
                if(!p.serverLevel().hasChunkAt(pos))return "No accessible targets; an indexed container at "+pos.toShortString()+" is unloaded. No chunk was loaded.";
                BlockEntity be=p.serverLevel().getBlockEntity(pos);String reason=Safety.reason(p,be,hub);if(!reason.isEmpty())return "No accessible targets; container at "+pos.toShortString()+": "+reason;
            }}
            return "No eligible loaded containers in range or within the scan budget. Chest support="+Config.get().vanillaChests+", barrels="+Config.get().vanillaBarrels+", shulkers="+Config.get().vanillaShulkers+". "+Safety.status();
        }
        MainInventory main=new MainInventory(p);List<Container> all=new ArrayList<>();all.add(main);targets.forEach(t->all.add(t.inventory));Transactions.Plan plan=new Transactions.Plan(all,guard(p,hub,targets));
        List<Container> eligible=destinations(plan,targets,hub,item,dump?Mode.DUMP:Mode.QUICK);
        if(eligible.isEmpty()){
            long filtered=targets.stream().filter(t->hub!=null&&!hub.route(t.pos).allows(item)).count();
            return filtered==targets.size()?"All "+filtered+" accessible containers reject this item's category or exact-item filter.":(dump?"No existing item-type match or designated destination.":"No existing exact item-and-component match. Named, enchanted or damaged variants count separately.")+" Filtered targets: "+filtered+".";
        }
        int accepted=plan.move(main,slot,eligible,item.getCount());
        return accepted==0?"Matching destinations are full or their slot filters reject the item.":"Preview only: "+accepted+" of "+item.getCount()+" items fit across "+eligible.size()+" matching destinations. Nothing moved.";
    }
    public static String performance(StorageEntity hub){''')
java('StoreMenu','if(page==Page.PREFS){Profiles.of(owner).protectedSlots^=1L<<slot;Profiles.changed(owner);return;}','if(page==Page.DIAGNOSTICS||(page==Page.PREFS&&click.shift)){explainSlot(slot,click.button==1);return;}\n        if(page==Page.PREFS){Profiles.of(owner).protectedSlots^=1L<<slot;Profiles.changed(owner);return;}')
java('StoreMenu','buttons.put(i,c->{Profiles.of(owner).protectedSlots^=1L<<slot;Profiles.changed(owner);});','buttons.put(i,c->{if(c.shift){explainSlot(slot,c.button==1);return;}Profiles.of(owner).protectedSlots^=1L<<slot;Profiles.changed(owner);});')
java('StoreMenu','"Click to toggle automatic deposit protection."','"Click: protection. Shift-click: explain QuickStack. Shift-right: explain Dump."')
java('StoreMenu','    private void selectOre(int slot,Click click){','''    private void explainSlot(int slot,boolean dump){
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
    private void selectOre(int slot,Click click){''')
java('StoreMenu','button(14,Items.HOPPER,"Why were items skipped?",null,Profiles.of(owner).lastReport);','button(14,Items.HOPPER,"Why were items skipped?",null,Profiles.of(owner).lastReport,"Click an inventory item below for a fresh preview.","Left checks QuickStack; right checks Dump. Nothing moves.");')
# Existing core assertions gain coverage for category-rule validation.
p=root/'src/test/java/dev/anya/storage/CoreTests.java';t=p.read_text();needle='        System.out.println("PASS: "+checks+" configuration assertions");'
if t.count(needle)!=1:raise RuntimeError('Core test marker changed')
pending[p]=t.replace(needle,'''        copy=Config.copy(d); copy.categoryRules.put("#c:ores","ores"); Config.validate(copy);check(true,"tag category rules");
        copy=Config.copy(d); copy.categoryRules.put("minecraft:diamond","materials"); Config.validate(copy);check(true,"item category rules");
        copy=Config.copy(d); copy.categoryRules.put("#invalid","ores");invalid(copy,"category tag syntax");
        copy=Config.copy(d); copy.categoryRules.put("minecraft:diamond","any");invalid(copy,"any is a route wildcard, not a category");
        copy=Config.copy(d); copy.categoryRules.put("minecraft:diamond","invented");invalid(copy,"unknown category");
'''+needle)
for p,t in pending.items():p.write_text(t)
Path(__file__).unlink();print('Applied final reviewed corrections to',len(pending),'files.')
