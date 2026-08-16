package com.otool.eprint.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.UUID;

import com.otool.eprint.protocol.ProtocolType;

import org.junit.Test;

public final class DeviceCatalogTest {
    @Test
    public void matchesKnownPrinterFamiliesWithoutClaimingUnknownDevice() {
        assertEquals(ProtocolType.TSPL,
                DeviceCatalog.matchKnown("TSC TE210", TransportType.CLASSIC_SPP).protocol);
        assertEquals(ProtocolType.CPCL,
                DeviceCatalog.matchKnown("ZQ620", TransportType.BLE_GATT).protocol);
        assertEquals(ProtocolType.ZPL,
                DeviceCatalog.matchKnown("Zebra ZD421", TransportType.CLASSIC_SPP).protocol);
        assertNull(DeviceCatalog.matchKnown("Unknown Device", TransportType.BLE_GATT));
    }

    @Test
    public void flagsLikelyPrivateProtocolNames() {
        assertTrue(DeviceCatalog.looksPrivateProtocol("WePrint-D11"));
        assertTrue(DeviceCatalog.looksPrivateProtocol("DP23-001"));
        assertTrue(DeviceCatalog.looksPrivateProtocol("P1-0009200000"));
        PrinterIdentity identity = DeviceCatalog.identify("P1-0009200000", TransportType.BLE_GATT);
        assertEquals("德佟电子（DothanTech）", identity.manufacturer);
        assertEquals(203, identity.dpi);
        assertEquals(384, identity.widthDots);
    }

    @Test
    public void includesMicrochipTransparentUartForObservedBleHardware() {
        PrinterProfile profile = DeviceCatalog.defaultProfile();
        assertTrue(profile.preferredBleServices.contains(UUID.fromString(
                "49535343-FE7D-4AE5-8FA9-9FAFD205E455")));
        assertTrue(profile.preferredBleWrites.contains(UUID.fromString(
                "49535343-8841-43F4-A8D4-ECBE34729BB3")));
    }
}
