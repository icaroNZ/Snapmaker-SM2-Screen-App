package fabscreen.libraries.legacy.lib;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;

import io.reactivex.exceptions.UndeliverableException;

/**
 * Decides what the global RxJava error handler does with an error that no subscriber received
 * (SCR-05). Without a handler RxJava rethrows it on the current thread, which crashed FabScreen
 * (and stopped a screen-streamed print) for things like a serial write racing a disconnect.
 * Pure Java so it can be unit-tested.
 */
public final class RxErrorPolicy {
    public enum Action {
        /** Expected after a dispose/disconnect (I/O or interrupt): log at debug level only. */
        IGNORE,
        /** Anything else: log as an error with the stack, but keep the app running. */
        LOG
    }

    private RxErrorPolicy() {
    }

    public static Throwable unwrap(Throwable e) {
        if (e instanceof UndeliverableException && e.getCause() != null) {
            return e.getCause();
        }
        return e;
    }

    public static Action classify(Throwable e) {
        Throwable cause = unwrap(e);
        if (cause instanceof IOException
                || cause instanceof InterruptedIOException
                || cause instanceof SocketException
                || cause instanceof InterruptedException) {
            return Action.IGNORE;
        }
        return Action.LOG;
    }
}
