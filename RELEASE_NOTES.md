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
  and `RulePieceCondition.normalizeText`. Moqui adds a missing column on start (`entity_add_missing_startup`, on by
  default). With add-missing off, add them by hand, for example
  `ALTER TABLE RULES_RULE_SET_CONDITION ADD NORMALIZE_TEXT CHAR(1)` and the same on `RULES_RULE_PIECE_CONDITION`.
- **Wider column.** `compareValue` on `RuleSetCondition` and `RulePieceCondition` is now `text-long` (`VARCHAR(4095)`
  on H2 and MySQL; it was `text-medium`, `VARCHAR(255)`), so a JSON list fits. An install coming from 0.1.x gets the
  new width when Moqui creates the `RULES_` tables. Moqui never changes the type of an existing column, so an install
  that already has the `RULES_` tables widens both by hand:
  - MySQL: `ALTER TABLE RULES_RULE_SET_CONDITION MODIFY COMPARE_VALUE VARCHAR(4095)` and
    `ALTER TABLE RULES_RULE_PIECE_CONDITION MODIFY COMPARE_VALUE VARCHAR(4095)`.
  - H2: `ALTER TABLE RULES_RULE_SET_CONDITION ALTER COLUMN COMPARE_VALUE VARCHAR(4095)` and
    `ALTER TABLE RULES_RULE_PIECE_CONDITION ALTER COLUMN COMPARE_VALUE VARCHAR(4095)`.
  - Other databases: change both columns to the database's `text-long` type.
- **New entities.** `RulePiece`, `RulePieceCondition`, and the view `RuleSetConditionPieceUse`.
- **Reload the seed** (`data/RuleSeedData.xml`): it adds the condition types `RCT_COMPARE_FIELD` and `RCT_PIECE` and
  the four `FieldPairOperator` enumerations (`FPO_REQUIRES`, `FPO_REQUIRED_BY`, `FPO_ALL_IN`, `FPO_CONTAINS_ALL`).

### Behaviour changes

- Compare lines take `ENTCO_IN` and `ENTCO_NOT_IN` with a `compareValue` that is a non-empty JSON array of strings,
  numbers, `true` or `false`. Each element is converted to the field's kind from its JSON type: a number field takes
  numbers (`1e2` included) and number strings; a yes/no field takes `true`, `false` and the strings true, false, y and
  n; on a text field a number is its plain text (`1e2` is `"100"`, `1.0` is `"1.0"`). Numbers compare by value (`2.5`
  is in `["2.50"]`). A missing field is in no list, so `ENTCO_NOT_IN` holds for it.
- A compare line that needs a value (every operator except `ENTCO_IS_NULL` and `ENTCO_IS_NOT_NULL`), in a rule or a piece, is refused
  at save when its `compareValue` is null or empty, naming the field; Moqui stores an empty string as null, so such a rule
  saved but could not run. To test for no value use is null or is not null.
- A compare value, a JSON list included, holds at most 4000 characters; a longer one is refused when the rule or piece
  is saved, naming the field.
- `normalizeText` takes `Y`, `N` or nothing; any other value is refused, naming it. With `Y`, on a text field with
  equals, not equals, in, not in, like or not like, a text value is first stripped of leading and trailing characters
  U+0000 to U+0020 (Java `String.trim`: spaces, tabs, line breaks and the other control characters) and upper-cased
  with `Locale.ROOT`, which can lengthen it (`ß` becomes `SS`). A value that is then empty, or null, is no value. A
  compare value or list element that is no value is refused when the rule or piece is saved. Without the flag, equals,
  not equals, in and not in compare text exactly and with case, and an empty text is a value.
- No value (a missing field, null, or with `normalizeText` a blank text) is not equal to, not in and not like
  anything: `=`, `in` and `like` do not hold, and `!=`, `not in` and `not like` do.
- LIKE and NOT LIKE patterns: `%` matches any run of characters including none, `_` exactly one UTF-16 unit, and both
  match line breaks. There is no escape character, so a literal `%` or `_` cannot be matched. LIKE always ignores
  letter case: both sides are upper-cased with `Locale.ROOT`. Without `normalizeText` nothing is trimmed (`ab%` does
  not match `" abcd"`); with it both sides are trimmed first. Every LIKE line is a value check matched in linear time,
  with no regex; before, a LIKE line without the flag ran Moqui's regular expression, ignoring ASCII case only and
  taking `_` as one code point.
- Rule-line conditions are judged in memory only, never sent to SQL. SQL `<>` does not hold for null, so a caller
  that translates `ENTCO_NOT_IN` or `ENTCO_NOT_EQUALS` to SQL must add `OR field IS NULL`.
- An any line takes choices that compare two fields, ignore case or use LIKE; exists lines are still refused there.
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
- A not line works over an all line, a piece or another not line whatever lines are under them, value checks
  (compare two fields, `normalizeText`, LIKE) included. An exists line still turns around only alone: directly under
  a not line, or as the one line of an all line or a piece under one.
- A host app's entity filter on `rules.RuleSetRule` or `rules.RuleSetCondition` applies through the views
  `RuleSetConditionAndRule` and `RuleSetConditionPieceUse`: they alias every field of their members (rule fields
  prefixed `rule`, piece-use line fields prefixed `line`). Before, such a filter made every read of the view fail.
- Clearing built decisions runs with authz disabled (the entity ECAs call `clear#BuiltDecisions` with
  `disable-authz`), so it finds every decision a changed row touches whatever filters the writer has.
- `build#Fact` reads the fact type with the caller's permissions before its built-fact cache: a fact type the caller
  cannot see answers "Fact type X does not exist", even when another caller built it.
- Facts are converted in bulk, with no service call per value. The new `convert#Facts` service converts many facts at
  once; `convert#Fact` is a shell over the same code, and `evaluate#RuleSet` and `check#File` make one `convert#Facts`
  call per batch. The per-value conversion and the copying of fact values into the rules are statically compiled.
- A text value holding `<` is converted as text by `convert#Fact`, `convert#Facts`, `evaluate#RuleSet` and `check#File`
  instead of failing the call; `convert#FactValue` called directly still refuses it.

### New services

- `convert#Facts`: converts a list of facts of any types in one call, giving each its converted fact and problems, as
  `convert#Fact` does for one.
- `store#Piece`: a named piece, a condition written once and used by many rules. Its lines may use exists lines over
  its fact type's list fields.
- `explain#RuleSet`: for one fact, whether each rule and each of its lines holds. It does no chaining.
- `clear#BuiltDecisions`: drops the built decisions a changed row can have made stale (called by the entity ECAs).
