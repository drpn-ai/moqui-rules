import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition

class PieceTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String ruleSetId = "RULES_TEST_PIECE_DECISION"
    @Shared String factTypeId = "RULES_TEST_PIECE_PAIR"
    @Shared String otherRuleSetId = "RULES_TEST_PIECE_OTHER"

    def setupSpec() {
        ec = Moqui.getExecutionContext(); RulesTestData.ensureLoaded(ec)
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeAll()
        ec.entity.makeValue("rules.FactType").setAll([factTypeId: factTypeId]).create()
        ec.entity.makeValue("rules.FactField").setAll([factTypeId: factTypeId, fieldName: "netsuite_status", valueTypeEnumId: "FVT_TEXT"]).create()
        ec.entity.makeValue("rules.FactField").setAll([factTypeId: factTypeId, fieldName: "hotwax_status", valueTypeEnumId: "FVT_TEXT"]).create()
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
        List<String> ruleIds = ec.entity.find("rules.RuleSetRule").condition("ruleSetId", "in", [ruleSetId, otherRuleSetId]).list()*.ruleId
        if (ruleIds) {
            ec.entity.find("rules.RuleSetCondition").condition("ruleId", "in", ruleIds)
                    .condition("parentConditionSeqId", EntityCondition.ComparisonOperator.IS_NOT_NULL, null).deleteAll()
            for (String entityName in ["RuleSetActionField", "RuleSetAction", "RuleSetCondition", "RuleSetRule"])
                ec.entity.find("rules." + entityName).condition("ruleId", "in", ruleIds).deleteAll()
        }
        for (String entityName in ["RuleSetOutcome", "RuleSetFactType", "RuleSet"])
            ec.entity.find("rules." + entityName).condition("ruleSetId", "in", [ruleSetId, otherRuleSetId]).deleteAll()
        // pieces last-written first would not matter: lines go before their pieces
        List<String> pieceIds = ec.entity.find("rules.RulePiece").condition("pieceId", EntityCondition.ComparisonOperator.LIKE, "RULES_TEST_%").list()*.pieceId
        if (pieceIds) {
            ec.entity.find("rules.RulePieceCondition").condition("pieceId", "in", pieceIds)
                    .condition("parentConditionSeqId", EntityCondition.ComparisonOperator.IS_NOT_NULL, null).deleteAll()
            ec.entity.find("rules.RulePieceCondition").condition("pieceId", "in", pieceIds).deleteAll()
            ec.entity.find("rules.RulePiece").condition("pieceId", "in", pieceIds).deleteAll()
        }
        ec.entity.find("rules.FactField").condition("factTypeId", factTypeId).deleteAll()
        ec.entity.find("rules.FactType").condition("factTypeId", factTypeId).deleteAll()
    }

    static String id(String name) { "RULES_TEST_" + name }

    static Map compare(String seq, String fieldName, String operatorEnumId, String value) {
        [conditionSeqId: seq, conditionTypeId: "RCT_COMPARE", factTypeId: "RULES_TEST_PIECE_PAIR", fieldName: fieldName,
         operatorEnumId: operatorEnumId, compareValue: value]
    }

    static Map piece(String name) { [conditionTypeId: "RCT_PIECE", compareValue: id(name)] }

    static List<Map> numbered(List<Map> lines) {
        lines.withIndex().collect { Map line, int index -> line.conditionSeqId ? line : line + [conditionSeqId: String.format("%02d", index + 1)] }
    }

    List<String> storePieceErrors(String name, String pieceFactTypeId, List<Map> lines) {
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Piece").parameters([pieceId: id(name), factTypeId: pieceFactTypeId,
                conditions: numbered(lines)]).call()
        List<String> errors = ec.message.errors.collect()
        ec.message.clearAll()
        return errors
    }

    void storePiece(String name, String pieceFactTypeId, List<Map> lines) {
        assert storePieceErrors(name, pieceFactTypeId, lines) == []
    }

    List<String> storeErrors(Object line, String decisionId = ruleSetId) {
        List<Map> lines = line instanceof List ? (List<Map>) line : [(Map) line + [conditionSeqId: "01"]]
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Rule").parameters([ruleSetId: decisionId, ruleName: "piece rule",
                priority: 10, conditions: lines,
                actions: [[actionTypeId: "RAT_OUTCOME", outcomeId: "FAILS"]]]).call()
        List<String> errors = ec.message.errors.collect()
        ec.message.clearAll()
        return errors
    }

    List<String> outcomeFor(Object line, Map fact, String decisionId = ruleSetId, String factFactTypeId = factTypeId) {
        assert storeErrors(line, decisionId) == []
        Map result = ec.service.sync().name("moqui.rules.RuleServices.evaluate#RuleSet")
                .parameters([ruleSetId: decisionId, facts: [[factTypeId: factFactTypeId, fact: fact]]]).call()
        assert !ec.message.hasError(), ec.message.errorsString
        return result.outcomes
    }

    def "a rule using a piece matches exactly when the piece's lines match"() {
        given: storePiece("REFUNDED", factTypeId, [compare("01", "netsuite_status", "ENTCO_EQUALS", "Refunded")])
        expect:
        outcomeFor(piece("REFUNDED"), [netsuite_status: "Refunded"]) == ["FAILS"]
        outcomeFor(piece("REFUNDED"), [netsuite_status: "Pending Receipt"]) == []
    }

    def "a piece that names itself, directly or through another piece, is refused with the chain"() {
        given:
        storePiece("P2", factTypeId, [compare("01", "netsuite_status", "ENTCO_EQUALS", "Refunded")])
        storePiece("P1", factTypeId, [piece("P2")])
        expect:
        storePieceErrors("P2", factTypeId, [piece("P1")]).any { it.contains("RULES_TEST_P2 > RULES_TEST_P1 > RULES_TEST_P2") }
        storePieceErrors("P3", factTypeId, [piece("P3")]).any { it.contains("RULES_TEST_P3 > RULES_TEST_P3") }
        ec.entity.find("rules.RulePiece").condition("pieceId", id("P3")).count() == 0
    }

    def "a piece over a fact type the decision does not read is refused when the rule is saved"() {
        given: storePiece("OTHER", "checkResult", [[conditionSeqId: "01", conditionTypeId: "RCT_COMPARE", factTypeId: "checkResult",
                fieldName: "refusedCount", operatorEnumId: "ENTCO_EQUALS", compareValue: "0"]])
        expect: storeErrors(piece("OTHER")).any { it.contains("does not read") }
    }

    def "changing a piece changes every decision that uses it on the next call"() {
        given: storePiece("REFUNDED", factTypeId, [compare("01", "netsuite_status", "ENTCO_EQUALS", "Refunded")])
        expect: outcomeFor(piece("REFUNDED"), [netsuite_status: "Closed"]) == []
        when: storePiece("REFUNDED", factTypeId, [compare("01", "netsuite_status", "ENTCO_EQUALS", "Closed")])
        then:
        outcomeFor(piece("REFUNDED"), [netsuite_status: "Closed"]) == ["FAILS"]
        outcomeFor(piece("REFUNDED"), [netsuite_status: "Refunded"]) == []
    }

    def "a piece that does not exist, or a piece over an unknown field, is refused and nothing is saved"() {
        expect:
        storeErrors(piece("MISSING")).any { it.contains("piece RULES_TEST_MISSING does not exist") }
        storePieceErrors("BAD", factTypeId, [compare("01", "nope", "ENTCO_EQUALS", "x")]).any { it.contains("nope") }
        storePieceErrors("BAD", "NO_SUCH_FACT_TYPE", [compare("01", "nope", "ENTCO_EQUALS", "x")]).any { it.contains("NO_SUCH_FACT_TYPE does not exist") }
        ec.entity.find("rules.RulePiece").condition("pieceId", id("BAD")).count() == 0
    }

    def "a piece nests up to 3 levels but no deeper"() {
        given:
        storePiece("L1", factTypeId, [compare("01", "netsuite_status", "ENTCO_EQUALS", "Refunded")])
        storePiece("L2", factTypeId, [piece("L1")])
        storePiece("L3", factTypeId, [piece("L2")])
        expect:
        storePieceErrors("L4", factTypeId, [piece("L3")]).any { it.contains("3 levels") }
        outcomeFor(piece("L3"), [netsuite_status: "Refunded"]) == ["FAILS"]
    }

    def "the view finds the rules that use a piece"() {
        given:
        storePiece("USED", factTypeId, [compare("01", "netsuite_status", "ENTCO_EQUALS", "Refunded")])
        expect:
        storeErrors(piece("USED")) == []
        ec.entity.find("rules.RuleSetConditionPieceUse").condition("pieceId", id("USED")).list()*.ruleSetId == [ruleSetId]
    }

    def "a piece with an exists line saves, and a rule using it matches when some item matches"() {
        given: "a decision that reads sales orders, whose items are order items"
        ec.entity.makeValue("rules.RuleSet").setAll([ruleSetId: otherRuleSetId, firstMatchOnly: "N"]).create()
        ec.entity.makeValue("rules.RuleSetFactType").setAll([ruleSetId: otherRuleSetId, factTypeId: "salesOrder"]).create()
        ec.entity.makeValue("rules.RuleSetOutcome").setAll([ruleSetId: otherRuleSetId, outcomeId: "FAILS"]).create()

        when:
        List<String> errors = storePieceErrors("HAS_DIGITAL", "salesOrder", [
                [conditionSeqId: "01", conditionTypeId: "RCT_EXISTS", factTypeId: "salesOrder", fieldName: "items"],
                [conditionSeqId: "02", parentConditionSeqId: "01", conditionTypeId: "RCT_COMPARE", factTypeId: "orderItem",
                 fieldName: "productTypeId", operatorEnumId: "ENTCO_EQUALS", compareValue: "DIGITAL_GOOD"]])

        then:
        errors == []
        outcomeFor(piece("HAS_DIGITAL"), [orderId: "P1", items: [[orderItemSeqId: "01", productTypeId: "DIGITAL_GOOD"]]],
                otherRuleSetId, "salesOrder") == ["FAILS"]
        outcomeFor(piece("HAS_DIGITAL"), [orderId: "P2", items: [[orderItemSeqId: "01", productTypeId: "FINISHED_GOOD"]]],
                otherRuleSetId, "salesOrder") == []
    }

    def "a not line over a one-line piece with a compare-two-fields line fires exactly when the line does not hold"() {
        given:
        storePiece("SAME_STATUS", factTypeId, [[conditionSeqId: "01", conditionTypeId: "RCT_COMPARE_FIELD", factTypeId: factTypeId,
                fieldName: "netsuite_status", operatorEnumId: "ENTCO_EQUALS", compareFieldName: "hotwax_status"]])
        List<Map> notPiece = [[conditionSeqId: "01", conditionTypeId: "RCT_NOT"], piece("SAME_STATUS") + [conditionSeqId: "02", parentConditionSeqId: "01"]]

        expect:
        outcomeFor(notPiece, [netsuite_status: "Refunded", hotwax_status: "Completed"]) == ["FAILS"]
        outcomeFor(notPiece, [netsuite_status: "Refunded", hotwax_status: "Refunded"]) == []
    }
}
