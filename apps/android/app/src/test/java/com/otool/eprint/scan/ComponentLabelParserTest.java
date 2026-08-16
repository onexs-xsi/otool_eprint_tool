package com.otool.eprint.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ComponentLabelParserTest {
    @Test
    public void autoDetectsAllObservedLcscLabels() {
        String[] samples = {
                "{on:SO25011518496,pc:C2843785,pm:XL-5050RGBC-WS2812B,qty:20,mc:null,cc:1,pdi:142926355,hp:21}",
                "{on:SO26063011291,pc:C2691448,pm:PZ254V-11-04P,qty:20,mc:,cc:1,pdi:222571843,hp:21}",
                "{on:SO26063011291,pc:C45359894,pm:CGA0402C0G120J500GT,qty:100,mc:,cc:1,pdi:222571873,hp:12}"
        };
        for (String sample : samples) {
            ComponentRecord record = ComponentLabelParser.parse(sample, SupplierFormat.AUTO);
            assertEquals(SupplierFormat.LCSC, record.supplier);
            assertTrue(record.recognized);
            assertTrue(record.confidence >= 70);
        }
    }

    @Test
    public void lcscModePreservesUnknownFieldsAndNormalizesNulls() {
        ComponentRecord record = ComponentLabelParser.parse(
                "{on:SO1,pc:C1234,pm:ABC,qty:20,mc:null,hp:21}", SupplierFormat.LCSC);
        assertEquals("", record.fields.get("mc"));
        assertEquals("21", record.fields.get("hp"));
        assertEquals(20, record.quantity);
    }

    @Test
    public void unknownAdaptivePayloadIsNotMislabelled() {
        ComponentRecord record = ComponentLabelParser.parse("hello", SupplierFormat.AUTO);
        assertFalse(record.recognized);
        assertEquals(SupplierFormat.AUTO, record.supplier);
    }
}
