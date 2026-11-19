package org.moqui.rules

/**
 * A line that compares one field of a fact with another field of the same fact, when a Moqui condition cannot say it:
 * equals within a tolerance, requires, required by, all in and contains all. The truth table is the one Darpan's
 * reconciliation rules have always used. A side that was not read (null) claims nothing, where noted.
 */
class ValueChecks {
    /** True when the check, turned around when holds is false, is met by these fact values. */
    static boolean holds(Map check, Map values) {
        Object a = values.get(check.fieldName), b = values.get(check.compareFieldName)
        return evaluate((String) check.kind, a, b, (BigDecimal) check.tolerance) == (check.holds != false)
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
        // text is compared exactly, as Darpan compares it: "SKU1 " is not "SKU1"
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
