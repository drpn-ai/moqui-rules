import java.sql.Timestamp
import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.rules.FactConverter

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
        // a field whose kind is neither a single value nor a list: a node-type enum on purpose, because convert#FactValue
        // refuses any kind that is not a FactValueType
        createFactType("ParityOdd", [odd: "FNT_RECORD"])
    }

    def cleanupSpec() {
        removeTestData()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    static final List<String> TEST_FACT_TYPES = ["ParityFact", "ParityItem", "ParityOdd"]
    static final String CHECK_SET = "PARITY_CHECK_SET"

    void removeTestData() {
        List<String> fileCheckIds = ec.entity.find("rules.FileCheck").condition("ruleSetId", CHECK_SET).list()*.fileCheckId
        if (fileCheckIds) {
            ec.entity.find("rules.FileCheckRecord").condition("fileCheckId", "in", fileCheckIds).deleteAll()
            ec.entity.find("rules.FileCheck").condition("fileCheckId", "in", fileCheckIds).deleteAll()
        }
        List<String> ruleIds = ec.entity.find("rules.RuleSetRule").condition("ruleSetId", CHECK_SET).list()*.ruleId
        if (ruleIds) {
            for (String entityName in ["RuleSetAction", "RuleSetCondition", "RuleSetRule"])
                ec.entity.find("rules." + entityName).condition("ruleId", "in", ruleIds).deleteAll()
        }
        for (String entityName in ["RuleSetOutcome", "RuleSetFactType", "RuleSet"])
            ec.entity.find("rules." + entityName).condition("ruleSetId", CHECK_SET).deleteAll()
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

    def "convertAll gives the same results as convertFact one at a time: #name"() {
        when:
        List<Map> results = new FactConverter(ec).convertAll([[factTypeId: "ParityFact", values: values]])

        then:
        results.size() == 1
        results[0].fact == expectedFact
        results[0].fact.collectEntries { k, v -> [k, v?.getClass()] } == expectedFact.collectEntries { k, v -> [k, v?.getClass()] }
        results[0].problems == expectedProblems

        where:
        [name, values, expectedFact, expectedProblems] << FACT_CASES
    }

    def "convertAll keeps order and does not let a bad fact shift its neighbours"() {
        when:
        List<Map> results = new FactConverter(ec).convertAll([
                [factTypeId: "ParityFact", values: [qty: "1"]],
                [factTypeId: "ParityFact", values: [qty: "x"]],
                [factTypeId: "ParityItem", values: [count: "3"]]])

        then:
        results*.problems == [[], ["Value x does not fit field qty of ParityFact"], []]
        results[0].fact.qty == 1L
        results[2].fact == [count: 3L, name: null]
    }

    def "a field that is neither a single value nor a list: convertAll agrees with convertFact"() {
        expect:
        new FactConverter(ec).convertAll([[factTypeId: "ParityOdd", values: [:]]]) ==
                [[fact: [:], problems: ["Value null does not fit field odd of ParityOdd"]]]
    }

    def "a value kind change is used by the next call"() {
        given:
        FactConverter converter = new FactConverter(ec)
        Map before = converter.convertFact("ParityItem", [count: "12.5"])

        when:
        ec.service.sync().name("moqui.rules.RuleServices.update#FactField")
                .parameters([factTypeId: "ParityItem", fieldName: "count", valueTypeEnumId: "FVT_DECIMAL"]).call()
        Map after = new FactConverter(ec).convertFact("ParityItem", [count: "12.5"])

        then:
        before.problems == ["Value 12.5 does not fit field count of ParityItem"]
        after.problems == []
        after.fact.count == new BigDecimal("12.5")

        cleanup:
        ec.service.sync().name("moqui.rules.RuleServices.update#FactField")
                .parameters([factTypeId: "ParityItem", fieldName: "count", valueTypeEnumId: "FVT_INTEGER"]).call()
        ec.message.clearAll()
    }

    /**
     * How many times the artifact of the given name ran, anywhere in this execution context's history tree. Counted
     * on the printed history: ArtifactExecutionInfo.getChildList throws on an artifact that has no children.
     */
    int hits(String name) {
        return ec.artifactExecution.printHistory().split("\n").count { it.contains(name) }
    }

    def "a batch does not call a service per value"() {
        given:
        String valueService = "moqui.rules.FactValueServices.convert#FactValue"
        // the counter sees a service call at all: one call of the service shows up once
        int base = hits(valueService)
        convert("FVT_TEXT", "x")
        assert hits(valueService) == base + 1
        List<Map> entries = (1..200).collect { [factTypeId: "ParityItem", values: [name: "n" + it, count: "5", extra: 1]] }
        int beforeBatch = hits(valueService)

        when:
        List<Map> results = new FactConverter(ec).convertAll(entries)

        then:
        results.size() == 200
        results.every { it.fact.count == 5L }
        hits(valueService) == beforeBatch
    }

    def "convert#Facts gives what convert#Fact gives for each entry"() {
        given:
        List<Map> entries = FACT_CASES.collect { [factTypeId: "ParityFact", values: it[1]] }

        when:
        Map out = ec.service.sync().name("moqui.rules.RuleServices.convert#Facts").parameters([entries: entries]).call()

        then:
        !ec.message.hasError()
        out.results.size() == FACT_CASES.size()
        FACT_CASES.eachWithIndex { c, i ->
            assert out.results[i].fact == c[2], c[0]
            assert out.results[i].fact.collectEntries { k, v -> [k, v?.getClass()] } == c[2].collectEntries { k, v -> [k, v?.getClass()] }, c[0]
            assert out.results[i].problems == c[3], c[0]
            assert out.results[i] == convertFact("ParityFact", c[1]), c[0]
        }
    }

    def "convert#Facts of no entries gives no results and adds no errors"() {
        when:
        Map out = ec.service.sync().name("moqui.rules.RuleServices.convert#Facts").parameters([entries: []]).call()

        then:
        out.results == []
        !ec.message.hasError()
    }

    def "check#File gives each record its own outcome or refusal"() {
        given:
        ec.entity.makeValue("rules.RuleSet").setAll([ruleSetId: CHECK_SET, firstMatchOnly: "Y"]).create()
        ec.entity.makeValue("rules.RuleSetFactType").setAll([ruleSetId: CHECK_SET, factTypeId: "ParityItem"]).create()
        ec.entity.makeValue("rules.RuleSetOutcome").setAll([ruleSetId: CHECK_SET, outcomeId: "PARITY_OK"]).create()
        ec.service.sync().name("moqui.rules.RuleServices.store#Rule").parameters([ruleSetId: CHECK_SET, ruleName: "any", priority: 10,
                conditions: [], actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "PARITY_OK"]]]).call()
        assert !ec.message.hasError()

        when:
        Map out = ec.service.sync().name("moqui.rules.RuleServices.check#File").parameters([ruleSetId: CHECK_SET, factTypeId: "ParityItem",
                fileName: "items.csv", fileText: "name,count\na,1\nb,zz\nc,3\n"]).call()

        then:
        !ec.message.hasError()
        out.checkedRecords*.recordNumber == [1, 2, 3]
        out.checkedRecords[0].outcomes == ["PARITY_OK"]
        out.checkedRecords[0].refusedReasons == null
        out.checkedRecords[1].refusedReasons == ["Value zz does not fit field count of ParityItem"]
        out.checkedRecords[1].outcomes == null
        out.checkedRecords[2].outcomes == ["PARITY_OK"]

        when: "a row the file read already refuses also holds a value that does not fit"
        Map refused = ec.service.sync().name("moqui.rules.RuleServices.check#File").parameters([ruleSetId: CHECK_SET, factTypeId: "ParityItem",
                fileName: "items.csv", fileText: "name,count\na,1\nb,zz,extra\n"]).call()

        then: "the read problem comes first, then the conversion problem"
        !ec.message.hasError()
        refused.checkedRecords[0].refusedReasons == null
        refused.checkedRecords[1].refusedReasons == ["It has 3 values, but the file has 2 columns",
                                                      "Value zz does not fit field count of ParityItem"]
        refused.checkedRecords[1].outcomes == null
    }
}
