import org.moqui.context.ExecutionContext

/** Specs load the rows they need themselves: on a new database (no Enumeration at all, as the specs' own H2 database
 *  starts, see build.gradle) every component's seed, install and demo data, the way an empty-db-load would (demo carries
 *  john.doe, whom the specs sign in as); then the component's seed and demo rows when they are missing.
 *  It leaves those rows in the database (by design); a failed load is retried on the next call. */
class RulesTestData {
    private static boolean checked = false

    static synchronized void ensureLoaded(ExecutionContext ec) {
        if (checked) return
        boolean wasDisabled = ec.artifactExecution.disableAuthz()
        try {
            // Two loads: one load with all four types reads files in path order, so a demo file (RuleDemoData) can run
            // before the seed file whose enumerations its rows point at, and its rows are refused.
            if (ec.entity.find("moqui.basic.Enumeration").count() == 0) {
                ec.entity.makeDataLoader().dataTypes(["seed", "seed-initial", "install"] as Set<String>).load()
                ec.entity.makeDataLoader().dataTypes(["demo"] as Set<String>).load()
            }
            if (ec.entity.find("rules.RuleConditionType").condition("conditionTypeId", "RCT_PIECE").count() == 0) {
                ec.entity.makeDataLoader().location("component://moqui-rules/data/RuleSeedData.xml").load()
                ec.entity.makeDataLoader().location("component://moqui-rules/data/RuleDemoData.xml").load()
            }
            checked = true
        } finally {
            if (!wasDisabled) ec.artifactExecution.enableAuthz()
        }
    }
}
