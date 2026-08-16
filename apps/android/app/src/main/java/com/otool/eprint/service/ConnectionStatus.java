package com.otool.eprint.service;

public final class ConnectionStatus {
    public final ConnectionPhase phase;
    public final String message;
    public final String deviceName;
    public final String address;
    public final String transport;
    public final boolean connectionDesired;
    public final int reconnectAttempt;
    public final long nextRetryAtMillis;

    public ConnectionStatus(ConnectionPhase phase, String message, String deviceName,
                            String address, String transport, boolean connectionDesired,
                            int reconnectAttempt, long nextRetryAtMillis) {
        this.phase = phase;
        this.message = message;
        this.deviceName = deviceName == null ? "" : deviceName;
        this.address = address == null ? "" : address;
        this.transport = transport == null ? "" : transport;
        this.connectionDesired = connectionDesired;
        this.reconnectAttempt = reconnectAttempt;
        this.nextRetryAtMillis = nextRetryAtMillis;
    }

    public static ConnectionStatus idle() {
        return new ConnectionStatus(ConnectionPhase.IDLE, "尚未选择打印机", "", "", "",
                false, 0, 0);
    }

    public String indicatorLabel() {
        switch (phase) {
            case PRINTER_READY: return "● 打印机就绪";
            case TRANSPORT_CONNECTED: return "● 蓝牙已连接";
            case PROTOCOL_NEGOTIATING: return "● 协议确认中";
            case SENDING: return "● 正在发送";
            case WAITING_RECEIPT: return "● 等待回执";
            case PRINT_FAILED: return "● 打印失败";
            case CONNECTED: return "● 已连接";
            case CONNECTING: return "● 连接中";
            case RECONNECT_WAIT: return "● 等待重连";
            case WAITING_FOR_HARDWARE: return "● 等待设备";
            case MANUAL_DISCONNECTED: return "● 已手动断开";
            case SERVICE_STOPPED: return "● 服务已停止";
            default: return "● 未连接";
        }
    }
}
