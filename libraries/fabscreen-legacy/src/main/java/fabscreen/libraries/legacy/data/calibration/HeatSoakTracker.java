package fabscreen.libraries.legacy.data.calibration;

/**
 * Screen-side estimate of the controller's heat soak, for the progress display.
 * <p>
 * The controller sends no progress while it heats and soaks (no 0x0a 0x03 until probing starts), so the
 * screen follows the bed temperature from the normal status reports and runs its own countdown once the bed
 * is within {@link LevelingParams#BED_REACHED_MARGIN} of the target (the controller waits like M190 R, so
 * it also waits for a hotter bed to cool down). The countdown is an estimate; the first probing point
 * (auto) or the start reply (manual) is the authoritative end, see {@link #finish()}.
 * <p>
 * Pure Java (no Android), unit-tested.
 */
public final class HeatSoakTracker {
    public static final int PHASE_HEATING = 1;
    /** Bed at temperature, countdown running. */
    public static final int PHASE_SOAKING = 2;
    /** Countdown over, waiting for the controller to home and start measuring. */
    public static final int PHASE_WAITING = 3;
    public static final int PHASE_DONE = 4;

    private final int mTargetTemp;
    private final int mSoakSeconds;
    private int mPhase;
    private long mSoakStartMs;
    private int mBedTemp;

    public HeatSoakTracker(int targetTemp, int soakSeconds) {
        mTargetTemp = targetTemp;
        mSoakSeconds = Math.max(0, soakSeconds);
        mPhase = targetTemp > 0 ? PHASE_HEATING : PHASE_DONE;
    }

    public static boolean isBedReached(int bedTemp, int targetTemp) {
        return Math.abs(bedTemp - targetTemp) <= LevelingParams.BED_REACHED_MARGIN;
    }

    /**
     * Feed the current bed temperature.
     *
     * @return true if the phase changed
     */
    public boolean update(int bedTemp, long nowMs) {
        final int oldPhase = mPhase;
        mBedTemp = bedTemp;

        if (mPhase == PHASE_HEATING && isBedReached(bedTemp, mTargetTemp)) {
            mSoakStartMs = nowMs;
            mPhase = mSoakSeconds > 0 ? PHASE_SOAKING : PHASE_WAITING;
        }
        if (mPhase == PHASE_SOAKING && getSecondsLeft(nowMs) == 0) {
            mPhase = PHASE_WAITING;
        }
        return oldPhase != mPhase;
    }

    /** Seconds of soak left (rounded up); the full soak while heating, 0 once the countdown is over. */
    public int getSecondsLeft(long nowMs) {
        switch (mPhase) {
            case PHASE_HEATING:
                return mSoakSeconds;
            case PHASE_SOAKING: {
                long elapsedMs = Math.max(0, nowMs - mSoakStartMs);
                long leftMs = mSoakSeconds * 1000L - elapsedMs;
                return leftMs <= 0 ? 0 : (int) ((leftMs + 999) / 1000);
            }
            default:
                return 0;
        }
    }

    /** The controller started probing (or answered): stop showing the soak. */
    public void finish() {
        mPhase = PHASE_DONE;
    }

    public int getPhase() {
        return mPhase;
    }

    public boolean isActive() {
        return mPhase != PHASE_DONE;
    }

    public int getTargetTemp() {
        return mTargetTemp;
    }

    public int getSoakSeconds() {
        return mSoakSeconds;
    }

    public int getBedTemp() {
        return mBedTemp;
    }
}
