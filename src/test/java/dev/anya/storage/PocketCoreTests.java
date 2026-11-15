package dev.anya.storage;
import dev.anya.storage.pocket.*;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

public final class PocketCoreTests {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private interface Task {void run()throws Exception;}
    private static void denied(Task task,String message)throws Exception {boolean caught=false;try{task.run();}catch(IOException|IllegalArgumentException expected){caught=true;}check(caught,message);}
    private static PocketLedger.Anchor anchor(long position){return new PocketLedger.Anchor("minecraft:overworld",11,UUID.randomUUID(),position);}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("anya-pocket-ledger-");PocketLedger ledger=new PocketLedger(root);UUID owner=UUID.randomUUID(),guest=UUID.randomUUID();
        var a=anchor(20);var first=ledger.reserve(owner,a,"galaxy",8);var second=ledger.reserve(owner,anchor(21),"beach",8);
        check(first.cell()!=second.cell()&&!first.id().equals(second.id()),"Cells and identities must be unique");
        check(first.x()%16==0&&first.z()%16==0,"Plot is chunk aligned");
        for(var p:ledger.pockets()){
            for(int x=p.x();x<p.x()+32;x++)for(int z=p.z();z<p.z()+32;z++)check(p.contains(x,65,z),"Full 32x32 plot addressable");
            check(!p.contains(p.x()-1,65,p.z())&&!p.contains(p.x()+32,65,p.z())&&!p.contains(p.x(),64,p.z())&&!p.contains(p.x(),97,p.z()),"Boundary is exclusive");
        }
        denied(()->ledger.reserve(owner,a,"galaxy",8),"Duplicate anchor refused");
        denied(()->ledger.reserve(owner,anchor(22),"unknown",8),"Unknown scenery refused");
        ledger.theme(first.id(),owner,"wasteland");denied(()->ledger.theme(first.id(),guest,"beach"),"Guest cannot change scenery");
        ledger.stage(first.id(),PocketLedger.Stage.RESERVED,PocketLedger.Stage.BUILDING);
        PocketLedger restarted=new PocketLedger(root);check(restarted.get(first.id()).stage()==PocketLedger.Stage.BUILDING,"Interrupted preparation persists, never silently regenerates");
        denied(()->ledger.stage(first.id(),PocketLedger.Stage.RESERVED,PocketLedger.Stage.BUILDING),"Preparation cannot replay");
        ledger.stage(first.id(),PocketLedger.Stage.BUILDING,PocketLedger.Stage.READY);
        denied(()->ledger.theme(first.id(),owner,"galaxy"),"No scenery overwrite after preparation");
        var back=new PocketLedger.ReturnPoint(owner,first.id(),"minecraft:overworld",2.5,70,4.5,90,0);ledger.reserveReturn(back);
        ledger.reserveReturn(new PocketLedger.ReturnPoint(guest,first.id(),"minecraft:overworld",3.5,70,4.5,0,0));
        denied(()->ledger.reserveReturn(back),"Second entry cannot overwrite unfinished return");
        PocketLedger reopened=new PocketLedger(root);check(reopened.returning(owner).equals(back)&&reopened.returnCount()==2,"Two return records survive reopen");
        denied(()->restarted.theme(second.id(),owner,"wasteland"),"Stale writer cannot overwrite newer ledger");
        var before=ledger.get(first.id());denied(()->ledger.reanchor(first.id(),guest,anchor(25)),"Non-owner cannot reanchor");
        var moved=ledger.reanchor(first.id(),owner,anchor(25));check(moved.id().equals(before.id())&&moved.cell()==before.cell()&&moved.stage()==before.stage()&&moved.epoch()>before.epoch(),"Reanchor preserves physical cell and advances epoch");
        ledger.clearReturn(owner);check(ledger.returning(owner)==null&&ledger.returning(guest)!=null,"One recovery leaves the other player intact");
        for(int i=2;i<8;i++)ledger.reserve(owner,anchor(50+i),"galaxy",8);
        denied(()->ledger.reserve(owner,anchor(99),"galaxy",8),"Pocket count is bounded");
        Path file=root.resolve("anya_storage/pockets.v1.dat");byte[] intact=Files.readAllBytes(file);byte[] corrupt=intact.clone();corrupt[35]^=3;Files.write(file,corrupt);
        denied(()->new PocketLedger(root),"Corrupt ledger cannot reset to empty");check(Arrays.equals(Files.readAllBytes(file),corrupt),"Rejected corruption left untouched");Files.write(file,intact);
        long revision=ledger.revision();Path saved=file.resolveSibling("kept-original.dat");Files.move(file,saved);Files.createDirectory(file);
        denied(()->ledger.clearReturn(guest),"Failed write refuses in-memory advancement");check(ledger.revision()==revision&&ledger.returning(guest)!=null,"Failed write preserved pending return");
        Files.delete(file);Files.move(saved,file);
        denied(()->new PocketLedger.ReturnPoint(owner,first.id(),"anyastorage:pockets",1,65,1,0,0),"Nested pocket returns denied");
        denied(()->new PocketLedger.ReturnPoint(owner,first.id(),"minecraft:overworld",Double.NaN,65,1,0,0),"Nonfinite coordinates denied");
        PocketLeases leases=new PocketLeases();UUID p1=UUID.randomUUID(),p2=UUID.randomUUID(),room=UUID.randomUUID(),other=UUID.randomUUID();
        check(leases.acquire(p1,room,PocketLeases.Kind.VISIT,0,0,1,2,10,30),"First lease");
        check(leases.acquire(p2,room,PocketLeases.Kind.REMOTE,0,0,1,2,10,30),"Independent second viewer");
        check(!leases.acquire(UUID.randomUUID(),other,PocketLeases.Kind.VISIT,0,1,1,2,10,30),"Budget refuses another pocket");
        leases.release(p1);check(leases.active().contains(room),"First departure retains remaining viewer");
        check(leases.renew(p2,0,9,10),"Permitted refresh before TTL");check(!leases.renew(p2,1,10,10)&&leases.active().isEmpty(),"Anchor revocation removes last lease");
        check(leases.acquire(p1,room,PocketLeases.Kind.VISIT,2,20,1,2,10,30),"New independent visit");
        for(int t=21;t<50;t++)check(leases.renew(p1,2,t,10),"Active refresh");
        check(!leases.renew(p1,2,50,10)&&leases.active().isEmpty(),"Absolute deadline cannot renew forever");
        check(leases.acquire(p2,room,PocketLeases.Kind.VISIT,3,100,1,2,5,30),"Expiry scenario");check(leases.expire(105).size()==1&&leases.active().isEmpty(),"Abandoned lease expires");
        check(BasePermissions.LEGACY==7&&!BasePermissions.allows(BasePermissions.LEGACY,BasePermissions.VISIT),"Legacy storage rights do not grant Visit");
        check(!BasePermissions.allows(BasePermissions.VISIT,BasePermissions.WITHDRAW)&&!BasePermissions.allows(BasePermissions.VIEW,BasePermissions.VISIT),"Visit/storage rights independent");
        System.out.println("PASS: "+checks+" pocket ledger, boundary, recovery-record and lease assertions (pure JVM; not multiplayer)");
    }
}
