package dev.anya.storage.acceptance;

import dev.anya.storage.AnyaStorage;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModList;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;

@Mod(Acceptance.ID)
public final class Acceptance {
    public static final String ID="anyaacceptance";
    public Acceptance(){
        if(!Boolean.getBoolean("anya.acceptance"))throw new IllegalStateException("Acceptance fixture is not a playable mod. Remove its JAR.");
    }
    public static void require(boolean test,String message){if(!test)throw new IllegalStateException(message);}
    public static Path control(){return Path.of(System.getProperty("anya.control")).toAbsolutePath();}
    public static void marker(String name,String text){try{Files.createDirectories(control());Files.writeString(control().resolve(name),text);}catch(Exception e){throw new IllegalStateException(e);}}
    public static void verifyJar(String side) throws Exception {
        Path actual=ModList.get().getModFileById("anyastorage").getFile().getFilePath();
        Path expected=Path.of(System.getProperty("anya.expectedJar"));
        require(Files.isRegularFile(actual)&&actual.toString().endsWith(".jar"),"Production classes must load from a packaged JAR, not source output: "+actual);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(actual)));
        require(MessageDigest.isEqual(Files.readAllBytes(actual),Files.readAllBytes(expected)),"Packaged runtime JAR mismatch");
        AnyaStorage.LOG.info("ANYA_PACKAGED_JAR_OK {} {} {}",side,actual,hash);
        marker("jar-"+side+".txt",actual+"\n"+hash+"\n");
    }
}
