# CLAUDE.md — kotoba-lang/worklaw

Statutory working-time limits by jurisdiction. **Not legal advice** — a
mechanism plus a cited, deliberately incomplete rule set. Zero dependencies.

## The invariant. It is the reason this repo exists.

**Silence is never compliance.** An unchecked jurisdiction never returns "no
violations": `check` reports `:worklaw/coverage` and `:worklaw/unevaluated`, and
`compliant?` is deliberately **not** `(empty? violations)` so a caller reaching
for the convenient boolean gets the conservative answer.

If you are about to make an empty violation list mean "fine", stop.

## Rules for changing the rule set

- **Every rule needs `:rule/citation`; every jurisdiction needs `:law/as-of`.**
  Tests enforce both. A rule whose provision you cannot cite does not go in.
- **A jurisdiction path is legal hierarchy, not geography.** A French worker is
  `[:eu :fr]` — the directive is the floor, national law layers on top. Bare
  `[:fr]` resolves to `:none`; do not "fix" that by aliasing country codes.
- **Record what a statute does NOT regulate** (`:law/absent`) so it is not
  inferred. US federal law has no daily cap; several states do.
- **Record what this rule set does not model** (`:rule/note`). The EU weekly cap
  is checked per single week where the directive allows averaging; JP weekly rest
  omits 4週4日変形休日制; DE's 8h day omits the six-month average.

## Windows and unevaluated reasons

A rule whose window exceeds the period is **not evaluated**, and the reason is
load-bearing for consumers:

| reason | whose problem |
|---|---|
| `:window-longer-than-period` | inherent — a week cannot judge an annual cap |
| `:missing-period` / `:missing-calendar` | the caller's |

`kintai` hard-holds the second and escalates the first. Collapsing them would
block every weekly approval forever, which is unusability, not compliance.

Two more asymmetries to preserve: an **edge gap may satisfy a rule, never violate
one**; and long-window rules count **statutory overtime** (daily excess first,
then weekly excess of the remainder — never both in full).

## Prohibitions vs premiums

`:violation/kind` in `priced-kinds` means the statute *prices* the hours.
Overtime is lawful and expensive; a missed rest period is not lawful at any
price. Keep `priced` and `prohibitions` distinct.

## Test

    clojure -M:test && clojure -M:lint
