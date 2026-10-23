package com.otool.eprint.ui.profile;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.otool.eprint.R;
import com.otool.eprint.device.DeviceCandidate;
import com.otool.eprint.device.DeviceCatalog;
import com.otool.eprint.device.DeviceSettingsStore;
import com.otool.eprint.device.PaperType;
import com.otool.eprint.device.PrinterIdentity;
import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.protocol.DothanCompressionMode;
import com.otool.eprint.protocol.DothanJobOptions;
import com.otool.eprint.protocol.ProtocolType;
import com.otool.eprint.service.PrinterConnectionService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 打印机档案设置对话框：浓度/速度/纸张等档案参数与 P1 私有协议选项，离线保存。 */
public final class ProfileSettingsDialog {

    /**
     * 对话框与宿主 Activity 的交互回调：字段回写、打印任务选项读写、保存收尾。
     * 各方法对应原 MainActivity.showProfileSettings 中依赖宿主状态的位置。
     */
    public interface Listener {
        /** 打开对话框时目标设备的离线档案覆盖（DeviceSettingsStore.apply）结果回写。 */
        void onWorkingProfileAdjusted(PrinterProfile profile);

        /** 读取当前德佟打印任务选项（份数/边距/方向/翻转/反色）。 */
        DothanJobOptions loadDothanJobOptions();

        /** 保存通用反色设置。 */
        void onCommonAntiColorSaved(boolean anti);

        /** 保存德佟打印任务选项。 */
        void onDothanJobOptionsSaved(int copies, int leftTenthsMm, int rightTenthsMm,
                                     int topTenthsMm, int bottomTenthsMm,
                                     int direction, boolean flip, boolean anti);

        /** 档案保存收尾：回写档案、持久化、刷新界面并关闭对话框、按需重连打印机。 */
        void onProfileSaved(PrinterProfile effectiveProfile, @Nullable DeviceCandidate target,
                            AlertDialog dialog);
    }

    private ProfileSettingsDialog() {}

    public static void show(Activity activity, @Nullable PrinterConnectionService printerService,
                            PrinterProfile selectedProfile, Listener listener) {
        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), dp(activity, 12));
        DeviceCandidate target = printerService == null ? null : printerService.targetDevice();
        PrinterProfile workingProfile = selectedProfile;
        if (target != null) {
            workingProfile = DeviceSettingsStore.apply(activity, target, workingProfile);
            listener.onWorkingProfileAdjusted(workingProfile);
        }
        PrinterProfile profile = workingProfile;
        PrinterIdentity identity = target == null ? null
                : DeviceCatalog.identify(target.name, target.transport);
        TextView deviceInfo = new TextView(activity);
        String maker = identity == null ? profile.manufacturer : identity.manufacturer;
        String type = identity == null ? profile.deviceType : identity.deviceType;
        String specification = identity == null
                ? profile.dpi + " DPI · " + profile.printableWidthDots
                + " 点 / " + String.format(Locale.US, "%.1f", profile.printableWidthMm()) + " mm"
                : identity.specification();
        deviceInfo.setText("设备名称：" + (target == null ? "未选择设备" : target.name)
                + "\n制造商：" + maker
                + "\n设备类型：" + type
                + "\n打印规格：" + specification
                + "\n协议档案：" + profile.label);
        deviceInfo.setTextColor(ContextCompat.getColor(activity, R.color.eprint_text));
        deviceInfo.setPadding(0, dp(activity, 6), 0, dp(activity, 8));
        form.addView(deviceInfo);

        boolean p1Config = profile.protocol == ProtocolType.DOTHAN_PRIVATE;
        int densityMinimum = p1Config ? 1 : 0;
        SeekBar density = addSettingSlider(activity, form,
                p1Config ? "打印浓度（P1 显示档位 1-15）" : "打印浓度（0-15）",
                densityMinimum, 15, profile.printDensity);
        SeekBar speed = addSettingSlider(activity, form, "打印速度（1-14）",
                1, 14, profile.printSpeed);
        DothanJobOptions currentJob = listener.loadDothanJobOptions();
        CheckBox antiColor = new CheckBox(activity);
        antiColor.setText("反色打印（黑白互换）");
        antiColor.setChecked(currentJob.antiColor);
        antiColor.setPadding(0, dp(activity, 4), 0, dp(activity, 4));
        form.addView(antiColor);
        TextView paperTitle = new TextView(activity);
        paperTitle.setText("纸张类型");
        paperTitle.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
        paperTitle.setPadding(0, dp(activity, 8), 0, 0);
        form.addView(paperTitle);
        Spinner paper = new Spinner(activity);
        ArrayAdapter<PaperType> paperAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, PaperType.values());
        paperAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        paper.setAdapter(paperAdapter);
        paper.setSelection(profile.paperType.ordinal());
        form.addView(paper);
        EditText gap = addSettingField(activity, form, "间隙长度（0.1 mm；30 表示 3.0 mm）",
                profile.gapLengthTenthsMm, true);
        CheckBox autoFeed = new CheckBox(activity);
        autoFeed.setText("打印后自动出纸");
        autoFeed.setChecked(profile.autoFeed);
        form.addView(autoFeed);
        CheckBox beep = new CheckBox(activity);
        beep.setText("打印机提示音（需设备协议支持）");
        beep.setChecked(profile.beep);
        form.addView(beep);
        EditText powerOff = addSettingField(activity, form, "自动关机时间（分钟；0 表示关闭）",
                profile.autoPowerOffMinutes, true);

        TextView protocolNote = new TextView(activity);
        protocolNote.setText("P1 会写入并回读确认浓度、速度、纸张/间隙、自动出纸、提示音和自动关机；"
                + "TSPL 会在打印任务中下发浓度、速度、纸张/间隙；ESC/POS 会应用自动出纸。"
                + "未验证协议的厂商扩展只离线保存，不会盲目发送。");
        protocolNote.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
        protocolNote.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
        form.addView(protocolNote);

        Button advancedToggle = new Button(activity);
        advancedToggle.setText("展开高级设置");
        advancedToggle.setAllCaps(false);
        advancedToggle.setBackgroundResource(R.drawable.button_secondary);
        form.addView(advancedToggle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout advanced = new LinearLayout(activity);
        advanced.setOrientation(LinearLayout.VERTICAL);
        advanced.setVisibility(View.GONE);
        form.addView(advanced);

        Spinner compression = null;
        EditText helloToken = null;
        EditText loginToken = null;
        EditText motorMode = null;
        EditText language = null;
        CheckBox applyExtras = null;
        EditText jobCopies = null;
        EditText jobLeft = null;
        EditText jobRight = null;
        EditText jobTop = null;
        EditText jobBottom = null;
        Spinner jobDirection = null;
        CheckBox jobFlip = null;
        if (p1Config) {
            TextView compressionTitle = new TextView(activity);
            compressionTitle.setText("德佟私有协议位图压缩");
            compressionTitle.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
            compressionTitle.setPadding(0, dp(activity, 8), 0, 0);
            advanced.addView(compressionTitle);
            compression = new Spinner(activity);
            ArrayAdapter<DothanCompressionMode> compressionAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item, DothanCompressionMode.values());
            compressionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            compression.setAdapter(compressionAdapter);
            compression.setSelection(profile.compression.ordinal());
            advanced.addView(compression);
            helloToken = addSettingField(activity, advanced, "自定义 helloToken（16 进制；留空表示不用自定义令牌）",
                    profile.helloTokenHex, false);
            loginToken = addSettingField(activity, advanced, "loginToken（16 进制，4 字节）",
                    profile.loginTokenHex, false);
            motorMode = addSettingField(activity, advanced, "电机模式（整数）",
                    profile.motorMode, true);
            language = addSettingField(activity, advanced, "语言（整数）",
                    profile.language, true);
            applyExtras = new CheckBox(activity);
            applyExtras.setText("向设备下发电机模式和语言（实验性，默认仅离线保存）");
            applyExtras.setChecked(profile.applyDothanExtras);
            advanced.addView(applyExtras);

            TextView jobTitle = new TextView(activity);
            jobTitle.setText("打印任务选项（份数 / 边距 / 方向 / 翻转，均待真机复验）");
            jobTitle.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
            jobTitle.setPadding(0, dp(activity, 12), 0, 0);
            advanced.addView(jobTitle);

            jobCopies = addSettingField(activity, advanced, "打印份数（1-20）", currentJob.copies, true);
            jobLeft = addSettingField(activity, advanced, "左边距（0.1 mm）",
                    Math.round(currentJob.leftDots * 254f / profile.dpi), true);
            jobRight = addSettingField(activity, advanced, "右边距（0.1 mm）",
                    Math.round(currentJob.rightDots * 254f / profile.dpi), true);
            jobTop = addSettingField(activity, advanced, "上边距（0.1 mm）",
                    Math.round(currentJob.topDots * 254f / profile.dpi), true);
            jobBottom = addSettingField(activity, advanced, "下边距（0.1 mm）",
                    Math.round(currentJob.bottomDots * 254f / profile.dpi), true);

            TextView directionTitle = new TextView(activity);
            directionTitle.setText("旋转方向");
            directionTitle.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
            directionTitle.setPadding(0, dp(activity, 8), 0, 0);
            advanced.addView(directionTitle);
            jobDirection = new Spinner(activity);
            ArrayAdapter<String> directionAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item, new String[]{"正常", "旋转 180°"});
            directionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            jobDirection.setAdapter(directionAdapter);
            jobDirection.setSelection(currentJob.direction == 180 ? 1 : 0);
            advanced.addView(jobDirection);

            jobFlip = new CheckBox(activity);
            jobFlip.setText("水平翻转");
            jobFlip.setChecked(currentJob.flipHorizontal);
            advanced.addView(jobFlip);
        }

        EditText width = addSettingField(activity, advanced, "最大可打印点宽", profile.printableWidthDots, true);
        EditText spp = addSettingField(activity, advanced, "SPP 分包字节", profile.sppChunkBytes, true);
        EditText ble = addSettingField(activity, advanced, "BLE 分包字节", profile.bleChunkBytes, true);
        EditText usb = addSettingField(activity, advanced, "USB Bulk 分包字节", profile.usbChunkBytes, true);
        EditText delay = addSettingField(activity, advanced, "分包间隔（毫秒）", profile.writeDelayMs, true);
        EditText retries = addSettingField(activity, advanced, "单包写入重试次数", profile.maxWriteRetries, true);
        EditText threshold = addSettingField(activity, advanced, "点阵黑白阈值（1-254）", profile.threshold, true);
        EditText services = addSettingField(activity, advanced, "首选 BLE Service UUID（逗号分隔）",
                TextUtils.join(",\n", profile.preferredBleServiceStrings()), false);
        EditText writes = addSettingField(activity, advanced, "首选 BLE Write UUID（逗号分隔）",
                TextUtils.join(",\n", profile.preferredBleWriteStrings()), false);
        advancedToggle.setOnClickListener(view -> {
            boolean visible = advanced.getVisibility() == View.VISIBLE;
            advanced.setVisibility(visible ? View.GONE : View.VISIBLE);
            advancedToggle.setText(visible ? "展开高级设置" : "收起高级设置");
        });
        Spinner compressionView = compression;
        EditText helloTokenView = helloToken;
        EditText loginTokenView = loginToken;
        EditText motorModeView = motorMode;
        EditText languageView = language;
        CheckBox applyExtrasView = applyExtras;
        EditText jobCopiesView = jobCopies;
        EditText jobLeftView = jobLeft;
        EditText jobRightView = jobRight;
        EditText jobTopView = jobTop;
        EditText jobBottomView = jobBottom;
        Spinner jobDirectionView = jobDirection;
        CheckBox jobFlipView = jobFlip;
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("打印机配置与协议 · 离线保存")
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    try {
                        PrinterProfile customized = profile.withHardwareSettings(
                                integerInRange(width, 128, 2400, "点宽"),
                                integerInRange(spp, 64, 8192, "SPP 分包"),
                                integerInRange(ble, 20, 512, "BLE 分包"),
                                integerInRange(usb, 64, 65536, "USB 分包"),
                                integerInRange(delay, 0, 500, "分包间隔"),
                                integerInRange(retries, 0, 5, "重试次数"),
                                integerInRange(threshold, 1, 254, "黑白阈值"),
                                splitUuidInput(services.getText().toString()),
                                splitUuidInput(writes.getText().toString()))
                                .withDeviceSettings(maker, type,
                                        densityMinimum + density.getProgress(),
                                        1 + speed.getProgress(),
                                        (PaperType) paper.getSelectedItem(),
                                        integerInRange(gap, 0, 3000, "间隙长度"),
                                        autoFeed.isChecked(), beep.isChecked(),
                                        integerInRange(powerOff, 0, 1440, "自动关机时间"));
                        PrinterProfile effectiveProfile = p1Config
                                ? customized.withDothanOptions(
                                        (DothanCompressionMode) compressionView.getSelectedItem(),
                                        helloTokenView.getText().toString().trim(),
                                        loginTokenView.getText().toString().trim())
                                .withDothanExtras(
                                        integerInRange(motorModeView, 0, 255, "电机模式"),
                                        integerInRange(languageView, 0, 255, "语言"),
                                        applyExtrasView.isChecked())
                                : customized;
                        listener.onCommonAntiColorSaved(antiColor.isChecked());
                        if (p1Config) {
                            listener.onDothanJobOptionsSaved(
                                    integerInRange(jobCopiesView, 1, 20, "打印份数"),
                                    integerInRange(jobLeftView, 0, 3000, "左边距"),
                                    integerInRange(jobRightView, 0, 3000, "右边距"),
                                    integerInRange(jobTopView, 0, 3000, "上边距"),
                                    integerInRange(jobBottomView, 0, 3000, "下边距"),
                                    jobDirectionView.getSelectedItemPosition() == 1 ? 180 : 0,
                                    jobFlipView.isChecked(),
                                    antiColor.isChecked());
                        }
                        listener.onProfileSaved(effectiveProfile, target, dialog);
                    } catch (IllegalArgumentException invalid) {
                        toast(activity, invalid.getMessage());
                    }
                }));
        dialog.show();
    }

    private static SeekBar addSettingSlider(Activity activity, LinearLayout form, String label,
                                            int minimum, int maximum, int value) {
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(activity, 8), 0, 0);
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView current = new TextView(activity);
        current.setTextColor(ContextCompat.getColor(activity, R.color.eprint_text));
        current.setTextSize(16);
        current.setTypeface(current.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(current);
        form.addView(header);

        SeekBar slider = new SeekBar(activity);
        slider.setMax(Math.max(0, maximum - minimum));
        slider.setProgress(Math.max(0, Math.min(maximum, value) - minimum));
        slider.setContentDescription(label);
        current.setText(Integer.toString(minimum + slider.getProgress()));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                current.setText(Integer.toString(minimum + progress));
            }

            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        form.addView(slider, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 44)));
        return slider;
    }

    private static EditText addSettingField(Activity activity, LinearLayout form, String label,
                                            int value, boolean numeric) {
        return addSettingField(activity, form, label, Integer.toString(value), numeric);
    }

    private static EditText addSettingField(Activity activity, LinearLayout form, String label,
                                            String value, boolean numeric) {
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
        title.setPadding(0, dp(activity, 8), 0, 0);
        form.addView(title);
        EditText input = new EditText(activity);
        input.setText(value);
        input.setSelectAllOnFocus(true);
        input.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        if (!numeric) input.setMinLines(2);
        form.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return input;
    }

    private static int integerInRange(EditText input, int minimum, int maximum, String label) {
        try {
            int value = Integer.parseInt(input.getText().toString().trim());
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(label + "必须在 " + minimum + " 到 " + maximum + " 之间");
        }
    }

    private static String[] splitUuidInput(String value) {
        String[] items = value.trim().split("[,\\s]+");
        List<String> result = new ArrayList<>();
        for (String item : items) if (!item.trim().isEmpty()) result.add(item.trim());
        return result.toArray(new String[0]);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
