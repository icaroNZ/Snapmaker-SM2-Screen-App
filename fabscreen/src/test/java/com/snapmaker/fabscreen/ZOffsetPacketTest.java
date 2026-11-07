package com.snapmaker.fabscreen;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

import fabscreen.libraries.legacy.data.calibration.ZOffsetParams;
import fabscreen.libraries.legacy.data.serial.fabpacket.FabPacket;
import fabscreen.libraries.legacy.data.serial.fabpacket.FabPacketBuilder;
import okio.ByteString;

/**
 * Separate mesh / Z-offset calibration packets (0x09 0x02 flags byte, 0x09 0x37, 0x09 0x38) and their replies,
 * contract in _snapmaker2-review/features/separate-mesh-and-z-offset.md.
 */
public class ZOffsetPacketTest {

    /** Event id + content, i.e. the frame without the 8-byte header. */
    private static String body(FabPacket packet) {
        byte[] frame = packet.toByteArray();
        return ByteString.of(frame, 8, frame.length - 8).hex();
    }

    /** Reply content (sub-op first) from a hex body that starts with the event id, e.g. "0a02...". */
    private static byte[] replyContent(String hexBody) {
        byte[] b = ByteString.decodeHex(hexBody).toByteArray();
        byte[] content = new byte[b.length - 1];
        System.arraycopy(b, 1, content, 0, content.length);
        return content;
    }

    private static FabPacket reply(String hexBody) throws IOException {
        byte[] b = ByteString.decodeHex(hexBody).toByteArray();
        FabPacket p = FabPacket.create();
        p.setEventId(b[0]);
        p.setContent(replyContent(hexBody));
        p.build();
        return new FabPacket(p.toByteArray());
    }

    // ---- requests -------------------------------------------------------------------------------------------

    @Test
    public void autoLevelWithStoredOffsetContractVector() {
        // grid 5, 60 C, 300 s, auto-apply = 09 02 05 00 3C 01 2C 01
        FabPacket packet = FabPacketBuilder.startAutoCalibration(5, 60, 300, true);
        Assert.assertEquals("090205003c012c01", body(packet));
    }

    @Test
    public void autoLevelWithStoredOffsetNoHeatingKeepsFullForm() {
        // without heating the flags byte must still be at payload index 5 (soak sent as 0)
        Assert.assertEquals("0902070000000001", body(FabPacketBuilder.startAutoCalibration(7, 0, 600, true)));
    }

    @Test
    public void autoLevelFlagClear() {
        Assert.assertEquals("090203003c012c00", body(FabPacketBuilder.startAutoCalibration(3, 60, 300, false)));
    }

    @Test
    public void autoLevelFlagsKeyUnchanged() {
        Assert.assertEquals(FabPacketBuilder.startAutoCalibration(3).getKey(),
                FabPacketBuilder.startAutoCalibration(11, 110, 3600, true).getKey());
    }

    @Test(expected = IllegalArgumentException.class)
    public void autoLevelFlagsGridChecked() {
        FabPacketBuilder.startAutoCalibration(12, 0, 0, true);
    }

    @Test
    public void zOffsetCalibrationContractVectors() {
        // 60 C / 300 s = 09 37 00 3C 01 2C; no heating = 09 37
        Assert.assertEquals("0937003c012c", body(FabPacketBuilder.startZOffsetCalibration(60, 300)));
        Assert.assertEquals("0937", body(FabPacketBuilder.startZOffsetCalibration(0, 300)));
        Assert.assertEquals("0937006e0e10", body(FabPacketBuilder.startZOffsetCalibration(110, 3600)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void zOffsetCalibrationBedChecked() {
        FabPacketBuilder.startZOffsetCalibration(111, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void zOffsetCalibrationSoakChecked() {
        FabPacketBuilder.startZOffsetCalibration(60, 3601);
    }

    @Test
    public void queryVector() {
        Assert.assertEquals("0938", body(FabPacketBuilder.queryZOffset()));
    }

    @Test
    public void replyKeysMatchRequestKeys() throws IOException {
        Assert.assertEquals(FabPacketBuilder.startZOffsetCalibration(60, 300).getKey(), reply("0a3700").getKey());
        Assert.assertEquals(FabPacketBuilder.queryZOffset().getKey(), reply("0a380000000004d2").getKey());
        Assert.assertEquals(FabPacketBuilder.startAutoCalibration(5, 60, 300, true).getKey(),
                reply("0a020000000004d2").getKey());
        // the old firmware's "invalid command" frame (0A 0A, no opcode) does not match the query
        Assert.assertNotEquals(FabPacketBuilder.queryZOffset().getKey(), reply("0a0a").getKey());
    }

    // ---- replies --------------------------------------------------------------------------------------------

    @Test
    public void autoLevelAppliedReply() throws IOException {
        // "applied, 1.234 mm" = 0A 02 00 00 00 00 04 D2
        ZOffsetParams.StatusReply r = ZOffsetParams.parseStatusReply(reply("0a020000000004d2").getContent());
        Assert.assertNotNull(r);
        Assert.assertTrue(r.extended);
        Assert.assertEquals(0, r.err);
        Assert.assertEquals(ZOffsetParams.STATUS_VALID, r.status);
        Assert.assertEquals(1234, r.offsetUm);
        Assert.assertTrue(r.isOffsetApplied());
        Assert.assertEquals("1.234", ZOffsetParams.formatMm(r.offsetUm));
    }

    @Test
    public void autoLevelCardStepReplies() {
        ZOffsetParams.StatusReply notSet = ZOffsetParams.parseStatusReply(replyContent("0a02000100000000"));
        Assert.assertTrue(notSet.isOk());
        Assert.assertFalse(notSet.isOffsetApplied());
        Assert.assertEquals(ZOffsetParams.STATUS_NOT_SET, notSet.status);

        ZOffsetParams.StatusReply other = ZOffsetParams.parseStatusReply(replyContent("0a020003000004d2"));
        Assert.assertEquals(ZOffsetParams.STATUS_OTHER_TOOLHEAD, other.status);
        Assert.assertFalse(other.isOffsetApplied());

        ZOffsetParams.StatusReply range = ZOffsetParams.parseStatusReply(replyContent("0a02000200001b58"));
        Assert.assertEquals(ZOffsetParams.STATUS_OUT_OF_RANGE, range.status);
        Assert.assertEquals(7000, range.offsetUm);
    }

    @Test
    public void legacyOneByteReplyMeansCardStep() {
        // a250t.3 ignores the flags byte and answers 0A 02 <err>
        ZOffsetParams.StatusReply r = ZOffsetParams.parseStatusReply(replyContent("0a0200"));
        Assert.assertFalse(r.extended);
        Assert.assertTrue(r.isOk());
        Assert.assertFalse(r.isOffsetApplied());

        ZOffsetParams.StatusReply failed = ZOffsetParams.parseStatusReply(replyContent("0a02cd"));
        Assert.assertEquals(205, failed.err);
        Assert.assertFalse(failed.isOk());
    }

    @Test
    public void extendedErrorReply() {
        // probing failed with the flag set: 6-byte reply with err 205
        ZOffsetParams.StatusReply r = ZOffsetParams.parseStatusReply(replyContent("0a02cd0000000000"));
        Assert.assertTrue(r.extended);
        Assert.assertEquals(205, r.err);
        Assert.assertFalse(r.isOk());
        Assert.assertFalse(r.isOffsetApplied());
    }

    @Test
    public void queryReplies() {
        ZOffsetParams.StatusReply valid = ZOffsetParams.parseStatusReply(replyContent("0a380000000004d2"));
        Assert.assertEquals(ZOffsetParams.STATUS_VALID, valid.status);
        Assert.assertEquals(1234, valid.offsetUm);

        ZOffsetParams.StatusReply never = ZOffsetParams.parseStatusReply(replyContent("0a380001" + "00000000"));
        Assert.assertEquals(ZOffsetParams.STATUS_NOT_SET, never.status);
        Assert.assertEquals(0, never.offsetUm);
        Assert.assertEquals("0.000", ZOffsetParams.formatMm(never.offsetUm));
    }

    @Test
    public void negativeOffset() {
        // -0.050 mm = 0xFFFFFFCE, -4.999 mm = 0xFFFFEC79
        ZOffsetParams.StatusReply r = ZOffsetParams.parseStatusReply(replyContent("0a3800" + "00" + "ffffffce"));
        Assert.assertEquals(-50, r.offsetUm);
        Assert.assertEquals("-0.050", ZOffsetParams.formatMm(r.offsetUm));
        Assert.assertEquals(-4999, ZOffsetParams.parseStatusReply(replyContent("0a380000ffffec79")).offsetUm);
    }

    @Test
    public void shortAndEmptyReplies() {
        Assert.assertNull(ZOffsetParams.parseStatusReply(null));
        Assert.assertNull(ZOffsetParams.parseStatusReply(new byte[0]));
        Assert.assertEquals(0, ZOffsetParams.parseStatusReply(new byte[]{0x02}).err);
        Assert.assertFalse(ZOffsetParams.parseStatusReply(replyContent("0a38000000")).extended);
        Assert.assertEquals(1, ZOffsetParams.parseErr(replyContent("0a0701")));
        Assert.assertEquals(0, ZOffsetParams.parseErr(replyContent("0a0700")));
        Assert.assertEquals(0, ZOffsetParams.parseErr(new byte[]{0x07}));
    }

    // ---- version gate ---------------------------------------------------------------------------------------

    @Test
    public void controllerVersionGate() {
        Assert.assertTrue(ZOffsetParams.isSupportedControllerVersion("V5.2.1-a250t.4"));
        Assert.assertTrue(ZOffsetParams.isSupportedControllerVersion("Snapmaker_V5.2.1-a250t.4"));
        Assert.assertTrue(ZOffsetParams.isSupportedControllerVersion("SM2-5.2.1-a250t.4"));
        Assert.assertTrue(ZOffsetParams.isSupportedControllerVersion("V5.2.1-A250T.12"));
        Assert.assertTrue(ZOffsetParams.isSupportedControllerVersion("V5.2.2-a250t.1"));
        Assert.assertTrue(ZOffsetParams.isSupportedControllerVersion("V6.0.0-a250t.0"));

        Assert.assertFalse(ZOffsetParams.isSupportedControllerVersion("V5.2.1-a250t.3"));
        Assert.assertFalse(ZOffsetParams.isSupportedControllerVersion("V5.2.0-a250t.9"));
        Assert.assertFalse(ZOffsetParams.isSupportedControllerVersion("V5.2.1"));
        Assert.assertFalse(ZOffsetParams.isSupportedControllerVersion("V4.6.2"));
        Assert.assertFalse(ZOffsetParams.isSupportedControllerVersion("Developer version"));
        Assert.assertFalse(ZOffsetParams.isSupportedControllerVersion(""));
        Assert.assertFalse(ZOffsetParams.isSupportedControllerVersion(null));
    }

    @Test
    public void statusTexts() {
        Assert.assertEquals("valid", ZOffsetParams.statusText(ZOffsetParams.STATUS_VALID));
        Assert.assertEquals("not set", ZOffsetParams.statusText(ZOffsetParams.STATUS_NOT_SET));
        Assert.assertEquals("out of range", ZOffsetParams.statusText(ZOffsetParams.STATUS_OUT_OF_RANGE));
        Assert.assertEquals("other toolhead", ZOffsetParams.statusText(ZOffsetParams.STATUS_OTHER_TOOLHEAD));
        Assert.assertEquals("not set", ZOffsetParams.statusText(9));
    }
}
