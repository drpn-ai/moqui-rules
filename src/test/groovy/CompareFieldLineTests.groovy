import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui

class CompareFieldLineTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String ruleSetId = "RULES_TEST_PAIR_DECISION"
    @Shared String factTypeId = "RULES_TEST_PAIR"

    def setupSpec() {
        ec = Moqui.getExecutionContext(); RulesTestData.ensureLoaded(ec)
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeAll()
        ec.entity.makeValue("rules.FactType").setAll([factTypeId: factTypeId]).create()
        [left_paid: "FVT_BOOLEAN", right_hasPayment: "FVT_BOOLEAN", left_amount: "FVT_DECIMAL", right_amount: "FVT_DECIMAL",
         left_ids: "FVT_TEXT", right_ids: "FVT_TEXT"].each { name, type ->
            ec.entity.makeValue("rules.FactField").setAll([factTypeId: factTypeId, fieldName: name, valueTypeEnumId: type]).create() }
        ec.entity.makeValue("rules.RuleSet").setAll([ruleSetId: ruleSetId, firstMatchOnly: "N"]).create()
        ec.entity.makeValue("rules.RuleSetFactType").setAll([ruleSetId: ruleSetId, factTypeId: factTypeId]).create()
        ec.entity.makeValue("rules.RuleSetOutcome").setAll([ruleSetId: ruleSetId, outcomeId: "FAILS"]).create()
    }

    def cleanupSpec() {
        removeAll()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void removeAll() {
        List<String> ruleIds = ec.entity.find("rules.RuleSetRule").condition("ruleSetId", ruleSetId).list()*.ruleId
        if (ruleIds) {
            // lines nest more than one level deep: delete leaves first, so no line is deleted before the lines under it
            List lines = ec.entity.find("rules.RuleSetCondition").condition("ruleId", "in", ruleIds).useCache(false).list().collect()
            while (lines) {
                List leaves = lines.findAll { line -> !lines.any { it.ruleId == line.ruleId && it.parentConditionSeqId == line.conditionSeqId } }
                leaves*.delete()
                lines.removeAll(leaves)
            }
            for (String entityName in ["RuleSetActionField", "RuleSetAction", "RuleSetCondition", "RuleSetRule"])
                ec.entity.find("rules." + entityName).condition("ruleId", "in", ruleIds).deleteAll()
        }
        for (String entityName in ["RuleSetOutcome", "RuleSetFactType", "RuleSet"])
            ec.entity.find("rules." + entityName).condition("ruleSetId", ruleSetId).deleteAll()
        ec.entity.find("rules.FactField").condition("factTypeId", factTypeId).deleteAll()
        ec.entity.find("rules.FactType").condition("factTypeId", factTypeId).deleteAll()
    }

    static Map field(String fieldName, String operatorEnumId, String compareFieldName, Object tolerance = null) {
        [conditionTypeId: "RCT_COMPARE_FIELD", factTypeId: "RULES_TEST_PAIR", fieldName: fieldName, operatorEnumId: operatorEnumId,
         compareFieldName: compareFieldName, tolerance: tolerance]
    }

    static List<Map> not(Map line) {
        [[conditionSeqId: "01", conditionTypeId: "RCT_NOT"], line + [conditionSeqId: "02", parentConditionSeqId: "01"]]
    }

    /** NOT over an ALL with this one line under it. */
    static List<Map> notAll(Map line) {
        [[conditionSeqId: "01", conditionTypeId: "RCT_NOT"], [conditionSeqId: "02", parentConditionSeqId: "01", conditionTypeId: "RCT_ALL"],
         line + [conditionSeqId: "03", parentConditionSeqId: "02"]]
    }

    List<String> storeErrors(Object line) {
        List<Map> lines = line instanceof List ? (List<Map>) line : [(Map) line + [conditionSeqId: "01"]]
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Rule").parameters([ruleSetId: ruleSetId, ruleName: "pair rule",
                priority: 10, conditions: lines, actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "FAILS"]]]).call()
        List<String> errors = ec.message.errors.collect()
        ec.message.clearAll()
        return errors
    }

    List<String> outcomeFor(Object line, Map fact) {
        assert storeErrors(line) == []
        Map result = ec.service.sync().name("moqui.rules.RuleServices.evaluate#RuleSet")
                .parameters([ruleSetId: ruleSetId, facts: [[factTypeId: factTypeId, fact: fact]]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
        return result.outcomes
    }

    @Unroll
    def "#story"() {
        expect: outcomeFor(line, fact) == expected
        where:
        story                                              | line                                                                 | fact                                                   || expected
        "paid requires a payment: paid, no payment fails"  | not(field("left_paid", "FPO_REQUIRES", "right_hasPayment"))     | [left_paid: true, right_hasPayment: false]         || ["FAILS"]
        "paid requires a payment: unpaid claims nothing"   | not(field("left_paid", "FPO_REQUIRES", "right_hasPayment"))     | [left_paid: false]                                  || []
        "a side not read claims nothing"                   | not(field("left_paid", "FPO_REQUIRES", "right_hasPayment"))     | [right_hasPayment: true]                              || []
        "required by: payment without paid fails"          | not(field("left_paid", "FPO_REQUIRED_BY", "right_hasPayment"))  | [left_paid: false, right_hasPayment: true]         || ["FAILS"]
        "required by: both true passes"                    | not(field("left_paid", "FPO_REQUIRED_BY", "right_hasPayment"))  | [left_paid: true, right_hasPayment: true]          || []
        "requires reads Y as true"                         | not(field("left_paid", "FPO_REQUIRES", "right_hasPayment"))     | [left_paid: "y", right_hasPayment: "N"]            || ["FAILS"]
        "all in: a left id missing on the right fails"     | not(field("left_ids", "FPO_ALL_IN", "right_ids"))               | [left_ids: "p1, p2", right_ids: "p2,p3"]           || ["FAILS"]
        "all in: every left id on the right passes"        | not(field("left_ids", "FPO_ALL_IN", "right_ids"))               | [left_ids: "p1, p2", right_ids: "p2,p1,p3"]        || []
        "all in: right not read claims nothing"            | not(field("left_ids", "FPO_ALL_IN", "right_ids"))               | [left_ids: "p1"]                                    || []
        "contains all: a right id missing on the left"     | not(field("left_ids", "FPO_CONTAINS_ALL", "right_ids"))         | [left_ids: "p1", right_ids: "p1,p2"]               || ["FAILS"]
        "contains all: left not read claims nothing"       | not(field("left_ids", "FPO_CONTAINS_ALL", "right_ids"))         | [right_ids: "p1"]                                     || []
        "amounts 0.99 apart pass a 1.00 tolerance"         | not(field("left_amount", "ENTCO_EQUALS", "right_amount", 1.00)) | [left_amount: 10.00, right_amount: 10.99]      || []
        "amounts 1.00 apart fail a 1.00 tolerance"         | not(field("left_amount", "ENTCO_EQUALS", "right_amount", 1.00)) | [left_amount: 10.00, right_amount: 11.00]      || ["FAILS"]
        "a missing amount fails a tolerance"               | not(field("left_amount", "ENTCO_EQUALS", "right_amount", 1.00)) | [left_amount: 10.00]                              || ["FAILS"]
        "plain greater-than between fields"                | field("left_amount", "ENTCO_GREATER", "right_amount")         | [left_amount: 12, right_amount: 11]              || ["FAILS"]
        "plain greater-than, not greater"                  | field("left_amount", "ENTCO_GREATER", "right_amount")         | [left_amount: 11, right_amount: 11]              || []
        "not around a plain equals fires on a difference"  | not(field("left_amount", "ENTCO_EQUALS", "right_amount"))     | [left_amount: 11, right_amount: 12]              || ["FAILS"]
        "not around a plain equals, equal amounts pass"    | not(field("left_amount", "ENTCO_EQUALS", "right_amount"))     | [left_amount: 11.0, right_amount: 11.00]         || []
        "tolerance 0 is plain equality: equal amounts pass" | not(field("left_amount", "ENTCO_EQUALS", "right_amount", 0)) | [left_amount: 10.00, right_amount: 10.00]   || []
        "tolerance 0 is plain equality: 0.01 apart fails"  | not(field("left_amount", "ENTCO_EQUALS", "right_amount", 0)) | [left_amount: 10.00, right_amount: 10.01]     || ["FAILS"]
        "greater-than, right side missing: value orders after null" | field("left_amount", "ENTCO_GREATER", "right_amount") | [left_amount: 12]                  || ["FAILS"]
        "greater-than, left side missing: null orders first" | field("left_amount", "ENTCO_GREATER", "right_amount")     | [right_amount: 11]                                || []
        "less-than, left side missing: null orders first"  | field("left_amount", "ENTCO_LESS", "right_amount")            | [right_amount: 11]                                || ["FAILS"]
        "integer 10 equals decimal 10.00"                  | not(field("left_amount", "ENTCO_EQUALS", "right_amount"))     | [left_amount: 10, right_amount: 10.00]         || []
        "text 10 equals number 10"                         | not(field("left_amount", "ENTCO_EQUALS", "right_amount"))     | [left_amount: "10", right_amount: 10]          || []
        "not-equals with one side missing differs"         | field("left_amount", "ENTCO_NOT_EQUALS", "right_amount")      | [left_amount: 10]                                 || ["FAILS"]
        "less-or-equal on equal amounts"                   | field("left_amount", "ENTCO_LESS_EQ", "right_amount")         | [left_amount: 10, right_amount: 10.0]          || ["FAILS"]
        "greater-or-equal on a smaller left"               | field("left_amount", "ENTCO_GREATER_EQ", "right_amount")      | [left_amount: 9, right_amount: 10]             || []
        "text is compared exactly: a trailing space differs" | field("left_ids", "ENTCO_EQUALS", "right_ids")              | [left_ids: "SKU1 ", right_ids: "SKU1"]           || []
        "text is compared exactly: same text is equal"     | field("left_ids", "ENTCO_EQUALS", "right_ids")                  | [left_ids: "SKU1", right_ids: "SKU1"]            || ["FAILS"]
        "not over a one-line all: differing amounts fire"  | notAll(field("left_amount", "ENTCO_EQUALS", "right_amount"))  | [left_amount: 11, right_amount: 12]            || ["FAILS"]
        "not over a one-line all: equal amounts pass"      | notAll(field("left_amount", "ENTCO_EQUALS", "right_amount"))  | [left_amount: 11, right_amount: 11]            || []
    }

    def "a tolerance on anything but equals, an unknown other field, or a list field is refused at save"() {
        expect:
        storeErrors(field("left_amount", "ENTCO_GREATER", "right_amount", 1.00)).any { it.contains("tolerance") }
        storeErrors(field("left_amount", "ENTCO_EQUALS", "nope")).any { it.contains("nope") }
        storeErrors(field("left_amount", "ENTCO_EQUALS", "right_amount", -1)).any { it.contains("negative") }
        storeErrors(field("left_amount", "ENTCO_LIKE", "right_amount")).any { it.contains("ENTCO_LIKE") }
    }

    def "a tolerance that is not a number is refused at save, naming it"() {
        expect:
        storeErrors(field("left_amount", "ENTCO_EQUALS", "right_amount", "abc")) == ["Condition 01 of rule pair rule: tolerance abc is not a number"]
    }

    def "a compare two fields line can be one choice of an any line"() {
        given:
        List<Map> lines = [[conditionSeqId: "01", conditionTypeId: "RCT_ANY"],
                field("left_paid", "FPO_REQUIRES", "right_hasPayment") + [conditionSeqId: "02", parentConditionSeqId: "01"],
                field("left_amount", "ENTCO_GREATER", "right_amount") + [conditionSeqId: "03", parentConditionSeqId: "01"]]
        expect:
        outcomeFor(lines, [left_paid: "Y", right_hasPayment: "N", left_amount: 1, right_amount: 2]) == []
        outcomeFor(lines, [left_paid: "Y", right_hasPayment: "Y", left_amount: 1, right_amount: 2]) == ["FAILS"]
        outcomeFor(lines, [left_paid: "Y", right_hasPayment: "N", left_amount: 3, right_amount: 2]) == ["FAILS"]
    }
}
