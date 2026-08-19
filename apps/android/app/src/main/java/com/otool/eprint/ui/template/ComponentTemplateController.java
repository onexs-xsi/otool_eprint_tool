package com.otool.eprint.ui.template;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.otool.eprint.BuildConfig;
import com.otool.eprint.R;
import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.model.ComponentFieldSelection;
import com.otool.eprint.model.ComponentLabelFactory;
import com.otool.eprint.model.ComponentTemplate;
import com.otool.eprint.model.ComponentTemplateStore;
import com.otool.eprint.model.LabelDocument;
import com.otool.eprint.model.TextElement;
import com.otool.eprint.model.WorkMode;
import com.otool.eprint.render.LabelBitmapRenderer;
import com.otool.eprint.scan.ComponentRecord;
import com.otool.eprint.scan.LabelTextParser;
import com.otool.eprint.scan.ocr.OcrLine;
import com.otool.eprint.ui.editor.ElementEditorController;
import com.otool.eprint.view.LabelCanvasView;

import java.util.ArrayList;
import java.util.List;

/**
 * 器件模板面板控制器：模板选择缩略图、模板字段开关、保留面板与工作模式外观、
 * 器件文档重建。共享状态经 {@link Host} 读写；元素级操作经元素编辑器 controller。
 */
public final class ComponentTemplateController {

    /** 宿主 Activity 提供的共享状态与动作。 */
    public interface Host {
        LabelDocument document();

        void setDocument(LabelDocument document);

        WorkMode workMode();

        PrinterProfile selectedProfile();

        @Nullable
        ComponentRecord componentRecord();

        LabelCanvasView labelCanvas();

        void showSelectedElement(@Nullable TextElement element);

        void refreshDocumentControls();

        void persistAndRedraw();
    }

    private final Activity activity;
    private final Host host;
    private final ElementEditorController editor;

    private LinearLayout componentPanel;
    private TextView componentRecordSummary;
    private TextView modeContextTitle;
    private View normalReservedPanel;
    private View componentReservedPanel;
    private LinearLayout templateSelectorRow;
    private TextView templateDescription;
    private CheckBox templateBoldCheck;
    private CheckBox templateBrandCheck;
    private CheckBox templateLargeFontCheck;
    private CheckBox templateSeparatorCheck;
    private CheckBox templateRemoveBlankCheck;
    private CheckBox templateModelCheck;
    private CheckBox templateCategoryCheck;
    private CheckBox templateSpecCheck;
    private CheckBox templatePackageCheck;
    private CheckBox templateParametersCheck;
    private CheckBox templateSupplierCheck;
    private ComponentTemplate componentTemplate = ComponentTemplate.STANDARD;
    private boolean templateBoldHeadline = true;
    private boolean templateLargeFont;
    private boolean templateSeparators = true;
    private boolean templateRemoveBlankFields;
    private ComponentFieldSelection templateFields = ComponentFieldSelection.defaults();
    private boolean populatingTemplate;

    public ComponentTemplateController(Activity activity, Host host, ElementEditorController editor,
                                       @Nullable TextView componentRecordSummary) {
        this.activity = activity;
        this.host = host;
        this.editor = editor;
        this.componentRecordSummary = componentRecordSummary;
        componentTemplate = ComponentTemplateStore.loadTemplate(activity);
        templateBoldHeadline = ComponentTemplateStore.loadBoldHeadline(activity);
        templateLargeFont = ComponentTemplateStore.loadLargeFont(activity);
        templateSeparators = ComponentTemplateStore.loadSeparators(activity);
        templateRemoveBlankFields = ComponentTemplateStore.loadRemoveBlankFields(activity);
        templateFields = ComponentTemplateStore.loadFields(activity, componentTemplate);
        componentPanel = activity.findViewById(R.id.componentPanel);
        modeContextTitle = activity.findViewById(R.id.modeContextTitle);
        TextView offlineStatus = activity.findViewById(R.id.offlineStatus);
        if (offlineStatus != null) {
            offlineStatus.setText("● v" + BuildConfig.VERSION_NAME + " · 本机离线工作");
        }
    }

    public ComponentTemplate componentTemplate() {
        return componentTemplate;
    }

    public boolean templateBoldHeadline() {
        return templateBoldHeadline;
    }

    public boolean templateLargeFont() {
        return templateLargeFont;
    }

    public boolean templateSeparators() {
        return templateSeparators;
    }

    public boolean templateRemoveBlankFields() {
        return templateRemoveBlankFields;
    }

    public ComponentFieldSelection templateFields() {
        return templateFields;
    }

    /** renderWorkMode 委托：面板/标题/识别日志占位的模式外观。 */
    public void renderWorkModePanels(boolean component, boolean recognitionLogEmpty) {
        if (componentPanel != null) componentPanel.setVisibility(View.VISIBLE);
        if (normalReservedPanel != null) {
            normalReservedPanel.setVisibility(component ? View.GONE : View.VISIBLE);
        }
        if (componentReservedPanel != null) {
            componentReservedPanel.setVisibility(component ? View.VISIBLE : View.GONE);
        }
        if (component) renderTemplatePanel();
        if (modeContextTitle != null) modeContextTitle.setText(component
                ? "器件整理 · 标签工作台" : "普通标签工作台");
        if (componentRecordSummary != null && recognitionLogEmpty) {
            componentRecordSummary.setText("等待识别任务…");
        }
    }

    public void configureReservedPanels() {
        normalReservedPanel = activity.findViewById(R.id.normalReservedPanel);
        componentReservedPanel = activity.findViewById(R.id.componentReservedPanel);
        templateDescription = activity.findViewById(R.id.templateDescription);
        templateBoldCheck = activity.findViewById(R.id.templateBoldCheck);
        templateBrandCheck = activity.findViewById(R.id.templateBrandCheck);
        templateLargeFontCheck = activity.findViewById(R.id.templateLargeFontCheck);
        templateSeparatorCheck = activity.findViewById(R.id.templateSeparatorCheck);
        templateRemoveBlankCheck = activity.findViewById(R.id.templateRemoveBlankCheck);
        templateModelCheck = activity.findViewById(R.id.templateModelCheck);
        templateCategoryCheck = activity.findViewById(R.id.templateCategoryCheck);
        templateSpecCheck = activity.findViewById(R.id.templateSpecCheck);
        templatePackageCheck = activity.findViewById(R.id.templatePackageCheck);
        templateParametersCheck = activity.findViewById(R.id.templateParametersCheck);
        templateSupplierCheck = activity.findViewById(R.id.templateSupplierCheck);
        editor.bindInsert(R.id.insertTextButton, TextElement.TYPE_TEXT);
        editor.bindInsert(R.id.insertBarcodeButton, TextElement.TYPE_BARCODE);
        editor.bindInsert(R.id.insertQrButton, TextElement.TYPE_QRCODE);
        editor.bindInsert(R.id.insertLineButton, TextElement.TYPE_LINE);
        editor.bindInsert(R.id.insertRectButton, TextElement.TYPE_RECT);
        editor.bindInsert(R.id.insertTimeButton, TextElement.TYPE_TIME);
        editor.bindInsert(R.id.insertSerialButton, TextElement.TYPE_SERIAL);
        editor.bindInsert(R.id.insertArcTextButton, TextElement.TYPE_ARCTEXT);
        editor.bindInsert(R.id.insertIconButton, TextElement.TYPE_ICON);
        editor.bindInsert(R.id.insertTableButton, TextElement.TYPE_TABLE);
        View insertImage = activity.findViewById(R.id.insertImageButton);
        if (insertImage != null) insertImage.setOnClickListener(view -> editor.insertImageElement());
        View insertSignature = activity.findViewById(R.id.insertSignatureButton);
        if (insertSignature != null) insertSignature.setOnClickListener(view -> editor.showSignatureDialog());
        View replaceImage = activity.findViewById(R.id.replaceImageButton);
        if (replaceImage != null) replaceImage.setOnClickListener(view -> editor.launchImagePicker());
        View borderStyle = activity.findViewById(R.id.borderCycleButton);
        if (borderStyle != null) {
            borderStyle.setOnClickListener(view -> editor.showBorderStyleDialog());
        }
        View undo = activity.findViewById(R.id.undoButton);
        if (undo != null) undo.setOnClickListener(view -> editor.undo());
        View redo = activity.findViewById(R.id.redoButton);
        if (redo != null) redo.setOnClickListener(view -> editor.redo());
        View clear = activity.findViewById(R.id.clearCanvasButton);
        if (clear != null) clear.setOnClickListener(view -> clearCanvasElements());
        templateSelectorRow = activity.findViewById(R.id.templateSelectorRow);
        if (templateBoldCheck != null) {
            templateBoldCheck.setOnCheckedChangeListener((button, checked) -> {
                if (populatingTemplate) return;
                templateBoldHeadline = checked;
                saveTemplateOptions();
                renderTemplatePanel();
                regenerateComponentDocument();
            });
        }
        bindTemplateFieldChecks();
        if (templateLargeFontCheck != null) {
            templateLargeFontCheck.setOnCheckedChangeListener((button, checked) -> {
                if (populatingTemplate) return;
                templateLargeFont = checked;
                saveTemplateOptions();
                renderTemplatePanel();
                regenerateComponentDocument();
            });
        }
        if (templateSeparatorCheck != null) {
            templateSeparatorCheck.setOnCheckedChangeListener((button, checked) -> {
                if (populatingTemplate) return;
                templateSeparators = checked;
                saveTemplateOptions();
                renderTemplatePanel();
                regenerateComponentDocument();
            });
        }
        if (templateRemoveBlankCheck != null) {
            templateRemoveBlankCheck.setOnCheckedChangeListener((button, checked) -> {
                if (populatingTemplate) return;
                templateRemoveBlankFields = checked;
                saveTemplateOptions();
                renderTemplatePanel();
                regenerateComponentDocument();
            });
        }
    }

    private void bindTemplateFieldChecks() {
        CheckBox[] checks = {templateModelCheck, templateCategoryCheck, templateSpecCheck,
                templatePackageCheck, templateBrandCheck, templateParametersCheck,
                templateSupplierCheck};
        for (CheckBox check : checks) {
            if (check == null) continue;
            check.setOnCheckedChangeListener((button, checked) -> {
                if (populatingTemplate) return;
                templateFields = new ComponentFieldSelection(
                        checked(templateModelCheck, templateFields.model),
                        checked(templateCategoryCheck, templateFields.category),
                        checked(templateSpecCheck, templateFields.specification),
                        checked(templatePackageCheck, templateFields.packageName),
                        checked(templateBrandCheck, templateFields.brand),
                        checked(templateParametersCheck, templateFields.parameters),
                        checked(templateSupplierCheck, templateFields.supplierPartNumber));
                saveTemplateOptions();
                renderTemplatePanel();
                regenerateComponentDocument();
            });
        }
    }

    private void selectTemplate(ComponentTemplate template) {
        if (componentTemplate == template) return;
        // 先固化旧模板，再加载新模板自己的字段组合；切模板不携带、也不重置字段。
        saveTemplateOptions();
        componentTemplate = template;
        templateFields = ComponentTemplateStore.loadFields(activity, template);
        saveTemplateOptions();
        renderTemplatePanel();
        regenerateComponentDocument();
    }

    private void saveTemplateOptions() {
        ComponentTemplateStore.save(activity, componentTemplate, templateBoldHeadline,
                templateLargeFont, templateFields, templateSeparators,
                templateRemoveBlankFields);
    }

    public void renderTemplatePanel() {
        if (templateDescription == null) return;
        populatingTemplate = true;
        templateDescription.setText(componentTemplate.label + " · " + componentTemplate.description);
        if (templateBoldCheck != null) templateBoldCheck.setChecked(templateBoldHeadline);
        if (templateBrandCheck != null) templateBrandCheck.setChecked(templateFields.brand);
        if (templateLargeFontCheck != null) templateLargeFontCheck.setChecked(templateLargeFont);
        if (templateSeparatorCheck != null) templateSeparatorCheck.setChecked(templateSeparators);
        if (templateRemoveBlankCheck != null) {
            templateRemoveBlankCheck.setChecked(templateRemoveBlankFields);
        }
        if (templateModelCheck != null) templateModelCheck.setChecked(templateFields.model);
        if (templateCategoryCheck != null) templateCategoryCheck.setChecked(templateFields.category);
        if (templateSpecCheck != null) templateSpecCheck.setChecked(templateFields.specification);
        if (templatePackageCheck != null) templatePackageCheck.setChecked(templateFields.packageName);
        if (templateParametersCheck != null) templateParametersCheck.setChecked(templateFields.parameters);
        if (templateSupplierCheck != null) templateSupplierCheck.setChecked(templateFields.supplierPartNumber);
        populatingTemplate = false;
        rebuildTemplateSelector();
    }

    private void rebuildTemplateSelector() {
        if (templateSelectorRow == null) return;
        templateSelectorRow.removeAllViews();
        ComponentRecord preview = host.componentRecord() != null
                ? host.componentRecord() : sampleComponentRecord();
        for (ComponentTemplate template : ComponentTemplate.values()) {
            templateSelectorRow.addView(buildTemplateTile(template, preview));
        }
    }

    /** 用真实扫描数据（未扫码时用立创样本）把每个模板渲染成可点选的缩略图。 */
    private View buildTemplateTile(ComponentTemplate template, ComponentRecord record) {
        boolean selected = componentTemplate == template;
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        GradientDrawable border = new GradientDrawable();
        border.setColor(0xFFFFFFFF);
        border.setCornerRadius(dp(6));
        border.setStroke(dp(selected ? 2 : 1), ContextCompat.getColor(activity,
                selected ? R.color.eprint_coral : R.color.eprint_line));
        tile.setBackground(border);
        tile.setPadding(dp(4), dp(4), dp(4), dp(2));

        ComponentFieldSelection previewFields = selected
                ? templateFields : ComponentTemplateStore.loadFields(activity, template);
        LabelDocument previewDocument = ComponentLabelFactory.create(record, 203, 25f, 15f,
                template, templateBoldHeadline, templateLargeFont, previewFields,
                templateSeparators, templateRemoveBlankFields);
        ImageView image = new ImageView(activity);
        image.setImageBitmap(LabelBitmapRenderer.render(previewDocument));
        tile.addView(image, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView caption = new TextView(activity);
        caption.setText(template.label);
        caption.setTextSize(10f);
        caption.setGravity(android.view.Gravity.CENTER);
        caption.setTextColor(ContextCompat.getColor(activity,
                selected ? R.color.eprint_coral_dark : R.color.eprint_muted));
        tile.addView(caption, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        tile.setContentDescription("模板" + template.label + (selected ? "，已选择" : ""));
        tile.setOnClickListener(view -> selectTemplate(template));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1f);
        params.setMarginEnd(dp(6));
        tile.setLayoutParams(params);
        return tile;
    }

    private static ComponentRecord sampleComponentRecord() {
        List<OcrLine> sample = new ArrayList<>();
        sample.add(new OcrLine("C45359894", 1f, 0, 0, 0, 0));
        sample.add(new OcrLine("贴片电容(MLCC)", 1f, 0, 0, 0, 0));
        sample.add(new OcrLine("12pF ±5% 50V", 1f, 0, 0, 0, 0));
        sample.add(new OcrLine("0402", 1f, 0, 0, 0, 0));
        sample.add(new OcrLine("CGA0402C0G120J500GT", 1f, 0, 0, 0, 0));
        sample.add(new OcrLine("HRE(芯声)", 1f, 0, 0, 0, 0));
        return LabelTextParser.parse(sample);
    }

    public void regenerateComponentDocument() {
        if (host.workMode() != WorkMode.COMPONENT_ORGANIZATION || host.componentRecord() == null) return;
        LabelDocument created = ComponentLabelFactory.create(host.componentRecord(),
                host.selectedProfile().dpi,
                host.document().widthMm, host.document().heightMm, componentTemplate,
                templateBoldHeadline, templateLargeFont, templateFields, templateSeparators,
                templateRemoveBlankFields);
        host.setDocument(created);
        host.labelCanvas().setDocument(created);
        // 不默认选中首元素：选择框与编辑面板等用户点按后再出现。
        host.showSelectedElement(null);
        host.refreshDocumentControls();
        host.persistAndRedraw();
    }

    private void clearCanvasElements() {
        if (host.document().elements.isEmpty()) {
            toast("画布上没有元素");
            return;
        }
        host.document().elements.clear();
        host.labelCanvas().select(null);
        host.showSelectedElement(null);
        host.persistAndRedraw();
        toast("已清空画布元素");
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static boolean checked(@Nullable CheckBox checkBox, boolean fallback) {
        return checkBox == null ? fallback : checkBox.isChecked();
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
