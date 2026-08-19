package com.otool.eprint.ui.discovery;

import android.app.Activity;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.otool.eprint.device.DeviceCandidate;
import com.otool.eprint.device.DeviceCatalog;
import com.otool.eprint.device.DeviceHistoryStore;
import com.otool.eprint.device.PrinterIdentity;
import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.device.PrinterDiscoveryEngine;
import com.otool.eprint.device.PrinterMatch;
import com.otool.eprint.device.ProfileStore;
import com.otool.eprint.device.TransportType;
import com.otool.eprint.print.BluetoothPrinterManager;
import com.otool.eprint.protocol.ProtocolType;
import com.otool.eprint.service.PrinterConnectionService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 打印机自动发现对话框：只发现不连接，点击候选并确认后回调宿主执行连接。 */
public final class PrinterDiscoveryDialog {

    /** 用户在发现对话框中确认连接某台打印机时的回调。 */
    public interface OnProfileConnectListener {
        void onProfileConnect(DeviceCandidate candidate, PrinterProfile profile, boolean automatic);
    }

    private PrinterDiscoveryDialog() {}

    public static void show(Activity activity, @Nullable PrinterConnectionService printerService,
                            PrinterProfile selectedProfile, OnProfileConnectListener connectListener) {
        Map<String, DeviceCandidate> candidates = new LinkedHashMap<>();
        List<RankedPrinter> displayed = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, labels);
        TextView status = new TextView(activity);
        status.setText("正在检查 USB、已配对 SPP 并扫描附近 BLE（10 秒）…");
        status.setPadding(dp(activity, 18), dp(activity, 12), dp(activity, 18), dp(activity, 8));
        ListView list = new ListView(activity);
        list.setAdapter(adapter);
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(status);
        container.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 360)));

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("自动寻找打印机")
                .setView(container)
                .setNeutralButton("显示全部设备", null)
                .setNegativeButton("取消", null)
                .create();
        boolean[] manualMode = {false};
        String lastAddress = DeviceHistoryStore.lastAddress(activity);
        TransportType lastTransport = DeviceHistoryStore.lastTransport(activity);
        PrinterProfile lastProfile = DeviceHistoryStore.lastProfile(activity);
        Runnable refresh = () -> refreshDiscoveryList(activity, candidates, displayed, labels, adapter,
                status, manualMode[0], lastAddress, lastTransport, lastProfile, selectedProfile);

        if (printerService == null) return;
        for (DeviceCandidate candidate : printerService.usbDevices()) {
            candidates.put(candidateKey(candidate), candidate);
        }
        if (printerService.isBluetoothEnabled()) for (DeviceCandidate candidate : printerService.bondedClassicDevices()) {
            candidates.put(candidateKey(candidate), candidate);
        }
        refresh.run();
        dialog.setOnDismissListener(value -> printerService.stopBleScan());
        list.setOnItemClickListener((parent, view, position, id) -> {
            RankedPrinter ranked = displayed.get(position);
            showPrinterCandidateDetails(activity, ranked, dialog, connectListener);
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            manualMode[0] = true;
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(false);
            refresh.run();
        });

        BluetoothPrinterManager.ScanListener scanListener = new BluetoothPrinterManager.ScanListener() {
            @Override
            public void onDevice(DeviceCandidate candidate) {
                candidates.put(candidateKey(candidate), candidate);
                refresh.run();
            }

            @Override
            public void onFinished() {
                refresh.run();
                if (displayed.isEmpty()) {
                    status.setText("未自动识别到打印机；可显示全部设备手动选择");
                } else {
                    status.setText("扫描完成：已按可信度排序。请点击目标打印机，确认后才会连接");
                }
            }

            @Override
            public void onError(String message) {
                status.setText(displayed.isEmpty() ? message : message + "；仍可选择已列出的端点");
            }
        };
        if (printerService.isBluetoothEnabled()) printerService.startBleScan(scanListener);
        else scanListener.onFinished();
    }

    private static void refreshDiscoveryList(Context context, Map<String, DeviceCandidate> candidates,
                                             List<RankedPrinter> displayed, List<String> labels,
                                             ArrayAdapter<String> adapter, TextView status,
                                             boolean manualMode, String lastAddress,
                                             TransportType lastTransport, PrinterProfile lastProfile,
                                             PrinterProfile selectedProfile) {
        List<RankedPrinter> ranked = new ArrayList<>();
        for (DeviceCandidate candidate : candidates.values()) {
            boolean remembered = candidate.transport == lastTransport
                    && candidate.address.equalsIgnoreCase(lastAddress);
            PrinterMatch match = PrinterDiscoveryEngine.evaluate(candidate, remembered ? lastAddress : null);
            PrinterProfile profile;
            if (match.suggestedProfile != null
                    && match.suggestedProfile.protocol == ProtocolType.DOTHAN_PRIVATE) {
                profile = ProfileStore.load(context, match.suggestedProfile);
            } else if (remembered) {
                profile = lastProfile;
            } else if (match.suggestedProfile != null) {
                profile = ProfileStore.load(context, match.suggestedProfile);
            } else if (match.identity != null && match.identity.dpi == 203
                    && match.identity.widthDots == 384) {
                profile = ProfileStore.load(context, DeviceCatalog.defaultProfile());
            } else {
                profile = selectedProfile;
            }
            if (manualMode || match.isLikelyPrinter()) ranked.add(new RankedPrinter(candidate, match, profile));
        }
        ranked.sort(Comparator
                .comparingInt((RankedPrinter value) -> value.match.score).reversed()
                .thenComparing(Comparator.comparingInt(
                        (RankedPrinter value) -> value.candidate.rssi).reversed()));
        displayed.clear();
        displayed.addAll(ranked);
        labels.clear();
        for (RankedPrinter value : displayed) labels.add(formatRankedPrinter(value));
        adapter.notifyDataSetChanged();
        if (!manualMode) {
            status.setText(displayed.isEmpty()
                    ? "扫描中，暂未发现具有打印机特征的设备…"
                    : "已识别 " + displayed.size() + " 个候选；只发现不连接，请点击目标打印机");
        } else {
            status.setText("手动模式：显示全部 " + displayed.size() + " 个硬件端点");
        }
    }

    private static String formatRankedPrinter(RankedPrinter value) {
        String marker = value.match.privateProtocolRisk ? "◆ 已识别（协议待确认）"
                : value.match.score >= 85 ? "★ 推荐（需点击）"
                : value.match.score >= 60 ? "● 候选" : "○ 待确认";
        String risk = value.match.privateProtocolRisk ? " · 私有协议风险" : "";
        String identity = value.match.identity == null ? ""
                : " · " + value.match.identity.manufacturer;
        return marker + " · " + value.match.confidence + " " + value.match.score + " 分" + risk
                + "\n" + value.candidate.name + " · " + value.candidate.transport.label
                + identity + "\n" + value.profile.protocol.label + " · " + value.match.reason;
    }

    private static String candidateKey(DeviceCandidate candidate) {
        return candidate.transport.name() + '|' + candidate.address;
    }

    private static void showPrinterCandidateDetails(Activity activity, RankedPrinter ranked,
                                                    AlertDialog discoveryDialog,
                                                    OnProfileConnectListener connectListener) {
        DeviceCandidate candidate = ranked.candidate;
        PrinterIdentity identity = ranked.match.identity;
        StringBuilder details = new StringBuilder()
                .append("设备名称：").append(candidate.name)
                .append("\n连接方式：").append(candidate.transport.label)
                .append("\n设备地址：").append(candidate.address);
        if (candidate.transport == TransportType.BLE_GATT && candidate.rssi != 0) {
            details.append("\n信号强度：").append(candidate.rssi).append(" dBm");
        }
        if (identity != null) {
            details.append("\n\n识别系列：").append(identity.family)
                    .append("\n制造商：").append(identity.manufacturer)
                    .append("\n设备类型：").append(identity.deviceType)
                    .append("\n打印规格：").append(identity.specification());
        }
        details.append("\n\n识别可信度：").append(ranked.match.confidence)
                .append("（").append(ranked.match.score).append(" 分）")
                .append("\n识别依据：").append(ranked.match.reason)
                .append("\n拟用档案：").append(ranked.profile.label);
        if (ranked.match.privateProtocolRisk) {
            details.append("\n\n注意：该系列使用厂商私有协议。当前只确认硬件身份和 BLE 通道，")
                    .append("不会把它伪装成已验证的公开打印协议。");
        }
        new AlertDialog.Builder(activity)
                .setTitle("确认目标打印机")
                .setMessage(details.toString())
                .setNegativeButton("返回列表", null)
                .setPositiveButton("连接此打印机", (dialog, which) -> {
                    discoveryDialog.dismiss();
                    connectRankedPrinter(activity, ranked, connectListener);
                })
                .show();
    }

    private static void connectRankedPrinter(Activity activity, RankedPrinter ranked,
                                             OnProfileConnectListener connectListener) {
        if (ranked.match.privateProtocolRisk) {
            new AlertDialog.Builder(activity)
                    .setTitle("可能是厂商私有协议设备")
                    .setMessage("已确认设备身份和 203 DPI / 384 点尺寸，但尚未确认其上层打印协议。"
                            + "当前版本不绕过厂商授权；只有设备同时接受所选公开档案时才能打印。")
                    .setPositiveButton("按尺寸档案连接", (dialog, which) ->
                            connectListener.onProfileConnect(ranked.candidate, ranked.profile, false))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }
        connectListener.onProfileConnect(ranked.candidate, ranked.profile, true);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class RankedPrinter {
        final DeviceCandidate candidate;
        final PrinterMatch match;
        final PrinterProfile profile;

        RankedPrinter(DeviceCandidate candidate, PrinterMatch match, PrinterProfile profile) {
            this.candidate = candidate;
            this.match = match;
            this.profile = profile;
        }
    }
}
