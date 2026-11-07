package fabscreen.libraries.legacy.server.http;

import androidx.annotation.NonNull;

import com.orhanobut.logger.Logger;
import com.yanzhenjie.andserver.annotation.Interceptor;
import com.yanzhenjie.andserver.framework.HandlerInterceptor;
import com.yanzhenjie.andserver.framework.body.StringBody;
import com.yanzhenjie.andserver.framework.handler.RequestHandler;
import com.yanzhenjie.andserver.http.HttpRequest;
import com.yanzhenjie.andserver.http.HttpResponse;

@Interceptor
public class HTTPInterceptor implements HandlerInterceptor {

    /**
     * SCR-01/SCR-08: no "Access-Control-Allow-Origin: *" any more (no client is a browser: Orca,
     * Luban's server and Cura are native), and requests a browser marks as cross-site are refused,
     * so a web page can no longer start a print, upload a file or move the machine through
     * this server. Returns true (handled) when the request is refused.
     */
    @Override
    public boolean onIntercept(@NonNull HttpRequest request, @NonNull HttpResponse response, @NonNull RequestHandler handler) {
        if (HttpGate.REJECT_BROWSER_CROSS_SITE_REQUESTS) {
            String origin = request.getHeader("Origin");
            String fetchSite = request.getHeader("Sec-Fetch-Site");
            if (HttpGate.isCrossSiteBrowserRequest(origin, fetchSite)) {
                Logger.w("HTTP: refused cross-site browser request %s %s (Origin %s, Sec-Fetch-Site %s, UA %s)",
                        request.getMethod(), request.getPath(), origin, fetchSite, request.getHeader("User-Agent"));
                response.setStatus(HttpResponse.SC_FORBIDDEN);
                response.setBody(new StringBody("Cross-site browser requests are not allowed."));
                return true;
            }
        }
        return false;
    }
}
