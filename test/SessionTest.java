package dev.pixelambient.aod;

import java.util.HashMap;
import android.app.AlarmManager;
import android.hardware.display.DisplayManager;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import com.android.systemui.doze.DozeMachine;
import com.android.systemui.doze.DozeMachine.State;
import com.android.systemui.doze.DozeService;

public final class SessionTest {
    static int count;
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); count++; }
    static final class Store implements AodPreferences.Store {
        HashMap<String,String> values = new HashMap<>();
        String reject;
        int writes;
        public String read(String key) { return values.get(key); }
        public boolean write(String key, String value) {
            writes++;
            if (key.equals(reject)) return false;
            if (value == null) values.remove(key); else values.put(key, value);
            return true;
        }
    }

    public static void main(String[] args) throws Throwable {
        Store store = new Store();
        check(!AodPreferences.enabled(store) && AodPreferences.dimmed(store) && store.writes == 0, "read-only initial state");
        store.reject = AmbientSettings.ALWAYS_ON;
        try { AodPreferences.wallpaper(store, true); throw new AssertionError("accepted rejected write"); } catch (IllegalStateException expected) { }
        check(store.values.isEmpty(), "rejected second setting restored a missing first key");
        store.reject = null;
        AodPreferences.wallpaper(store, true);
        check(AodPreferences.enabled(store), "wallpaper and always-on enabled");
        int writes = store.writes; AodPreferences.wallpaper(store, true);
        check(writes == store.writes, "redundant request does not write");
        AodPreferences.shade(store, false);
        check(AodPreferences.enabled(store) && !AodPreferences.dimmed(store), "shade does not change master");
        AodPreferences.wallpaper(store, false);
        check(!AodPreferences.enabled(store) && "1".equals(store.read(AmbientSettings.ALWAYS_ON)), "ordinary clock preserved");

        AodTimeout.install(SessionTest.class.getClassLoader());
        check(AodTimeout.ready, "hooks installed");
        DozeService dream = new DozeService();
        DozeMachine machine = dream.mDozeMachine;
        DisplayManager displays = dream.getSystemService(DisplayManager.class);
        AlarmManager alarms = dream.getSystemService(AlarmManager.class);
        dream.start();
        machine.requestState(State.DOZE_AOD);
        check(alarms.count == 0, "no deadline before the screen enters AOD");
        SystemClock.now = 1123; displays.show(Display.STATE_DOZE_SUSPEND);
        check(alarms.at == 11123 && alarms.count == 1, "deadline is ten seconds after visible AOD");
        machine.requestState(State.DOZE_AOD_PAUSING); machine.requestState(State.DOZE_AOD);
        check(alarms.at == 11123 && alarms.count == 1, "proximity and state chatter do not extend AOD");
        SystemClock.now = 11122; machine.requestState(State.DOZE_AOD);
        check(machine.mState == State.DOZE_AOD, "not off at 9999 milliseconds");
        SystemClock.now++; alarms.listener.onAlarm();
        check(machine.mState == State.DOZE, "native screen-off at deadline");
        machine.requestState(State.DOZE_AOD);
        check(machine.mState == State.DOZE, "no immediate AOD re-entry");
        machine.requestState(State.DOZE_PULSING);
        check(machine.mState == State.DOZE_PULSING, "notification pulse remains native");
        machine.requestState(State.DOZE_AOD);
        check(machine.mState == State.DOZE, "pulse restores off after deadline");
        dream.stop(); dream.start(); machine.requestState(State.DOZE_AOD);
        AlarmManager.OnAlarmListener stale = alarms.listener;
        check(alarms.at == 21123, "new lock receives a fresh window");
        dream.stop(); SystemClock.now = 12000; dream.start(); machine.requestState(State.DOZE_AOD);
        SystemClock.now = 21123; stale.onAlarm();
        check(machine.mState == State.DOZE_AOD && alarms.at == 22000, "stale callback cannot turn off new lock");
        dream.getSystemService(PowerManager.class).interactive = true; SystemClock.now = 22000; alarms.listener.onAlarm();
        check(machine.mState == State.DOZE_AOD, "waking display is not put back to sleep");
        dream.stop(); dream.getSystemService(PowerManager.class).interactive = false;
        SystemClock.now++; dream.start(); machine.requestState(State.DOZE_AOD);
        AmbientSettings.on = false; SystemClock.now += 10000; alarms.listener.onAlarm();
        check(machine.mState == State.DOZE_AOD, "turning feature off cancels pending control");
        dream.stop(); int total = alarms.count; dream.start(); machine.requestState(State.DOZE_AOD);
        check(alarms.count == total && machine.mState == State.DOZE_AOD, "ordinary AOD not timed");
        dream.stop(); check(displays.listener == null, "observer removed");
        AmbientSettings.on = true; dream.start(); machine.requestState(State.DOZE); displays.show(Display.STATE_OFF);
        SystemClock.now = 40000; total = alarms.count;
        machine.requestState(State.DOZE_REQUEST_PULSE,1);
        check(machine.mState == State.DOZE_REQUEST_PULSE && alarms.count == total, "native notification from black");
        machine.requestState(State.DOZE); machine.requestState(State.DOZE_REQUEST_PULSE,10);
        check(machine.mState == State.DOZE_REQUEST_PULSE && alarms.count == total, "fingerprint is not pickup");
        machine.requestState(State.DOZE); machine.requestState(State.DOZE_REQUEST_PULSE,3);
        check(machine.mState == State.DOZE_AOD && alarms.count == total, "accepted dark pickup awaits visible panel");
        SystemClock.now = 40150; displays.show(Display.STATE_DOZE);
        check(alarms.at == 50150 && alarms.count == ++total, "pickup measured from first visible AOD");
        SystemClock.now = 45000; machine.requestState(State.DOZE_REQUEST_PULSE,3);
        check(machine.mState == State.DOZE_AOD && alarms.count == total && alarms.at == 50150, "repeat pickup cannot extend window");
        SystemClock.now = 50150; AlarmManager.OnAlarmListener firstPickup = alarms.listener; firstPickup.onAlarm();
        check(machine.mState == State.DOZE, "pickup expires to native black");
        displays.show(Display.STATE_OFF); SystemClock.now++; machine.requestState(State.DOZE_REQUEST_PULSE,3); displays.show(Display.STATE_DOZE_SUSPEND);
        check(machine.mState == State.DOZE_AOD && alarms.at == 60151 && alarms.count == ++total, "second pickup re-arms within same dream");
        firstPickup.onAlarm(); check(machine.mState == State.DOZE_AOD, "old pickup callback cannot close new window");
        dream.stop(); dream.start(); machine.requestState(State.DOZE); dream.getSystemService(PowerManager.class).interactive=true;
        machine.requestState(State.DOZE_REQUEST_PULSE,3); check(machine.mState == State.DOZE_REQUEST_PULSE && alarms.count == total, "real wake is untouched");
        dream.stop(); dream.start(); dream.getSystemService(PowerManager.class).interactive=false; AmbientSettings.on=false; machine.requestState(State.DOZE);
        machine.requestState(State.DOZE_REQUEST_PULSE,3); check(machine.mState == State.DOZE_REQUEST_PULSE && alarms.count == total, "master off leaves pickup native");
        dream.stop();
        System.out.println("PASS: " + count + " settings and lifecycle assertions");
    }
}
