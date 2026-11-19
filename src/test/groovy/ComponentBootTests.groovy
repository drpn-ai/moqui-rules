import spock.lang.*
import org.moqui.context.ExecutionContext
import org.moqui.Moqui
import org.moqui.rules.RuleSets

class ComponentBootTests extends Specification {
    @Shared ExecutionContext ec

    def setupSpec() { ec = Moqui.getExecutionContext(); ec.user.internalLoginUser("john.doe"); RulesTestData.ensureLoaded(ec) }
    def cleanupSpec() { if (ec != null) ec.destroy() }

    def "Moqui starts with the moqui-rules component loaded"() {
        expect:
        ec.factory.getComponentBaseLocations().containsKey("moqui-rules")
    }

    def "every seeded decision builds"() {
        when:
        RuleSets ruleSets = ec.getTool("Rules", RuleSets)
        // data loaded after boot is not built yet, so ask for each decision; this builds it if it is not cached
        List builtRuleSets = ["SWITCH_ON_A_JOB", "HAS_DIGITAL_GOOD"].collect { ruleSets.builtRuleSet(it) }
        List<String> builtRuleSetIds = ec.cache.getCache(RuleSets.CACHE_NAME).collect { it.key }

        then:
        ruleSets != null
        builtRuleSets.every { it != null }
        builtRuleSetIds.containsAll(["SWITCH_ON_A_JOB", "HAS_DIGITAL_GOOD"])
    }
}
