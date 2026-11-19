import org.moqui.context.ExecutionContext

/** Specs load the component's seed and demo rows themselves when the dev database lacks them.
 *  It leaves those rows in the database (by design); a failed load is retried on the next call. */
class RulesTestData {
    private static boolean checked = false

    static synchronized void ensureLoaded(ExecutionContext ec) {
        if (checked) return
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            if (ec.entity.find("rules.RuleConditionType").condition("conditionTypeId", "RCT_COMPARE").count() == 0) {
                ec.entity.makeDataLoader().location("component://moqui-rules/data/RuleSeedData.xml").load()
                ec.entity.makeDataLoader().location("component://moqui-rules/data/RuleDemoData.xml").load()
            }
            checked = true
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
