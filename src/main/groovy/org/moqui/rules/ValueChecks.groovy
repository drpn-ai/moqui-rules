package org.moqui.rules

import java.util.regex.Pattern

/**
 * What a line checks when a Moqui condition cannot say it. Three forms:
 * <ul>
 * <li>two fields of the same fact (fieldName, compareFieldName): equals within a tolerance, requires, required by, all in
 * and contains all. A side that was not read (null) claims nothing, where noted.</li>
 * <li>one field against fixed values (fieldName, values, normalizeText): =, !=, in, not in, like, not like. With
 * normalizeText, text is trimmed and upper-cased first and a value of only spaces is no value.</li>
 * <li>any (choices): some choice's full match holds, judged by ItemChecks.matches.</li>
 * </ul>
 */
class ValueChecks {
    /** True when the check, turned around when holds is false, is met by these fact values. */
    static boolean holds(Map check, Map values) {
        String kind = (String) check.kind
        boolean met
        if (kind == "any") met = ((List<Map>) check.choices).any { Map choice -> ItemChecks.matches(choice, values) }
        else if (check.containsKey("values")) met = fixedValue(kind, values.get(check.fieldName), (List) check.values, check.normalizeText == true)
        else met = evaluate(kind, values.get(check.fieldName), values.get(check.compareFieldName), (BigDecimal) check.tolerance)
        return met == (check.holds != false)
    }

    /** One fact value against fixed values. A missing value equals none of them and is like none of them. */
    static boolean fixedValue(String kind, Object value, List listed, boolean normalizeText) {
        Object fact = normalizeText ? normalizedText(value) : value
        List compareTo = normalizeText ? listed.collect { normalizedText(it) } : listed
        boolean like = kind == "like" || kind == "not like"
        boolean found = fact != null && compareTo.any { Object one ->
            one != null && (like ? likeMatches(fact.toString(), one.toString()) : (normalizeText ? fact == one : equalValues(fact, one))) }
        switch (kind) {
            case "=": case "in": case "like": return found
            case "!=": case "not in": case "not like": return !found
            default: throw new IllegalArgumentException("Unknown value check ${kind}".toString())
        }
    }

    /** Trimmed and upper-cased; null, or only spaces, is no value. */
    static String normalizedText(Object value) {
        String text = normalize(value)
        return text?.toUpperCase(Locale.ROOT)
    }

    /** A like pattern: % is any run of characters, _ is one character, everything else is itself. */
    static boolean likeMatches(String text, String pattern) {
        StringBuilder regex = new StringBuilder()
        for (char c in pattern.toCharArray()) {
            if (c == ('%' as char)) regex.append(".*")
            else if (c == ('_' as char)) regex.append(".")
            else regex.append(Pattern.quote(String.valueOf(c)))
        }
        return Pattern.compile(regex.toString(), Pattern.DOTALL).matcher(text).matches()
    }

    static boolean evaluate(String kind, Object a, Object b, BigDecimal tolerance) {
        switch (kind) {
            case "=": return equalValues(a, b)
            case "!=": return !equalValues(a, b)
            case "<": return compareValues(a, b) < 0
            case ">": return compareValues(a, b) > 0
            case "<=": return compareValues(a, b) <= 0
            case ">=": return compareValues(a, b) >= 0
            case "requires": return !isTrue(a) || isTrue(b)
            case "required by": return !isTrue(b) || isTrue(a)
            case "all in": return b == null || valueList(b).containsAll(valueList(a))
            case "contains all": return a == null || valueList(a).containsAll(valueList(b))
            case "equals within":
                BigDecimal x = toBigDecimal(a), y = toBigDecimal(b)
                if (tolerance != null && x != null && y != null) return (x - y).abs() < tolerance
                return equalValues(a, b)
            default: throw new IllegalArgumentException("Unknown value check ${kind}".toString())
        }
    }

    /** Y or true, any case. N, blank, null and anything else are not. */
    static boolean isTrue(Object value) {
        if (value instanceof Boolean) return (Boolean) value
        String text = normalize(value)?.toUpperCase(Locale.ROOT)
        return text == "Y" || text == "TRUE"
    }

    /** A list, or comma-joined text, as its trimmed non-blank values. */
    static Set<String> valueList(Object value) {
        if (value == null) return [] as Set
        Collection parts = value instanceof Collection ? (Collection) value : value.toString().split(",") as List
        return parts.collect { normalize(it) }.findAll { it } as Set
    }

    static boolean equalValues(Object left, Object right) {
        if (left == null || right == null) return Objects.equals(left, right)
        BigDecimal leftNumber = toBigDecimal(left), rightNumber = toBigDecimal(right)
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber) == 0
        // text is compared exactly: "SKU1 " is not "SKU1"
        return Objects.equals(left, right) || left.toString() == right.toString()
    }

    /** Numbers as BigDecimal, null before everything, anything else as exact text. */
    static int compareValues(Object left, Object right) {
        if (left == null || right == null) return left == right ? 0 : (left == null ? -1 : 1)
        BigDecimal leftNumber = toBigDecimal(left), rightNumber = toBigDecimal(right)
        if (leftNumber != null && rightNumber != null) return leftNumber.compareTo(rightNumber)
        return left.toString() <=> right.toString()
    }

    static BigDecimal toBigDecimal(Object value) {
        if (value instanceof BigDecimal) return (BigDecimal) value
        if (value instanceof Number) return new BigDecimal(value.toString())
        String text = normalize(value)
        if (!text || !(text ==~ /-?\d+(\.\d+)?/)) return null
        return new BigDecimal(text)
    }

    private static String normalize(Object value) {
        if (value == null) return null
        String text = value.toString().trim()
        return text.isEmpty() ? null : text
    }
}
