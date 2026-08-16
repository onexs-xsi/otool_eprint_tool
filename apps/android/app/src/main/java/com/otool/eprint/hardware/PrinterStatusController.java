package com.otool.eprint.hardware;

import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.protocol.ProtocolType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class PrinterStatusController {
    private final PrinterProfile profile;
    private final Deque<Integer> expectedEscStatus = new ArrayDeque<>();
    private final StringBuilder zplBuffer = new StringBuilder();
    private final List<String> zplStatusLines = new ArrayList<>();
    private PrinterHardwareStatus status = PrinterHardwareStatus.disconnected("未连接");

    public PrinterStatusController(PrinterProfile profile) {
        this.profile = profile;
    }

    public synchronized void onConnected() {
        zplBuffer.setLength(0);
        zplStatusLines.clear();
        status = PrinterHardwareStatus.connected("通道已连接，等待设备状态");
    }

    public synchronized void onDisconnected(String detail) {
        expectedEscStatus.clear();
        zplBuffer.setLength(0);
        zplStatusLines.clear();
        status = PrinterHardwareStatus.disconnected(detail);
    }

    public synchronized byte[] createProbe() {
        expectedEscStatus.clear();
        if (profile.protocol == ProtocolType.ESC_POS) {
            for (int type = 1; type <= 4; type++) expectedEscStatus.addLast(type);
            return new byte[]{0x10, 0x04, 0x01, 0x10, 0x04, 0x02,
                    0x10, 0x04, 0x03, 0x10, 0x04, 0x04};
        }
        if (profile.protocol == ProtocolType.ZPL) {
            zplBuffer.setLength(0);
            zplStatusLines.clear();
            return "~HS\r\n".getBytes(StandardCharsets.US_ASCII);
        }
        return new byte[0];
    }

    public synchronized PrinterHardwareStatus onBytes(byte[] bytes) {
        if (profile.protocol == ProtocolType.ESC_POS && !expectedEscStatus.isEmpty()) {
            for (byte value : bytes) {
                Integer type = expectedEscStatus.pollFirst();
                if (type == null) break;
                applyEscStatus(type, value & 0xFF);
            }
        } else if (profile.protocol == ProtocolType.ZPL) {
            applyZplStatus(bytes);
        } else {
            String raw = toDisplay(bytes);
            status = status.with(status.busy, status.paper, status.cover,
                    status.temperature, true, "设备响应：" + raw);
        }
        return status;
    }

    public synchronized PrinterHardwareStatus current() {
        return status;
    }

    private void applyEscStatus(int type, int value) {
        boolean busy = status.busy;
        HardwareValue paper = status.paper;
        HardwareValue cover = status.cover;
        HardwareValue temperature = status.temperature;
        String detail;
        if (type == 1) {
            busy = (value & 0x08) != 0;
            detail = "ESC/POS 打印机状态 0x" + hex(value);
        } else if (type == 2) {
            cover = (value & 0x04) != 0 ? HardwareValue.ERROR : HardwareValue.OK;
            busy = busy || (value & 0x68) != 0;
            detail = "ESC/POS 离线状态 0x" + hex(value);
        } else if (type == 3) {
            temperature = (value & 0x40) != 0 ? HardwareValue.WARNING : HardwareValue.OK;
            detail = "ESC/POS 错误状态 0x" + hex(value);
        } else {
            paper = (value & 0x60) != 0 ? HardwareValue.ERROR
                    : (value & 0x0C) != 0 ? HardwareValue.WARNING : HardwareValue.OK;
            detail = "ESC/POS 纸张状态 0x" + hex(value);
        }
        status = status.with(busy, paper, cover, temperature, true, detail);
    }

    private void applyZplStatus(byte[] bytes) {
        zplBuffer.append(new String(bytes, StandardCharsets.US_ASCII));
        int end;
        while ((end = zplBuffer.indexOf("\u0003")) >= 0) {
            String line = zplBuffer.substring(0, end)
                    .replace("\u0002", "").replace("\r", "").replace("\n", "").trim();
            zplBuffer.delete(0, end + 1);
            if (!line.isEmpty()) zplStatusLines.add(line);
        }
        if (zplStatusLines.size() < 2) {
            status = status.with(status.busy, status.paper, status.cover,
                    status.temperature, true, "已收到 ZPL 状态片段");
            return;
        }
        String[] first = zplStatusLines.get(0).split(",", -1);
        String[] second = zplStatusLines.get(1).split(",", -1);
        if (first.length < 8 || second.length < 4) {
            status = status.with(status.busy, status.paper, status.cover,
                    status.temperature, true, "ZPL ~HS 响应字段不完整");
            return;
        }
        boolean paperOut = "1".equals(first[1].trim());
        boolean paused = "1".equals(first[2].trim());
        boolean bufferFull = "1".equals(first[5].trim());
        boolean partialFormat = "1".equals(first[7].trim());
        boolean headUp = "1".equals(second[2].trim());
        boolean ribbonOut = "1".equals(second[3].trim());
        status = status.with(paused || bufferFull || partialFormat,
                paperOut || ribbonOut ? HardwareValue.ERROR : HardwareValue.OK,
                headUp ? HardwareValue.ERROR : HardwareValue.OK,
                status.temperature, true,
                "ZPL ~HS：" + (paperOut ? "缺纸 " : "") + (ribbonOut ? "缺碳带 " : "")
                        + (headUp ? "打印头抬起 " : "") + (paused ? "已暂停" : "设备就绪"));
        if (zplStatusLines.size() >= 3) zplStatusLines.clear();
    }

    private static String toDisplay(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.US_ASCII).trim();
        if (!text.isEmpty()) return text.length() > 160 ? text.substring(0, 160) + "…" : text;
        StringBuilder hex = new StringBuilder();
        for (int index = 0; index < bytes.length && index < 48; index++) {
            if (hex.length() > 0) hex.append(' ');
            hex.append(hex(bytes[index] & 0xFF));
        }
        return hex.toString();
    }

    private static String hex(int value) {
        return String.format(java.util.Locale.US, "%02X", value);
    }
}
