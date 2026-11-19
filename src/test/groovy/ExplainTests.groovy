import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition

class ExplainTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String ruleSetId = "RULES_TEST_EXPLAIN"
    @Shared String shapesRuleSetId = "RULES_TEST_EXPLAIN_SHAPES"
    @Shared String pieceId = "RULES_TEST_EXPLAIN_PIECE"
    @Shared String twoKindsRuleSetId = "RULES_TEST_EXPLAIN_TWO"
    @Shared String otherFactTypeId = "RULES_TEST_EXPLAIN_B"

    def setupSpec() {
        ec = Moqui.getExecutionContext(); RulesTestData.ensureLoaded(ec)
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeAll()

        createDecision(ruleSetId)
        storeRule(ruleSetId, "switch on", 30, "SWITCH_ON", [
                compare("01", "refusedCount", "ENTCO_EQUALS", "0"), compare("02", "logStatusId", "ENTCO_EQUALS", "DmlsFinished")])
        storeRule(ruleSetId, "check again", 20, "CHECK_AGAIN", [
                compare("01", "logStatusId", "ENTCO_NOT_EQUALS", "DmlsFinished"), compare("02", "tryCount", "ENTCO_LESS", "3")])
        storeRule(ruleSetId, "put back", 10, "PUT_BACK", [])

        ec.entity.makeValue("rules.FactType").setAll([factTypeId: otherFactTypeId]).create()
        ec.entity.makeValue("rules.FactField").setAll([factTypeId: otherFactTypeId, fieldName: "code", valueTypeEnumId: "FVT_TEXT"]).create()
        createDecision(twoKindsRuleSetId)
        ec.entity.makeValue("rules.RuleSetFactType").setAll([ruleSetId: twoKindsRuleSetId, factTypeId: otherFactTypeId]).create()
        storeRule(twoKindsRuleSetId, "two kinds", 20, "SWITCH_ON", [compare("01", "refusedCount", "ENTCO_EQUALS", "0"),
                compare("02", "code", "ENTCO_EQUALS", "b") + [factTypeId: otherFactTypeId]])
        storeRule(twoKindsRuleSetId, "one kind", 10, "PUT_BACK", [])

        createDecision(shapesRuleSetId)
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Piece").parameters([pieceId: pieceId, factTypeId: "checkResult",
                conditions: [compare("01", "logStatusId", "ENTCO_EQUALS", "X")]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
        storeRule(shapesRuleSetId, "not five", 40, "SWITCH_ON", [
                [conditionSeqId: "01", conditionTypeId: "RCT_NOT"],
                compare("02", "refusedCount", "ENTCO_EQUALS", "5") + [parentConditionSeqId: "01"]])
        storeRule(shapesRuleSetId, "one or two", 30, "CHECK_AGAIN", [
                [conditionSeqId: "01", conditionTypeId: "RCT_ANY"],
                compare("02", "tryCount", "ENTCO_EQUALS", "1") + [parentConditionSeqId: "01"],
                compare("03", "tryCount", "ENTCO_EQUALS", "2") + [parentConditionSeqId: "01"]])
        storeRule(shapesRuleSetId, "uses piece", 20, "PUT_BACK", [[conditionSeqId: "01", conditionTypeId: "RCT_PIECE", compareValue: pieceId]])
    }

    def cleanupSpec() {
        removeAll()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void createDecision(String id) {
        ec.entity.makeValue("rules.RuleSet").setAll([ruleSetId: id, firstMatchOnly: "Y"]).create()
        ec.entity.makeValue("rules.RuleSetFactType").setAll([ruleSetId: id, factTypeId: "checkResult"]).create()
        for (String outcomeId in ["SWITCH_ON", "CHECK_AGAIN", "PUT_BACK"])
            ec.entity.makeValue("rules.RuleSetOutcome").setAll([ruleSetId: id, outcomeId: outcomeId]).create()
    }

    void removeAll() {
        for (String id in [ruleSetId, shapesRuleSetId, twoKindsRuleSetId]) {
            List<String> ruleIds = ec.entity.find("rules.RuleSetRule").condition("ruleSetId", id).list()*.ruleId
            if (ruleIds) {
                ec.entity.find("rules.RuleSetCondition").condition("ruleId", "in", ruleIds)
                        .condition("parentConditionSeqId", EntityCondition.ComparisonOperator.IS_NOT_NULL, null).deleteAll()
                for (String entityName in ["RuleSetActionField", "RuleSetAction", "RuleSetCondition", "RuleSetRule"])
                    ec.entity.find("rules." + entityName).condition("ruleId", "in", ruleIds).deleteAll()
            }
            for (String entityName in ["RuleSetOutcome", "RuleSetFactType", "RuleSet"])
                ec.entity.find("rules." + entityName).condition("ruleSetId", id).deleteAll()
        }
        ec.entity.find("rules.RulePieceCondition").condition("pieceId", pieceId).deleteAll()
        ec.entity.find("rules.RulePiece").condition("pieceId", pieceId).deleteAll()
        ec.entity.find("rules.FactField").condition("factTypeId", otherFactTypeId).deleteAll()
        ec.entity.find("rules.FactType").condition("factTypeId", otherFactTypeId).deleteAll()
    }

    static Map compare(String seqId, String fieldName, String operatorEnumId, String compareValue) {
        [conditionSeqId: seqId, conditionTypeId: "RCT_COMPARE", factTypeId: "checkResult", fieldName: fieldName,
         operatorEnumId: operatorEnumId, compareValue: compareValue]
    }

    void storeRule(String id, String ruleName, int priority, String outcomeId, List<Map> conditions) {
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Rule").parameters([ruleSetId: id, ruleName: ruleName, priority: priority,
                conditions: conditions, actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: outcomeId]]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
    }

    Map explain(Map fact, String id = ruleSetId, String factTypeId = "checkResult") {
        ec.message.clearAll()
        return ec.service.sync().name("moqui.rules.RuleServices.explain#RuleSet")
                .parameters([ruleSetId: id, factTypeId: factTypeId, fact: fact]).call()
    }

    List<String> evaluate(Map fact, String id = ruleSetId) {
        ec.message.clearAll()
        Map out = ec.service.sync().name("moqui.rules.RuleServices.evaluate#RuleSet")
                .parameters([ruleSetId: id, facts: [[factTypeId: "checkResult", fact: fact]]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
        return (List<String>) out.outcomes
    }

    static List<Map> sampleFacts() {
        [[refusedCount: 0, logStatusId: "DmlsFinished", tryCount: 1],     // switch on wins, both lines hold
         [refusedCount: 0, logStatusId: "DmlsRunning", tryCount: 1],      // second line fails, check again wins
         [refusedCount: 5, logStatusId: "DmlsFinished", tryCount: 1],     // first line fails, nothing but put back
         [refusedCount: 5, logStatusId: "DmlsRunning", tryCount: 1],      // check again wins, both hold
         [refusedCount: 5, logStatusId: "DmlsRunning", tryCount: 9],      // check again's second line fails
         [refusedCount: 0, logStatusId: "DmlsRunning", tryCount: 3],      // boundary of less
         [refusedCount: 0, tryCount: 2],                                  // logStatusId missing
         [logStatusId: "DmlsFinished"],                                   // refusedCount and tryCount missing
         [:]]
    }

    static List<Map> shapeFacts() {
        [[refusedCount: 5, logStatusId: "X", tryCount: 1],   // not fails, any holds, piece holds
         [refusedCount: 1, logStatusId: "Y", tryCount: 7],   // not holds
         [refusedCount: 5, logStatusId: "Y", tryCount: 2],   // not fails, any holds via second choice
         [refusedCount: 5, logStatusId: "X", tryCount: 7],   // not fails, any fails, piece holds
         [refusedCount: 5, logStatusId: "Y", tryCount: 7],   // nothing holds
         [tryCount: 1],                                      // missing fields
         [:]]
    }

    def "every rule and every line of a decision says whether it held for this fact"() {
        when: Map out = explain([refusedCount: 5, logStatusId: "DmlsRunning", tryCount: 1])
        then:
        !ec.message.hasError()
        out.rules*.ruleName == ["switch on", "check again", "put back"]
        out.rules*.matched == [false, true, true]
        out.rules[0].lines*.holds == [false, false]
        out.rules[1].lines*.text == ["logStatusId not-equals DmlsFinished", "tryCount less 3"]
        out.rules*.outcomeIds == [["SWITCH_ON"], ["CHECK_AGAIN"], ["PUT_BACK"]]
        out.rules*.priority == [30, 20, 10]
        out.rules[2].lines == []
    }

    def "explain agrees with evaluate on every fact of a sample"() {
        expect:
        sampleFacts().size() >= 8
        sampleFacts().every { fact ->
            List<String> first = explain(fact).rules.findAll { it.matched }*.outcomeIds.flatten().take(1)
            assert first == evaluate(fact), "disagree on ${fact}"
            true
        }
    }

    def "a not line, an any line and a piece agree with evaluate and list their lines"() {
        expect:
        shapeFacts().every { fact ->
            List<String> first = explain(fact, shapesRuleSetId).rules.findAll { it.matched }*.outcomeIds.flatten().take(1)
            assert first == evaluate(fact, shapesRuleSetId), "disagree on ${fact}"
            true
        }
        when: Map out = explain([refusedCount: 5, logStatusId: "X", tryCount: 2], shapesRuleSetId)
        then:
        out.rules*.ruleName == ["not five", "one or two", "uses piece"]
        out.rules*.matched == [false, true, true]
        out.rules[0].lines*.text == ["none of"]
        out.rules[0].lines[0].holds == false
        out.rules[0].lines[0].lines*.text == ["refusedCount equals 5"]
        out.rules[0].lines[0].lines*.holds == [true]
        out.rules[1].lines[0].text == "any of"
        out.rules[1].lines[0].lines*.holds == [false, true]
        out.rules[2].lines[0].text == "piece " + pieceId
        out.rules[2].lines[0].holds
        out.rules[2].lines[0].lines*.text == ["logStatusId equals X"]
        out.rules[2].lines[0].lines*.holds == [true]
    }

    def "a fact type the decision does not read directly is refused"() {
        when: explain([:], ruleSetId, "orderItem")
        then: ec.message.errors.any { it.contains("orderItem") }
    }

    def "a rule that also reads another fact type is refused, naming the rule and the type"() {
        when: explain([refusedCount: 0], twoKindsRuleSetId)
        then: ec.message.errors.any { it.contains("explain reads one fact type; rule two kinds also reads " + otherFactTypeId) }
        !ec.message.errors.any { it.contains("one kind") }
    }

    def "a value that does not fit its field is refused"() {
        when: explain([tryCount: "many"])
        then: ec.message.errors.any { it.contains("tryCount") }
    }
}
