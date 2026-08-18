package com.otool.eprint.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DothanReceiveParserTest {
    @Test
    public void parsesCoreReceiveCommands() {
        DothanDeviceState state = new DothanDeviceState();
        DothanReceiveParser.parse(new DothanFrame(0x71, new byte[]{(byte) 203}), state);
        assertEquals(203, state.dpi);

        DothanReceiveParser.parse(new DothanFrame(0x72,
                new byte[]{0x01, (byte) 0x80, 14, 0}), state);
        assertEquals(384, state.widthDots);
        assertEquals(15, state.density);
        assertEquals(1, state.speed);

        DothanReceiveParser.parse(new DothanFrame(0x77, new byte[]{2}), state);
        assertEquals(1000, state.bufferBytes);

        DothanReceiveParser.parse(new DothanFrame(0x70, new byte[]{0, 0, 1, 2}), state);
        assertEquals(0, state.receiptStatus);
        assertEquals(1, state.receiptPage);
        assertEquals(2, state.receiptCopies);
    }

    @Test
    public void parsesFlagsAndDeviceName() {
        DothanDeviceState state = new DothanDeviceState();
        DothanReceiveParser.parse(new DothanFrame(0x4D, new byte[]{0x03}), state);
        assertTrue(state.flagJ);
        assertTrue(state.flagI);
        DothanReceiveParser.parse(new DothanFrame(0x79, "P1".getBytes(java.nio.charset.StandardCharsets.US_ASCII)), state);
        assertEquals("P1", state.deviceName);
    }

    @Test
    public void parsesSettingsReadbackAndGbkDeviceName() throws Exception {
        DothanDeviceState state = new DothanDeviceState();
        DothanReceiveParser.parse(new DothanFrame(0x42, new byte[]{2}), state);
        DothanReceiveParser.parse(new DothanFrame(0x43, new byte[]{14}), state);
        DothanReceiveParser.parse(new DothanFrame(0x44, new byte[]{0}), state);
        DothanReceiveParser.parse(new DothanFrame(0x45,
                new byte[]{(byte) 0xC1, 0x2C}), state);
        DothanReceiveParser.parse(new DothanFrame(0x47, new byte[]{1}), state);
        DothanReceiveParser.parse(new DothanFrame(0x48, new byte[]{15}), state);
        DothanReceiveParser.parse(new DothanFrame(0x49, new byte[]{2}), state);
        DothanReceiveParser.parse(new DothanFrame(0x4D,
                new byte[]{0, 0, 0x04}), state);
        DothanReceiveParser.parse(new DothanFrame(0x79,
                "打印机".getBytes("GBK")), state);
        assertEquals(2, state.gapType);
        assertEquals(15, state.density);
        assertEquals(1, state.speed);
        assertEquals(300, state.gapHundredthsMm);
        assertEquals(1, state.motorMode);
        assertEquals(15, state.autoPowerOffMinutes);
        assertEquals(2, state.language);
        assertEquals(1, state.autoFeed);
        assertEquals(1, state.beep);
        assertEquals("打印机", state.deviceName);
    }

    @Test
    public void parsesExtendedGapLength() {
        DothanDeviceState state = new DothanDeviceState();
        DothanReceiveParser.parse(new DothanFrame(0x45,
                new byte[]{(byte) 0xC0, 0x75, 0x30}), state);
        assertEquals(30_000, state.gapHundredthsMm);
    }

    @Test
    public void parsesAddressRecordsFromFullParameters() {
        DothanDeviceState state = new DothanDeviceState();
        byte[] payload = new byte[]{
                20, 0x12, 0x34, 0x56, 0x78, (byte) 0x9A, (byte) 0xBC,
                16, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66
        };
        DothanReceiveParser.parse(new DothanFrame(0x7D, payload), state);
        assertEquals(20, state.addressType);
        assertEquals("12:34:56:78:9A:BC", state.macAddress);
    }

    @Test
    public void parsesStatusReportManufacturerBranch() {
        DothanDeviceState state = new DothanDeviceState();
        byte[] payload = new byte[30];
        payload[0] = 4;
        payload[1] = 0x20;
        payload[2] = 'P';
        payload[3] = '1';
        payload[6] = 18;
        payload[7] = 22;
        DothanReceiveParser.parse(new DothanFrame(0x40, payload), state);
        assertEquals("P1", state.manufacturerInfo.product);
    }
}
