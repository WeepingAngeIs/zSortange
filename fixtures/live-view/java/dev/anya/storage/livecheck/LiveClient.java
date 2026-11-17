package dev.anya.storage.livecheck;
import dev.anya.storage.*;
import dev.anya.storage.pocket.PocketService;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.core.*;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.*;
import net.minecraft.nbt.CompoundTag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.network.ImmPtlNetworking;
import qouteall.imm_ptl.core.api.PortalAPI;
import qouteall.imm_ptl.core.portal.Portal;
import java.lang.reflect.*;
import java.util.*;

/** Genuine network client. Test signals direct ordinary command/item packets and inspect actual applied destination data. */
@EventBusSubscriber(modid=LiveTest.ID,value=Dist.CLIENT)
public final class LiveClient {
 static boolean connecting,hello;static int age,ticks,waited,stable;static TestWire.Signal work;
 static long homeColumnPackets,homeColumnPacketsAtExit;
 static final Set<Long> columns=new HashSet<>();static final Map<String,Long> applied=new TreeMap<>();static final Map<Integer,UUID> portals=new HashMap<>();
 public static void receive(TestWire.Signal s){
  check(work==null,"Overlapping client directive: "+(work==null?"none":work.action())+" -> "+s.action());
  work=s;ticks=waited=stable=0;
  dev.anya.storage.AnyaStorage.LOG.info("LIVE_CLIENT_RECEIVED role={} sequence={} action={}",role(),s.sequence(),s.action());
 }
 static String role(){return System.getProperty("anya.role");}
 static void check(boolean b,String m){LiveTest.check(b,m);}
 static void ack(){
  var mc=Minecraft.getInstance();
  dev.anya.storage.AnyaStorage.LOG.info("LIVE_CLIENT_ACK role={} sequence={} action={} ticks={} screen={} dimension={}",role(),work.sequence(),work.action(),ticks,mc.screen==null?"none":mc.screen.getClass().getSimpleName(),mc.level==null?"none":mc.level.dimension().location());
  PacketDistributor.sendToServer(new TestWire.Signal(work.sequence(),"done"));work=null;
 }
 @SuppressWarnings("unchecked") static ClientLevel remote()throws Exception{var f=ClientWorldLoader.class.getDeclaredField("CLIENT_WORLD_MAP");f.setAccessible(true);return (ClientLevel)((Map<?,?>)f.get(null)).get(PocketService.DIMENSION);}
 static void tag(CompoundTag t){check(t==null||Set.of("Symbol","ShowLabels").containsAll(t.getAllKeys()),"Private chest data reached client: "+t);}
 /** Called after CE applies a redirected packet on the actual client thread, never substitutes its handler. */
 public static void observe(ResourceKey<Level> dimension,Packet<?> packet){
  if(!Minecraft.getInstance().isSameThread())return;
  try{
   if(packet instanceof ClientboundCustomPayloadPacket c&&c.payload() instanceof ImmPtlNetworking.PortalSyncPacket p&&p.extraData().getString("dimensionTo").equals(PocketService.DIMENSION.location().toString())){
    check(!p.extraData().getBoolean("teleportable")&&!p.extraData().getBoolean("interactable")&&!p.extraData().getBoolean("hasCrossPortalCollision"),"Unsafe portal flags reached client");portals.put(p.id(),p.uuid());applied.merge("portal",1L,Long::sum);
   }
   if(packet instanceof ClientboundRemoveEntitiesPacket r)for(int id:r.getEntityIds())portals.remove(id);
   if(dimension.equals(Level.OVERWORLD)&&packet instanceof ClientboundLevelChunkWithLightPacket home&&home.getX()==0&&home.getZ()==0){
    homeColumnPackets++;dev.anya.storage.AnyaStorage.LOG.info("LIVE_APPLIED_HOME role={} count={} chunk_present={}",role(),homeColumnPackets,Minecraft.getInstance().level.hasChunkAt(new BlockPos(3,81,1)));
   }
   if(!dimension.equals(PocketService.DIMENSION))return;
   applied.merge(packet.getClass().getSimpleName(),1L,Long::sum);
   if(packet instanceof ClientboundLevelChunkWithLightPacket c){columns.add(ChunkPos.asLong(c.getX(),c.getZ()));
    Object data=c.getChunkData();for(var f:data.getClass().getDeclaredFields())if(List.class.isAssignableFrom(f.getType())){f.setAccessible(true);for(Object entry:(List<?>)f.get(data))for(var n:entry.getClass().getDeclaredFields())if(n.getType()==CompoundTag.class){n.setAccessible(true);tag((CompoundTag)n.get(entry));}}
   }else if(packet instanceof ClientboundForgetLevelChunkPacket f)columns.remove(f.pos().toLong());
   else if(packet instanceof ClientboundBlockEntityDataPacket be)tag(be.getTag());
  }catch(Throwable t){LiveTest.write("FAILED",Map.of("client",role(),"observation",t.toString()));Minecraft.getInstance().stop();}
 }
 static void inspect(int expected)throws Exception{
  var r=remote();int mask=0;for(int i=0;i<2;i++){var pos=new BlockPos(4096+i*512+6,65,4096+6);if(r!=null&&r.hasChunkAt(pos)){mask|=1<<i;var be=r.getBlockEntity(pos);if(be instanceof StorageEntity chest){for(int j=0;j<chest.getContainerSize();j++)check(chest.getItem(j).isEmpty(),"Live chunk update leaked inventory");check(chest.owner==null&&chest.pocketId==null&&chest.trusted.isEmpty()&&chest.memberPermissions.isEmpty(),"Live chunk update leaked private metadata");}}}
  check(mask==expected,"Applied destination footprint differs: expected="+expected+" actual="+mask+" columns="+columns.size());
  for(long k:columns){boolean allowed=false;for(int i=0;i<2;i++)if((expected&(1<<i))!=0&&Math.abs(ChunkPos.getX(k)-(256+i*32))<=2&&Math.abs(ChunkPos.getZ(k)-256)<=2)allowed=true;check(allowed,"Unexpected applied destination column "+new ChunkPos(k));}
  if(expected==0)check(columns.isEmpty(),"Unauthorized/closed client retained destination columns");
  LiveTest.write("client-"+role()+"-inspection-"+work.sequence()+".json",Map.of("expected_mask",expected,"actual_mask",mask,"current_columns",columns.size(),"received_counts",applied,"current_portals",portals.size()));
 }
 static void inventory()throws Exception{
  var ids=new HashSet<String>();var mods=new ArrayList<Map<String,String>>();for(var m:ModList.get().getMods()){ids.add(m.getModId());mods.add(Map.of("id",m.getModId(),"version",m.getVersion().toString()));}
  check(ids.containsAll(Set.of("sodium","iris","cloth_config","anyastorage","anyadurableprobe","anyaliveview","imm_ptl")),"Required runtime missing");
  Object shaders=Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPack").invoke(null);check(shaders instanceof Optional<?> o&&o.isEmpty(),"Shaders must stay disabled");
  LiveTest.write("runtime-mods-"+role()+".json",mods);
 }
 @SubscribeEvent public static void tick(ClientTickEvent.Post e){var mc=Minecraft.getInstance();age++;try{
  check(age<24000,"Client tick ceiling");if(!connecting&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){connecting=true;ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString("127.0.0.1:25579"),new ServerData("Disposable live-view acceptance","127.0.0.1:25579",ServerData.Type.OTHER),false,null);}
  if(mc.player==null||mc.level==null||mc.gameMode==null||mc.getOverlay()!=null)return;
  // Readiness gates a first interaction, not completion of an already-sent
  // command across a dimension transition. Never send that mutation again.
  if(ticks==0&&mc.screen instanceof ReceivingLevelScreen&&(work==null||!work.action().equals("return-ready")))return;
  if(!hello){inventory();hello=true;PacketDistributor.sendToServer(new TestWire.Signal(0,"ready"));}
  if(work==null)return;String[] words=work.action().split(" ");String action=words[0];
  if(ticks==0&&action.equals("block")){BlockPos pos=new BlockPos(Integer.parseInt(words[1]),Integer.parseInt(words[2]),Integer.parseInt(words[3]));check(++waited<240,"Readiness deadline before one use packet");if(!mc.level.hasChunkAt(pos)||!mc.player.canInteractWithBlock(pos,0))return;if(++stable<8)return;}
  ticks++;
  if(ticks==1){
   dev.anya.storage.AnyaStorage.LOG.info("LIVE_CLIENT_DISPATCH role={} sequence={} action={} dimension={}",role(),work.sequence(),work.action(),mc.level.dimension().location());
   if(action.equals("cmd")){mc.player.closeContainer();if(work.action().equals("cmd anya pocket exit"))homeColumnPacketsAtExit=homeColumnPackets;mc.player.connection.sendCommand(work.action().substring(4));}
   else if(action.equals("block")){var pos=new BlockPos(Integer.parseInt(words[1]),Integer.parseInt(words[2]),Integer.parseInt(words[3]));mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.UP,pos,false));}
   else if(action.equals("deny-items")){check(mc.player.containerMenu instanceof StoreMenu,"Expected Visit-only menu");var m=(StoreMenu)mc.player.containerMenu;check(m.privateView()&&m.resultCount()==0,"Visit-only menu leaked items");PacketDistributor.sendToServer(new Packets.Action(m.containerId,m.session(),m.revision(),"slot","",256));PacketDistributor.sendToServer(new Packets.Action(m.containerId,m.session(),m.revision(),"slot","",m.shownSlots()));}
   else if(action.equals("forge")){check(mc.player.containerMenu==mc.player.inventoryMenu,"View created an inventory menu");PacketDistributor.sendToServer(new Packets.Action(12345,17,0,"slot","",256));for(var entity:mc.level.entitiesForRendering())if(entity instanceof Portal portal&&PocketService.DIMENSION.equals(portal.getDestDim())){check(!portal.getTeleportable()&&!portal.isInteractable(),"Client portal unsafe");PacketDistributor.sendToServer(new ImmPtlNetworking.TeleportPacket(PortalAPI.clientDimKeyToInt(mc.level.dimension()),mc.player.getEyePosition(),portal.getUUID()));break;}}
  }
  if(action.equals("return-ready")){
   check(ticks<=800,"Returned client did not regain a usable home world within the separate readiness window");
   var pos=new BlockPos(3,81,1);
   check(mc.player.containerMenu==mc.player.inventoryMenu,"Return retained an unexpected storage menu");
   boolean home=mc.level.dimension().equals(Level.OVERWORLD)&&mc.player.level()==mc.level&&mc.level.hasChunkAt(pos)&&!(mc.screen instanceof ReceivingLevelScreen);
   if(ticks==1||ticks%20==0)dev.anya.storage.AnyaStorage.LOG.info("LIVE_RETURN_READINESS role={} ticks={} dimension={} home_chunk={} player_chunk={} screen={} position={}",role(),ticks,mc.level.dimension().location(),mc.level.hasChunkAt(pos),mc.level.hasChunkAt(mc.player.blockPosition()),mc.screen==null?"none":mc.screen.getClass().getSimpleName(),mc.player.position());
   if(!home){stable=0;return;}if(++stable<12)return;
   check(homeColumnPackets>homeColumnPacketsAtExit,"Return became ready without independently observed fresh home-column application");
   LiveTest.write("client-"+role()+"-returned-"+work.sequence()+".json",Map.of("dimension",mc.level.dimension().location().toString(),"home_chunk_loaded",true,"loading_screen",false,"position",mc.player.position().toString(),"home_column_packets_after_exit",homeColumnPackets-homeColumnPacketsAtExit));ack();return;
  }
  if(action.equals("inspect")){if(ticks<50)return;inspect(Integer.parseInt(words[1]));if(role().equals("Alpha")&&Integer.parseInt(words[1])==1)Screenshot.grab(mc.gameDirectory,"live-view-"+work.sequence()+".png",mc.getMainRenderTarget(),c->{});ack();}
  else if(ticks>=40){if(action.equals("deny-items"))mc.player.closeContainer();ack();}
 }catch(Throwable t){LiveTest.write("FAILED",Map.of("client",role(),"action",work==null?"startup":work.action(),"error",t.toString()));mc.stop();}}
}
