package org.moqui.rules

import javax.cache.Cache
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import org.moqui.context.ExecutionContext
import org.moqui.context.ExecutionContextFactory
import org.moqui.context.TransactionFacade
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class RuleSets {
    protected final static Logger logger = LoggerFactory.getLogger(RuleSets.class)
    static final String CACHE_NAME = "moqui.rules.built.rule.set"
    static final String FORGET_AT_END_NAME = "moqui.rules.forget.built.decisions"

    final ExecutionContextFactory ecf

    /** Bumped by every forget, so a build that a forget overtook is not cached. Guarded by generationLock. */
    private final Object generationLock = new Object()
    private long allGeneration = 0L
    private final Map<String, Long> generations = new HashMap<>()

    RuleSets(ExecutionContextFactory ecf) { this.ecf = ecf }

    BuiltRuleSet builtRuleSet(String ruleSetId) {
        BuiltRuleSet built = cache().get(ruleSetId)
        return built != null ? built : buildAndCache(ruleSetId)
    }

    private synchronized BuiltRuleSet buildAndCache(String ruleSetId) {
        Cache<String, BuiltRuleSet> cache = cache()
        BuiltRuleSet built = cache.get(ruleSetId)
        if (built != null) return built
        List<Long> startGeneration = generationOf(ruleSetId)
        built = buildNow(ruleSetId)
        // a forget while the build ran may mean it read rows that are now stale: answer with it, but do not keep it
        synchronized (generationLock) {
            if (generationOf(ruleSetId) == startGeneration) cache.put(ruleSetId, built)
        }
        return built
    }

    /** Build a decision from its rows. Protected so a spec can stand in for a slow build. */
    protected BuiltRuleSet buildNow(String ruleSetId) {
        return new RuleSetBuilder(ecf.getExecutionContext(), ruleSetId).build()
    }

    private List<Long> generationOf(String ruleSetId) {
        synchronized (generationLock) { return [allGeneration, generations.get(ruleSetId) ?: 0L] }
    }

    private Cache<String, BuiltRuleSet> cache() {
        return ecf.getCache().getCache(CACHE_NAME)
    }

    /** Drop only these decisions from the cache; null means every decision. A build running now is not kept. */
    void forget(Collection<String> ruleSetIds) {
        synchronized (generationLock) {
            Cache<String, BuiltRuleSet> cache = cache()
            if (ruleSetIds == null) {
                allGeneration++
                cache.clear()
                return
            }
            for (String ruleSetId in ruleSetIds) {
                generations.put(ruleSetId, (generations.get(ruleSetId) ?: 0L) + 1L)
                cache.remove(ruleSetId)
            }
        }
    }

    /** Forget now, and again when the current transaction ends, committed or rolled back: until then another caller can
     *  build from the old committed rows, and this one from rows that may roll back. Without a transaction, forget now only. */
    void forgetNowAndAtTransactionEnd(Collection<String> ruleSetIds) {
        forget(ruleSetIds)
        TransactionFacade transaction = ecf.getExecutionContext().getTransaction()
        if (transaction.getStatus() != Status.STATUS_ACTIVE) return
        ForgetAtTransactionEnd atEnd = (ForgetAtTransactionEnd) transaction.getActiveSynchronization(FORGET_AT_END_NAME)
        if (atEnd == null) {
            atEnd = new ForgetAtTransactionEnd(this)
            transaction.putAndEnlistActiveSynchronization(FORGET_AT_END_NAME, atEnd)
        }
        atEnd.add(ruleSetIds)
    }

    /** One per transaction: gathers what the transaction's changes made stale and forgets it once, when it ends. */
    static class ForgetAtTransactionEnd implements Synchronization {
        private final RuleSets ruleSets
        private final Set<String> ruleSetIds = new LinkedHashSet<>()
        private boolean all = false

        ForgetAtTransactionEnd(RuleSets ruleSets) { this.ruleSets = ruleSets }

        synchronized void add(Collection<String> ids) {
            if (ids == null) all = true
            else ruleSetIds.addAll(ids)
        }

        @Override void beforeCompletion() { }

        @Override synchronized void afterCompletion(int status) {
            try {
                ruleSets.forget(all ? null : ruleSetIds)
            } catch (Throwable t) {
                logger.error("Could not drop built decisions ${all ? 'all' : ruleSetIds} after a transaction ended", t)
            }
        }
    }

    void buildAll() {
        ExecutionContext ec = ecf.getExecutionContext()
        try {
            ec.artifactExecution.disableAuthz()
            long startTime = System.currentTimeMillis()
            List<String> ruleSetIds = ec.entity.find("rules.RuleSet").list()*.ruleSetId
            int builtCount = 0
            for (String ruleSetId in ruleSetIds) {
                try {
                    builtRuleSet(ruleSetId)
                    builtCount++
                } catch (Throwable t) {
                    logger.error("Could not build decision ${ruleSetId}", t)
                }
            }
            logger.info("Built ${builtCount} of ${ruleSetIds.size()} decisions in ${System.currentTimeMillis() - startTime} ms")
        } finally {
            ec.destroy()
        }
    }
}
