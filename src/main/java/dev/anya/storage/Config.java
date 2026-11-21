package dev.anya.storage;

import com.google.gson.*;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.neoforged.fml.loading.FMLPaths;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** One server-authoritative, versioned config. Menus edit private copies, never the live object. */
public final class Config {
    public static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile Data live;
    private static Path path;
    private static boolean reloading;
    private static volatile boolean pocketActivationPending;
    public static String lastStatus = "Not initialized";
    public static final List<String> GROUPS = List.of("backpacks", "hubs", "terminals", "containers", "upgrades", "endgame");
    public static final List<String> UPGRADE_IDS = List.of("pickup", "feeding", "refill", "crafting", "smelting", "compacting", "resupply");
    public static final List<String> UPGRADE_FAMILIES = java.util.stream.Stream.concat(UPGRADE_IDS.stream(),
            java.util.stream.Stream.of("smoking","blasting")).toList();
    public static final List<String> DISC_IDS = java.util.stream.Stream.concat(UPGRADE_IDS.stream(),
            java.util.stream.Stream.of("advanced_pickup","advanced_compacting","manual_smelting","auto_smelting",
                    "manual_smoking","auto_smoking","manual_blasting","auto_blasting")).toList();
    public static String upgradeFamily(String id) {
        return switch(id) {
            case "advanced_pickup" -> "pickup";
            case "advanced_compacting" -> "compacting";
            case "manual_smelting", "auto_smelting" -> "smelting";
            case "manual_smoking", "auto_smoking" -> "smoking";
            case "manual_blasting", "auto_blasting" -> "blasting";
            default -> id;
        };
    }

    public static final List<String> CATEGORIES = List.of("any", "food", "ores", "materials", "blocks", "equipment", "farming", "mob_drops", "redstone", "misc");
    public static final List<String> SYMBOLS = List.of("heart", "star", "square", "circle", "diamond", "leaf", "gear");
    private static final List<String> CUPID_SETTINGS = List.of(
            "tabletOpeningChancePercent", "chestCameoEnabled", "chestCameoChancePercent", "backpackCameoEnabled");
    private static final List<String> RUNTIME_EXPANSION_SETTINGS = java.util.stream.Stream.concat(
            CUPID_SETTINGS.stream(), java.util.stream.Stream.of("pocketStorage", "pocketEditing")).toList();

    public static final class Tier {
        public String id, name, predecessor, material;
        public int slots, upgrades, cost;
        public boolean enabled;
        public Tier(String id, String name, String predecessor, String material, int slots, int upgrades, int cost, boolean enabled) {
            this.id=id; this.name=name; this.predecessor=predecessor; this.material=material;
            this.slots=slots; this.upgrades=upgrades; this.cost=cost; this.enabled=enabled;
        }
    }
    public static final class Data {
        public int schema=2;
        public ExpansionRules.Options expansion=new ExpansionRules.Options();
        public long revision=0;
        public boolean compatibility=false, fallbackMissingTiers=false;
        public boolean backpacks=true, network=true, quickStack=true, organize=true, labels=true, audit=true;
        public boolean vanillaChests=true, vanillaBarrels=true, vanillaShulkers=false;
        public int initialRadius=16, maxRadius=64, radiusStep=8, quickRadius=8;
        public int scanChunksPerTick=2, maxContainers=128, maxScanEntries=512, maxOperationSlots=8192;
        public int upgradeBaseCost=128, cubeRecipe=0;
        public Map<String,Boolean> recipes=new LinkedHashMap<>();
        public Map<String,Boolean> upgrades=new LinkedHashMap<>();
        public Map<String,Integer> oreValues=new LinkedHashMap<>();
        public Map<String,String> categoryRules=new LinkedHashMap<>();
        public List<Tier> tiers=new ArrayList<>();
        public Data() {
            GROUPS.forEach(k->recipes.put(k,!k.equals("endgame")));
            UPGRADE_FAMILIES.forEach(k->upgrades.put(k,true));
            tiers.add(new Tier("starter","Starter","","",27,1,0,true));
            tiers.add(new Tier("iron","Iron","starter","minecraft:iron_ingot",45,2,8,true));
            tiers.add(new Tier("gold","Gold","iron","minecraft:gold_ingot",63,3,8,true));
            tiers.add(new Tier("diamond","Diamond","gold","minecraft:diamond",81,4,8,true));
            tiers.add(new Tier("netherite","Netherite","diamond","minecraft:netherite_ingot",108,5,4,true));
            oreValues.put("minecraft:raw_copper",1); oreValues.put("minecraft:raw_iron",2);
            oreValues.put("minecraft:raw_gold",4); oreValues.put("minecraft:emerald",8);
            oreValues.put("minecraft:diamond",16); oreValues.put("minecraft:diamond_ore",24);
            oreValues.put("minecraft:deepslate_diamond_ore",24); oreValues.put("minecraft:ancient_debris",64);
        }
        public Tier tier(String id) { return tiers.stream().filter(t->t.id.equals(id)).findFirst().orElse(null); }
    }
    private Config() {}
    public static Data get() { if(live==null) throw new IllegalStateException("Config is not initialized"); return live; }
    /** Snapshot for a server-side pack settings screen. Return the revision with an edit. */
    public record TabletOpeningChance(long revision, int percent) {}
    public static TabletOpeningChance tabletOpeningChance() {
        Data current = get();
        return new TabletOpeningChance(current.revision, current.expansion.tabletOpeningChancePercent);
    }
    /** Apply one operator edit from the logical server thread using the normal config transaction. */
    public static synchronized boolean setTabletOpeningChancePercent(ServerPlayer actor, long expectedRevision, int percent) {
        return setServerSetting(actor, expectedRevision, "expansion.tabletOpeningChancePercent", Integer.toString(percent));
    }

    /** Immutable server-side values for a pack settings adapter. */
    public record SettingsSnapshot(long revision, Map<String, Object> values) {}

    public static SettingsSnapshot serverSettings(ServerPlayer actor) {
        if (!onServerThread(actor) || !actor.hasPermissions(2)) return null;
        Data current = get();
        return new SettingsSnapshot(current.revision, ServerSettings.values(current));
    }

    /** Apply a single named setting with the same transaction used by the operator menu. */
    public static synchronized boolean setServerSetting(ServerPlayer actor, long expectedRevision, String key, String value) {
        if (!onServerThread(actor) || !canSave(actor, expectedRevision)) return false;
        Data current = get();
        Data draft = copy(current);
        try {
            ServerSettings.update(draft, key, value);
        } catch (IllegalArgumentException e) {
            AnyaStorage.tell(actor, e.getMessage());
            return false;
        }
        if (JSON.toJsonTree(current).equals(JSON.toJsonTree(draft))) return true;
        return applyDraft(actor, draft);
    }

    private static boolean onServerThread(ServerPlayer actor) {
        return actor != null && actor.getServer() != null && actor.getServer().isSameThread();
    }

    public static boolean pocketStorageEnabled() {
        return get().expansion.pocketStorage && !pocketActivationPending;
    }
    public static Data copy(Data data) { return JSON.fromJson(JSON.toJson(data),Data.class); }
    static Data read(Path file) throws IOException {
        JsonObject object=JsonParser.parseString(Files.readString(file)).getAsJsonObject();
        Set<String> fields=JSON.toJsonTree(new Data()).getAsJsonObject().keySet();
        for(String key:object.keySet()) if(!fields.contains(key)) throw new IOException("Unknown config key: "+key);
        if (object.get("expansion") instanceof JsonObject expansion) {
            checkWholePercent(expansion, "tabletOpeningChancePercent", "Tablet opening chance");
            checkWholePercent(expansion, "chestCameoChancePercent", "Chest cameo chance");
            checkBoolean(expansion, "chestCameoEnabled");
            checkBoolean(expansion, "backpackCameoEnabled");
        }
        // Older schema-2 files have only the furnace switch. Expand them in memory only.
        if (object.get("upgrades") instanceof JsonObject switches && switches.has("smelting")) {
            for (String family : List.of("smoking", "blasting")) {
                if (!switches.has(family)) switches.add(family, switches.get("smelting"));
                checkBoolean(switches, family);
            }
        }
        Data data=JSON.fromJson(object,Data.class);
        validate(data,true);
        return data;
    }
    private static void checkWholePercent(JsonObject expansion, String key, String label) throws IOException {
        JsonElement value = expansion.get(key);
        if (value == null) return;
        // Gson's tree reader can truncate fractions or wrap oversized values when reading an int.
        if (!(value instanceof JsonPrimitive number && number.isNumber()))
            throw new IOException(label + " must be a whole percentage from 0 to 100");
        try { number.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException e) {
            throw new IOException(label + " must be a whole percentage from 0 to 100", e);
        }
    }
    private static void checkBoolean(JsonObject expansion, String key) throws IOException {
        JsonElement value = expansion.get(key);
        if (value != null && !(value instanceof JsonPrimitive flag && flag.isBoolean()))
            throw new IOException(key + " must be true or false");
    }
    public static void init() {
        path=FMLPaths.CONFIGDIR.get().resolve("anya-storage-plus.json").toAbsolutePath();
        pocketActivationPending=false;
        try {
            if(Files.exists(path)) {
                live=read(path);
            } else { live=new Data(); persist(live); }
            lastStatus="Loaded revision "+live.revision;
        } catch(Exception e) { throw new IllegalStateException("Cannot load storage config; original file has not been reset",e); }
    }
    public static void validate(Data d) {validate(d,false);}
    static void validateDraft(Data draft) { validate(draft, onlyCupidSettingsChanged(get(), draft)); }
    private static void validate(Data d,boolean dormantLoad) {
        require(d!=null && d.schema==2 && d.revision>=0,"Unsupported schema or revision");
        ExpansionRules.validate(d.expansion,dormantLoad);
        require(d.initialRadius>=4 && d.initialRadius<=128,"Initial radius must be 4..128");
        require(d.maxRadius>=d.initialRadius && d.maxRadius<=128,"Maximum radius must be initial..128");
        require(d.radiusStep>=1 && d.radiusStep<=32,"Radius step must be 1..32");
        require(d.quickRadius>=1 && d.quickRadius<=16,"QuickStack radius must be 1..16");
        require(d.scanChunksPerTick>=1 && d.scanChunksPerTick<=8,"Scan budget must be 1..8 chunks");
        require(d.maxContainers>=1 && d.maxContainers<=512,"Container limit must be 1..512");
        require(d.maxScanEntries>=32 && d.maxScanEntries<=2048,"Entry budget must be 32..2048");
        require(d.maxOperationSlots>=54 && d.maxOperationSlots<=32768,"Operation slots must be 54..32768");
        require(d.upgradeBaseCost>=1 && d.upgradeBaseCost<=100000,"Ore cost must be 1..100000");
        require(d.cubeRecipe>=0 && d.cubeRecipe<=2,"Unknown cube recipe");
        require(d.recipes!=null && d.recipes.keySet().equals(new HashSet<>(GROUPS)) && !d.recipes.containsValue(null),"Invalid recipe groups");
        require(d.upgrades!=null && d.upgrades.keySet().equals(new HashSet<>(UPGRADE_FAMILIES)) && !d.upgrades.containsValue(null),"Invalid upgrade switches");
        require(d.categoryRules!=null && d.categoryRules.size()<=256,"At most 256 category rules");
        d.categoryRules.forEach((key,category)->require(key!=null && validId(key.startsWith("#")?key.substring(1):key) && CATEGORIES.contains(category) && !category.equals("any"),"Invalid category rule "+key));
        require(d.oreValues!=null && d.oreValues.size()<=128,"At most 128 ore rules");
        d.oreValues.forEach((k,v)->require(validId(k)&&v!=null&&v>=1&&v<=100000,"Invalid ore rule "+k));
        require(d.tiers!=null && !d.tiers.isEmpty() && d.tiers.size()<=64,"Tier count must be 1..64");
        Set<String> ids=new HashSet<>(), recipes=new HashSet<>();
        for(Tier t:d.tiers) {
            require(t!=null && t.id!=null && t.id.matches("[a-z0-9_-]{1,40}") && ids.add(t.id),"Duplicate or invalid tier ID");
            require(t.name!=null && !t.name.isBlank() && t.name.length()<=48,"Tier names must be 1..48 characters");
            require(t.slots>=9 && t.slots<=216 && t.slots%9==0,"Capacity must be a multiple of 9, up to 216");
            require(t.upgrades>=0 && t.upgrades<=7,"Upgrade slots must be 0..7");
            require(t.predecessor!=null && t.material!=null,"Missing tier fields");
            if(t.id.equals("starter")) require(t.predecessor.isEmpty()&&t.cost==0,"Starter cannot have a predecessor/cost");
            else {
                require(validId(t.material)&&t.cost>=1&&t.cost<=8,"Tier material/cost invalid");
                require(recipes.add(t.predecessor+"|"+t.material+"|"+t.cost),"Ambiguous upgrade recipe");
            }
        }
        require(d.tier("starter")!=null,"Starter tier is required");
        for(Tier t:d.tiers) {
            Set<String> seen=new HashSet<>(); Tier cursor=t;
            while(!cursor.predecessor.isEmpty()) {
                require(seen.add(cursor.id),"Tier predecessor cycle");
                Tier parent=d.tier(cursor.predecessor);
                require(parent!=null,"Unknown predecessor "+cursor.predecessor);
                require(cursor.slots>=parent.slots && cursor.upgrades>=parent.upgrades,"Upgrade cannot reduce capacity or upgrade slots");
                cursor=parent;
            }
            require(cursor.id.equals("starter"),"All tiers must lead to starter");
        }
    }
    public static boolean validId(String id) { return id!=null && id.length()<=256 && id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"); }
    private static void require(boolean ok,String message) { if(!ok) throw new IllegalArgumentException(message); }
    public static boolean installed(String id) {
        ResourceLocation key=ResourceLocation.tryParse(id);
        return key!=null && BuiltInRegistries.ITEM.containsKey(key) && BuiltInRegistries.ITEM.get(key)!=Items.AIR;
    }
    public static String unavailable(Data d,Tier t) {
        if(t==null) return "Tier definition missing; existing contents are retained";
        if(!d.backpacks || !Boolean.TRUE.equals(d.recipes.get("backpacks"))) return "Backpack crafting disabled";
        if(!t.enabled) return "This tier is disabled";
        if(t.id.equals("starter")) return "";
        if(!installed(t.material)) return "Missing material: "+t.material;
        if(!t.material.startsWith("minecraft:")&&!d.compatibility) return "Optional compatibility master switch is OFF";
        String parent=predecessor(d,t);
        if(parent.isEmpty()) return "No available predecessor";
        return "";
    }
    public static String predecessor(Data d,Tier t) {
        Tier parent=d.tier(t.predecessor);
        for(int i=0;parent!=null&&i<64;i++) {
            boolean available=parent.enabled && (parent.id.equals("starter") || (installed(parent.material)&&(parent.material.startsWith("minecraft:")||d.compatibility)));
            if(available) return parent.id;
            if(!d.fallbackMissingTiers) return "";
            parent=d.tier(parent.predecessor);
        }
        return "";
    }
    private static boolean canSave(ServerPlayer actor, long expectedRevision) {
        if(!actor.hasPermissions(2)) { AnyaStorage.tell(actor,"Operator permission required."); return false; }
        if(reloading) { AnyaStorage.tell(actor,"A settings reload is already in progress."); return false; }
        if(expectedRevision!=live.revision) { AnyaStorage.tell(actor,"Another admin saved first. Reopen settings."); return false; }
        return true;
    }
    private static boolean onlyCupidSettingsChanged(Data before, Data after) {
        return onlyExpansionSettingsChanged(before, after, CUPID_SETTINGS);
    }
    private static boolean onlyExpansionSettingsChanged(Data before, Data after, List<String> keys) {
        if (after.expansion == null) return false;
        JsonObject expected = JSON.toJsonTree(before).getAsJsonObject();
        JsonObject actual = JSON.toJsonTree(after).getAsJsonObject();
        JsonObject previousExpansion = expected.getAsJsonObject("expansion");
        JsonObject nextExpansion = actual.getAsJsonObject("expansion");
        if (previousExpansion.equals(nextExpansion)) return false;
        for (String key : keys) previousExpansion.add(key, nextExpansion.get(key));
        return expected.equals(actual);
    }
    public static synchronized boolean save(ServerPlayer actor,Data draft) {
        if (!onServerThread(actor) || draft == null || !canSave(actor, draft.revision)) return false;
        return applyDraft(actor, draft);
    }
    /** The callers hold the Config lock and have checked thread, permission, revision and reload state. */
    private static boolean applyDraft(ServerPlayer actor, Data draft) {
        Data before=copy(live), next=copy(draft);
        boolean reload = !onlyExpansionSettingsChanged(before, next, RUNTIME_EXPANSION_SETTINGS);
        boolean pocketChanged = next.expansion != null && before.expansion.pocketStorage != next.expansion.pocketStorage;
        var server = actor.getServer();
        try {
            validate(next, onlyCupidSettingsChanged(before, next));
            if (pocketChanged) PocketService.validateStorageChange(server, next.expansion.pocketStorage);
            for(Tier t:next.tiers) {
                Tier old=before.tier(t.id);
                if(t.enabled&&!t.id.equals("starter")&&(old==null||!old.enabled||!old.material.equals(t.material)))
                    require(installed(t.material),"Cannot enable missing material "+t.material);
            }
            next.revision=Math.addExact(live.revision,1);
            persist(next);
            // A mixed edit can still roll back after reload. Keep new pocket construction closed until it succeeds.
            pocketActivationPending = reload && pocketChanged && next.expansion.pocketStorage;
            live=next;
        } catch(Exception e) { AnyaStorage.tell(actor,"Not saved: "+e.getMessage()); return false; }
        if (pocketChanged && !next.expansion.pocketStorage) PocketService.storageDisabled(server);
        if(before.audit||next.audit) AnyaStorage.LOG.info("Storage config revision {} by {} [{}]",next.revision,actor.getGameProfile().getName(),actor.getUUID());
        if (!reload) {
            lastStatus="Applied revision "+next.revision;
            AnyaStorage.tell(actor,lastStatus);
            return true;
        }
        reloading=true;
        try {
            server.reloadResources(List.copyOf(server.getPackRepository().getSelectedIds())).whenComplete((v,error)->server.execute(()->{
                if(error==null) {
                    pocketActivationPending=false; reloading=false;
                    lastStatus="Applied revision "+next.revision;
                    AnyaStorage.tell(actor,lastStatus);
                }
                else {
                    AnyaStorage.LOG.error("Storage recipe reload failed",error);
                    try {
                        before.revision=next.revision+1; persist(before); live=before;
                        pocketActivationPending=false;
                        server.reloadResources(List.copyOf(server.getPackRepository().getSelectedIds())).whenComplete((unused,recovery)->server.execute(()->{
                            reloading=recovery!=null;
                            lastStatus=recovery==null?"Reload failed; previous settings restored":"Recovery failed. Inspect logs and restart before edits";
                            AnyaStorage.tell(actor,lastStatus);
                        }));
                    } catch(Exception failure) { lastStatus="Recovery failed; restart required"; AnyaStorage.LOG.error(lastStatus,failure); }
                }
            }));
        } catch(Exception e) {
            lastStatus="Reload could not start; restart required";
            AnyaStorage.LOG.error(lastStatus,e);
            AnyaStorage.tell(actor,lastStatus);
        }
        return true;
    }
    private static void persist(Data d) throws IOException {
        Files.createDirectories(path.getParent());
        Path tmp=Files.createTempFile(path.getParent(),"anya-storage-",".tmp");
        try {
            byte[] data=JSON.toJson(d).getBytes(StandardCharsets.UTF_8);
            try(FileChannel out=FileChannel.open(tmp,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer=ByteBuffer.wrap(data); while(buffer.hasRemaining()) out.write(buffer); out.force(true);
            }
            if(Files.exists(path)) Files.copy(path,path.resolveSibling(path.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(tmp); }
    }
    public static List<String> changes(Data before,Data after) {
        List<String> lines=new ArrayList<>();
        JsonObject a=JSON.toJsonTree(before).getAsJsonObject(), b=JSON.toJsonTree(after).getAsJsonObject();
        for(String key:a.keySet()) if(!a.get(key).equals(b.get(key))) {
            if (key.equals("expansion") && after.expansion != null) {
                describeCupidChanges(lines, before.expansion, after.expansion);
                if (onlyCupidSettingsChanged(before, after)) continue;
            }
            lines.add(key+": changed");
        }
        if(after.maxRadius<before.maxRadius) lines.add("Range reduction hides distant storage; it does not delete items.");
        lines.add("Existing backpacks retain their saved capacity, even after tier changes.");
        return lines;
    }
    private static void describeCupidChanges(List<String> lines, ExpansionRules.Options before, ExpansionRules.Options after) {
        if (before.tabletOpeningChancePercent != after.tabletOpeningChancePercent)
            lines.add("Tablet opening appearance chance: "+before.tabletOpeningChancePercent+"% to "+after.tabletOpeningChancePercent+"%");
        if (before.chestCameoEnabled != after.chestCameoEnabled)
            lines.add("Chest Cupid cameo " + (after.chestCameoEnabled ? "enabled" : "disabled"));
        if (before.chestCameoChancePercent != after.chestCameoChancePercent)
            lines.add("Chest Cupid cameo chance: "+before.chestCameoChancePercent+"% to "+after.chestCameoChancePercent+"%");
        if (before.backpackCameoEnabled != after.backpackCameoEnabled)
            lines.add("Backpack Cupid cameo " + (after.backpackCameoEnabled ? "enabled" : "disabled"));
    }
}
