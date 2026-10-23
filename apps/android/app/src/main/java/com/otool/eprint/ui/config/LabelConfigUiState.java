package com.otool.eprint.ui.config;

/** 标签配置页面的纯状态规则；与 Android View 解耦，便于回归折叠与控件联动。 */
final class LabelConfigUiState {
    static final String SECTION_WORKFLOW = "workflow";
    static final String SECTION_PREPROCESSING = "preprocessing";
    static final String SECTION_RESULT = "result";
    static final String SECTION_GLM_INPUT = "glm_input";
    static final String SECTION_GLM_RUNTIME = "glm_runtime";
    static final String SECTION_QWEN = "qwen";

    private LabelConfigUiState() {}

    static boolean canTuneFusion(boolean labelEnabled, boolean ppEnabled, boolean vlmEnabled) {
        return labelEnabled && ppEnabled && vlmEnabled;
    }

    static boolean canUseWeakMask(boolean labelEnabled, boolean vlmEnabled, boolean maskCodes) {
        return labelEnabled && vlmEnabled && maskCodes;
    }

    /** 明显错误修正需要审计完整记录，因此不能同时限制为“仅缺失时调用”。 */
    static boolean canLimitQwenToMissing(boolean qwenActive, boolean correctionsEnabled) {
        return qwenActive && !correctionsEnabled;
    }
}
