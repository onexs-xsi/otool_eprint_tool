package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class CategoryPackageMatcherTest {
    private static CategoryPackageMatcher sampleMatcher() {
        return new CategoryPackageMatcher(Arrays.asList(
                new CategoryPackageMatcher.CategoryRule(
                        "贴片电阻", "电阻", "电阻", Arrays.asList("0402", "0603", "0805")),
                new CategoryPackageMatcher.CategoryRule(
                        "功率电感", "电感/线圈/变压器", "电感/线圈/变压器",
                        Arrays.asList("0805", "0806", "SMD,8.0x8.0mm"))));
    }

    @Test
    public void exactCategoryConstrainsNumericPackage() {
        CategoryPackageMatcher matcher = sampleMatcher();
        assertEquals("0806", matcher.findPackage("功率电感", Collections.singletonList("0806")));
        assertEquals("", matcher.findPackage("贴片电阻", Collections.singletonList("0806")));
        assertTrue(matcher.hasCategory("功率电感"));
    }

    @Test
    public void modelSubstringIsNotAcceptedAsPackage() {
        CategoryPackageMatcher matcher = sampleMatcher();
        assertEquals("", matcher.findPackage("贴片电阻",
                Collections.singletonList("CGA0402C0G120J500GT")));
        assertEquals("0402", matcher.findPackage("贴片电阻",
                Collections.singletonList("封装: 0402")));
    }

    @Test
    public void unknownCategoryLeavesFallbackDecisionToParser() {
        CategoryPackageMatcher matcher = sampleMatcher();
        assertFalse(matcher.hasCategory("RGB LED"));
    }

    @Test
    public void categoryAndBrandAliasesResolveToCanonicalCatalogValues() {
        CategoryPackageMatcher matcher = new CategoryPackageMatcher(Collections.singletonList(
                new CategoryPackageMatcher.CategoryRule(
                        "RGB LED(内置IC)", "RGB LEDs(Built-in IC)", "光电器件", "光电器件",
                        Collections.singletonList("SMD5050"),
                        Collections.singletonList("XINGLIGHT(成兴光)"))));

        assertEquals("RGB LED(内置IC)", matcher.canonicalCategory("RGBLED(内置IC"));
        assertEquals("XINGLIGHT(成兴光)", matcher.canonicalBrand("XINGLIGHT"));
        assertEquals("XINGLIGHT(成兴光)", matcher.canonicalBrand("成兴光"));
        assertEquals("SMD5050", matcher.canonicalPackage("RGBLED(内置IC", "SMD5050"));
        assertEquals("", matcher.canonicalBrand("RGBLED(内置IC", "VISHAY(威世)"));
    }
}
