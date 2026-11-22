package dev.anya.storage.pocket;

import dev.anya.storage.ExpansionPackets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.Connection;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

/** A visible-surface observation local to the lens. The only absolute position is the viewer's monitor. */
public record CameraScene(BlockPos pos, long time, int weatherTick, SourceSky sky, float rain, float thunder,
                          Vec3 eye, Vec3 forward, Vec3 right, Vec3 up,
                          List<Surface> surfaces, List<Actor> actors, int[] cells, int[] backdrop, int[] edgeBackdrop) implements CustomPacketPayload {
    public static final int SIDE = 32, ATMOSPHERE = Short.MAX_VALUE;
    static final int MAX_SURFACES = 512, MAX_ACTORS = 6, MAX_EDGE_CELLS = 48;
    public static final Type<CameraScene> TYPE = new Type<>(ExpansionPackets.id("camera_scene_v3"));
    public static BiConsumer<Connection, CameraScene> receiver = (connection, scene) -> {};

    public record Surface(int stateId, int x, int y, int z, Direction face, float plane,
                          int skyLight, int blockLight, int tint) {}
    public record Actor(int typeId, Vec3 feet, float yaw, float headYaw, float pitch, float phase, float speed,
                        int skyLight, int blockLight) {}

    static boolean adjacentOnPlane(Surface a, Surface b) {
        int dx=a.x-b.x, dy=a.y-b.y, dz=a.z-b.z;
        return a.face==b.face && a.plane==b.plane && a.face.getAxis().choose(dx,dy,dz)==0
                && Math.abs(dx)<=1 && Math.abs(dy)<=1 && Math.abs(dz)<=1;
    }

    /** Unit-face rectangles must share a line segment, not merely a corner or projected pixels. */
    static boolean sharesEdge(Surface a, Surface b) {
        Direction.Axis aa=a.face.getAxis(), ba=b.face.getAxis();
        if(aa==ba || !outwardBoundary(a) || !outwardBoundary(b))return false;
        int bp=aa.choose(b.x,b.y,b.z), ap=ba.choose(a.x,a.y,a.z);
        if((a.plane!=bp && a.plane!=bp+1) || (b.plane!=ap && b.plane!=ap+1))return false;
        if(aa!=Direction.Axis.X && ba!=Direction.Axis.X)return a.x==b.x;
        if(aa!=Direction.Axis.Y && ba!=Direction.Axis.Y)return a.y==b.y;
        return a.z==b.z;
    }
    private static boolean outwardBoundary(Surface s) {
        int origin=s.face.getAxis().choose(s.x,s.y,s.z);
        return s.plane==origin+(s.face.getAxisDirection()==Direction.AxisDirection.POSITIVE?1:0);
    }

    @Override public Type<CameraScene> type() { return TYPE; }

    static CameraScene empty(BlockPos monitor) {
        return new CameraScene(monitor, -1, 0, null, 0, 0, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO, Vec3.ZERO,
                List.of(), List.of(), new int[0], new int[0], new int[0]);
    }

    static void register(RegisterPayloadHandlersEvent event) {
        StreamCodec<RegistryFriendlyByteBuf, CameraScene> codec = StreamCodec.ofMember(CameraScene::write, CameraScene::read);
        event.registrar("sharpcamera3").optional().playToClient(TYPE, codec, (scene,ctx) -> receiver.accept(ctx.connection(),scene));
    }

    private void write(RegistryFriendlyByteBuf out) {
        out.writeBlockPos(pos);
        out.writeLong(time);
        if (time < 0) return;
        out.writeShort(weatherTick);
        out.writeBoolean(sky != null);
        if (sky != null) {
            out.writeInt(sky.background()); out.writeFloat(sky.clarity());
            out.writeFloat(sky.angle()); out.writeByte(sky.phase());
        }
        out.writeFloat(rain); out.writeFloat(thunder);
        vector(out,eye); vector(out,forward); vector(out,right); vector(out,up);
        out.writeVarInt(surfaces.size());
        for (Surface face : surfaces) {
            out.writeVarInt(face.stateId);
            out.writeByte(face.x); out.writeByte(face.y); out.writeByte(face.z);
            out.writeByte(face.face.get3DDataValue()); out.writeFloat(face.plane);
            out.writeByte(face.skyLight << 4 | face.blockLight); out.writeInt(face.tint);
        }
        out.writeByte(actors.size());
        for (Actor actor : actors) {
            out.writeVarInt(actor.typeId); vector(out,actor.feet);
            out.writeFloat(actor.yaw); out.writeFloat(actor.headYaw); out.writeFloat(actor.pitch); out.writeFloat(actor.phase); out.writeFloat(actor.speed);
            out.writeByte(actor.skyLight << 4 | actor.blockLight);
        }
        for (int i=0;i<cells.length;i++) {
            out.writeShort(cells[i]);
            if(cells[i]<0) out.writeShort(backdrop[i]);
        }
        // Sparse by angular cell. Four signed refs at most per cell, ordered TL,TR,BL,BR.
        int refined=0;
        for(int i=0;i<cells.length;i++)if(hasEdgeProof(edgeBackdrop,i))refined++;
        out.writeByte(refined);
        for(int i=0;i<cells.length;i++)if(hasEdgeProof(edgeBackdrop,i)){
            out.writeShort(i);
            for(int q=0;q<4;q++)out.writeShort(edgeBackdrop[4*i+q]);
        }
    }

    private static boolean hasEdgeProof(int[] refs,int cell) {
        int i=cell*4;
        return refs[i]!=0 || refs[i+1]!=0 || refs[i+2]!=0 || refs[i+3]!=0;
    }

    private static CameraScene read(RegistryFriendlyByteBuf in) {
        BlockPos monitor = in.readBlockPos(); long time = in.readLong();
        if (time < 0) return empty(monitor);
        int weatherTick=in.readUnsignedShort();
        SourceSky sky = in.readBoolean() ? new SourceSky(in.readInt() & 0xffffff, bounded(in.readFloat(),0,1),
                bounded(in.readFloat(),-7,7), bounded(in.readUnsignedByte(),7)) : null;
        float rain=bounded(in.readFloat(),0,1), thunder=bounded(in.readFloat(),0,1);
        Vec3 eye=vector(in,0,1), forward=vector(in,-1,1), right=vector(in,-1,1), up=vector(in,-1,1);
        int count=bounded(in.readVarInt(),MAX_SURFACES);
        List<Surface> surfaces=new ArrayList<>(count);
        for (int i=0;i<count;i++) {
            int id=bounded(in.readVarInt(),Integer.MAX_VALUE);
            int x=in.readByte(),y=in.readByte(),z=in.readByte();
            if(Math.abs(x)>25 || Math.abs(y)>25 || Math.abs(z)>25) throw new IllegalArgumentException("Face outside camera range");
            Direction normal=Direction.from3DDataValue(bounded(in.readUnsignedByte(),5));
            float plane=bounded(in.readFloat(),-26,26); int light=in.readUnsignedByte();
            surfaces.add(new Surface(id,x,y,z,normal,plane,light>>>4,light&15,in.readInt()&0xffffff));
        }
        count=bounded(in.readUnsignedByte(),MAX_ACTORS);
        List<Actor> actors=new ArrayList<>(count);
        for(int i=0;i<count;i++) {
            int id=bounded(in.readVarInt(),Integer.MAX_VALUE);
            Vec3 feet=vector(in,-26,26);
            float yaw=bounded(in.readFloat(),-180,180),head=bounded(in.readFloat(),-180,180);
            float pitch=bounded(in.readFloat(),-180,180),phase=bounded(in.readFloat(),0,256),speed=bounded(in.readFloat(),0,1);
            int light=in.readUnsignedByte();
            actors.add(new Actor(id,feet,yaw,head,pitch,phase,speed,light>>>4,light&15));
        }
        int[] cells=new int[SIDE*SIDE], backdrop=new int[SIDE*SIDE];
        for(int i=0;i<cells.length;i++) {
            int cell=in.readShort();
            if(cell != ATMOSPHERE && (cell < -actors.size() || cell >= 2+surfaces.size())) throw new IllegalArgumentException("Unknown camera cell");
            cells[i]=cell;
            if(cell<0) {
                int back=in.readUnsignedShort();
                backdrop[i]=back==ATMOSPHERE?back:bounded(back,surfaces.size()+1);
            }
        }
        int[] edge=new int[cells.length*4];
        int refined=bounded(in.readUnsignedByte(),MAX_EDGE_CELLS), previous=-1;
        for(int n=0;n<refined;n++){
            int i=bounded(in.readUnsignedShort(),cells.length-1);
            int base=cells[i]<0?backdrop[i]:cells[i];
            if(i<=previous || base<2 || base==ATMOSPHERE)throw new IllegalArgumentException("Unexpected camera edge");
            previous=i;
            for(int q=0;q<4;q++){
                int code=in.readShort();
                if(code< -1 || code==1 || code>=surfaces.size()+2)throw new IllegalArgumentException("Unknown edge surface");
                edge[4*i+q]=code;
            }
        }
        return new CameraScene(monitor,time,weatherTick,sky,rain,thunder,eye,forward,right,up,List.copyOf(surfaces),List.copyOf(actors),cells,backdrop,edge);
    }

    private static int bounded(int value,int max) {
        if(value<0 || value>max) throw new IllegalArgumentException("Camera value out of range");
        return value;
    }
    private static float bounded(float value,float min,float max) {
        if(!(value>=min && value<=max)) throw new IllegalArgumentException("Camera coordinate out of range");
        return value;
    }
    private static void vector(RegistryFriendlyByteBuf out,Vec3 vec) {
        out.writeFloat((float)vec.x); out.writeFloat((float)vec.y); out.writeFloat((float)vec.z);
    }
    private static Vec3 vector(RegistryFriendlyByteBuf in,float min,float max) {
        return new Vec3(bounded(in.readFloat(),min,max),bounded(in.readFloat(),min,max),bounded(in.readFloat(),min,max));
    }
}
