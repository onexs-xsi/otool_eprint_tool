package com.otool.eprint.scan;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ComponentRecord {
    public final SupplierFormat supplier;
    public final boolean recognized;
    public final int confidence;
    public final String orderNumber;
    public final String supplierPartNumber;
    public final String manufacturerPartNumber;
    public final int quantity;
    public final String trackingId;
    public final String raw;
    public final Map<String, String> fields;
    public final String warning;

    ComponentRecord(SupplierFormat supplier, boolean recognized, int confidence,
                    String orderNumber, String supplierPartNumber,
                    String manufacturerPartNumber, int quantity, String trackingId,
                    String raw, Map<String, String> fields, String warning) {
        this.supplier = supplier;
        this.recognized = recognized;
        this.confidence = confidence;
        this.orderNumber = clean(orderNumber);
        this.supplierPartNumber = clean(supplierPartNumber);
        this.manufacturerPartNumber = clean(manufacturerPartNumber);
        this.quantity = Math.max(0, quantity);
        this.trackingId = clean(trackingId);
        this.raw = raw == null ? "" : raw;
        this.fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
        this.warning = clean(warning);
    }

    public String summary() {
        StringBuilder value = new StringBuilder()
                .append("供应商：").append(supplier.label)
                .append(" · 识别置信度 ").append(confidence).append('%');
        append(value, "立创料号", supplierPartNumber);
        append(value, "型号", manufacturerPartNumber);
        append(value, "品类", fields.get("品类"));
        append(value, "规格", fields.get("规格"));
        append(value, "封装", fields.get("封装"));
        append(value, "品牌", fields.get("厂商"));
        append(value, "参数", fields.get("参数"));
        if (!warning.isEmpty()) value.append("\n提示：").append(warning);
        return value.toString();
    }

    private static void append(StringBuilder target, String label, String value) {
        if (value != null && !value.isEmpty()) {
            target.append('\n').append(label).append("：").append(value);
        }
    }

    private static String clean(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        return "null".equalsIgnoreCase(normalized) ? "" : normalized;
    }
}
