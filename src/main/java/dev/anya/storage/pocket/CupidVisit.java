package dev.anya.storage.pocket;

/** Room-relative tracks in the original 32-block interior. Frame indices use the shipped six poses. */
final class CupidVisit {
    static final int STROLL=0,WAVE=1,FLIP=2,SPIN=3,SIT=4,DRIFT=5,PEEK=6,DANCE=7;
    static final int[][] SPOTS={{3,1,3},{28,1,3},{28,1,28},{3,1,28},{7,1,10},{23,2,15},{12,1,21},{26,3,8},
            {6,12,17},{22,18,25},{10,29,9},{25,29,19},{17,25,5},{7,8,25}};
    static final int WAIT=135,DANCE_TIME=60;
    static boolean valid(int cue){return cue>=0 && cue<8*SPOTS.length;}
    static int cue(java.util.random.RandomGenerator rng){
        int act=rng.nextInt(8);
        int spot=switch(act){
            case DANCE -> rng.nextInt(4);
            case STROLL,SIT -> rng.nextInt(8);
            case DRIFT,PEEK -> 8+rng.nextInt(SPOTS.length-8);
            default -> rng.nextInt(SPOTS.length);
        };
        return spot*8+act;
    }
    record Pose(double x,double y,double z,float roll,float width,float knees,int frame,float alpha){}
    static Pose pose(int cue,float age,float danceStart){
        int act=cue%8;var spot=SPOTS[cue/8];
        float end=act==DANCE?(danceStart<0?WAIT:danceStart+DANCE_TIME):100;
        float t=Math.clamp(age/100,0,1),wave=(float)Math.sin(age*.12),hop=(float)Math.sin(Math.PI*t);
        double x=spot[0]+.5,y=spot[1],z=spot[2]+.5;float roll=0,width=1,knees=1;
        int face=switch(act){case SPIN,DRIFT,DANCE->4;case WAVE,SIT->0;case FLIP,PEEK->5;default->1;};
        switch(act){
            case STROLL->{x+=2*t-1;y+=.07*Math.abs(wave);roll=wave*3;}
            case WAVE->{roll=wave*5;face=(age>28 && age<65)?2:0;}
            case FLIP->{roll=360*smooth((age-15)/65);y+=.9*hop;}
            case SPIN->{width=.24f+.76f*Math.abs((float)Math.cos(2*Math.PI*t));roll=8*wave;y+=.25*hop;}
            case SIT->{knees=.42f;y-=.28;roll=-6+2*wave;face=age<34?0:2;}
            case DRIFT->{x+=1.5*Math.cos(Math.PI*t);z+=1.2*hop;y+=.35*wave;roll=14*wave;}
            case PEEK->{roll=(spot[1]>=25?170:20)+7*wave;y-=.45*hop;face=age<42?5:0;}
            case DANCE->{if(danceStart>=0){
                float beat=(age-danceStart)*.29f;roll=16*(float)Math.sin(beat);x+=.39*Math.sin(beat);
                y+=.33*Math.abs(Math.sin(beat));knees=.77f+.23f*(float)Math.abs(Math.cos(beat));
                face=(int)((age-danceStart)/18)%2==0?5:1;
            }else{roll=3*wave;face=(int)(age/34)%2==0?0:4;}}
        }
        // Look aside, hold, then briefly close the eyes; the entire body does not cycle on every tick.
        int beat=(int)(age+act*7)%62;
        if(beat>=48 && beat<52)face=3;
        else if(beat>=27 && beat<43 && act!=DANCE)face=face<3?Math.floorMod(face+1,3):5;
        return new Pose(x,y,z,roll,width,knees,face,smooth(age/9)*smooth((end-age)/12));
    }
    static float smooth(float t){float s=Math.clamp(t,0,1);return s*s*(3-2*s);}
}
