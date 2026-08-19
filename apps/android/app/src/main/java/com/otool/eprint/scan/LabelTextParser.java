package com.otool.eprint.scan;

import com.otool.eprint.scan.ocr.OcrLine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OCR 行 → ComponentRecord 规则解析器（按立创横版贴纸版式，见 docs/ocr_plan_0818.md §3.5）。
 * 纯 JVM 实现，可单测；只读输入，不做任何网络访问。
 */
public final class LabelTextParser {
    private static final Pattern INTERNAL_CODE =
            Pattern.compile("\\d{4}-[A-Z]-[A-Z0-9]{2,}-\\d{3}");
    // Android 的 Unicode 单词边界会把数字后的汉字视为同一词，导致 C2843785静 漏匹配。
    // 这里仅禁止相邻 ASCII 字母数字，既能截出料号，又不会把它误当品牌候选。
    private static final Pattern LCSC_PART = Pattern.compile(
            "(?<![A-Za-z0-9])C\\d{5,}(?![A-Za-z0-9])");
    private static final Pattern QTY = Pattern.compile("QTY[:：]?\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern SO_NUMBER = Pattern.compile("\\bSO\\d{10,}\\b");
    private static final Pattern LOT_NUMBER = Pattern.compile("^\\d{4}-\\d{4}$");
    // 单位模式：多字符单位（pF/KΩ/mA…）直接可信；单字符单位（V/A/W/H）后随字母数字或连字符时
    // 多为型号片段（如 PZ254V-11-04P），用否定前瞻排除。
    private static final Pattern UNIT_VALUE = Pattern.compile(
            "\\d+(\\.\\d+)?\\s*(pF|nF|uF|µF|μF|mF|Ω|KΩ|kΩ|MΩ|mA|mV|kV|ppm|mW|mH|uH|nH)(?![A-Za-z])"
                    + "|\\d+(\\.\\d+)?\\s*(V|A|W|H)(?![\\w-])"
                    + "|\\d+(\\.\\d+)?\\s*%",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TOLERANCE = Pattern.compile("[±土]\\s*\\d+(\\.\\d+)?\\s*%");
    private static final Pattern PACKAGE_CODE = Pattern.compile(
            "\\b(0[2346]0[23]|0805|1206|1210|2512)\\b|\\bSMD[-\\s]?\\d{4}\\b");
    private static final Pattern CATEGORY_HINT = Pattern.compile(
            "\\b(RGB\\s*LED|LED|MLCC|RESISTOR|CAPACITOR|DIODE|TRANSISTOR|MOSFET|IC|CONNECTOR)\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CHINESE_CATEGORY_HINT = Pattern.compile(
            "贴片电容|电容|电阻|发光二极管|二极管|三极管|MOS管|连接器|芯片|继电器|电感|晶振|灯珠");
    private static final Pattern HAS_CHINESE = Pattern.compile("[\\u4e00-\\u9fff]");
    private static final Pattern HAS_LETTER = Pattern.compile("[A-Za-z]");
    private static final Pattern HAS_DIGIT = Pattern.compile("\\d");

    private LabelTextParser() {}

    public static ComponentRecord parse(List<OcrLine> lines) {
        return parse(lines, CategoryPackageMatcher.empty());
    }

    /** 使用立创离线品类目录约束封装；目录不可用时保持原有基础正则回退。 */
    public static ComponentRecord parse(List<OcrLine> lines,
                                        CategoryPackageMatcher packageMatcher) {
        List<OcrLine> ordered = readingOrder(lines);
        StringBuilder raw = new StringBuilder();
        for (OcrLine line : ordered) {
            if (raw.length() > 0) raw.append('\n');
            raw.append(line.text);
        }
        List<String> texts = mergeResidualLines(ordered);
        List<String> correctedTexts = new ArrayList<>();

        String internalCode = "";
        String supplierPart = "";
        String soNumber = "";
        String lotNumber = "";
        int quantity = 0;
        String spec = "";
        String fallbackPackageCode = "";
        String vendor = "";
        String category = "";
        String model = "";
        int specUnitCount = 0;

        for (String original : texts) {
            String line = correctCommonMisreads(original.trim());
            if (line.isEmpty()) continue;
            correctedTexts.add(line);

            Matcher internal = INTERNAL_CODE.matcher(line);
            if (internalCode.isEmpty() && internal.find()) {
                internalCode = internal.group();
                continue;
            }
            Matcher part = LCSC_PART.matcher(line);
            if (part.find()) {
                // 料号行优先级最高；同行若同时含 QTY 也一并收下。
                if (supplierPart.isEmpty()) supplierPart = part.group();
                Matcher qtyInLine = QTY.matcher(line);
                if (quantity == 0 && qtyInLine.find()) quantity = parseInt(qtyInLine.group(1));
                continue;
            }
            Matcher qty = QTY.matcher(line);
            if (quantity == 0 && qty.find()) {
                quantity = parseInt(qty.group(1));
                continue;
            }
            Matcher so = SO_NUMBER.matcher(line);
            if (soNumber.isEmpty() && so.find()) {
                soNumber = so.group();
                continue;
            }
            if (lotNumber.isEmpty() && LOT_NUMBER.matcher(line).matches()) {
                lotNumber = line;
                continue;
            }
            if (HAS_CHINESE.matcher(line).find()) {
                // 以 ASCII 字母开头的是厂商（HRE(芯声)），中文开头的是品类（贴片电容(MLCC)）。
                if (Character.isLetter(line.charAt(0)) && line.charAt(0) < 0x80) {
                    if (vendor.isEmpty()) vendor = line;
                } else if (CHINESE_CATEGORY_HINT.matcher(line).find()) {
                    Matcher hint = CHINESE_CATEGORY_HINT.matcher(line);
                    hint.find();
                    if (UNIT_VALUE.matcher(line).find()) {
                        // 混合行（如 "厚膜电阻 0Ω 333.333mW"）：中文段作品类，
                        // 单位段继续作规格候选，不再整行当归品类后丢弃规格。
                        if (category.isEmpty()) category = line.substring(0, hint.end());
                        String specPart = line.substring(hint.end()).trim();
                        int units = countUnitValues(specPart);
                        if (!specPart.isEmpty() && units > specUnitCount) {
                            spec = specPart;
                            specUnitCount = units;
                        }
                    } else if (category.isEmpty()) {
                        category = line;
                    }
                }
                continue;
            }
            if (TOLERANCE.matcher(line).find() || UNIT_VALUE.matcher(line).find()) {
                // 规格候选取单位值更多的一行（如 "0Ω 333.333mW" 胜过孤立的 "3.00mW" 残读）。
                int units = countUnitValues(line);
                if (spec.isEmpty() || units > specUnitCount) {
                    spec = line;
                    specUnitCount = units;
                }
                Matcher pkg = PACKAGE_CODE.matcher(line);
                if (fallbackPackageCode.isEmpty() && pkg.find()) {
                    fallbackPackageCode = pkg.group();
                }
                continue;
            }
            Matcher pkg = PACKAGE_CODE.matcher(line);
            if (pkg.find()) {
                if (fallbackPackageCode.isEmpty()) fallbackPackageCode = pkg.group();
                String packageOnly = pkg.group().replace("-", "").replace(" ", "");
                String lineWithoutSeparators = line.replace("-", "").replace(" ", "");
                if (lineWithoutSeparators.equalsIgnoreCase(packageOnly)) continue;
            }
            // 英文品类行（例如样品上的 "RGB LED(...)"）不含中文，需在型号候选前识别。
            if (category.isEmpty() && !HAS_DIGIT.matcher(line).find()
                    && CATEGORY_HINT.matcher(line).find()) {
                category = line;
                continue;
            }
            // 型号候选：排除上述模式后最长的字母数字混合行。
            if (HAS_LETTER.matcher(line).find() && HAS_DIGIT.matcher(line).find()
                    && line.length() > model.length()) {
                model = line;
            }
        }

        CategoryPackageMatcher matcher = packageMatcher == null
                ? CategoryPackageMatcher.empty() : packageMatcher;
        String catalogCategory = matcher.canonicalCategory(category);
        if (catalogCategory.isEmpty() && category.isEmpty()) {
            catalogCategory = matcher.findCategory(correctedTexts);
        }
        if (!catalogCategory.isEmpty()) category = catalogCategory;

        // 字典扫描所有独立行，而非只接受第一个“ASCII+中文”候选。这样即使料号旁粘了
        // 一个汉字，也不会抢先覆盖后续真实品牌；命中后统一输出立创规范品牌名。
        String catalogBrand = matcher.findBrand(category, correctedTexts);
        if (catalogBrand.isEmpty()) catalogBrand = matcher.canonicalBrand(category, vendor);
        if (!catalogBrand.isEmpty()) vendor = catalogBrand;

        String packageCode;
        if (matcher.hasCategory(category)) {
            // 已知品类必须命中该品类允许集合；不再让全局四位数字白名单越权回填。
            packageCode = matcher.findPackage(category, correctedTexts);
        } else {
            packageCode = fallbackPackageCode;
        }

        Map<String, String> fields = new LinkedHashMap<>();
        if (!internalCode.isEmpty()) fields.put("内部编码", internalCode);
        if (!spec.isEmpty()) fields.put("规格", spec);
        if (!packageCode.isEmpty()) fields.put("封装", packageCode);
        if (!vendor.isEmpty()) fields.put("厂商", vendor);
        if (!category.isEmpty()) fields.put("品类", category);
        if (!soNumber.isEmpty()) fields.put("订单号", soNumber);
        if (!lotNumber.isEmpty()) fields.put("批次号", lotNumber);

        int score = 0;
        if (!supplierPart.isEmpty()) score += 40;
        if (!model.isEmpty()) score += 40;
        if (!spec.isEmpty()) score += 5;
        if (!packageCode.isEmpty()) score += 5;
        if (!vendor.isEmpty()) score += 5;
        if (!category.isEmpty()) score += 5;
        score = Math.min(100, score);

        SupplierFormat supplier = supplierPart.isEmpty() ? SupplierFormat.AUTO : SupplierFormat.LCSC;
        boolean recognized = !supplierPart.isEmpty() && !model.isEmpty();
        String warning = buildWarning(supplierPart, model, raw.length() > 0);
        // SO 流水号即立创订单号（与二维码 payload 的 on 字段对齐），批次号作追溯号。
        return new ComponentRecord(supplier, recognized, score, soNumber, supplierPart,
                model, quantity, lotNumber, raw.toString(), fields, warning);
    }

    /** 阅读顺序排序：按行带上边缘、再按左边缘；包围盒无效时保持输入顺序。 */
    private static List<OcrLine> readingOrder(List<OcrLine> lines) {
        List<OcrLine> ordered = new ArrayList<>(lines);
        boolean hasRects = false;
        for (OcrLine line : ordered) hasRects |= line.hasRect();
        if (hasRects) {
            ordered.sort(Comparator.comparingInt((OcrLine line) -> line.top)
                    .thenComparingInt(line -> line.left));
        }
        return ordered;
    }

    /**
     * 残行合并：被二维码遮挡/截断产生的 1~2 字符孤行，若与另一行处于同一水平带
     * 且水平间距小于 1.5 倍行高，则按左右顺序并入该行；找不到宿主则丢弃。
     */
    private static List<String> mergeResidualLines(List<OcrLine> lines) {
        boolean[] consumed = new boolean[lines.size()];
        List<String> result = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            OcrLine line = lines.get(index);
            // 残行不在主循环输出：由宿主行合并；找不到宿主的孤行直接丢弃。
            if (isResidual(line)) continue;
            StringBuilder merged = new StringBuilder(line.text);
            for (int other = 0; other < lines.size(); other++) {
                if (other == index || consumed[other]) continue;
                OcrLine candidate = lines.get(other);
                if (!isResidual(candidate) || !sameBand(line, candidate)
                        || !isMergeableResidual(line, candidate)) continue;
                consumed[other] = true;
                if (candidate.left < line.left) merged.insert(0, candidate.text);
                else merged.append(candidate.text);
            }
            result.add(merged.toString());
        }
        return result;
    }

    private static boolean isResidual(OcrLine line) {
        return line.hasRect() && line.text.trim().length() <= 2;
    }

    private static boolean sameBand(OcrLine line, OcrLine other) {
        int tolerance = Math.max(1, (int) (Math.max(line.height(), other.height()) * 0.6));
        int gap = Math.max(other.left - line.right, line.left - other.right);
        int maxGap = Math.max(1, (int) (Math.max(line.height(), other.height()) * 1.5));
        return Math.abs(line.centerY() - other.centerY()) <= tolerance && gap <= maxGap;
    }

    /** 当前样品中可信的残行拼接仅是 QTY 末尾数字；孤立汉字/字母不得污染品类或型号。 */
    private static boolean isMergeableResidual(OcrLine host, OcrLine residual) {
        return QTY.matcher(host.text).find() && residual.text.trim().matches("\\d{1,2}");
    }

    /** 常见误识映射：土→±；数字之间的 O/o→0、l/I→1。 */
    static String correctCommonMisreads(String line) {
        String corrected = line.replace('土', '±');
        StringBuilder out = new StringBuilder(corrected.length());
        for (int index = 0; index < corrected.length(); index++) {
            char c = corrected.charAt(index);
            boolean digitBefore = index > 0 && Character.isDigit(corrected.charAt(index - 1));
            boolean digitAfter = index + 1 < corrected.length()
                    && Character.isDigit(corrected.charAt(index + 1));
            if (digitBefore && digitAfter && (c == 'O' || c == 'o')) c = '0';
            else if (digitBefore && digitAfter && (c == 'l' || c == 'I')) c = '1';
            out.append(c);
        }
        return out.toString();
    }

    private static String buildWarning(String part, String model, boolean hasText) {
        if (!hasText) return "未识别到有效字段，请对准标签重拍";
        StringBuilder missing = new StringBuilder();
        if (part.isEmpty()) missing.append("料号 ");
        if (model.isEmpty()) missing.append("型号 ");
        return missing.length() == 0 ? ""
                : "缺少" + missing.toString().trim() + "，打印前请人工核对";
    }

    /** 一行中命中的单位值个数（规格行优先级依据）。 */
    private static int countUnitValues(String line) {
        int count = 0;
        Matcher matcher = UNIT_VALUE.matcher(line);
        int from = 0;
        while (matcher.find(from)) {
            count++;
            from = matcher.end();
        }
        return count;
    }

    private static int parseInt(String value) {
        try {
            return Math.max(0, Integer.parseInt(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }
}
