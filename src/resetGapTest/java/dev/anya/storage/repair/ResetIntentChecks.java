package dev.anya.storage.repair;
import java.io.*;import java.nio.file.*;import java.util.*;
/** Small isolated filesystem checks. Not Minecraft clients, disk-loss simulation or a latency benchmark. */
public final class ResetIntentChecks {
 private static int checks;
 private static void ok(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 private static SnapshotJournal seed(Path p)throws Exception{var j=new SnapshotJournal(p);var r=j.pending(7,new byte[]{1});j.applied(7,r.token(),true);return j;}
 public static void main(String[] a)throws Exception{
  Path root=Path.of(a[0]);Files.createDirectories(root);
  var j=seed(root.resolve("supersession"));var token=j.get(7).token();j.beginReset(7,token);j.reset(7,token);
  ok(j.get(7).state()==SnapshotJournal.State.APPLIED&&!j.get(7).locked()&&j.blocked(7));
  j=new SnapshotJournal(root.resolve("supersession"));ok(j.resetPending(7)&&j.blocked(7));
  var newer=j.pending(7,new byte[]{2});ok(newer.locked());j.applied(7,newer.token(),false);ok(j.get(7).state()==SnapshotJournal.State.REPAIRED&&j.blocked(7));
  try{j.completeReset(7,token);throw new AssertionError();}catch(IOException expected){ok(true);}
  j.beginReset(7,newer.token());j.reset(7,newer.token());ok(j.blocked(7));j.completeReset(7,newer.token());ok(!j.blocked(7));
  j=new SnapshotJournal(root.resolve("supersession"));ok(!j.blocked(7)&&Arrays.equals(j.get(7).payload(),new byte[]{2}));
  for(String point:List.of("reset-intent-before-force","reset-intent-after-force","reset-intent-published","reset-projection-before-force","reset-projection-after-force","reset-projection-published","reset-before-force","reset-after-force","reset-published")){
   Path p=root.resolve(point);var x=seed(p);var t=x.get(7).token();x.cuts(at->{if(at.equals(point))throw new IOException("cut "+at);});
   boolean hit=false;try{x.beginReset(7,t);x.reset(7,t);x.completeReset(7,t);}catch(IOException expected){hit=true;}ok(hit);
   if(point.endsWith("published")){var reopened=new SnapshotJournal(p);ok(reopened.blocked(7)!=point.equals("reset-published"));ok(Arrays.equals(reopened.get(7).payload(),new byte[]{1}));}
   else {try{new SnapshotJournal(p);throw new AssertionError("Orphan silently accepted");}catch(IOException expected){ok(expected.getMessage().contains("Incomplete publication"));}}
  }
  Path corrupt=root.resolve("corrupt");var c=seed(corrupt);c.beginReset(7,c.get(7).token());var bytes=Files.readAllBytes(corrupt.resolve("7.reset"));bytes[13]^=1;Files.write(corrupt.resolve("7.reset"),bytes);try{new SnapshotJournal(corrupt);throw new AssertionError();}catch(IOException expected){ok(expected.getMessage().contains("checksum"));}ok(Arrays.equals(bytes,Files.readAllBytes(corrupt.resolve("7.reset"))));
  Path missing=root.resolve("missing");var m=seed(missing);m.beginReset(7,m.get(7).token());Files.delete(missing.resolve("7.wal"));try{new SnapshotJournal(missing);throw new AssertionError();}catch(IOException expected){ok(expected.getMessage().contains("without retained snapshot"));}
  var q=new SnapshotJournal(root.resolve("quota"));for(int n=0;n<64;n++){var r=q.pending(n,new byte[]{1});q.applied(n,r.token(),true);q.beginReset(n,r.token());}try{q.pending(64,new byte[]{2});throw new AssertionError();}catch(IOException expected){ok(true);}var rq=new SnapshotJournal(root.resolve("quota"));ok(rq.all().size()==64);for(var r:rq.all())ok(rq.blocked(r.key())&&rq.resetPending(r.key()));
  System.out.println("RESET_INTENT_CORE_PASSED assertions="+checks+" cuts=9 checksum=true latestPayloadRetained=true incompleteFailsClosed=true");
 }
}
