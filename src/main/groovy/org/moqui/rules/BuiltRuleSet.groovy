package org.moqui.rules

import groovy.transform.CompileStatic
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
    /** Firings allowed per fact: at least this many, and at least two per rule, so many rules matching every fact pass. */
    static final int RULE_FIRINGS_PER_FACT = 20
    static final long TIME_LIMIT_MS = 30_000L

    private static final ScheduledThreadPoolExecutor haltTimer = makeHaltTimer()

    final String ruleSetId
    final KieBase kieBase
    final Map<String, PrototypeFact> prototypes
    final List<String> directFactTypeIds
    final int ruleCount

    BuiltRuleSet(String ruleSetId, KieBase kieBase, Map<String, PrototypeFact> prototypes, List<String> directFactTypeIds, int ruleCount) {
        this.ruleSetId = ruleSetId
        this.kieBase = kieBase
        this.prototypes = prototypes
        this.directFactTypeIds = directFactTypeIds
        this.ruleCount = ruleCount
    }

    static int ruleFiringCap(int factCount, int ruleCount) {
        long cap = (long) factCount * Math.max(RULE_FIRINGS_PER_FACT, 2 * ruleCount)
        return (int) Math.min(Integer.MAX_VALUE, Math.max(MIN_RULE_FIRINGS, cap))
    }

    /** Judges facts, each a Map of factTypeId, fact and an optional recordId. Gives matchedRules and outcomes over all
     *  facts, and decisions: one per fact of the decision's single direct fact type, in input order. */
    Map decide(ExecutionContext ec, List<Map> facts, boolean firstMatchOnly) {
        List<Map> matchList = []
        String groupFactTypeId = directFactTypeIds.size() == 1 ? directFactTypeIds[0] : null
        // the input facts of the grouped fact type, by identity, so a fact without a record id still gets its own answer
        Map<PrototypeFactInstance, Integer> groupIndexes = new IdentityHashMap<>()
        List<Map> decisions = []
        int firingCap = ruleFiringCap(facts.size(), ruleCount)
        AtomicBoolean timedOut = new AtomicBoolean(false)
        KieSession session = kieBase.newKieSession()
        ScheduledFuture haltTask = null
        try {
            session.setGlobal(MATCHES_GLOBAL, matchList)
            for (Map factEntry in facts) {
                PrototypeFactInstance factInstance = prototypes.get((String) factEntry.factTypeId).newInstance()
                copyValues((Map) factEntry.fact, factInstance)
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

        // entries come in firing order, so a fact a rule added is credited before any rule matches it
        Map<PrototypeFactInstance, Map> addedFactRecordIds = new IdentityHashMap<>()
        List<Map> ruleMatches = []
        for (Map entry in matchList) {
            Map<String, PrototypeFactInstance> matchedFacts = (Map<String, PrototypeFactInstance>) entry.remove("matchedFacts")
            Map recordIds = [:]
            matchedFacts.each { String factTypeId, PrototypeFactInstance fact ->
                Map carriedRecordIds = addedFactRecordIds.get(fact)
                if (carriedRecordIds != null) recordIds.putAll(carriedRecordIds)
                else if (fact.has(RECORD_ID_FIELD)) recordIds.put(factTypeId, fact.get(RECORD_ID_FIELD))
            }
            // only a rule that matched one fact answers for one record; an addition from several facts answers for none
            Integer groupIndex = matchedFacts.size() == 1 ? groupIndexes.get(matchedFacts.values().first()) : null
            if (entry.addedFact != null) {
                PrototypeFactInstance addedFact = (PrototypeFactInstance) entry.addedFact
                addedFactRecordIds.put(addedFact, recordIds)
                if (groupIndex != null) groupIndexes.put(addedFact, groupIndex)
                continue
            }
            entry.recordIds = recordIds
            ruleMatches.add(entry)
            if (groupFactTypeId != null && groupIndex != null) ((List) decisions[groupIndex].matchedRules).add(entry)
        }

        Map decided = answerFor(ruleMatches, firstMatchOnly)
        decided.decisions = groupFactTypeId == null ? [] : decisions.collect { Map decision ->
            [recordId: decision.recordId] + answerFor((List<Map>) decision.matchedRules, firstMatchOnly) }
        return decided
    }

    /**
     * Puts each value of a fact on its instance, in the fact's key order. Statically compiled: it runs for every value of
     * every fact, and dynamic dispatch on values of several classes relinks its call site value after value.
     * Keys are field names (Strings); a non-String key is turned into text by the cast.
     */
    @CompileStatic
    private static void copyValues(Map values, PrototypeFactInstance factInstance) {
        for (Object e in values.entrySet()) {
            Map.Entry entry = (Map.Entry) e
            factInstance.put((String) entry.getKey(), entry.getValue())
        }
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
