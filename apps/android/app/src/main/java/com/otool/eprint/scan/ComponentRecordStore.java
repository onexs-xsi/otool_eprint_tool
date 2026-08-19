package com.otool.eprint.scan;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最近一次器件识别结果的持久化。进程重启后 componentRecord 恢复非空，
 * 模板配置切换（分隔线/字号/字段）与标签尺寸切换才能实时重排
 * （v1.5.29 之前重启后 record 为 null，这两类操作在主预览上不生效）。
 */
public final class ComponentRecordStore {
    private static final String PREFERENCES = "eprint_component_record";
    private static final String KEY_PAYLOAD = "payload";

    private ComponentRecordStore() {}

    public static void save(Context context, ComponentRecord record) {
        SharedPreferences prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        if (record == null) {
            prefs.edit().remove(KEY_PAYLOAD).apply();
            return;
        }
        try {
            JSONObject json = new JSONObject();
            json.put("supplier", record.supplier.name());
            json.put("recognized", record.recognized);
            json.put("confidence", record.confidence);
            json.put("orderNumber", record.orderNumber);
            json.put("supplierPartNumber", record.supplierPartNumber);
            json.put("manufacturerPartNumber", record.manufacturerPartNumber);
            json.put("quantity", record.quantity);
            json.put("trackingId", record.trackingId);
            json.put("raw", record.raw);
            json.put("warning", record.warning);
            JSONObject fields = new JSONObject();
            for (Map.Entry<String, String> entry : record.fields.entrySet()) {
                fields.put(entry.getKey(), entry.getValue());
            }
            json.put("fields", fields);
            prefs.edit().putString(KEY_PAYLOAD, json.toString()).apply();
        } catch (JSONException ignored) {
            // 序列化失败不致命：保持旧值即可。
        }
    }

    /** 读取持久化的识别结果；从未识别或数据损坏时返回 null。 */
    public static ComponentRecord load(Context context) {
        String payload = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(KEY_PAYLOAD, null);
        if (payload == null) return null;
        try {
            JSONObject json = new JSONObject(payload);
            Map<String, String> fields = new LinkedHashMap<>();
            JSONObject fieldsJson = json.optJSONObject("fields");
            if (fieldsJson != null) {
                for (Iterator<String> keys = fieldsJson.keys(); keys.hasNext();) {
                    String key = keys.next();
                    fields.put(key, fieldsJson.optString(key, ""));
                }
            }
            SupplierFormat supplier = SupplierFormat.AUTO;
            try {
                supplier = SupplierFormat.valueOf(json.optString("supplier", "AUTO"));
            } catch (IllegalArgumentException unknown) {
                // 未知供应商枚举按 AUTO 处理。
            }
            return new ComponentRecord(supplier, json.optBoolean("recognized"),
                    json.optInt("confidence"), json.optString("orderNumber"),
                    json.optString("supplierPartNumber"),
                    json.optString("manufacturerPartNumber"), json.optInt("quantity"),
                    json.optString("trackingId"), json.optString("raw"), fields,
                    json.optString("warning"));
        } catch (JSONException corrupted) {
            return null;
        }
    }
}
