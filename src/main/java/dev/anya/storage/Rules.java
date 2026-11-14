package dev.anya.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Server-owned, revisioned rules. Menus edit deep copies, never the live object. */
public final class Rules {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    public static final List<String> GROUPS = List.of("backpacks", "hubs", "terminals", "containers", "upgrades", "endgame");
    public static final List<String> CATEGORIES = List.of("any", "food", "ores", "materials", "building", "equipment", "farming", "redstone", "mob_drops", "misc");
    public static final List<String> UPGRADE_IDS = List.of("pickup", "feeding", "refill", "compacting");
    public static volatile Config current = new Config();
    private static Path file;
    private Rules() {}

    public static final class Tier {
        public String id, name, parent, material;
        public int slots, upgrades, cost;
        public boolean enabled = true, optional;
        public Tier() {}
        public Tier(String id, String name, String parent, String material, int slots, int upgrades, int cost, boolean optional) {
            this.id=id; this.name=name; this.parent=parent; this.material=material;
            this.slots=slots; this.upgrades=upgrades; this.cost=cost; this.optional=optional;
        }
    }
    public static final class Config {
        public int schema=2;
        public long revision;
        public boolean backpacks=true, network=true, quickStack=true, organize=true, labels=true, upgrades=true;
        public boolean allowChests=true, allowBarrels=true, allowShulkers=false;
        public boolean compatibility=false, showHelp=true, audit=true;
        public int baseRadius=16, radiusStep=8, maxRadius=64, radiusCost=64;
        public int outsideRadius=8, scanBudget=2, scanInterval=100, maxContainers=128, cooldown=10;
        public int crateSlots=54, cubeSlots=432, endgameMode=0;
        public Map<String,Boolean> recipes=new LinkedHashMap<>(), materials=new TreeMap<>(), upgradeTypes=new LinkedHashMap<>();
        public Map<String,Integer> ores=new TreeMap<>();
        public Map<String,String> categories=new TreeMap<>();
        public List<Tier> tiers=new ArrayList<>();
        public Config() {
            GROUPS.forEach(x->recipes.put(x,!x.equals("endgame")));
            UPGRADE_IDS.forEach(x->upgradeTypes.put(x,true));
            ores.put("minecraft:coal",1); ores.put("minecraft:raw_copper",1); ores.put("minecraft:raw_iron",2);
            ores.put("minecraft:raw_gold",4); ores.put("minecraft:redstone",2); ores.put("minecraft:lapis_lazuli",3);
            ores.put("minecraft:diamond",16); ores.put("minecraft:emerald",12); ores.put("minecraft:ancient_debris",48);
            tiers.add(new Tier("starter","Starter","","minecraft:leather",27,1,8,false));
            tiers.add(new Tier("iron","Iron","starter","minecraft:iron_ingot",45,2,8,false));
            tiers.add(new Tier("gold","Gold","iron","minecraft:gold_ingot",63,3,8,false));
            tiers.add(new Tier("diamond","Diamond","gold","minecraft:diamond",81,4,8,false));
            tiers.add(new Tier("netherite","Netherite","diamond","minecraft:netherite_ingot",108,4,4,false));
        }
        public Tier tier(String id) { return tiers.stream().filter(t->Objects.equals(t.id,id)).findFirst().orElse(null); }
        public Config copy() { return JSON.fromJson(JSON.toJson(this),Config.class); }
    }

    public static boolean itemExists(String text) {
        ResourceLocation id=ResourceLocation.tryParse(text);
        return id!=null && BuiltInRegistries.ITEM.containsKey(id) && BuiltInRegistries.ITEM.get(id)!=Items.AIR;
    }
    public static boolean validId(String s) { return s!=null && s.length()<=256 && s.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"); }
    public static boolean group(String id) { return Boolean.TRUE.equals(current.recipes.get(id)); }
    public static String unavailable(Config c, Tier tier) {
        if(tier==null) return "Unknown tier";
        if(!c.backpacks || !Boolean.TRUE.equals(c.recipes.get("backpacks"))) return "Backpack crafting is disabled";
        if(!tier.enabled) return "This tier is disabled";
        if(!itemExists(tier.material)) return "Missing material: "+tier.material;
        if(tier.optional && !c.compatibility) return "Master compatibility is OFF";
        if(tier.optional && !Boolean.TRUE.equals(c.materials.get(tier.material))) return "Material compatibility is OFF: "+tier.material;
        Set<String> seen=new HashSet<>();
        Tier cursor=tier;
        while(!cursor.parent.isEmpty()) {
            if(!seen.add(cursor.id)) return "Cyclic predecessor chain";
            cursor=c.tier(cursor.parent);
            if(cursor==null) return "Missing predecessor";
            if(!cursor.enabled) return "Predecessor disabled: "+cursor.name;
            if(!itemExists(cursor.material)) return "Predecessor material missing: "+cursor.material;
            if(cursor.optional && (!c.compatibility || !Boolean.TRUE.equals(c.materials.get(cursor.material)))) return "Predecessor compatibility disabled: "+cursor.name;
        }
        return "";
    }

    public static void validate(Config c) {
        Objects.requireNonNull(c,"Missing configuration");
        if(c.schema!=2 || c.revision<0) throw new IllegalArgumentException("Unsupported schema or negative revision");
        range(c.baseRadius,4,128,"base radius"); range(c.radiusStep,1,32,"radius step"); range(c.maxRadius,c.baseRadius,128,"maximum radius");
        range(c.radiusCost,1,100000,"radius cost"); range(c.outsideRadius,1,16,"outside radius");
        range(c.scanBudget,1,16,"global chunk scan budget"); range(c.scanInterval,20,1200,"scan interval");
        range(c.maxContainers,1,256,"maximum containers"); range(c.cooldown,2,100,"action cooldown");
        range(c.crateSlots,9,216,"crate capacity"); range(c.cubeSlots,54,864,"cube capacity"); range(c.endgameMode,0,2,"endgame recipe mode");
        if(c.crateSlots%9!=0 || c.cubeSlots%9!=0) throw new IllegalArgumentException("Container capacities must be multiples of 9");
        if(c.recipes==null || !c.recipes.keySet().equals(new HashSet<>(GROUPS)) || c.recipes.containsValue(null)) throw new IllegalArgumentException("Invalid recipe groups");
        if(c.upgradeTypes==null || !c.upgradeTypes.keySet().equals(new HashSet<>(UPGRADE_IDS)) || c.upgradeTypes.containsValue(null)) throw new IllegalArgumentException("Invalid upgrade switches");
        if(c.materials==null || c.materials.size()>128) throw new IllegalArgumentException("At most 128 compatibility materials");
        c.materials.forEach((id,on)->{ if(!validId(id)||on==null) throw new IllegalArgumentException("Invalid material "+id); });
        if(c.ores==null || c.ores.size()>128) throw new IllegalArgumentException("At most 128 feeder materials");
        c.ores.forEach((id,value)->{if(!validId(id)||value==null) throw new IllegalArgumentException("Invalid feeder material"); range(value,1,10000,"ore value");});
        if(c.categories==null || c.categories.size()>512) throw new IllegalArgumentException("At most 512 category overrides");
        c.categories.forEach((id,category)->{if(!validId(id)||!CATEGORIES.contains(category)) throw new IllegalArgumentException("Invalid category override "+id);});
        if(c.tiers==null || c.tiers.isEmpty() || c.tiers.size()>48) throw new IllegalArgumentException("Use 1 to 48 tiers");
        Set<String> ids=new HashSet<>();
        for(Tier t:c.tiers) {
            if(t==null || t.id==null || !t.id.matches("[a-z0-9_]{1,48}") || !ids.add(t.id)) throw new IllegalArgumentException("Invalid or duplicate tier id");
            if(t.name==null || t.name.isBlank() || t.name.length()>48 || t.name.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Tier name must be 1 to 48 printable characters");
            if(t.parent==null || !validId(t.material)) throw new IllegalArgumentException("Invalid tier material or predecessor");
            range(t.slots,9,432,"tier slots"); range(t.upgrades,0,8,"tier upgrade slots"); range(t.cost,1,8,"recipe material count");
            if(t.slots%9!=0) throw new IllegalArgumentException("Tier capacity must be a multiple of 9");
        }
        Tier starter=c.tier("starter");
        if(starter==null || !starter.parent.isEmpty() || starter.optional) throw new IllegalArgumentException("Keep a non-optional starter root");
        for(Tier t:c.tiers) {
            Set<String> seen=new HashSet<>(); Tier cursor=t;
            while(!cursor.parent.isEmpty()) {
                if(!seen.add(cursor.id)) throw new IllegalArgumentException("Predecessor cycle at "+t.id);
                Tier parent=c.tier(cursor.parent);
                if(parent==null) throw new IllegalArgumentException("Unknown predecessor: "+cursor.parent);
                if(cursor.slots<parent.slots || cursor.upgrades<parent.upgrades) throw new IllegalArgumentException("A successor cannot have less capacity or fewer upgrade slots");
                cursor=parent;
            }
            if(!cursor.id.equals("starter")) throw new IllegalArgumentException("Every tier must descend from starter");
        }
        if(JSON.toJson(c).getBytes(StandardCharsets.UTF_8).length>200000) throw new IllegalArgumentException("Configuration exceeds sync size limit");
    }
    private static void range(int n,int min,int max,String name) { if(n<min||n>max) throw new IllegalArgumentException(name+" must be "+min+" to "+max); }

    public static void load() {
        file=FMLPaths.CONFIGDIR.get().resolve("anya-storage-plus.json");
        try {
            Config c;
            if(Files.exists(file)) {
                if(Files.size(file)>200000) throw new IOException("Configuration is too large");
                try(Reader r=Files.newBufferedReader(file)) { c=JSON.fromJson(r,Config.class); }
                validate(c);
            } else {
                c=new Config();
                Path legacy=file.resolveSibling("anya-storage-plus.properties");
                if(Files.exists(legacy)) {
                    Properties p=new Properties(); try(Reader r=Files.newBufferedReader(legacy)){p.load(r);}
                    if(!"1".equals(p.getProperty("schema"))) throw new IOException("Unknown legacy settings schema");
                    c.compatibility=Boolean.parseBoolean(p.getProperty("compatibility.enabled","false"));
                    c.audit=Boolean.parseBoolean(p.getProperty("audit.enabled","true"));
                    c.showHelp=Boolean.parseBoolean(p.getProperty("menu.help","true"));
                    for(String g:GROUPS) if(p.containsKey("recipes."+g)) c.recipes.put(g,Boolean.parseBoolean(p.getProperty("recipes."+g)));
                    for(String k:p.stringPropertyNames()) if(k.startsWith("material.")) c.materials.put(k.substring(9),Boolean.parseBoolean(p.getProperty(k)));
                }
                validate(c); writeAtomic(file,JSON.toJson(c));
            }
            current=c;
        } catch(Exception e) { throw new IllegalStateException("Anya Storage settings are invalid; the original file was not reset",e); }
    }
    public static synchronized void apply(ServerPlayer actor, Config draft) throws IOException {
        if(!actor.hasPermissions(2)) throw new IllegalArgumentException("Operator permission required");
        if(!actor.getServer().isSameThread()) throw new IllegalStateException("Not the server thread");
        if(draft.revision!=current.revision) throw new IllegalArgumentException("Another administrator saved first. Reopen your draft.");
        validate(draft);
        for(var e:draft.materials.entrySet()) if(e.getValue()&&!Boolean.TRUE.equals(current.materials.get(e.getKey()))&&!itemExists(e.getKey())) throw new IllegalArgumentException("Material is not installed: "+e.getKey());
        StorageData.get(actor.getServer()).validateTierChange(draft);
        Config next=draft.copy(); next.revision=Math.addExact(current.revision,1);
        writeAtomic(file,JSON.toJson(next));
        if(current.audit||next.audit) AnyaStorage.LOG.info("Storage settings revision {} -> {} by {} [{}]",current.revision,next.revision,actor.getGameProfile().getName(),actor.getUUID());
        current=next;
        Packets.syncAll(actor.getServer());
        StorageOps.invalidateAll();
    }
    public static void writeAtomic(Path target,String text) throws IOException {
        target=target.toAbsolutePath(); Files.createDirectories(target.getParent());
        Path temp=Files.createTempFile(target.getParent(),target.getFileName()+".",".tmp");
        try {
            byte[] bytes=text.getBytes(StandardCharsets.UTF_8);
            try(FileChannel channel=FileChannel.open(temp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer b=ByteBuffer.wrap(bytes); while(b.hasRemaining()) channel.write(b); channel.force(true);
            }
            if(Files.exists(target)) Files.copy(target,target.resolveSibling(target.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }
    public static List<String> discoverMaterials() {
        TreeSet<String> ids=new TreeSet<>(current.materials.keySet());
        for(ResourceLocation id:BuiltInRegistries.ITEM.keySet()) {
            String p=id.getPath(); if(p.contains("ingot")&&(p.contains("dragonsteel")||p.contains("witherite"))) ids.add(id.toString());
        }
        return new ArrayList<>(ids);
    }
    public static Config preset(boolean beta,Config before) {
        Config c=before.copy(); c.baseRadius=beta?24:16; c.maxRadius=beta?96:64;
        c.scanBudget=2; c.scanInterval=100; c.maxContainers=beta?192:96; c.outsideRadius=8;
        c.allowShulkers=false; c.audit=true;
        if(beta) for(Tier t:c.tiers) { t.slots=Math.min(432,t.slots+18); }
        return c;
    }
}
