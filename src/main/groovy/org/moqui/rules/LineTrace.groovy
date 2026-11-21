package org.moqui.rules

/**
 * Says, line by line, whether a prepared line holds for one fact. It walks the same prepared match the decision
 * builder uses (ItemChecks.matches), so it can never disagree with evaluate. The words come from row fields only.
 */
class LineTrace {
    static Map trace(Map prepared, Map values) { return trace(prepared, values, [:]) }

    /** operatorWords: operator enum id to its description. A line under an exists line is judged against the list's items:
     *  it holds when some item meets it. */
    static Map trace(Map prepared, Map values, Map<String, String> operatorWords) {
        return traceOn(prepared, [values], operatorWords, false)
    }

    private static Map traceOn(Map prepared, List<Map> subjects, Map<String, String> operatorWords, boolean underExists) {
        Map row = (Map) prepared.row ?: [:]
        String typeId = (String) row.conditionTypeId
        Map match = (Map) prepared.matches
        // under an exists line the subjects are the items, and the line holds when one of them meets it
        boolean holds = match != null && (underExists ? subjects.any { Map subject -> ItemChecks.matches(match, subject) }
                : ItemChecks.matches(match, subjects[0]))

        List<Map> childLines = (List<Map>) (typeId == "RCT_PIECE" ? prepared.innerLines : prepared.children) ?: []
        List<Map> children
        if (typeId == "RCT_EXISTS") {
            List<Map> items = (List<Map>) subjects[0].get(row.fieldName) ?: []
            children = childLines.collect { traceOn(it, items, operatorWords, true) }
        } else {
            children = childLines.collect { traceOn(it, subjects, operatorWords, underExists) }
        }
        return [conditionSeqId: row.conditionSeqId, conditionTypeId: typeId, text: textOf(row, operatorWords),
                holds: holds, lines: children]
    }

    static String textOf(Map row, Map<String, String> operatorWords) {
        String operator = operatorWords?.get(row.operatorEnumId) ?: row.operatorEnumId
        switch ((String) row.conditionTypeId) {
            case "RCT_COMPARE":
                if (row.operatorEnumId in ["ENTCO_IN", "ENTCO_NOT_IN"]) {
                    Object listed = null
                    try { listed = new groovy.json.JsonSlurper().parseText(row.compareValue?.toString() ?: "") } catch (Exception ignored) { }
                    String words = row.operatorEnumId == "ENTCO_IN" ? "is one of" : "is not one of"
                    return "${row.fieldName} ${words} ${listed instanceof List ? listed.join(', ') : row.compareValue}".toString()
                }
                return [row.fieldName, operator, row.compareValue].findAll { it != null && it.toString() != "" }.join(" ")
            case "RCT_COMPARE_FIELD":
                String text = "${row.fieldName} ${operator} ${row.compareFieldName}".toString()
                if (row.tolerance != null && row.tolerance.toString().trim() != "") text += " (within ${row.tolerance})".toString()
                return text
            case "RCT_PIECE": return "piece ${row.compareValue}".toString()
            case "RCT_ALL": return "all of"
            case "RCT_ANY": return "any of"
            case "RCT_NOT": return "none of"
            case "RCT_EXISTS": return "one ${row.fieldName} item where".toString()
            default: return (String) row.conditionTypeId
        }
    }
}
