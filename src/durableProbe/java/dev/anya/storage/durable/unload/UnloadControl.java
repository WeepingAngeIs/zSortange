package dev.anya.storage.durable.unload;
public interface UnloadControl {
    int anyaRetryCount();
    long[] anyaRetryStats();
}
