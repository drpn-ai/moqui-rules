package org.moqui.rules

import groovy.transform.CompileStatic
import org.codehaus.groovy.runtime.typehandling.DefaultTypeTransformation
import org.moqui.context.ExecutionContext
import org.moqui.entity.EntityValue

import java.sql.Timestamp
import java.util.regex.Pattern

/**
 * Converts the values of facts to the kinds their fact type's fields name, without a service call per value.
 * A value is converted by one branch per kind (text, whole number, decimal, yes/no, date and time); a value that does
 * not fit is reported as a problem and left out of the fact. A list field holds item facts, each converted with the
 * field's item fact type; item numbers in problem texts count from 1.
 *
 * Fields and their kinds are read once per fact type per converter: through Moqui's entity cache, which drops them when
 * a row changes, and then kept in a memo that lives only as long as the instance. There is no static cache, so make a
 * converter per call or batch and let it go.
 *
 * Statically compiled: every value of a batch passes through here, and dynamic dispatch on values of several classes
 * relinks its call sites value after value. Problem texts are built only when a value does not fit.
 */
@CompileStatic
class FactConverter {
    private static final Pattern NUMBER_TEXT = Pattern.compile(/[+-]?(\d+(\.\d*)?|\.\d+)/)
    private static final BigDecimal LONG_MAX = BigDecimal.valueOf(Long.MAX_VALUE)
    private static final List<String> SINGLE_VALUE_TYPES = Collections.unmodifiableList(["String", "Long", "BigDecimal", "Boolean", "Timestamp"])
    /** Marks a value that does not fit its type; never leaves this class. */
    private static final Object NO_FIT = new Object()

    private final ExecutionContext ec
    /** factTypeId to its fields, in the order the field rows are read, and their names. */
    private final Map<String, FactFields> fieldsByFactType = [:]

    FactConverter(ExecutionContext ec) { this.ec = ec }

    /**
     * One value to the Moqui type named typeName (String, Long, BigDecimal, Boolean or Timestamp). Gives
     * [convertedValue, problem]; problem is null when the value fits, and a null value fits any type as null.
     * A number is only digits with one optional sign and point, a whole number takes no fraction, yes/no takes only
     * true, false, y or n in any case, and a date and time must parse, in the signed-in user's time zone for text.
     */
    static Map convertValue(ExecutionContext ec, String typeName, Object value) {
        Map result = [convertedValue: null, problem: null]
        Object converted = convertOrNoFit(ec, typeName, value)
        if (converted.is(NO_FIT)) {
            String problem
            if (typeName == "Long") problem = "${value} is not a whole number".toString()
            else if (typeName == "BigDecimal") problem = "${value} is not a number".toString()
            else if (typeName == "Boolean") problem = "${value} is not yes or no".toString()
            else problem = "${value} is not a date and time".toString()
            result.put("problem", problem)
        } else {
            result.put("convertedValue", converted)
        }
        return result
    }

    /** The converted value, or NO_FIT when the value does not fit typeName. Same branches as convertValue states. */
    private static Object convertOrNoFit(ExecutionContext ec, String typeName, Object value) {
        if (value == null) return null
        // a number value always goes through its text, as it always did, so a number with no decimal form still fails here
        BigDecimal valueNumber = value instanceof Number ? new BigDecimal(textOf(value)) : null

        if (typeName == "String") return textOf(value)
        if (typeName == "Long") {
            BigDecimal number = valueNumber != null ? valueNumber : numberOfText(textOf(value).trim())
            if (number == null || number.stripTrailingZeros().scale() > 0 || number.abs().compareTo(LONG_MAX) > 0) return NO_FIT
            return Long.valueOf(number.longValue())
        }
        if (typeName == "BigDecimal") {
            BigDecimal number = valueNumber != null ? valueNumber : numberOfText(textOf(value).trim())
            return number != null ? number : NO_FIT
        }
        if (typeName == "Boolean") {
            if (value instanceof Boolean) return value
            String lower = textOf(value).trim().toLowerCase()
            if (lower == "true" || lower == "y") return Boolean.TRUE
            if (lower == "false" || lower == "n") return Boolean.FALSE
            return NO_FIT
        }
        if (value instanceof Timestamp) return value
        if (value instanceof Date) return new Timestamp(((Date) value).getTime())
        Timestamp parsed = ec.l10n.parseTimestamp(textOf(value).trim(), null)
        return parsed != null ? parsed : NO_FIT
    }

    private static BigDecimal numberOfText(String text) {
        return NUMBER_TEXT.matcher(text).matches() ? new BigDecimal(text) : null
    }

    /** The string form Groovy gives value.toString(): the plain one for strings, numbers, yes/no and dates, Groovy's for the rest (a map shows as [a:1]). */
    private static String textOf(Object value) {
        if (value instanceof String) return (String) value
        if (value instanceof Number || value instanceof Boolean || value instanceof Date) return String.valueOf(value)
        return value.toString()
    }

    /** Each entry [factTypeId, values] to [fact, problems], in the same order. Same as convertFact on each entry. */
    List<Map> convertAll(List<Map> entries) {
        List<Map> results = new ArrayList<>(entries.size())
        for (Map entry in entries) {
            results.add(convertFact((String) DefaultTypeTransformation.castToType(entry.get("factTypeId"), String),
                    (Map) DefaultTypeTransformation.castToType(entry.get("values"), Map)))
        }
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
        FactFields factFields = fieldsOf(factTypeId)
        String recordIdField = BuiltRuleSet.RECORD_ID_FIELD
        if (values.containsKey(recordIdField))
            problems.add("A fact of ${factTypeId} may not hold a value named ${recordIdField}; give the record id beside the fact".toString())
        for (Object name in values.keySet()) {
            if (name != recordIdField && !factFields.names.contains(name)) problems.add("Fact type ${factTypeId} has no field ${name}".toString())
        }

        for (FieldInfo field in factFields.fields) {
            String fieldName = field.fieldName
            Object value = values.get(fieldName)
            if (field.isList) {
                if (value != null && !(value instanceof List)) {
                    problems.add("Field ${fieldName} of ${factTypeId} must be a list".toString())
                    continue
                }
                List items = value == null ? null : []
                int itemNumber = 0
                if (value != null) for (Object itemValues in (List) value) {
                    itemNumber++
                    if (!(itemValues instanceof Map)) {
                        problems.add("Item ${itemNumber} of field ${fieldName} of ${factTypeId} must be a fact".toString())
                        continue
                    }
                    Map convertedItem = convertFact(field.itemFactTypeId, (Map) itemValues)
                    problems.addAll((List<String>) convertedItem.get("problems"))
                    items.add(convertedItem.get("fact"))
                }
                fact.put(fieldName, items)
                continue
            }
            // a kind that is no single value fails first, even with no value
            Object converted = field.typeName == null ? NO_FIT : convertOrNoFit(ec, field.typeName, value)
            if (converted.is(NO_FIT)) problems.add("Value ${value} does not fit field ${fieldName} of ${factTypeId}".toString())
            else fact.put(fieldName, converted)
        }
        return [fact: fact, problems: problems]
    }

    private FactFields fieldsOf(String factTypeId) {
        FactFields factFields = fieldsByFactType.get(factTypeId)
        if (factFields != null) return factFields
        List<FieldInfo> fields = []
        // no order-by on purpose: problems come in the order the field rows are read, as they always did
        for (EntityValue row in ec.entity.find("rules.FactField").condition("factTypeId", factTypeId).useCache(true).list()) {
            String valueTypeEnumId = (String) row.get("valueTypeEnumId")
            EntityValue kind = ec.entity.find("moqui.basic.Enumeration").condition("enumId", valueTypeEnumId).useCache(true).one()
            String enumCode = kind != null ? (String) kind.get("enumCode") : null
            String typeName = kind != null && kind.get("enumTypeId") == "FactValueType" && SINGLE_VALUE_TYPES.contains(enumCode) ? enumCode : null
            fields.add(new FieldInfo((String) row.get("fieldName"), valueTypeEnumId == "FVT_LIST", typeName, (String) row.get("itemFactTypeId")))
        }
        factFields = new FactFields(fields)
        fieldsByFactType.put(factTypeId, factFields)
        return factFields
    }

    /** One field row as conversion reads it. */
    private static final class FieldInfo {
        final String fieldName
        final boolean isList
        /** The single-value type the field's kind names, null when the kind holds no single value. */
        final String typeName
        final String itemFactTypeId

        FieldInfo(String fieldName, boolean isList, String typeName, String itemFactTypeId) {
            this.fieldName = fieldName
            this.isList = isList
            this.typeName = typeName
            this.itemFactTypeId = itemFactTypeId
        }
    }

    /** The fields of one fact type, in read order, and the set of their names. */
    private static final class FactFields {
        final List<FieldInfo> fields
        final Set<String> names

        FactFields(List<FieldInfo> fields) {
            this.fields = fields
            Set<String> fieldNames = new HashSet<>()
            for (FieldInfo field in fields) fieldNames.add(field.fieldName)
            this.names = fieldNames
        }
    }
}
