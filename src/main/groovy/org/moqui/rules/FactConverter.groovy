package org.moqui.rules

import org.moqui.context.ExecutionContext

import java.sql.Timestamp

/**
 * Converts the values of facts to the kinds their fact type's fields name, without a service call per value.
 * A value is converted by one branch per kind (text, whole number, decimal, yes/no, date and time); a value that does
 * not fit is reported as a problem and left out of the fact. A list field holds item facts, each converted with the
 * field's item fact type; item numbers in problem texts count from 1.
 *
 * Fields and their kinds are read once per fact type per converter: through Moqui's entity cache, which drops them when
 * a row changes, and then kept in a memo that lives only as long as the instance. There is no static cache, so make a
 * converter per call or batch and let it go.
 */
class FactConverter {
    private final ExecutionContext ec
    /** factTypeId to one map per field, in the order the field rows are read: fieldName, valueTypeEnumId, typeName, itemFactTypeId. */
    private final Map<String, List<Map>> fieldsByFactType = [:]

    FactConverter(ExecutionContext ec) { this.ec = ec }

    /**
     * One value to the Moqui type named typeName (String, Long, BigDecimal, Boolean or Timestamp). Gives
     * [convertedValue, problem]; problem is null when the value fits, and a null value fits any type as null.
     * A number is only digits with one optional sign and point, a whole number takes no fraction, yes/no takes only
     * true, false, y or n in any case, and a date and time must parse, in the signed-in user's time zone for text.
     */
    static Map convertValue(ExecutionContext ec, String typeName, Object value) {
        Map result = [convertedValue: null, problem: null]
        if (value == null) return result
        String text = value.toString().trim()
        BigDecimal number = value instanceof Number ? new BigDecimal(value.toString()) :
                text ==~ /[+-]?(\d+(\.\d*)?|\.\d+)/ ? new BigDecimal(text) : null

        if (typeName == "String") {
            result.convertedValue = value.toString()
        } else if (typeName == "Long") {
            if (number == null || number.stripTrailingZeros().scale() > 0 || number.abs() > Long.MAX_VALUE) result.problem = "${value} is not a whole number".toString()
            else result.convertedValue = number.longValue()
        } else if (typeName == "BigDecimal") {
            result.convertedValue = number
            if (number == null) result.problem = "${value} is not a number".toString()
        } else if (typeName == "Boolean") {
            result.convertedValue = value instanceof Boolean ? value : ['true': true, 'y': true, 'false': false, 'n': false].get(text.toLowerCase())
            if (result.convertedValue == null) result.problem = "${value} is not yes or no".toString()
        } else {
            result.convertedValue = value instanceof Timestamp ? value : value instanceof Date ? new Timestamp(((Date) value).time) : ec.l10n.parseTimestamp(text, null)
            if (result.convertedValue == null) result.problem = "${value} is not a date and time".toString()
        }
        return result
    }

    /** Each entry [factTypeId, values] to [fact, problems], in the same order. Same as convertFact on each entry. */
    List<Map> convertAll(List<Map> entries) {
        List<Map> results = []
        for (Map entry in entries) results.add(convertFact((String) entry.factTypeId, (Map) entry.values))
        return results
    }

    /**
     * The values of one fact checked against the fields of its fact type and converted. Gives [fact, problems]: fact
     * holds every field of the type (null for a field with no value, nothing for a value that does not fit), problems
     * lists everything wrong at once, in order: a reserved record id key, unknown names, then the fields in field order.
     */
    Map convertFact(String factTypeId, Map values) {
        List<String> problems = []
        Map fact = [:]
        values = values ?: [:]
        List<Map> fields = fieldsOf(factTypeId)
        String recordIdField = BuiltRuleSet.RECORD_ID_FIELD
        if (values.containsKey(recordIdField))
            problems.add("A fact of ${factTypeId} may not hold a value named ${recordIdField}; give the record id beside the fact".toString())
        Set knownNames = new HashSet(fields*.fieldName)
        for (Object name in values.keySet()) {
            if (name != recordIdField && !knownNames.contains(name)) problems.add("Fact type ${factTypeId} has no field ${name}".toString())
        }

        for (Map field in fields) {
            String fieldName = (String) field.fieldName
            Object value = values.get(fieldName)
            if (field.valueTypeEnumId == "FVT_LIST") {
                if (value != null && !(value instanceof List)) {
                    problems.add("Field ${fieldName} of ${factTypeId} must be a list".toString())
                    continue
                }
                List items = value == null ? null : []
                int itemNumber = 0
                for (Object itemValues in (value ?: [])) {
                    itemNumber++
                    if (!(itemValues instanceof Map)) {
                        problems.add("Item ${itemNumber} of field ${fieldName} of ${factTypeId} must be a fact".toString())
                        continue
                    }
                    Map convertedItem = convertFact((String) field.itemFactTypeId, (Map) itemValues)
                    problems.addAll((List) convertedItem.problems)
                    items.add(convertedItem.fact)
                }
                fact.put(fieldName, items)
                continue
            }
            // a kind that is no single value fails first, even with no value
            Map converted = field.typeName == null ? [convertedValue: null, problem: "no single value"] :
                    convertValue(ec, (String) field.typeName, value)
            if (converted.problem) problems.add("Value ${value} does not fit field ${fieldName} of ${factTypeId}".toString())
            else fact.put(fieldName, converted.convertedValue)
        }
        return [fact: fact, problems: problems]
    }

    private List<Map> fieldsOf(String factTypeId) {
        List<Map> fields = fieldsByFactType.get(factTypeId)
        if (fields != null) return fields
        fields = []
        // no order-by on purpose: problems come in the order the field rows are read, as they always did
        for (def row in ec.entity.find("rules.FactField").condition("factTypeId", factTypeId).useCache(true).list()) {
            def kind = ec.entity.find("moqui.basic.Enumeration").condition("enumId", row.valueTypeEnumId).useCache(true).one()
            String typeName = kind?.enumTypeId == "FactValueType" && kind.enumCode in ["String", "Long", "BigDecimal", "Boolean", "Timestamp"] ? kind.enumCode : null
            fields.add([fieldName: row.fieldName, valueTypeEnumId: row.valueTypeEnumId, typeName: typeName, itemFactTypeId: row.itemFactTypeId])
        }
        fieldsByFactType.put(factTypeId, fields)
        return fields
    }
}
