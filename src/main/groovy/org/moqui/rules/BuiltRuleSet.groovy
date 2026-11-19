package org.moqui.rules

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.kie.api.KieBase
import org.kie.api.prototype.PrototypeFact
import org.kie.api.prototype.PrototypeFactInstance
import org.kie.api.runtime.KieSession
import org.moqui.context.ExecutionContext

class BuiltRuleSet {
    static final String MATCHES_GLOBAL = "matches"
    /** The field of a fact instance that holds the caller's record id. No fact field may have this name. */
    static final String RECORD_ID_FIELD = "_recordId"
    static final int MIN_RULE_FIRINGS = 1000
    static final int RULE_FIRINGS_PER_FACT = 20
    static final long TIME_LIMIT_MS = 30_000L

    private static final ScheduledThreadPoolExecutor haltTimer = makeHaltTimer()

    final String ruleSetId
    final KieBase kieBase
    final Map<String, PrototypeFact> prototypes
    final List<String> directFactTypeIds

    BuiltRuleSet(String ruleSetId, KieBase kieBase, Map<String, PrototypeFact> prototypes, List<String> directFactTypeIds) {
        this.ruleSetId = ruleSetId
        this.kieBase = kieBase
        this.prototypes = prototypes
        this.directFactTypeIds = directFactTypeIds
    }

    static int ruleFiringCap(int factCount) { return Math.max(MIN_RULE_FIRINGS, RULE_FIRINGS_PER_FACT * factCount) }

    /** Judges facts, each a Map of factTypeId, fact and an optional recordId. Gives matchedRules and outcomes over all
     *  facts, and decisions: one per fact of the decision's single direct fact type, in input order. */
    Map decide(ExecutionContext ec, List<Map> facts, boolean firstMatchOnly) {
        List<Map> matchList = []
        String groupFactTypeId = directFactTypeIds.size() == 1 ? directFactTypeIds[0] : null
        // the input facts of the grouped fact type, by identity, so a fact without a record id still gets its own answer
        Map<PrototypeFactInstance, Integer> groupIndexes = new IdentityHashMap<>()
        List<Map> decisions = []
        int firingCap = ruleFiringCap(facts.size())
        AtomicBoolean timedOut = new AtomicBoolean(false)
        KieSession session = kieBase.newKieSession()
        ScheduledFuture haltTask = null
        try {
            session.setGlobal(MATCHES_GLOBAL, matchList)
            for (Map factEntry in facts) {
                PrototypeFactInstance factInstance = prototypes.get((String) factEntry.factTypeId).newInstance()
                ((Map) factEntry.fact).each { String fieldName, Object value -> factInstance.put(fieldName, value) }
                if (factEntry.recordId != null) factInstance.put(RECORD_ID_FIELD, factEntry.recordId)
                if (factEntry.factTypeId == groupFactTypeId) {
                    groupIndexes.put(factInstance, decisions.size())
                    decisions.add([recordId: factEntry.recordId, matchedRules: []])
                }
                session.insert(factInstance)
            }
            haltTask = haltTimer.schedule({ timedOut.set(true); session.halt() } as Runnable, TIME_LIMIT_MS, TimeUnit.MILLISECONDS)
            int firedCount = session.fireAllRules(firingCap)
            haltTask.cancel(false)
            if (timedOut.get()) {
                ec.message.addError("Decision ${ruleSetId} stopped after ${TIME_LIMIT_MS} ms")
                return null
            }
            if (firedCount >= firingCap) {
                ec.message.addError("Decision ${ruleSetId} stopped after ${firingCap} rule firings")
                return null
            }
        } finally {
            if (haltTask != null) haltTask.cancel(false)
            session.dispose()
        }

        List<Map> ruleMatches = []
        for (Map entry in matchList) {
            if (entry.addedFact != null) {
                // a fact a rule added answers for the record whose fact made the rule fire
                Integer fromIndex = groupIndexes.get((PrototypeFactInstance) entry.fromFact)
                if (fromIndex != null) groupIndexes.put((PrototypeFactInstance) entry.addedFact, fromIndex)
                continue
            }
            Map<String, PrototypeFactInstance> matchedFacts = (Map<String, PrototypeFactInstance>) entry.remove("matchedFacts")
            ruleMatches.add(entry)
            Integer groupIndex = groupFactTypeId ? groupIndexes.get(matchedFacts.get(groupFactTypeId)) : null
            if (groupIndex != null) ((List) decisions[groupIndex].matchedRules).add(entry)
        }

        Map decided = answerFor(ruleMatches, firstMatchOnly)
        decided.decisions = groupFactTypeId == null ? [] : decisions.collect { Map decision ->
            [recordId: decision.recordId] + answerFor((List<Map>) decision.matchedRules, firstMatchOnly) }
        return decided
    }

    private static Map answerFor(List<Map> matches, boolean firstMatchOnly) {
        List<Map> matchedRules = matches.sort(false) { a, b -> (b.priority <=> a.priority) ?: (a.ruleName <=> b.ruleName) }
        List<String> outcomeIds = matchedRules*.outcomeId.findAll { it != null }.unique()
        return [matchedRules: matchedRules, outcomes: firstMatchOnly ? outcomeIds.take(1) : outcomeIds]
    }

    private static ScheduledThreadPoolExecutor makeHaltTimer() {
        ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(1, { Runnable runnable ->
            Thread thread = Executors.defaultThreadFactory().newThread(runnable)
            thread.setName("moqui-rules-halt")
            thread.setDaemon(true)
            return thread
        } as java.util.concurrent.ThreadFactory)
        timer.setRemoveOnCancelPolicy(true)
        return timer
    }
}
