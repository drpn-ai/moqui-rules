import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.entity.EntityCondition
import org.moqui.entity.EntityValue
import org.moqui.rules.RuleSets

class NormalizedTextTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String ruleSetId = "RULES_TEST_NORM_DECISION"
    @Shared String factTypeId = "RULES_TEST_NORM_FACT"
    @Shared String pieceId = "RULES_TEST_NORM_P"

    def setupSpec() {
        ec = Moqui.getExecutionContext(); RulesTestData.ensureLoaded(ec)
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeAll()
        ec.entity.makeValue("rules.FactType").setAll([factTypeId: factTypeId]).create()
        [code: "FVT_TEXT", qty: "FVT_INTEGER", a: "FVT_TEXT", b: "FVT_TEXT"].each { name, type ->
            ec.entity.makeValue("rules.FactField").setAll([factTypeId: factTypeId, fieldName: name, valueTypeEnumId: type]).create() }
        ec.entity.makeValue("rules.RuleSet").setAll([ruleSetId: ruleSetId, firstMatchOnly: "N"]).create()
        ec.entity.makeValue("rules.RuleSetFactType").setAll([ruleSetId: ruleSetId, factTypeId: factTypeId]).create()
        ec.entity.makeValue("rules.RuleSetOutcome").setAll([ruleSetId: ruleSetId, outcomeId: "FAILS"]).create()

        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Piece").parameters([pieceId: pieceId, factTypeId: factTypeId,
                conditions: single("code", "ENTCO_EQUALS", "abc")]).call()
        assert !ec.message.hasError(), ec.message.errorsString
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
            // lines nest more than one level deep: delete leaves first
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
        List<String> pieceIds = ec.entity.find("rules.RulePiece").condition("pieceId", EntityCondition.ComparisonOperator.LIKE, "RULES_TEST_NORM_%").list()*.pieceId
        if (pieceIds) {
            ec.entity.find("rules.RulePieceCondition").condition("pieceId", "in", pieceIds)
                    .condition("parentConditionSeqId", EntityCondition.ComparisonOperator.IS_NOT_NULL, null).deleteAll()
            ec.entity.find("rules.RulePieceCondition").condition("pieceId", "in", pieceIds).deleteAll()
            ec.entity.find("rules.RulePiece").condition("pieceId", "in", pieceIds).deleteAll()
        }
        ec.entity.find("rules.FactField").condition("factTypeId", factTypeId).deleteAll()
        ec.entity.find("rules.FactType").condition("factTypeId", factTypeId).deleteAll()
    }

    /** A compare line; normalizeText is Y unless given otherwise. */
    static Map compare(String seq, String fieldName, String operatorEnumId, String value, String normalizeText = "Y") {
        [conditionSeqId: seq, conditionTypeId: "RCT_COMPARE", factTypeId: "RULES_TEST_NORM_FACT", fieldName: fieldName,
         operatorEnumId: operatorEnumId, compareValue: value, normalizeText: normalizeText]
    }

    static List<Map> single(String field, String operator, String value, String normalizeText = "Y") {
        [compare("01", field, operator, value, normalizeText)]
    }

    static List<Map> notOver(Map line) { [[conditionSeqId: "01", conditionTypeId: "RCT_NOT"], line + [parentConditionSeqId: "01"]] }

    static List<Map> anyOf(Map first, Map second) {
        [[conditionSeqId: "01", conditionTypeId: "RCT_ANY"], first + [parentConditionSeqId: "01"], second + [parentConditionSeqId: "01"]]
    }

    static List<Map> notOverAny(Map first, Map second) {
        [[conditionSeqId: "01", conditionTypeId: "RCT_NOT"], [conditionSeqId: "02", parentConditionSeqId: "01", conditionTypeId: "RCT_ANY"],
         first + [parentConditionSeqId: "02"], second + [parentConditionSeqId: "02"]]
    }

    static Map codeAbc(String seq) { compare(seq, "code", "ENTCO_EQUALS", "abc") }
    static Map qtyNine(String seq) { compare(seq, "qty", "ENTCO_EQUALS", "9", null) }
    static Map aEqualsB(String seq) {
        [conditionSeqId: seq, conditionTypeId: "RCT_COMPARE_FIELD", factTypeId: "RULES_TEST_NORM_FACT", fieldName: "a",
         operatorEnumId: "ENTCO_EQUALS", compareFieldName: "b"]
    }

    List<String> storeErrors(List<Map> lines) {
        ec.message.clearAll()
        ec.service.sync().name("moqui.rules.RuleServices.store#Rule").parameters([ruleSetId: ruleSetId, ruleName: "norm rule",
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

    Map explain(Map fact) {
        Map out = ec.service.sync().name("moqui.rules.RuleServices.explain#RuleSet")
                .parameters([ruleSetId: ruleSetId, factTypeId: factTypeId, fact: fact]).call()
        assert !ec.message.hasError(), ec.message.errorsString
        return out
    }

    @Unroll
    def "#caseName"() {
        expect: fires(lines, fact) == expected
        where:
        caseName                             | lines                                                         | fact                         | expected
        "case ignored"                       | single("code", "ENTCO_EQUALS", "abc")                         | [code: "ABC"]                | true
        "spaces ignored"                     | single("code", "ENTCO_EQUALS", "abc")                         | [code: "  abc "]             | true
        "inner spaces count"                 | single("code", "ENTCO_EQUALS", "abc")                         | [code: "a bc"]               | false
        "only spaces is no value"            | single("code", "ENTCO_EQUALS", "abc")                         | [code: "   "]                | false
        "not equals, only spaces"            | single("code", "ENTCO_NOT_EQUALS", "abc")                     | [code: "   "]                | true
        "missing field, not equals"          | single("code", "ENTCO_NOT_EQUALS", "abc")                     | [:]                          | true
        "in, case ignored"                   | single("code", "ENTCO_IN", '["Open","closed"]')               | [code: " CLOSED"]            | true
        "not in, case ignored"               | single("code", "ENTCO_NOT_IN", '["Open"]')                    | [code: "open"]               | false
        "like keeps wildcards"               | single("code", "ENTCO_LIKE", "ab%")                           | [code: " ABCD"]              | true
        "not like"                           | single("code", "ENTCO_NOT_LIKE", "ab%")                       | [code: "xab"]                | true
        "not over a normalized line"         | notOver(compare("02", "code", "ENTCO_EQUALS", "abc"))         | [code: "ABC"]                | false
        "without the flag, exact as before"  | single("code", "ENTCO_EQUALS", "abc", null)                   | [code: "ABC"]                | false
        "on a piece line"                    | [[conditionSeqId: "01", conditionTypeId: "RCT_PIECE", compareValue: "RULES_TEST_NORM_P"]] | [code: "ABC"] | true
        "normalized inside an any"           | anyOf(codeAbc("02"), qtyNine("03"))                           | [code: " ABC", qty: 1]       | true
        "neither choice"                     | anyOf(codeAbc("02"), qtyNine("03"))                           | [code: "x", qty: 1]          | false
        "not over that any"                  | notOverAny(codeAbc("03"), qtyNine("04"))                      | [code: "abc"]                | false
        "compare-two-fields inside an any"   | anyOf(aEqualsB("02"), qtyNine("03"))                          | [a: "x", b: "x", qty: 1]     | true
        "like: _ is one character"           | single("code", "ENTCO_LIKE", "a_c")                           | [code: "ABC"]                | true
        "like: _ is not zero characters"     | single("code", "ENTCO_LIKE", "a_c")                           | [code: "AC"]                 | false
        "like: % alone matches anything"     | single("code", "ENTCO_LIKE", "%")                             | [code: "X"]                  | true
        "like: %% matches an empty run"      | single("code", "ENTCO_LIKE", "a%%c")                          | [code: "AC"]                 | true
        "like: leading % then a last letter" | single("code", "ENTCO_LIKE", "%b")                            | [code: "AB"]                 | true
        "like: the last letter must be last" | single("code", "ENTCO_LIKE", "%b")                            | [code: "BA"]                 | false
        "like: _ over an empty value"        | single("code", "ENTCO_LIKE", "_")                             | [code: ""]                   | false
    }

    def "the flag is refused off a text compare line with an operator it fits, naming the field and the reason"() {
        expect:
        storeErrors(single("qty", "ENTCO_EQUALS", "9")) ==
                ["Condition 01 of rule norm rule: field qty is not text, so it cannot ignore case and surrounding spaces"]
        storeErrors(single("code", "ENTCO_GREATER", "abc")) ==
                ["Condition 01 of rule norm rule: operator ENTCO_GREATER on field code cannot ignore case and surrounding spaces"]
        storeErrors([aEqualsB("01") + [normalizeText: "Y"]]) ==
                ["Condition 01 of rule norm rule: only a compare line can ignore case and surrounding spaces, not this RCT_COMPARE_FIELD line on field a"]
        storeErrors([[conditionSeqId: "01", conditionTypeId: "RCT_ALL", normalizeText: "Y"], codeAbc("02") + [parentConditionSeqId: "01"]]) ==
                ["Condition 01 of rule norm rule: only a compare line can ignore case and surrounding spaces, not this RCT_ALL line"]
    }

    def "explain says the line ignores case and surrounding spaces, and traces the lines under an any line"() {
        when:
        assert storeErrors(single("code", "ENTCO_EQUALS", "abc")) == []
        Map single = explain([code: "ABC"])
        assert storeErrors(anyOf(codeAbc("02"), qtyNine("03"))) == []
        Map overAny = explain([code: " ABC", qty: 1])

        then:
        single.rules[0].matched
        single.rules[0].lines*.text == ["code equals abc (ignoring case and surrounding spaces)"]
        single.rules[0].lines*.holds == [true]
        overAny.rules[0].matched
        overAny.rules[0].lines*.text == ["any of"]
        overAny.rules[0].lines*.holds == [true]
        overAny.rules[0].lines[0].lines*.text == ["code equals abc (ignoring case and surrounding spaces)", "qty equals 9"]
        overAny.rules[0].lines[0].lines*.holds == [true, false]
    }

    def "a like pattern with many wildcards stays fast on long text"() {
        given:
        assert storeErrors(single("code", "ENTCO_LIKE", "%a%a%a%a%a%a%a%a%b")) == []
        ((RuleSets) ec.getTool("Rules", RuleSets.class)).builtRuleSet(ruleSetId)
        String longText = "a" * 20000

        when:
        long started = System.nanoTime()
        Map result = ec.service.sync().name("moqui.rules.RuleServices.evaluate#RuleSet")
                .parameters([ruleSetId: ruleSetId, facts: [[factTypeId: factTypeId, fact: [code: longText]]]]).call()
        long elapsedMillis = (System.nanoTime() - started).intdiv(1000000L)
        println "like over 20000 characters took ${elapsedMillis} ms"

        then:
        !ec.message.hasError()
        result.outcomes == []
        elapsedMillis < 1000
    }

    List<EntityValue> normalizeTextAudit(String ruleId) {
        ec.entity.find("moqui.entity.EntityAuditLog").condition("changedEntityName", "rules.RuleSetCondition")
                .condition("changedFieldName", "normalizeText").condition("pkPrimaryValue", ruleId)
                .orderBy("changedDate").useCache(false).list()
    }

    def "changing only the flag on a stored line is audited and rebuilds the decision"() {
        given:
        assert storeErrors(single("code", "ENTCO_EQUALS", "abc", null)) == []
        String ruleId = ec.entity.find("rules.RuleSetRule").condition([ruleSetId: ruleSetId, ruleName: "norm rule"]).useCache(false).one().ruleId
        int auditedBefore = normalizeTextAudit(ruleId).size()
        ((RuleSets) ec.getTool("Rules", RuleSets.class)).builtRuleSet(ruleSetId)
        assert ec.cache.getCache('moqui.rules.built.rule.set').containsKey(ruleSetId)

        when:
        assert storeErrors(single("code", "ENTCO_EQUALS", "abc")) == []
        List<EntityValue> audited = normalizeTextAudit(ruleId)

        then:
        audited.size() == auditedBefore + 1
        audited[-1].newValueText == "Y"
        !ec.cache.getCache('moqui.rules.built.rule.set').containsKey(ruleSetId)
    }
}
