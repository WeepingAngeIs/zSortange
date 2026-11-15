package dev.anya.storage.appearance;

import dev.anya.storage.*;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

@EventBusSubscriber(modid=AnyaStorage.ID,bus=EventBusSubscriber.Bus.MOD,value=Dist.CLIENT)
public final class MoteParticles {
    @SubscribeEvent public static void register(RegisterParticleProvidersEvent event){
        event.registerSpriteSet(Content.LEAF_MOTE.get(),sprites->(options,level,x,y,z,dx,dy,dz)->new Mote(level,x,y,z,sprites,false));
        event.registerSpriteSet(Content.CHERRY_MOTE.get(),sprites->(options,level,x,y,z,dx,dy,dz)->new Mote(level,x,y,z,sprites,false));
        event.registerSpriteSet(Content.STAR_MOTE.get(),sprites->(options,level,x,y,z,dx,dy,dz)->new Mote(level,x,y,z,sprites,true));
    }
    private static final class Mote extends TextureSheetParticle {
        private final boolean star;
        Mote(ClientLevel level,double x,double y,double z,SpriteSet sprites,boolean star){super(level,x,y,z);this.star=star;pickSprite(sprites);lifetime=28;quadSize=star?.055f:.075f;gravity=star?0:.025f;friction=.94f;hasPhysics=false;yd=star?.006:-.02;alpha=.8f;}
        @Override public void tick(){super.tick();xd=Math.sin(age*.3)*.006;zd=Math.cos(age*.3)*.004;alpha=Math.max(0,(1-age/(float)lifetime)*.8f);roll=star?0:(float)Math.sin(age*.2)*.6f;}
        @Override public ParticleRenderType getRenderType(){return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;}
    }
}
