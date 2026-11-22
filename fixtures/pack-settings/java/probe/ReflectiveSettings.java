package probe;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.minecraft.server.level.ServerPlayer;

/** The pack-facing code in this check has no compile-time reference to a Storage class. */
final class ReflectiveSettings {
    private final Class<?> config=Class.forName("dev.anya.storage.Config");
    private final Class<?> settings=Class.forName("dev.anya.storage.ServerSettings");
    private final Method keys=settings.getMethod("keys"), definition=settings.getMethod("definition",String.class);
    private final Method read=config.getMethod("serverSettings",ServerPlayer.class), status=config.getMethod("serverSettingsStatus",ServerPlayer.class);
    private final Method write=config.getMethod("applyServerSetting",ServerPlayer.class,long.class,String.class,String.class,boolean.class);
    ReflectiveSettings() throws ReflectiveOperationException {}

    @SuppressWarnings("unchecked")
    List<String> keys() throws ReflectiveOperationException{return (List<String>)keys.invoke(null);}
    Object definition(String key)throws ReflectiveOperationException{return definition.invoke(null,key);}
    int version()throws ReflectiveOperationException{return config.getField("PACK_SETTINGS_API").getInt(null);}
    Object status(ServerPlayer player)throws ReflectiveOperationException{return status.invoke(null,player);}
    Object snapshot(ServerPlayer player)throws ReflectiveOperationException{return read.invoke(null,player);}
    Object set(ServerPlayer player,long revision,String key,String value,boolean confirmed)throws ReflectiveOperationException{
        return write.invoke(null,player,revision,key,value,confirmed);
    }
    static Object field(Object record,String name)throws ReflectiveOperationException{return record.getClass().getMethod(name).invoke(record);}
    static long revision(Object record)throws ReflectiveOperationException{return ((Number)field(record,"revision")).longValue();}
    @SuppressWarnings("unchecked")
    static Map<String,Object> values(Object record)throws ReflectiveOperationException{return (Map<String,Object>)field(record,"values");}
}
