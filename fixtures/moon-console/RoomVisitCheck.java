package dev.anya.storage.pocket;
import java.util.*;

/** Hosted, deterministic schedule and track checks against the actual playable. */
public final class RoomVisitCheck {
    static void check(boolean ok,String msg){if(!ok)throw new AssertionError(msg);}
    public static void main(String[] args){
        var schedule=new RoomVisitSchedule(new Random(41));UUID p=new UUID(1,1);
        int last=0,shows=0,hides=0;boolean[] actions=new boolean[8];Set<Integer> places=new HashSet<>();
        for(int tick=0;tick<2_000_000;tick++){
            int cue=schedule.advance(p,tick);
            if(cue==RoomVisitSchedule.HIDE){check(tick-last==RoomVisitSchedule.DURATION,"Expiry");hides++;}
            if(cue<0)continue;
            check(CupidVisit.valid(cue) && tick-last>=1200 && tick-last<=24000,"One to twenty minute spacing");last=tick;shows++;
            int act=cue%8;actions[act]=true;places.add(cue/8);
            Set<Integer> faces=new HashSet<>();int longest=0,hold=0,previous=-1;
            for(int age=1;age<100;age++){
                var pose=CupidVisit.pose(cue,age,-1);
                check(pose.x()>0 && pose.x()<32 && pose.y()>=.6 && pose.y()<31 && pose.z()>0 && pose.z()<32,"Inside shell");
                faces.add(pose.frame());hold=previous==pose.frame()?hold+1:1;longest=Math.max(longest,hold);previous=pose.frame();
            }
            check(faces.contains(3) && faces.size()>=2 && longest>=10,"Blink and held expression");
            if(act==CupidVisit.DANCE){
                check(cue/8<4 && CupidVisit.pose(cue,CupidVisit.WAIT,-1).alpha()==0,"Unnoticed corner departure");
                check(CupidVisit.pose(cue,60,50).alpha()>0 && CupidVisit.pose(cue,111,50).alpha()==0,"Brief dance then departure");
            }
        }
        for(boolean present:actions)check(present,"Missing act");
        check(places.size()==CupidVisit.SPOTS.length && shows>=100 && Math.abs(shows-hides)<=1,"Variety");
        schedule.clear();check(!schedule.end(p),"Cleared visitor");
        System.out.println("PASS eight acts, fourteen spots, first and repeat 1-20 minute scheduling, held expressions and blinks, corner dance and timed departure.");
    }
}
