package fabscreen.libraries.legacy.server.socket;


import android.content.Context;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;

import com.orhanobut.logger.Logger;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.nio.charset.Charset;

import fabscreen.libraries.legacy.BaseApplication;
import fabscreen.libraries.legacy.data.Model;
import fabscreen.libraries.legacy.data.serial.fabpacket.FabPacketContent;
import fabscreen.libraries.legacy.lib.LogHelper;
import okio.Buffer;
import okio.ByteString;


/**
 * Discover Server: Socket server for device discovery.
 * <p>
 * Start server:
 * discoverServer = new DiscoverServer(context, "Snapmaker");
 * discoverServer.start()
 */
public class DiscoverServer extends Thread {
    private static final int BIND_PORT = 20054;
    private static final String DISCOVER_MESSAGE = "discover";
    private static final long BIND_RETRY_MS = 5000;

    private WifiManager mWifiManager;
    private volatile DatagramSocket mSocket;

    /**
     * DiscoverServer: bind socket and wait for client to search
     *
     * @param context     Use for creating WifiManager to get current ip
     */
    public DiscoverServer(Context context) {
        mWifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
    }

    /**
     * SCR-21: the thread used to die for good on the first IOException (bind, receive or send), so
     * discovery stopped until the app restarted. Now binding is retried every 5 s, a failed send
     * only skips that reply, and a failed socket is re-bound.
     */
    public void run() {
        while (!isInterrupted()) {
            DatagramSocket socket;
            try {
                socket = new DatagramSocket(BIND_PORT);
                mSocket = socket;
                Logger.i("Discovery: bound UDP %d", BIND_PORT);
            } catch (IOException e) {
                Logger.w("Discovery: bind UDP %d failed (%s), retrying in %d s", BIND_PORT, e, BIND_RETRY_MS / 1000);
                if (!sleepQuietly(BIND_RETRY_MS)) {
                    break;
                }
                continue;
            }

            serve(socket);
            socket.close();
            mSocket = null;

            if (!isInterrupted()) {
                Logger.w("Discovery: socket failed, re-binding");
                if (!sleepQuietly(1000)) {
                    break;
                }
            }
        }
        Logger.e("Discovery stopped");
    }

    private void serve(DatagramSocket socket) {
        byte[] data = new byte[64];
        DatagramPacket packet = new DatagramPacket(data, data.length);
        while (!isInterrupted()) {
            try {
                packet.setLength(data.length); // receive() shrinks it to the last datagram
                socket.receive(packet);
            } catch (IOException e) {
                if (!isInterrupted()) {
                    Logger.w("Discovery: receive failed: %s", e);
                }
                return;
            }

            String message = new String(data, 0, packet.getLength(), Charset.forName("UTF-8"));
            if (!message.equals(DISCOVER_MESSAGE)) {
                continue;
            }

            SocketAddress address = packet.getSocketAddress();
            try {
                String ip = getHostAddress();
                if (!hasUsableAddress(ip)) {
                    Logger.d("Discovery: no IP address yet, not answering %s", address);
                    continue;
                }
                byte[] bytes = getResponse(ip);
                socket.send(new DatagramPacket(bytes, bytes.length, address));
                Logger.d("Discover request, response sent.");
            } catch (IOException e) {
                Logger.w("Discovery: reply to %s failed: %s", address, e);
            } catch (RuntimeException e) {
                Logger.e("Discovery: building the reply for %s failed: %s", address, e);
            }
        }
    }

    private boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            interrupt();
            return false;
        }
    }

    /** Whether {@code ip} is an address a client can connect to (not 0.0.0.0 / empty). */
    static boolean hasUsableAddress(String ip) {
        return ip != null && !ip.isEmpty() && !ip.equals("0.0.0.0");
    }

    @Override
    public void interrupt() {
        DatagramSocket socket = mSocket;
        mSocket = null;
        if (socket != null) {
            socket.close();
        }

        super.interrupt();
    }

    private byte[] getResponse(String hostAddress) {
        Model model = BaseApplication.getInstance().getModel();

        final String machineName = model.getPreferences().getMachineName();
        final String machineModal = model.getPreferences().getMachineModel();
        FabPacketContent.MachineStatus status = model.getMachineController().getMachineStatus();

        // Build response string
        // {name}@{ip}|model:{model}|status:{status}
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(String.format("%s@%s", machineName, hostAddress));
        stringBuilder.append(String.format("|%s:%s", "model", machineModal));

        if (status.printerStatus == 0) {
            stringBuilder.append(String.format("|%s:%s", "status", "IDLE"));
        } else if (status.printerStatus == 1 || status.printerStatus == 3) {
            stringBuilder.append(String.format("|%s:%s", "status", "RUNNING"));
        } else {
            stringBuilder.append(String.format("|%s:%s", "status", "PAUSED"));
        }

        String description = stringBuilder.toString();

        Buffer buffer = new Buffer();
        buffer.write(ByteString.encodeUtf8(description));
        return buffer.readByteArray();
    }

    private String getHostAddress() {
        WifiInfo wifiInfo = mWifiManager.getConnectionInfo();
        return intToIp(wifiInfo.getIpAddress());
    }

    static String intToIp(int ipAddress) {
        return (ipAddress & 0xFF) + "." +
                ((ipAddress >> 8) & 0xFF) + "." +
                ((ipAddress >> 16) & 0xFF) + "." +
                (ipAddress >> 24 & 0xFF);
    }
}
