"""Execute the packaged local tablet binding against a small offline inventory and authority host."""
import argparse
import os
from pathlib import Path
import sys
import tempfile

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "tablet-lifecycle"))
from run import cached_paths, invoke


HOST = {
    "net.minecraft.core.BlockPos": "public record BlockPos(long value) {}",
    "net.minecraft.world.InteractionHand": "public enum InteractionHand {MAIN_HAND, OFF_HAND}",
    "net.minecraft.world.Container": "public interface Container {}",
    "net.minecraft.server.MinecraftServer": "public class MinecraftServer {}",
    "net.minecraft.world.entity.player.Inventory": "public class Inventory { public int selected; }",
    "net.minecraft.world.level.block.entity.BlockEntity": "public class BlockEntity {}",
    "net.minecraft.core.component.DataComponentType": "public class DataComponentType<T> {}",
    "net.minecraft.core.component.DataComponents": """public class DataComponents {
        public static final DataComponentType<net.minecraft.world.item.component.CustomData> CUSTOM_DATA=new DataComponentType<>();
    }""",
    "net.minecraft.nbt.CompoundTag": """public class CompoundTag {
        public java.util.UUID id; public Long pos; public String dimension="";
        public boolean hasUUID(String key){return key.equals("PocketId") && id!=null;}
        public java.util.UUID getUUID(String key){return id;}
        public boolean contains(String key,int type){return key.equals("HubPos") && type==4 && pos!=null;}
        public long getLong(String key){return pos;}
        public String getString(String key){return dimension;}
    }""",
    "net.minecraft.world.item.component.CustomData": """public record CustomData(net.minecraft.nbt.CompoundTag tag) {}""",
    "net.minecraft.world.item.ItemStack": """public class ItemStack {
        public static final ItemStack EMPTY=new ItemStack();
        public boolean tablet,creative,sealed; public int count=1; public net.minecraft.world.item.component.CustomData data;
        public int getCount(){return count;}
        @SuppressWarnings("unchecked") public <T>T get(net.minecraft.core.component.DataComponentType<T> type){return (T)data;}
    }""",
    "net.minecraft.server.level.ServerPlayer": """public class ServerPlayer {
        public final java.util.UUID id=java.util.UUID.randomUUID();
        public final net.minecraft.world.entity.player.Inventory inv=new net.minecraft.world.entity.player.Inventory();
        public net.minecraft.world.item.ItemStack main=net.minecraft.world.item.ItemStack.EMPTY,off=main;
        public boolean spectator;
        public net.minecraft.server.MinecraftServer getServer(){return dev.anya.storage.pocket.PocketService.server;}
        public java.util.UUID getUUID(){return id;}
        public net.minecraft.world.entity.player.Inventory getInventory(){return inv;}
        public net.minecraft.world.item.ItemStack getItemInHand(net.minecraft.world.InteractionHand h){
            return h==net.minecraft.world.InteractionHand.MAIN_HAND?main:off;
        }
    }""",
    "net.minecraft.server.level.ServerLevel": """public class ServerLevel {
        public boolean loaded=true;
        public final java.util.Map<net.minecraft.core.BlockPos,net.minecraft.world.level.block.entity.BlockEntity> cells=new java.util.HashMap<>();
        public boolean hasChunkAt(net.minecraft.core.BlockPos p){return loaded;}
        public net.minecraft.world.level.block.entity.BlockEntity getBlockEntity(net.minecraft.core.BlockPos p){return cells.get(p);}
    }""",
    "dev.anya.storage.StorageEntity": """public class StorageEntity extends net.minecraft.world.level.block.entity.BlockEntity implements net.minecraft.world.Container {
        public long profileRevision; public java.util.UUID pocketId; public boolean removed;
        public final net.minecraft.core.BlockPos pos;
        public StorageEntity(long pos){this.pos=new net.minecraft.core.BlockPos(pos);}
        public net.minecraft.core.BlockPos getBlockPos(){return pos;} public boolean isRemoved(){return removed;}
        public boolean permits(net.minecraft.server.level.ServerPlayer p,int mask){
            return BasePermissions.allows(dev.anya.storage.pocket.PocketService.rights,mask);
        }
        public void logTransfer(net.minecraft.server.level.ServerPlayer p,BaseActivity.Action act,int count){}
    }""",
    "dev.anya.storage.StorageItem": """public class StorageItem {
        public static boolean isTablet(net.minecraft.world.item.ItemStack s){return s.tablet;}
        public static boolean permittedTablet(net.minecraft.server.level.ServerPlayer p,net.minecraft.world.item.ItemStack s){
            return s.tablet && (!s.creative || (!p.spectator && s.count==1));
        }
    }""",
    "dev.anya.storage.BagInventory": """public class BagInventory {
        public static net.minecraft.nbt.CompoundTag data(net.minecraft.world.item.ItemStack s){
            return s.data==null?new net.minecraft.nbt.CompoundTag():s.data.tag();
        }
    }""",
    "dev.anya.storage.CreativeTabletAccess": """public class CreativeTabletAccess {
        public static boolean priorHubBinding(net.minecraft.server.level.ServerPlayer p,net.minecraft.world.item.ItemStack s,StorageEntity hub){
            return !s.creative || s.sealed;
        }
    }""",
    "dev.anya.storage.Transactions": """public class Transactions {
        public static boolean quarantined(net.minecraft.world.Container c){return false;}
    }""",
    "dev.anya.storage.Safety": """public class Safety {
        public static Runnable onClaim;
        public static String locationReason(net.minecraft.server.level.ServerPlayer p,net.minecraft.server.level.ServerLevel l,net.minecraft.core.BlockPos pos){
            var callback=onClaim;onClaim=null;if(callback!=null)callback.run();return "";
        }
    }""",
    "dev.anya.storage.pocket.PocketRoomLayout": """public class PocketRoomLayout {
        public static java.util.List<dev.anya.storage.StorageEntity> registry;
        public static java.util.List<dev.anya.storage.StorageEntity> present(net.minecraft.server.level.ServerLevel l,PocketLedger.Pocket room){
            return java.util.List.copyOf(registry);
        }
    }""",
    "dev.anya.storage.pocket.PocketService": """public class PocketService {
        public static final net.minecraft.server.MinecraftServer server=new net.minecraft.server.MinecraftServer();
        static final PocketLeases leases=new PocketLeases();
        static final dev.anya.storage.StorageEntity hub=new dev.anya.storage.StorageEntity(100);
        static final net.minecraft.server.level.ServerLevel level=new net.minecraft.server.level.ServerLevel();
        static PocketLedger data; public static int rights; static int remoteCloses;
        public static PocketLedger ledger(net.minecraft.server.MinecraftServer s){return data;}
        public static PocketLeases.Lease lease(java.util.UUID p){return leases.get(p);}
        public static dev.anya.storage.StorageEntity home(net.minecraft.server.level.ServerPlayer p,PocketLedger.Pocket r){return hub;}
        public static String authorize(net.minecraft.server.level.ServerPlayer p,PocketLedger.Pocket r,PocketLeases.Kind k,boolean arrives,int mask){
            return dev.anya.storage.BasePermissions.allows(rights,mask) ? "" : "revoked";
        }
        public static net.minecraft.server.level.ServerLevel dimension(net.minecraft.server.MinecraftServer s){return level;}
        public static void closeView(net.minecraft.server.level.ServerPlayer p){remoteCloses++;leases.release(p.id);}
    }"""
}


def run(args):
    with tempfile.TemporaryDirectory(prefix="anya-room-binding-") as temp:
        root = Path(temp)
        host = root / "classes"
        host.mkdir()
        sources = []
        for name, declaration in HOST.items():
            package, simple = name.rsplit(".", 1)
            file = root / "src" / package.replace(".", "/") / (simple + ".java")
            file.parent.mkdir(parents=True, exist_ok=True)
            file.write_text("package " + package + ";\n" + declaration + "\n", encoding="utf-8")
            sources.append(file)
        jar = args.support_jar.resolve()
        cp = os.pathsep.join((str(host), str(jar), cached_paths(args.classpath_file)))
        invoke(args.jdk, "javac", ["--release", "21", "-proc:none", "-classpath", cp,
                                  "-d", host, *sources, HERE / "BindingCheck.java"], root)
        invoke(args.jdk, "java", ["-Djava.awt.headless=true", "-classpath", cp,
                                 "dev.anya.storage.pocket.BindingCheck", jar], root)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk", required=True, type=Path)
    parser.add_argument("--classpath-file", required=True, type=Path)
    parser.add_argument("--support-jar", required=True, type=Path)
    run(parser.parse_args())
