package dev.anya.storage.repair;
import java.nio.file.*;import java.io.*;import java.util.*;
public final class JournalChecks {
 static int checks;static void check(boolean v){if(!v)throw new AssertionError("check "+checks);checks++;}
 interface IO{void run()throws Exception;}static void denied(IO w)throws Exception{try{w.run();throw new AssertionError("Expected refusal");}catch(IOException expected){checks++;}}
 public static void main(String[] args)throws Exception{
  Path base=Files.createTempDirectory("anya-journal-check-");SnapshotJournal j=new SnapshotJournal(base.resolve("basic"));byte[] a={1,2,3},b={4,5,6};
  var first=j.pending(1,a);check(j.blocked(1));j.lock(1,first.token());j=new SnapshotJournal(base.resolve("basic"));check(j.blocked(1));j.applied(1,first.token(),true);check(j.blocked(1));j.reset(1,first.token());check(!j.blocked(1));
  var latest=j.pending(1,b);SnapshotJournal q=j;denied(()->q.reset(1,latest.token()));denied(()->q.applied(1,first.token(),true));check(Arrays.equals(j.get(1).payload(),b));j.applied(1,latest.token(),false);check(!j.blocked(1));
  for(int i=2;i<=64;i++){var r=j.pending(i,a);j.applied(i,r.token(),false);}denied(()->q.pending(65,a));check(j.all().size()==64);denied(()->q.pending(1,new byte[SnapshotJournal.MAX_BYTES+1]));
  for(String point:List.of("pending-before-force","pending-after-force","pending-published","applied-before-force","applied-published","reset-before-force","reset-published")){
   Path root=base.resolve(point);var x=new SnapshotJournal(root);var old=x.pending(7,a);x.applied(7,old.token(),false);var next=x.pending(7,b);x.lock(7,next.token());x.applied(7,next.token(),true);
   x.cuts(p->{if(p.equals(point))throw new IOException("injected "+p);});denied(()->{if(point.startsWith("pending"))x.pending(7,b);else if(point.startsWith("applied"))x.applied(7,next.token(),true);else x.reset(7,next.token());});
   try{var again=new SnapshotJournal(root);check(Arrays.equals(again.get(7).payload(),b));}catch(IOException expected){checks++;}
  }
  Path corrupt=base.resolve("corrupt");var c=new SnapshotJournal(corrupt);c.pending(3,a);Path rec=corrupt.resolve("3.wal");byte[] raw=Files.readAllBytes(rec);raw[raw.length-1]^=1;Files.write(rec,raw);denied(()->new SnapshotJournal(corrupt));
  Path bad=base.resolve("bad-world");Files.createDirectories(bad);Files.writeString(bad.resolve("world.id"),UUID.randomUUID().toString());Files.copy(base.resolve("basic/1.wal"),bad.resolve("1.wal"));denied(()->new SnapshotJournal(bad));
  System.out.println("ANYA_REPAIR_CORE_OK checks="+checks+" atomic force/checksum/world binding/stale reset/quotas/cut refusals; "+base);
 }
}
