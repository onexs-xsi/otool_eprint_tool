package com.otool.eprint.hardware;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.otool.eprint.device.DeviceCatalog;
import com.otool.eprint.device.PrinterProfile;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

public final class PrinterStatusControllerTest {
    @Test
    public void escPosProbeDecodesReadyStateAcrossSeparateReads() {
        PrinterStatusController controller = new PrinterStatusController(
                DeviceCatalog.find("escpos_58_203"));
        controller.onConnected();
        assertArrayEquals(new byte[]{0x10, 0x04, 0x01, 0x10, 0x04, 0x02,
                0x10, 0x04, 0x03, 0x10, 0x04, 0x04}, controller.createProbe());
        controller.onBytes(new byte[]{0x00});
        controller.onBytes(new byte[]{0x00, 0x00, 0x00});
        PrinterHardwareStatus status = controller.current();
        assertTrue(status.responseConfirmed);
        assertFalse(status.busy);
        assertEquals(HardwareValue.OK, status.paper);
        assertEquals(HardwareValue.OK, status.cover);
    }

    @Test
    public void escPosPaperNearEndIsWarningAndPaperOutIsError() {
        PrinterStatusController warning = escControllerAtPaperStatus();
        warning.onBytes(new byte[]{0x0C});
        assertEquals(HardwareValue.WARNING, warning.current().paper);

        PrinterStatusController error = escControllerAtPaperStatus();
        error.onBytes(new byte[]{0x60});
        assertEquals(HardwareValue.ERROR, error.current().paper);
    }

    @Test
    public void zplHostStatusDecodesPaperAndHeadFlagsAcrossChunks() {
        PrinterProfile profile = DeviceCatalog.find("zpl_4in_203");
        PrinterStatusController controller = new PrinterStatusController(profile);
        controller.onConnected();
        assertArrayEquals("~HS\r\n".getBytes(StandardCharsets.US_ASCII), controller.createProbe());
        controller.onBytes("\u0002000,1,0,0200,000,0,0,0,000,0,0,0\u0003\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        controller.onBytes("\u0002000,0,1,0,0,2,0,0,00000000,1,000\u0003\r\n"
                .getBytes(StandardCharsets.US_ASCII));
        PrinterHardwareStatus status = controller.current();
        assertTrue(status.responseConfirmed);
        assertEquals(HardwareValue.ERROR, status.paper);
        assertEquals(HardwareValue.ERROR, status.cover);
    }

    private static PrinterStatusController escControllerAtPaperStatus() {
        PrinterStatusController controller = new PrinterStatusController(
                DeviceCatalog.find("escpos_58_203"));
        controller.onConnected();
        controller.createProbe();
        controller.onBytes(new byte[]{0x00, 0x00, 0x00});
        return controller;
    }
}
