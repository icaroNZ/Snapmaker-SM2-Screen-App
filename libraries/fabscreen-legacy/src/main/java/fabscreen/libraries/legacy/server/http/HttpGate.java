package fabscreen.libraries.legacy.server.http;

import java.util.Locale;

/**
 * Pure-Java checks shared by the HTTP handlers (no Android, unit-tested): busy gate, motion
 * limits, upload file names, browser cross-site detection, token redaction, and the HTTP
 * security switches.
 */
public final class HttpGate {

    // ---- Security switches (build-time config; see the repo CLAUDE.md "HTTP API behaviour") ----

    /**
     * SCR-01/SCR-08: refuse any request that a web browser marks as cross-site (an {@code Origin}
     * header, or {@code Sec-Fetch-Site: cross-site|same-site}). Native clients (Orca, Luban's
     * server, Cura, curl) send neither, so they are unaffected; a web page on any site can no
     * longer start a print or move the machine through this server. Default ON.
     */
    public static final boolean REJECT_BROWSER_CROSS_SITE_REQUESTS = true;

    /**
     * SCR-01: require an approved token ({@code ?token=}) on the legacy, non-/v1 camera routes that
     * move the machine or overwrite the camera calibration ({@code /api/request_capture_photo},
     * {@code /api/set_camera_calibration_matrix}, {@code /api/set_10w_laser_camera_calibration_matrix}).
     * Default ON (safer). NOTE: Luban's image-getPhoto.js calls these WITHOUT a token, so Luban's
     * Wi-Fi camera capture / calibration needs a Luban change (pass the token) or this switch OFF.
     */
    public static final boolean REQUIRE_TOKEN_FOR_LEGACY_CAMERA_ROUTES = true;

    /**
     * SCR-08: require an approved token ({@code ?token=} or the OctoPrint {@code X-Api-Key} header)
     * on the OctoPrint-compatible {@code POST /api/files/local}. Default OFF: Orca's OctoPrint host
     * pointed at :8080 with a blank API key (suggested in investigations/orca-octoprint-403.md)
     * would stop working; the owner's host type is unconfirmed (fix list H-2). Cross-site browser
     * requests are refused regardless (REJECT_BROWSER_CROSS_SITE_REQUESTS).
     */
    public static final boolean REQUIRE_TOKEN_FOR_OCTOPRINT_UPLOAD = false;

    // ---- Motion limits ----

    public static final int MIN_FEED_RATE = 100;   // mm/min
    public static final int MAX_FEED_RATE = 6000;  // mm/min

    private HttpGate() {
    }

    /**
     * Whether a job is active and the machine must not be moved, nor its workspace file replaced
     * (SCR-01, SCR-04). {@code printerStatus} is the controller status (0 = idle);
     * {@code jobState} is {@link fabscreen.libraries.legacy.data.print.MachinePrintJobState}'s
     * value. Only states with a live job count (starting, printing, pausing, paused, recovering,
     * resuming); a stale STOPPED/FINISHED must not lock uploads out.
     */
    public static boolean isBusy(int printerStatus, int jobState) {
        if (printerStatus != 0) {
            return true;
        }
        switch (jobState) {
            case 1:  // STARTING
            case 2:  // PRINTING
            case 3:  // PAUSING
            case 4:  // PAUSED
            case 9:  // RECOVERING
            case 10: // RESUMING
                return true;
            default:
                return false;
        }
    }

    public static int clampFeedRate(int feedRate) {
        return Math.max(MIN_FEED_RATE, Math.min(MAX_FEED_RATE, feedRate));
    }

    /** Clamp to [min, max]; NaN becomes min. */
    public static float clamp(float value, float min, float max) {
        if (Float.isNaN(value)) {
            return min;
        }
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Base name of a client-supplied upload name, or null when it is unsafe (SCR-08): any
     * directory part is stripped, and empty, ".", "..", dot-prefixed names, names with control
     * characters or longer than 255 characters are refused. Unicode and punctuation are allowed
     * (slicers put the project name in the file name).
     */
    public static String sanitizeFileName(String name) {
        if (name == null) {
            return null;
        }
        String base = name;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.trim();
        if (base.isEmpty() || base.startsWith(".")) {
            return null;
        }
        if (base.length() > 255) {
            return null;
        }
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            if (c < 0x20 || c == 0x7f || c == ':') {
                return null;
            }
        }
        return base;
    }

    /**
     * Whether a browser marked this request as coming from another site. Same-origin requests and
     * requests without these headers (native clients) are allowed.
     */
    public static boolean isCrossSiteBrowserRequest(String origin, String secFetchSite) {
        if (secFetchSite != null) {
            String s = secFetchSite.trim().toLowerCase(Locale.US);
            if (s.equals("cross-site") || s.equals("same-site")) {
                return true;
            }
            if (s.equals("same-origin") || s.equals("none")) {
                return false;
            }
        }
        return origin != null && !origin.trim().isEmpty();
    }

    /**
     * Whether an upload of {@code contentLength} bytes leaves {@code reserveBytes} free (SCR-09).
     * An unknown length (-1, chunked) is let through: nothing is deleted any more, and the write
     * itself fails if the disk fills up.
     */
    public static boolean uploadFits(long contentLength, long availableBytes, long reserveBytes) {
        if (contentLength < 0) {
            return true;
        }
        return contentLength + reserveBytes <= availableBytes;
    }

    /** First 8 characters of a token for logs (SCR-20: never log a full token). */
    public static String tokenPrefix(String token) {
        if (token == null) {
            return "none";
        }
        return token.length() <= 8 ? token : token.substring(0, 8) + "...";
    }
}
