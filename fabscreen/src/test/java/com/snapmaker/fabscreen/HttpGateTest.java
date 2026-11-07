package com.snapmaker.fabscreen;

import org.junit.Assert;
import org.junit.Test;

import fabscreen.libraries.legacy.data.Constants;
import fabscreen.libraries.legacy.data.print.MachinePrintJobState;
import fabscreen.libraries.legacy.server.http.HttpGate;

/** SCR-01, SCR-04, SCR-08, SCR-09, SCR-19, SCR-20 (token prefix) HTTP checks. */
public class HttpGateTest {

    @Test
    public void busyWhenControllerOrJobIsActive() {
        Assert.assertFalse(HttpGate.isBusy(0, MachinePrintJobState.PRINT_JOB_STATE_IDLE.getValue()));
        Assert.assertTrue(HttpGate.isBusy(3, MachinePrintJobState.PRINT_JOB_STATE_IDLE.getValue()));  // USB-host job
        Assert.assertTrue(HttpGate.isBusy(1, MachinePrintJobState.PRINT_JOB_STATE_IDLE.getValue()));
        Assert.assertTrue(HttpGate.isBusy(0, MachinePrintJobState.PRINT_JOB_STATE_PRINTING.getValue()));  // stale status
        Assert.assertTrue(HttpGate.isBusy(0, MachinePrintJobState.PRINT_JOB_STATE_PAUSED.getValue()));
        Assert.assertTrue(HttpGate.isBusy(0, MachinePrintJobState.PRINT_JOB_STATE_STARTING.getValue()));
        Assert.assertTrue(HttpGate.isBusy(0, MachinePrintJobState.PRINT_JOB_STATE_RECOVERING.getValue()));
        Assert.assertTrue(HttpGate.isBusy(0, MachinePrintJobState.PRINT_JOB_STATE_RESUMING.getValue()));
        Assert.assertTrue(HttpGate.isBusy(0, MachinePrintJobState.PRINT_JOB_STATE_PAUSING.getValue()));
        // A stale finished/stopped job must not lock uploads out
        Assert.assertFalse(HttpGate.isBusy(0, MachinePrintJobState.PRINT_JOB_STATE_FINISHED.getValue()));
        Assert.assertFalse(HttpGate.isBusy(0, MachinePrintJobState.PRINT_JOB_STATE_STOPPED.getValue()));
    }

    @Test
    public void feedRateIsClamped() {
        Assert.assertEquals(100, HttpGate.clampFeedRate(0));
        Assert.assertEquals(100, HttpGate.clampFeedRate(-5));
        Assert.assertEquals(3000, HttpGate.clampFeedRate(3000));
        Assert.assertEquals(6000, HttpGate.clampFeedRate(60000));
        Assert.assertEquals(5f, HttpGate.clamp(Float.NaN, 5f, 10f), 0f);
        Assert.assertEquals(10f, HttpGate.clamp(99f, 5f, 10f), 0f);
    }

    @Test
    public void fileNamesAreSanitised() {
        Assert.assertEquals("cube.gcode", HttpGate.sanitizeFileName("cube.gcode"));
        Assert.assertEquals("x.gcode", HttpGate.sanitizeFileName("../x.gcode"));
        Assert.assertEquals("x.gcode", HttpGate.sanitizeFileName("..\\..\\x.gcode"));
        Assert.assertEquals("passwd", HttpGate.sanitizeFileName("/etc/passwd"));
        Assert.assertEquals("Benchy (PLA) #2 [0.2mm].gcode", HttpGate.sanitizeFileName("Benchy (PLA) #2 [0.2mm].gcode"));
        Assert.assertEquals("\u6a21\u578b.gcode", HttpGate.sanitizeFileName("\u6a21\u578b.gcode"));
        Assert.assertNull(HttpGate.sanitizeFileName(null));
        Assert.assertNull(HttpGate.sanitizeFileName(""));
        Assert.assertNull(HttpGate.sanitizeFileName("   "));
        Assert.assertNull(HttpGate.sanitizeFileName("."));
        Assert.assertNull(HttpGate.sanitizeFileName(".."));
        Assert.assertNull(HttpGate.sanitizeFileName("dir/"));
        Assert.assertNull(HttpGate.sanitizeFileName(".hidden"));
        Assert.assertNull(HttpGate.sanitizeFileName("a\nb.gcode"));
        Assert.assertNull(HttpGate.sanitizeFileName("C:evil"));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            longName.append('a');
        }
        Assert.assertNull(HttpGate.sanitizeFileName(longName.toString()));
    }

    @Test
    public void browserCrossSiteRequestsAreDetected() {
        // Native clients (Orca, curl, Luban's node server) send neither header
        Assert.assertFalse(HttpGate.isCrossSiteBrowserRequest(null, null));
        Assert.assertTrue(HttpGate.isCrossSiteBrowserRequest("http://evil.example", null));
        Assert.assertTrue(HttpGate.isCrossSiteBrowserRequest(null, "cross-site"));
        Assert.assertTrue(HttpGate.isCrossSiteBrowserRequest(null, "same-site"));
        Assert.assertFalse(HttpGate.isCrossSiteBrowserRequest(null, "none"));
        Assert.assertFalse(HttpGate.isCrossSiteBrowserRequest("http://192.168.1.20:8080", "same-origin"));
        Assert.assertFalse(HttpGate.isCrossSiteBrowserRequest("", null));
    }

    @Test
    public void securityDefaults() {
        Assert.assertTrue(HttpGate.REJECT_BROWSER_CROSS_SITE_REQUESTS);
        Assert.assertTrue(HttpGate.REQUIRE_TOKEN_FOR_LEGACY_CAMERA_ROUTES);
        Assert.assertFalse(HttpGate.REQUIRE_TOKEN_FOR_OCTOPRINT_UPLOAD);
    }

    @Test
    public void uploadSpaceCheck() {
        long mb = 1024L * 1024;
        Assert.assertTrue(HttpGate.uploadFits(100 * mb, 200 * mb, 32 * mb));
        Assert.assertFalse(HttpGate.uploadFits(180 * mb, 200 * mb, 32 * mb));
        Assert.assertFalse(HttpGate.uploadFits(2000000000L, 500 * mb, 32 * mb));
        Assert.assertTrue(HttpGate.uploadFits(-1, 0, 32 * mb)); // unknown length: let through, nothing deleted
    }

    @Test
    public void tokenPrefixNeverLogsWholeToken() {
        Assert.assertEquals("12345678...", HttpGate.tokenPrefix("1234567890abcdef"));
        Assert.assertEquals("abc", HttpGate.tokenPrefix("abc"));
        Assert.assertEquals("none", HttpGate.tokenPrefix(null));
    }

    @Test
    public void everyLaserHeadIsALaser() {
        Assert.assertTrue(Constants.isLaserHead(Constants.HEAD_LASER));
        Assert.assertTrue(Constants.isLaserHead(Constants.HEAD_LASER_10W));
        Assert.assertTrue(Constants.isLaserHead(Constants.HEAD_LASER_20W));
        Assert.assertTrue(Constants.isLaserHead(Constants.HEAD_LASER_40W));
        Assert.assertTrue(Constants.isLaserHead(Constants.HEAD_LASER_2W_IR));
        Assert.assertFalse(Constants.isLaserHead(Constants.HEAD_3DP));
        Assert.assertFalse(Constants.isLaserHead(Constants.HEAD_CNC_200W));
        Assert.assertFalse(Constants.isLaserHead(Constants.HEAD_UNPLUGGED));
    }
}
