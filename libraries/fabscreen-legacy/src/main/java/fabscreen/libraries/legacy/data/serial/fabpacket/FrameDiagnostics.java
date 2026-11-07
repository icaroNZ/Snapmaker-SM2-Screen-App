package fabscreen.libraries.legacy.data.serial.fabpacket;

import java.util.Locale;

/**
 * Pure-Java helpers to describe and sanity-check SSTP frames in logs (no Android dependencies, so
 * they run in host unit tests).
 */
public final class FrameDiagnostics {
    /** At most this many content bytes are printed as hex. */
    public static final int MAX_HEX_BYTES = 48;

    private FrameDiagnostics() {
    }

    /**
     * One-line description of a frame, e.g. {@code eid=0x0A op=0x37 len=2 hex=3700}.
     * Safe for null or truncated frames.
     */
    public static String describe(FabPacket packet) {
        if (packet == null) {
            return "frame=null";
        }
        byte[] content = packet.getContent();
        int len = content == null ? 0 : content.length;
        String op = len > 0 ? String.format(Locale.US, "0x%02X", content[0] & 0xFF) : "none";
        return String.format(Locale.US, "eid=0x%02X op=%s len=%d hex=%s",
                packet.getEventId() & 0xFF, op, len, toHex(content, MAX_HEX_BYTES));
    }

    /** Hex of the first {@code max} bytes, with "..." when truncated. */
    public static String toHex(byte[] bytes, int max) {
        if (bytes == null) {
            return "";
        }
        int n = Math.min(bytes.length, Math.max(0, max));
        StringBuilder sb = new StringBuilder(n * 2 + 3);
        for (int i = 0; i < n; i++) {
            sb.append(String.format(Locale.US, "%02x", bytes[i] & 0xFF));
        }
        if (n < bytes.length) {
            sb.append("...");
        }
        return sb.toString();
    }

    /**
     * Whether a received frame carries the operation byte that the reply dispatcher indexes.
     * Event ids 0x01-0x04 have no operation byte; every other event id needs at least one content
     * byte (SerialController.onReceive reads content[0]).
     */
    public static boolean hasOperationByte(FabPacket packet) {
        if (packet == null) {
            return false;
        }
        if ((packet.getEventId() & 0xFF) <= 0x04) {
            return true;
        }
        byte[] content = packet.getContent();
        return content != null && content.length > 0;
    }
}
