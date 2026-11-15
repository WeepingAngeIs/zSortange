package dev.anya.storage;

import net.minecraft.nbt.*;
import java.util.*;

/** A bounded convenience log, never an authoritative inventory transaction journal. */
public final class BaseActivity {
    public static final int HARD_LIMIT=128;
    public enum Action { DEPOSIT, WITHDRAW, QUICKSTACK, DUMP }
    public record Entry(long time, UUID actor, Action action, int count) {}
    private final Deque<Entry> entries=new ArrayDeque<>();

    public boolean record(ExpansionRules.Options policy,long time,UUID actor,Action action,int count){
        if(!policy.activityLogging||count<=0||count>2097152||time<0||action==null)return false;
        entries.addLast(new Entry(time,policy.activityActorIds?actor:null,action,count));
        prune(policy);return true;
    }
    /** Turning actor collection off also strips IDs from loaded history, not just the next event. */
    public boolean prune(ExpansionRules.Options policy){
        boolean changed=false;int limit=Math.max(1,Math.min(HARD_LIMIT,policy.activityLogEntries));
        while(entries.size()>limit){entries.removeFirst();changed=true;}
        if(!policy.activityActorIds&&entries.stream().anyMatch(e->e.actor()!=null)){
            List<Entry> clean=entries.stream().map(e->new Entry(e.time(),null,e.action(),e.count())).toList();
            entries.clear();entries.addAll(clean);changed=true;
        }
        return changed;
    }
    public List<Entry> visible(ExpansionRules.Options policy){
        if(!policy.activityLogging)return List.of();
        List<Entry> out=new ArrayList<>();int skip=Math.max(0,entries.size()-Math.max(1,Math.min(HARD_LIMIT,policy.activityLogEntries)));
        for(Entry e:entries){if(skip-->0)continue;out.add(new Entry(e.time(),policy.activityActorIds?e.actor():null,e.action(),e.count()));}
        Collections.reverse(out);return List.copyOf(out);
    }
    public void clear(){entries.clear();}
    public ListTag save(ExpansionRules.Options policy){
        prune(policy);ListTag list=new ListTag();
        for(Entry e:entries){CompoundTag t=new CompoundTag();t.putLong("Time",e.time());t.putString("Action",e.action().name());t.putInt("Count",e.count());if(e.actor()!=null)t.putUUID("Actor",e.actor());list.add(t);}
        return list;
    }
    public void load(ListTag list,ExpansionRules.Options policy){
        entries.clear();
        for(int i=Math.max(0,list.size()-HARD_LIMIT);i<list.size();i++){
            CompoundTag t=list.getCompound(i);long time=t.getLong("Time");int count=t.getInt("Count");
            if(time<0||count<=0||count>2097152)continue;
            try{Action action=Action.valueOf(t.getString("Action"));entries.addLast(new Entry(time,policy.activityActorIds&&t.hasUUID("Actor")?t.getUUID("Actor"):null,action,count));}
            catch(IllegalArgumentException ignored){/* Malformed log data cannot grant permissions or move items. */}
        }
        prune(policy);
    }
}
