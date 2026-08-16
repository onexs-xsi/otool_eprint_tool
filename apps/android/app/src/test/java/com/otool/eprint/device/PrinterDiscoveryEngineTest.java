package com.otool.eprint.device;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.bluetooth.BluetoothClass;

import com.otool.eprint.protocol.ProtocolType;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class PrinterDiscoveryEngineTest {
    @Test
    public void knownPosNameIsHighConfidenceAndSuggestsProfile() {
        DeviceCandidate candidate = new DeviceCandidate(null, TransportType.CLASSIC_SPP,
                "POS-58 Printer", "00:11:22:33:44:55", 0, true,
                BluetoothClass.Device.Major.IMAGING, true, Collections.emptyList());
        PrinterMatch match = PrinterDiscoveryEngine.evaluate(candidate, "");
        assertTrue(match.score >= 85);
        assertTrue(match.isRecommended());
        assertNotNull(match.suggestedProfile);
        assertEquals(ProtocolType.ESC_POS, match.suggestedProfile.protocol);
    }

    @Test
    public void blePrintServiceRaisesGenericPrinterCandidate() {
        DeviceCandidate candidate = new DeviceCandidate(null, TransportType.BLE_GATT,
                "Thermal Printer", "AA:BB:CC:DD:EE:FF", -52, false, 0, false,
                Arrays.asList("0000FFE0-0000-1000-8000-00805F9B34FB"));
        PrinterMatch match = PrinterDiscoveryEngine.evaluate(candidate, "");
        assertTrue(match.isLikelyPrinter());
        assertFalse(match.isRecommended());
    }

    @Test
    public void audioDeviceIsRejectedEvenWhenBonded() {
        DeviceCandidate candidate = new DeviceCandidate(null, TransportType.CLASSIC_SPP,
                "Wireless Headset", "10:20:30:40:50:60", 0, true,
                BluetoothClass.Device.Major.AUDIO_VIDEO, false, Collections.emptyList());
        PrinterMatch match = PrinterDiscoveryEngine.evaluate(candidate, "");
        assertEquals(0, match.score);
        assertFalse(match.isLikelyPrinter());
    }

    @Test
    public void lastSuccessfulAddressWinsButKeepsUnknownProtocolManual() {
        DeviceCandidate candidate = new DeviceCandidate(null, TransportType.BLE_GATT,
                "OEM-123", "01:02:03:04:05:06", -70, false);
        PrinterMatch match = PrinterDiscoveryEngine.evaluate(candidate, "01:02:03:04:05:06");
        assertTrue(match.score >= 85);
        assertFalse(match.isRecommended());
    }

    @Test
    public void usbPrinterClassEndpointIsRecommendedButStillRequiresUserSelection() {
        DeviceCandidate candidate = new DeviceCandidate("TSC USB Printer", "usb:1203:500:1",
                true, 1203, 500);
        PrinterMatch match = PrinterDiscoveryEngine.evaluate(candidate, "");
        assertTrue(match.isRecommended());
        assertTrue(match.score >= 90);
        assertNotNull(match.suggestedProfile);
        assertEquals(ProtocolType.TSPL, match.suggestedProfile.protocol);
    }

    @Test
    public void identifiesObservedDothanP1WithDedicatedPrivateProtocol() {
        DeviceCandidate candidate = new DeviceCandidate(null, TransportType.BLE_GATT,
                "P1-0009200000", "02:00:00:00:00:01", -48, false);
        PrinterMatch match = PrinterDiscoveryEngine.evaluate(candidate, "");
        assertTrue(match.score >= 85);
        assertFalse(match.privateProtocolRisk);
        assertNotNull(match.identity);
        assertNotNull(match.suggestedProfile);
        assertEquals(ProtocolType.DOTHAN_PRIVATE, match.suggestedProfile.protocol);
        assertEquals("德佟电子（DothanTech）", match.identity.manufacturer);
        assertEquals(384, match.identity.widthDots);
        assertTrue(match.isRecommended());
    }
}
