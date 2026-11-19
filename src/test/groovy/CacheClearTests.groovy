import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.rules.RuleSets

class CacheClearTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String factTypeId = "RULES_TEST_CACHE_PAIR"
    @Shared String otherFactTypeId = "RULES_TEST_CACHE_OTHER"
    @Shared List<String> decisionIds = ["RULES_TEST_CACHE_D1", "RULES_TEST_CACHE_D2", "RULES_TEST_CACHE_D3"]

    def setupSpec() {
        ec = Moqui.getExecutionContext(); RulesTestData.ensureLoaded(ec)
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeAll()
        for (String typeId in [factTypeId, otherFactTypeId]) {
            ec.entity.makeValue("rules.FactType").setAll([factTypeId: typeId]).create()
            ec.entity.makeValue("rules.FactField").setAll([factTypeId: typeId, fieldName: "netsuite_status", valueTypeEnumId: "FVT_TEXT"]).create()
        }
        // D1 and D2 read the pair type and use piece P; D3 reads the other type and does not
        for (String ruleSetId in decisionIds) {
            ec.entity.makeValue("rules.RuleSet").setAll([ruleSetId: ruleSetId, firstMatchOnly: "N"]).create()
            ec.entity.makeValue("rules.RuleSetFactType").setAll([ruleSetId: ruleSetId,
                    factTypeId: ruleSetId.endsWith("D3") ? otherFactTypeId : factTypeId]).create()
            ec.entity.makeValue("rules.RuleSetOutcome").setAll([ruleSetId: ruleSetId, outcomeId: "FAILS"]).create()
        }
        storePiece("P", [compare("01", "netsuite_status", "ENTCO_EQUALS", "Refunded")])
        storeRule("RULES_TEST_CACHE_D1", "uses piece", 10, piece("P"))
        storeRule("RULES_TEST_CACHE_D2", "uses piece", 10, piece("P"))
        storeRule("RULES_TEST_CACHE_D3", "plain", 10, compareOn(otherFactTypeId, "01", "Open"))
    }

    def cleanupSpec() {
        removeAll()
        ec.artifactExecution.enableAuthz()
        ec.user.logoutUser()
        ec.destroy()
    }

    void removeAll() {
        List<String> ruleIds = ec.entity.find("rules.RuleSetRule").condition("ruleSetId", "in", decisionIds).list()*.ruleId
        if (ruleIds) {
            for (String entityName in ["RuleSetActionField", "RuleSetAction", "RuleSetCondition", "RuleSetRule"])
                ec.entity.find("rules." + entityName).condition("ruleId", "in", ruleIds).deleteAll()
        }
        for (String entityName in ["RuleSetOutcome", "RuleSetFactType", "RuleSet"])
            ec.entity.find("rules." + entityName).condition("ruleSetId", "in", decisionIds).deleteAll()
        ec.entity.find("rules.RulePieceCondition").condition("pieceId", EntityCondition.ComparisonOperator.LIKE, "RULES_TEST_CACHE_%").deleteAll()
        ec.entity.find("rules.RulePiece").condition("pieceId", EntityCondition.ComparisonOperator.LIKE, "RULES_TEST_CACHE_%").deleteAll()
        for (String typeId in [factTypeId, otherFactTypeId]) {
            ec.entity.find("rules.FactField").condition("factTypeId", typeId).deleteAll()
            ec.entity.find("rules.FactType").condition("factTypeId", typeId).deleteAll()
        }
    }

    static String pieceId(String name) { "RULES_TEST_CACHE_" + name }

    static Map compareOn(String typeId, String seq, String value) {
        [conditionSeqId: seq, conditionTypeId: "RCT_COMPARE", factTypeId: typeId, fieldName: "netsuite_status",
         operatorEnumId: "ENTCO_EQUALS", compareValue: value]
    }

    static Map compare(String seq, String fieldName, String operatorEnumId, String value) {
        [conditionSeqId: seq, conditionTypeId: "RCT_COMPARE", factTypeId: "RULES_TEST_CACHE_PAIR", fieldName: fieldName,
         operatorEnumId: operatorEnumId, compareValue: value]
    }

    static Map piece(String name) { [conditionSeqId: "01", conditionTypeId: "RCT_PIECE", compareValue: pieceId(name)] }

    void storePiece(String name, List<Map> lines) {
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Piece").parameters([pieceId: pieceId(name), factTypeId: factTypeId,
                conditions: lines]).call()
        assert !ec.message.hasError(), ec.message.errorsString
    }

    void storeRule(String ruleSetId, String ruleName, int priority, Map line) {
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Rule").parameters([ruleSetId: ruleSetId, ruleName: ruleName,
                priority: priority, conditions: [line], actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "FAILS"]]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
    }

    void built(String ruleSetId) { ((RuleSets) ec.getTool("Rules", RuleSets.class)).builtRuleSet(ruleSetId) }

    boolean isCached(String ruleSetId) { ec.cache.getCache('moqui.rules.built.rule.set').containsKey(ruleSetId) }

    def "editing a rule rebuilds its decision and leaves an unrelated decision built"() {
        given: built("RULES_TEST_CACHE_D1"); built("RULES_TEST_CACHE_D2")
        when: storeRule("RULES_TEST_CACHE_D1", "uses piece", 10, compare("01", "netsuite_status", "ENTCO_EQUALS", "Closed"))
        then: !isCached("RULES_TEST_CACHE_D1"); isCached("RULES_TEST_CACHE_D2")
    }

    def "editing a piece rebuilds both decisions that use it, and not a third"() {
        given:
        storeRule("RULES_TEST_CACHE_D1", "uses piece", 10, piece("P"))
        built("RULES_TEST_CACHE_D1"); built("RULES_TEST_CACHE_D2"); built("RULES_TEST_CACHE_D3")
        when: storePiece("P", [compare("01", "netsuite_status", "ENTCO_EQUALS", "Closed")])
        then: !isCached("RULES_TEST_CACHE_D1"); !isCached("RULES_TEST_CACHE_D2"); isCached("RULES_TEST_CACHE_D3")
    }

    def "editing a piece that another piece uses reaches the decisions of the outer piece"() {
        given:
        storePiece("OUTER", [piece("P")])
        storeRule("RULES_TEST_CACHE_D2", "uses piece", 10, piece("OUTER"))
        built("RULES_TEST_CACHE_D1"); built("RULES_TEST_CACHE_D2"); built("RULES_TEST_CACHE_D3")
        when: storePiece("P", [compare("01", "netsuite_status", "ENTCO_EQUALS", "Refunded")])
        then: !isCached("RULES_TEST_CACHE_D1"); !isCached("RULES_TEST_CACHE_D2"); isCached("RULES_TEST_CACHE_D3")
    }

    def "editing a field of a fact type clears the decisions that read it and not an unrelated one"() {
        given: built("RULES_TEST_CACHE_D1"); built("RULES_TEST_CACHE_D2"); built("RULES_TEST_CACHE_D3")
        when: ec.service.sync().name("update#rules.FactField").parameters([factTypeId: factTypeId,
                fieldName: "netsuite_status", description: "edited"]).call()
        then: !isCached("RULES_TEST_CACHE_D1"); !isCached("RULES_TEST_CACHE_D2"); isCached("RULES_TEST_CACHE_D3")
    }
}
