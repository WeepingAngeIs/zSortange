package dev.anya.storage.appearance;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import dev.anya.storage.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.*;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.*;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.*;
import net.minecraft.world.item.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.*;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraft.client.renderer.item.ItemProperties;
import java.nio.file.*;
import java.util.*;

@EventBusSubscriber(modid=AnyaStorage.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class ClientLooks {
    private static final boolean FIGURA = net.neoforged.fml.ModList.get().isLoaded("figura");
    public static final List<String> TEXTURES;
    static{List<String> t=new ArrayList<>(List.of("plain","stone","diamond","magma","water"));Looks.LEAVES.forEach(s->t.add("leaf_"+s));Looks.SKINS.stream().filter(s->!s.equals("elemental")).forEach(t::add);TEXTURES=List.copyOf(t);}
    public static final class Options {public boolean particles=true,images=true,models=true;public int particleBudget=4;}
    public static Options options=new Options();
    private static final Map<Integer,ExpansionPackets.Look> worn=new HashMap<>();
    private static Object previousLevel;
    private static final Map<String,ItemStack> shells=new HashMap<>();
    @SubscribeEvent public static void setup(FMLClientSetupEvent event){event.enqueueWork(()->{
        Path file=FMLPaths.CONFIGDIR.get().resolve("anya-storage-plus-client.json");
        try{if(Files.isRegularFile(file)&&Files.size(file)<=4096){Options loaded=Config.JSON.fromJson(Files.readString(file),Options.class);if(loaded!=null)options=loaded;}}catch(Exception ignored){}
        options.particleBudget=Math.max(0,Math.min(8,options.particleBudget));
        TabletThreatCue.receiver = (connection, cue) -> {
            var current = Minecraft.getInstance().getConnection();
            if (current != null && current.getConnection() == connection) TabletThreat.receive(cue.octant());
        };
        for (var tablet : List.of(Content.CUPID_TABLET.get(), Content.CREATIVE_TABLET.get()))
            ItemProperties.register(tablet,ExpansionPackets.id("cupid_face"),
                    (stack,level,living,seed)->TabletFaces.face(stack).property());
        ItemProperties.register(Content.BACKPACK.get(),ExpansionPackets.id("appearance"),(stack,level,living,seed)->Math.max(0,TEXTURES.indexOf(Looks.of(stack).texture()))+1);
        ExpansionPackets.lookReceiver=look->{if(look.entity()<0){if(Minecraft.getInstance().player!=null&&Minecraft.getInstance().player.containerMenu.containerId==look.menu())ClientInteriors.look(look);}else if(TEXTURES.contains(look.texture())||look.texture().isEmpty()){if(worn.size()>256)worn.clear();worn.put(look.entity(),look);}};
        ExpansionPackets.imageReceiver=ClientInteriors::accept;
        dev.anya.storage.guide.GuidePacket.receiver=packet->Minecraft.getInstance().setScreen(new dev.anya.storage.guide.GuideScreen(packet));
    });}
    public static void saveOptions(){try{Files.writeString(FMLPaths.CONFIGDIR.get().resolve("anya-storage-plus-client.json"),Config.JSON.toJson(options));}catch(Exception ignored){}}
    @SubscribeEvent @SuppressWarnings({"rawtypes","unchecked"}) public static void layers(EntityRenderersEvent.AddLayers event){
        for(var skin:event.getSkins()){LivingEntityRenderer renderer=event.getSkin(skin);if(renderer!=null)renderer.addLayer(new Layer(renderer));}
        for(EntityType<?> type:List.of(EntityType.ZOMBIE,EntityType.HUSK,EntityType.DROWNED,EntityType.SKELETON,EntityType.STRAY,EntityType.WITHER_SKELETON)){
            EntityRenderer<?> renderer=event.getRenderer(type);if(renderer instanceof LivingEntityRenderer living)living.addLayer(new Layer(living));
        }
    }
    /** Read-only render-only shell. Never pass to a menu, mutation or gameplay inventory. */
    public static ItemStack shell(String texture){
        if(!TEXTURES.contains(texture))texture="plain";
        ItemStack cached=shells.get(texture);if(cached!=null)return cached;
        ItemStack s=new ItemStack(Content.BACKPACK.get());CompoundTag data=new CompoundTag();
        if(texture.startsWith("leaf_")){data.putString("Element","leaf");data.putString("LeafSpecies",texture.substring(5));}
        else if(Looks.SKINS.contains(texture))data.putString("Skin",texture);else data.putString("Element",texture);
        BagInventory.data(s,data);shells.put(texture,s);return s;
    }
    private static final class Layer<T extends LivingEntity,M extends EntityModel<T>> extends RenderLayer<T,M> {
        Layer(RenderLayerParent<T,M> parent){super(parent);}
        @Override public void render(PoseStack pose,MultiBufferSource buffers,int light,T entity,float walk,float amount,float partial,float age,float yaw,float pitch){
            ExpansionPackets.Look look=worn.get(entity.getId());
            if(!options.models||entity.isInvisible()||look==null||!look.uuid().equals(entity.getUUID())||look.texture().isEmpty()||!(getParentModel() instanceof HumanoidModel<?> humanoid))return;
            if(Minecraft.getInstance().getCameraEntity()!=null&&entity.distanceToSqr(Minecraft.getInstance().getCameraEntity())>48*48)return;
            // Humanoid +Y points down and +Z points behind the torso. Preserve +Z so the pocket faces away from the body.
            pose.pushPose();
            try {
                if (!(FIGURA && entity instanceof AbstractClientPlayer player && FiguraBackFit.apply(player, pose))) {
                    humanoid.body.translateAndRotate(pose);
                    pose.translate(0, .29, .29);
                    pose.mulPose(Axis.ZP.rotationDegrees(180));
                    pose.scale(.72f, .72f, .72f);
                }
                Minecraft.getInstance().getItemRenderer().renderStatic(shell(look.texture()),ItemDisplayContext.NONE,light,OverlayTexture.NO_OVERLAY,pose,buffers,entity.level(),entity.getId());
            } finally {
                pose.popPose();
            }
        }
    }
    @EventBusSubscriber(modid=AnyaStorage.ID,value=Dist.CLIENT)
    public static final class Events {
        @SubscribeEvent public static void beforePlayer(RenderPlayerEvent.Pre event) {
            if (!FIGURA) return;
            FiguraBackFit.end();
            if (!(event.getEntity() instanceof AbstractClientPlayer player) || !options.models || player.isInvisible()) return;
            var look = worn.get(player.getId());
            if (look != null && look.uuid().equals(player.getUUID()) && !look.texture().isEmpty())
                FiguraBackFit.begin(player, event.getPoseStack(), event.getMultiBufferSource());
        }
        @SubscribeEvent public static void afterPlayer(RenderPlayerEvent.Post event) {
            if (FIGURA) FiguraBackFit.end();
        }
        @SubscribeEvent public static void logout(ClientPlayerNetworkEvent.LoggingOut event){worn.clear();previousLevel=null;ClientInteriors.clear();if(FIGURA)FiguraBackFit.clear();}
        @SubscribeEvent public static void tick(ClientTickEvent.Post event){
            var mc=Minecraft.getInstance();if(mc.level!=previousLevel){previousLevel=mc.level;worn.clear();ClientInteriors.clear();TabletThreat.clear();}
            if(mc.level==null||mc.player==null)return;
            worn.entrySet().removeIf(e->mc.level.getEntity(e.getKey())==null);
            if(mc.player.tickCount%20!=0||!options.particles||mc.isPaused())return;
            int budget=Math.min(8,Math.max(0,options.particleBudget));
            for(var e:worn.entrySet()){
                if(budget<=0)break;var look=e.getValue();Entity entity=mc.level.getEntity(e.getKey());
                if(entity==null||entity.isInvisible()||!entity.getUUID().equals(look.uuid())||entity.distanceToSqr(mc.player)>24*24||look.element().equals("disabled")||look.texture().isEmpty())continue;
                if(entity==mc.player&&mc.options.getCameraType().isFirstPerson())continue;
                boolean walking=entity.getDeltaMovement().horizontalDistanceSqr()>.001;
                var particle=look.texture().contains("cherry")?Content.CHERRY_MOTE.get():look.texture().contains("leaf")||look.texture().equals("oak")||look.texture().equals("dark_oak")?Content.LEAF_MOTE.get():look.texture().equals("galaxy")?Content.STAR_MOTE.get():look.element().equals("magma")?net.minecraft.core.particles.ParticleTypes.WHITE_ASH:look.element().equals("water")?net.minecraft.core.particles.ParticleTypes.BUBBLE_POP:null;
                if(particle==null||(!walking&&!look.texture().equals("galaxy")))continue;
                double angle=Math.toRadians(entity.getYRot());double x=entity.getX()+Math.sin(angle)*.3,z=entity.getZ()-Math.cos(angle)*.3;
                mc.level.addParticle(particle,x,entity.getY()+1,z,0,-.005,0);budget--;
            }
        }
    }
}
