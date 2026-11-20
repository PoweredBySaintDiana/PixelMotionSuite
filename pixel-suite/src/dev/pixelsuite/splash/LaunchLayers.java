package dev.pixelsuite.splash;

import java.util.LinkedHashMap;

final class LaunchLayers<Key,Value> {
    private final LinkedHashMap<Key,Entry<Value>> entries=new LinkedHashMap<>();

    private static final class Entry<Value> {
        final long time;
        final String target;
        final Value value;
        Entry(long time,String target,Value value) { this.time=time; this.target=target; this.value=value; }
    }

    void put(Key key,long time,String target,Value value,long now) {
        if (!SplashPolicy.fresh(time,now) || key==null || target==null || value==null) return;
        if (!entries.containsKey(key) && entries.size()>=8) entries.remove(entries.keySet().iterator().next());
        entries.put(key,new Entry<>(time,target,value));
    }

    Value take(Key key,String target,long now) {
        Entry<Value> entry=entries.get(key);
        if (entry==null) return null;
        if (!SplashPolicy.fresh(entry.time,now)) { entries.remove(key); return null; }
        if (!entry.target.equals(target)) return null;
        entries.remove(key); return entry.value;
    }

    void discard(Key key,long time) {
        Entry<Value> entry=entries.get(key); if (entry!=null && entry.time==time) entries.remove(key);
    }
}
