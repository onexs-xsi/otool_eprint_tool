package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

public final class UnitSpacingFormatterTest {
    @Test
    public void insertsSpaceOnlyAfterElectricalUnit() {
        assertEquals("0Ω 333.333mW",
                UnitSpacingFormatter.formatValue("0Ω333.333mW"));
        assertEquals("12pF ±5% 50V",
                UnitSpacingFormatter.formatValue("12pF ±5% 50V"));
        assertEquals("10kΩ 25°C 100mA",
                UnitSpacingFormatter.formatValue("10kΩ25°C100mA"));
    }

    @Test
    public void isIdempotentAndDoesNotSplitWordsOrPackageCodes() {
        String formatted = UnitSpacingFormatter.formatValue("0Ω 333.333mW");
        assertEquals(formatted, UnitSpacingFormatter.formatValue(formatted));
        assertEquals("0603 VISHAY ROHS CRCW06030000Z0EAHP",
                UnitSpacingFormatter.formatValue("0603 VISHAY ROHS CRCW06030000Z0EAHP"));
    }

    @Test
    public void formatsOnlySpecificationAndParametersInRecord() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("规格", "0Ω333.333mW");
        fields.put("参数", "50V100mA");
        fields.put("封装", "0603");
        fields.put("厂商", "VISHAY");
        ComponentRecord input = new ComponentRecord(SupplierFormat.LCSC, true, 95,
                "", "C844719", "CRCW06030000Z0EAHP", 0, "", "", fields, "");

        ComponentRecord output = UnitSpacingFormatter.format(input);

        assertEquals("0Ω 333.333mW", output.fields.get("规格"));
        assertEquals("50V 100mA", output.fields.get("参数"));
        assertEquals("0603", output.fields.get("封装"));
        assertEquals("CRCW06030000Z0EAHP", output.manufacturerPartNumber);
    }
}
