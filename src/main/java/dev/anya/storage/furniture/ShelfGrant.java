package dev.anya.storage.furniture;

/** One outstanding observation per camera. A reply can use only the time remaining since the request. */
final class ShelfGrant {
    private static final long TICKS=10, NANOS=500_000_000L;
    private long serial, pending, sentTick, sentTime, untilTick, untilTime;
    long request(long tick,long now) {
        sentTick=tick;sentTime=now;
        return pending=++serial;
    }
    boolean accepts(long token) { return token>0 && pending==token; }
    boolean consume(long token,boolean allowed,long tick,long now) {
        if(!accepts(token)) return false;
        pending=0;
        untilTick=0;
        if(!allowed || tick-sentTick>=TICKS || now-sentTime>=NANOS) return false;
        untilTick=sentTick+TICKS;untilTime=sentTime+NANOS;
        return true;
    }
    boolean visible(long tick,long now) { return untilTick!=0 && tick<untilTick && now-untilTime<0; }
    void clear() { pending=0;untilTick=0; }
}
