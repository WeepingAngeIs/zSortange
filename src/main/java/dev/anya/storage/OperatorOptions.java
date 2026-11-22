package dev.anya.storage;

import java.util.*;
import java.util.function.Consumer;

/** A row edits only the supplied config copy. Config.save validates the complete result. */
final class OperatorOptions {
    record Row(String key,String label,String help,String value,boolean toggle,boolean confirm,Consumer<String> edit) {}
    private final List<Row> rows=new ArrayList<>();
    private void add(String key,String label,String help,Object value,boolean confirm,Consumer<String> edit){
        rows.add(new Row(key,label,help,value.toString(),value instanceof Boolean,confirm,edit));
    }
    static List<Row> rows(Config.Data d){
        var options=new OperatorOptions();
        ServerSettings.values(d).forEach((key,value)->{
            var def=ServerSettings.definition(key);
            String bounds=def.minimum()==null?"":" Range "+def.minimum()+" to "+def.maximum()+".";
            Boolean confirmValue=def.confirmValue();
            boolean confirm=confirmValue!=null && value instanceof Boolean current && !confirmValue.equals(current);
            options.add(key,def.label(),def.description()+bounds,value,confirm,v->ServerSettings.update(d,key,v));
        });
        options.add("pocketActivityLog","Pocket activity logging","Record pocket activity.",d.expansion.pocketActivityLog,false,v->d.expansion.pocketActivityLog=flag(v));
        options.add("pocketLogNames","Names in pocket activity logs","Include player names in pocket logs.",d.expansion.pocketLogNames,true,v->d.expansion.pocketLogNames=flag(v));
        options.add("pocketLogEntries","Pocket log length","Records per pocket, 0 to 200.",d.expansion.pocketLogEntries,false,v->d.expansion.pocketLogEntries=Integer.parseInt(v));
        for(var t:d.tiers){
            String id="tier/"+t.id+"/",label=t.name+" tier / ";
            options.add(id+"enabled",label+"Enabled","Enable this crafting tier.",t.enabled,false,v->t.enabled=flag(v));
            options.add(id+"name",label+"Name","Display name, up to 48 characters.",t.name,false,v->t.name=v);
            options.add(id+"slots",label+"Capacity","Slots, in multiples of nine.",t.slots,false,v->t.slots=Integer.parseInt(v));
            options.add(id+"upgrades",label+"Disc slots","Upgrade sockets, 0 to 7.",t.upgrades,false,v->t.upgrades=Integer.parseInt(v));
            if(t.id.equals("starter"))continue;
            options.add(id+"material",label+"Ingredient","Enter an item ID.",t.material,true,v->t.material=v);
            options.add(id+"cost",label+"Ingredient count","Occupied recipe cells, 1 to 8.",t.cost,true,v->t.cost=Integer.parseInt(v));
            options.add(id+"previous",label+"Previous tier","Enter a tier ID.",t.predecessor,true,v->t.predecessor=v);
        }
        options.add("tier/add","Add a backpack tier","Enter an installed material ID. The tier starts disabled.","",true,v->{
            if(!Config.installed(v))throw new IllegalArgumentException("Choose an installed item.");
            var prev=d.tier("netherite");if(prev==null)prev=d.tier("starter");
            String base=v.replaceAll("[^a-z0-9_-]","_");base=base.substring(0,Math.min(34,base.length()));
            String id=base;for(int i=2;d.tier(id)!=null;i++)id=base+"_"+i;
            d.tiers.add(new Config.Tier(id,id,prev.id,v,Math.min(216,prev.slots+36),Math.min(7,prev.upgrades+1),8,false));
        });
        d.oreValues.forEach((k,n)->options.add("ore/"+k,"Ore points / "+k,"Enter 1 to 100000, or remove.",n,true,v->{if(v.equals("remove"))d.oreValues.remove(k);else d.oreValues.put(k,Integer.parseInt(v));}));
        d.categoryRules.forEach((k,c)->options.add("category/"+k,"Category / "+k,"Enter a category or remove.",c,true,v->{if(v.equals("remove"))d.categoryRules.remove(k);else d.categoryRules.put(k,v);}));
        options.add("ore/add","Add ore points","Enter an item ID and point value, separated by a space.","",true,v->{var pair=pair(v);if(d.oreValues.putIfAbsent(pair[0],Integer.parseInt(pair[1]))!=null)throw new IllegalArgumentException("This ore already has a row.");});
        options.add("category/add","Add category rule","Enter an item or #tag ID and category, separated by a space.","",true,v->{var pair=pair(v);if(d.categoryRules.putIfAbsent(pair[0],pair[1])!=null)throw new IllegalArgumentException("This category rule already exists.");});
        options.members("species","Backpack monster species",d.expansion.monsterSpecies);
        options.members("uploader","Image uploader UUID",d.expansion.imageUploaders);
        for(boolean large:new boolean[]{false,true}){
            options.add("preset/"+large,large?"Apply larger network preset":"Apply conservative network preset",
                    large?"Sets range 16–96, QuickStack 12, 256 containers and 4 chunks/tick.":"Sets range 16–48, QuickStack 6, 64 containers and 1 chunk/tick.",
                    "Apply",true,v->{d.initialRadius=16;d.maxRadius=large?96:48;d.quickRadius=large?12:6;d.maxContainers=large?256:64;d.scanChunksPerTick=large?4:1;});
        }
        return options.rows;
    }
    private void members(String id,String label,List<String> members){
        for(String member:members)add(id+"/"+member,label+" / "+member,"Remove this entry.","Remove",true,v->members.remove(member));
        add(id+"/add","Add "+label,"Enter the exact ID.","",true,v->{if(members.contains(v))throw new IllegalArgumentException("Already in this list.");members.add(v);});
    }
    private static String[] pair(String value){var pair=value.split("\\s+",2);if(pair.length!=2)throw new IllegalArgumentException("Enter the ID and value separated by a space.");return pair;}
    private static boolean flag(String v){if(!v.equals("true")&&!v.equals("false"))throw new IllegalArgumentException("Choose On or Off.");return Boolean.parseBoolean(v);}
}
