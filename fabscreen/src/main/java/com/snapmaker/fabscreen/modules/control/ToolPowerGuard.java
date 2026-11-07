package com.snapmaker.fabscreen.modules.control;

import com.orhanobut.logger.Logger;

import java.util.concurrent.TimeUnit;

import fabscreen.libraries.legacy.data.ISlaveComputer;
import io.reactivex.Observable;
import io.reactivex.disposables.Disposable;

/**
 * SCR-06/SCR-12: switch the laser beam or CNC spindle off with M5, best effort.
 * <p>
 * Used when the Control page is left by any route other than its Back button (e.g. a saved
 * Orca/Luban token reconnects and ViewSub jumps to the Remote page): the controller has no idle
 * auto-off, so the tool stayed on. The request is not bound to the fragment lifecycle (it is sent
 * while the page is going away), has a 2 s timeout and an error consumer, so a dead link can
 * neither crash the app nor block navigation.
 */
final class ToolPowerGuard {
    static final int OFF_TIMEOUT_S = 2;

    private ToolPowerGuard() {
    }

    /** Fire-and-forget M5. {@code tool} is "laser" or "spindle", {@code reason} why (back/tab/pause). */
    static Disposable switchOff(ISlaveComputer slaveComputer, String tool, String reason) {
        Logger.i("Control: %s OFF (%s)", tool, reason);
        return offRequest(slaveComputer, tool, reason)
                .subscribe(r -> { /* acknowledged */ }, e -> { /* logged in offRequest */ });
    }

    /** M5 with timeout; never errors (failures are logged and mapped to false). */
    static Observable<Boolean> offRequest(ISlaveComputer slaveComputer, String tool, String reason) {
        return slaveComputer.sendGcode("M5")
                .timeout(OFF_TIMEOUT_S, TimeUnit.SECONDS)
                .map(r -> true)
                .onErrorReturn(e -> {
                    Logger.w("Control: M5 for %s (%s) failed or timed out: %s", tool, reason, e);
                    return false;
                });
    }
}
