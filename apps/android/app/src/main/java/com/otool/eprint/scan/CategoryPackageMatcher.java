package com.otool.eprint.scan;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 立创离线品类、品牌和封装匹配器。目录命中既可规范化 OCR 结果，也可为双通道融合
 * 提供强证据；封装仍优先受已匹配品类约束，避免把型号中的四位数字误填为封装。
 */
public final class CategoryPackageMatcher {
    private static final Pattern PACKAGE_PREFIX = Pattern.compile(
            "^(?:封装(?:/规格)?|PACKAGE|PKG)\\s*[:：]?\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern CATEGORY_PREFIX = Pattern.compile(
            "^(?:品类|类别|CATEGORY)\\s*[:：]?\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BRAND_PREFIX = Pattern.compile(
            "^(?:品牌|厂商|制造商|BRAND|MFR|MANUFACTURER)\\s*[:：]?\\s*",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> AMBIGUOUS_PACKAGES;

    static {
        Set<String> values = new HashSet<>();
        Collections.addAll(values, "", "-", "--", "无", "其他", "其它", "N/A", "NA");
        AMBIGUOUS_PACKAGES = Collections.unmodifiableSet(values);
    }

    /** 单个立创末级品类及其可用品牌、封装。 */
    public static final class CategoryRule {
        public final String name;
        public final String nameEn;
        public final String parentName;
        public final String rootName;
        public final List<String> packages;
        public final List<String> brands;

        /** 兼容旧测试/调用方的品类—封装构造器。 */
        public CategoryRule(String name, String parentName, String rootName,
                            List<String> packages) {
            this(name, "", parentName, rootName, packages, Collections.emptyList());
        }

        public CategoryRule(String name, String nameEn, String parentName, String rootName,
                            List<String> packages, List<String> brands) {
            this.name = clean(name);
            this.nameEn = clean(nameEn);
            this.parentName = clean(parentName);
            this.rootName = clean(rootName);
            this.packages = immutableCopy(packages);
            this.brands = immutableCopy(brands);
        }

        private static List<String> immutableCopy(List<String> values) {
            return Collections.unmodifiableList(new ArrayList<>(
                    values == null ? Collections.emptyList() : values));
        }
    }

    private static final CategoryPackageMatcher EMPTY =
            new CategoryPackageMatcher(Collections.emptyList());

    private final List<IndexedRule> rules;
    private final Map<String, String> globalPackages;
    private final Map<String, String> brandAliases;

    public CategoryPackageMatcher(List<CategoryRule> sourceRules) {
        List<IndexedRule> indexed = new ArrayList<>();
        Map<String, String> packages = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        Set<String> ambiguousBrandAliases = new HashSet<>();
        if (sourceRules != null) {
            for (CategoryRule source : sourceRules) {
                if (source == null || source.name.isEmpty()) continue;
                indexed.add(new IndexedRule(source));
                for (String packageName : source.packages) {
                    String normalized = normalizePackage(packageName);
                    if (!AMBIGUOUS_PACKAGES.contains(normalized)) {
                        packages.putIfAbsent(normalized, packageName);
                    }
                }
                for (String brand : source.brands) {
                    addBrandAliases(aliases, ambiguousBrandAliases, brand);
                }
            }
        }
        for (String ambiguous : ambiguousBrandAliases) aliases.remove(ambiguous);
        rules = Collections.unmodifiableList(indexed);
        globalPackages = Collections.unmodifiableMap(packages);
        brandAliases = Collections.unmodifiableMap(aliases);
    }

    public static CategoryPackageMatcher empty() {
        return EMPTY;
    }

    /** 当前 OCR 品类是否能落到目录中的一个或多个末级/父级规则。 */
    public boolean hasCategory(String category) {
        return !matchingRules(category).isEmpty();
    }

    /** 将一个足够明确的中英文品类写法规范为立创末级品类名；歧义时返回空。 */
    public String canonicalCategory(String category) {
        String normalized = normalizeCategory(stripPrefix(category, CATEGORY_PREFIX));
        if (normalized.isEmpty()) return "";
        List<ScoredRule> matches = scoredRules(normalized);
        if (matches.isEmpty() || matches.get(0).tier < 3) return "";
        int bestTier = matches.get(0).tier;
        String selected = "";
        String selectedNormalized = "";
        for (ScoredRule match : matches) {
            if (match.tier != bestTier) break;
            String candidate = match.rule.source.name;
            String candidateNormalized = normalizeCategory(candidate);
            if (selected.isEmpty()) {
                selected = candidate;
                selectedNormalized = candidateNormalized;
            } else if (!selectedNormalized.equals(candidateNormalized)) {
                return "";
            }
        }
        return selected;
    }

    /** 从独立 OCR 行中寻找可无歧义规范化的品类。 */
    public String findCategory(List<String> lines) {
        if (lines == null) return "";
        for (String line : lines) {
            String matched = canonicalCategory(line);
            if (!matched.isEmpty()) return matched;
        }
        return "";
    }

    /** 全局品牌字典规范化；支持规范全名、英文名和括号内中文别名。 */
    public String canonicalBrand(String value) {
        String normalized = normalizeBrand(stripPrefix(value, BRAND_PREFIX));
        String matched = brandAliases.get(normalized);
        return matched == null ? "" : matched;
    }

    /** 品类已知时额外要求该品牌确实属于该品类；品类未知时使用全局品牌字典。 */
    public String canonicalBrand(String category, String value) {
        String canonical = canonicalBrand(value);
        if (canonical.isEmpty()) return "";
        List<IndexedRule> matchedRules = matchingRules(category);
        if (matchedRules.isEmpty()) return canonical;
        String normalized = normalizeBrand(canonical);
        for (IndexedRule rule : matchedRules) {
            if (rule.brands.contains(normalized)) return canonical;
        }
        return "";
    }

    public String findBrand(String category, List<String> lines) {
        if (lines == null) return "";
        for (String line : lines) {
            String matched = canonicalBrand(category, line);
            if (!matched.isEmpty()) return matched;
        }
        return "";
    }

    /**
     * 从 OCR 行中寻找当前品类允许的封装。优先接受独立行或带“封装:”前缀的值，
     * 不在普通型号长串中做子串搜索。
     */
    public String findPackage(String category, List<String> lines) {
        List<IndexedRule> matchedRules = matchingRules(category);
        if (matchedRules.isEmpty() || lines == null || lines.isEmpty()) return "";
        Map<String, String> allowed = allowedPackages(matchedRules);
        if (allowed.isEmpty()) return "";

        for (String rawLine : lines) {
            String line = clean(rawLine);
            if (line.isEmpty()) continue;
            String candidate = stripPrefix(line, PACKAGE_PREFIX);
            candidate = trimOuterPunctuation(candidate);
            String matched = allowed.get(normalizePackage(candidate));
            if (matched != null) return matched;
        }
        return "";
    }

    /** 品类已知时按该品类校验封装，否则只做全局精确封装规范化。 */
    public String canonicalPackage(String category, String value) {
        String candidate = trimOuterPunctuation(stripPrefix(value, PACKAGE_PREFIX));
        String normalized = normalizePackage(candidate);
        if (AMBIGUOUS_PACKAGES.contains(normalized)) return "";
        List<IndexedRule> matchedRules = matchingRules(category);
        Map<String, String> allowed = matchedRules.isEmpty()
                ? globalPackages : allowedPackages(matchedRules);
        String matched = allowed.get(normalized);
        return matched == null ? "" : matched;
    }

    private static Map<String, String> allowedPackages(List<IndexedRule> matchedRules) {
        Map<String, String> allowed = new LinkedHashMap<>();
        for (IndexedRule rule : matchedRules) {
            for (String packageName : rule.source.packages) {
                String normalized = normalizePackage(packageName);
                if (!AMBIGUOUS_PACKAGES.contains(normalized)) {
                    allowed.putIfAbsent(normalized, packageName);
                }
            }
        }
        return allowed;
    }

    private List<IndexedRule> matchingRules(String category) {
        String normalized = normalizeCategory(stripPrefix(category, CATEGORY_PREFIX));
        if (normalized.isEmpty()) return Collections.emptyList();
        List<ScoredRule> scored = scoredRules(normalized);
        if (scored.isEmpty()) return Collections.emptyList();
        int selectedTier = scored.get(0).tier;
        List<IndexedRule> selected = new ArrayList<>();
        for (ScoredRule value : scored) {
            if (value.tier != selectedTier) break;
            selected.add(value.rule);
        }
        return selected;
    }

    private List<ScoredRule> scoredRules(String normalizedCategory) {
        List<ScoredRule> scored = new ArrayList<>();
        for (IndexedRule rule : rules) {
            int tier = matchTier(normalizedCategory, rule);
            if (tier > 0) scored.add(new ScoredRule(rule, tier));
        }
        scored.sort(Comparator.comparingInt((ScoredRule value) -> value.tier).reversed()
                .thenComparing(Comparator.comparingInt(
                        (ScoredRule value) -> value.rule.name.length()).reversed()));
        return scored;
    }

    private static int matchTier(String category, IndexedRule rule) {
        if (category.equals(rule.name) || (!rule.nameEn.isEmpty() && category.equals(rule.nameEn))) {
            return 5;
        }
        if (containsAlias(category, rule.name) || containsAlias(category, rule.nameEn)) return 4;
        if (containsAlias(rule.name, category) || containsAlias(rule.nameEn, category)) return 3;
        if (!rule.parent.isEmpty()
                && (category.equals(rule.parent) || category.contains(rule.parent))) return 2;
        if (!rule.root.isEmpty()
                && (category.equals(rule.root) || category.contains(rule.root))) return 1;
        return 0;
    }

    private static boolean containsAlias(String value, String alias) {
        return !alias.isEmpty() && alias.length() >= 2 && value.contains(alias);
    }

    private static void addBrandAliases(Map<String, String> aliases, Set<String> ambiguous,
                                        String canonical) {
        String cleaned = clean(canonical);
        if (cleaned.isEmpty()) return;
        addBrandAlias(aliases, ambiguous, cleaned, cleaned);
        int open = firstParenthesis(cleaned);
        int close = lastParenthesis(cleaned);
        if (open > 0) addBrandAlias(aliases, ambiguous, cleaned.substring(0, open), cleaned);
        if (open >= 0 && close > open + 1) {
            addBrandAlias(aliases, ambiguous, cleaned.substring(open + 1, close), cleaned);
        }
    }

    private static void addBrandAlias(Map<String, String> aliases, Set<String> ambiguous,
                                      String alias, String canonical) {
        String normalized = normalizeBrand(alias);
        if (normalized.length() < 2 || ambiguous.contains(normalized)) return;
        String previous = aliases.get(normalized);
        if (previous == null || previous.equals(canonical)) {
            aliases.put(normalized, canonical);
        } else {
            aliases.remove(normalized);
            ambiguous.add(normalized);
        }
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

    private static String normalizeCategory(String value) {
        return normalizeLettersAndDigits(value);
    }

    private static String normalizeBrand(String value) {
        return normalizeLettersAndDigits(value);
    }

    private static String normalizeLettersAndDigits(String value) {
        return Normalizer.normalize(clean(value), Normalizer.Form.NFKC)
                .replaceAll("[^\\p{L}\\p{N}]", "")
                .toUpperCase(Locale.ROOT);
    }

    private static String normalizePackage(String value) {
        return Normalizer.normalize(clean(value), Normalizer.Form.NFKC)
                .replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);
    }

    private static String stripPrefix(String value, Pattern prefix) {
        return prefix.matcher(clean(value)).replaceFirst("");
    }

    private static String trimOuterPunctuation(String value) {
        return clean(value).replaceAll("^[,，;；]+|[,，;；]+$", "").trim();
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static final class IndexedRule {
        final CategoryRule source;
        final String name;
        final String nameEn;
        final String parent;
        final String root;
        final Set<String> brands;

        IndexedRule(CategoryRule source) {
            this.source = source;
            name = normalizeCategory(source.name);
            nameEn = normalizeCategory(source.nameEn);
            parent = normalizeCategory(source.parentName);
            root = normalizeCategory(source.rootName);
            Set<String> normalizedBrands = new HashSet<>();
            for (String brand : source.brands) normalizedBrands.add(normalizeBrand(brand));
            brands = Collections.unmodifiableSet(normalizedBrands);
        }
    }

    private static final class ScoredRule {
        final IndexedRule rule;
        final int tier;

        ScoredRule(IndexedRule rule, int tier) {
            this.rule = rule;
            this.tier = tier;
        }
    }
}
