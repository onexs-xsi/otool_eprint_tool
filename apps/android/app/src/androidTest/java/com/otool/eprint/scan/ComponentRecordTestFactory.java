package com.otool.eprint.scan;

import java.util.Map;

/** 为跨子包 instrumentation 构造受控器件记录，不扩大生产构造器可见性。 */
public final class ComponentRecordTestFactory {
    private ComponentRecordTestFactory() {}

    public static ComponentRecord create(String supplierPart, String model, String raw,
                                         Map<String, String> fields) {
        return new ComponentRecord(SupplierFormat.LCSC, true, 90, "",
                supplierPart, model, 0, "", raw, fields, "");
    }
}
