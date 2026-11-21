import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import java.sql.Timestamp

/**
 * A host application adds its own entity filters to the component's entities, such as a tenant per row. This spec
 * proves such a filter on a joined member applies through the component's two views, and that build#Fact answers a
 * caller the filter hides "does not exist" even when another caller built that fact type already.
 */
class HostFilterTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String userId = "RULES_TEST_HOST_USER"
    @Shared String username = "rules.test.host"
    @Shared String visibleId = "RULES_TEST_HOST_SHOWN"
    @Shared String hiddenId = "RULES_TEST_HOST_HIDDEN"
    @Shared String factTypeId = "RULES_TEST_HOST_FACT"
    @Shared String hiddenFactTypeId = "RULES_TEST_HOST_FACT_HIDDEN"
    @Shared String pieceId = "RULES_TEST_HOST_PIECE"
    @Shared String shownRuleId = "RULES_TEST_HOST_R1"
    @Shared String hiddenRuleId = "RULES_TEST_HOST_R2"
    @Shared Timestamp fromDate = new Timestamp(System.currentTimeMillis() - 60000L)

    // [entity, primary key, other fields] in creation order; removal runs in reverse
    @Shared List<List> ruleRows = [
            ["rules.FactType", [factTypeId: factTypeId], [:]],
            ["rules.FactField", [factTypeId: factTypeId, fieldName: "status"], [valueTypeEnumId: "FVT_TEXT"]],
            ["rules.FactNode", [factTypeId: factTypeId, nodeSeqId: "01"], [sequenceNum: 1, nodeTypeEnumId: "FNT_VALUE",
                    fieldName: "status", parameterName: "status"]],
            ["rules.FactType", [factTypeId: hiddenFactTypeId], [:]],
            ["rules.FactField", [factTypeId: hiddenFactTypeId, fieldName: "status"], [valueTypeEnumId: "FVT_TEXT"]],
            ["rules.FactNode", [factTypeId: hiddenFactTypeId, nodeSeqId: "01"], [sequenceNum: 1, nodeTypeEnumId: "FNT_VALUE",
                    fieldName: "status", parameterName: "status"]],
            ["rules.RulePiece", [pieceId: pieceId], [factTypeId: factTypeId]],
            ["rules.RulePieceCondition", [pieceId: pieceId, conditionSeqId: "01"], [sequenceNum: 1, conditionTypeId: "RCT_COMPARE",
                    factTypeId: factTypeId, fieldName: "status", operatorEnumId: "ENTCO_EQUALS", compareValue: "OPEN"]],
            ["rules.RuleSet", [ruleSetId: visibleId], [firstMatchOnly: "N"]],
            ["rules.RuleSet", [ruleSetId: hiddenId], [firstMatchOnly: "N"]],
            ["rules.RuleSetRule", [ruleId: shownRuleId], [ruleSetId: visibleId, ruleName: "shown rule", priority: 1]],
            ["rules.RuleSetRule", [ruleId: hiddenRuleId], [ruleSetId: hiddenId, ruleName: "hidden rule", priority: 1]],
            ["rules.RuleSetCondition", [ruleId: shownRuleId, conditionSeqId: "01"], [sequenceNum: 1, conditionTypeId: "RCT_PIECE",
                    compareValue: pieceId]],
            ["rules.RuleSetCondition", [ruleId: shownRuleId, conditionSeqId: "02"], [sequenceNum: 2, conditionTypeId: "RCT_COMPARE",
                    factTypeId: factTypeId, fieldName: "status", operatorEnumId: "ENTCO_EQUALS", compareValue: "OPEN"]],
            ["rules.RuleSetCondition", [ruleId: hiddenRuleId, conditionSeqId: "01"], [sequenceNum: 1, conditionTypeId: "RCT_PIECE",
                    compareValue: pieceId]],
    ]

    @Shared List<List> securityRows = [
            ["moqui.security.UserAccount", [userId: userId], [username: username, userFullName: "Rules host filter test"]],
            ["moqui.security.UserGroup", [userGroupId: "RULES_TEST_HOST_GROUP"], [description: "Rules host filter test"]],
            ["moqui.security.UserGroupMember", [userGroupId: "RULES_TEST_HOST_GROUP", userId: userId, fromDate: fromDate], [:]],
            ["moqui.security.ArtifactGroup", [artifactGroupId: "RULES_TEST_HOST_ARTIFACTS"], [description: "Rules host filter test"]],
            ["moqui.security.ArtifactGroupMember", [artifactGroupId: "RULES_TEST_HOST_ARTIFACTS", artifactName: "rules.RuleSetConditionAndRule"],
                    [artifactTypeEnumId: "AT_ENTITY"]],
            ["moqui.security.ArtifactGroupMember", [artifactGroupId: "RULES_TEST_HOST_ARTIFACTS", artifactName: "rules.RuleSetConditionPieceUse"],
                    [artifactTypeEnumId: "AT_ENTITY"]],
            ["moqui.security.ArtifactGroupMember", [artifactGroupId: "RULES_TEST_HOST_ARTIFACTS", artifactName: "moqui.rules.RuleServices.build#Fact"],
                    [artifactTypeEnumId: "AT_SERVICE", inheritAuthz: "Y"]],
            ["moqui.security.ArtifactAuthz", [artifactAuthzId: "RULES_TEST_HOST_AUTHZ"],
                    [userGroupId: "RULES_TEST_HOST_GROUP", artifactGroupId: "RULES_TEST_HOST_ARTIFACTS",
                     authzTypeEnumId: "AUTHZT_ALWAYS", authzActionEnumId: "AUTHZA_ALL"]],
            ["moqui.security.EntityFilterSet", [entityFilterSetId: "RULES_TEST_HOST_SET"], [description: "Rules host filter test"]],
            // A filter on fields neither view aliased before: ruleSetId is not in RuleSetConditionAndRule, ruleName and
            // conditionTypeId are not in RuleSetConditionPieceUse.
            ["moqui.security.EntityFilter", [entityFilterId: "RULES_TEST_HOST_RULE"], [entityFilterSetId: "RULES_TEST_HOST_SET",
                    entityName: "rules.RuleSetRule", filterMap: "[ruleSetId: '" + visibleId + "', ruleName: 'shown rule']"]],
            ["moqui.security.EntityFilter", [entityFilterId: "RULES_TEST_HOST_LINE"], [entityFilterSetId: "RULES_TEST_HOST_SET",
                    entityName: "rules.RuleSetCondition", filterMap: "[conditionTypeId: 'RCT_PIECE']"]],
            ["moqui.security.EntityFilter", [entityFilterId: "RULES_TEST_HOST_FACT"], [entityFilterSetId: "RULES_TEST_HOST_SET",
                    entityName: "rules.FactType", filterMap: "[factTypeId: '" + factTypeId + "']"]],
            ["moqui.security.ArtifactAuthzFilter", [artifactAuthzId: "RULES_TEST_HOST_AUTHZ", entityFilterSetId: "RULES_TEST_HOST_SET"], [:]],
    ]

    def setupSpec() {
        ec = Moqui.getExecutionContext(); RulesTestData.ensureLoaded(ec)
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeTestData()
        for (List row in ruleRows + securityRows) ec.entity.makeValue((String) row[0]).setAll((Map) row[1]).setAll((Map) row[2]).create()
        actAsFilteredUser()
    }

    def cleanupSpec() {
        actAsJohnDoe()
        removeTestData()
        ec.user.logoutUser()
        ec.destroy()
    }

    void actAsFilteredUser() {
        ec.artifactExecution.disableAuthz()
        ec.user.logoutUser()
        ec.user.internalLoginUser(username)
        ec.artifactExecution.enableAuthz()
        ec.message.clearAll()
    }

    void actAsJohnDoe() {
        ec.artifactExecution.disableAuthz()
        ec.user.logoutUser()
        ec.user.internalLoginUser("john.doe")
        ec.message.clearAll()
    }

    void removeTestData() {
        for (List row in (ruleRows + securityRows).reverse()) ec.entity.find((String) row[0]).condition((Map) row[1]).deleteAll()
        ec.cache.getCache('moqui.rules.built.fact').clear()
    }

    def "a host filter on a rule and on a line applies through the rule-and-line view"() {
        when: "the filtered user reads the lines of both rules"
        List<String> seen = ec.entity.find("rules.RuleSetConditionAndRule").condition("ruleId", "in", [shownRuleId, hiddenRuleId])
                .useCache(false).list().collect { "${it.ruleId}/${it.conditionSeqId}".toString() }

        then: "only the piece line of the shown rule is returned"
        !ec.artifactExecution.authzDisabled
        seen == [shownRuleId + "/01"]
    }

    def "a host filter on a rule and on a line applies through the piece-use view"() {
        when: "the filtered user reads every rule using the piece"
        List<String> ruleSetIds = ec.entity.find("rules.RuleSetConditionPieceUse").condition("pieceId", pieceId)
                .useCache(false).list()*.ruleSetId

        then: "only the shown decision's rule is returned"
        !ec.artifactExecution.authzDisabled
        ruleSetIds == [visibleId]
    }

    def "build#Fact answers a fact type the caller cannot see as missing, even when another caller built it"() {
        when: "another caller builds the hidden fact type, so it is in the built-fact cache"
        actAsJohnDoe()
        Map builtByOther = ec.service.sync().name("moqui.rules.RuleServices.build#Fact")
                .parameters([factTypeId: hiddenFactTypeId, parameters: [status: "OPEN"]]).call()
        boolean cachedByOther = ec.cache.getCache('moqui.rules.built.fact').get(hiddenFactTypeId) != null
        actAsFilteredUser()
        Map hidden = ec.service.sync().name("moqui.rules.RuleServices.build#Fact")
                .parameters([factTypeId: hiddenFactTypeId, parameters: [status: "OPEN"]]).call()
        List<String> hiddenErrors = ec.message.errors.collect()
        ec.message.clearAll()
        Map shown = ec.service.sync().name("moqui.rules.RuleServices.build#Fact")
                .parameters([factTypeId: factTypeId, parameters: [status: "OPEN"]]).call()
        List<String> shownErrors = ec.message.errors.collect()
        ec.message.clearAll()

        then: "the hidden one does not exist for the filtered user; the visible one builds"
        builtByOther.fact == [status: "OPEN"]
        cachedByOther
        hidden?.fact == null
        hiddenErrors == ["Fact type ${hiddenFactTypeId} does not exist".toString()]
        shownErrors == []
        shown.fact == [status: "OPEN"]
    }
}
