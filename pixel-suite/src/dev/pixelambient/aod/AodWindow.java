package dev.pixelambient.aod;

final class AodWindow {
    static final long DURATION = 10_000L;
    private long deadline = -1L;

    boolean start(String state, long now) {
        if (deadline >= 0 || !visible(state)) return false;
        deadline = now + DURATION;
        return true;
    }

    long deadline() { return deadline; }
    boolean expired(long now) { return deadline >= 0 && now >= deadline; }
    boolean suppress(String state, long now) { return expired(now) && ambient(state); }
    void reset() { deadline = -1L; }

    static boolean visible(String state) {
        return "DOZE_AOD".equals(state) || "DOZE_AOD_DOCKED".equals(state) || "DOZE_AOD_MINMODE".equals(state);
    }

    private static boolean ambient(String state) {
        return visible(state) || "DOZE_AOD_PAUSING".equals(state) || "DOZE_AOD_PAUSED".equals(state);
    }
}
