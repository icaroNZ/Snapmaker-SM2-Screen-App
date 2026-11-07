package fabscreen.libraries.legacy.data.calibration;

import java.util.Locale;

/**
 * Ranges and payload encoding for heat-soaked bed levelling (A250T firmware SM2-5.2.1-a250t.3 and later).
 * <p>
 * Contract (see _snapmaker2-review/features/bed-levelling-heat-soak.md): the auto levelling request
 * 0x09 0x02 (and manual levelling 0x09 0x04) carries {@code <grid:u8> [<bed_temp:u16> <soak_s:u16>]},
 * big-endian. Length 1 is the stock form; length 5 adds heating and soaking. bed_temp 0 means no heating.
 * <p>
 * Pure Java on purpose (no Android, no Logger), so the JVM unit tests can use it.
 */
public final class LevelingParams {
    /** Smallest grid the firmware accepts from the screen. */
    public static final int FIRMWARE_GRID_MIN = 2;
    /** Smallest grid offered in the UI (the stock app offered 3, 4 and 5). */
    public static final int GRID_MIN = 3;
    /** Firmware GRID_MAX_NUM. */
    public static final int GRID_MAX = 11;
    public static final int GRID_DEFAULT = 3;

    /** Firmware kBedTempMax (clamps anything above). 0 = do not heat. */
    public static final int BED_TEMP_MAX = 110;
    public static final int BED_TEMP_DEFAULT = 60;
    public static final int BED_TEMP_STEP = 5;

    public static final int SOAK_MINUTES_MAX = 60;
    public static final int SOAK_MINUTES_DEFAULT = 5;
    /** Firmware clamps the soak to 3600 s. */
    public static final int SOAK_SECONDS_MAX = 3600;

    /** The UI is considered "at temperature" within this many degrees, like the stock pre-heat page. */
    public static final int BED_REACHED_MARGIN = 1;

    private LevelingParams() {
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    public static int clampGrid(int grid) {
        return clamp(grid, GRID_MIN, GRID_MAX);
    }

    public static int clampBedTemperature(int celsius) {
        return clamp(celsius, 0, BED_TEMP_MAX);
    }

    public static int clampSoakMinutes(int minutes) {
        return clamp(minutes, 0, SOAK_MINUTES_MAX);
    }

    public static boolean isFirmwareGridValid(int grid) {
        return grid >= FIRMWARE_GRID_MIN && grid <= GRID_MAX;
    }

    public static int soakMinutesToSeconds(int minutes) {
        return clampSoakMinutes(minutes) * 60;
    }

    /** Next bed temperature for a -/+ button: steps of 5 degrees, 0..110. */
    public static int stepBedTemperature(int celsius, boolean up) {
        int value = clampBedTemperature(celsius);
        if (up) {
            value = (value / BED_TEMP_STEP + 1) * BED_TEMP_STEP;
        } else {
            value = ((value + BED_TEMP_STEP - 1) / BED_TEMP_STEP - 1) * BED_TEMP_STEP;
        }
        return clampBedTemperature(value);
    }

    /** Next soak time for a -/+ button: 1 minute steps up to 10, then 5 minute steps up to 60. */
    public static int stepSoakMinutes(int minutes, boolean up) {
        int value = clampSoakMinutes(minutes);
        if (up) {
            value = value < 10 ? value + 1 : (value / 5 + 1) * 5;
        } else {
            value = value <= 10 ? value - 1 : ((value + 4) / 5 - 1) * 5;
        }
        return clampSoakMinutes(value);
    }

    /**
     * Payload (sub-op included) for 0x09 0x02 / 0x09 0x04.
     *
     * @param opcode      0x02 (auto) or 0x04 (manual)
     * @param grid        2..11
     * @param bedTemp     0..110 degrees C; 0 = no heating, sends the stock 1-byte form
     * @param soakSeconds 0..3600; ignored when bedTemp is 0
     * @throws IllegalArgumentException if a value is out of range
     */
    public static byte[] buildLevelingPayload(int opcode, int grid, int bedTemp, int soakSeconds) {
        if (!isFirmwareGridValid(grid)) {
            throw new IllegalArgumentException("grid out of range: " + grid);
        }
        if (bedTemp < 0 || bedTemp > BED_TEMP_MAX) {
            throw new IllegalArgumentException("bed temperature out of range: " + bedTemp);
        }
        if (soakSeconds < 0 || soakSeconds > SOAK_SECONDS_MAX) {
            throw new IllegalArgumentException("soak time out of range: " + soakSeconds);
        }

        if (bedTemp == 0) {
            return new byte[]{(byte) opcode, (byte) grid};
        }

        return new byte[]{
                (byte) opcode,
                (byte) grid,
                (byte) (bedTemp >> 8), (byte) bedTemp,
                (byte) (soakSeconds >> 8), (byte) soakSeconds,
        };
    }

    /** "mm:ss" for the soak countdown. */
    public static String formatCountdown(int seconds) {
        if (seconds < 0) seconds = 0;
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
