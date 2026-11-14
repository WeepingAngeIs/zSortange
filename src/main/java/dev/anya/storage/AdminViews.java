package dev.anya.storage;

import net.minecraft.world.item.*;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class AdminViews {
    private AdminViews(){}
    static void render(StorageMenu m){
        if(m.mode!=StorageMenu.Mode.RECIPES&&m.mode!=StorageMenu.Mode.RECIPE){
            if(!m.admin()){m.go(StorageMenu.Mode.HOME);return;}
            if(m.draft==null)m.draft=Rules.current.copy();
        }
        switch(m.mode){
            case ADMIN -> main(m);
            case NUMBERS -> numbers(m);
            case MATERIALS -> materials(m);
            case TIERS -> tiers(m);
            case TIER -> tier(m);
            case ORES -> ores(m);
            case CATEGORIES -> categories(m);
            case RECIPES -> recipes(m);
            case RECIPE -> recipe(m);
            case IMPACT -> impact(m);
            default -> { }
        }
    }
    private static void main(StorageMenu m){
        Rules.Config c=m.draft;
        m.toggle(0,"Backpack deposits and automation",c.backpacks,()->c.backpacks=!c.backpacks,"Existing backpacks retain recovery withdrawals.");
        m.toggle(1,"Base network access",c.network,()->c.network=!c.network,"Physical storage contents are never deleted.");
        m.toggle(2,"QuickStack and category dump",c.quickStack,()->c.quickStack=!c.quickStack);
        m.toggle(3,"Organization",c.organize,()->c.organize=!c.organize);
        m.toggle(4,"Animated chest symbols",c.labels,()->c.labels=!c.labels);
        m.toggle(5,"Backpack upgrades",c.upgrades,()->c.upgrades=!c.upgrades);
        m.toggle(6,"Vanilla chests",c.allowChests,()->c.allowChests=!c.allowChests,"Exact vanilla chest classes only, not arbitrary mod machines.");
        m.toggle(7,"Vanilla barrels",c.allowBarrels,()->c.allowBarrels=!c.allowBarrels);
        m.toggle(8,"Vanilla shulker boxes",c.allowShulkers,()->c.allowShulkers=!c.allowShulkers,"Disabled by default. Nested backpack storage is still forbidden.");
        for(int i=0;i<Rules.GROUPS.size();i++){String group=Rules.GROUPS.get(i);m.toggle(9+i,"Recipe group: "+group,Boolean.TRUE.equals(c.recipes.get(group)),()->c.recipes.put(group,!Boolean.TRUE.equals(c.recipes.get(group))));}
        m.toggle(15,"Optional material compatibility",c.compatibility,()->c.compatibility=!c.compatibility,"Each optional material must also be enabled separately.");
        m.toggle(16,"Configuration audit log",c.audit,()->c.audit=!c.audit);
        m.toggle(17,"Expanded help footer",c.showHelp,()->c.showHelp=!c.showHelp);
        for(int i=0;i<Rules.UPGRADE_IDS.size();i++){String id=Rules.UPGRADE_IDS.get(i);m.toggle(18+i,"Upgrade: "+id,Boolean.TRUE.equals(c.upgradeTypes.get(id)),()->c.upgradeTypes.put(id,!Boolean.TRUE.equals(c.upgradeTypes.get(id))));}
        String[] routes={"Undecided / disabled","4 Nether Stars + 4 Dragon Breath","4 Nether Stars + 4 Dragon Victory Tokens"};
        m.button(22,AnyaStorage.CUBE_ITEM.get(),"Endgame route: "+routes[c.endgameMode],(b,t)->c.endgameMode=Math.floorMod(c.endgameMode+(b==1?-1:1),3),"Both routes also require one Storage Core.","Breath route does NOT enforce repeated dragon kills.","Victory tokens: one per managed End dragon when that route is enabled.","Endgame recipe group must also be ON. Default stays undecided.");
        m.button(27,Items.COMPARATOR,"Ranges, capacities and performance",(b,t)->m.go(StorageMenu.Mode.NUMBERS));
        m.button(28,Items.NETHERITE_INGOT,"Installed material compatibility",(b,t)->m.go(StorageMenu.Mode.MATERIALS));
        m.button(29,AnyaStorage.BACKPACK.get(),"Backpack tier editor",(b,t)->m.go(StorageMenu.Mode.TIERS));
        m.button(30,Items.RAW_IRON,"Feeder material values",(b,t)->m.go(StorageMenu.Mode.ORES));
        m.button(31,Items.HOPPER,"Item category overrides",(b,t)->m.go(StorageMenu.Mode.CATEGORIES));
        m.button(32,Items.BOOK,"Recipe guide and diagnostics",(b,t)->m.go(StorageMenu.Mode.RECIPES));
        m.button(36,Items.SHIELD,"Conservative network preset",(b,t)->m.draft=Rules.preset(false,c),"Changes radius and scan limits in your draft only.","Keeps existing tier definitions and material choices.");
        m.button(38,Items.DIAMOND,"Roomier beta preset",(b,t)->m.draft=Rules.preset(true,c),"Larger radius, +18 tier slots, no automatic compatibility.","Review exact changes before applying.");
        m.button(40,Items.PAPER,"Draft revision "+c.revision,null,"Live revision: "+Rules.current.revision,"Other operators have independent drafts.","A stale revision cannot overwrite a newer save.");
        m.button(48,Items.BARRIER,"Discard draft / reload live settings",(b,t)->{m.draft=Rules.current.copy();m.go(StorageMenu.Mode.ADMIN);},"This discards only your unsaved edits.");
    }
    private static void numbers(StorageMenu m){
        String[][] fields={
            {"baseRadius","Base radius","4 to 128 blocks","4"},{"radiusStep","Radius increment","1 to 32 blocks","1"},
            {"maxRadius","Maximum radius","Base radius to 128 blocks","8"},{"radiusCost","Base feeder upgrade cost","1 to 100000 progress","8"},
            {"outsideRadius","Outside-base QuickStack radius","1 to 16 blocks","1"},{"scanBudget","Global scan budget","1 to 16 chunks per server tick","1"},
            {"scanInterval","Index refresh interval","20 to 1200 ticks","20"},{"maxContainers","Maximum containers per network","1 to 256","16"},
            {"cooldown","QuickStack cooldown","2 to 100 ticks","2"},{"crateSlots","New chest capacity","9 to 216, multiples of 9","9"},
            {"cubeSlots","New UwU cube capacity","54 to 864, multiples of 9","54"}};
        for(int i=0;i<fields.length;i++){
            String[] f=fields[i];int value=getInt(m.draft,f[0]);
            m.button(i,Items.COMPARATOR,f[1]+": "+value,(b,t)->setInt(m.draft,f[0],m.number(value,Integer.parseInt(f[3]),b)),f[2],"Type an exact integer, then click; or left + / right -.","Validation happens before Apply. Nothing is truncated.");
        }
        m.button(31,Items.SHIELD,"Existing storage is retained",null,"Physical capacity settings apply to newly placed blocks.","The tier editor checks occupied backpack slots before a reduction.","A smaller radius never deletes disconnected container contents.");
        m.button(48,Items.ARROW,"Back to operator settings",(b,t)->m.go(StorageMenu.Mode.ADMIN));
    }
    private static int getInt(Rules.Config c,String name){try{return Rules.Config.class.getField(name).getInt(c);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    private static void setInt(Rules.Config c,String name,int value){try{Rules.Config.class.getField(name).setInt(c,value);}catch(ReflectiveOperationException e){throw new IllegalStateException(e);}}
    private static void materials(StorageMenu m){
        Set<String> all=new TreeSet<>(Rules.discoverMaterials());all.addAll(m.draft.materials.keySet());
        List<String> list=all.stream().filter(id->m.input.isBlank()||id.contains(m.input.toLowerCase(Locale.ROOT))).toList();int start=m.offset(list.size(),36);
        for(int i=start;i<Math.min(list.size(),start+36);i++){
            String id=list.get(i);boolean exists=Rules.itemExists(id),on=Boolean.TRUE.equals(m.draft.materials.get(id));
            m.button(i-start,PlayerViews.item(id),(on?"ON ":"OFF ")+id,(b,t)->{if(!exists&&!on)throw new IllegalArgumentException("This exact material ID is not registered on the server.");m.draft.materials.put(id,!on);},exists?"Registered material found":"Missing mod or material ID", "Toggle applies to storage recipes that CONSUME this material.","It never manufactures Dragonsteel or Witherite ingots.");
        }
        m.toggle(37,"Master compatibility",m.draft.compatibility,()->m.draft.compatibility=!m.draft.compatibility);
        m.button(39,Items.EMERALD,"Add selected / held material",(b,t)->{String id=m.selectedMaterial();if(!Rules.itemExists(id))throw new IllegalArgumentException("Material is not installed.");if(m.draft.materials.size()>=128&&!m.draft.materials.containsKey(id))throw new IllegalArgumentException("Material limit reached.");m.draft.materials.putIfAbsent(id,false);m.note("Added disabled material gate: "+id);},"Click an item in your inventory to select its exact ID.","New gates start OFF. Add a tier that uses it in the tier editor.","Selected: "+(m.chosenMaterial.isBlank()?"none":m.chosenMaterial));
        m.button(41,Items.BOOK,"Detection is not progression guessing",null,"Known ingot-name candidates are discovered in the runtime registry.","Choose predecessor, capacity and recipe cost yourself.","Missing optional mods are not hard dependencies.");
        m.button(48,Items.ARROW,"Back to operator settings",(b,t)->m.go(StorageMenu.Mode.ADMIN));
    }
    private static void tiers(StorageMenu m){
        List<Rules.Tier> tiers=m.draft.tiers;int start=m.offset(tiers.size(),36);
        for(int i=start;i<Math.min(tiers.size(),start+36);i++){
            Rules.Tier tier=tiers.get(i);String why=Rules.unavailable(m.draft,tier);
            m.button(i-start,AnyaStorage.BACKPACK.get(),tier.name+" ["+tier.id+"]",(b,t)->{m.chosenTier=tier.id;m.go(StorageMenu.Mode.TIER);},"Parent: "+(tier.parent.isEmpty()?"base chest":tier.parent),"Capacity: "+tier.slots+" slots; upgrades: "+tier.upgrades,"Cost: "+tier.cost+" x "+tier.material,why.isEmpty()?"Recipe is available":why);
        }
        m.button(39,Items.EMERALD,"Add tier from selected / held material",(b,t)->{
            if(tiers.size()>=48)throw new IllegalArgumentException("Tier limit reached.");String material=m.selectedMaterial();
            if(material.equals("anyastorage:backpack"))throw new IllegalArgumentException("A backpack cannot be its own tier-upgrade material.");
            String stem="custom_"+material.substring(material.indexOf(':')+1).replaceAll("[^a-z0-9_]","_");stem=stem.substring(0,Math.min(40,stem.length()));String id=stem;int suffix=2;while(m.draft.tier(id)!=null)id=stem+"_"+suffix++;
            Rules.Tier parent=tiers.getLast();Rules.Tier added=new Rules.Tier(id,"Custom "+suffix,parent.id,material,Math.min(432,parent.slots+18),Math.min(8,parent.upgrades+1),8,true);added.enabled=false;
            tiers.add(added);m.draft.materials.putIfAbsent(material,false);m.chosenTier=id;m.go(StorageMenu.Mode.TIER);
        },"New tiers and their compatibility gates start OFF.","Click an inventory material first, then this button.","Set the predecessor explicitly before applying.");
        m.button(48,Items.ARROW,"Back to operator settings",(b,t)->m.go(StorageMenu.Mode.ADMIN));
    }
    private static void tier(StorageMenu m){
        Rules.Tier tier=m.draft.tier(m.chosenTier);if(tier==null){m.go(StorageMenu.Mode.TIERS);return;}
        m.button(4,AnyaStorage.BACKPACK.get(),tier.name+" ["+tier.id+"]",null,"Persistent ID is not renamed, so existing backpacks stay linked.");
        m.toggle(9,"Tier crafting enabled",tier.enabled,()->tier.enabled=!tier.enabled);
        m.toggle(10,"Optional mod material",tier.optional,()->{if(tier.id.equals("starter"))throw new IllegalArgumentException("The starter root cannot be optional.");tier.optional=!tier.optional;if(tier.optional)m.draft.materials.putIfAbsent(tier.material,false);});
        m.button(11,Items.NAME_TAG,"Rename tier",(b,t)->{String value=m.input.strip();if(value.isEmpty()||value.length()>48)throw new IllegalArgumentException("Type a name from 1 to 48 characters.");tier.name=value;},"Type a display name above, then click.");
        m.button(12,PlayerViews.item(tier.material),"Material: "+tier.material,(b,t)->{String id=m.selectedMaterial();if(id.equals("anyastorage:backpack"))throw new IllegalArgumentException("Backpacks cannot be upgrade material.");tier.material=id;if(tier.optional)m.draft.materials.putIfAbsent(id,false);},"Click an inventory item, then this field.","Registered: "+Rules.itemExists(tier.material));
        m.button(13,Items.CHAIN,"Predecessor: "+(tier.parent.isEmpty()?"base chest":tier.parent),(b,t)->{
            if(tier.id.equals("starter"))throw new IllegalArgumentException("The starter root has no predecessor.");
            if(!m.input.isBlank()){if(m.draft.tier(m.input.strip())==null)throw new IllegalArgumentException("Type an existing tier ID.");tier.parent=m.input.strip();}
            else {List<String> ids=m.draft.tiers.stream().map(x->x.id).filter(id->!id.equals(tier.id)).toList();int at=ids.indexOf(tier.parent);tier.parent=ids.get(Math.floorMod(at+(b==1?-1:1),ids.size()));}
        },"Type an exact tier ID; or left/right to cycle.","Cycles and capacity downgrades are rejected at Apply.");
        m.button(14,Items.CHEST,"Capacity: "+tier.slots,(b,t)->tier.slots=m.number(tier.slots,9,b),"9 to 432, multiples of 9. Type exact value or left + / right -.");
        m.button(15,AnyaStorage.CORE.get(),"Upgrade slots: "+tier.upgrades,(b,t)->tier.upgrades=m.number(tier.upgrades,1,b),"0 to 8. Successors cannot have fewer than their predecessor.");
        m.button(16,Items.IRON_INGOT,"Material count: "+tier.cost,(b,t)->tier.cost=m.number(tier.cost,1,b),"1 to 8 material slots plus the predecessor backpack.");
        m.button(27,Items.ARROW,"Move earlier in display order",(b,t)->{int at=m.draft.tiers.indexOf(tier);if(at>0)Collections.swap(m.draft.tiers,at,at-1);},"Display order is separate from the predecessor chain.");
        m.button(28,Items.ARROW,"Move later in display order",(b,t)->{int at=m.draft.tiers.indexOf(tier);if(at<m.draft.tiers.size()-1)Collections.swap(m.draft.tiers,at,at+1);});
        m.button(30,Items.BOOK,"Preview recipe / why unavailable",(b,t)->StorageRecipe.definitions(m.draft).stream().filter(d->d.tier().equals(tier.id)).findFirst().ifPresent(m::chooseRecipe));
        m.button(32,Items.BARRIER,"Remove unused tier from draft",(b,t)->{
            if(tier.id.equals("starter"))throw new IllegalArgumentException("The starter root must remain.");
            if(m.data().bags.values().stream().anyMatch(bag->bag.tier.equals(tier.id)))throw new IllegalArgumentException("Existing backpack identities still use this tier. Disable crafting instead.");
            if(m.draft.tiers.stream().anyMatch(other->other.parent.equals(tier.id)))throw new IllegalArgumentException("Another tier still uses this predecessor.");
            m.draft.tiers.remove(tier);m.go(StorageMenu.Mode.TIERS);
        },"Existing or predecessor tiers cannot be removed.","Disabling crafting is safe and keeps existing contents.");
        String why=Rules.unavailable(m.draft,tier);m.button(40,Items.PAPER,why.isEmpty()?"Availability: ready":why,null);
        m.button(48,Items.ARROW,"Back to tiers",(b,t)->m.go(StorageMenu.Mode.TIERS));
    }
    private static void ores(StorageMenu m){
        List<Map.Entry<String,Integer>> entries=new ArrayList<>(m.draft.ores.entrySet());int start=m.offset(entries.size(),36);
        for(int i=start;i<Math.min(entries.size(),start+36);i++){var e=entries.get(i);m.button(i-start,PlayerViews.item(e.getKey()),e.getKey()+": "+e.getValue(),(b,t)->{if(t==net.minecraft.world.inventory.ClickType.QUICK_MOVE)m.draft.ores.remove(e.getKey());else m.draft.ores.put(e.getKey(),m.number(e.getValue(),1,b));},"Type progress per item, then click; or left + / right -.","Shift-click removes this feeder material. Range 1 to 10000.");}
        m.button(39,Items.EMERALD,"Add selected feeder material",(b,t)->{String id=m.selectedMaterial();if(!Rules.itemExists(id))throw new IllegalArgumentException("Unknown material.");m.draft.ores.putIfAbsent(id,1);},"Click an inventory item first. Starts at 1 progress.");
        m.button(48,Items.ARROW,"Back to operator settings",(b,t)->m.go(StorageMenu.Mode.ADMIN));
    }
    private static void categories(StorageMenu m){
        List<Map.Entry<String,String>> entries=new ArrayList<>(m.draft.categories.entrySet());int start=m.offset(entries.size(),36);
        for(int i=start;i<Math.min(entries.size(),start+36);i++){var e=entries.get(i);m.button(i-start,PlayerViews.item(e.getKey()),e.getKey()+": "+e.getValue(),(b,t)->m.draft.categories.remove(e.getKey()),"Click to remove this override and use automatic categories.");}
        m.button(38,Items.HOPPER,"Selected category: "+m.chosenCategory,(b,t)->{int at=Rules.CATEGORIES.indexOf(m.chosenCategory);m.chosenCategory=Rules.CATEGORIES.get(Math.floorMod(at+(b==1?-1:1),Rules.CATEGORIES.size()));},"Left / right cycles the category.");
        m.button(40,Items.EMERALD,"Assign selected item to category",(b,t)->{String id=m.selectedMaterial();if(m.chosenCategory.equals("any"))m.draft.categories.remove(id);else m.draft.categories.put(id,m.chosenCategory);},"Click an inventory item, choose a category, then assign.","any removes the override. Maximum 512 overrides.","Selected: "+(m.chosenMaterial.isBlank()?"none":m.chosenMaterial));
        m.button(48,Items.ARROW,"Back to operator settings",(b,t)->m.go(StorageMenu.Mode.ADMIN));
    }
    private static void recipes(StorageMenu m){
        List<StorageRecipe.Definition> defs=StorageRecipe.definitions(m.rules()).stream().filter(d->m.input.isBlank()||d.name().toLowerCase(Locale.ROOT).contains(m.input.toLowerCase(Locale.ROOT))||d.id().contains(m.input.toLowerCase(Locale.ROOT))).toList();int start=m.offset(defs.size(),45);
        for(int i=start;i<Math.min(defs.size(),start+45);i++){var d=defs.get(i);m.button(i-start,d.outputStack(),d.name(),(b,t)->m.chooseRecipe(d),List.of(d.blocked().isEmpty()?"Available":d.blocked(),"Click for ingredient layout and conflict diagnostics.",m.draft==null?"Live server rules":"Your unsaved draft rules"));}
        if(m.draft!=null)m.button(48,Items.ARROW,"Back to operator settings",(b,t)->m.go(StorageMenu.Mode.ADMIN));
    }
    private static void recipe(StorageMenu m){
        StorageRecipe.Definition d=m.recipe;if(d==null){m.go(StorageMenu.Mode.RECIPES);return;}
        List<ItemStack> ingredients=d.preview();for(int i=0;i<9;i++)if(!ingredients.get(i).isEmpty())m.button(10+i%3+(i/3)*9,ingredients.get(i),ingredients.get(i).getHoverName().getString(),null,List.of(StorageOps.itemId(ingredients.get(i))));
        m.button(24,d.outputStack(),d.name(),null,List.of("Shapeless: place the listed ingredients anywhere in the grid.",d.blocked().isEmpty()?"Available under the viewed rules":d.blocked(),"Upgrading a backpack preserves its inventory UUID and components."));
        List<String> lines=new ArrayList<>();lines.add(d.blocked().isEmpty()?"No disabled-condition blocker":d.blocked());
        if(m.conflicts.isEmpty())lines.add("No matching recipe conflict found for this preview layout.");else{lines.add("Other recipes match this layout; inspect before crafting:");lines.addAll(m.conflicts);}
        lines.add("Other-mod recipe conflicts are reported, not overwritten.");
        m.button(40,new ItemStack(m.conflicts.isEmpty()?Items.PAPER:Items.REDSTONE_TORCH),"Why unavailable / recipe diagnostics",null,lines);
        m.button(48,Items.ARROW,"Back to recipes",(b,t)->m.go(StorageMenu.Mode.RECIPES));
    }
    private static void impact(StorageMenu m){
        Rules.Config old=Rules.current,next=m.draft;List<String> lines=new ArrayList<>();String problem="";
        try{Rules.validate(next);m.data().validateTierChange(next);if(next.revision!=old.revision)throw new IllegalArgumentException("Stale draft: another administrator saved first.");}
        catch(RuntimeException error){problem=error.getMessage();}
        lines.add("Revision "+old.revision+" -> "+(old.revision+1));
        lines.add("Base radius: "+old.baseRadius+" -> "+next.baseRadius);lines.add("Maximum radius: "+old.maxRadius+" -> "+next.maxRadius);
        long affected=StorageOps.loadedHubs().stream().filter(h->h.radius()!=Math.min(next.maxRadius,Math.max(next.baseRadius,h.earnedRadius))).count();
        lines.add("Loaded hubs whose effective range changes: "+affected);lines.add("Unloaded hubs cannot be counted without loading chunks.");
        lines.add("Known backpack identities checked: "+m.data().bags.size());
        long tierChanges=next.tiers.stream().filter(t->{Rules.Tier before=old.tier(t.id);return before==null||before.slots!=t.slots||before.upgrades!=t.upgrades||before.enabled!=t.enabled||!before.parent.equals(t.parent)||!before.material.equals(t.material)||before.cost!=t.cost;}).count();
        lines.add("Added or changed tier definitions: "+tierChanges);lines.add("New chest capacity: "+old.crateSlots+" -> "+next.crateSlots);lines.add("New cube capacity: "+old.cubeSlots+" -> "+next.cubeSlots);
        lines.add("Existing physical containers retain their full inventory.");lines.add("Occupied backpack slots and installed upgrades cannot be truncated.");
        lines.add("Disabling recipes does not delete existing items.");lines.add("Range reduction disconnects remote access, not stored contents.");
        lines.add("File is atomically replaced; previous configuration becomes .bak.");
        for(int i=0;i<lines.size();i++)m.button(i,Items.PAPER,lines.get(i),null);
        if(!problem.isEmpty())m.button(31,Items.BARRIER,"Apply blocked",null,problem);
        else m.button(31,Items.LIME_DYE,"Validation passed",null,"Review the changes above. Apply is a separate action.");
        m.button(48,Items.ARROW,"Continue editing draft",(b,t)->m.go(StorageMenu.Mode.ADMIN));
        m.button(53,Items.EMERALD,"Apply this configuration draft",(b,t)->{
            try{Rules.apply(m.owner,m.draft);m.draft=null;m.go(StorageMenu.Mode.HOME);m.note("Configuration saved and synchronized. Live revision: "+Rules.current.revision);}
            catch(IOException e){AnyaStorage.LOG.error("Could not persist settings draft",e);m.note("Settings could not be saved. The live configuration was not changed.");}
        },"The server revalidates permissions, revision and item safety.","No write occurs if validation fails.");
    }
}
