package dev.anya.storage.rendercheck;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.lang.reflect.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Collect the loader's selected modules, not guesses from nested archive versions. */
@EventBusSubscriber(modid=RendererCheck.ID,value=Dist.CLIENT)
public final class RendererClient {
    private static boolean connected,reported;
    private static int age,settled,stage;
    private static void require(boolean yes,String reason){if(!yes)throw new IllegalStateException(reason);}
    private static String sha(Path p)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));}
    private static void inventory(Minecraft mc)throws Exception {
        var mods=new ArrayList<Map<String,Object>>();
        for(var m:ModList.get().getMods()){
            var row=new LinkedHashMap<String,Object>();row.put("id",m.getModId());row.put("version",m.getVersion().toString());
            Path path=m.getOwningFile().getFile().getFilePath();row.put("file",path.toString());
            if(Files.isRegularFile(path)&&Files.size(path)<64L*1024*1024)row.put("sha256",sha(path));
            else row.put("hash_status","not a directly readable regular archive; see static nested provenance");
            mods.add(row);
        }
        mods.sort(Comparator.comparing(m->m.get("id").toString()));
        for(String id:List.of("sodium","iris","cloth_config","anyastorage","anyadurableprobe"))require(ModList.get().isLoaded(id),"Required module not loaded: "+id);
        for(String id:List.of("imm_ptl","immersive_portals_core","embeddium","oculus"))require(!ModList.get().isLoaded(id),"Unapproved or wrong baseline backend: "+id);
        var iris=Class.forName("net.irisshaders.iris.Iris");Object pack=iris.getMethod("getCurrentPack").invoke(null);
        require(pack instanceof Optional<?> o&&o.isEmpty(),"Shaders unexpectedly active");
        var fields=new ArrayList<Map<String,String>>();
        for(Class<?> cls=mc.levelRenderer.getClass();cls!=null;cls=cls.getSuperclass())for(Field f:cls.getDeclaredFields()){
            if(Modifier.isStatic(f.getModifiers()))continue;
            f.setAccessible(true);Object value=f.get(mc.levelRenderer);
            if(value!=null&&value.getClass().getName().startsWith("net.caffeinemc.mods.sodium"))fields.add(Map.of("field",f.getName(),"runtime_class",value.getClass().getName()));
        }
        RendererCheck.write("runtime-mods.json",mods);
        RendererCheck.write("backend.json",Map.of("sodium_level_renderer_fields",fields,"iris_current_pack",pack.toString(),"ce_loaded",false,"graphics_vendor",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR),"graphics_renderer",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER),"gl_version",org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VERSION),"level_renderer_interfaces",Arrays.stream(mc.levelRenderer.getClass().getInterfaces()).map(Class::getName).toList()));
        require(!fields.isEmpty(),"No actual Sodium renderer object found in transformed LevelRenderer");
        require(mods.stream().filter(m->m.get("id").toString().replace('-','_').equals("fabric_renderer_api_v1")).count()==1,"Expected exactly one resolved renderer API module");
    }
    private static void shot(Minecraft mc,String name)throws Exception {
        Screenshot.grab(mc.gameDirectory,name+".png",mc.getMainRenderTarget(),c->{});
        RendererCheck.write(name+"-captured",Map.of("age",age,"block",mc.level.getBlockState(RendererCheck.TARGET).toString()));
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post e){
        var mc=Minecraft.getInstance();age++;
        try {
            RendererCheck.control();require(age<7000,"Client tick ceiling");
            if(!connected&&mc.screen instanceof TitleScreen&&mc.getOverlay()==null){
                connected=true;ConnectScreen.startConnecting(mc.screen,mc,ServerAddress.parseString("127.0.0.1:25579"),new ServerData("Disposable renderer prerequisite","127.0.0.1:25579",ServerData.Type.OTHER),false,null);
            }
            if(mc.level==null||mc.player==null||mc.getOverlay()!=null||mc.screen instanceof ReceivingLevelScreen)return;
            if(mc.screen!=null)return;
            if(!mc.level.hasChunkAt(RendererCheck.TARGET))return;
            boolean right=mc.level.getBlockState(RendererCheck.TARGET).is(stage==0?Blocks.RED_CONCRETE:Blocks.LIME_CONCRETE);
            if(!right){settled=0;return;}if(++settled<50)return;
            if(!reported){inventory(mc);reported=true;}
            if(stage==0){shot(mc,"red");stage=1;settled=0;}
            else if(stage==1){shot(mc,"green");stage=2;settled=0;}
            else if(stage==2&&settled>60){RendererCheck.write("client-finished",Map.of("result","BASELINE_DIRECT_WORLD_ONLY","ce_live_frame","NOT_RUN_APPROVAL_REQUIRED"));mc.stop();}
        }catch(Throwable t){RendererCheck.fail(t);mc.stop();}
    }
}
