package org.moqui.rules

import org.drools.model.DSL
import org.drools.model.Drools
import org.drools.model.Global
import org.drools.model.PatternDSL
import org.drools.model.Rule
import org.drools.model.RuleItemBuilder
import org.drools.model.functions.Block2
import org.drools.model.functions.Block3
import org.drools.model.functions.Block4
import org.drools.model.functions.Block5
import org.drools.model.functions.Predicate1
import org.drools.model.impl.ModelImpl
import org.drools.model.prototype.PrototypeDSL
import org.drools.model.prototype.PrototypeVariable
import org.drools.modelcompiler.KieBaseBuilder
import org.kie.api.prototype.PrototypeFact
import org.kie.api.prototype.PrototypeFactInstance
import org.moqui.context.ExecutionContext

class RuleSetBuilder {
    final ExecutionContext ec
    final String ruleSetId
    final Map<String, PrototypeFact> prototypes = [:]
    List<String> directFactTypeIds = []
    final Global<List> matches = DSL.globalOf(List.class, "org.moqui.rules", BuiltRuleSet.MATCHES_GLOBAL)

    RuleSetBuilder(ExecutionContext ec, String ruleSetId) {
        this.ec = ec
        this.ruleSetId = ruleSetId
    }

    BuiltRuleSet build() {
        Map decisionFactTypes = ec.service.sync().name("moqui.rules.RuleServices.get#DecisionFactTypes")
                .parameters([ruleSetId: ruleSetId]).disableAuthz().call()
        ((List<String>) decisionFactTypes.factTypeIds).each { prototypes.put(it, PrototypeDSL.prototypeFact(it)) }
        directFactTypeIds = ((List<String>) decisionFactTypes.directFactTypeIds).collect()

        ModelImpl model = new ModelImpl().addGlobal(matches)
        List<Map> rules = ec.entity.find("rules.RuleSetRule").condition("ruleSetId", ruleSetId)
                .useCache(false).disableAuthz().listMaster("default")
        for (Map rule in rules) model.addRule(buildRule(rule))
        return new BuiltRuleSet(ruleSetId, KieBaseBuilder.createKieBaseFromModel(model), prototypes, directFactTypeIds)
    }

    private Rule buildRule(Map rule) {
        Map preparedLines = ec.service.sync().name("moqui.rules.RuleLineServices.prepare#RuleLines")
                .parameters([ruleSetId: ruleSetId, ruleName: rule.ruleName, conditions: rule.conditions]).disableAuthz().call()
        if (preparedLines.problems) throw new IllegalArgumentException(((List) preparedLines.problems).join("; "))

        // a rule with no lines still answers for each fact when the decision reads one fact type
        Map<String, Map> factMatches = (Map<String, Map>) preparedLines.factMatches
        if (!factMatches && directFactTypeIds.size() == 1) factMatches = [(directFactTypeIds[0]): null]

        List<RuleItemBuilder> items = []
        Map<String, PrototypeVariable> factVariables = [:]
        factMatches.each { String factTypeId, Map factMatch ->
            PrototypeVariable factVariable = PrototypeDSL.variable(prototypes.get(factTypeId), "${factTypeId}_${rule.ruleId}")
            PrototypeDSL.PrototypePatternDef pattern = PrototypeDSL.protoPattern(factVariable)
            if (factMatch != null)
                pattern.expr("${rule.ruleId}_${factTypeId}", { PrototypeFactInstance fact -> ItemChecks.matches(factMatch, fact.asMap()) }.dehydrate() as Predicate1<PrototypeFactInstance>)
            factVariables.put(factTypeId, factVariable)
            items.add(pattern)
        }
        items.add(consequenceFor(rule, factVariables))

        return PatternDSL.rule("org.moqui.rules", "${rule.ruleName} (${rule.ruleId})")
                .attribute(Rule.Attribute.SALIENCE, (rule.priority ?: 0) as Integer)
                .build(items as RuleItemBuilder[])
    }

    private RuleItemBuilder consequenceFor(Map rule, Map<String, PrototypeVariable> factVariables) {
        List<String> problems = []
        List<Map> preparedActions = ((List<Map>) rule.actions ?: []).sort(false) { it.actionSeqId }.withIndex().collect { Map action, int index ->
            Map actionType = (Map) action.actionType
            if (actionType == null) {
                problems.add("Action ${index + 1}: action type ${action.actionTypeId} is not known".toString())
                return [:]
            }
            Map prepared = ec.service.sync().name((String) actionType.prepareServiceName)
                    .parameters([ruleSetId: ruleSetId, action: action]).disableAuthz().call()
            problems.addAll(((List<String>) prepared.problems).collect { "Action ${index + 1}: ${it}".toString() })
            return prepared
        }
        if (problems) throw new IllegalArgumentException("Rule ${rule.ruleName}: " + problems.join("; "))

        Map ruleInfo = [ruleId: rule.ruleId, ruleName: rule.ruleName, priority: rule.priority]
        List<Map> ruleMatches = preparedActions*.outcomeId.findAll { it }.collect { ruleInfo + [outcomeId: it] } ?: [ruleInfo + [outcomeId: null]]
        List<Map> factsToAdd = preparedActions*.factToAdd.findAll { it }
        Map<String, PrototypeFact> factPrototypes = prototypes
        List<String> boundFactTypeIds = factVariables.keySet().collect()

        Closure fire = { Drools drools, List matchList, List<PrototypeFactInstance> boundFacts ->
            Map<String, PrototypeFactInstance> matchedFacts = [:]
            Map<String, Object> recordIds = [:]
            boundFacts.eachWithIndex { PrototypeFactInstance fact, int index ->
                matchedFacts.put(boundFactTypeIds[index], fact)
                if (fact.has(BuiltRuleSet.RECORD_ID_FIELD)) recordIds.put(boundFactTypeIds[index], fact.get(BuiltRuleSet.RECORD_ID_FIELD))
            }
            for (Map ruleMatch in ruleMatches) matchList.add(ruleMatch + [recordIds: recordIds, matchedFacts: matchedFacts])
            PrototypeFactInstance fromFact = boundFacts.size() == 1 ? boundFacts[0] : null
            for (Map factToAdd in factsToAdd) {
                PrototypeFactInstance newFact = factPrototypes.get((String) factToAdd.factTypeId).newInstance()
                ((Map) factToAdd.values).each { String fieldName, Object value -> newFact.put(fieldName, value) }
                if (fromFact != null && fromFact.has(BuiltRuleSet.RECORD_ID_FIELD))
                    newFact.put(BuiltRuleSet.RECORD_ID_FIELD, fromFact.get(BuiltRuleSet.RECORD_ID_FIELD))
                if (fromFact != null) matchList.add([addedFact: newFact, fromFact: fromFact])
                drools.insert(newFact)
            }
        }.dehydrate()

        List<PrototypeVariable> variables = factVariables.values().collect()
        switch (variables.size()) {
            case 0: return DSL.on(matches).execute({ Drools drools, List matchList ->
                    fire(drools, matchList, []) }.dehydrate() as Block2<Drools, List>)
            case 1: return DSL.on(matches, variables[0]).execute({ Drools drools, List matchList, PrototypeFactInstance fact1 ->
                    fire(drools, matchList, [fact1]) }.dehydrate() as Block3<Drools, List, PrototypeFactInstance>)
            case 2: return DSL.on(matches, variables[0], variables[1]).execute({ Drools drools, List matchList,
                    PrototypeFactInstance fact1, PrototypeFactInstance fact2 ->
                    fire(drools, matchList, [fact1, fact2]) }.dehydrate() as Block4<Drools, List, PrototypeFactInstance, PrototypeFactInstance>)
            case 3: return DSL.on(matches, variables[0], variables[1], variables[2]).execute({ Drools drools, List matchList,
                    PrototypeFactInstance fact1, PrototypeFactInstance fact2, PrototypeFactInstance fact3 ->
                    fire(drools, matchList, [fact1, fact2, fact3]) }.dehydrate() as Block5<Drools, List, PrototypeFactInstance, PrototypeFactInstance, PrototypeFactInstance>)
            default: throw new IllegalArgumentException("Rule ${rule.ruleName}: a rule reads at most 3 fact types")
        }
    }
}
