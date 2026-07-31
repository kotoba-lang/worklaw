# kotoba-worklaw

**Statutory working-time limits, by jurisdiction** — a
[kotoba-lang](https://github.com/kotoba-lang) capability library that answers one
question: does this worked time break a statutory limit here?

> **Not legal advice.** This is a mechanism plus a small, cited rule set. It is
> deliberately incomplete, and its most important behaviour is what it does about
> that.

## The invariant: silence is never compliance

Everything else in here is ordinary arithmetic. This is the reason the library
exists.

**An unchecked jurisdiction never returns "no violations."**

```clojure
(law/check twenty-three-hour-day [:atlantis] date-of {:period week})
;; => {:worklaw/coverage :none
;;     :worklaw/unchecked [[:atlantis]]
;;     :worklaw/violations []}          ; empty, and it means NOTHING WAS CHECKED

(law/compliant? that)   ;; => false
```

`compliant?` is deliberately not `(empty? violations)`. A caller who reaches for
the convenient boolean gets the conservative answer rather than the flattering
one.

## Jurisdictions are paths, not codes

`[:jp]`, `[:us]`, `[:us :ca]`, `[:fr]`. Rules attach at a level, and a person in
`[:us :ca]` is checked against every level that has rules, with the rest listed
in `:worklaw/unchecked`.

This exists because of a specific trap. **US federal law has no daily overtime;
California does.** A checker keyed on "US" that returned "compliant" for a
twelve-hour day would be confidently wrong for a worker in San Jose.

```clojure
(law/check twelve-hour-day [:us :ca] date-of {:period week})
;; => {:worklaw/coverage :partial :worklaw/checked [[:us]] :worklaw/unchecked [[:us :ca]]}
```

## Rules that need a period say so

A week with Monday–Friday worked and nothing after it does not say whether the
weekend was a day off or simply outside the data. Guessing is wrong in a
different direction either way, so weekly rest is **not evaluated** without
`:period`, and says which rule it skipped:

```clojure
(law/check mon-to-fri [:jp] date-of)
;; => {:worklaw/coverage :full :worklaw/unevaluated [:jp-weekly-rest] :worklaw/violations []}
(law/compliant? that)   ;; => false
```

Related asymmetry, and the reason the edge gaps are computed separately:
**an edge gap may satisfy a rule, never violate one.** A window that happens to
open at midnight before an 09:00 shift shows a nine-hour leading gap that is an
artifact of where the query was cut, not a rest period anyone was denied.

## Shipped rule sets

Three jurisdictions, each rule carrying its provision and the date it was
recorded, so a reader can check it rather than trust it — and so a stale rule set
shows up as a stale date rather than as a confident answer.

| jurisdiction | source | rules |
|---|---|---|
| `[:jp]` | 労働基準法 | daily 8h (32条2項) · weekly 40h (32条1項) · break 45min over 6h / 60min over 8h (34条1項) · weekly rest 24h (35条1項) |
| `[:us]` | FLSA (federal) | overtime premium from 40h/week (29 U.S.C. §207(a)(1)) |
| `[:eu]` | Directive 2003/88/EC | weekly 48h (Art. 6(b)) · daily rest 11h (Art. 3) · break over 6h (Art. 4) · weekly rest 24h (Art. 5) |

Statutory **silence** is recorded too, so it is not left to be inferred:

```clojure
(law/absences [:us])
;; => [{:absent/kind :daily-hours-max
;;      :absent/note "federal law sets no daily hour cap and no daily overtime;
;;                    several states do"} ...]
```

Notes on what the shipped rules do *not* model are attached to the rules
themselves: the EU weekly cap is checked per single week where the directive
allows an averaging reference period (stricter, and said so); the JP weekly-rest
rule does not implement 4週4日の変形休日制; a directive binds member states, not
employers, and national transposition is often stricter.

## Prohibitions vs premiums

A finding carries `:violation/kind :overtime-due` when the statute prices the
hours rather than forbidding them. US overtime is lawful and expensive; a missed
rest period is not lawful at any price. Consumers are expected to route the two
differently — `cloud-itonami/kintai` blocks on prohibitions and escalates
overtime to a person.

## Maturity

| | |
|---|---|
| Role | capability |
| Dependencies | none |
| Tests | 20 tests, 72 assertions, all green |
| Jurisdictions | 3 (`[:jp]` `[:us]` `[:eu]`) — anything else is `:none`, by construction |
| Runtime | `.cljc`, JVM + ClojureScript |
| Actor | `cloud-itonami/kintai` (勤怠) |

Adding a jurisdiction means adding a map with `:law/as-of` and a
`:rule/citation` on every rule; a test enforces both.

## Test

```bash
clojure -M:test
clojure -M:lint
```

## License

Apache-2.0.
