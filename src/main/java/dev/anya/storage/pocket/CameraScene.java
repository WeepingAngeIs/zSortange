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
                          List<Surface> surfaces, List<Actor> actors, int[] cells, int[] backdrop, int[] edgeBackdrop,
                          int[] detail) implements CustomPacketPayload {
    public static final int SIDE = 32, ATMOSPHERE = Short.MAX_VALUE;
    static final int RANGE = 96;
    static final float FAR_CLIP = RANGE + 4;
    static final int MAX_SURFACES = 1024, MAX_ACTORS = 6, MAX_EDGE_CELLS = 48;
    static final int DETAIL_SIDE = 6, DETAIL_AREA = DETAIL_SIDE * DETAIL_SIDE, MAX_DETAIL_CELLS = 48;
    public static final Type<CameraScene> TYPE = new Type<>(ExpansionPackets.id("camera_scene_v5"));
    public static BiConsumer<Connection, CameraScene> receiver = (connection, scene) -> {};

    public record Surface(int stateId, int x, int y, int z, Direction face, float plane,
                          int skyLight, int blockLight, int tint) {}
    public record Actor(int typeId, Vec3 feet, float yaw, float headYaw, float pitch, float phase, float speed,
                        int skyLight, int blockLight, int appearance, int skinParts) {}

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
                List.of(), List.of(), new int[0], new int[0], new int[0], new int[0]);
    }

    static void register(RegisterPayloadHandlersEvent event) {
        StreamCodec<RegistryFriendlyByteBuf, CameraScene> codec = StreamCodec.ofMember(CameraScene::write, CameraScene::read);
        event.registrar("sharpcamera5").optional().playToClient(TYPE, codec, (scene,ctx) -> receiver.accept(ctx.connection(),scene));
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
            out.writeVarInt(actor.appearance);
            if (actor.appearance != 0) out.writeByte(actor.skinParts);
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
        int tiles=0;
        for(int i=0;detail.length!=0 && i<cells.length;i++)if(hasDetail(detail,i))tiles++;
        out.writeByte(tiles);
        for(int i=0;detail.length!=0 && i<cells.length;i++)if(hasDetail(detail,i)){
            out.writeShort(i);
            for(int j=0;j<DETAIL_AREA;j++)out.writeShort(detail[i*DETAIL_AREA+j]);
        }
    }

    private static boolean hasDetail(int[] refs,int cell) {
        for(int i=0;i<DETAIL_AREA;i++)if(refs[cell*DETAIL_AREA+i]!=0)return true;
        return false;
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
        float maxAperture=(float)CameraOptics.WIDE_APERTURE+.0001f;
        Vec3 eye=vector(in,0,1), forward=vector(in,-1,1);
        Vec3 right=vector(in,-maxAperture,maxAperture), up=vector(in,-maxAperture,maxAperture);
        double aperture=right.length();
        if(Math.abs(forward.lengthSqr()-1)>.001 || aperture<.07 || aperture>maxAperture
                || up.length()>maxAperture || Math.abs(up.length()-aperture)>.0001
                || Math.abs(forward.dot(right))>.0001 || Math.abs(forward.dot(up))>.0001
                || Math.abs(right.dot(up))>.0001 || right.cross(forward).dot(up)<=0)
            throw new IllegalArgumentException("Invalid camera lens orientation");
        int count=bounded(in.readVarInt(),MAX_SURFACES);
        List<Surface> surfaces=new ArrayList<>(count);
        for (int i=0;i<count;i++) {
            int id=bounded(in.readVarInt(),Integer.MAX_VALUE);
            int x=in.readByte(),y=in.readByte(),z=in.readByte();
            if(Math.abs(x)>RANGE+1 || Math.abs(y)>RANGE+1 || Math.abs(z)>RANGE+1) throw new IllegalArgumentException("Face outside camera range");
            Direction normal=Direction.from3DDataValue(bounded(in.readUnsignedByte(),5));
            float plane=bounded(in.readFloat(),-RANGE-2,RANGE+2); int light=in.readUnsignedByte();
            surfaces.add(new Surface(id,x,y,z,normal,plane,light>>>4,light&15,in.readInt()&0xffffff));
        }
        count=bounded(in.readUnsignedByte(),MAX_ACTORS);
        List<Actor> actors=new ArrayList<>(count);
        for(int i=0;i<count;i++) {
            int id=bounded(in.readVarInt(),Integer.MAX_VALUE);
            Vec3 feet=vector(in,-RANGE-2,RANGE+2);
            float yaw=bounded(in.readFloat(),-180,180),head=bounded(in.readFloat(),-180,180);
            float pitch=bounded(in.readFloat(),-180,180),phase=bounded(in.readFloat(),0,256),speed=bounded(in.readFloat(),0,1);
            int light=in.readUnsignedByte();
            int appearance = in.readVarInt();
            if (appearance < CameraAppearanceHistory.SELF) throw new IllegalArgumentException("Unknown appearance handle");
            int parts = appearance == 0 ? 0 : bounded(in.readUnsignedByte(), 127);
            actors.add(new Actor(id,feet,yaw,head,pitch,phase,speed,light>>>4,light&15,appearance,parts));
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
        int tiles=bounded(in.readUnsignedByte(),MAX_DETAIL_CELLS);
        int[] detail=new int[tiles==0?0:cells.length*DETAIL_AREA];
        previous=-1;
        for(int n=0;n<tiles;n++){
            int cell=bounded(in.readUnsignedShort(),cells.length-1);
            if(cell<=previous || cells[cell]<2 || cells[cell]==ATMOSPHERE)
                throw new IllegalArgumentException("Invalid detail tile");
            previous=cell;
            for(int j=0;j<DETAIL_AREA;j++){
                int ref=in.readShort();
                if(ref< -1 || ref==1 || ref>=surfaces.size()+2)throw new IllegalArgumentException("Invalid detail surface");
                detail[cell*DETAIL_AREA+j]=ref;
            }
        }
        return new CameraScene(monitor,time,weatherTick,sky,rain,thunder,eye,forward,right,up,List.copyOf(surfaces),List.copyOf(actors),cells,backdrop,edge,detail);
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
