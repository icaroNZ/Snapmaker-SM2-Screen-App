package fabscreen.libraries.legacy.server.http.handlers;


import static fabscreen.libraries.legacy.data.remote.SessionManager.NULL_SESSION;

import android.os.SystemClock;

import com.orhanobut.logger.Logger;
import com.yanzhenjie.andserver.framework.body.StringBody;
import com.yanzhenjie.andserver.http.HttpRequest;
import com.yanzhenjie.andserver.http.HttpResponse;

import fabscreen.libraries.legacy.BaseApplication;
import fabscreen.libraries.legacy.data.Model;
import fabscreen.libraries.legacy.data.remote.SessionManager;
import fabscreen.libraries.legacy.server.http.HttpGate;

public class BaseRequestHandler {
    /** SCR-13: longest an HTTP handler waits for a controller reply (pause/stop/G-code). */
    protected static final int HTTP_COMMAND_TIMEOUT_S = 30;
    /** SCR-13: longest execute_code waits for "ok" (G28 homing on an A350 can take a while). */
    protected static final int HTTP_GCODE_TIMEOUT_S = 120;
    /** SCR-08/13: longest an HTTP handler waits for a G-code file to be parsed. */
    protected static final int HTTP_PARSE_TIMEOUT_S = 120;

    protected Model getModel() {
        return BaseApplication.getInstance().getModel();
    }

    protected boolean ensureConnection(HttpRequest request, HttpResponse response) {
        String token = request.getParameter("token");
        if (token == null) {
            response.setStatus(HttpResponse.SC_BAD_REQUEST);
            return false;
        }

        SessionManager.Session currentSession = getModel().getRemoteController().getCurrentSession();

        // check if current session
        if (currentSession != NULL_SESSION && currentSession.getToken().equals(token)) {
            if (currentSession.isGranted()) {
                // session is granted, success
                getModel().getRemoteController().accessCurrentSession();
                return true;
            } else {
                getModel().getRemoteController().accessCurrentSession();
                // If session is pending granted, return (204)
                response.setStatus(HttpResponse.SC_NO_CONTENT);
                return false;
            }
        } else {
            // If Auth doesn't exist, deny request (401)
            response.setBody(new StringBody("Machine is not connected yet."));
            response.setStatus(HttpResponse.SC_UNAUTHORIZED);
            return false;
        }
    }

    /**
     * SCR-01/SCR-04: refuse (409) when a job is running or paused, so an HTTP call cannot move the
     * machine or replace/reset the running job. Returns true when the request was refused.
     */
    protected boolean refuseIfBusy(HttpRequest request, HttpResponse response) {
        int printerStatus = getModel().getMachineController().getMachineStatus().printerStatus;
        int jobState = getModel().getPrintController().getPrintJobState().getValue();
        if (HttpGate.isBusy(printerStatus, jobState)) {
            Logger.w("HTTP %s refused (409): machine busy (printerStatus %d, job state %d, token %s)",
                    request.getPath(), printerStatus, jobState, HttpGate.tokenPrefix(request.getParameter("token")));
            response.setStatus(HttpResponse.SC_CONFLICT);
            response.setBody(new StringBody("Machine is busy (a job is running or paused)."));
            return true;
        }
        return false;
    }

    /**
     * SCR-01: token check for the legacy camera routes, behind HttpGate.REQUIRE_TOKEN_FOR_LEGACY_CAMERA_ROUTES.
     */
    protected boolean ensureLegacyCameraAccess(HttpRequest request, HttpResponse response) {
        if (!HttpGate.REQUIRE_TOKEN_FOR_LEGACY_CAMERA_ROUTES) {
            return true;
        }
        if (ensureConnection(request, response)) {
            return true;
        }
        Logger.w("HTTP %s refused: no approved token (UA %s)", request.getPath(), request.getHeader("User-Agent"));
        return false;
    }

    protected void logResponseCostTime(HttpRequest request, long startTime) {
        long intervalTime = SystemClock.elapsedRealtime() - startTime;
        if (intervalTime > 500) {
            Logger.e("API: %s using %d ms", request.getPath(), intervalTime);
        } else if (intervalTime > 50) {
            Logger.w("API: %s using %d ms", request.getPath(), intervalTime);
        }

    }
}
