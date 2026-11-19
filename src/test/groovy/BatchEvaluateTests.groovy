import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition

class BatchEvaluateTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String ruleSetId = "RULES_TEST_BATCH"
    @Shared String typesRuleSetId = "RULES_TEST_BATCH_TYPES"
    @Shared String chainRuleSetId = "RULES_TEST_BATCH_CHAIN"
    @Shared String pairRuleSetId = "RULES_TEST_BATCH_PAIR"
    @Shared List<String> extraFactTypeIds = ["batchTestA", "batchTestB", "batchTestC", "batchStep"]

    def setupSpec() {
        ec = Moqui.getExecutionContext(); RulesTestData.ensureLoaded(ec)
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeDecision(ruleSetId)
        removeDecision(typesRuleSetId)
        removeDecision(chainRuleSetId)
        removeDecision(pairRuleSetId)
        removeExtraFactTypes()

        createDecision(ruleSetId, ["checkResult"])
        storeRule(ruleSetId, "switch on", 30, "SWITCH_ON", [
                compare("01", "checkResult", "refusedCount", "ENTCO_EQUALS", "0"), compare("02", "checkResult", "logStatusId", "ENTCO_EQUALS", "DmlsFinished")])
        storeRule(ruleSetId, "check again", 20, "CHECK_AGAIN", [
                compare("01", "checkResult", "logStatusId", "ENTCO_NOT_EQUALS", "DmlsFinished"), compare("02", "checkResult", "tryCount", "ENTCO_LESS", "3")])
        storeRule(ruleSetId, "put back", 10, "PUT_BACK", [])

        for (String factTypeId in extraFactTypeIds) {
            ec.entity.makeValue("rules.FactType").setAll([factTypeId: factTypeId]).create()
            ec.entity.makeValue("rules.FactField").setAll([factTypeId: factTypeId, fieldName: "code", valueTypeEnumId: "FVT_TEXT"]).create()
        }
        createDecision(typesRuleSetId, ["checkResult"] + extraFactTypeIds)
        storeRule(typesRuleSetId, "three kinds", 10, "SWITCH_ON", [
                compare("01", "checkResult", "refusedCount", "ENTCO_EQUALS", "0"),
                compare("02", "batchTestA", "code", "ENTCO_EQUALS", "a"),
                compare("03", "batchTestB", "code", "ENTCO_EQUALS", "b")])

        // one record fact type: a rule adds a step of the same type, and a second rule matches that step
        createDecision(chainRuleSetId, ["batchStep"])
        storeRule(chainRuleSetId, "start", 20, [compare("01", "batchStep", "code", "ENTCO_EQUALS", "go")],
                [[actionTypeId: "RAT_ADD_FACT", factTypeId: "batchStep", fields: [[fieldName: "code", fixedValue: "next"]]]])
        storeRule(chainRuleSetId, "next step", 10, "CHECK_AGAIN", [compare("01", "batchStep", "code", "ENTCO_EQUALS", "next")])

        // two record fact types: a rule over both adds a third kind of fact, and a second rule matches it
        createDecision(pairRuleSetId, ["batchTestA", "batchTestB", "batchTestC"])
        storeRule(pairRuleSetId, "pair", 20, [compare("01", "batchTestA", "code", "ENTCO_EQUALS", "a"),
                compare("02", "batchTestB", "code", "ENTCO_EQUALS", "b")],
                [[actionTypeId: "RAT_ADD_FACT", factTypeId: "batchTestC", fields: [[fieldName: "code", fixedValue: "c"]]]])
        storeRule(pairRuleSetId, "after pair", 10, "SWITCH_ON", [compare("01", "batchTestC", "code", "ENTCO_EQUALS", "c")])
    }

    def cleanupSpec() {
        removeDecision(ruleSetId)
        removeDecision(typesRuleSetId)
        removeDecision(chainRuleSetId)
        removeDecision(pairRuleSetId)
        removeExtraFactTypes()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void createDecision(String id, List<String> factTypeIds) {
        ec.entity.makeValue("rules.RuleSet").setAll([ruleSetId: id, firstMatchOnly: "Y"]).create()
        for (String factTypeId in factTypeIds)
            ec.entity.makeValue("rules.RuleSetFactType").setAll([ruleSetId: id, factTypeId: factTypeId]).create()
        for (String outcomeId in ["SWITCH_ON", "CHECK_AGAIN", "PUT_BACK"])
            ec.entity.makeValue("rules.RuleSetOutcome").setAll([ruleSetId: id, outcomeId: outcomeId]).create()
    }

    void removeDecision(String id) {
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

    void removeExtraFactTypes() {
        ec.entity.find("rules.FactField").condition("factTypeId", "in", extraFactTypeIds).deleteAll()
        ec.entity.find("rules.FactType").condition("factTypeId", "in", extraFactTypeIds).deleteAll()
    }

    static Map compare(String seqId, String factTypeId, String fieldName, String operatorEnumId, String compareValue) {
        [conditionSeqId: seqId, conditionTypeId: "RCT_COMPARE", factTypeId: factTypeId, fieldName: fieldName,
         operatorEnumId: operatorEnumId, compareValue: compareValue]
    }

    List<String> storeRule(String id, String ruleName, int priority, String outcomeId, List<Map> conditions, boolean mustStore = true) {
        return storeRule(id, ruleName, priority, conditions, [[actionTypeId: "RAT_OUTCOME", outcomeId: outcomeId]], mustStore)
    }

    List<String> storeRule(String id, String ruleName, int priority, List<Map> conditions, List<Map> actions, boolean mustStore = true) {
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Rule").parameters([ruleSetId: id, ruleName: ruleName,
                priority: priority, conditions: conditions, actions: actions]).call()
        List<String> errors = ec.message.errors.collect()
        ec.message.clearAll()
        if (mustStore) assert errors == []
        return errors
    }

    Map evaluate(List<Map> facts, String id = ruleSetId) {
        ec.message.clearAll()
        return ec.service.sync().name("moqui.rules.RuleServices.evaluate#RuleSet")
                .parameters([ruleSetId: id, facts: facts]).call()
    }

    def "each record in a batch gets its own first match"() {
        when:
        Map out = evaluate([[factTypeId: "checkResult", recordId: "A", fact: [refusedCount: 0, logStatusId: "DmlsFinished", tryCount: 1]],
                            [factTypeId: "checkResult", recordId: "B", fact: [refusedCount: 5, logStatusId: "DmlsRunning", tryCount: 1]],
                            [factTypeId: "checkResult", recordId: "C", fact: [refusedCount: 5, logStatusId: "DmlsRunning", tryCount: 9]]])
        then:
        !ec.message.hasError()
        out.decisions*.recordId == ["A", "B", "C"]
        out.decisions*.outcomes == [["SWITCH_ON"], ["CHECK_AGAIN"], ["PUT_BACK"]]
        out.decisions[0].matchedRules*.ruleName == ["switch on", "put back"]
        out.matchedRules.every { it.recordIds.checkResult in ["A", "B", "C"] }
    }

    def "a record id used twice in one batch is refused, naming it"() {
        when: evaluate([[factTypeId: "checkResult", recordId: "A", fact: [refusedCount: 0]], [factTypeId: "checkResult", recordId: "A", fact: [refusedCount: 1]]])
        then: ec.message.errors.any { it.contains("A") && it.contains("more than once") }
    }

    def "a fact may not carry a field named _recordId"() {
        when: evaluate([[factTypeId: "checkResult", recordId: "A", fact: [_recordId: "X"]]])
        then: ec.message.errors.any { it.contains("_recordId") }
    }

    def "1,500 facts that all match the catch-all rule are judged, not stopped at 1,000 firings"() {
        when: Map out = evaluate((1..1500).collect { [factTypeId: "checkResult", recordId: "R$it", fact: [refusedCount: 5, logStatusId: "X", tryCount: 9]] })
        then: !ec.message.hasError(); out.decisions.size() == 1500; out.decisions.every { it.outcomes == ["PUT_BACK"] }
    }

    def "facts without a record id are still judged one by one, in input order"() {
        when:
        Map out = evaluate([[factTypeId: "checkResult", fact: [refusedCount: 5, logStatusId: "DmlsRunning", tryCount: 1]],
                            [factTypeId: "checkResult", recordId: "B", fact: [refusedCount: 0, logStatusId: "DmlsFinished", tryCount: 1]],
                            [factTypeId: "checkResult", fact: [refusedCount: 5, logStatusId: "DmlsRunning", tryCount: 9]]])
        then:
        !ec.message.hasError()
        out.decisions*.recordId == [null, "B", null]
        out.decisions*.outcomes == [["CHECK_AGAIN"], ["SWITCH_ON"], ["PUT_BACK"]]
        out.outcomes == ["SWITCH_ON"]
    }

    def "a rule over three fact types names each matched record, and the batch has no per-record decisions"() {
        when:
        Map out = evaluate([[factTypeId: "checkResult", recordId: "C1", fact: [refusedCount: 0]],
                            [factTypeId: "batchTestA", recordId: "A1", fact: [code: "a"]],
                            [factTypeId: "batchTestB", recordId: "B1", fact: [code: "b"]]], typesRuleSetId)
        then:
        !ec.message.hasError()
        out.outcomes == ["SWITCH_ON"]
        out.matchedRules*.recordIds == [[checkResult: "C1", batchTestA: "A1", batchTestB: "B1"]]
        out.decisions == []
    }

    def "a rule over four fact types is refused"() {
        when:
        List<String> errors = storeRule(typesRuleSetId, "four kinds", 20, "PUT_BACK", [
                compare("01", "checkResult", "refusedCount", "ENTCO_EQUALS", "0"),
                compare("02", "batchTestA", "code", "ENTCO_EQUALS", "a"),
                compare("03", "batchTestB", "code", "ENTCO_EQUALS", "b"),
                compare("04", "batchTestC", "code", "ENTCO_EQUALS", "c")], false)
        then:
        errors.any { it.contains("a rule reads at most 3 fact types") }
    }

    def "a file with a column named _recordId is refused before any field is learned"() {
        when:
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.learn#FileFields").parameters([factTypeId: "batchTestFile",
                fileName: "with-id.csv", fileText: "orderId,_recordId\n100,R1\n"]).call()
        List<String> errors = ec.message.errors.collect()
        ec.message.clearAll()
        then:
        errors.any { it.contains("_recordId") }
        ec.entity.find("rules.FactType").condition("factTypeId", "batchTestFile").count() == 0
    }

    def "a fact a rule added answers for the record whose fact made the rule fire"() {
        when:
        Map out = evaluate([[factTypeId: "batchStep", recordId: "A", fact: [code: "go"]],
                            [factTypeId: "batchStep", recordId: "B", fact: [code: "stop"]]], chainRuleSetId)
        then:
        !ec.message.hasError()
        out.decisions*.recordId == ["A", "B"]
        out.decisions[0].outcomes == ["CHECK_AGAIN"]
        out.decisions[0].matchedRules*.ruleName == ["start", "next step"]
        out.decisions[1].outcomes == []
        out.decisions[1].matchedRules == []
        out.matchedRules.find { it.ruleName == "next step" }.recordIds == [batchStep: "A"]
    }

    def "a fact added by a rule over two facts names both records but answers for no record"() {
        when:
        Map out = evaluate([[factTypeId: "batchTestA", recordId: "A1", fact: [code: "a"]],
                            [factTypeId: "batchTestB", recordId: "B1", fact: [code: "b"]]], pairRuleSetId)
        Map followOn = out.matchedRules.find { it.ruleName == "after pair" }
        then:
        !ec.message.hasError()
        out.outcomes == ["SWITCH_ON"]
        followOn.recordIds == [batchTestA: "A1", batchTestB: "B1"]
        !out.decisions.any { decision -> decision.matchedRules.any { it.ruleName == "after pair" } }
    }
}
