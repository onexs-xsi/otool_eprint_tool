package com.otool.eprint.scan;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 延迟加载 APK 内合并的立创品类—品牌—封装离线快照；识别过程中不访问网络。 */
public final class LcscPackageCatalog {
    private static final String TAG = "LcscPackageCatalog";
    private static final String ASSET_PATH = "catalog/lcsc_category_packages.json";
    private static volatile CategoryPackageMatcher cached;

    private LcscPackageCatalog() {}

    public static CategoryPackageMatcher get(Context context) {
        CategoryPackageMatcher value = cached;
        if (value != null) return value;
        synchronized (LcscPackageCatalog.class) {
            value = cached;
            if (value != null) return value;
            try (InputStream input = context.getApplicationContext().getAssets().open(ASSET_PATH)) {
                value = fromJson(readUtf8(input));
            } catch (IOException | JSONException error) {
                Log.w(TAG, "立创器件目录加载失败，保留基础正则回退", error);
                value = CategoryPackageMatcher.empty();
            }
            cached = value;
            return value;
        }
    }

    static CategoryPackageMatcher fromJson(String json) throws JSONException {
        JSONObject root = new JSONObject(json);
        if (root.optInt("schemaVersion", 0) != 2) {
            throw new JSONException("不支持的立创器件目录 schemaVersion");
        }
        JSONArray packageArray = root.getJSONArray("packages");
        List<String> packagePool = new ArrayList<>(packageArray.length());
        for (int index = 0; index < packageArray.length(); index++) {
            packagePool.add(packageArray.getString(index));
        }
        JSONArray brandArray = root.getJSONArray("brands");
        List<String> brandPool = new ArrayList<>(brandArray.length());
        for (int index = 0; index < brandArray.length(); index++) {
            JSONObject brand = brandArray.getJSONObject(index);
            String code = brand.optString("code", "").trim();
            String name = brand.optString("name", "").trim();
            if (code.isEmpty() || name.isEmpty()) {
                throw new JSONException("立创品牌目录包含空 code/name");
            }
            brandPool.add(name);
        }

        JSONArray categoryArray = root.getJSONArray("categories");
        List<CategoryPackageMatcher.CategoryRule> rules =
                new ArrayList<>(categoryArray.length());
        for (int index = 0; index < categoryArray.length(); index++) {
            JSONObject category = categoryArray.getJSONObject(index);
            JSONArray packageIds = category.getJSONArray("packageIds");
            List<String> packages = new ArrayList<>(packageIds.length());
            for (int packageIndex = 0; packageIndex < packageIds.length(); packageIndex++) {
                int id = packageIds.getInt(packageIndex);
                if (id < 0 || id >= packagePool.size()) {
                    throw new JSONException("立创封装目录 packageId 越界：" + id);
                }
                packages.add(packagePool.get(id));
            }
            JSONArray brandIds = category.getJSONArray("brandIds");
            List<String> brands = new ArrayList<>(brandIds.length());
            for (int brandIndex = 0; brandIndex < brandIds.length(); brandIndex++) {
                int id = brandIds.getInt(brandIndex);
                if (id < 0 || id >= brandPool.size()) {
                    throw new JSONException("立创品牌目录 brandId 越界：" + id);
                }
                brands.add(brandPool.get(id));
            }
            rules.add(new CategoryPackageMatcher.CategoryRule(
                    category.optString("name", ""),
                    category.optString("nameEn", ""),
                    category.optString("parentName", ""),
                    category.optString("rootName", ""),
                    packages,
                    brands));
        }
        return new CategoryPackageMatcher(rules);
    }

    private static String readUtf8(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }
}
