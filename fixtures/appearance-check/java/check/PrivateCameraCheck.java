package check;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** A fresh private integrated world only. Reads a local avatar; never connects to a server. */
@Mod("cameralocalcheck")
@EventBusSubscriber(modid="cameralocalcheck",value=Dist.CLIENT)
public final class PrivateCameraCheck {
    private static final String WORLD="IsolatedAppearance";
    private static final BlockPos MONITOR=new BlockPos(0,82,-5), CAMERA=new BlockPos(1,82,-3);
    private static Path output, avatar;
    private static int phase, ticks, since;
    private static boolean active, starting, finished, ordered;
    private static volatile boolean installed;
    private static volatile Throwable setupError;

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc=Minecraft.getInstance();
        if(finished)return;
        try {
            if(!active) {
                if(!(mc.screen instanceof TitleScreen)||mc.getOverlay()!=null)return;
                String raw=System.getProperty("camera.appearance.root","");
                if(raw.isBlank()){finished=true;return;}
                Path root=Path.of(raw).toRealPath();
                if(!root.resolve("game").equals(mc.gameDirectory.toPath().toRealPath())
                        ||!Files.isRegularFile(root.resolve("private-check.marker"))
                        ||Files.exists(root.resolve("game/saves/"+WORLD))||mc.getConnection()!=null)
                    throw new IllegalStateException("Expected a marked, fresh private game directory");
                avatar=root.resolve("avatar").toRealPath();
                if(!avatar.startsWith(root)||!Files.isRegularFile(avatar.resolve("avatar.json")))
                    throw new IllegalArgumentException("Missing unpacked local avatar");
                output=Files.createDirectory(root.resolve("evidence"));
                active=true;mc.options.pauseOnLostFocus=false;mc.options.hideGui=true;
                mc.options.setCameraType(CameraType.FIRST_PERSON);
                var settings=new LevelSettings(WORLD,GameType.CREATIVE,false,Difficulty.PEACEFUL,false,
                        new GameRules(),WorldDataConfiguration.DEFAULT);
                starting=true;
                mc.createWorldOpenFlows().createFreshLevel(WORLD,settings,new WorldOptions(863L,false,false),
                        regs->regs.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT)
                                .value().createWorldDimensions(),mc.screen);
                return;
            }
            if(!starting || mc.player==null || mc.screen!=null)return;
            var server=mc.getSingleplayerServer();
            if(server==null||server.isPublished())throw new IllegalStateException("Requires a private integrated server");
            if(!ordered){
                UUID owner=mc.player.getUUID();ordered=true;
                server.execute(()->{
                    try { setup(server.getPlayerList().getPlayer(owner));installed=true; }
                    catch(Throwable error){setupError=error;}
                });
            }
            if(setupError!=null)throw new IllegalStateException("Disposable scene setup failed",setupError);
            if(++ticks>1800)throw new IllegalStateException("Timed out during appearance phase "+phase);
        }catch(Exception error){fail(mc,error);}
    }
    private static void setup(ServerPlayer p)throws Exception {
        if(p==null)throw new IllegalStateException("Missing test player");
        var level=p.serverLevel();
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,p.getServer());
        level.setDayTime(6000);
        for(int x=-5;x<=5;x++)for(int z=-6;z<10;z++)
            level.setBlockAndUpdate(new BlockPos(x,80,z),Blocks.SMOOTH_QUARTZ.defaultBlockState());
        for(int x=-2;x<=2;x++)for(int y=80;y<85;y++)
            level.setBlockAndUpdate(new BlockPos(x,y,-6),Blocks.GRAY_CONCRETE.defaultBlockState());
        level.setBlockAndUpdate(CAMERA.north(),Blocks.GRAY_CONCRETE.defaultBlockState());
        p.connection.teleport(.5,81,.5,180,0);
        var item=BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath("anyastorage","cupid_tablet"));
        p.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(item));p.setShiftKeyDown(true);
        for(var point:List.of(CAMERA,MONITOR)){
            String id=point.equals(CAMERA)?"outdoor_camera":"wall_monitor";
            Block block=BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("anyastorage",id));
            var state=block.defaultBlockState().setValue(BlockStateProperties.FACING,Direction.SOUTH);
            level.setBlockAndUpdate(point,state);
            block.setPlacedBy(level,point,state,p,new ItemStack(block));
            var device=level.getBlockEntity(point);
            var use=Class.forName("dev.anya.storage.pocket.CameraDevices").getDeclaredMethod("use",ServerPlayer.class,device.getClass(),InteractionHand.class);
            use.setAccessible(true);
            if(!Boolean.TRUE.equals(use.invoke(null,p,device,InteractionHand.MAIN_HAND)))
                throw new IllegalStateException("Test owner could not link device");
        }
        p.setShiftKeyDown(false);p.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
        var d=level.getBlockEntity(MONITOR);
        var size=Class.forName("dev.anya.storage.pocket.WallMonitorBlock")
                .getDeclaredMethod("resize",ServerPlayer.class,d.getClass(),int.class,int.class,boolean.class);
        size.setAccessible(true);size.invoke(null,p,d,3,3,false);
    }
    @SubscribeEvent public static void frame(RenderGuiEvent.Post event){
        render();
    }
    @SubscribeEvent(priority=net.neoforged.bus.api.EventPriority.LOWEST)
    public static void worldFrame(net.neoforged.neoforge.client.event.RenderLevelStageEvent event){
        if(event.getStage()==net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_LEVEL)render();
    }
    private static void render(){
        var mc=Minecraft.getInstance();
        if(!active || !installed || finished || mc.player==null || mc.screen!=null || ticks-since<48)return;
        try{
            if(phase==2){
                var loaded=loadedAvatar(mc);
                if(loaded==null)return;
                photo(mc,"native-avatar");Files.writeString(output.resolve("completed.txt"),
                        "Local model loaded with renderer. Captured native view and two independent monitor frames. Compare visual evidence.\n");
                finished=true;mc.stop();return;
            }
            if(phase==1 && loadedAvatar(mc)==null)return;
            var picture=Class.forName("dev.anya.storage.pocket.CameraPicture");
            var rendered=field(picture,"rendered",null);
            if(rendered==null||((List<?>)field(rendered.getClass(),"actors",rendered)).isEmpty())return;
            var texture=(DynamicTexture)field(picture,"texture",null);
            if(texture==null||texture.getPixels()==null)return;
            String name=phase==0?"vanilla":"avatar";
            texture.getPixels().writeToFile(output.resolve(name+"-lens.png"));
            photo(mc,name+"-on-monitor");
            if(phase==0){
                Class.forName("org.figuramc.figura.avatar.AvatarManager").getMethod("loadLocalAvatar",Path.class).invoke(null,avatar);
            }else mc.options.setCameraType(CameraType.THIRD_PERSON_FRONT);
            since=ticks;phase++;
        }catch(Exception error){fail(mc,error);}
    }
    private static Object loadedAvatar(Minecraft mc)throws Exception{
        var manager=Class.forName("org.figuramc.figura.avatar.AvatarManager");
        var model=manager.getMethod("getLoadedAvatar",UUID.class).invoke(null,mc.player.getUUID());
        return model!=null && model.getClass().getField("loaded").getBoolean(model)
                && model.getClass().getField("renderer").get(model)!=null?model:null;
    }
    private static Object field(Class<?> cls,String name,Object instance)throws Exception{
        var f=cls.getDeclaredField(name);f.setAccessible(true);return f.get(instance);
    }
    private static void photo(Minecraft mc,String name)throws Exception{
        try(var image=Screenshot.takeScreenshot(mc.getMainRenderTarget())){image.writeToFile(output.resolve(name+".png"));}
    }
    private static void fail(Minecraft mc,Exception error){
        finished=true;
        if(output!=null)try{Files.writeString(output.resolve("failure.txt"),error.toString()+"\n"+String.valueOf(error.getCause()));}
        catch(java.io.IOException ignored){}
        if(active)mc.stop();else throw new IllegalStateException(error);
    }
}
