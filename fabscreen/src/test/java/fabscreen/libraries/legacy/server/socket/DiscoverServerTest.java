package fabscreen.libraries.legacy.server.socket;

import org.junit.Assert;
import org.junit.Test;

/** SCR-21: discovery skips replies without a usable IP address. */
public class DiscoverServerTest {
    @Test
    public void intToIpIsLittleEndian() {
        // 192.168.1.20 as WifiInfo.getIpAddress() reports it (little-endian)
        int ip = 192 | (168 << 8) | (1 << 16) | (20 << 24);
        Assert.assertEquals("192.168.1.20", DiscoverServer.intToIp(ip));
        Assert.assertEquals("0.0.0.0", DiscoverServer.intToIp(0));
    }

    @Test
    public void noReplyWithoutAddress() {
        Assert.assertFalse(DiscoverServer.hasUsableAddress("0.0.0.0"));
        Assert.assertFalse(DiscoverServer.hasUsableAddress(""));
        Assert.assertFalse(DiscoverServer.hasUsableAddress(null));
        Assert.assertTrue(DiscoverServer.hasUsableAddress("192.168.1.20"));
    }
}
