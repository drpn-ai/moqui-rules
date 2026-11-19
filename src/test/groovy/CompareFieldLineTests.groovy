import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition

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
        [shopify_paid: "FVT_BOOLEAN", hotwax_hasPayment: "FVT_BOOLEAN", shopify_amount: "FVT_DECIMAL", netsuite_amount: "FVT_DECIMAL",
         shopify_ids: "FVT_TEXT", hotwax_ids: "FVT_TEXT"].each { name, type ->
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
            ec.entity.find("rules.RuleSetCondition").condition("ruleId", "in", ruleIds)
                    .condition("parentConditionSeqId", EntityCondition.ComparisonOperator.IS_NOT_NULL, null).deleteAll()
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
        "paid requires a payment: paid, no payment fails"  | not(field("shopify_paid", "FPO_REQUIRES", "hotwax_hasPayment"))     | [shopify_paid: true, hotwax_hasPayment: false]         || ["FAILS"]
        "paid requires a payment: unpaid claims nothing"   | not(field("shopify_paid", "FPO_REQUIRES", "hotwax_hasPayment"))     | [shopify_paid: false]                                  || []
        "a side not read claims nothing"                   | not(field("shopify_paid", "FPO_REQUIRES", "hotwax_hasPayment"))     | [hotwax_hasPayment: true]                              || []
        "required by: payment without paid fails"          | not(field("shopify_paid", "FPO_REQUIRED_BY", "hotwax_hasPayment"))  | [shopify_paid: false, hotwax_hasPayment: true]         || ["FAILS"]
        "required by: both true passes"                    | not(field("shopify_paid", "FPO_REQUIRED_BY", "hotwax_hasPayment"))  | [shopify_paid: true, hotwax_hasPayment: true]          || []
        "requires reads Y as true"                         | not(field("shopify_paid", "FPO_REQUIRES", "hotwax_hasPayment"))     | [shopify_paid: "y", hotwax_hasPayment: "N"]            || ["FAILS"]
        "all in: a left id missing on the right fails"     | not(field("shopify_ids", "FPO_ALL_IN", "hotwax_ids"))               | [shopify_ids: "p1, p2", hotwax_ids: "p2,p3"]           || ["FAILS"]
        "all in: every left id on the right passes"        | not(field("shopify_ids", "FPO_ALL_IN", "hotwax_ids"))               | [shopify_ids: "p1, p2", hotwax_ids: "p2,p1,p3"]        || []
        "all in: right not read claims nothing"            | not(field("shopify_ids", "FPO_ALL_IN", "hotwax_ids"))               | [shopify_ids: "p1"]                                    || []
        "contains all: a right id missing on the left"     | not(field("shopify_ids", "FPO_CONTAINS_ALL", "hotwax_ids"))         | [shopify_ids: "p1", hotwax_ids: "p1,p2"]               || ["FAILS"]
        "contains all: left not read claims nothing"       | not(field("shopify_ids", "FPO_CONTAINS_ALL", "hotwax_ids"))         | [hotwax_ids: "p1"]                                     || []
        "amounts 0.99 apart pass a 1.00 tolerance"         | not(field("shopify_amount", "ENTCO_EQUALS", "netsuite_amount", 1.00)) | [shopify_amount: 10.00, netsuite_amount: 10.99]      || []
        "amounts 1.00 apart fail a 1.00 tolerance"         | not(field("shopify_amount", "ENTCO_EQUALS", "netsuite_amount", 1.00)) | [shopify_amount: 10.00, netsuite_amount: 11.00]      || ["FAILS"]
        "a missing amount fails a tolerance"               | not(field("shopify_amount", "ENTCO_EQUALS", "netsuite_amount", 1.00)) | [shopify_amount: 10.00]                              || ["FAILS"]
        "plain greater-than between fields"                | field("shopify_amount", "ENTCO_GREATER", "netsuite_amount")         | [shopify_amount: 12, netsuite_amount: 11]              || ["FAILS"]
        "plain greater-than, not greater"                  | field("shopify_amount", "ENTCO_GREATER", "netsuite_amount")         | [shopify_amount: 11, netsuite_amount: 11]              || []
        "not around a plain equals fires on a difference"  | not(field("shopify_amount", "ENTCO_EQUALS", "netsuite_amount"))     | [shopify_amount: 11, netsuite_amount: 12]              || ["FAILS"]
        "not around a plain equals, equal amounts pass"    | not(field("shopify_amount", "ENTCO_EQUALS", "netsuite_amount"))     | [shopify_amount: 11.0, netsuite_amount: 11.00]         || []
    }

    def "a tolerance on anything but equals, an unknown other field, or a list field is refused at save"() {
        expect:
        storeErrors(field("shopify_amount", "ENTCO_GREATER", "netsuite_amount", 1.00)).any { it.contains("tolerance") }
        storeErrors(field("shopify_amount", "ENTCO_EQUALS", "nope")).any { it.contains("nope") }
        storeErrors(field("shopify_amount", "ENTCO_EQUALS", "netsuite_amount", -1)).any { it.contains("negative") }
        storeErrors(field("shopify_amount", "ENTCO_LIKE", "netsuite_amount")).any { it.contains("ENTCO_LIKE") }
    }

    def "a compare two fields line with a value check cannot be one choice of an any line"() {
        expect:
        storeErrors([[conditionSeqId: "01", conditionTypeId: "RCT_ANY"], field("shopify_paid", "FPO_REQUIRES", "hotwax_hasPayment") +
                [conditionSeqId: "02", parentConditionSeqId: "01"]]).any { it.contains("any line") }
    }
}
