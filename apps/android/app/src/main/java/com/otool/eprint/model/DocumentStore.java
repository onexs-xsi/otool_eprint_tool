package com.otool.eprint.model;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;

public final class DocumentStore {
    private static final String PREFERENCES = "eprint_native";
    private static final String DOCUMENT_KEY = "label_document_v1";
    private static final String COMPONENT_DOCUMENT_KEY = "label_document_component_v2";

    private DocumentStore() {}

    public static LabelDocument load(Context context) {
        return load(context, WorkMode.NORMAL);
    }

    public static LabelDocument load(Context context, WorkMode mode) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        String json = preferences.getString(key(mode), null);
        return json == null ? LabelDocument.createDefault() : LabelDocument.fromJson(json);
    }

    public static void save(Context context, LabelDocument document) {
        save(context, WorkMode.NORMAL, document);
    }

    public static void save(Context context, WorkMode mode, LabelDocument document) {
        try {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                    .edit()
                    .putString(key(mode), document.toJson().toString())
                    .apply();
        } catch (JSONException ignored) {
            // The in-memory document remains usable even if persistence fails.
        }
    }

    private static String key(WorkMode mode) {
        return mode == WorkMode.COMPONENT_ORGANIZATION ? COMPONENT_DOCUMENT_KEY : DOCUMENT_KEY;
    }
}
