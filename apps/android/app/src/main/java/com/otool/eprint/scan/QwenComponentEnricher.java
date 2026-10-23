package com.otool.eprint.scan;

import com.otool.eprint.scan.qwen.QwenSettings;

import org.json.JSONException;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Qwen 严格 JSON 的最终落地门禁：默认只补空字段，且每个值必须有 OCR 文字证据。 */
public final class QwenComponentEnricher {
    private QwenComponentEnricher() {}

    public static Result apply(ComponentRecord current, String qwenOutput, String ocrEvidence,
                               CategoryPackageMatcher catalog, QwenSettings settings)
            throws JSONException {
        if (current == null) throw new IllegalArgumentException("当前器件记录为空");
        ComponentRecord candidate = GlmInformationExtractionParser.parse(qwenOutput);
        CategoryPackageMatcher matcher = catalog == null ? CategoryPackageMatcher.empty() : catalog;
        QwenSettings policy = settings == null ? QwenSettings.defaults() : settings;
        String evidence = clean(ocrEvidence);

        MutableCounts counts = new MutableCounts();
        String model = chooseValue(current.manufacturerPartNumber,
                candidate.manufacturerPartNumber, evidence, EvidenceMode.STANDALONE,
                isClearlyWrongModel(current.manufacturerPartNumber, current), policy, counts);
        if (!same(model, current.manufacturerPartNumber)
                && (isKnownNonModelValue(model, current)
                || (!clean(current.manufacturerPartNumber).isEmpty()
                && policy.allowEvidenceBackedCorrections
                && !looksLikeStrongModel(model)))) {
            model = current.manufacturerPartNumber;
            counts.undoChangeAsRejected();
        }
        String supplierCandidate = clean(candidate.supplierPartNumber);
        String supplierPart;
        if (!supplierCandidate.isEmpty()
                && !same(current.supplierPartNumber, supplierCandidate)
                && !looksLikeLcscPart(supplierCandidate)) {
            counts.rejected++;
            supplierPart = clean(current.supplierPartNumber);
        } else {
            supplierPart = chooseValue(current.supplierPartNumber,
                    supplierCandidate, evidence, EvidenceMode.STANDALONE,
                    !clean(current.supplierPartNumber).isEmpty()
                            && !looksLikeLcscPart(current.supplierPartNumber),
                    policy, counts);
        }

        Map<String, String> fields = new LinkedHashMap<>(current.fields);
        String categoryCandidate = clean(candidate.fields.get("品类"));
        String category = chooseCatalogValue(clean(fields.get("品类")), categoryCandidate,
                "category", "", evidence, matcher, policy, counts);
        putOrRemove(fields, "品类", category);

        String specification = chooseValue(clean(fields.get("规格")),
                candidate.fields.get("规格"), evidence, EvidenceMode.SUBSTRING,
                isClearlyPollutedText(fields.get("规格"), current), policy, counts);
        putOrRemove(fields, "规格", specification);
        String packageCode = chooseCatalogValue(clean(fields.get("封装")),
                candidate.fields.get("封装"), "package", category,
                evidence, matcher, policy, counts);
        putOrRemove(fields, "封装", packageCode);
        String brand = chooseCatalogValue(clean(fields.get("厂商")),
                candidate.fields.get("厂商"), "brand", category,
                evidence, matcher, policy, counts);
        putOrRemove(fields, "厂商", brand);
        String parameters = chooseValue(clean(fields.get("参数")),
                candidate.fields.get("参数"), evidence, EvidenceMode.SUBSTRING,
                isClearlyPollutedText(fields.get("参数"), current), policy, counts);
        putOrRemove(fields, "参数", parameters);

        if (!counts.changed()) {
            return new Result(current, 0, 0, counts.rejected);
        }
        String warning = appendWarning(current.warning,
                "Qwen text-only " + changeSummary(counts.filled, counts.corrected)
                        + (counts.rejected > 0 ? "；拒绝 " + counts.rejected + " 项无证据候选" : ""));
        String raw = current.raw + "\nQwen3.5 text-only:\n" + qwenOutput;
        SupplierFormat supplier = supplierPart.isEmpty() ? current.supplier : SupplierFormat.LCSC;
        ComponentRecord enriched = new ComponentRecord(supplier,
                current.recognized || !model.isEmpty() || !supplierPart.isEmpty(),
                current.confidence, current.orderNumber, supplierPart, model,
                current.quantity, current.trackingId, raw, fields, warning);
        return new Result(enriched, counts.filled, counts.corrected, counts.rejected);
    }

    private static String chooseValue(String currentValue, String candidateValue,
                                      String evidence, EvidenceMode evidenceMode,
                                      boolean currentObviouslyWrong,
                                      QwenSettings settings, MutableCounts counts) {
        String current = clean(currentValue);
        String candidate = clean(candidateValue);
        if (candidate.isEmpty() || same(current, candidate)) return current;
        boolean supported = supportsEvidence(evidence, candidate, evidenceMode);
        if (current.isEmpty()) {
            if (!supported) {
                counts.rejected++;
                return current;
            }
            counts.filled++;
            return candidate;
        }
        if (settings.allowEvidenceBackedCorrections && supported
                && (currentObviouslyWrong
                || !supportsEvidence(evidence, current, evidenceMode))) {
            counts.corrected++;
            return candidate;
        }
        return current;
    }

    private static String chooseCatalogValue(String currentValue, String candidateValue,
                                             String type, String category, String evidence,
                                             CategoryPackageMatcher matcher,
                                             QwenSettings settings, MutableCounts counts) {
        String candidate = clean(candidateValue);
        if (candidate.isEmpty() || same(currentValue, candidate)) return clean(currentValue);
        String canonical;
        if ("category".equals(type)) canonical = matcher.canonicalCategory(candidate);
        else if ("package".equals(type)) canonical = matcher.canonicalPackage(category, candidate);
        else canonical = matcher.canonicalBrand(category, candidate);
        if (settings.requireCatalogMatch && canonical.isEmpty()) {
            if (clean(currentValue).isEmpty()) counts.rejected++;
            return clean(currentValue);
        }
        String accepted = canonical.isEmpty() ? candidate : canonical;
        EvidenceMode evidenceMode = "package".equals(type)
                ? EvidenceMode.STANDALONE
                : "brand".equals(type) ? EvidenceMode.CATALOG_ALIAS : EvidenceMode.SUBSTRING;
        // 先按模型原始候选核验证据，再返回目录规范值；不能要求规范化后的中文/括号别名也逐字出现。
        boolean supported = supportsEvidence(evidence, candidate, evidenceMode);
        String current = clean(currentValue);
        if (current.isEmpty()) {
            if (!supported) {
                counts.rejected++;
                return current;
            }
            counts.filled++;
            return accepted;
        }
        String canonicalCurrent;
        if ("category".equals(type)) canonicalCurrent = matcher.canonicalCategory(current);
        else if ("package".equals(type)) canonicalCurrent = matcher.canonicalPackage(category, current);
        else canonicalCurrent = matcher.canonicalBrand(category, current);
        boolean currentObviouslyWrong = settings.requireCatalogMatch
                && !current.isEmpty() && canonicalCurrent.isEmpty();
        if (settings.allowEvidenceBackedCorrections && supported
                && (currentObviouslyWrong
                || !supportsEvidence(evidence, current, evidenceMode))) {
            counts.corrected++;
            return accepted;
        }
        return current;
    }

    private static boolean isClearlyWrongModel(String value, ComponentRecord record) {
        return isKnownNonModelValue(value, record) || isClearlyPollutedText(value, record);
    }

    private static boolean isKnownNonModelValue(String value, ComponentRecord record) {
        if (isRejectedIdentifier(value, record)) return true;
        return record != null && (sameNonEmpty(value, record.fields.get("品类"))
                || sameNonEmpty(value, record.fields.get("规格"))
                || sameNonEmpty(value, record.fields.get("封装"))
                || sameNonEmpty(value, record.fields.get("厂商"))
                || sameNonEmpty(value, record.fields.get("参数")));
    }

    private static boolean isRejectedIdentifier(String value, ComponentRecord record) {
        String candidate = clean(value);
        if (candidate.isEmpty() || record == null) return false;
        return sameNonEmpty(candidate, record.supplierPartNumber)
                || sameNonEmpty(candidate, record.orderNumber)
                || sameNonEmpty(candidate, record.trackingId)
                || sameNonEmpty(candidate, record.fields.get("内部编码"))
                || sameNonEmpty(candidate, record.fields.get("订单号"))
                || sameNonEmpty(candidate, record.fields.get("批次号"));
    }

    private static boolean isClearlyPollutedText(String value, ComponentRecord record) {
        String clean = clean(value);
        if (clean.isEmpty()) return false;
        String upper = clean.toUpperCase(Locale.ROOT);
        return upper.contains("ROHS") || upper.contains("QTY")
                || isRejectedIdentifier(clean, record);
    }

    private static boolean looksLikeLcscPart(String value) {
        return clean(value).matches("(?i)C\\d+");
    }

    private static boolean looksLikeStrongModel(String value) {
        boolean letter = false;
        boolean digit = false;
        String clean = clean(value);
        for (int index = 0; index < clean.length(); index++) {
            char character = clean.charAt(index);
            letter = letter || Character.isLetter(character);
            digit = digit || Character.isDigit(character);
        }
        return letter && digit;
    }

    private static boolean sameNonEmpty(String first, String second) {
        return !clean(second).isEmpty() && same(first, second);
    }

    private static String changeSummary(int filled, int corrected) {
        if (filled > 0 && corrected > 0) {
            return "安全补全 " + filled + " 项、修正明显错误 " + corrected + " 项";
        }
        if (corrected > 0) return "修正明显错误 " + corrected + " 项";
        return "安全补全 " + filled + " 项";
    }

    private static boolean supportsEvidence(String evidence, String candidate,
                                            EvidenceMode mode) {
        if (mode == EvidenceMode.STANDALONE) return supportsStandalone(evidence, candidate);
        if (mode == EvidenceMode.CATALOG_ALIAS) return supportsCatalogAlias(evidence, candidate);
        return supports(evidence, candidate);
    }

    private static boolean supports(String evidence, String candidate) {
        String normalizedCandidate = normalize(candidate);
        return normalizedCandidate.length() >= 2
                && normalize(evidence).contains(normalizedCandidate);
    }

    private static boolean supportsCatalogAlias(String evidence, String candidate) {
        if (supports(evidence, candidate)) return true;
        int open = firstParenthesis(candidate);
        int close = lastParenthesis(candidate);
        if (open > 0 && supports(evidence, candidate.substring(0, open))) return true;
        return open >= 0 && close > open + 1
                && supports(evidence, candidate.substring(open + 1, close));
    }

    /** 型号、立创料号和封装必须是独立值，不能只作为另一个长编码的子串出现。 */
    private static boolean supportsStandalone(String evidence, String candidate) {
        String needle = normalize(candidate);
        if (needle.length() < 2) return false;
        String normalizedEvidence = Normalizer.normalize(clean(evidence), Normalizer.Form.NFKC)
                .toUpperCase(Locale.ROOT);
        int from = 0;
        while (from <= normalizedEvidence.length() - needle.length()) {
            int match = normalizedEvidence.indexOf(needle, from);
            if (match < 0) break;
            int end = match + needle.length();
            boolean startsAtBoundary = match == 0
                    || !Character.isLetterOrDigit(normalizedEvidence.charAt(match - 1));
            boolean endsAtBoundary = end == normalizedEvidence.length()
                    || !Character.isLetterOrDigit(normalizedEvidence.charAt(end));
            if (startsAtBoundary && endsAtBoundary) return true;
            from = match + 1;
        }
        // 逐行兼容“封装0603”“型号ABC/1”等无冒号写法；逐行比较也允许忽略斜杠等版式符号。
        String[] labels = {"封装", "PACKAGE", "PKG", "型号", "MPN", "PARTNUMBER",
                "立创料号", "LCSC", "料号"};
        for (String line : clean(evidence).split("\\R")) {
            String compactLine = normalize(line);
            if (compactLine.equals(needle)) return true;
            for (String label : labels) {
                if (compactLine.equals(normalize(label) + needle)) return true;
            }
        }
        return false;
    }

    private static int firstParenthesis(String value) {
        int ascii = value.indexOf('(');
        int chinese = value.indexOf('（');
        if (ascii < 0) return chinese;
        if (chinese < 0) return ascii;
        return Math.min(ascii, chinese);
    }

    private static int lastParenthesis(String value) {
        return Math.max(value.lastIndexOf(')'), value.lastIndexOf('）'));
    }

    private static void putOrRemove(Map<String, String> fields, String key, String value) {
        if (clean(value).isEmpty()) fields.remove(key);
        else fields.put(key, value);
    }

    private static String appendWarning(String first, String second) {
        if (clean(first).isEmpty()) return clean(second);
        if (clean(second).isEmpty()) return clean(first);
        return clean(first) + "；" + clean(second);
    }

    private static boolean same(String first, String second) {
        return normalize(first).equals(normalize(second));
    }

    private static String normalize(String value) {
        return Normalizer.normalize(clean(value), Normalizer.Form.NFKC)
                .replaceAll("[^\\p{L}\\p{N}%+\\-.]", "")
                .toUpperCase(Locale.ROOT);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class MutableCounts {
        int filled;
        int corrected;
        int rejected;

        boolean changed() {
            return filled > 0 || corrected > 0;
        }

        void undoChangeAsRejected() {
            if (filled > 0) filled--;
            else if (corrected > 0) corrected--;
            rejected++;
        }
    }

    private enum EvidenceMode {
        SUBSTRING,
        STANDALONE,
        CATALOG_ALIAS
    }

    public static final class Result {
        public final ComponentRecord record;
        public final int filled;
        public final int corrected;
        public final int rejected;

        Result(ComponentRecord record, int filled, int corrected, int rejected) {
            this.record = record;
            this.filled = filled;
            this.corrected = corrected;
            this.rejected = rejected;
        }

        public boolean changed() {
            return filled > 0 || corrected > 0;
        }

        public String summary() {
            if (!changed()) {
                return rejected > 0 ? "未接受补全，拒绝 " + rejected + " 项无证据候选"
                        : "已完成校验，无需补全";
            }
            return changeSummary(filled, corrected)
                    + (rejected > 0 ? "，拒绝 " + rejected + " 项" : "");
        }
    }
}
