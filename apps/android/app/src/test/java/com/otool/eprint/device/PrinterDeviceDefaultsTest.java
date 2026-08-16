package com.otool.eprint.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PrinterDeviceDefaultsTest {
    @Test
    public void observedP1DefaultsMatchOfficialDeviceDetails() {
        PrinterIdentity identity = DeviceCatalog.identify("P1-0009200000",
                TransportType.BLE_GATT);
        PrinterProfile configured = PrinterDeviceDefaults.apply(DeviceCatalog.defaultProfile(), identity);
        assertEquals("德佟电子（DothanTech）", configured.manufacturer);
        assertEquals("热敏标签打印机", configured.deviceType);
        assertEquals(15, configured.printDensity);
        assertEquals(1, configured.printSpeed);
        assertEquals(PaperType.GAP_LABEL, configured.paperType);
        assertEquals(30, configured.gapLengthTenthsMm);
        assertEquals(15, configured.autoPowerOffMinutes);
        assertTrue(configured.autoFeed);
        assertTrue(configured.beep);
    }
}
