# moqui-rules release notes

## 0.2.0

### Upgrade steps

- **Entity names.** Every entity moved from the `moqui.rules` package to `rules`, so Moqui entity filters can apply to
  them (Moqui skips filters on any entity whose name starts with `moqui.`). `moqui.rules.RuleSet` is now
  `rules.RuleSet`, and so on for all 17 entities. Service names are unchanged (`moqui.rules.RuleServices.*`).
  - Data XML files must use the new element names (`<rules.RuleSet .../>`).
  - `ArtifactGroupMember`, `EntityFilter`, `DataDocument` and any other rows that name an entity must name the new one.
  - `EntityAuditLog` history stays under the old entity names.
- **Table names.** Every entity now sets its table explicitly, prefixed `RULES_` (`RULES_RULE_SET`,
  `RULES_RULE_SET_RULE`, `RULES_FACT_TYPE`, ...), so no table is shared with another component's entity of the same
  simple name (an app with its own `RuleSet` entity no longer reads or writes this component's rows). Moqui creates
  the new tables on start. An existing install must copy its rows from the old tables (`RULE_SET`, `RULE_SET_RULE`,
  `FACT_TYPE`, ...) into the new ones, then drop the old tables once nothing else uses them: their foreign key and
  index names are the ones the new tables use, which clash on databases where those names are schema-wide. The two
  unique indexes on `RuleSetRule` are now `RULES_RULE_NAME` and `RULES_RULE_PRIORITY`.
- **New columns.** `RuleSetCondition.compareFieldName` and `RuleSetCondition.tolerance`; `RuleSetCondition.normalizeText`
  and `RulePieceCondition.normalizeText`.
- **New entities.** `RulePiece`, `RulePieceCondition`, and the view `RuleSetConditionPieceUse`.
- **Reload the seed** (`data/RuleSeedData.xml`): it adds the condition types `RCT_COMPARE_FIELD` and `RCT_PIECE` and
  the four `FieldPairOperator` enumerations (`FPO_REQUIRES`, `FPO_REQUIRED_BY`, `FPO_ALL_IN`, `FPO_CONTAINS_ALL`).

### Behaviour changes

- Compare lines take `ENTCO_IN` and `ENTCO_NOT_IN` with a JSON array `compareValue`; a missing field is not one of any list.
- A compare line with `normalizeText` Y, on a text field with equals, not equals, in, not in, like or not like, trims
  and upper-cases the fact value and the compare values before comparing, and a value of only spaces is no value.
- An any line takes choices that compare two fields or ignore case; exists lines are still refused there.
- `evaluate#RuleSet` judges a batch: each fact may carry a `recordId`. Each entry of `matchedRules` carries
  `recordIds`, the record of each fact the rule matched. The new out-parameter `decisions` gives one answer per fact
  of the decision's single direct fact type, in input order.
- `_recordId` is a reserved field name: a fact, or a learned file column, may not use it.
- A rule with no lines fires once per fact; a call with no facts gets no catch-all outcome.
- A call stops with an error after `max(1000, facts x max(20, 2 x rules))` rule firings, or after 30 seconds. The
  30 seconds cover rule firing only, not converting facts or building the decision.
- A rule reads at most 3 fact types.
- `check#File` records carry their outcomes and matched rules.
- A change clears only the built decisions it touches, now and again when the writer's transaction ends (committed
  or rolled back); a build that a clear overtakes is answered with but not kept.
- Value comparisons between two fields are exact: text is not trimmed, so `"SKU1 "` and `"SKU1"` differ. Numbers are
  still compared as numbers.
- A not line over an all line or a piece with one line under it works for any line, a compare-two-fields line
  included.
- A host app's entity filter on `rules.RuleSetRule` or `rules.RuleSetCondition` applies through the views
  `RuleSetConditionAndRule` and `RuleSetConditionPieceUse`: they alias every field of their members (rule fields
  prefixed `rule`, piece-use line fields prefixed `line`). Before, such a filter made every read of the view fail.
- Clearing built decisions runs with authz disabled (the entity ECAs call `clear#BuiltDecisions` with
  `disable-authz`), so it finds every decision a changed row touches whatever filters the writer has.
- `build#Fact` reads the fact type with the caller's permissions before its built-fact cache: a fact type the caller
  cannot see answers "Fact type X does not exist", even when another caller built it.

### New services

- `store#Piece`: a named piece, a condition written once and used by many rules. Its lines may use exists lines over
  its fact type's list fields.
- `explain#RuleSet`: for one fact, whether each rule and each of its lines holds. It does no chaining.
- `clear#BuiltDecisions`: drops the built decisions a changed row can have made stale (called by the entity ECAs).
