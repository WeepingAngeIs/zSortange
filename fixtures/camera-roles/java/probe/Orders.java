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
    static void recordFrozenRuntime(String role)throws Exception {
        if(!"true".equals(System.getenv("CUMULATIVE_FROZEN")))return;
        var mod=net.neoforged.fml.ModList.get().getModContainerById("anyastorage").orElseThrow().getModInfo();
        Path jar=mod.getOwningFile().getFile().getFilePath();
        var sha=java.security.MessageDigest.getInstance("SHA-256");
        try(var file=Files.newInputStream(jar)){
            try(var hashed=new java.security.DigestInputStream(file,sha)){hashed.transferTo(java.io.OutputStream.nullOutputStream());}
        }
        String hash=java.util.HexFormat.of().formatHex(sha.digest());
        check(hash.equals("a46c6666190c57feeb016a40c78e4f3112959591d6e41909880800542e992497"),
                "Different Anya Storage JAR loaded on "+role);
        write(role+"-runtime.txt",mod.getVersion()+" "+hash+" "+jar.getFileName());
    }
    static Path ack(String role,String step){return ROOT.resolve(step+"-"+role+".ok");}
}

