import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import java.sql.Timestamp

/**
 * Moqui skips entity filters on any entity whose full name starts with "moqui.", so the component's entities
 * live in the "rules" package. This spec proves the package, and that a real filter then keeps a user to the
 * decisions it allows.
 */
class TenantFilterTests extends Specification {
    @Shared ExecutionContext ec
    @Shared String userId = "RULES_TEST_FILTER_USER"
    @Shared String username = "rules.test.filter"
    @Shared String visibleId = "RULES_TEST_VISIBLE"
    @Shared String hiddenId = "RULES_TEST_HIDDEN"
    @Shared Timestamp fromDate = new Timestamp(System.currentTimeMillis() - 60000L)

    // [entity, primary key, other fields] in creation order; removal runs in reverse
    @Shared List<List> securityRows = [
            ["moqui.security.UserAccount", [userId: userId], [username: username, userFullName: "Rules filter test"]],
            ["moqui.security.UserGroup", [userGroupId: "RULES_TEST_FILTER_GROUP"], [description: "Rules filter test"]],
            ["moqui.security.UserGroupMember", [userGroupId: "RULES_TEST_FILTER_GROUP", userId: userId, fromDate: fromDate], [:]],
            ["moqui.security.ArtifactGroup", [artifactGroupId: "RULES_TEST_FILTER_ARTIFACTS"], [description: "Rules filter test"]],
            ["moqui.security.ArtifactGroupMember", [artifactGroupId: "RULES_TEST_FILTER_ARTIFACTS", artifactName: "rules.RuleSet"],
                    [artifactTypeEnumId: "AT_ENTITY"]],
            ["moqui.security.ArtifactAuthz", [artifactAuthzId: "RULES_TEST_FILTER_AUTHZ"],
                    [userGroupId: "RULES_TEST_FILTER_GROUP", artifactGroupId: "RULES_TEST_FILTER_ARTIFACTS",
                     authzTypeEnumId: "AUTHZT_ALWAYS", authzActionEnumId: "AUTHZA_VIEW"]],
            ["moqui.security.EntityFilterSet", [entityFilterSetId: "RULES_TEST_FILTER_SET"], [description: "Rules filter test"]],
            ["moqui.security.EntityFilter", [entityFilterId: "RULES_TEST_FILTER"],
                    [entityFilterSetId: "RULES_TEST_FILTER_SET", entityName: "rules.RuleSet", filterMap: "[ruleSetId: '" + visibleId + "']"]],
            ["moqui.security.ArtifactAuthzFilter", [artifactAuthzId: "RULES_TEST_FILTER_AUTHZ", entityFilterSetId: "RULES_TEST_FILTER_SET"], [:]],
    ]

    def setupSpec() {
        ec = Moqui.getExecutionContext()
        ec.user.internalLoginUser("john.doe")
        ec.artifactExecution.disableAuthz()
        removeTestData()
        for (String ruleSetId in [visibleId, hiddenId])
            ec.entity.makeValue("rules.RuleSet").setAll([ruleSetId: ruleSetId, firstMatchOnly: "Y"]).create()
        for (List row in securityRows) ec.entity.makeValue((String) row[0]).setAll((Map) row[1]).setAll((Map) row[2]).create()
        ec.user.logoutUser()
        ec.user.internalLoginUser(username)
        ec.artifactExecution.enableAuthz()
    }

    def cleanupSpec() {
        ec.artifactExecution.disableAuthz()
        ec.user.logoutUser()
        ec.user.internalLoginUser("john.doe")
        removeTestData()
        ec.user.logoutUser()
        ec.destroy()
    }

    void removeTestData() {
        for (List row in securityRows.reverse()) ec.entity.find((String) row[0]).condition((Map) row[1]).deleteAll()
        for (String ruleSetId in [visibleId, hiddenId]) ec.entity.find("rules.RuleSet").condition("ruleSetId", ruleSetId).deleteAll()
    }

    def "no moqui-rules entity is in the moqui package, so entity filters can apply"() {
        expect:
        ["FactType","FactField","FactNode","FactNodeCondition","RuleSet","RuleSetFactType","RuleSetOutcome","RuleSetRule",
         "RuleConditionType","RuleSetCondition","RuleSetConditionAndRule","RulePiece","RulePieceCondition",
         "RuleSetConditionPieceUse","RuleActionType","RuleSetAction","RuleSetActionField","FileCheck","FileCheckRecord"]
                .every { ec.entity.isEntityDefined("rules." + it) && !ec.entity.isEntityDefined("moqui.rules." + it) }
    }

    def "no moqui-rules entity shares a table with any other entity"() {
        when: "every defined entity is read with its table name"
        def efi = ec.entity
        Map<String, String> tableByEntity = [:]
        for (String entityName in efi.getAllEntityNames()) {
            def ed
            try { ed = efi.getEntityDefinition(entityName) } catch (Exception e) { continue }
            if (ed != null && !ed.isViewEntity) tableByEntity.put(entityName, ed.getTableName().toUpperCase(Locale.ROOT))
        }
        Map<String, String> rulesTables = tableByEntity.findAll { it.key.startsWith("rules.") }
        List<String> clashes = rulesTables.collectMany { String rulesEntity, String table ->
            tableByEntity.findAll { it.key != rulesEntity && it.value == table }.collect { "${rulesEntity} and ${it.key} share ${table}".toString() } }

        then: "the component has its 17 entities, each on a table of its own named RULES_"
        rulesTables.size() == 17
        rulesTables.values().every { it.startsWith("RULES_") }
        clashes == []
    }

    def "an entity filter keeps a filtered user to the decisions it allows"() {
        when: "the filtered user reads both decisions with authz on"
        List<String> seen = ec.entity.find("rules.RuleSet").condition("ruleSetId", "in", [visibleId, hiddenId]).list()*.ruleSetId

        then: "the hidden decision is not returned"
        !ec.artifactExecution.authzDisabled
        seen == [visibleId]
    }
}
