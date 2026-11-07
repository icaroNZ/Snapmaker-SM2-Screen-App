package fabscreen.libraries.legacy.data.calibration;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Separate bed-mesh measurement and Z-offset calibration (A250T firmware SM2-5.2.1-a250t.4 and later).
 * <p>
 * Contract (see _snapmaker2-review/features/separate-mesh-and-z-offset.md), SSTP, big-endian:
 * <ul>
 * <li>Auto levelling 0x09 0x02 {@code <grid:u8> <bed_temp:u16> <soak_s:u16> <flags:u8>}; flags bit 0 = apply
 * the stored Z offset. With bit 0 the reply is {@code 0A 02 <err> <status> <offset_um:i32>}; firmware without
 * the feature answers the 1-byte {@code 0A 02 <err>} (card step needed).</li>
 * <li>Calibrate Z offset 0x09 0x37 [{@code <bed_temp:u16> <soak_s:u16>}], reply {@code 0A 37 <err>}; then jog
 * 0x09 0x06, save 0x09 0x07 ({@code 0A 07 <err>}, 0 = stored) or exit 0x09 0x08.</li>
 * <li>Query Z offset 0x09 0x38, reply {@code 0A 38 <err=0> <status> <offset_um:i32>}.</li>
 * </ul>
 * Pure Java on purpose (no Android, no Logger), so the JVM unit tests can use it.
 */
public final class ZOffsetParams {
    public static final int OP_AUTO_LEVELING = 0x02;
    public static final int OP_ZOFFSET_CALIBRATION = 0x37;
    public static final int OP_QUERY_ZOFFSET = 0x38;

    /** 0x09 0x02 flags byte, bit 0: apply the stored Z offset and finish without the card step. */
    public static final int FLAG_USE_STORED_OFFSET = 0x01;

    /** Status byte in every reply: valid / applied. */
    public static final int STATUS_VALID = 0;
    /** Never calibrated, cleared (G1029 Z0 / M502) or migrated from older firmware. */
    public static final int STATUS_NOT_SET = 1;
    /** Stored offset is larger than 5 mm. */
    public static final int STATUS_OUT_OF_RANGE = 2;
    /** Calibrated with a different 3DP toolhead. */
    public static final int STATUS_OTHER_TOOLHEAD = 3;

    /** First firmware with the feature: SM2-5.2.1-a250t.4 (the screen sees "V5.2.1-a250t.4"). */
    private static final int[] MIN_BASE_VERSION = {5, 2, 1};
    private static final int MIN_A250T_BUILD = 4;
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)-a250t\\.(\\d+)", Pattern.CASE_INSENSITIVE);

    private ZOffsetParams() {
    }

    /**
     * Decoded {@code 0A 02} (auto levelling) or {@code 0A 38} (query) reply.
     */
    public static final class StatusReply {
        /** Controller error code, 0 = OK. */
        public final int err;
        /** One of the STATUS_* values; STATUS_NOT_SET for a short (legacy) reply. */
        public final int status;
        /** Stored offset in micrometres (signed). 0 for a short reply. */
        public final int offsetUm;
        /** True when the 6-byte form was received (the controller supports the feature). */
        public final boolean extended;

        public StatusReply(int err, int status, int offsetUm, boolean extended) {
            this.err = err;
            this.status = status;
            this.offsetUm = offsetUm;
            this.extended = extended;
        }

        public boolean isOk() {
            return err == 0;
        }

        /** Levelling finished with the stored offset: no card step, no 0x07/0x08. */
        public boolean isOffsetApplied() {
            return err == 0 && extended && status == STATUS_VALID;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "err %d, status %d (%s), offset %s mm%s", err, status,
                    statusText(status), formatMm(offsetUm), extended ? "" : ", short reply");
        }
    }

    /**
     * Payload (sub-op included) for 0x09 0x02 with the flags byte. Always the full 6-byte form, also
     * without heating (bed 0, soak 0), because the firmware only reads the flags at payload index 5.
     *
     * @throws IllegalArgumentException grid not 2..11, bedTemp not 0..110 or soakSeconds not 0..3600
     */
    public static byte[] buildAutoLevelPayload(int grid, int bedTemp, int soakSeconds, boolean useStoredOffset) {
        // range checks are the same as the heat-soak request
        LevelingParams.buildLevelingPayload(OP_AUTO_LEVELING, grid, bedTemp, soakSeconds);
        // the firmware ignores (and logs) a soak time without a temperature
        final int soak = bedTemp == 0 ? 0 : soakSeconds;
        return new byte[]{
                (byte) OP_AUTO_LEVELING,
                (byte) grid,
                (byte) (bedTemp >> 8), (byte) bedTemp,
                (byte) (soak >> 8), (byte) soak,
                (byte) (useStoredOffset ? FLAG_USE_STORED_OFFSET : 0),
        };
    }

    /**
     * Payload (sub-op included) for 0x09 0x37: no fields without heating, else bed_temp and soak_s.
     *
     * @throws IllegalArgumentException bedTemp not 0..110 or soakSeconds not 0..3600
     */
    public static byte[] buildZOffsetCalibrationPayload(int bedTemp, int soakSeconds) {
        if (bedTemp < 0 || bedTemp > LevelingParams.BED_TEMP_MAX) {
            throw new IllegalArgumentException("bed temperature out of range: " + bedTemp);
        }
        if (soakSeconds < 0 || soakSeconds > LevelingParams.SOAK_SECONDS_MAX) {
            throw new IllegalArgumentException("soak time out of range: " + soakSeconds);
        }
        if (bedTemp == 0) {
            return new byte[]{(byte) OP_ZOFFSET_CALIBRATION};
        }
        return new byte[]{
                (byte) OP_ZOFFSET_CALIBRATION,
                (byte) (bedTemp >> 8), (byte) bedTemp,
                (byte) (soakSeconds >> 8), (byte) soakSeconds,
        };
    }

    /** Payload (sub-op included) for 0x09 0x38. */
    public static byte[] buildQueryPayload() {
        return new byte[]{(byte) OP_QUERY_ZOFFSET};
    }

    /**
     * Decode the content of a {@code 0A 02} or {@code 0A 38} reply (content[0] is the sub-op).
     * 7+ bytes = extended form; 2..6 bytes = only the error byte is used (legacy 1-byte reply);
     * 1 byte = no error byte, treated as err 0.
     *
     * @return null if content is null or empty
     */
    public static StatusReply parseStatusReply(byte[] content) {
        if (content == null || content.length < 1) {
            return null;
        }
        final int err = content.length > 1 ? content[1] & 0xFF : 0;
        if (content.length < 7) {
            return new StatusReply(err, STATUS_NOT_SET, 0, false);
        }
        final int status = content[2] & 0xFF;
        final int offsetUm = ((content[3] & 0xFF) << 24) | ((content[4] & 0xFF) << 16)
                | ((content[5] & 0xFF) << 8) | (content[6] & 0xFF);
        return new StatusReply(err, status, offsetUm, true);
    }

    /** Short reply ({@code 0A xx <err>}) error byte, 0 if missing. */
    public static int parseErr(byte[] content) {
        return content != null && content.length > 1 ? content[1] & 0xFF : 0;
    }

    /**
     * True if the controller version reported by 0xa9 0x03 (e.g. "V5.2.1-a250t.4") is SM2-5.2.1-a250t.4
     * or later. Stock and older a250t versions return false (they do not know 0x37/0x38).
     */
    public static boolean isSupportedControllerVersion(String version) {
        if (version == null) {
            return false;
        }
        Matcher m = VERSION_PATTERN.matcher(version);
        if (!m.find()) {
            return false;
        }
        final int[] base = {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
        final int build = Integer.parseInt(m.group(4));
        for (int i = 0; i < base.length; i++) {
            if (base[i] != MIN_BASE_VERSION[i]) {
                return base[i] > MIN_BASE_VERSION[i];
            }
        }
        return build >= MIN_A250T_BUILD;
    }

    /** Micrometres as millimetres with 3 decimals, e.g. 1234 -> "1.234", -50 -> "-0.050". */
    public static String formatMm(int offsetUm) {
        return String.format(Locale.US, "%.3f", offsetUm / 1000.0);
    }

    /** English status text for logs. */
    public static String statusText(int status) {
        switch (status) {
            case STATUS_VALID:
                return "valid";
            case STATUS_OUT_OF_RANGE:
                return "out of range";
            case STATUS_OTHER_TOOLHEAD:
                return "other toolhead";
            case STATUS_NOT_SET:
            default:
                return "not set";
        }
    }
}
