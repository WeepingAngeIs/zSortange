package dev.anya.storage;
import dev.anya.storage.guide.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class GuideJournalTests {
    private static int count;
    private static void check(boolean b,String reason){count++;if(!b)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception {
        Path root=Files.createTempDirectory("anya-guide-test-");UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        try{
            GuideJournal first=new GuideJournal(root);check(!first.claimed(a),"new player unclaimed");check(first.claim(a),"first automatic claim allowed");
            for(int i=0;i<100;i++)check(!first.claim(a),"repeated login cannot duplicate");
            GuideJournal restarted=new GuideJournal(root);check(restarted.claimed(a),"claim survives a new journal instance");check(!restarted.claim(a),"restart cannot duplicate");check(restarted.claim(b),"another player has an independent grant");
            UUID c=UUID.randomUUID();ExecutorService pool=Executors.newFixedThreadPool(8);
            try{List<Callable<Boolean>> calls=new ArrayList<>();for(int i=0;i<64;i++)calls.add(()->new GuideJournal(root).claim(c));int wins=0;for(Future<Boolean> f:pool.invokeAll(calls))if(f.get())wins++;check(wins==1,"exactly one durable claim across concurrent instances");}finally{pool.shutdownNow();}
            UUID partial=UUID.randomUUID();Files.createFile(root.resolve("anya_storage/guide_grants/"+partial+".claim"));check(!new GuideJournal(root).claim(partial),"interrupted zero-byte claim fails closed");
            Path directory=root.resolve("anya_storage/guide_grants");UUID invalid=UUID.randomUUID();Files.createDirectory(directory.resolve(invalid+".claim"));boolean rejected=false;try{first.claim(invalid);}catch(java.io.IOException ex){rejected=true;}check(rejected,"invalid claim path rejected");
            var opts=Config.JSON.fromJson("{}",ExpansionRules.Options.class);check(opts.tutorialGrant&&opts.tutorialRecovery,"old configs receive guide defaults");
            System.out.println("PASS: "+count+" durable tutorial grant assertions");
        }finally{try(var stream=Files.walk(root)){for(Path p:stream.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
}
