package com.otool.eprint.ui.config;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import com.otool.eprint.scan.qwen.QwenSettings;
import com.otool.eprint.scan.qwen.QwenSettingsStore;
import com.otool.eprint.scan.qwen.QwenTextOnlyEngine;
import com.otool.eprint.scan.vlm.VlmOcrEngine;
import com.otool.eprint.scan.vlm.VlmSettings;
import com.otool.eprint.scan.vlm.VlmSettingsStore;

import java.io.File;

/** 识别模型配置对话框：标签 / 二维码 / 条形码三页独立配置并持久化。 */
public final class ModelConfigDialog {

    private static final String UI_PREFERENCES = "model_config_ui";
    private static final String SECTION_PREFERENCE_PREFIX = "label_section_expanded_";

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
        QwenSettings qwenCurrent = QwenSettingsStore.load(activity);
        RecognitionSettings recognition = RecognitionSettingsStore.load(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(activity, 14), dp(activity, 8), dp(activity, 14), dp(activity, 8));

        TextView overview = new TextView(activity);
        overview.setText("三种识别模式独立配置并持久化；每个工作页面也会分别记住最后使用的扫描档位。"
                + " 标签处理固定在全部模型结束后才生成模板；普通冲突按五档权重取舍，"
                + "命中立创字典的品类、品牌和封装默认使用强证据保护；Qwen 只处理 OCR 文本。");
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

        TextView labelHelp = new TextView(activity);
        labelHelp.setText("常用配置默认展开，高级与实验配置默认折叠；折叠状态会自动记住，配置值仍由保存按钮统一写入。");
        labelHelp.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
        labelHelp.setPadding(0, dp(activity, 4), 0, dp(activity, 6));
        labelPage.addView(labelHelp);

        LinearLayout labelActions = new LinearLayout(activity);
        labelActions.setOrientation(LinearLayout.HORIZONTAL);
        Button expandAllSections = createCompactButton(activity, "全部展开");
        Button collapseAllSections = createCompactButton(activity, "全部折叠");
        Button restoreRecommended = createCompactButton(activity, "恢复推荐值");
        labelActions.addView(expandAllSections, weightedParams(activity));
        labelActions.addView(collapseAllSections, weightedParams(activity));
        labelActions.addView(restoreRecommended, weightedParams(activity));
        labelPage.addView(labelActions, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 44)));

        SharedPreferences uiPreferences = activity.getSharedPreferences(
                UI_PREFERENCES, Context.MODE_PRIVATE);
        CollapsibleSection workflowSection = addCollapsibleSection(activity, labelPage,
                uiPreferences, LabelConfigUiState.SECTION_WORKFLOW,
                "识别流程", "总开关、PP-OCR 与 GLM-OCR", true);
        CheckBox labelEnabled = addConfigCheck(activity, workflowSection.body,
                "启用标签识别", recognition.labelEnabled);
        CheckBox ppEnabled = addConfigCheck(activity, workflowSection.body,
                "启用 PP-OCRv6（文字检测/方向/识别）",
                recognition.ppOcrEnabled);
        CheckBox vlmEnabled = addConfigCheck(activity, workflowSection.body,
                "启用 GLM-OCR（全文转写后规则提取）",
                recognition.vlmEnabled);

        CollapsibleSection preprocessingSection = addCollapsibleSection(activity, labelPage,
                uiPreferences, LabelConfigUiState.SECTION_PREPROCESSING,
                "图像预处理", "标签定位、透视、反光与质量阈值", true);
        CheckBox cropLabel = addConfigCheck(activity, preprocessingSection.body,
                "自动定位标签并清除标签外区域",
                recognition.cropToLabel);
        CheckBox perspectiveRectify = addConfigCheck(activity, preprocessingSection.body,
                "OpenCV 透视纠偏标签（默认开启；失败自动回退原旋正）",
                recognition.perspectiveRectify);
        CheckBox reduceGlare = addConfigCheck(activity, preprocessingSection.body,
                "自动抑制标签反光（局部光照均衡；默认开启）",
                recognition.reduceGlare);
        EditText minimumCoverage = addSettingField(activity, preprocessingSection.body,
                "标签定位最低占比（10-90%）",
                recognition.minimumLabelCoveragePercent, true);
        EditText blurThreshold = addSettingField(activity, preprocessingSection.body,
                "模糊提示阈值（10-500，越大越严格）",
                recognition.blurVarianceThreshold, true);
        CheckBox processingStages = addConfigCheck(activity, preprocessingSection.body,
                "显示每一步实时处理图片", recognition.showProcessingPreview);

        CollapsibleSection resultSection = addCollapsibleSection(activity, labelPage,
                uiPreferences, LabelConfigUiState.SECTION_RESULT,
                "结果整理与融合", "单位空格、字典证据与双模型权重", true);
        CheckBox spaceUnits = addConfigCheck(activity, resultSection.body,
                "识别后仅在单位后自动加空格（默认开启）",
                recognition.spaceRecognizedUnits);
        CheckBox dictionaryStrongWeight = addConfigCheck(activity, resultSection.body,
                "立创字典命中时显著提高品类/品牌/封装权重（默认开启）",
                recognition.dictionaryStrongWeight);
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
        resultSection.body.addView(fusionWeightLabel);
        resultSection.body.addView(fusionWeight);

        CollapsibleSection glmInputSection = addCollapsibleSection(activity, labelPage,
                uiPreferences, LabelConfigUiState.SECTION_GLM_INPUT,
                "GLM 输入实验", "码区遮盖与弱码定位（谨慎启用）", false);
        CheckBox maskCodes = addConfigCheck(activity, glmInputSection.body,
                "实验：送入 GLM 前遮盖二维码/一维码（默认关闭）",
                recognition.maskCodesBeforeVlm);
        CheckBox weakMask = addConfigCheck(activity, glmInputSection.body,
                "实验性弱码条纹定位（默认关闭；可能误伤文字）",
                recognition.experimentalWeakCodeMask);

        File modelDir = VlmOcrEngine.modelDir(activity);
        String missing = VlmOcrEngine.missingModelFile(modelDir);
        CollapsibleSection glmRuntimeSection = addCollapsibleSection(activity, labelPage,
                uiPreferences, LabelConfigUiState.SECTION_GLM_RUNTIME,
                "GLM-OCR 0.9B · MNN", "模型状态、运行参数与提示词", false);
        TextView status = new TextView(activity);
        status.setText("模型：" + (missing == null ? "已部署" : "缺少 " + missing)
                + "\n目录：" + modelDir.getAbsolutePath()
                + "\n默认执行全文转写 → 规则提取；含完整七键 schema 的自定义提示词可切换 JSON 提取。"
                + "\n保存后会释放并按新配置重新加载；开发阶段不做失败自动回退。");
        status.setTextColor(ContextCompat.getColor(activity, R.color.eprint_text));
        status.setTextIsSelectable(true);
        status.setPadding(0, dp(activity, 4), 0, dp(activity, 8));
        glmRuntimeSection.body.addView(status);

        Spinner backend = addModelSettingSpinner(activity, glmRuntimeSection.body, "推理后端",
                VlmSettings.BACKEND_LABELS,
                VlmSettings.indexOf(current.backend, VlmSettings.BACKEND_VALUES));
        EditText threads = addSettingField(activity, glmRuntimeSection.body,
                "CPU 线程数（1-8；SM-X800 实测 4 最快，超 4 显著变慢）",
                current.threadCount, true);
        Spinner precision = addModelSettingSpinner(activity, glmRuntimeSection.body, "计算精度",
                VlmSettings.PRECISION_LABELS,
                VlmSettings.indexOf(current.precision, VlmSettings.PRECISION_VALUES));
        Spinner memory = addModelSettingSpinner(activity, glmRuntimeSection.body, "内存策略",
                VlmSettings.MEMORY_LABELS,
                VlmSettings.indexOf(current.memory, VlmSettings.MEMORY_VALUES));
        EditText maxTokens = addSettingField(activity, glmRuntimeSection.body, "最大输出 Token（64-2048）",
                current.maxNewTokens, true);
        EditText timeout = addSettingField(activity, glmRuntimeSection.body, "单次识别超时秒数（10-300）",
                current.timeoutSeconds, true);
        EditText repetitionPenalty = addDecimalSettingField(activity, glmRuntimeSection.body,
                "重复惩罚（1.0-3.0；转写建议 1.3）", current.repetitionPenalty);
        EditText presencePenalty = addDecimalSettingField(activity, glmRuntimeSection.body,
                "出现惩罚（0.0-2.0；转写建议 0.3）", current.presencePenalty);
        EditText frequencyPenalty = addDecimalSettingField(activity, glmRuntimeSection.body,
                "频率惩罚（0.0-2.0；转写建议 0.2）", current.frequencyPenalty);
        EditText prompt = addSettingField(activity, glmRuntimeSection.body,
                "VLM 提示词（默认 Text Recognition: 转写；含完整七键 schema 则走 JSON 提取）",
                current.prompt, false);

        File qwenDir = QwenTextOnlyEngine.modelDir(activity);
        String qwenMissing = QwenTextOnlyEngine.missingModelFile(qwenDir);
        CollapsibleSection qwenSection = addCollapsibleSection(activity, labelPage,
                uiPreferences, LabelConfigUiState.SECTION_QWEN,
                "Qwen3.5-2B · MNN text-only", "OCR 后补全开关、安全策略与运行参数", false);
        TextView qwenStatus = new TextView(activity);
        qwenStatus.setText("模型：" + (qwenMissing == null ? "已部署" : "缺少 " + qwenMissing)
                + "\n目录：" + qwenDir.getAbsolutePath()
                + "\n固定 revision：" + QwenTextOnlyEngine.REVISION
                + "\n仅允许普通文本；图片标签/视觉 token 在 JNI 前拒绝，且每次结果要求 vision_time=0。"
                + "\n启用后在 PP/GLM 结束后实际调用；默认每次运行但只补空字段，无 OCR 证据不落盘。"
                + "\n开启明显错误修正后会检查完整记录；只接受有独立 OCR 证据且通过格式/目录门禁的修改。"
                + "\nGLM 与 Qwen 串行释放/加载，避免两个大模型同时常驻；CPU 后端固定为已验证路径。");
        qwenStatus.setTextColor(ContextCompat.getColor(activity, R.color.eprint_text));
        qwenStatus.setTextIsSelectable(true);
        qwenStatus.setPadding(0, dp(activity, 4), 0, dp(activity, 8));
        qwenSection.body.addView(qwenStatus);
        CheckBox qwenEnabled = addConfigCheck(activity, qwenSection.body,
                "启用 Qwen3.5 OCR 后文本补全", qwenCurrent.enabled);
        CheckBox qwenOnlyMissing = addConfigCheck(activity, qwenSection.body,
                "仅在核心字段缺失时调用（节省时间；与明显错误修正互斥）",
                qwenCurrent.onlyWhenCoreFieldsMissing);
        CheckBox qwenCorrections = addConfigCheck(activity, qwenSection.body,
                "允许 Qwen3.5 修正明显错误（需 OCR/格式/目录证据，默认关闭）",
                qwenCurrent.allowEvidenceBackedCorrections);
        CheckBox qwenCatalog = addConfigCheck(activity, qwenSection.body,
                "品类/品牌/封装必须命中立创离线字典（推荐）",
                qwenCurrent.requireCatalogMatch);
        EditText qwenThreads = addSettingField(activity, qwenSection.body,
                "Qwen CPU 线程数（1-8；SM-X800 推荐 4）", qwenCurrent.threadCount, true);
        EditText qwenMaxTokens = addSettingField(activity, qwenSection.body,
                "Qwen 最大输出 Token（64-512；推荐 192）", qwenCurrent.maxNewTokens, true);
        EditText qwenTimeout = addSettingField(activity, qwenSection.body,
                "Qwen 单次超时秒数（10-180）", qwenCurrent.timeoutSeconds, true);
        EditText qwenExtraInstructions = addSettingField(activity, qwenSection.body,
                "额外文本整理规则（可空，最多 1000 字；不能放图片/视觉标记）",
                qwenCurrent.extraInstructions, false);

        CollapsibleSection[] labelSections = {
                workflowSection, preprocessingSection, resultSection, glmInputSection,
                glmRuntimeSection, qwenSection
        };
        expandAllSections.setOnClickListener(view -> setAllSectionsExpanded(labelSections, true));
        collapseAllSections.setOnClickListener(view -> setAllSectionsExpanded(labelSections, false));

        Runnable refreshLabelDependencies = () -> {
            boolean labelActive = labelEnabled.isChecked();
            boolean vlmActive = labelActive && vlmEnabled.isChecked();
            setEnabledWithAlpha(ppEnabled, labelActive);
            setEnabledWithAlpha(vlmEnabled, labelActive);
            setEnabledWithAlpha(cropLabel, labelActive);
            setEnabledWithAlpha(perspectiveRectify, labelActive);
            setEnabledWithAlpha(reduceGlare, labelActive);
            setEnabledWithAlpha(minimumCoverage, labelActive);
            setEnabledWithAlpha(blurThreshold, labelActive);
            setEnabledWithAlpha(processingStages, labelActive);
            setEnabledWithAlpha(spaceUnits, labelActive);
            setEnabledWithAlpha(dictionaryStrongWeight, labelActive);
            boolean fusionActive = LabelConfigUiState.canTuneFusion(
                    labelActive, ppEnabled.isChecked(), vlmEnabled.isChecked());
            setEnabledWithAlpha(fusionWeightLabel, fusionActive);
            setEnabledWithAlpha(fusionWeight, fusionActive);
            setEnabledWithAlpha(maskCodes, vlmActive);
            setEnabledWithAlpha(weakMask, LabelConfigUiState.canUseWeakMask(
                    labelActive, vlmEnabled.isChecked(), maskCodes.isChecked()));
            setEnabledWithAlpha(qwenEnabled, labelActive);
            boolean qwenActive = labelActive && qwenEnabled.isChecked();
            setEnabledWithAlpha(qwenOnlyMissing, LabelConfigUiState.canLimitQwenToMissing(
                    qwenActive, qwenCorrections.isChecked()));
            setEnabledWithAlpha(qwenCorrections, qwenActive);
            setEnabledWithAlpha(qwenCatalog, qwenActive);
            setEnabledWithAlpha(qwenThreads, qwenActive);
            setEnabledWithAlpha(qwenMaxTokens, qwenActive);
            setEnabledWithAlpha(qwenTimeout, qwenActive);
            setEnabledWithAlpha(qwenExtraInstructions, qwenActive);
        };
        labelEnabled.setOnCheckedChangeListener((button, checked) -> refreshLabelDependencies.run());
        ppEnabled.setOnCheckedChangeListener((button, checked) -> refreshLabelDependencies.run());
        vlmEnabled.setOnCheckedChangeListener((button, checked) -> refreshLabelDependencies.run());
        maskCodes.setOnCheckedChangeListener((button, checked) -> {
            if (!checked && weakMask.isChecked()) weakMask.setChecked(false);
            refreshLabelDependencies.run();
        });
        qwenEnabled.setOnCheckedChangeListener(
                (button, checked) -> refreshLabelDependencies.run());
        qwenCorrections.setOnCheckedChangeListener((button, checked) -> {
            if (checked && qwenOnlyMissing.isChecked()) {
                qwenOnlyMissing.setChecked(false);
                toast(activity, "已关闭“仅缺失时调用”，Qwen 将检查完整记录中的明显错误");
            }
            refreshLabelDependencies.run();
        });
        refreshLabelDependencies.run();

        restoreRecommended.setOnClickListener(view -> {
            RecognitionSettings defaults = RecognitionSettings.defaults();
            VlmSettings vlmDefaults = VlmSettings.defaults();
            QwenSettings qwenDefaults = QwenSettings.defaults();
            labelEnabled.setChecked(defaults.labelEnabled);
            ppEnabled.setChecked(defaults.ppOcrEnabled);
            vlmEnabled.setChecked(defaults.vlmEnabled);
            cropLabel.setChecked(defaults.cropToLabel);
            perspectiveRectify.setChecked(defaults.perspectiveRectify);
            reduceGlare.setChecked(defaults.reduceGlare);
            minimumCoverage.setText(Integer.toString(defaults.minimumLabelCoveragePercent));
            blurThreshold.setText(Integer.toString(defaults.blurVarianceThreshold));
            processingStages.setChecked(defaults.showProcessingPreview);
            spaceUnits.setChecked(defaults.spaceRecognizedUnits);
            dictionaryStrongWeight.setChecked(defaults.dictionaryStrongWeight);
            fusionWeight.setProgress(defaults.fusionWeightLevel - 1);
            maskCodes.setChecked(defaults.maskCodesBeforeVlm);
            weakMask.setChecked(defaults.experimentalWeakCodeMask);
            backend.setSelection(VlmSettings.indexOf(vlmDefaults.backend, VlmSettings.BACKEND_VALUES));
            threads.setText(Integer.toString(vlmDefaults.threadCount));
            precision.setSelection(VlmSettings.indexOf(
                    vlmDefaults.precision, VlmSettings.PRECISION_VALUES));
            memory.setSelection(VlmSettings.indexOf(vlmDefaults.memory, VlmSettings.MEMORY_VALUES));
            maxTokens.setText(Integer.toString(vlmDefaults.maxNewTokens));
            timeout.setText(Integer.toString(vlmDefaults.timeoutSeconds));
            repetitionPenalty.setText(Float.toString(vlmDefaults.repetitionPenalty));
            presencePenalty.setText(Float.toString(vlmDefaults.presencePenalty));
            frequencyPenalty.setText(Float.toString(vlmDefaults.frequencyPenalty));
            prompt.setText(vlmDefaults.prompt);
            qwenEnabled.setChecked(qwenDefaults.enabled);
            qwenOnlyMissing.setChecked(qwenDefaults.onlyWhenCoreFieldsMissing);
            qwenCorrections.setChecked(qwenDefaults.allowEvidenceBackedCorrections);
            qwenCatalog.setChecked(qwenDefaults.requireCatalogMatch);
            qwenThreads.setText(Integer.toString(qwenDefaults.threadCount));
            qwenMaxTokens.setText(Integer.toString(qwenDefaults.maxNewTokens));
            qwenTimeout.setText(Integer.toString(qwenDefaults.timeoutSeconds));
            qwenExtraInstructions.setText(qwenDefaults.extraInstructions);
            refreshLabelDependencies.run();
            toast(activity, "已恢复标签识别推荐值；点击暂存配置或保存并重启后生效");
        });

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
                        String qwenExtraValue = qwenExtraInstructions.getText().toString().trim();
                        if (labelEnabled.isChecked() && !ppEnabled.isChecked()
                                && !vlmEnabled.isChecked()) {
                            throw new IllegalArgumentException("标签识别至少启用 PP-OCR 或 GLM-OCR");
                        }
                        if (vlmEnabled.isChecked() && promptValue.isEmpty()) {
                            throw new IllegalArgumentException("信息提取提示词不能为空");
                        }
                        if (qwenExtraValue.length() > QwenSettings.MAX_EXTRA_INSTRUCTIONS_CHARS) {
                            throw new IllegalArgumentException("Qwen 额外规则不能超过 "
                                    + QwenSettings.MAX_EXTRA_INSTRUCTIONS_CHARS + " 字");
                        }
                        if (qwenEnabled.isChecked() && !qwenExtraValue.isEmpty()) {
                            String forbidden = QwenTextOnlyEngine.forbiddenPromptReason(
                                    "额外文本规则：\n" + qwenExtraValue);
                            if (forbidden != null) {
                                throw new IllegalArgumentException("Qwen 额外规则不符合 text-only："
                                        + forbidden);
                            }
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
                        QwenSettings savedQwen = new QwenSettings(
                                qwenEnabled.isChecked(), qwenOnlyMissing.isChecked(),
                                qwenCorrections.isChecked(), qwenCatalog.isChecked(),
                                integerInRange(qwenThreads, QwenSettings.MIN_THREADS,
                                        QwenSettings.MAX_THREADS, "Qwen CPU 线程数"),
                                integerInRange(qwenMaxTokens, QwenSettings.MIN_NEW_TOKENS,
                                        QwenSettings.MAX_NEW_TOKENS, "Qwen 最大输出 Token"),
                                integerInRange(qwenTimeout, QwenSettings.MIN_TIMEOUT_SECONDS,
                                        QwenSettings.MAX_TIMEOUT_SECONDS, "Qwen 超时"),
                                qwenExtraValue);
                        RecognitionSettings savedRecognition = new RecognitionSettings(
                                labelEnabled.isChecked(), ppEnabled.isChecked(),
                                vlmEnabled.isChecked(), cropLabel.isChecked(),
                                perspectiveRectify.isChecked(),
                                reduceGlare.isChecked(), spaceUnits.isChecked(),
                                maskCodes.isChecked(), maskCodes.isChecked() && weakMask.isChecked(),
                                processingStages.isChecked(), dictionaryStrongWeight.isChecked(),
                                fusionWeight.getProgress() + 1,
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
                        QwenSettingsStore.save(activity, savedQwen);
                        RecognitionSettingsStore.save(activity, savedRecognition);
                        listener.onSettingsSaved(savedRecognition);
                        return true;
                    } catch (IllegalArgumentException invalid) {
                        revealEditableSections(labelSections);
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

    private static Button createCompactButton(Activity activity, String label) {
        Button button = new Button(activity);
        button.setText(label);
        button.setAllCaps(false);
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setTextSize(13f);
        button.setBackgroundResource(R.drawable.button_secondary);
        button.setTextColor(ContextCompat.getColor(activity, R.color.eprint_text));
        return button;
    }

    private static CollapsibleSection addCollapsibleSection(
            Activity activity, LinearLayout form, SharedPreferences preferences,
            String key, String title, String summary, boolean defaultExpanded) {
        Button header = new Button(activity);
        header.setAllCaps(false);
        header.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        header.setMinHeight(dp(activity, 52));
        header.setMinWidth(0);
        header.setTextSize(14f);
        header.setTextColor(ContextCompat.getColor(activity, R.color.eprint_text));
        header.setBackgroundResource(R.drawable.button_secondary);
        header.setPadding(dp(activity, 12), dp(activity, 6), dp(activity, 8), dp(activity, 6));
        LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        headerParams.topMargin = dp(activity, 8);
        form.addView(header, headerParams);

        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 12), dp(activity, 2), dp(activity, 4), dp(activity, 6));
        form.addView(body, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        String preferenceKey = SECTION_PREFERENCE_PREFIX + key;
        boolean expanded = preferences.getBoolean(preferenceKey, defaultExpanded);
        CollapsibleSection section = new CollapsibleSection(
                header, body, preferences, preferenceKey, title, summary);
        section.updateExpanded(expanded, false);
        header.setOnClickListener(view -> section.setExpanded(!section.isExpanded()));
        return section;
    }

    private static void setAllSectionsExpanded(CollapsibleSection[] sections, boolean expanded) {
        for (CollapsibleSection section : sections) section.setExpanded(expanded);
    }

    private static void revealEditableSections(CollapsibleSection[] sections) {
        // 校验失败时临时展开全部可编辑分组，避免错误字段藏在折叠区。
        for (CollapsibleSection section : sections) section.updateExpanded(true, false);
    }

    private static void setEnabledWithAlpha(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.setAlpha(enabled ? 1f : 0.46f);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                group.getChildAt(index).setEnabled(enabled);
            }
        }
    }

    private static final class CollapsibleSection {
        final LinearLayout body;
        private final Button header;
        private final SharedPreferences preferences;
        private final String preferenceKey;
        private final String title;
        private final String summary;

        CollapsibleSection(Button header, LinearLayout body, SharedPreferences preferences,
                           String preferenceKey, String title, String summary) {
            this.header = header;
            this.body = body;
            this.preferences = preferences;
            this.preferenceKey = preferenceKey;
            this.title = title;
            this.summary = summary;
        }

        boolean isExpanded() {
            return body.getVisibility() == View.VISIBLE;
        }

        void setExpanded(boolean expanded) {
            updateExpanded(expanded, true);
        }

        void updateExpanded(boolean expanded, boolean persist) {
            body.setVisibility(expanded ? View.VISIBLE : View.GONE);
            header.setText((expanded ? "▼ " : "▶ ") + title + "\n" + summary);
            header.setContentDescription((expanded ? "收起" : "展开") + title + "；" + summary);
            if (persist) preferences.edit().putBoolean(preferenceKey, expanded).apply();
        }
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
