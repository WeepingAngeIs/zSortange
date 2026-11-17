package dev.anya.storage.cecontrol;
import com.google.gson.GsonBuilder;
import net.minecraft.core.*;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.*;
import net.neoforged.neoforge.event.server.*;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
/** Isolated portal pixel control. No Anya pocket/lease adapter, inventory mutation or permission claim. */
@Mod(CeControl.ID) @EventBusSubscriber(modid=CeControl.ID)
public final class CeControl {
 public static final String ID="anyacecontrol";
 public static final ResourceKey<Level> DEST=ResourceKey.create(Registries.DIMENSION,ResourceLocation.fromNamespaceAndPath(ID,"target"));
 public static final BlockPos TARGET=new BlockPos(8,67,10),BARRIER=new BlockPos(8,67,4);
 private static MinecraftServer server;private static ServerLevel dest;private static Entity portal;
 private static int phase;private static long idleAt=-1;private static int peakDest;
 public CeControl(){control();}
 public static Path control(){
  if(!"true".equals(System.getenv("GITHUB_ACTIONS")))throw new IllegalStateException("Disposable GitHub fixture only");
  Path p=Path.of(System.getProperty("anya.ceControl")).toAbsolutePath().normalize();
  if(!Files.isRegularFile(p.resolve(".disposable-approved-ce")))throw new IllegalStateException("Disposable CE marker absent");
  if(!p.equals(Path.of(System.getProperty("anya.control")).toAbsolutePath().normalize()))throw new IllegalStateException("Safety diagnostics path differs");return p;
 }
 public static void require(boolean yes,String msg){if(!yes)throw new IllegalStateException(msg);}
 public static void write(String name,Object data)throws Exception{Files.writeString(control().resolve(name),new GsonBuilder().setPrettyPrinting().create().toJson(data)+"\n");}
 public static void fail(Throwable e){try{write("FAILED",Map.of("error",e.toString(),"phase",phase));}catch(Exception ignored){}e.printStackTrace();}
 private static void wall(ServerLevel l,int z,Block b){for(int x=4;x<=12;x++)for(int y=64;y<=72;y++)l.setBlock(new BlockPos(x,y,z),b.defaultBlockState(),3);}
 private static void call(String name,Class<?> type,Object value)throws Exception{portal.getClass().getMethod(name,type).invoke(portal,value);}
 @SubscribeEvent public static void start(ServerStartedEvent e){try{
  server=e.getServer();dest=server.getLevel(DEST);require(dest!=null,"Fresh fixture dimension not registered");
  for(String n:List.of("anya.nonblockingPrepare","anya.minimalOwnership","anya.serializationBudget","anya.backpressure","anya.repairJournal"))require(Boolean.getBoolean(n),"Missing existing protection "+n);
  for(var l:List.of(server.overworld(),dest)){l.setDayTime(6000);l.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);l.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);}
  server.setDefaultGameType(GameType.SPECTATOR);wall(server.overworld(),10,Blocks.BLUE_CONCRETE);wall(dest,10,Blocks.RED_CONCRETE);
  write("server-ready",Map.of("source",Level.OVERWORLD.location().toString(),"destination",DEST.location().toString(),"source_plane","always blue","destination_plane","red then green","notAnyaPocket",true));
 }catch(Throwable t){fail(t);e.getServer().halt(false);}}
 @SubscribeEvent public static void login(PlayerEvent.PlayerLoggedInEvent e){if(e.getEntity() instanceof ServerPlayer p)try{
  require(portal==null,"Exactly one fixture observer is supported");p.setGameMode(GameType.SPECTATOR);p.teleportTo(server.overworld(),8.5,65.5,2.5,0,0);
  Class<?> cls=Class.forName("qouteall.imm_ptl.core.portal.Portal");EntityType<?> type=(EntityType<?>)cls.getField("ENTITY_TYPE").get(null);portal=type.create(server.overworld());require(portal!=null,"Portal creation failed");
  call("setOriginPos",Vec3.class,new Vec3(8.5,67.12,6.5));
  cls.getMethod("setOrientationAndSize",Vec3.class,Vec3.class,double.class,double.class).invoke(portal,new Vec3(-1,0,0),new Vec3(0,1,0),6d,6d);
  call("setDestinationDimension",ResourceKey.class,DEST);call("setDestination",Vec3.class,new Vec3(8.5,67.12,6.5));call("setTeleportable",boolean.class,false);call("setInteractable",boolean.class,false);
  Field coll=cls.getDeclaredField("crossPortalCollisionEnabled");coll.setAccessible(true);coll.setBoolean(portal,false);cls.getField("specificPlayerId").set(portal,p.getUUID());
  require(!(boolean)cls.getMethod("getTeleportable").invoke(portal)&&!(boolean)cls.getMethod("isInteractable").invoke(portal),"Portal is not view-only");require(!cls.getField("isGlobalPortal").getBoolean(portal),"Global portal forbidden");
  require(server.overworld().addFreshEntity(portal),"Portal was not spawned");write("portal-spawned",Map.of("uuid",portal.getUUID(),"teleport",false,"interaction",false,"crossCollision",false,"global",false,"fixture_recipient",p.getUUID()));
 }catch(Throwable t){fail(t);server.halt(false);}}
 private static int ceTickets()throws Exception{
  Class<?> c=Class.forName("qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTickets");Map<?,?> dims=(Map<?,?>)c.getField("BY_DIMENSION").get(null);Object info=dims.get(dest);if(info==null)return 0;
  Field f=c.getDeclaredField("chunkPosToTicketInfo");f.setAccessible(true);return ((Map<?,?>)f.get(info)).size();
 }
 @SubscribeEvent public static void tick(ServerTickEvent.Post e){if(server==null)return;try{
  require(server.getTickCount()<6000,"Server tick limit");peakDest=Math.max(peakDest,dest.getChunkSource().getLoadedChunksCount());require(peakDest<4096,"Destination cache guard");
  if(phase==0&&Files.exists(control().resolve("portal-red-captured"))){wall(dest,10,Blocks.LIME_CONCRETE);phase=1;write("green-sent",Map.of("tick",server.getTickCount()));}
  else if(phase==1&&Files.exists(control().resolve("portal-green-captured"))){wall(server.overworld(),4,Blocks.BLUE_CONCRETE);phase=2;write("occlusion-sent",Map.of("portal_retained",true,"visible_flag_unchanged",true));}
  else if(phase==2&&Files.exists(control().resolve("occluded-blue-captured"))){wall(server.overworld(),4,Blocks.AIR);phase=3;write("unoccluded-sent",Map.of("tick",server.getTickCount()));}
  else if(phase==3&&Files.exists(control().resolve("restored-green-captured"))){portal.discard();phase=4;write("portal-removed",Map.of("removed",portal.isRemoved(),"invisibility_used_for_cleanup",false));}
  if(Files.exists(control().resolve("client-finished"))&&server.getPlayerList().getPlayers().isEmpty()){
   if(idleAt<0)idleAt=server.getTickCount();
   if(server.getTickCount()-idleAt>=650){require(portal.isRemoved(),"Portal retained");require(ceTickets()==0,"CE destination ticket registry retained");require(dest.getChunkSource().getLoadedChunksCount()==0,"Destination cache did not drain");
    Class<?> pockets=Class.forName("dev.anya.storage.pocket.PocketService");require(((Number)pockets.getMethod("ownedTicketRequests").invoke(null)).intValue()==0,"Anya requests retained");
    write("destination-drained",Map.of("ticks",server.getTickCount()-idleAt,"ce_registered_destination_tickets",ceTickets(),"destination_cache",0,"peak_destination_cache",peakDest,"scope","isolated fixture, not adapter or full-pack unloading proof"));server.saveEverything(true,true,true);server.halt(false);
   }
  }
 }catch(Throwable t){fail(t);server.halt(false);}}
 @SubscribeEvent public static void stopped(ServerStoppedEvent e){try{write("server-stopped",Map.of("clean",true));}catch(Exception x){fail(x);}}
}
