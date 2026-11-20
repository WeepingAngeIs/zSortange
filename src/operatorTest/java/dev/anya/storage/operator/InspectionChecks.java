package dev.anya.storage.operator;
import dev.anya.storage.repair.SnapshotJournal;
import java.nio.file.*;
import java.util.*;
import java.io.*;
public final class InspectionChecks {
 static int checks;static void check(boolean v){if(!v)throw new AssertionError("check "+checks);checks++;}
 static Map<String,String> tree(Path p)throws Exception{Map<String,String> h=new TreeMap<>();if(Files.exists(p))try(var s=Files.walk(p)){for(Path x:s.toList()){if(Files.isRegularFile(x,LinkOption.NOFOLLOW_LINKS))h.put(p.relativize(x).toString(),JournalDisk.hash(Files.readAllBytes(x)));else h.put(p.relativize(x).toString(),Files.isSymbolicLink(x)?"symlink":"directory");}}return h;}
 static void refuse(Path p,long key)throws Exception{var before=tree(p);boolean rejected=false;try{JournalDisk.read(p,key);}catch(IOException e){rejected=true;}check(rejected);check(before.equals(tree(p)));}
 static SnapshotJournal seed(Path p)throws Exception{var j=new SnapshotJournal(p);var r=j.pending(7,new byte[]{1,2,3});j.applied(7,r.token(),true);j.beginReset(7,r.token());j.reset(7,r.token());return j;}
 public static void main(String[] args)throws Exception{
  Path base=Path.of(args[0]);if(Files.exists(base))throw new IOException("fresh disposable root required");Files.createDirectories(base);
  refuse(base.resolve("missing"),7);check(!Files.exists(base.resolve("missing")));
  var p=base.resolve("valid");var j=seed(p);var original=tree(p);var v=JournalDisk.read(p,7);check(v.intent().pending());check(v.intent().token().equals(v.entry().token()));check(JournalDisk.decision(v,true).equals("REFUSE_UNCERTAIN_OLD_VERSION"));check(original.equals(tree(p)));
  var newer=j.pending(7,new byte[]{4,5,6});j.applied(7,newer.token(),true);v=JournalDisk.read(p,7);check(!v.intent().token().equals(v.entry().token()));check(JournalDisk.decision(v,true).equals("ELIGIBLE_FOR_EXPLICIT_REVERIFY"));check(JournalDisk.decision(v,false).equals("REFUSE_PRIMARY_JOURNAL_DISAGREEMENT"));
  for(String mode:List.of("corrupt-wal","corrupt-reset","missing-wal","missing-world","temp","symlink","world-mismatch","record-key","marker-key","unknown")){
   Path d=base.resolve(mode);seed(d);Path target=d.resolve(mode.contains("reset")?"7.reset":"7.wal");
   switch(mode){
    case "corrupt-wal","corrupt-reset"->{byte[] b=Files.readAllBytes(target);b[0]^=1;Files.write(target,b);}
    case "missing-wal"->Files.delete(d.resolve("7.wal"));case "missing-world"->Files.delete(d.resolve("world.id"));
    case "temp"->Files.writeString(d.resolve("7.reset.tmp-test"),"partial");
    case "symlink"->{Files.move(target,d.resolve("backup"));Files.createSymbolicLink(target,Path.of("backup"));}
    case "world-mismatch"->Files.writeString(d.resolve("world.id"),UUID.randomUUID().toString());
    case "record-key"->Files.move(d.resolve("7.wal"),d.resolve("8.wal"));case "marker-key"->Files.move(d.resolve("7.reset"),d.resolve("8.reset"));
    case "unknown"->Files.writeString(d.resolve("unknown"),"retained");
   }refuse(d,7);
  }
  Path absent=base.resolve("missing-reset");seed(absent);Files.delete(absent.resolve("7.reset"));check(JournalDisk.decision(JournalDisk.read(absent,7),true).equals("REFUSE_MISSING_RESET_EVIDENCE"));
  Path fatal=base.resolve("fatal");var fj=seed(fatal);fj.failClosed("injected");check(JournalDisk.decision(JournalDisk.read(fatal,7),true).equals("REFUSE_GLOBAL_FAULT"));
  Path pending=base.resolve("pending");var pj=seed(pending);pj.pending(7,new byte[]{8});check(JournalDisk.decision(JournalDisk.read(pending,7),true).equals("REFUSE_VERIFIED_REPAIR_PROOF_MISSING"));
  Path full=base.resolve("full");var q=seed(full);for(int i=0;i<63;i++)q.pending(100+i,new byte[]{1});var snap=tree(full);check(JournalDisk.read(full,7).keys()==64);check(snap.equals(tree(full)));try{q.pending(999,new byte[]{1});throw new AssertionError();}catch(IOException expected){check(snap.equals(tree(full)));}
  System.out.println("OPERATOR_INSPECTION_CHECKS_OK assertions="+checks+" no mutation/no constructor on inspect; uncertain/mismatched/partial/corrupt/missing evidence refused;64 snapshots retained");
 }
}
