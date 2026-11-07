package com.snapmaker.fabscreen;

import org.junit.Assert;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import fabscreen.libraries.legacy.data.serial.fabpacket.FabPacket;
import fabscreen.libraries.legacy.data.serial.fabpacket.FabPacketBuilder;
import fabscreen.libraries.legacy.data.serial.fabpacket.FabPacketInputStream;
import fabscreen.libraries.legacy.data.serial.fabpacket.FrameDiagnostics;
import fabscreen.libraries.legacy.lib.RxErrorPolicy;
import io.reactivex.exceptions.OnErrorNotImplementedException;
import io.reactivex.exceptions.UndeliverableException;

/**
 * SCR-02 (frame reader growth/resync), SCR-05 (malformed frames, Rx error policy) and SCR-16
 * (unsigned event ids in FabPacket.getKey).
 */
public class SerialRobustnessTest {

    private static FabPacket frame(int eventId, byte[] content) {
        FabPacket p = FabPacket.create();
        p.setEventId((byte) eventId);
        p.setContent(content);
        p.build();
        return p;
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }

    @Test
    public void readerGrowsBufferForFrameLargerThanInitialBuffer() throws IOException {
        byte[] content = new byte[1000];
        content[0] = 0x01;
        for (int i = 1; i < content.length; i++) {
            content[i] = (byte) (i * 7);
        }
        byte[] wire = concat(new byte[]{0x11, 0x22}, frame(0x0a, content).toByteArray());

        // 64-byte initial buffer: the old fill() grew only a local copy and threw
        // ArrayIndexOutOfBounds / lost data here.
        FabPacketInputStream reader = new FabPacketInputStream(new ByteArrayInputStream(wire), 64);
        FabPacket p = reader.readPacket();
        Assert.assertNotNull(p);
        Assert.assertEquals(0x0a, p.getEventId());
        Assert.assertArrayEquals(content, p.getContent());
        Assert.assertNull(reader.readPacket());
    }

    @Test
    public void readerResyncsAfterOversizeHeader() throws IOException {
        // aa 55 ff ff | ver 01 | len-check ff^ff = 00 | checksum: a false header with length 65535
        byte[] bogus = new byte[]{(byte) 0xaa, 0x55, (byte) 0xff, (byte) 0xff, 0x01, 0x00, 0x12, 0x34};
        FabPacket good = FabPacketBuilder.gcodeRequest("G28\n", 0);
        byte[] wire = concat(bogus, good.toByteArray());

        FabPacketInputStream reader = new FabPacketInputStream(new ByteArrayInputStream(wire), 8192,
                FabPacketInputStream.MAX_DATA_LENGTH);
        FabPacket p = reader.readPacket();
        Assert.assertNotNull(p);
        Assert.assertEquals(good.getEventId(), p.getEventId());
        Assert.assertArrayEquals(good.getContent(), p.getContent());
        Assert.assertTrue(reader.getResyncCount() >= 1);
    }

    @Test
    public void readerRejectsZeroLengthHeader() throws IOException {
        // length 0 would make FabPacket(byte[]) read -1 bytes and throw
        byte[] zero = new byte[]{(byte) 0xaa, 0x55, 0x00, 0x00, 0x01, 0x00, (byte) 0xff, (byte) 0xff};
        FabPacket good = FabPacketBuilder.gcodeRequest("M105\n", 0);
        FabPacketInputStream reader = new FabPacketInputStream(
                new ByteArrayInputStream(concat(zero, good.toByteArray())));
        FabPacket p = reader.readPacket();
        Assert.assertNotNull(p);
        Assert.assertArrayEquals(good.getContent(), p.getContent());
        Assert.assertEquals(1, reader.getResyncCount());
    }

    @Test
    public void maxDataLengthMatchesController() {
        Assert.assertEquals(1024, FabPacketInputStream.MAX_DATA_LENGTH);
    }

    @Test
    public void defaultReaderStillAcceptsLargeFramesForTheCameraLink() throws IOException {
        // LaserCameraController (Bluetooth) uses the default reader: frames above 1024 bytes must pass.
        byte[] content = new byte[3000];
        content[0] = 0x02;
        FabPacket big = frame(0x0e, content);
        FabPacketInputStream reader = new FabPacketInputStream(new ByteArrayInputStream(big.toByteArray()), 64);
        FabPacket p = reader.readPacket();
        Assert.assertNotNull(p);
        Assert.assertEquals(3000, p.getContent().length);

        // ...but the controller reader skips the same frame as a false header.
        FabPacketInputStream controller = new FabPacketInputStream(
                new ByteArrayInputStream(big.toByteArray()), 64, FabPacketInputStream.MAX_DATA_LENGTH);
        Assert.assertNull(controller.readPacket());
        Assert.assertTrue(controller.getResyncCount() >= 1);
    }

    @Test
    public void getKeyOnFrameWithoutOperationByteDoesNotThrow() {
        FabPacket p = frame(0x0a, new byte[0]);
        Assert.assertEquals(10000, p.getKey());
        Assert.assertFalse(FrameDiagnostics.hasOperationByte(p));
        Assert.assertTrue(FrameDiagnostics.hasOperationByte(frame(0x02, new byte[0])));
        Assert.assertTrue(FrameDiagnostics.hasOperationByte(frame(0x0a, new byte[]{0x37})));
        Assert.assertFalse(FrameDiagnostics.hasOperationByte(null));
    }

    @Test
    public void updateSubCommandsGetDistinctKeys() {
        int start = FabPacketBuilder.startUpdate().getKey();
        int pkg = FabPacketBuilder.requestUpdatePackage().getKey();
        int ver = FabPacketBuilder.checkControllerVersion().getKey();
        int mod = FabPacketBuilder.requestModuleVersion().getKey();
        Assert.assertEquals(170000, start);
        Assert.assertEquals(170001, pkg);
        Assert.assertEquals(170003, ver);
        Assert.assertEquals(170007, mod);

        // Replies (0xaa <op> ...) pair with their requests (0xa9 <op>)
        Assert.assertEquals(start, frame(0xaa, new byte[]{0x00, 0x00}).getKey());
        Assert.assertEquals(pkg, frame(0xaa, new byte[]{0x01, 0x00, 0x05}).getKey());
        Assert.assertEquals(ver, frame(0xaa, new byte[]{0x03, 'V', '1'}).getKey());
        Assert.assertEquals(mod, frame(0xaa, new byte[]{0x07}).getKey());
    }

    @Test
    public void lowEventIdKeysUnchanged() {
        Assert.assertEquals(2000, frame(0x01, new byte[]{0x00}).getKey());
        Assert.assertEquals(2000, frame(0x02, new byte[]{0x00}).getKey());
        Assert.assertEquals(4000, frame(0x04, new byte[]{0x00}).getKey());
        Assert.assertEquals(10000 + 0x37, frame(0x09, new byte[]{0x37}).getKey());
        Assert.assertEquals(10000 + 0x37, frame(0x0a, new byte[]{0x37}).getKey());
    }

    @Test
    public void describeIsSafeAndTruncates() {
        Assert.assertEquals("frame=null", FrameDiagnostics.describe(null));
        Assert.assertEquals("eid=0x0A op=none len=0 hex=", FrameDiagnostics.describe(frame(0x0a, new byte[0])));
        Assert.assertEquals("eid=0xAA op=0x03 len=2 hex=0356",
                FrameDiagnostics.describe(frame(0xaa, new byte[]{0x03, 0x56})));
        byte[] big = new byte[100];
        String d = FrameDiagnostics.describe(frame(0x08, big));
        Assert.assertTrue(d, d.endsWith("..."));
        Assert.assertEquals("0a0b", FrameDiagnostics.toHex(new byte[]{0x0a, 0x0b}, 10));
        Assert.assertEquals("0a...", FrameDiagnostics.toHex(new byte[]{0x0a, 0x0b}, 1));
    }

    @Test
    public void rxErrorPolicyIgnoresIoAfterDisposeOnly() {
        Assert.assertEquals(RxErrorPolicy.Action.IGNORE,
                RxErrorPolicy.classify(new UndeliverableException(new IOException("closed"))));
        Assert.assertEquals(RxErrorPolicy.Action.IGNORE,
                RxErrorPolicy.classify(new UndeliverableException(new InterruptedException())));
        Assert.assertEquals(RxErrorPolicy.Action.LOG,
                RxErrorPolicy.classify(new UndeliverableException(new ArrayIndexOutOfBoundsException(1))));
        Assert.assertEquals(RxErrorPolicy.Action.LOG, RxErrorPolicy.classify(new NullPointerException()));
        Assert.assertEquals(RxErrorPolicy.Action.IGNORE,
                RxErrorPolicy.classify(new OnErrorNotImplementedException(new IOException("closed"))));
        Throwable cause = new IllegalStateException("x");
        Assert.assertSame(cause, RxErrorPolicy.unwrap(new UndeliverableException(cause)));
    }
}
