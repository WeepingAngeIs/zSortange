package probe;

import com.google.gson.Gson;
import java.io.IOException;
import java.nio.file.*;
import net.minecraft.core.BlockPos;

final class Orders {
    static final Path ROOT=Path.of(System.getProperty("roomroles.control"));
    static final Gson JSON=new Gson();
    record Order(String step,String action,String dimension,long pos,String item,boolean sneak,
                 String face,double x,double y,double z,int color) {
        BlockPos target(){return BlockPos.of(pos);}
    }
    static void write(String name,String text)throws IOException {
        Path temp=ROOT.resolve(name+".tmp");
        Files.writeString(temp,text);
        Files.move(temp,ROOT.resolve(name),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
    static void check(boolean condition,String why){if(!condition)throw new AssertionError(why);}
    static Path ack(String role,String step){return ROOT.resolve(step+"-"+role+".ok");}
}

