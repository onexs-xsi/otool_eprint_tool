package com.otool.eprint.job;

public enum PrintJobState {
    QUEUED("等待打印"),
    SENDING("正在发送"),
    SENT_TO_DEVICE("已发送到设备"),
    COMPLETED_CONFIRMED("打印机已确认"),
    COMPLETED_UNCONFIRMED("发送完成，设备未提供完成回执"),
    RETRY_WAIT("等待重试"),
    FAILED("失败"),
    CANCELLED("已取消");

    public final String label;

    PrintJobState(String label) {
        this.label = label;
    }
}
