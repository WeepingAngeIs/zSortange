package dev.anya.storage.pocket;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/** The Overworld celestial plane in camera coordinates. No destination coordinates leave the server. */
public record SourceSky(int background, float clarity, float angle, int phase) {
    static final int SUN=0x4000000, MOON=0x5000000;
    static SourceSky observe(ServerLevel level,BlockPos camera){
        var type=level.dimensionType();
        if(!type.hasSkyLight() || type.hasCeiling() || !type.effectsLocation().toString().equals("minecraft:overworld"))return null;
        var color=Vec3.fromRGB24(level.getBiome(camera).value().getSkyColor());
        float angle=level.getSunAngle(1),rain=level.getRainLevel(1),storm=level.getThunderLevel(1);
        color=color.scale(Mth.clamp(2*Mth.cos(angle)+.5f,0,1));
        color=weather(color,rain,.6);color=weather(color,storm,.2);
        int rgb=((int)(color.x*255)<<16)|((int)(color.y*255)<<8)|(int)(color.z*255);
        return new SourceSky(rgb,1-rain,angle,level.getMoonPhase());
    }
    private static Vec3 weather(Vec3 rgb,float amount,double intensity){
        double grey=(.3*rgb.x+.59*rgb.y+.11*rgb.z)*intensity;
        return rgb.lerp(new Vec3(grey,grey,grey),amount*.75);
    }
    int direction(Vec3 ray){
        // Invert the game's celestial-plane yaw and time-of-day rotation.
        double c=Math.cos(angle),s=Math.sin(angle);
        double x=ray.z,y=c*ray.y-s*ray.x,z=-s*ray.y-c*ray.x;
        boolean sun=y>0;double half=(sun?.30:.20)*Math.abs(y);
        if(half<=0 || Math.abs(x)>=half || Math.abs(z)>=half)return CameraFeed.SKY;
        int u=Mth.clamp((int)(128*(1+(sun?x:-x)/half)),0,255);
        int v=Mth.clamp((int)(128*(1+z/half)),0,255);
        return (sun?SUN:MOON)|(phase<<16)|(v<<8)|u;
    }
}
