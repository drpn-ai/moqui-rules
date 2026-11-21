import java.sql.Timestamp
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

/**
 * Pins what fact conversion gives today, value for value: the converted value and its class, the problem text,
 * and the problems of a whole fact in order. The expected values are written out, never computed by the code
 * under test, so a rewrite of the conversion can be checked against this table.
 *
 * The date and time rows from text depend on the time zone Moqui parses in: the signed-in user's own zone (else
 * the JVM's). The test user john.doe is in a zone six hours behind UTC in November, so "2026-11-03 10:15:00" is
 * 2026-11-03 16:15:00 UTC, the epoch millis written below. Not the JVM zone: Timestamp.valueOf would be wrong here.
 */
class FactConversionParityTests extends Specification {
    @Shared ExecutionContext ec

    static final Timestamp STAMP = new Timestamp(1700000000123L)

    /** kind | value || expected converted value | expected problem. A problem row has a null converted value. */
    static final List<List> PARITY_CASES = [
        // text: anything stays as it came, the string form of a non-string
        ["FVT_TEXT", null, null, null],
        ["FVT_TEXT", "", "", null],
        ["FVT_TEXT", "  ", "  ", null],
        ["FVT_TEXT", "abc", "abc", null],
        ["FVT_TEXT", " 12 ", " 12 ", null],
        ["FVT_TEXT", 12, "12", null],
        ["FVT_TEXT", true, "true", null],
        // integer: a whole number only, padding trimmed, a zero fraction allowed
        ["FVT_INTEGER", null, null, null],
        ["FVT_INTEGER", "", null, " is not a whole number"],
        ["FVT_INTEGER", "  ", null, "   is not a whole number"],
        ["FVT_INTEGER", "12", 12L, null],
        ["FVT_INTEGER", " 12 ", 12L, null],
        ["FVT_INTEGER", "-12", -12L, null],
        ["FVT_INTEGER", "12.0", 12L, null],
        ["FVT_INTEGER", "12.5", null, "12.5 is not a whole number"],
        ["FVT_INTEGER", "abc", null, "abc is not a whole number"],
        ["FVT_INTEGER", "+.5", null, "+.5 is not a whole number"],
        ["FVT_INTEGER", "1e3", null, "1e3 is not a whole number"],
        ["FVT_INTEGER", "9223372036854775807", Long.MAX_VALUE, null],
        ["FVT_INTEGER", "9223372036854775808", null, "9223372036854775808 is not a whole number"],
        ["FVT_INTEGER", 12, 12L, null],
        ["FVT_INTEGER", 12.5d, null, "12.5 is not a whole number"],
        ["FVT_INTEGER", true, null, "true is not a whole number"],
        // decimal: digits with one optional sign and point; the scale of the text is kept
        ["FVT_DECIMAL", null, null, null],
        ["FVT_DECIMAL", "", null, " is not a number"],
        ["FVT_DECIMAL", "  ", null, "   is not a number"],
        ["FVT_DECIMAL", "40.50", new BigDecimal("40.50"), null],
        ["FVT_DECIMAL", " 12 ", new BigDecimal("12"), null],
        ["FVT_DECIMAL", "+.5", new BigDecimal("0.5"), null],
        ["FVT_DECIMAL", "5.", new BigDecimal("5"), null],
        ["FVT_DECIMAL", "abc", null, "abc is not a number"],
        ["FVT_DECIMAL", "1e3", null, "1e3 is not a number"],
        ["FVT_DECIMAL", 12, new BigDecimal("12"), null],
        ["FVT_DECIMAL", 3, new BigDecimal("3"), null],
        ["FVT_DECIMAL", 12.5d, new BigDecimal("12.5"), null],
        // boolean: true, false, y, n in any case, padding trimmed; a Boolean stays
        ["FVT_BOOLEAN", null, null, null],
        ["FVT_BOOLEAN", "", null, " is not yes or no"],
        ["FVT_BOOLEAN", "Y", true, null],
        ["FVT_BOOLEAN", "n", false, null],
        ["FVT_BOOLEAN", "True ", true, null],
        ["FVT_BOOLEAN", "FALSE", false, null],
        ["FVT_BOOLEAN", "maybe", null, "maybe is not yes or no"],
        ["FVT_BOOLEAN", "yes", null, "yes is not yes or no"],
        ["FVT_BOOLEAN", 1, null, "1 is not yes or no"],
        ["FVT_BOOLEAN", true, true, null],
        ["FVT_BOOLEAN", false, false, null],
        // date and time: a Timestamp stays, a Date keeps its instant, text goes through Moqui's parser
        ["FVT_DATE_TIME", null, null, null],
        ["FVT_DATE_TIME", "", null, " is not a date and time"],
        ["FVT_DATE_TIME", "  ", null, "   is not a date and time"],
        // zone: text is read in john.doe's seeded zone (six hours behind UTC in November), so 10:15 there is 16:15 UTC
        ["FVT_DATE_TIME", "2026-11-03 10:15:00", new Timestamp(1793722500000L), null],
        ["FVT_DATE_TIME", " 2026-11-03 10:15:00 ", new Timestamp(1793722500000L), null],
        ["FVT_DATE_TIME", new Date(1700000000000L), new Timestamp(1700000000000L), null],
        ["FVT_DATE_TIME", STAMP, STAMP, null],
        ["FVT_DATE_TIME", "not a date", null, "not a date is not a date and time"],
    ]

    static final Map NULL_FACT = [amount: null, flag: null, items: null, label: null, qty: null, stamp: null]
    static final String RECORD_ID_PROBLEM = "A fact of ParityFact may not hold a value named _recordId; give the record id beside the fact"

    /** name | values || fact | problems, for the fact type ParityFact (items hold ParityItem). */
    static final List<List> FACT_CASES = [
        ["no values", [:], NULL_FACT, []],
        ["values null", null, NULL_FACT, []],
        ["record id key", [_recordId: "r1"], NULL_FACT, [RECORD_ID_PROBLEM]],
        ["two unknown names, key order", [zeta: 1, alpha: 2], NULL_FACT,
                ["Fact type ParityFact has no field zeta", "Fact type ParityFact has no field alpha"]],
        ["every kind good", [label: "a", qty: "7", amount: "1.50", flag: "Y", stamp: STAMP, items: [[name: "x", count: "2"]]],
                [label: "a", qty: 7L, amount: new BigDecimal("1.50"), flag: true, stamp: STAMP, items: [[name: "x", count: 2L]]], []],
        ["blank to text stays text, blank to number is a problem", [label: "", qty: " "], NULL_FACT - [qty: null] + [label: ""],
                ["Value   does not fit field qty of ParityFact"]],
        ["bad value is left out of the fact", [qty: "x"], NULL_FACT - [qty: null], ["Value x does not fit field qty of ParityFact"]],
        ["bad value of a field listed first", [qty: "x", amount: "y"], NULL_FACT - [qty: null, amount: null],
                ["Value y does not fit field amount of ParityFact", "Value x does not fit field qty of ParityFact"]],
        ["record id, unknown name, then bad values in field order", [qty: "x", _recordId: "r", amount: "y", zeta: 1], NULL_FACT - [qty: null, amount: null],
                [RECORD_ID_PROBLEM, "Fact type ParityFact has no field zeta",
                 "Value y does not fit field amount of ParityFact", "Value x does not fit field qty of ParityFact"]],
        ["items null", [items: null], NULL_FACT, []],
        ["items empty", [items: []], NULL_FACT + [items: []], []],
        ["items a string", [items: "abc"], NULL_FACT - [items: null], ["Field items of ParityFact must be a list"]],
        ["items holding a non-map", [items: [[name: "a"], "oops"]], NULL_FACT + [items: [[name: "a", count: null]]],
                ["Item 2 of field items of ParityFact must be a fact"]],
        ["items holding null", [items: [null, [name: "b"]]], NULL_FACT + [items: [[name: "b", count: null]]],
                ["Item 1 of field items of ParityFact must be a fact"]],
        ["items, one good and one bad item", [items: [[name: "a", count: "1"], [name: "b", count: "zz"]]],
                NULL_FACT + [items: [[name: "a", count: 1L], [name: "b"]]], ["Value zz does not fit field count of ParityItem"]],
        ["item with a record id and an unknown name", [items: [[_recordId: "r", bogus: 1]]], NULL_FACT + [items: [[name: null, count: null]]],
                ["A fact of ParityItem may not hold a value named _recordId; give the record id beside the fact",
                 "Fact type ParityItem has no field bogus"]],
        ["problems of the fact and of its items keep field order", [qty: "x", items: [[count: "q"]], amount: "y"],
                NULL_FACT - [qty: null, amount: null] + [items: [[name: null]]],
                ["Value y does not fit field amount of ParityFact", "Value q does not fit field count of ParityItem",
                 "Value x does not fit field qty of ParityFact"]],
    ]

    def setupSpec() {
        ec = Moqui.getExecutionContext(); RulesTestData.ensureLoaded(ec)
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeTestData()
        createFactType("ParityItem", [name: "FVT_TEXT", count: "FVT_INTEGER"])
        createFactType("ParityFact", [label: "FVT_TEXT", qty: "FVT_INTEGER", amount: "FVT_DECIMAL", flag: "FVT_BOOLEAN",
                stamp: "FVT_DATE_TIME", items: "FVT_LIST"])
        ec.entity.makeValue("rules.FactField").setAll([factTypeId: "ParityFact", fieldName: "items", valueTypeEnumId: "FVT_LIST",
                itemFactTypeId: "ParityItem"]).update()
        // a field whose kind is neither a single value nor a list
        createFactType("ParityOdd", [odd: "FNT_RECORD"])
    }

    def cleanupSpec() {
        removeTestData()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    static final List<String> TEST_FACT_TYPES = ["ParityFact", "ParityItem", "ParityOdd"]

    void removeTestData() {
        for (String factTypeId in TEST_FACT_TYPES) {
            ec.entity.find("rules.FactField").condition("factTypeId", factTypeId).deleteAll()
            ec.entity.find("rules.FactType").condition("factTypeId", factTypeId).deleteAll()
        }
    }

    void createFactType(String factTypeId, Map<String, String> fieldKinds) {
        ec.entity.makeValue("rules.FactType").setAll([factTypeId: factTypeId]).create()
        // created in name order: convert#Fact reads fields with no order-by, so this is the order problems come in
        fieldKinds.sort().each { String fieldName, String valueTypeEnumId ->
            ec.entity.makeValue("rules.FactField").setAll([factTypeId: factTypeId, fieldName: fieldName, valueTypeEnumId: valueTypeEnumId]).create()
        }
    }

    Map convert(String valueTypeEnumId, Object value) {
        ec.service.sync().name("moqui.rules.FactValueServices.convert#FactValue")
                .parameters([valueTypeEnumId: valueTypeEnumId, value: value]).call()
    }

    /** Both outputs of convert#Fact: [fact: Map, problems: List]. */
    Map convertFact(String factTypeId, Map values) {
        Map result = ec.service.sync().name("moqui.rules.RuleServices.convert#Fact")
                .parameters([factTypeId: factTypeId, values: values]).call()
        return [fact: result.fact, problems: result.problems]
    }

    def "value conversion: #kind of #value gives #expected / #problem"() {
        when:
        Map converted = convert(kind, value)

        then:
        converted.convertedValue == expected
        converted.convertedValue?.getClass() == expected?.getClass()
        // the scale of a decimal is part of the result
        !(expected instanceof BigDecimal) || converted.convertedValue.scale() == expected.scale()
        // a problem is empty when the value fits
        (converted.problem ?: null) == problem

        where:
        [kind, value, expected, problem] << PARITY_CASES
    }

    def "fact conversion: #name"() {
        when:
        Map converted = convertFact("ParityFact", values)

        then:
        converted.fact == expectedFact
        converted.fact.collectEntries { k, v -> [k, v?.getClass()] } == expectedFact.collectEntries { k, v -> [k, v?.getClass()] }
        converted.problems == expectedProblems

        where:
        [name, values, expectedFact, expectedProblems] << FACT_CASES
    }

    def "a field that is neither a single value nor a list is a problem, even with no value"() {
        expect:
        convertFact("ParityOdd", values) == [fact: [:], problems: expectedProblems]

        where:
        values     || expectedProblems
        [:]        || ["Value null does not fit field odd of ParityOdd"]
        [odd: "x"] || ["Value x does not fit field odd of ParityOdd"]
    }
}
