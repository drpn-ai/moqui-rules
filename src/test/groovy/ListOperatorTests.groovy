import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition

class ListOperatorTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String ruleSetId = "RULES_TEST_LIST_DECISION"
    @Shared String factTypeId = "RULES_TEST_LIST_FACT"

    def setupSpec() {
        ec = Moqui.getExecutionContext(); RulesTestData.ensureLoaded(ec)
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeAll()
        ec.entity.makeValue("rules.FactType").setAll([factTypeId: factTypeId]).create()
        ec.entity.makeValue("rules.FactField").setAll([factTypeId: factTypeId, fieldName: "status", valueTypeEnumId: "FVT_TEXT"]).create()
        ec.entity.makeValue("rules.FactField").setAll([factTypeId: factTypeId, fieldName: "amount", valueTypeEnumId: "FVT_DECIMAL"]).create()
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
        List<String> pieceIds = ec.entity.find("rules.RulePiece").condition("pieceId", EntityCondition.ComparisonOperator.LIKE, "RULES_TEST_LIST_%").list()*.pieceId
        if (pieceIds) {
            ec.entity.find("rules.RulePieceCondition").condition("pieceId", "in", pieceIds)
                    .condition("parentConditionSeqId", EntityCondition.ComparisonOperator.IS_NOT_NULL, null).deleteAll()
            ec.entity.find("rules.RulePieceCondition").condition("pieceId", "in", pieceIds).deleteAll()
            ec.entity.find("rules.RulePiece").condition("pieceId", "in", pieceIds).deleteAll()
        }
        ec.entity.find("rules.FactField").condition("factTypeId", factTypeId).deleteAll()
        ec.entity.find("rules.FactType").condition("factTypeId", factTypeId).deleteAll()
    }

    static Map compare(String seq, String fieldName, String operatorEnumId, String value) {
        [conditionSeqId: seq, conditionTypeId: "RCT_COMPARE", factTypeId: "RULES_TEST_LIST_FACT", fieldName: fieldName,
         operatorEnumId: operatorEnumId, compareValue: value]
    }

    static List<Map> single(String field, String operator, String value) { [compare("01", field, operator, value)] }

    List<String> storeErrors(List<Map> lines) {
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Rule").parameters([ruleSetId: ruleSetId, ruleName: "list rule",
                priority: 10, conditions: lines, actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "FAILS"]]]).call()
        List<String> errors = ec.message.errors.collect()
        ec.message.clearAll()
        return errors
    }

    boolean fires(List<Map> lines, Map fact) {
        assert storeErrors(lines) == []
        Map result = ec.service.sync().name("moqui.rules.RuleServices.evaluate#RuleSet")
                .parameters([ruleSetId: ruleSetId, facts: [[factTypeId: factTypeId, fact: fact]]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
        return result.outcomes == ["FAILS"]
    }

    @Unroll
    def "#caseName"() {
        expect: fires(lines, fact) == expected
        where:
        caseName                                | lines                                                                      | fact                          | expected
        "in: a listed value"                    | single("status", "ENTCO_IN", '["Open","Closed"]')                          | [status: "Open"]              | true
        "in: an unlisted value"                 | single("status", "ENTCO_IN", '["Open","Closed"]')                          | [status: "Draft"]             | false
        "in: exact text, case counts"           | single("status", "ENTCO_IN", '["Open","Closed"]')                          | [status: "open"]              | false
        "in: a missing field"                   | single("status", "ENTCO_IN", '["Open","Closed"]')                          | [:]                           | false
        "not in: an unlisted value"             | single("status", "ENTCO_NOT_IN", '["Open","Closed"]')                      | [status: "Draft"]             | true
        "not in: a listed value"                | single("status", "ENTCO_NOT_IN", '["Open","Closed"]')                      | [status: "Closed"]            | false
        "not in: a missing field"               | single("status", "ENTCO_NOT_IN", '["Open","Closed"]')                      | [:]                           | true
        "a comma inside a value"                | single("status", "ENTCO_IN", '["A,B","C"]')                                | [status: "A,B"]               | true
        "a comma inside a value, its halves"    | single("status", "ENTCO_IN", '["A,B","C"]')                                | [status: "A"]                 | false
        "numbers by value"                      | single("amount", "ENTCO_IN", '["1","2.50"]')                               | [amount: 2.5]                 | true
        "numbers not in, by value"              | single("amount", "ENTCO_NOT_IN", '["1","2.50"]')                           | [amount: 2.5]                 | false
        "not over in turns it around"           | notOver(compare("02", "status", "ENTCO_IN", '["Open"]'))                   | [status: "Open"]              | false
        "not over in, other value"              | notOver(compare("02", "status", "ENTCO_IN", '["Open"]'))                   | [status: "Draft"]             | true
        "not over in, a missing field"          | notOver(compare("02", "status", "ENTCO_IN", '["Open"]'))                   | [:]                           | true
        "not over not in, a missing field"      | notOver(compare("02", "status", "ENTCO_NOT_IN", '["Open"]'))               | [:]                           | false
        "in inside an any"                      | anyOf(compare("02", "status", "ENTCO_IN", '["Open"]'), compare("03", "amount", "ENTCO_EQUALS", "9")) | [status: "Draft", amount: 9] | true
        "a 300-character list"                  | single("status", "ENTCO_IN", longList(300, "Hit"))                         | [status: "Hit"]               | true
        "not in inside an any, a missing field" | anyOf(compare("02", "status", "ENTCO_NOT_IN", '["Open"]'), compare("03", "amount", "ENTCO_EQUALS", "9")) | [amount: 1] | true
    }

    /** A JSON list of filler values ending in last, exactly length characters long. */
    static String longList(int length, String last) {
        String tail = ',"' + last + '"]'
        String filler = '["' + 'x' * (length - 3 - tail.length()) + '"'
        String json = filler + tail
        assert json.length() == length
        return json
    }

    static List<Map> notOver(Map line) { [[conditionSeqId: "01", conditionTypeId: "RCT_NOT"], line + [parentConditionSeqId: "01"]] }
    static List<Map> anyOf(Map first, Map second) {
        [[conditionSeqId: "01", conditionTypeId: "RCT_ANY"], first + [parentConditionSeqId: "01"], second + [parentConditionSeqId: "01"]]
    }

    def "in on a piece line"() {
        given:
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Piece").parameters([pieceId: "RULES_TEST_LIST_P", factTypeId: factTypeId,
                conditions: single("status", "ENTCO_IN", '["Open"]')]).call()
        assert !ec.message.hasError(), ec.message.errorsString
        List<Map> usePiece = [[conditionSeqId: "01", conditionTypeId: "RCT_PIECE", compareValue: "RULES_TEST_LIST_P"]]
        expect:
        fires(usePiece, [status: "Open"])
        !fires(usePiece, [status: "Draft"])
    }

    def "a list that cannot be used is refused with a text naming the field"() {
        expect:
        storeErrors(single("status", "ENTCO_IN", "Open")).any { it.contains("status") && it.contains("not a JSON list") }
        storeErrors(single("status", "ENTCO_IN", "[]")).any { it.contains("status") && it.contains("is empty") }
        storeErrors(single("status", "ENTCO_NOT_IN", "[null]")).any { it.contains("status") && it.contains("null") }
        storeErrors(single("status", "ENTCO_IN", "[{}]")).any { it.contains("status") && it.contains("not a text or a number") }
        storeErrors(single("status", "ENTCO_IN", '[["a"]]')).any { it.contains("status") && it.contains("not a text or a number") }
        storeErrors(single("amount", "ENTCO_IN", '["x"]')).any { it.contains("amount") && it.contains("does not fit") }
        storeErrors(single("status", "ENTCO_IN", longList(4001, "Hit"))) ==
                ["Condition 01 of rule list rule: the value for field status is 4001 characters long, more than the 4000 a compare value holds"]
        storeErrors(single("status", "ENTCO_IN", longList(4000, "Hit"))) == []
    }

    def "explain says is one of and is not one of, values in stored order"() {
        given: assert storeErrors(single("status", "ENTCO_IN", '["Open","Closed"]')) == []
        when:
        Map out = ec.service.sync().name("moqui.rules.RuleServices.explain#RuleSet")
                .parameters([ruleSetId: ruleSetId, factTypeId: factTypeId, fact: [status: "Open"]]).call()
        then:
        out.rules[0].lines[0].text == "status is one of Open, Closed"
        org.moqui.rules.LineTrace.textOf(compare("01", "status", "ENTCO_NOT_IN", '["Open","Closed"]'), [:]) == "status is not one of Open, Closed"
    }
}
