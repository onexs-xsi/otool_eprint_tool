package com.otool.eprint.ui.editor;

import android.app.Activity;
import android.app.Dialog;
import android.text.Editable;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.otool.eprint.R;
import com.otool.eprint.device.DeviceCatalog;
import com.otool.eprint.device.PrinterProfile;
import com.otool.eprint.model.DocumentStore;
import com.otool.eprint.model.LabelDocument;
import com.otool.eprint.model.TextElement;
import com.otool.eprint.model.TimeElementFormat;
import com.otool.eprint.model.WorkMode;
import com.otool.eprint.render.BarcodeRasterizer;
import com.otool.eprint.render.IconCatalog;
import com.otool.eprint.render.ImageElementRenderer;
import com.otool.eprint.scan.SupplierFormat;
import com.otool.eprint.ui.SimpleTextWatcher;
import com.otool.eprint.view.LabelCanvasView;
import com.otool.eprint.view.SignatureView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * 元素编辑器控制器：属性面板绑定与回填、元素操作（微调/居中/复制/删除/插入）、
 * 图片与签名导入、边框样式、撤销/重做、元素 chips，以及跨切点 persistAndRedraw。
 * 文档、画布、工作模式、打印机档案等共享状态经 {@link Host} 读写。
 */
public final class ElementEditorController {

    /** 宿主 Activity 提供的共享状态与动作。 */
    public interface Host {
        LabelDocument document();

        void setDocument(LabelDocument document);

        WorkMode workMode();

        PrinterProfile selectedProfile();

        SupplierFormat supplierFormat();

        LabelCanvasView labelCanvas();

        void updateCompatibilityWarning();

        void refreshDocumentControls();

        void switchWorkMode(WorkMode selected);

        void onSupplierFormatSelected(int position);

        /** 以 populatingProfile 保护地把当前档案同步到档案 spinner。 */
        void selectCurrentProfileInSpinner();

        void onProfileSpinnerSelected(int position);

        void configureLabelSizeSpinner();

        void requestScan();

        void requestPrint();

        void requestChoosePrinter();

        void showDiagnostics();

        void showProfileSettings();

        void showModelConfigDialog();

        void showCustomLabelSizeDialog();

        void applyCanvasSize();

        void applyPreset(float widthMm, float heightMm);

        void showPreview();

        void disconnectPrinter();

        void cancelJob();

        void retryJob();

        void launchImagePicker();
    }

    private static final float NUDGE_STEP_MM = 0.5f;

    private final Activity activity;
    private final Host host;

    private LinearLayout textEditorCard;
    private EditText elementXInput;
    private EditText elementYInput;
    private EditText elementWidthInput;
    private EditText elementHeightInput;
    private EditText textInput;
    private EditText fontSizeInput;
    private CheckBox boldCheck;
    private TextView dpiSpinner;
    private Spinner profileSpinner;
    private Spinner alignmentSpinner;
    private Spinner workModeSpinner;
    private Spinner supplierSpinner;
    private TextView editorTitle;
    private View rowFont;
    private View rowBarcode;
    private View rowQr;
    private View rowCodeLayout;
    private View rowLine;
    private View rowRect;
    private View rowTime;
    private View rowArc;
    private Spinner barcodeFormatSpinner;
    private Spinner barcodeTextPositionSpinner;
    private Spinner qrFormatSpinner;
    private Spinner qrErrorCorrectionSpinner;
    private Spinner codeQuietZoneSpinner;
    private Spinner codeRotationSpinner;
    private Spinner lineOrientationSpinner;
    private Spinner arcDirectionSpinner;
    private Spinner timeFormatSpinner;
    private EditText lineWidthInput;
    private EditText rectWidthInput;
    private CheckBox rectFilledCheck;
    private CheckBox lockCheck;
    private View rowSerial;
    private EditText serialValueInput;
    private EditText serialStepInput;
    private EditText serialDigitsInput;
    private View undoButton;
    private View redoButton;
    private Button borderStyleButton;
    private View rowImage;
    private EditText imageWidthInput;
    private EditText imageHeightInput;
    private Spinner imageFitSpinner;
    private View rowIcon;
    private Spinner iconStyleSpinner;
    private EditText iconLineWidthInput;
    private View rowTable;
    private EditText tableRowsInput;
    private EditText tableColsInput;
    private EditText tableLineWidthInput;
    private LinearLayout elementChipsRow;
    private HorizontalScrollView elementChipsScroll;
    private Button addTextButton;
    private Button customLabelSizeButton;
    private Button printButton;
    private final java.util.ArrayDeque<String> undoStack = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<String> redoStack = new java.util.ArrayDeque<>();
    private String lastSnapshot;
    private long lastSnapshotAt;
    private boolean restoringSnapshot;
    private boolean populatingEditor;
    private Dialog elementEditorDialog;

    public ElementEditorController(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
        textEditorCard = activity.findViewById(R.id.textEditorCard);
        elementXInput = activity.findViewById(R.id.elementXInput);
        elementYInput = activity.findViewById(R.id.elementYInput);
        elementWidthInput = activity.findViewById(R.id.elementWidthInput);
        elementHeightInput = activity.findViewById(R.id.elementHeightInput);
        textInput = activity.findViewById(R.id.textInput);
        fontSizeInput = activity.findViewById(R.id.fontSizeInput);
        boldCheck = activity.findViewById(R.id.boldCheck);
        dpiSpinner = activity.findViewById(R.id.dpiSpinner);
        profileSpinner = activity.findViewById(R.id.profileSpinner);
        alignmentSpinner = activity.findViewById(R.id.alignmentSpinner);
        workModeSpinner = activity.findViewById(R.id.workModeSpinner);
        supplierSpinner = activity.findViewById(R.id.supplierSpinner);
        editorTitle = activity.findViewById(R.id.editorTitle);
        rowFont = activity.findViewById(R.id.rowFont);
        rowBarcode = activity.findViewById(R.id.rowBarcode);
        rowQr = activity.findViewById(R.id.rowQr);
        rowCodeLayout = activity.findViewById(R.id.rowCodeLayout);
        rowLine = activity.findViewById(R.id.rowLine);
        rowRect = activity.findViewById(R.id.rowRect);
        rowTime = activity.findViewById(R.id.rowTime);
        rowArc = activity.findViewById(R.id.rowArc);
        barcodeFormatSpinner = activity.findViewById(R.id.barcodeFormatSpinner);
        barcodeTextPositionSpinner = activity.findViewById(R.id.barcodeTextPositionSpinner);
        qrFormatSpinner = activity.findViewById(R.id.qrFormatSpinner);
        qrErrorCorrectionSpinner = activity.findViewById(R.id.qrErrorCorrectionSpinner);
        codeQuietZoneSpinner = activity.findViewById(R.id.codeQuietZoneSpinner);
        codeRotationSpinner = activity.findViewById(R.id.codeRotationSpinner);
        lineOrientationSpinner = activity.findViewById(R.id.lineOrientationSpinner);
        arcDirectionSpinner = activity.findViewById(R.id.arcDirectionSpinner);
        timeFormatSpinner = activity.findViewById(R.id.timeFormatSpinner);
        lineWidthInput = activity.findViewById(R.id.lineWidthInput);
        rectWidthInput = activity.findViewById(R.id.rectWidthInput);
        rectFilledCheck = activity.findViewById(R.id.rectFilledCheck);
        lockCheck = activity.findViewById(R.id.lockCheck);
        rowSerial = activity.findViewById(R.id.rowSerial);
        serialValueInput = activity.findViewById(R.id.serialValueInput);
        serialStepInput = activity.findViewById(R.id.serialStepInput);
        serialDigitsInput = activity.findViewById(R.id.serialDigitsInput);
        undoButton = activity.findViewById(R.id.undoButton);
        redoButton = activity.findViewById(R.id.redoButton);
        borderStyleButton = activity.findViewById(R.id.borderCycleButton);
        rowImage = activity.findViewById(R.id.rowImage);
        imageWidthInput = activity.findViewById(R.id.imageWidthInput);
        imageHeightInput = activity.findViewById(R.id.imageHeightInput);
        imageFitSpinner = activity.findViewById(R.id.imageFitSpinner);
        rowIcon = activity.findViewById(R.id.rowIcon);
        iconStyleSpinner = activity.findViewById(R.id.iconStyleSpinner);
        iconLineWidthInput = activity.findViewById(R.id.iconLineWidthInput);
        rowTable = activity.findViewById(R.id.rowTable);
        tableRowsInput = activity.findViewById(R.id.tableRowsInput);
        tableColsInput = activity.findViewById(R.id.tableColsInput);
        tableLineWidthInput = activity.findViewById(R.id.tableLineWidthInput);
        elementChipsRow = activity.findViewById(R.id.elementChipsRow);
        elementChipsScroll = activity.findViewById(R.id.elementChipsScroll);
        addTextButton = activity.findViewById(R.id.addTextButton);
        customLabelSizeButton = activity.findViewById(R.id.customLabelSizeButton);
        printButton = activity.findViewById(R.id.printButton);
    }

    public void configureSpinners() {
        if (workModeSpinner != null) {
            ArrayAdapter<WorkMode> modeAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item, WorkMode.values());
            modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            workModeSpinner.setAdapter(modeAdapter);
            workModeSpinner.setSelection(host.workMode().ordinal());
            workModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                     int position, long id) {
                    WorkMode selected = WorkMode.values()[position];
                    if (selected != host.workMode()) host.switchWorkMode(selected);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (supplierSpinner != null) {
            ArrayAdapter<SupplierFormat> supplierAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item, SupplierFormat.values());
            supplierAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            supplierSpinner.setAdapter(supplierAdapter);
            supplierSpinner.setSelection(host.supplierFormat().ordinal());
            supplierSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View view,
                                                     int position, long id) {
                    host.onSupplierFormatSelected(position);
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (dpiSpinner != null) dpiSpinner.setText(host.selectedProfile().dpi + " DPI");

        ArrayAdapter<String> alignmentAdapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, new String[]{"左对齐", "居中", "右对齐"});
        alignmentAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        alignmentSpinner.setAdapter(alignmentAdapter);

        if (barcodeFormatSpinner != null) {
            ArrayAdapter<String> barcodeAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item,
                    BarcodeRasterizer.BARCODE_FORMAT_LABELS);
            barcodeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            barcodeFormatSpinner.setAdapter(barcodeAdapter);
        }
        if (barcodeTextPositionSpinner != null) {
            ArrayAdapter<String> textPositionAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item,
                    new String[]{"不显示", "上方显示", "下方显示"});
            textPositionAdapter.setDropDownViewResource(
                    android.R.layout.simple_spinner_dropdown_item);
            barcodeTextPositionSpinner.setAdapter(textPositionAdapter);
        }
        if (qrFormatSpinner != null) {
            ArrayAdapter<String> qrFormatAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item,
                    BarcodeRasterizer.TWO_DIMENSIONAL_FORMAT_LABELS);
            qrFormatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            qrFormatSpinner.setAdapter(qrFormatAdapter);
        }
        if (lineOrientationSpinner != null) {
            ArrayAdapter<String> orientationAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item, new String[]{"横向", "纵向"});
            orientationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            lineOrientationSpinner.setAdapter(orientationAdapter);
        }
        if (timeFormatSpinner != null) {
            ArrayAdapter<TimeElementFormat> timeAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item, TimeElementFormat.values());
            timeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            timeFormatSpinner.setAdapter(timeAdapter);
        }
        if (qrErrorCorrectionSpinner != null) {
            ArrayAdapter<String> qrCorrectionAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item,
                    new String[]{"L · 低", "M · 中", "Q · 高", "H · 最高"});
            qrCorrectionAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            qrErrorCorrectionSpinner.setAdapter(qrCorrectionAdapter);
        }
        if (codeQuietZoneSpinner != null) {
            ArrayAdapter<String> quietZoneAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item,
                    new String[]{"0 码元", "2 码元", "4 码元"});
            quietZoneAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            codeQuietZoneSpinner.setAdapter(quietZoneAdapter);
        }
        if (codeRotationSpinner != null) {
            ArrayAdapter<String> rotationAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item,
                    new String[]{"0°", "90°", "180°", "270°"});
            rotationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            codeRotationSpinner.setAdapter(rotationAdapter);
        }
        if (arcDirectionSpinner != null) {
            ArrayAdapter<String> arcAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item, new String[]{"上弧 ∩", "下弧 ∪"});
            arcAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            arcDirectionSpinner.setAdapter(arcAdapter);
        }
        if (imageFitSpinner != null) {
            ArrayAdapter<String> fitAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item, ImageElementRenderer.FIT_MODE_LABELS);
            fitAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            imageFitSpinner.setAdapter(fitAdapter);
        }
        if (iconStyleSpinner != null) {
            ArrayAdapter<String> iconAdapter = new ArrayAdapter<>(activity,
                    android.R.layout.simple_spinner_item, IconCatalog.LABELS);
            iconAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            iconStyleSpinner.setAdapter(iconAdapter);
        }

        ArrayAdapter<PrinterProfile> profileAdapter = new ArrayAdapter<>(activity,
                R.layout.spinner_item_compact, DeviceCatalog.profiles());
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(profileAdapter);
        host.selectCurrentProfileInSpinner();
        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                host.onProfileSpinnerSelected(position);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        host.configureLabelSizeSpinner();
    }

    public void configureActions() {
        activity.findViewById(R.id.scanButton).setOnClickListener(view -> host.requestScan());
        if (addTextButton != null) {
            addTextButton.setOnClickListener(view -> showElementEditorDialog());
        }
        if (customLabelSizeButton != null) {
            customLabelSizeButton.setOnClickListener(view -> host.showCustomLabelSizeDialog());
        }
        activity.findViewById(R.id.deleteTextButton).setOnClickListener(view -> deleteSelected());
        View applySize = activity.findViewById(R.id.applySizeButton);
        if (applySize != null) applySize.setOnClickListener(view -> host.applyCanvasSize());
        View presetSmall = activity.findViewById(R.id.presetSmallButton);
        if (presetSmall != null) presetSmall.setOnClickListener(view -> host.applyPreset(20f, 10f));
        View presetMedium = activity.findViewById(R.id.presetMediumButton);
        if (presetMedium != null) presetMedium.setOnClickListener(view -> host.applyPreset(25f, 15f));
        View presetLarge = activity.findViewById(R.id.presetLargeButton);
        if (presetLarge != null) presetLarge.setOnClickListener(view -> host.applyPreset(40f, 30f));
        activity.findViewById(R.id.previewButton).setOnClickListener(view -> host.showPreview());
        printButton.setOnClickListener(view -> host.requestPrint());
        activity.findViewById(R.id.connectButton).setOnClickListener(view -> host.requestChoosePrinter());
        activity.findViewById(R.id.diagnosticsButton).setOnClickListener(view -> host.showDiagnostics());
        activity.findViewById(R.id.profileSettingsButton).setOnClickListener(view -> host.showProfileSettings());
        View modelConfig = activity.findViewById(R.id.modelConfigButton);
        if (modelConfig != null) modelConfig.setOnClickListener(view -> host.showModelConfigDialog());
        activity.findViewById(R.id.disconnectButton).setOnClickListener(view -> host.disconnectPrinter());
        activity.findViewById(R.id.cancelJobButton).setOnClickListener(view -> host.cancelJob());
        activity.findViewById(R.id.retryJobButton).setOnClickListener(view -> host.retryJob());
        View fontDecrease = activity.findViewById(R.id.fontDecreaseButton);
        if (fontDecrease != null) fontDecrease.setOnClickListener(view -> stepFontSize(-1f));
        View fontIncrease = activity.findViewById(R.id.fontIncreaseButton);
        if (fontIncrease != null) fontIncrease.setOnClickListener(view -> stepFontSize(1f));
        bindNudge(R.id.nudgeLeftButton, -NUDGE_STEP_MM, 0f);
        bindNudge(R.id.nudgeRightButton, NUDGE_STEP_MM, 0f);
        bindNudge(R.id.nudgeUpButton, 0f, -NUDGE_STEP_MM);
        bindNudge(R.id.nudgeDownButton, 0f, NUDGE_STEP_MM);
        View centerHorizontal = activity.findViewById(R.id.centerHorizontalButton);
        if (centerHorizontal != null) {
            centerHorizontal.setOnClickListener(view -> centerSelectedHorizontally());
        }
        View centerVertical = activity.findViewById(R.id.centerVerticalButton);
        if (centerVertical != null) {
            centerVertical.setOnClickListener(view -> centerSelectedVertically());
        }
        View duplicate = activity.findViewById(R.id.duplicateTextButton);
        if (duplicate != null) duplicate.setOnClickListener(view -> duplicateSelected());
    }

    /** 把现有完整属性面板移入近全屏对话框，并增加通用坐标/尺寸编辑。 */
    private void showElementEditorDialog() {
        TextElement selected = host.labelCanvas().getSelectedElement();
        if (selected == null) {
            toast("请先选择要编辑的画布元素");
            return;
        }
        if (elementEditorDialog != null && elementEditorDialog.isShowing()) return;
        if (!(textEditorCard.getParent() instanceof ViewGroup)) return;

        showSelectedElement(selected);
        ViewGroup originalParent = (ViewGroup) textEditorCard.getParent();
        int originalIndex = originalParent.indexOfChild(textEditorCard);
        ViewGroup.LayoutParams originalParams = textEditorCard.getLayoutParams();

        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(12), dp(16), dp(12));
        root.setBackgroundColor(0xFFF9F7F0);

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText("编辑元素 · " + titleFor(selected));
        title.setTextColor(ContextCompat.getColor(activity, R.color.eprint_text));
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        header.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button done = new Button(activity);
        done.setText("完成");
        done.setAllCaps(false);
        done.setBackgroundResource(R.drawable.button_primary);
        done.setTextColor(ContextCompat.getColor(activity, R.color.eprint_paper));
        header.addView(done, new LinearLayout.LayoutParams(dp(100), dp(44)));
        root.addView(header, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView geometryHint = new TextView(activity);
        geometryHint.setText(elementXInput == null
                ? "位置与尺寸（mm）· 可输入负坐标，元素可超出标签边界"
                : "所有属性即时生效；X/Y/宽/高使用 mm，可输入负坐标并允许越界");
        geometryHint.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
        geometryHint.setTextSize(13);
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hintParams.topMargin = dp(6);
        root.addView(geometryHint, hintParams);

        if (elementXInput == null) {
            LinearLayout geometryRow = new LinearLayout(activity);
            geometryRow.setGravity(android.view.Gravity.BOTTOM);
            EditText xInput = geometryInput(selected.xMm);
            EditText yInput = geometryInput(selected.yMm);
            EditText widthInput = geometryInput(selected.widthMm);
            EditText heightInput = geometryInput(selected.heightMm);
            geometryRow.addView(geometryField("X", xInput), weightedGeometryParams());
            geometryRow.addView(geometryField("Y", yInput), weightedGeometryParams());
            geometryRow.addView(geometryField("宽", widthInput), weightedGeometryParams());
            geometryRow.addView(geometryField("高", heightInput), weightedGeometryParams());
            Button applyGeometry = new Button(activity);
            applyGeometry.setText("应用位置/尺寸");
            applyGeometry.setAllCaps(false);
            applyGeometry.setBackgroundResource(R.drawable.button_secondary);
            LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(dp(150), dp(48));
            applyParams.setMarginStart(dp(8));
            geometryRow.addView(applyGeometry, applyParams);
            applyGeometry.setOnClickListener(view -> applyEditorGeometry(
                    xInput, yInput, widthInput, heightInput));
            root.addView(geometryRow, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }

        FrameLayout editorHost = new FrameLayout(activity);
        LinearLayout.LayoutParams hostParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        hostParams.topMargin = dp(8);
        root.addView(editorHost, hostParams);
        originalParent.removeView(textEditorCard);
        editorHost.addView(textEditorCard, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        textEditorCard.setVisibility(View.VISIBLE);

        Dialog dialog = new Dialog(activity);
        elementEditorDialog = dialog;
        dialog.setContentView(root);
        done.setOnClickListener(view -> dialog.dismiss());
        dialog.setOnDismissListener(ignored -> {
            if (textEditorCard.getParent() instanceof ViewGroup) {
                ((ViewGroup) textEditorCard.getParent()).removeView(textEditorCard);
            }
            int targetIndex = Math.min(originalIndex, originalParent.getChildCount());
            originalParent.addView(textEditorCard, targetIndex, originalParams);
            elementEditorDialog = null;
            showSelectedElement(host.labelCanvas().getSelectedElement());
        });
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            window.setLayout(Math.round(metrics.widthPixels * 0.94f),
                    Math.round(metrics.heightPixels * 0.92f));
        }
    }

    public EditText geometryInput(float value) {
        EditText input = new EditText(activity);
        input.setText(formatNumber(value));
        input.setSelectAllOnFocus(true);
        input.setSingleLine(true);
        input.setGravity(android.view.Gravity.CENTER);
        input.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        return input;
    }

    private LinearLayout geometryField(String label, EditText input) {
        LinearLayout field = new LinearLayout(activity);
        field.setOrientation(LinearLayout.VERTICAL);
        TextView caption = new TextView(activity);
        caption.setText(label + " mm");
        caption.setTextColor(ContextCompat.getColor(activity, R.color.eprint_muted));
        caption.setGravity(android.view.Gravity.CENTER);
        field.addView(caption, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        field.addView(input, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(48)));
        return field;
    }

    private LinearLayout.LayoutParams weightedGeometryParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginEnd(dp(6));
        return params;
    }

    private void applyEditorGeometry(EditText xInput, EditText yInput,
                                     EditText widthInput, EditText heightInput) {
        TextElement selected = host.labelCanvas().getSelectedElement();
        Float x = parseNumber(xInput.getText().toString());
        Float y = parseNumber(yInput.getText().toString());
        Float width = parseNumber(widthInput.getText().toString());
        Float height = parseNumber(heightInput.getText().toString());
        if (selected == null || x == null || y == null || width == null || height == null
                || width <= 0f || height <= 0f) {
            toast("请输入有效的位置和大于 0 的宽高");
            return;
        }
        selected.xMm = x;
        selected.yMm = y;
        selected.widthMm = width;
        selected.heightMm = height;
        persistAndRedraw();
        xInput.setText(formatNumber(selected.xMm));
        yInput.setText(formatNumber(selected.yMm));
        widthInput.setText(formatNumber(selected.widthMm));
        heightInput.setText(formatNumber(selected.heightMm));
        toast("位置与尺寸已应用");
    }

    private void bindNudge(int buttonId, float dxMm, float dyMm) {
        View button = activity.findViewById(buttonId);
        if (button != null) button.setOnClickListener(view -> nudgeSelected(dxMm, dyMm));
    }

    private void nudgeSelected(float dxMm, float dyMm) {
        TextElement selected = host.labelCanvas().getSelectedElement();
        if (selected == null) return;
        if (selected.locked) {
            toast("元素已锁定，先解锁再调整");
            return;
        }
        selected.xMm += dxMm;
        selected.yMm += dyMm;
        persistAndRedraw();
    }

    private void centerSelectedHorizontally() {
        TextElement selected = host.labelCanvas().getSelectedElement();
        if (selected == null) return;
        if (selected.locked) {
            toast("元素已锁定，先解锁再调整");
            return;
        }
        selected.xMm = (host.document().widthMm - selected.widthMm) / 2f;
        persistAndRedraw();
    }

    private void centerSelectedVertically() {
        TextElement selected = host.labelCanvas().getSelectedElement();
        if (selected == null) return;
        if (selected.locked) {
            toast("元素已锁定，先解锁再调整");
            return;
        }
        selected.yMm = (host.document().heightMm - selected.heightMm) / 2f;
        persistAndRedraw();
    }

    private void stepFontSize(float deltaPt) {
        TextElement selected = host.labelCanvas().getSelectedElement();
        if (selected == null || !selected.isTextual()) return;
        selected.fontSizePt = LabelDocument.clamp(selected.fontSizePt + deltaPt, 6f, 72f);
        populatingEditor = true;
        fontSizeInput.setText(formatNumber(selected.fontSizePt));
        populatingEditor = false;
        persistAndRedraw();
    }

    private void duplicateSelected() {
        TextElement selected = host.labelCanvas().getSelectedElement();
        if (selected == null) {
            toast("请先选择要复制的元素");
            return;
        }
        TextElement copy = selected.copyWithNewId();
        copy.yMm = selected.yMm + 3f;
        host.document().elements.add(copy);
        host.document().normalize();
        host.labelCanvas().select(copy.id);
        showSelectedElement(copy);
        persistAndRedraw();
    }

    public void deleteSelected() {
        TextElement selected = host.labelCanvas().getSelectedElement();
        if (selected == null) return;
        host.document().remove(selected.id);
        TextElement replacement = host.document().elements.isEmpty()
                ? null : host.document().elements.get(0);
        host.labelCanvas().select(replacement == null ? null : replacement.id);
        showSelectedElement(replacement);
        persistAndRedraw();
    }

    public void bindInsert(int buttonId, String type) {
        View button = activity.findViewById(buttonId);
        if (button != null) button.setOnClickListener(view -> insertElement(type));
    }

    private void insertElement(String type) {
        TextElement element = host.document().addElement(type);
        host.labelCanvas().select(element.id);
        showSelectedElement(element);
        persistAndRedraw();
    }

    public void insertImageElement() {
        insertElement(TextElement.TYPE_IMAGE);
        launchImagePicker();
    }

    public void launchImagePicker() {
        TextElement selected = host.labelCanvas().getSelectedElement();
        if (selected == null || !TextElement.TYPE_IMAGE.equals(selected.type)) {
            toast("请先选中一个图片元素");
            return;
        }
        host.launchImagePicker();
    }

    public void importImageToSelected(android.net.Uri uri) {
        TextElement selected = host.labelCanvas().getSelectedElement();
        if (selected == null || !TextElement.TYPE_IMAGE.equals(selected.type)) return;
        String path = copyImageToInternal(uri);
        if (path == null) {
            toast("图片读取失败");
            return;
        }
        selected.text = path;
        // 按图片宽高比修正盒高，避免拉伸
        android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(path, bounds);
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            selected.heightMm = LabelDocument.clamp(
                    selected.widthMm * bounds.outHeight / (float) bounds.outWidth,
                    3f, LabelDocument.MAX_ELEMENT_SIZE_MM);
        }
        ImageElementRenderer.invalidate(path);
        persistAndRedraw();
        toast("图片已加入画布");
    }

    @Nullable
    private String copyImageToInternal(android.net.Uri uri) {
        try {
            android.graphics.BitmapFactory.Options bounds = new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                android.graphics.BitmapFactory.decodeStream(in, null, bounds);
            }
            if (bounds.outWidth <= 0) return null;
            int sample = 1;
            while (bounds.outWidth / sample > 800 || bounds.outHeight / sample > 800) sample *= 2;
            android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
            options.inSampleSize = sample;
            android.graphics.Bitmap bitmap;
            try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                bitmap = android.graphics.BitmapFactory.decodeStream(in, null, options);
            }
            if (bitmap == null) return null;
            File directory = new File(activity.getFilesDir(), "images");
            if (!directory.exists() && !directory.mkdirs()) return null;
            File file = new File(directory, "img_" + System.currentTimeMillis() + ".jpg");
            try (OutputStream out = new FileOutputStream(file)) {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out);
            }
            bitmap.recycle();
            return file.getAbsolutePath();
        } catch (IOException | OutOfMemoryError error) {
            return null;
        }
    }

    public void showSignatureDialog() {
        SignatureView board = new SignatureView(activity);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("手写签名（保存后生成图片元素）")
                .setView(board)
                .setNeutralButton("清除", null)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> board.clear());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                android.graphics.Bitmap bitmap = board.createBitmap();
                if (bitmap == null) {
                    toast("签名为空");
                    return;
                }
                Button saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                saveButton.setEnabled(false);
                File directory = new File(activity.getFilesDir(), "images");
                String fileName = "sign_" + System.currentTimeMillis() + ".png";
                new Thread(() -> {
                    String path = SignatureView.saveBitmap(bitmap, directory, fileName);
                    activity.runOnUiThread(() -> {
                        if (path == null) {
                            saveButton.setEnabled(true);
                            toast("签名保存失败");
                            return;
                        }
                        TextElement element = host.document().addElement(TextElement.TYPE_IMAGE);
                        element.text = path;
                        element.heightMm = LabelDocument.clamp(element.widthMm / 2f,
                                3f, LabelDocument.MAX_ELEMENT_SIZE_MM);
                        host.labelCanvas().select(element.id);
                        showSelectedElement(element);
                        persistAndRedraw();
                        dialog.dismiss();
                    });
                }, "eprint-signature-save").start();
            });
        });
        dialog.show();
    }

    public void showBorderStyleDialog() {
        String[] styles = {"无边框", "单线边框", "双线边框"};
        new AlertDialog.Builder(activity)
                .setTitle("标签边框")
                .setSingleChoiceItems(styles, host.document().borderStyle, (dialog, which) -> {
                    host.document().borderStyle = which;
                    persistAndRedraw();
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    public void refreshBorderStyleButton() {
        if (borderStyleButton == null) return;
        borderStyleButton.setText(host.document().borderStyle == 0 ? "边框:无"
                : host.document().borderStyle == 1 ? "边框:单" : "边框:双");
    }

    private void trackSnapshot() {
        if (restoringSnapshot) return;
        String json;
        try {
            json = host.document().toJson().toString();
        } catch (org.json.JSONException error) {
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        // 与上一快照间隔小于 800ms 的连续修改合并为一步；栈上限 50
        if (lastSnapshot == null) {
            lastSnapshot = json;
            lastSnapshotAt = 0L;
        } else if (!lastSnapshot.equals(json)) {
            if (lastSnapshotAt == 0L || now - lastSnapshotAt > 800) {
                if (undoStack.size() >= 50) undoStack.removeLast();
                undoStack.push(lastSnapshot);
            }
            // 任意新修改都会分叉历史，连续修改仍在 800ms 窗口内合并。
            redoStack.clear();
            lastSnapshot = json;
            lastSnapshotAt = now;
        }
        refreshUndoState();
    }

    public void undo() {
        if (undoStack.isEmpty()) {
            toast("没有可撤销的操作");
            return;
        }
        restoringSnapshot = true;
        redoStack.push(lastSnapshot);
        lastSnapshot = undoStack.pop();
        restoreSnapshot(lastSnapshot);
        lastSnapshotAt = 0L;
        restoringSnapshot = false;
        refreshUndoState();
    }

    public void redo() {
        if (redoStack.isEmpty()) {
            toast("没有可重做的操作");
            return;
        }
        restoringSnapshot = true;
        undoStack.push(lastSnapshot);
        lastSnapshot = redoStack.pop();
        restoreSnapshot(lastSnapshot);
        lastSnapshotAt = 0L;
        restoringSnapshot = false;
        refreshUndoState();
    }

    private void restoreSnapshot(String json) {
        LabelDocument restored = LabelDocument.fromJson(json);
        restored.dpi = host.selectedProfile().dpi;
        restored.normalize();
        host.setDocument(restored);
        DocumentStore.save(activity, host.workMode(), restored);
        host.labelCanvas().setDocument(restored);
        showSelectedElement(null);
        host.refreshDocumentControls();
        host.labelCanvas().invalidate();
        host.updateCompatibilityWarning();
        refreshElementChips();
    }

    private void refreshUndoState() {
        if (undoButton != null) undoButton.setAlpha(undoStack.isEmpty() ? 0.4f : 1f);
        if (redoButton != null) redoButton.setAlpha(redoStack.isEmpty() ? 0.4f : 1f);
    }

    /** onCreate 初始化撤销基线。 */
    public void initHistory() {
        try {
            lastSnapshot = host.document().toJson().toString();
        } catch (org.json.JSONException ignored) {
            lastSnapshot = null;
        }
        // 0 表示尚未开始修改分组，确保启动后的首次操作立即产生可撤销快照。
        lastSnapshotAt = 0L;
    }

    /** 切换工作模式后重置撤销历史（栈清空、以当前文档为基线）。 */
    public void resetHistory() {
        undoStack.clear();
        redoStack.clear();
        try {
            lastSnapshot = host.document().toJson().toString();
        } catch (org.json.JSONException ignored) {
            lastSnapshot = null;
        }
        lastSnapshotAt = 0L;
        refreshUndoState();
    }

    private static String chipLabel(TextElement element) {
        switch (element.type) {
            case TextElement.TYPE_LINE:
                return "v".equals(element.format) ? "竖线" : "横线";
            case TextElement.TYPE_RECT:
                return element.filled ? "矩形·填充" : "矩形";
            case TextElement.TYPE_TIME:
                return "时间";
            case TextElement.TYPE_SERIAL:
                return "序列:" + element.serialText();
            case TextElement.TYPE_ARCTEXT:
                String arc = element.text == null ? "" : element.text.trim();
                return compactElementLabel("弧形", arc, 10);
            case TextElement.TYPE_IMAGE:
                return "图片";
            case TextElement.TYPE_ICON:
                return "图标:" + IconCatalog.LABELS[IconCatalog.indexOf(element.format)];
            case TextElement.TYPE_TABLE:
                return "表格" + element.tableRows + "×" + element.tableCols;
            case TextElement.TYPE_BARCODE:
                String content = element.text == null ? "" : element.text.trim();
                return "一维码 · " + BarcodeRasterizer.BARCODE_FORMAT_LABELS[
                        barcodeFormatIndex(element.format)] + " · " + content.length() + "字";
            case TextElement.TYPE_QRCODE:
                String qrContent = element.text == null ? "" : element.text.trim();
                return "二维码 · " + BarcodeRasterizer.TWO_DIMENSIONAL_FORMAT_LABELS[
                        twoDimensionalFormatIndex(element.format)] + " · " + qrContent.length() + "字";
            case TextElement.TYPE_TEXT:
            default:
                String label = element.text == null ? "" : element.text.trim();
                if (label.isEmpty()) label = "（空文本）";
                return (element.locked ? "🔒" : "")
                        + compactElementLabel("文本", label, 12);
        }
    }

    private static String compactElementLabel(String type, String content, int visibleLimit) {
        if (content == null || content.isEmpty()) return type + " · 空";
        return content.length() <= visibleLimit ? type + " · " + content
                : type + " · " + content.length() + "字";
    }

    public void refreshElementChips() {
        if (elementChipsRow == null) return;
        elementChipsRow.removeAllViews();
        TextElement selected = host.labelCanvas() == null ? null : host.labelCanvas().getSelectedElement();
        Button activeChip = null;
        for (TextElement element : host.document().elements) {
            String label = chipLabel(element);
            Button chip = new Button(activity);
            chip.setText(label);
            chip.setAllCaps(false);
            chip.setTextSize(11);
            chip.setSingleLine(true);
            chip.setIncludeFontPadding(true);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(dp(10), 0, dp(10), 0);
            chip.setMinHeight(0);
            chip.setMinWidth(0);
            boolean active = selected != null && element.id.equals(selected.id);
            if (active) activeChip = chip;
            chip.setBackgroundResource(active
                    ? R.drawable.element_chip_primary : R.drawable.element_chip_secondary);
            chip.setTextColor(ContextCompat.getColor(activity,
                    active ? R.color.eprint_paper : R.color.eprint_text));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
            params.setMarginEnd(dp(6));
            chip.setLayoutParams(params);
            final String elementId = element.id;
            chip.setOnClickListener(view -> {
                host.labelCanvas().select(elementId);
                showSelectedElement(host.labelCanvas().getSelectedElement());
                refreshElementChips();
            });
            elementChipsRow.addView(chip);
        }
        if (elementChipsScroll != null && activeChip != null) {
            Button target = activeChip;
            elementChipsScroll.post(() -> {
                int left = Math.max(0, target.getLeft() - dp(6));
                elementChipsScroll.smoothScrollTo(left, 0);
            });
        }
    }

    public void configureEditor() {
        textInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = host.labelCanvas().getSelectedElement();
                if (selected != null) {
                    selected.text = editable.toString();
                    persistAndRedraw();
                }
            }
        });
        fontSizeInput.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = host.labelCanvas().getSelectedElement();
                Float value = parseNumber(editable.toString());
                if (selected != null && value != null) {
                    selected.fontSizePt = LabelDocument.clamp(value, 6f, 72f);
                    persistAndRedraw();
                }
            }
        });
        boldCheck.setOnCheckedChangeListener((button, checked) -> {
            if (populatingEditor) return;
            TextElement selected = host.labelCanvas().getSelectedElement();
            if (selected != null) {
                selected.bold = checked;
                persistAndRedraw();
            }
        });
        alignmentSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (populatingEditor) return;
                TextElement selected = host.labelCanvas().getSelectedElement();
                if (selected != null) {
                    selected.alignment = position;
                    persistAndRedraw();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
    }

    /** 类型特有属性行（码制/边长/线宽/填充/时间格式/锁定）的监听。 */
    public void configureTypeEditors() {
        bindElementGeometry(elementXInput, 0);
        bindElementGeometry(elementYInput, 1);
        bindElementGeometry(elementWidthInput, 2);
        bindElementGeometry(elementHeightInput, 3);
        if (barcodeFormatSpinner != null) {
            barcodeFormatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = host.labelCanvas().getSelectedElement();
                    if (selected == null || !TextElement.TYPE_BARCODE.equals(selected.type)) return;
                    selected.format = BarcodeRasterizer.BARCODE_FORMATS[position];
                    if (!BarcodeRasterizer.isValid(selected.text, selected.format)) {
                        toast("当前内容不符合 " + selected.format + " 码制，将显示为占位框");
                    }
                    persistAndRedraw();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (barcodeTextPositionSpinner != null) {
            barcodeTextPositionSpinner.setOnItemSelectedListener(
                    new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                                           int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = host.labelCanvas().getSelectedElement();
                    if (selected != null && TextElement.TYPE_BARCODE.equals(selected.type)) {
                        selected.barcodeTextPosition = new String[]{"none", "top", "bottom"}[position];
                        selected.showLabel = position != 0;
                        persistAndRedraw();
                    }
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (qrFormatSpinner != null) {
            qrFormatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                                           int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = host.labelCanvas().getSelectedElement();
                    if (selected != null && TextElement.TYPE_QRCODE.equals(selected.type)) {
                        selected.format = BarcodeRasterizer.TWO_DIMENSIONAL_FORMATS[position];
                        if (qrErrorCorrectionSpinner != null) {
                            qrErrorCorrectionSpinner.setEnabled("QR_CODE".equals(selected.format));
                        }
                        persistAndRedraw();
                    }
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (qrErrorCorrectionSpinner != null) {
            qrErrorCorrectionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = host.labelCanvas().getSelectedElement();
                    if (selected != null && TextElement.TYPE_QRCODE.equals(selected.type)) {
                        selected.qrErrorCorrection = new String[]{"L", "M", "Q", "H"}[position];
                        persistAndRedraw();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (codeQuietZoneSpinner != null) {
            codeQuietZoneSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                                           int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = host.labelCanvas().getSelectedElement();
                    if (selected != null && (TextElement.TYPE_BARCODE.equals(selected.type)
                            || TextElement.TYPE_QRCODE.equals(selected.type))) {
                        selected.quietZoneModules = new int[]{0, 2, 4}[position];
                        persistAndRedraw();
                    }
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (codeRotationSpinner != null) {
            codeRotationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view,
                                           int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = host.labelCanvas().getSelectedElement();
                    if (selected != null && (TextElement.TYPE_BARCODE.equals(selected.type)
                            || TextElement.TYPE_QRCODE.equals(selected.type))) {
                        selected.rotationDegrees = position * 90;
                        persistAndRedraw();
                    }
                }

                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (lineOrientationSpinner != null) {
            lineOrientationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = host.labelCanvas().getSelectedElement();
                    if (selected == null || !TextElement.TYPE_LINE.equals(selected.type)) return;
                    selected.format = position == 1 ? "v" : "h";
                    if ("v".equals(selected.format)) {
                        selected.heightMm = Math.max(selected.heightMm, selected.widthMm);
                    }
                    persistAndRedraw();
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        bindLineWidth(lineWidthInput, TextElement.TYPE_LINE);
        bindLineWidth(rectWidthInput, TextElement.TYPE_RECT);
        if (rectFilledCheck != null) {
            rectFilledCheck.setOnCheckedChangeListener((button, checked) -> {
                if (populatingEditor) return;
                TextElement selected = host.labelCanvas().getSelectedElement();
                if (selected != null && TextElement.TYPE_RECT.equals(selected.type)) {
                    selected.filled = checked;
                    persistAndRedraw();
                }
            });
        }
        if (timeFormatSpinner != null) {
            timeFormatSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = host.labelCanvas().getSelectedElement();
                    if (selected != null && TextElement.TYPE_TIME.equals(selected.type)) {
                        selected.format = TimeElementFormat.values()[position].pattern;
                        persistAndRedraw();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (arcDirectionSpinner != null) {
            arcDirectionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = host.labelCanvas().getSelectedElement();
                    if (selected != null && TextElement.TYPE_ARCTEXT.equals(selected.type)) {
                        selected.format = position == 1 ? "down" : "up";
                        persistAndRedraw();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (lockCheck != null) {
            lockCheck.setOnCheckedChangeListener((button, checked) -> {
                if (populatingEditor) return;
                TextElement selected = host.labelCanvas().getSelectedElement();
                if (selected != null) {
                    selected.locked = checked;
                    persistAndRedraw();
                    toast(checked ? "已锁定，画布拖拽与微调失效" : "已解锁");
                }
            });
        }
        bindSerialInt(serialValueInput, 0);
        bindSerialInt(serialStepInput, 1);
        bindSerialInt(serialDigitsInput, 2);
        if (iconStyleSpinner != null) {
            iconStyleSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = host.labelCanvas().getSelectedElement();
                    if (selected != null && TextElement.TYPE_ICON.equals(selected.type)) {
                        selected.format = IconCatalog.IDS[position];
                        persistAndRedraw();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        if (imageFitSpinner != null) {
            imageFitSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    if (populatingEditor) return;
                    TextElement selected = host.labelCanvas().getSelectedElement();
                    if (selected != null && TextElement.TYPE_IMAGE.equals(selected.type)) {
                        selected.format = ImageElementRenderer.FIT_MODE_IDS[position];
                        persistAndRedraw();
                    }
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {}
            });
        }
        bindBoxSize(imageWidthInput, true);
        bindBoxSize(imageHeightInput, false);
        bindLineWidth(iconLineWidthInput, TextElement.TYPE_ICON);
        bindLineWidth(tableLineWidthInput, TextElement.TYPE_TABLE);
        bindTableInt(tableRowsInput, true);
        bindTableInt(tableColsInput, false);
    }

    /** 通用位置/尺寸字段：0=X、1=Y、2=宽、3=高。 */
    private void bindElementGeometry(@Nullable EditText input, int field) {
        if (input == null) return;
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = host.labelCanvas().getSelectedElement();
                Float value = parseNumber(editable.toString());
                if (selected == null || value == null) return;
                if (field == 0) {
                    selected.xMm = LabelDocument.clamp(value,
                            -LabelDocument.MAX_ELEMENT_OFFSET_MM,
                            LabelDocument.MAX_ELEMENT_OFFSET_MM);
                } else if (field == 1) {
                    selected.yMm = LabelDocument.clamp(value,
                            -LabelDocument.MAX_ELEMENT_OFFSET_MM,
                            LabelDocument.MAX_ELEMENT_OFFSET_MM);
                } else if (field == 2) {
                    selected.widthMm = LabelDocument.clamp(value,
                            LabelDocument.MIN_ELEMENT_SIZE_MM,
                            LabelDocument.MAX_ELEMENT_SIZE_MM);
                } else {
                    selected.heightMm = LabelDocument.clamp(value,
                            LabelDocument.MIN_ELEMENT_SIZE_MM,
                            LabelDocument.MAX_ELEMENT_SIZE_MM);
                }
                persistAndRedraw();
            }
        });
    }

    /** 图片盒尺寸：width 为 true 改 widthMm，false 改 heightMm。 */
    private void bindBoxSize(@Nullable EditText input, boolean width) {
        if (input == null) return;
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = host.labelCanvas().getSelectedElement();
                Float value = parseNumber(editable.toString());
                if (selected == null || !TextElement.TYPE_IMAGE.equals(selected.type)
                        || value == null) return;
                if (width) {
                    selected.widthMm = LabelDocument.clamp(value, 1f,
                            LabelDocument.MAX_ELEMENT_SIZE_MM);
                } else {
                    selected.heightMm = LabelDocument.clamp(value, 1f,
                            LabelDocument.MAX_ELEMENT_SIZE_MM);
                }
                persistAndRedraw();
            }
        });
    }

    private void bindTableInt(@Nullable EditText input, boolean rows) {
        if (input == null) return;
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = host.labelCanvas().getSelectedElement();
                if (selected == null || !TextElement.TYPE_TABLE.equals(selected.type)) return;
                try {
                    int value = Integer.parseInt(editable.toString().trim());
                    if (rows) selected.tableRows = (int) LabelDocument.clamp(value, 1, 8);
                    else selected.tableCols = (int) LabelDocument.clamp(value, 1, 8);
                    persistAndRedraw();
                } catch (NumberFormatException ignored) {
                    // 输入中途的非法片段不落状态
                }
            }
        });
    }

    /** 序列号整数字段：field 0=当前值 1=步长 2=位数。 */
    private void bindSerialInt(@Nullable EditText input, int field) {
        if (input == null) return;
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = host.labelCanvas().getSelectedElement();
                if (selected == null || !TextElement.TYPE_SERIAL.equals(selected.type)) return;
                try {
                    int value = Integer.parseInt(editable.toString().trim());
                    if (field == 0) selected.serialCounter = Math.max(0, value);
                    else if (field == 1) selected.serialStep = (int) LabelDocument.clamp(value, 1, 999);
                    else selected.serialDigits = (int) LabelDocument.clamp(value, 1, 10);
                    persistAndRedraw();
                } catch (NumberFormatException ignored) {
                    // 输入中途的非法片段不落状态
                }
            }
        });
    }

    private void bindLineWidth(@Nullable EditText input, String expectedType) {
        if (input == null) return;
        input.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable editable) {
                if (populatingEditor) return;
                TextElement selected = host.labelCanvas().getSelectedElement();
                Float value = parseNumber(editable.toString());
                if (selected != null && expectedType.equals(selected.type) && value != null) {
                    selected.lineWidthMm = LabelDocument.clamp(value, 0.1f, 3f);
                    persistAndRedraw();
                }
            }
        });
    }

    public void showSelectedElement(@Nullable TextElement element) {
        populatingEditor = true;
        textEditorCard.setVisibility(element == null
                || host.workMode() == WorkMode.COMPONENT_ORGANIZATION ? View.GONE : View.VISIBLE);
        if (element != null) {
            if (editorTitle != null) editorTitle.setText(titleFor(element));
            if (elementXInput != null) elementXInput.setText(formatNumber(element.xMm));
            if (elementYInput != null) elementYInput.setText(formatNumber(element.yMm));
            if (elementWidthInput != null) elementWidthInput.setText(formatNumber(element.widthMm));
            if (elementHeightInput != null) elementHeightInput.setText(formatNumber(element.heightMm));
            boolean textual = element.isTextual();
            boolean codeContent = TextElement.TYPE_BARCODE.equals(element.type)
                    || TextElement.TYPE_QRCODE.equals(element.type);
            boolean hasContent = (textual && !TextElement.TYPE_TIME.equals(element.type))
                    || codeContent;
            textInput.setVisibility(hasContent ? View.VISIBLE : View.GONE);
            if (hasContent) {
                textInput.setHint(contentHintFor(element));
                textInput.setText(element.text);
                textInput.setSelection(textInput.length());
            }
            if (rowFont != null) rowFont.setVisibility(textual ? View.VISIBLE : View.GONE);
            fontSizeInput.setText(formatNumber(element.fontSizePt));
            boldCheck.setChecked(element.bold);
            alignmentSpinner.setSelection(element.alignment);
            if (rowBarcode != null) {
                rowBarcode.setVisibility(TextElement.TYPE_BARCODE.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (barcodeFormatSpinner != null && TextElement.TYPE_BARCODE.equals(element.type)) {
                barcodeFormatSpinner.setSelection(barcodeFormatIndex(element.format));
            }
            if (barcodeTextPositionSpinner != null) {
                barcodeTextPositionSpinner.setSelection(barcodeTextPositionIndex(
                        element.effectiveBarcodeTextPosition()));
            }
            if (rowQr != null) {
                rowQr.setVisibility(TextElement.TYPE_QRCODE.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            boolean code = TextElement.TYPE_BARCODE.equals(element.type)
                    || TextElement.TYPE_QRCODE.equals(element.type);
            if (rowCodeLayout != null) {
                rowCodeLayout.setVisibility(code ? View.VISIBLE : View.GONE);
            }
            if (qrFormatSpinner != null && TextElement.TYPE_QRCODE.equals(element.type)) {
                qrFormatSpinner.setSelection(twoDimensionalFormatIndex(element.format));
            }
            if (qrErrorCorrectionSpinner != null) {
                qrErrorCorrectionSpinner.setSelection(qrErrorCorrectionIndex(
                        element.qrErrorCorrection));
                qrErrorCorrectionSpinner.setEnabled(TextElement.TYPE_QRCODE.equals(element.type)
                        && "QR_CODE".equals(element.format));
            }
            if (codeQuietZoneSpinner != null) {
                codeQuietZoneSpinner.setSelection(quietZoneIndex(element.quietZoneModules));
            }
            if (codeRotationSpinner != null) {
                codeRotationSpinner.setSelection(element.rotationDegrees / 90);
            }
            if (rowLine != null) {
                rowLine.setVisibility(TextElement.TYPE_LINE.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (lineOrientationSpinner != null) {
                lineOrientationSpinner.setSelection("v".equals(element.format) ? 1 : 0);
            }
            if (lineWidthInput != null) lineWidthInput.setText(formatNumber(element.lineWidthMm));
            if (rowRect != null) {
                rowRect.setVisibility(TextElement.TYPE_RECT.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (rectWidthInput != null) rectWidthInput.setText(formatNumber(element.lineWidthMm));
            if (rectFilledCheck != null) rectFilledCheck.setChecked(element.filled);
            if (rowTime != null) {
                rowTime.setVisibility(TextElement.TYPE_TIME.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (timeFormatSpinner != null) {
                timeFormatSpinner.setSelection(
                        TimeElementFormat.byPattern(element.format).ordinal());
            }
            if (rowArc != null) {
                rowArc.setVisibility(TextElement.TYPE_ARCTEXT.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (arcDirectionSpinner != null) {
                arcDirectionSpinner.setSelection("down".equals(element.format) ? 1 : 0);
            }
            if (rowSerial != null) {
                rowSerial.setVisibility(TextElement.TYPE_SERIAL.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (TextElement.TYPE_SERIAL.equals(element.type)) {
                if (serialValueInput != null) serialValueInput.setText(Integer.toString(element.serialCounter));
                if (serialStepInput != null) serialStepInput.setText(Integer.toString(element.serialStep));
                if (serialDigitsInput != null) serialDigitsInput.setText(Integer.toString(element.serialDigits));
            }
            if (rowImage != null) {
                rowImage.setVisibility(TextElement.TYPE_IMAGE.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (TextElement.TYPE_IMAGE.equals(element.type)) {
                if (imageWidthInput != null) imageWidthInput.setText(formatNumber(element.widthMm));
                if (imageHeightInput != null) imageHeightInput.setText(formatNumber(element.heightMm));
                if (imageFitSpinner != null) {
                    imageFitSpinner.setSelection(ImageElementRenderer.fitModeIndex(element.format));
                }
            }
            if (rowIcon != null) {
                rowIcon.setVisibility(TextElement.TYPE_ICON.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (iconStyleSpinner != null) {
                iconStyleSpinner.setSelection(IconCatalog.indexOf(element.format));
            }
            if (iconLineWidthInput != null) {
                iconLineWidthInput.setText(formatNumber(element.lineWidthMm));
            }
            if (rowTable != null) {
                rowTable.setVisibility(TextElement.TYPE_TABLE.equals(element.type)
                        ? View.VISIBLE : View.GONE);
            }
            if (TextElement.TYPE_TABLE.equals(element.type)) {
                if (tableRowsInput != null) tableRowsInput.setText(Integer.toString(element.tableRows));
                if (tableColsInput != null) tableColsInput.setText(Integer.toString(element.tableCols));
                if (tableLineWidthInput != null) {
                    tableLineWidthInput.setText(formatNumber(element.lineWidthMm));
                }
            }
            if (lockCheck != null) lockCheck.setChecked(element.locked);
        }
        populatingEditor = false;
    }

    /** renderWorkMode 委托：普通/器件模式下编辑器卡片可见性。 */
    public void updateEditorCardVisibility(boolean component) {
        TextElement selected = host.labelCanvas() == null ? null : host.labelCanvas().getSelectedElement();
        if (textEditorCard != null) {
            textEditorCard.setVisibility(!component && selected != null ? View.VISIBLE : View.GONE);
        }
    }

    private static String contentHintFor(TextElement element) {
        switch (element.type) {
            case TextElement.TYPE_BARCODE: return "一维码内容";
            case TextElement.TYPE_QRCODE: return "二维码内容";
            case TextElement.TYPE_SERIAL: return "序列号前缀";
            case TextElement.TYPE_TABLE: return "表格内容（每行一个单元格）";
            case TextElement.TYPE_TIME: return "时间元素按下方格式自动生成";
            case TextElement.TYPE_ARCTEXT: return "弧形文字内容";
            default: return "文本内容";
        }
    }

    private static String titleFor(TextElement element) {
        switch (element.type) {
            case TextElement.TYPE_BARCODE: return "所选一维码";
            case TextElement.TYPE_QRCODE: return "所选二维码";
            case TextElement.TYPE_LINE: return "所选线条";
            case TextElement.TYPE_RECT: return "所选矩形";
            case TextElement.TYPE_TIME: return "所选时间";
            case TextElement.TYPE_SERIAL: return "所选序列号（前缀在上方编辑）";
            case TextElement.TYPE_ARCTEXT: return "所选弧形文字";
            case TextElement.TYPE_IMAGE: return "所选图片";
            case TextElement.TYPE_ICON: return "所选图标";
            case TextElement.TYPE_TABLE: return "所选表格（上方每行一个单元格）";
            case TextElement.TYPE_TEXT:
            default: return "所选文本";
        }
    }

    private static int barcodeFormatIndex(String format) {
        if ("ITF".equals(format)) format = "ITF_25";
        for (int index = 0; index < BarcodeRasterizer.BARCODE_FORMATS.length; index++) {
            if (BarcodeRasterizer.BARCODE_FORMATS[index].equals(format)) return index;
        }
        return 0;
    }

    private static int twoDimensionalFormatIndex(String format) {
        for (int index = 0; index < BarcodeRasterizer.TWO_DIMENSIONAL_FORMATS.length; index++) {
            if (BarcodeRasterizer.TWO_DIMENSIONAL_FORMATS[index].equals(format)) return index;
        }
        return 0;
    }

    private static int barcodeTextPositionIndex(String value) {
        if ("top".equals(value)) return 1;
        if ("bottom".equals(value)) return 2;
        return 0;
    }

    private static int quietZoneIndex(int value) {
        if (value >= 4) return 2;
        if (value >= 2) return 1;
        return 0;
    }

    private static int qrErrorCorrectionIndex(String value) {
        if ("L".equals(value)) return 0;
        if ("Q".equals(value)) return 2;
        if ("H".equals(value)) return 3;
        return 1;
    }

    /** 文档归一化 → 持久化 → 画布重绘 → 兼容性/边框/chips 刷新 → 快照。 */
    public void persistAndRedraw() {
        host.document().normalize();
        DocumentStore.save(activity, host.workMode(), host.document());
        host.labelCanvas().invalidate();
        host.updateCompatibilityWarning();
        refreshBorderStyleButton();
        refreshElementChips();
        trackSnapshot();
    }

    @Nullable
    private static Float parseNumber(String value) {
        try {
            return Float.parseFloat(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String formatNumber(float value) {
        if (value == Math.round(value)) return Integer.toString(Math.round(value));
        return String.format(Locale.US, "%.1f", value);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_SHORT).show();
    }
}
