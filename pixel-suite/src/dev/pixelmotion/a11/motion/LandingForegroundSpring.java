package dev.pixelmotion.a11.motion;

/** The foreground's final motion, expressed relative to its resting desktop position. */
final class LandingForegroundSpring {
    private static final double OMEGA = Math.sqrt(200.0);
    private static final double DAMPING_RATIO = .55;
    private static final double DECAY = DAMPING_RATIO * OMEGA;
    private static final double RING = OMEGA * Math.sqrt(1.0 - DAMPING_RATIO * DAMPING_RATIO);

    private double x, y, velocityX, velocityY;
    private boolean critical;
    private boolean settled = true;

    void start(float x, float y, float velocityX, float velocityY) {
        critical = false;
        if (!Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(velocityX) || !Float.isFinite(velocityY)) {
            clear();
            return;
        }
        this.x = x;
        this.y = y;
        this.velocityX = velocityX;
        this.velocityY = velocityY;
        settled = x == 0f && y == 0f && velocityX == 0f && velocityY == 0f;
    }

    /** Keep the current position and velocity while suppressing later oscillations. */
    void settle() {
        critical = true;
    }

    /** Advance by animation time in seconds, after any system duration scaling. */
    boolean advance(float seconds) {
        if (settled) return false;
        if (!Float.isFinite(seconds) || seconds <= 0f) return true;
        double dt = seconds;
        if (critical) {
            double decay = Math.exp(-OMEGA * dt);
            double qx = velocityX + OMEGA * x;
            double qy = velocityY + OMEGA * y;
            x = decay * (x + qx * dt);
            y = decay * (y + qy * dt);
            velocityX = decay * (velocityX - OMEGA * qx * dt);
            velocityY = decay * (velocityY - OMEGA * qy * dt);
        } else {
            double decay = Math.exp(-DECAY * dt);
            double cosine = Math.cos(RING * dt);
            double sineOverRing = Math.sin(RING * dt) / RING;
            double nextX = decay * (x * cosine + (velocityX + DECAY * x) * sineOverRing);
            double nextY = decay * (y * cosine + (velocityY + DECAY * y) * sineOverRing);
            velocityX = decay * (velocityX * cosine
                    - (DECAY * velocityX + OMEGA * OMEGA * x) * sineOverRing);
            velocityY = decay * (velocityY * cosine
                    - (DECAY * velocityY + OMEGA * OMEGA * y) * sineOverRing);
            x = nextX;
            y = nextY;
        }
        if (Math.abs(x) + Math.abs(y) < .1
                && Math.abs(velocityX) + Math.abs(velocityY) < 1.0) {
            clear();
            return false;
        }
        return true;
    }

    private void clear() {
        x = y = velocityX = velocityY = 0.0;
        settled = true;
    }

    boolean isSettled() { return settled; }
    float x() { return (float) x; }
    float y() { return (float) y; }
    float velocityX() { return (float) velocityX; }
    float velocityY() { return (float) velocityY; }
}
