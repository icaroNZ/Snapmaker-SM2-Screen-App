package fabscreen.libraries.legacy.lib.fabserver;

import java.net.SocketException;
import java.net.SocketTimeoutException;

/**
 * Which HTTP server exceptions are routine and not worth a log line (pure Java, unit-tested).
 * Idle keep-alive connections time out and clients drop connections all the time (Orca polls
 * status every second); everything else is logged (SCR-20).
 */
public final class HttpErrorFilter {
    private HttpErrorFilter() {
    }

    public static boolean isRoutine(Exception ex) {
        if (ex == null) {
            return true;
        }
        if (ex instanceof SocketTimeoutException || ex instanceof SocketException) {
            return true;
        }
        // org.apache.httpcore.ConnectionClosedException (repackaged in AndServer): match by name so
        // this class does not depend on the repackaged type.
        String name = ex.getClass().getSimpleName();
        return name.equals("ConnectionClosedException");
    }
}
