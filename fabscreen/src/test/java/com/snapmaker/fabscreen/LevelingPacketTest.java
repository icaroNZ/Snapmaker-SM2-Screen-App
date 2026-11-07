package com.snapmaker.fabscreen;

import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

import fabscreen.libraries.legacy.data.calibration.LevelingParams;
import fabscreen.libraries.legacy.data.serial.fabpacket.FabPacket;
import fabscreen.libraries.legacy.data.serial.fabpacket.FabPacketBuilder;
import okio.ByteString;

/**
 * Heat-soaked levelling request (0x09 0x02 / 0x09 0x04 with optional bed_temp and soak_s, big-endian),
 * contract in _snapmaker2-review/features/bed-levelling-heat-soak.md.
 */
public class LevelingPacketTest {

    /** Event id + content, i.e. the frame without the 8-byte header. */
    private static String body(FabPacket packet) {
        byte[] frame = packet.toByteArray();
        return ByteString.of(frame, 8, frame.length - 8).hex();
    }

    @Test
    public void autoLevelContractVector() {
        // Contract example: grid 5, 60 C, 300 s = 09 02 05 00 3C 01 2C
        FabPacket packet = FabPacketBuilder.startAutoCalibration(5, 60, 300);
        Assert.assertEquals("090205003c012c", body(packet));
        Assert.assertEquals(FabPacket.SETTINGS_REQUEST_EVENT_ID, packet.getEventId());
        Assert.assertEquals(6, packet.getContent().length);
    }

    @Test
    public void autoLevelNoHeatingIsStockForm() {
        FabPacket packet = FabPacketBuilder.startAutoCalibration(7, 0, 600);
        Assert.assertEquals("090207", body(packet));
        // identical to the stock builder
        Assert.assertEquals(body(FabPacketBuilder.startAutoCalibration(7)), body(packet));
    }

    @Test
    public void manualLevelWithSoak() {
        FabPacket packet = FabPacketBuilder.startManualCalibration(11, 110, 3600);
        Assert.assertEquals("09040b006e0e10", body(packet));
        Assert.assertEquals("09040b", body(FabPacketBuilder.startManualCalibration(11, 0, 0)));
    }

    @Test
    public void bigEndianHighBytes() {
        // 0x0102 s soak, 0x6e C: checks both u16 bytes are written high byte first
        FabPacket packet = FabPacketBuilder.startAutoCalibration(2, 110, 258);
        Assert.assertEquals("090202006e0102", body(packet));
    }

    @Test
    public void keyUnchangedByLongerPayload() {
        // replies are matched by event id pair + first content byte, so dispatch needs no change
        Assert.assertEquals(FabPacketBuilder.startAutoCalibration(3).getKey(),
                FabPacketBuilder.startAutoCalibration(3, 60, 300).getKey());
        Assert.assertEquals(FabPacketBuilder.startManualCalibration(3).getKey(),
                FabPacketBuilder.startManualCalibration(9, 60, 300).getKey());
    }

    @Test
    public void roundTrip() throws IOException {
        FabPacket in = FabPacketBuilder.startAutoCalibration(9, 65, 900);
        FabPacket out = new FabPacket(in.toByteArray());
        Assert.assertArrayEquals(in.toByteArray(), out.toByteArray());
        Assert.assertArrayEquals(new byte[]{0x02, 0x09, 0x00, 0x41, 0x03, (byte) 0x84}, out.getContent());
    }

    @Test(expected = IllegalArgumentException.class)
    public void gridTooLarge() {
        FabPacketBuilder.startAutoCalibration(12, 60, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void gridTooSmall() {
        FabPacketBuilder.startManualCalibration(1, 0, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void bedTooHot() {
        FabPacketBuilder.startAutoCalibration(3, 111, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void negativeBed() {
        FabPacketBuilder.startAutoCalibration(3, -1, 0);
    }

    @Test(expected = IllegalArgumentException.class)
    public void soakTooLong() {
        FabPacketBuilder.startAutoCalibration(3, 60, 3601);
    }

    @Test
    public void clamps() {
        Assert.assertEquals(3, LevelingParams.clampGrid(0));
        Assert.assertEquals(3, LevelingParams.clampGrid(2));
        Assert.assertEquals(7, LevelingParams.clampGrid(7));
        Assert.assertEquals(11, LevelingParams.clampGrid(15));
        Assert.assertEquals(0, LevelingParams.clampBedTemperature(-5));
        Assert.assertEquals(110, LevelingParams.clampBedTemperature(150));
        Assert.assertEquals(0, LevelingParams.clampSoakMinutes(-1));
        Assert.assertEquals(60, LevelingParams.clampSoakMinutes(61));
        Assert.assertEquals(3600, LevelingParams.soakMinutesToSeconds(60));
        Assert.assertEquals(3600, LevelingParams.soakMinutesToSeconds(90));
        Assert.assertEquals(300, LevelingParams.soakMinutesToSeconds(5));
        Assert.assertTrue(LevelingParams.isFirmwareGridValid(2));
        Assert.assertTrue(LevelingParams.isFirmwareGridValid(11));
        Assert.assertFalse(LevelingParams.isFirmwareGridValid(12));
    }

    @Test
    public void bedTemperatureSteps() {
        Assert.assertEquals(5, LevelingParams.stepBedTemperature(0, true));
        Assert.assertEquals(0, LevelingParams.stepBedTemperature(0, false));
        Assert.assertEquals(65, LevelingParams.stepBedTemperature(60, true));
        Assert.assertEquals(55, LevelingParams.stepBedTemperature(60, false));
        // off-step values snap to the 5-degree grid
        Assert.assertEquals(70, LevelingParams.stepBedTemperature(67, true));
        Assert.assertEquals(65, LevelingParams.stepBedTemperature(67, false));
        Assert.assertEquals(110, LevelingParams.stepBedTemperature(110, true));
        Assert.assertEquals(105, LevelingParams.stepBedTemperature(110, false));
    }

    @Test
    public void soakSteps() {
        Assert.assertEquals(1, LevelingParams.stepSoakMinutes(0, true));
        Assert.assertEquals(0, LevelingParams.stepSoakMinutes(0, false));
        Assert.assertEquals(10, LevelingParams.stepSoakMinutes(9, true));
        Assert.assertEquals(15, LevelingParams.stepSoakMinutes(10, true));
        Assert.assertEquals(9, LevelingParams.stepSoakMinutes(10, false));
        Assert.assertEquals(10, LevelingParams.stepSoakMinutes(15, false));
        Assert.assertEquals(15, LevelingParams.stepSoakMinutes(12, true));
        Assert.assertEquals(10, LevelingParams.stepSoakMinutes(12, false));
        Assert.assertEquals(60, LevelingParams.stepSoakMinutes(60, true));
        Assert.assertEquals(55, LevelingParams.stepSoakMinutes(60, false));
    }

    @Test
    public void countdown() {
        Assert.assertEquals("00:00", LevelingParams.formatCountdown(-3));
        Assert.assertEquals("00:59", LevelingParams.formatCountdown(59));
        Assert.assertEquals("05:00", LevelingParams.formatCountdown(300));
        Assert.assertEquals("60:00", LevelingParams.formatCountdown(3600));
    }
}
