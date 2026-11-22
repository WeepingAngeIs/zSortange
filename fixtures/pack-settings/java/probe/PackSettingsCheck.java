package probe;

import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.anya.storage.Config;
import dev.anya.storage.StoreMenu;
import io.netty.channel.embedded.EmbeddedChannel;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.*;
import net.minecraft.network.protocol.*;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

@Mod("packsettingsprobe")
@EventBusSubscriber(modid="packsettingsprobe")
public final class PackSettingsCheck {
    static final String CHANCE="expansion.tabletOpeningChancePercent", SHARING="expansion.imageSharing", COMMAND="anya storage settings";
    static final Path CONFIG=Path.of("config/anya-storage-plus.json"), BACKUP=CONFIG.resolveSibling("anya-storage-plus.json.bak");
    static final Map<UUID,List<String>> messages=new HashMap<>();
    static final List<String> evidence=new ArrayList<>();
    static ReflectiveSettings api;
    static ServerPlayer operator, colleague, guest;
    static int step,started;
    static long afterReload;
    static Map<String,Object> expectedSettings;
    static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
    static void pass(String detail){evidence.add("PASS "+detail);}
    static ServerPlayer actor(MinecraftServer server,String name,boolean op){
        var uuid=UUID.nameUUIDFromBytes(("OfflinePlayer:"+name).getBytes(StandardCharsets.UTF_8));
        messages.put(uuid,new ArrayList<>());
        var profile=new GameProfile(uuid,name);
        var player=new ServerPlayer(server,server.overworld(),profile,ClientInformation.createDefault());
        var connection=new Connection(PacketFlow.SERVERBOUND){
            @Override public boolean isMemoryConnection(){return true;}
            @Override public void send(Packet<?> packet,PacketSendListener callback,boolean flush){
                if(packet instanceof ClientboundSystemChatPacket system)messages.get(uuid).add(system.content().getString());
            }
        };
        new EmbeddedChannel(connection);NetworkRegistry.configureMockConnection(connection);
        server.getPlayerList().placeNewPlayer(connection,player,CommonListenerCookie.createInitial(profile,false));
        if(op)server.getPlayerList().op(profile);
        check(player.hasPermissions(2)==op,"Incorrect real operator grant: "+name);return player;
    }
    static Object read()throws Exception{return api.snapshot(operator);}
    static long rev()throws Exception{return ReflectiveSettings.revision(read());}
    static Map<String,Object> values()throws Exception{return ReflectiveSettings.values(read());}
    static Object field(Object obj,String field)throws Exception{return ReflectiveSettings.field(obj,field);}
    static Object write(ServerPlayer actor,long revision,String key,String value,boolean confirmed,String expected)throws Exception{
        var result=api.set(actor,revision,key,value,confirmed);
        check(expected.equals(field(result,"status")),"Setting "+key+"="+value+" expected "+expected+", received "+result);
        check(!field(result,"message").toString().isBlank(),"Missing result message for "+expected);
        return result;
    }
    static String onDisk()throws IOException{return Files.readString(CONFIG);}
    static void disk(Path path,long revision,int percent,boolean sharing)throws Exception{
        var json=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
        var expansion=json.getAsJsonObject("expansion");
        check(json.get("revision").getAsLong()==revision && expansion.get("tabletOpeningChancePercent").getAsInt()==percent
                &&expansion.get("imageSharing").getAsBoolean()==sharing,"Wrong durable values in "+path);
    }
    static int command(ServerPlayer p,String line)throws Exception{
        return p.getServer().getCommands().getDispatcher().execute(line,p.createCommandSourceStack());
    }
    static void deniedCommand(ServerPlayer p,String line)throws Exception{
        boolean denied=false;
        try{denied=command(p,line)==0;}catch(CommandSyntaxException refused){denied=true;}
        check(denied,"Guest could dispatch /"+line);
    }
    // Deliberately takes no actor or snapshot. Only the public, static definition properties are exportable.
    static String schema()throws Exception{
        var json=new com.google.gson.GsonBuilder().setPrettyPrinting().serializeNulls().create();
        var root=new com.google.gson.JsonObject();
        root.addProperty("modId","anyastorage");root.addProperty("apiVersion",api.version());
        var definitions=new com.google.gson.JsonArray();
        for(String key:new TreeSet<>(api.keys())){
            Object source=api.definition(key);
            var definition=new com.google.gson.JsonObject();
            for(String name:List.of("key","kind","label","description","defaultValue","minimum","maximum","unit","applyMode","confirmValue")){
                var value=field(source,name);
                definition.add(name,json.toJsonTree(value instanceof Enum<?> kind ? kind.name() : value));
            }
            definitions.add(definition);
        }
        check(definitions.size()==71,"Unexpected schema key count");
        root.add("settings",definitions);
        return json.toJson(root)+"\n";
    }
    static void catalogue()throws Exception{
        List<String> legacy;
        try(var stream=PackSettingsCheck.class.getResourceAsStream("/legacy-keys.txt")){
            if(stream==null)throw new AssertionError("Missing baseline key inventory");
            legacy=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8)).lines().toList();
        }
        check(api.version()==1,"Unexpected pack API version");
        var keys=api.keys();var stored=values();
        check(legacy.size()==71 && new TreeSet<>(keys).containsAll(legacy) && new HashSet<>(keys).size()==keys.size()
                &&stored.keySet().equals(new HashSet<>(keys)),"Missing/duplicated persisted settings");
        for(String key:keys){
            var row=api.definition(key);
            check(key.equals(field(row,"key")) && !field(row,"label").toString().isBlank() && !field(row,"description").toString().isBlank(),"Missing operator wording for "+key);
            check(Set.of("live","resource_reload").contains(field(row,"applyMode")),"Missing apply mode for "+key);
            String kind=field(row,"kind").toString();
            check(Set.of("BOOLEAN","INTEGER","DECIMAL","REGISTRY_ID").contains(kind),"Unknown kind "+key);
            if(kind.equals("INTEGER")||kind.equals("DECIMAL")){
                var lo=(Number)field(row,"minimum");var hi=(Number)field(row,"maximum");var def=(Number)field(row,"defaultValue");
                check(lo!=null && hi!=null && lo.doubleValue()<=def.doubleValue() && def.doubleValue()<=hi.doubleValue(),"Invalid numeric bounds/default for "+key);
                check(!field(row,"unit").toString().isBlank(),"Missing numeric unit for "+key);
            }
        }
        var chance=api.definition(CHANCE);
        check(field(chance,"unit").equals("percent") && field(chance,"applyMode").equals("live") &&
                field(chance,"minimum").equals(0)&&field(chance,"maximum").equals(100)&&field(chance,"defaultValue").equals(24),"Tablet chance metadata");
        check("blocks".equals(field(api.definition("quickRadius"),"unit")) &&
                "ticks".equals(field(api.definition("expansion.pocketSessionTicks"),"unit")) &&
                "fraction".equals(field(api.definition("expansion.monsterChance"),"unit")),"Blocks/ticks/fraction metadata");
        check(Boolean.TRUE.equals(field(api.definition(SHARING),"confirmValue")) && field(chance,"confirmValue")==null,"Sensitive target metadata");
        check(api.definition("not.a.storage.setting")==null,"Invented setting accepted");
        Files.writeString(Path.of("SCHEMA.json"),schema(),StandardCharsets.UTF_8);
        pass("A reflection-only consumer read all 71 existing keys, labels, types, defaults and ranges, and distinguished percentages, fractions, block distances, ticks and disruptive controls.");
    }
    static void policy(int percent)throws Exception{
        check(values().get(CHANCE).equals(percent) && Config.tabletOpeningChance().percent()==percent,"Runtime percent differs from authorized snapshot");
        var roll=Config.get().expansion.getClass().getDeclaredMethod("rollTabletOpening",java.util.random.RandomGenerator.class);roll.setAccessible(true);
        var rolls=new Random(){int next;@Override public int nextInt(int limit){check(limit==100,"Percentile bound");return next++;}};
        int accepted=0;for(int i=0;i<100;i++)if((Boolean)roll.invoke(Config.get().expansion,rolls))accepted++;
        check(accepted==percent,"Production tablet policy chose "+accepted+" out of 100, expected "+percent);
    }
    static void checkMenu(ServerPlayer p,String target)throws Exception{
        check(p.containerMenu instanceof StoreMenu,"Native command did not open a Storage menu");
        var page=StoreMenu.class.getDeclaredField("page");page.setAccessible(true);
        check(page.get(p.containerMenu).toString().equals(target),"Wrong native page "+page.get(p.containerMenu));
        p.closeContainer();
    }
    static void commands()throws Exception{
        var messagesForOp=messages.get(operator.getUUID());messagesForOp.clear();
        check(command(operator,COMMAND+" list")==1 && messagesForOp.stream().anyMatch(m->m.contains(CHANCE) && m.contains("quickStack")),"Operator command catalogue");
        messagesForOp.clear();check(command(operator,COMMAND+" get "+CHANCE)==1 && messagesForOp.stream().anyMatch(m->m.contains("24")),"Operator get current saved chance");
        check(command(operator,COMMAND)==1,"Operator shorthand");checkMenu(operator,"ADMIN");
        check(command(operator,"anya storage")==1,"Existing operator command");checkMenu(operator,"ADMIN");
        check(command(guest,"anya storage")==1,"Existing player command");checkMenu(guest,"PREFS");
        deniedCommand(guest,COMMAND);deniedCommand(guest,COMMAND+" list");deniedCommand(guest,COMMAND+" get "+CHANCE);deniedCommand(guest,COMMAND+" set "+CHANCE+" 0");
        pass("The actual command dispatcher accepted operator list/get and both bare operator entries. A non-operator could open personal preferences and could not run the pack settings commands.");
    }
    static void operations(MinecraftServer server)throws Exception{
        var initial=rev();var original=onDisk();expectedSettings=new HashMap<>(values());
        check(api.snapshot(guest)==null && ReflectiveSettings.revision(api.status(guest))==-1,"Guest obtained settings/status");
        check(ReflectiveSettings.revision(write(guest,initial,CHANCE,"0",false,"rejected"))==-1,"Guest rejection disclosed a revision");
        var off=CompletableFuture.supplyAsync(()->{
            try{return api.snapshot(operator)==null && ReflectiveSettings.revision(write(operator,initial,CHANCE,"0",true,"rejected"))==-1;}
            catch(Exception e){throw new CompletionException(e);}
        }).get(5,TimeUnit.SECONDS);
        check(off&&onDisk().equals(original),"Off-thread access changed or exposed settings");
        policy(24);
        check(ReflectiveSettings.revision(write(operator,initial,CHANCE,"24",false,"unchanged"))==initial && onDisk().equals(original),"No-op wrote settings");
        check(command(operator,COMMAND+" set "+CHANCE+" 0")==1 && rev()==initial+1,"Command edit did not commit one revision");policy(0);
        var diskZero=onDisk();String bakZero=Files.readString(BACKUP);
        for(String invalid:List.of("-1","101","1.5","NaN","null","true","4294967396"))
            write(operator,rev(),CHANCE,invalid,false,"rejected");
        check(command(operator,COMMAND+" set "+CHANCE+" 101")==0,"Command bypassed bounds");
        write(operator,initial,CHANCE,"24",false,"rejected");write(operator,rev(),"unregistered.key","true",false,"rejected");
        check(rev()==initial+1&&onDisk().equals(diskZero)&&Files.readString(BACKUP).equals(bakZero),"Invalid/stale edit changed the file or its backup");
        write(operator,rev(),CHANCE,"24",false,"applied");policy(24);
        write(operator,rev(),CHANCE,"100",false,"applied");policy(100);
        var before=rev();disk(CONFIG,before,100,false);disk(BACKUP,before-1,24,false);
        expectedSettings.put(CHANCE,100);check(expectedSettings.equals(values()),"Changing tablet chance altered another setting");
        server.getPlayerList().deop(operator.getGameProfile());
        check(api.snapshot(operator)==null && ReflectiveSettings.revision(write(operator,before,CHANCE,"0",true,"rejected"))==-1,"An old operator draft survived revocation");
        server.getPlayerList().op(operator.getGameProfile());
        pass("The real tablet policy admitted exactly 0, 24 and 100 of 100 controlled rolls. Out-of-range, malformed, unknown, stale, revoked and off-thread edits left disk and backups intact.");
        var untouched=onDisk();
        write(operator,before,SHARING,"true",true,"confirmation_required");
        write(colleague,before,SHARING,"true",true,"confirmation_required");
        check(onDisk().equals(untouched)&&!((Boolean)values().get(SHARING)),"A forged first confirmation or another operator enabled sharing");
        var applied=write(operator,before,SHARING,"true",true,"pending_reload");afterReload=ReflectiveSettings.revision(applied);
        check(afterReload==before+1 && field(api.status(operator),"status").equals("pending_reload"),"Confirmed change did not advance revision once and report its pending reload");
        expectedSettings.put(SHARING,true);
        pass("Turning on sharing required a matching second confirmation by the same operator and reported its resource reload.");
    }
    @SubscribeEvent public static void tick(ServerTickEvent.Post event)throws Exception{
        if(step==99)return;
        try{
            var server=event.getServer();if(server.getTickCount()<30)return;
            if(step==0){
                api=new ReflectiveSettings();operator=actor(server,"SettingsOp",true);
                check(ModList.get().getMods().stream().map(m->m.getModId().toLowerCase(Locale.ROOT)).noneMatch(id->id.contains("unyielding")||id.contains("configtopia")),"Optional pack unexpectedly loaded");
                if(Files.readString(Path.of("phase.txt")).trim().equals("restart")){
                    long expected=Long.parseLong(Files.readString(Path.of("expected.txt")));
                    check(rev()==expected && Boolean.TRUE.equals(values().get(SHARING)),"Saved settings did not survive a dedicated restart");
                    policy(100);disk(CONFIG,expected,100,true);disk(BACKUP,expected-1,100,false);
                    check(Files.readString(Path.of("SCHEMA.json")).equals(schema()),"The exported definitions changed with the saved values");
                    pass("Exported 71 public definitions to SCHEMA.json and reproduced the exact same schema after changing settings and restarting.");
                    pass("A clean standalone restart recovered the exact revision, changed percentage, sharing setting and prior-value backup, with no optional pack mod installed.");
                    Files.write(Path.of("restart.pass"),evidence);step=99;return;
                }
                guest=actor(server,"SettingsGuest",false);colleague=actor(server,"SettingsSecondOp",true);
                catalogue();commands();operations(server);started=server.getTickCount();step=1;
            }else if(step==1){
                if(!field(api.status(operator),"status").equals("ready")){
                    check(server.getTickCount()-started<500,"Resource reload did not complete: "+Config.lastStatus);return;
                }
                disk(CONFIG,afterReload,100,true);disk(BACKUP,afterReload-1,100,false);
                check(rev()==afterReload && values().equals(expectedSettings) && ReflectiveSettings.revision(api.status(operator))==afterReload,"Unexpected revision or setting after resource reload");
                Files.writeString(Path.of("expected.txt"),Long.toString(afterReload));
                Files.write(Path.of("first.pass"),evidence);step=99;
            }
        }catch(Throwable error){
            var detail=new StringWriter();error.printStackTrace(new PrintWriter(detail));
            Files.writeString(Path.of("FAILED"),detail.toString());step=99;throw error;
        }
    }
}
