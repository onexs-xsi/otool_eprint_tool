package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;

import org.json.JSONException;
import org.junit.Test;

import java.util.Collections;

public final class LcscPackageCatalogTest {
    @Test
    public void compactPackagePoolIsDecodedIntoCategoryRules() throws JSONException {
        String json = "{"
                + "\"schemaVersion\":2,"
                + "\"packages\":[\"0603\",\"0806\"],"
                + "\"brands\":[{\"code\":\"12469\",\"name\":\"XINGLIGHT(成兴光)\"}],"
                + "\"categories\":[{"
                + "\"name\":\"功率电感\","
                + "\"parentName\":\"电感/线圈/变压器\","
                + "\"rootName\":\"电感/线圈/变压器\","
                + "\"packageIds\":[1],\"brandIds\":[0]}]}";

        CategoryPackageMatcher matcher = LcscPackageCatalog.fromJson(json);

        assertEquals("0806", matcher.findPackage(
                "功率电感", Collections.singletonList("0806")));
        assertEquals("", matcher.findPackage(
                "功率电感", Collections.singletonList("0603")));
        assertEquals("XINGLIGHT(成兴光)", matcher.canonicalBrand("XINGLIGHT"));
    }

    @Test(expected = JSONException.class)
    public void invalidPackageIdFailsClosed() throws JSONException {
        LcscPackageCatalog.fromJson("{"
                + "\"schemaVersion\":2,"
                + "\"packages\":[\"0806\"],"
                + "\"brands\":[],"
                + "\"categories\":[{\"name\":\"功率电感\","
                + "\"packageIds\":[2],\"brandIds\":[]}]}");
    }
}
