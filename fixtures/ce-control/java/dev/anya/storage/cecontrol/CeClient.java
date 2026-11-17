package dev.anya.storage.cecontrol;
import net.minecraft.client.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
/** Actual client renderer and changing through-portal framebuffer observation. */
@EventBusSubscriber(modid=CeControl.ID,value=Dist.CLIENT)
public final class CeClient {
 private static boolean connected,reported,capture;private static int age,settled,stage,finishAt;
 private static final String[] NAMES={"portal-red","portal-green","occluded-blue","restored-green","removed-blue"};
 private static final Set<String> ALLOWED=Set.of("minecraft","neoforge","sodium","iris","cloth_config","anyastorage","anyadurableprobe","anyacecontrol","fabric_api_base","fabric_block_view_api_v2","fabric_renderer_api_v1","fabric_rendering_data_attachment_v1","imm_ptl","immersive_portals_core","q_misc_util");
 private static void require(boolean yes,String reason){CeControl.require(yes,reason);}
 private static Object staticField(String cls,String name)throws Exception{return Class.forName(cls).getField(name).get(null);}
 private static ClientLevel remote()throws Exception{
  var f=Class.forName("qouteall.imm_ptl.core.ClientWorldLoader").getDeclaredField("CLIENT_WORLD_MAP");f.setAccessible(true);return (ClientLevel)((Map<?,?>)f.get(null)).get(CeControl.DEST);
 }
 private static void inventory(Minecraft mc)throws Exception{
  var mods=new ArrayList<Map<String,Object>>();var ids=new HashSet<String>();
  for(var m:ModList.get().getMods()){
   String id=m.getModId();require(ALLOWED.contains(id),"STOP unreviewed runtime module "+id);ids.add(id);var row=new LinkedHashMap<String,Object>();row.put("id",id);row.put("version",m.getVersion().toString());var p=m.getOwningFile().getFile().getFilePath();row.put("path",p.toString());
   if(Files.isRegularFile(p)&&Files.size(p)<64L*1024*1024)row.put("sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p))));mods.add(row);
  }
  require(ids.containsAll(Set.of("sodium","iris","cloth_config","anyastorage","anyadurableprobe","imm_ptl","immersive_portals_core","q_misc_util")),"Required runtime module missing");
  Object pack=Class.forName("net.irisshaders.iris.Iris").getMethod("getCurrentPack").invoke(null);require(pack instanceof Optional<?> p&&p.isEmpty(),"Shaders unexpectedly enabled");
  var fields=new ArrayList<Map<String,String>>();for(Class<?> c=mc.levelRenderer.getClass();c!=null;c=c.getSuperclass())for(var f:c.getDeclaredFields())if(!Modifier.isStatic(f.getModifiers())){f.setAccessible(true);Object v=f.get(mc.levelRenderer);if(v!=null&&v.getClass().getName().startsWith("net.caffeinemc.mods.sodium"))fields.add(Map.of("field",f.getName(),"class",v.getClass().getName()));}
  require(!fields.isEmpty(),"Sodium renderer object missing");var interfaces=Arrays.stream(mc.levelRenderer.getClass().getInterfaces()).map(Class::getName).toList();require(interfaces.stream().anyMatch(n->n.startsWith("qouteall.imm_ptl")),"CE LevelRenderer transformation absent");
  CeControl.write("runtime-mods.json",mods);CeControl.write("backend.json",Map.of("sodium_fields",fields,"level_renderer_interfaces",interfaces,"iris_current_pack",pack.toString(),"ce_expected_version","6.1.4","graphics_vendor",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR),"graphics_renderer",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER),"gl_version",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION),"target_gpu_proof",false));
 }
 private static boolean ready(Minecraft mc)throws Exception{
  require(mc.level.dimension().equals(Level.OVERWORLD),"Fixture player unexpectedly crossed a portal");
  require(mc.level.getBlockState(CeControl.TARGET).is(Blocks.BLUE_CONCRETE),"Source control wall changed");
  if(stage==2)return mc.level.getBlockState(CeControl.BARRIER).is(Blocks.BLUE_CONCRETE);
  if(stage==4)return Files.exists(CeControl.control().resolve("portal-removed"));
  if(stage==3&&!mc.level.getBlockState(CeControl.BARRIER).isAir())return false;
  var remote=remote();return remote!=null&&remote.hasChunkAt(CeControl.TARGET)&&remote.getBlockState(CeControl.TARGET).is(stage==0?Blocks.RED_CONCRETE:Blocks.LIME_CONCRETE);
 }
 @SubscribeEvent public static void tick(ClientTickEvent.Post e){var mc=Minecraft.getInstance();age++;try{
  CeControl.control();require(age<9000,"Client tick deadline");
  if(!connected&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){connected=true;ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString("127.0.0.1:25579"),new ServerData("Disposable approved CE control","127.0.0.1:25579",ServerData.Type.OTHER),false,null);}
  if(stage>=NAMES.length){if(age-finishAt>40){CeControl.write("client-finished",Map.of("result","THROUGH_PORTAL_AND_OCCLUSION_CONTROL","client_remained_in","minecraft:overworld","not_permission_adapter",true));mc.stop();}return;}
  if(mc.level==null||mc.player==null||mc.getOverlay()!=null||mc.screen!=null||!mc.level.hasChunkAt(CeControl.TARGET))return;
  if(!ready(mc)){settled=0;return;}if(!reported){inventory(mc);reported=true;}
  if(++settled>=60)capture=true;
 }catch(Throwable t){CeControl.fail(t);mc.stop();}}
 @SubscribeEvent public static void frame(RenderFrameEvent.Post e){if(!capture)return;var mc=Minecraft.getInstance();try{
  if(mc.level==null||!ready(mc))return;
  var dims=(Set<?>)staticField("qouteall.imm_ptl.core.render.context_management.RenderStates","renderedDimensions");int portals=((Number)staticField("qouteall.imm_ptl.core.render.context_management.RenderStates","portalsRenderedThisFrame")).intValue();
  boolean positive=stage==0||stage==1||stage==3;
  if(positive)require(portals>0&&dims.contains(CeControl.DEST),"No actual destination render in positive frame");
  String name=NAMES[stage];Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),ignored->{});
  CeControl.write(name+"-captured",Map.of("age",age,"portals_rendered",portals,"rendered_dimensions",dims.stream().map(Object::toString).toList(),"source_dimension",mc.level.dimension().location().toString(),"source_control_block",mc.level.getBlockState(CeControl.TARGET).toString(),"positive_through_portal",positive));
  stage++;settled=0;capture=false;finishAt=age;
 }catch(Throwable t){capture=false;CeControl.fail(t);mc.stop();}}
}
