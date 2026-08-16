package com.otool.eprint.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ConnectionStatusTest {
    @Test
    public void reconnectStatusKeepsDesiredConnectionVisible() {
        ConnectionStatus status = new ConnectionStatus(ConnectionPhase.RECONNECT_WAIT,
                "16 秒后重连", "P1-0009200000", "AA:BB", "BLE GATT",
                true, 9, 123L);
        assertEquals("● 等待重连", status.indicatorLabel());
        assertTrue(status.connectionDesired);
        assertEquals(9, status.reconnectAttempt);
    }
}
