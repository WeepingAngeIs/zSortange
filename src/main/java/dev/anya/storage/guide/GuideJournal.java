package dev.anya.storage.guide;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.UUID;

/** At-most-once automatic grants. Commit the claim BEFORE handing out an item; never erase a claim. */
public final class GuideJournal {
    private final Path directory;
    public GuideJournal(Path worldRoot)throws IOException {
        Path root=worldRoot.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path parent=root.resolve("anya_storage");Files.createDirectories(parent);
        directory=parent.resolve("guide_grants");Files.createDirectories(directory);
        if(Files.isSymbolicLink(parent)||Files.isSymbolicLink(directory))throw new IOException("Unsafe guide grant directory");
    }
    private Path path(UUID player){return directory.resolve(player.toString()+".claim");}
    public synchronized boolean claimed(UUID player)throws IOException {
        Path file=path(player);
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return false;
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))throw new IOException("Invalid guide claim");
        return true; // Even a partial write must fail closed, not produce another item.
    }
    public synchronized boolean claim(UUID player)throws IOException {
        Path file=path(player);
        try(FileChannel out=FileChannel.open(file,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
            ByteBuffer bytes=ByteBuffer.wrap("Ancient Technology automatic grant v1\n".getBytes(StandardCharsets.UTF_8));
            while(bytes.hasRemaining())out.write(bytes);out.force(true);
            // Supported on the Linux hosted server. Some platforms cannot open a directory channel.
            try(FileChannel dir=FileChannel.open(directory,StandardOpenOption.READ)){dir.force(true);}catch(IOException|UnsupportedOperationException ignored){}
            return true;
        }catch(FileAlreadyExistsException e){if(!claimed(player))throw e;return false;}
    }
}
