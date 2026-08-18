package com.otool.eprint.protocol;

/** Lightweight state model for the offline Dothan print session. */
public final class DothanStateMachine {
    public enum State {
        IDLE("空闲"),
        TRANSPORT_CONNECTED("通道已连接"),
        PROTOCOL_NEGOTIATING("协议确认中"),
        AUTH_WAIT("等待授权"),
        PRINTER_READY("打印机就绪"),
        SETTINGS_WRITE("参数写入中"),
        SETTINGS_VERIFY("参数回读验证中"),
        SENDING("正在发送"),
        WAITING_RECEIPT("等待回执"),
        FAILED("失败");

        public final String label;

        State(String label) {
            this.label = label;
        }
    }

    private State state = State.IDLE;

    public synchronized State state() {
        return state;
    }

    public synchronized void reset() {
        state = State.IDLE;
    }

    public synchronized void transportConnected() {
        state = State.TRANSPORT_CONNECTED;
    }

    public synchronized void negotiating() {
        state = State.PROTOCOL_NEGOTIATING;
    }

    public synchronized void authWait() {
        state = State.AUTH_WAIT;
    }

    public synchronized void ready() {
        state = State.PRINTER_READY;
    }

    public synchronized void settingsWrite() {
        state = State.SETTINGS_WRITE;
    }

    public synchronized void settingsVerify() {
        state = State.SETTINGS_VERIFY;
    }

    public synchronized void sending() {
        state = State.SENDING;
    }

    public synchronized void waitingReceipt() {
        state = State.WAITING_RECEIPT;
    }

    public synchronized void failed() {
        state = State.FAILED;
    }

    public synchronized String summary() {
        return state.label;
    }
}
