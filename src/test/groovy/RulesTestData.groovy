import org.moqui.context.ExecutionContext

/** Specs load the component's seed and demo rows themselves when the dev database lacks them. */
class RulesTestData {
    private static boolean checked = false

    static synchronized void ensureLoaded(ExecutionContext ec) {
        if (checked) return
        checked = true
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            if (ec.entity.find("rules.RuleConditionType").condition("conditionTypeId", "RCT_COMPARE").count() == 0) {
                ec.entity.makeDataLoader().location("component://moqui-rules/data/RuleSeedData.xml").load()
                ec.entity.makeDataLoader().location("component://moqui-rules/data/RuleDemoData.xml").load()
            }
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
