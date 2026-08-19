package com.otool.eprint.ui.config;

import android.app.Activity;
import android.content.Context;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.otool.eprint.R;
import com.otool.eprint.scan.RecognitionSettings;
import com.otool.eprint.scan.RecognitionSettingsStore;
import com.otool.eprint.scan.ScanInputMode;
import com.otool.eprint.scan.vlm.VlmOcrEngine;
import com.otool.eprint.scan.vlm.VlmSettings;
import com.otool.eprint.scan.vlm.VlmSettingsStore;

import java.io.File;

/** 识别模型配置对话框：标签 / 二维码 / 条形码三页独立配置并持久化。 */
public final class ModelConfigDialog {

    /** 融合权重五档的滑块文案（下标 = 档位 - 1）。 */
    private static final String[] FUSION_WEIGHT_LABELS = {
            "融合权重 1 档 · PP-OCR 为主（GLM-OCR 只补漏）",
            "融合权重 2 档 · PP-OCR 语义权重大于 GLM-OCR",
            "融合权重 3 档 · 均衡融合（按置信度取舍）",
            "融合权重 4 档 · GLM-OCR 语义权重大于 PP-OCR",
            "融合权重 5 档 · GLM-OCR 为主（PP-OCR 只补漏）",
    };

    /** 保存与重启动作回调，由宿主 Activity 实现。 */
    public interface Listener {
        /** 配置已写入 Store 后回调：宿主负责应用新配置并刷新识别流程。 */
        void onSettingsSaved(RecognitionSettings saved);

        /** 用户点击“保存并重启”时回调：配置已保存，宿主负责重启。 */
        void onRestartRequested();
    }

    private ModelConfigDialog() {}

    public static void show(Activity activity, ScanInputMode scanInputMode, Listener listener) {
        VlmSettings current = VlmSettingsStore.load(activity);
        RecognitionSettings recognition = RecognitionSettingsStore.load(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8));

        TextView overview = new TextView(activity);
        overview.setText("三种识别模式独立配置并持久化；每个工作页面也会分别记住最后使用的扫描档位。"
                + " 标签处理固定在全部模型结束后才生成模板，冲突字段默认由 GLM-OCR 优先。");
        overview.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
        overview.setPadding(dp(activity, 4), 0, dp(activity, 4), dp(activity, 8));
        root.addView(overview);

        LinearLayout tabs = new LinearLayout(activity);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        Button labelTab = createConfigTab(activity, "标签");
        Button qrTab = createConfigTab(activity, "二维码");
        Button barcodeTab = createConfigTab(activity, "条形码");
        tabs.addView(labelTab, weightedParams(activity));
        tabs.addView(qrTab, weightedParams(activity));
        tabs.addView(barcodeTab, weightedParams(activity));
        root.addView(tabs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 46)));

        FrameLayout pageHost = new FrameLayout(activity);
        LinearLayout labelPage = configPage(activity);
        LinearLayout qrPage = configPage(activity);
        LinearLayout barcodePage = configPage(activity);
        pageHost.addView(labelPage);
        pageHost.addView(qrPage);
        pageHost.addView(barcodePage);
        root.addView(pageHost, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        Button[] tabButtons = {labelTab, qrTab, barcodeTab};
        View[] pages = {labelPage, qrPage, barcodePage};
        labelTab.setOnClickListener(view -> selectConfigPage(activity, tabButtons, pages, 0));
        qrTab.setOnClickListener(view -> selectConfigPage(activity, tabButtons, pages, 1));
        barcodeTab.setOnClickListener(view -> selectConfigPage(activity, tabButtons, pages, 2));

        addConfigSection(activity, labelPage, "标签识别总开关与流程");
        CheckBox labelEnabled = addConfigCheck(activity, labelPage, "启用标签识别", recognition.labelEnabled);
        CheckBox ppEnabled = addConfigCheck(activity, labelPage, "启用 PP-OCRv6（文字检测/方向/识别）",
                recognition.ppOcrEnabled);
        CheckBox vlmEnabled = addConfigCheck(activity, labelPage, "启用 GLM-OCR 信息提取",
                recognition.vlmEnabled);
        CheckBox cropLabel = addConfigCheck(activity, labelPage, "自动定位标签并清除标签外区域",
                recognition.cropToLabel);
        CheckBox perspectiveRectify = addConfigCheck(activity, labelPage,
                "OpenCV 透视纠偏标签（默认开启；失败自动回退原旋正）",
                recognition.perspectiveRectify);
        CheckBox maskCodes = addConfigCheck(activity, labelPage,
                "实验：送入 GLM 前遮盖二维码/一维码（默认关闭）",
                recognition.maskCodesBeforeVlm);
        CheckBox weakMask = addConfigCheck(activity, labelPage,
                "实验性弱码条纹定位（默认关闭；可能误伤文字）",
                recognition.experimentalWeakCodeMask);
        CheckBox processingStages = addConfigCheck(activity, labelPage, "显示每一步实时处理图片",
                recognition.showProcessingPreview);
        TextView fusionWeightLabel = new TextView(activity);
        fusionWeightLabel.setTextColor(ContextCompat.getColor(activity, R.color.eprint_text));
        SeekBar fusionWeight = new SeekBar(activity);
        fusionWeight.setMax(4); // 5 档：progress 0..4 ↔ 档位 1..5
        fusionWeight.setProgress(recognition.fusionWeightLevel - 1);
        fusionWeightLabel.setText(FUSION_WEIGHT_LABELS[recognition.fusionWeightLevel - 1]);
        fusionWeight.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                fusionWeightLabel.setText(FUSION_WEIGHT_LABELS[progress]);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });
        labelPage.addView(fusionWeightLabel);
        labelPage.addView(fusionWeight);
        EditText minimumCoverage = addSettingField(activity, labelPage,
                "标签定位最低占比（10-90%）",
                recognition.minimumLabelCoveragePercent, true);
        EditText blurThreshold = addSettingField(activity, labelPage,
                "模糊提示阈值（10-500，越大越严格）",
                recognition.blurVarianceThreshold, true);

        File modelDir = VlmOcrEngine.modelDir(activity);
        String missing = VlmOcrEngine.missingModelFile(modelDir);
        addConfigSection(activity, labelPage, "GLM-OCR 0.9B · MNN 运行参数");
        TextView status = new TextView(activity);
        status.setText("模型：" + (missing == null ? "已部署" : "缺少 " + missing)
                + "\n目录：" + modelDir.getAbsolutePath()
                + "\n严格七键 JSON 信息提取；完整 schema 不允许被自定义提示词移除。"
                + "\n保存后会释放并按新配置重新加载；开发阶段不做失败自动回退。");
        status.setTextColor(ContextCompat.getColor(activity, R.color.eprint_text));
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(activity, 4), 0, dp(activity, 8));
        labelPage.addView(status);
        Spinner backend = addModelSettingSpinner(activity, labelPage, "推理后端",
                VlmSettings.BACKEND_LABELS,
                VlmSettings.indexOf(current.backend, VlmSettings.BACKEND_VALUES));
        EditText threads = addSettingField(activity, labelPage, "CPU 线程数（1-8）",
                current.threadCount, true);
        Spinner precision = addModelSettingSpinner(activity, labelPage, "计算精度",
                VlmSettings.PRECISION_LABELS,
                VlmSettings.indexOf(current.precision, VlmSettings.PRECISION_VALUES));
        Spinner memory = addModelSettingSpinner(activity, labelPage, "内存策略",
                VlmSettings.MEMORY_LABELS,
                VlmSettings.indexOf(current.memory, VlmSettings.MEMORY_VALUES));
        EditText maxTokens = addSettingField(activity, labelPage, "最大输出 Token（64-2048）",
                current.maxNewTokens, true);
        EditText timeout = addSettingField(activity, labelPage, "单次识别超时秒数（10-300）",
                current.timeoutSeconds, true);
        EditText repetitionPenalty = addDecimalSettingField(activity, labelPage,
                "重复惩罚（1.0-3.0；JSON 建议 1.1）", current.repetitionPenalty);
        EditText presencePenalty = addDecimalSettingField(activity, labelPage,
                "出现惩罚（0.0-2.0；JSON 建议 0.0）", current.presencePenalty);
        EditText frequencyPenalty = addDecimalSettingField(activity, labelPage,
                "频率惩罚（0.0-2.0；JSON 建议 0.0）", current.frequencyPenalty);
        EditText prompt = addSettingField(activity, labelPage,
                "信息提取提示词（自动补齐完整 JSON schema）", current.prompt, false);

        addConfigSection(activity, qrPage, "二维码识别");
        CheckBox qrEnabled = addConfigCheck(activity, qrPage, "启用二维码识别", recognition.qrEnabled);
        CheckBox qrContinuous = addConfigCheck(activity, qrPage, "连续识别（关闭后每次只识别一个）",
                recognition.qrContinuous);
        CheckBox qrTryHarder = addConfigCheck(activity, qrPage, "增强定位 / Try Harder",
                recognition.qrTryHarder);
        CheckBox qrInverted = addConfigCheck(activity, qrPage, "同时识别反色码",
                recognition.qrAlsoInverted);
        CheckBox qrExtended = addConfigCheck(activity, qrPage,
                "扩展二维制式（Data Matrix / Aztec / PDF417）",
                recognition.qrExtendedFormats);
        CheckBox qrDuplicates = addConfigCheck(activity, qrPage, "2.5 秒内抑制重复结果",
                recognition.qrSuppressDuplicates);
        TextView qrHelp = new TextView(activity);
        qrHelp.setText("二维码页只启用所选二维格式，不会混入一维条形码；解析与画布更新均在本机完成。");
        qrHelp.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
        qrPage.addView(qrHelp);

        addConfigSection(activity, barcodePage, "一维条形码识别");
        CheckBox barcodeEnabled = addConfigCheck(activity, barcodePage, "启用条形码识别",
                recognition.barcodeEnabled);
        CheckBox barcodeContinuous = addConfigCheck(activity, barcodePage,
                "连续识别（关闭后每次只识别一个）", recognition.barcodeContinuous);
        CheckBox barcodeTryHarder = addConfigCheck(activity, barcodePage, "增强定位 / Try Harder",
                recognition.barcodeTryHarder);
        CheckBox barcodeInverted = addConfigCheck(activity, barcodePage, "同时识别反色码",
                recognition.barcodeAlsoInverted);
        addConfigSection(activity, barcodePage, "启用格式组");
        CheckBox retailFormats = addConfigCheck(activity, barcodePage,
                "零售码（EAN-13 / EAN-8 / UPC-A / UPC-E）",
                recognition.barcodeRetailFormats);
        CheckBox industrialFormats = addConfigCheck(activity, barcodePage,
                "工业码（Code 128 / Code 39 / Code 93）",
                recognition.barcodeIndustrialFormats);
        CheckBox logisticsFormats = addConfigCheck(activity, barcodePage,
                "物流码（ITF / Codabar）", recognition.barcodeLogisticsFormats);
        CheckBox barcodeDuplicates = addConfigCheck(activity, barcodePage, "2.5 秒内抑制重复结果",
                recognition.barcodeSuppressDuplicates);

        int initialPage = scanInputMode == ScanInputMode.LABEL ? 0
                : scanInputMode == ScanInputMode.QR ? 1 : 2;
        selectConfigPage(activity, tabButtons, pages, initialPage);

        ScrollView scroll = new ScrollView(activity);
        scroll.addView(root);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("识别配置 · 标签 / 二维码 / 条形码")
                .setView(scroll)
                .setNeutralButton("取消", null)
                .setNegativeButton("保存并重启", null)
                .setPositiveButton("暂存配置", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            // 校验 + 持久化 + 应用；校验失败弹提示并返回 false。两个按钮共用。
            java.util.function.Supplier<Boolean> saveAll = () -> {
                try {
                        String promptValue = prompt.getText().toString().trim();
                        if (labelEnabled.isChecked() && !ppEnabled.isChecked()
                                && !vlmEnabled.isChecked()) {
                            throw new IllegalArgumentException("标签识别至少启用 PP-OCR 或 GLM-OCR");
                        }
                        if (vlmEnabled.isChecked() && promptValue.isEmpty()) {
                            throw new IllegalArgumentException("信息提取提示词不能为空");
                        }
                        if (barcodeEnabled.isChecked() && !retailFormats.isChecked()
                                && !industrialFormats.isChecked()
                                && !logisticsFormats.isChecked()) {
                            throw new IllegalArgumentException("条形码至少启用一个格式组");
                        }
                        VlmSettings saved = new VlmSettings(
                                VlmSettings.BACKEND_VALUES[backend.getSelectedItemPosition()],
                                integerInRange(threads, 1, 8, "CPU 线程数"),
                                VlmSettings.PRECISION_VALUES[precision.getSelectedItemPosition()],
                                VlmSettings.MEMORY_VALUES[memory.getSelectedItemPosition()],
                                integerInRange(maxTokens, 64, 2048, "最大输出 Token"),
                                integerInRange(timeout, 10, 300, "识别超时"),
                                floatInRange(repetitionPenalty, 1.0f, 3.0f, "重复惩罚"),
                                floatInRange(presencePenalty, 0.0f, 2.0f, "出现惩罚"),
                                floatInRange(frequencyPenalty, 0.0f, 2.0f, "频率惩罚"),
                                promptValue);
                        RecognitionSettings savedRecognition = new RecognitionSettings(
                                labelEnabled.isChecked(), ppEnabled.isChecked(),
                                vlmEnabled.isChecked(), cropLabel.isChecked(),
                                perspectiveRectify.isChecked(),
                                maskCodes.isChecked(), weakMask.isChecked(),
                                processingStages.isChecked(), fusionWeight.getProgress() + 1,
                                integerInRange(minimumCoverage, 10, 90, "标签定位最低占比"),
                                integerInRange(blurThreshold, 10, 500, "模糊提示阈值"),
                                qrEnabled.isChecked(), qrContinuous.isChecked(),
                                qrTryHarder.isChecked(), qrInverted.isChecked(),
                                qrExtended.isChecked(), qrDuplicates.isChecked(),
                                barcodeEnabled.isChecked(), barcodeContinuous.isChecked(),
                                barcodeTryHarder.isChecked(), barcodeInverted.isChecked(),
                                retailFormats.isChecked(), industrialFormats.isChecked(),
                                logisticsFormats.isChecked(), barcodeDuplicates.isChecked());
                        VlmSettingsStore.save(activity, saved);
                        RecognitionSettingsStore.save(activity, savedRecognition);
                        listener.onSettingsSaved(savedRecognition);
                        return true;
                    } catch (IllegalArgumentException invalid) {
                        toast(activity, invalid.getMessage());
                        return false;
                    }
            };
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> {
                if (!saveAll.get()) return; // 校验未过不重启，避免丢掉未通过校验的配置
                dialog.dismiss();
                listener.onRestartRequested();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (!saveAll.get()) return;
                    toast(activity, "三种识别配置已暂存并应用；重启后仍保持");
                    dialog.dismiss();
                });
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) window.setLayout(dp(activity, 960), WindowManager.LayoutParams.MATCH_PARENT);
    }

    private static LinearLayout configPage(Activity activity) {
        LinearLayout page = new LinearLayout(activity);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(activity, 8), dp(activity, 8), dp(activity, 8), dp(activity, 18));
        page.setVisibility(View.GONE);
        return page;
    }

    private static Button createConfigTab(Activity activity, String label) {
        Button tab = new Button(activity);
        tab.setText(label);
        tab.setAllCaps(false);
        tab.setMinHeight(0);
        tab.setMinWidth(0);
        return tab;
    }

    private static LinearLayout.LayoutParams weightedParams(Activity activity) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        params.setMarginEnd(dp(activity, 4));
        return params;
    }

    private static void selectConfigPage(Activity activity, Button[] tabs, View[] pages, int selected) {
        for (int index = 0; index < pages.length; index++) {
            boolean active = index == selected;
            pages[index].setVisibility(active ? View.VISIBLE : View.GONE);
            tabs[index].setBackgroundResource(active
                    ? R.drawable.button_primary : R.drawable.button_secondary);
            tabs[index].setTextColor(ContextCompat.getColor(activity,
                    active ? R.color.eprint_paper : R.color.eprint_text));
        }
    }

    private static void addConfigSection(Activity activity, LinearLayout form, String title) {
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextColor(ContextCompat.getColor(activity, R.color.eprint_text));
        heading.setTextSize(15f);
        heading.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        heading.setPadding(0, dp(activity, 14), 0, dp(activity, 4));
        form.addView(heading);
    }

    private static CheckBox addConfigCheck(Activity activity, LinearLayout form, String label, boolean checked) {
        CheckBox check = new CheckBox(activity);
        check.setText(label);
        check.setChecked(checked);
        check.setTextColor(ContextCompat.getColor(activity, R.color.eprint_text));
        check.setMinHeight(dp(activity, 42));
        form.addView(check, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return check;
    }

    private static Spinner addModelSettingSpinner(Activity activity, LinearLayout form, String label,
                                                  String[] labels, int selection) {
        TextView title = new TextView(activity);
        title.setText(label);
        title.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
        title.setPadding(0, dp(activity, 8), 0, 0);
        form.addView(title);
        Spinner spinner = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(labels.length - 1, selection)));
        form.addView(spinner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 48)));
        return spinner;
    }

    private static EditText addSettingField(Activity activity, LinearLayout form, String label,
                                            int value, boolean numeric) {
        return addSettingField(activity, form, label, Integer.toString(value), numeric);
    }

    private static EditText addDecimalSettingField(Activity activity, LinearLayout form, String label, float value) {
        EditText input = addSettingField(activity, form, label, Float.toString(value), true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        return input;
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

    private static float floatInRange(EditText input, float minimum, float maximum, String label) {
        try {
            float value = Float.parseFloat(input.getText().toString().trim());
            if (!Float.isFinite(value) || value < minimum || value > maximum) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(label + "必须在 " + minimum + " 到 " + maximum + " 之间");
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }
}
