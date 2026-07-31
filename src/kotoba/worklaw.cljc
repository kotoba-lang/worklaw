(ns kotoba.worklaw
  "Statutory working-time limits, by jurisdiction — pure data contracts.

  A kotoba-lang capability library for the 各国労働法 half of workforce
  management. It answers one question: does this worked time break a
  statutory limit in this jurisdiction?

  NOT LEGAL ADVICE. This is a mechanism plus a small, cited rule set. It
  is deliberately incomplete, and its most important behaviour is what it
  does about that.

  ## The invariant: silence is never compliance

  Every other check in this library is ordinary arithmetic. This one is
  the reason the library exists:

      an unchecked jurisdiction NEVER returns 'no violations'

  `check` reports `:worklaw/coverage` as `:full`, `:partial` or `:none`,
  and a caller that reads `:worklaw/violations` without reading coverage
  is reading a number that may mean 'we looked and found nothing' or
  'we did not look'. The two are different and this library refuses to
  let them share a representation.

  ## Jurisdictions are paths, not codes

  A jurisdiction is a vector: `[:jp]`, `[:us]`, `[:us :ca]`, `[:fr]`.
  Rules attach at a level, and a person in `[:us :ca]` is checked against
  every level for which rules exist — federal here — with the levels that
  have none listed in `:worklaw/unchecked`.

  This exists because of a specific trap. US federal law has no daily
  overtime; California does. A checker keyed on 'US' that returned
  'compliant' for a twelve-hour day would be confidently wrong for a
  worker in San Jose. So `[:us :ca]` returns coverage `:partial` with
  `[:us :ca]` listed as unchecked, and the caller has to decide what to
  do about that rather than being told everything is fine.

  Portable (.cljc) across JVM / ClojureScript / SCI / GraalVM."
  (:require [clojure.string :as str]))

(def ^:private ms-per-hour 3600000)

;; ---------------------------------------------------------------------------
;; Rules
;;
;; Each rule carries the provision it comes from and the date it was
;; recorded, so a reader can check it rather than trust it, and so a stale
;; rule set is visible as a stale date rather than as a confident answer.
;; ---------------------------------------------------------------------------

(def rules
  "Rule sets by jurisdiction path. Recorded 2026-07-31; verify against the
  current statute before relying on any of it.

  Rule kinds:
    :daily-hours-max      worked hours in one day
    :weekly-hours-max     worked hours in one week
    :weekly-overtime-from hours after which a week is overtime
    :break-min            minimum break for a day over :over-hours
    :daily-rest-min       minimum consecutive rest between days
    :weekly-rest-min      minimum consecutive rest in a week"
  {[:jp]
   {:law/name "労働基準法"
    :law/as-of "2026-07-31"
    :law/rules
    [{:rule/id :jp-daily-8 :rule/kind :daily-hours-max :rule/hours 8
      :rule/citation "労働基準法 第32条第2項"
      :rule/note "法定労働時間。36協定があれば時間外として超過しうるが、超過自体は記録される"}
     {:rule/id :jp-weekly-40 :rule/kind :weekly-hours-max :rule/hours 40
      :rule/citation "労働基準法 第32条第1項"}
     {:rule/id :jp-break-45 :rule/kind :break-min :rule/over-hours 6 :rule/minutes 45
      :rule/citation "労働基準法 第34条第1項"}
     {:rule/id :jp-break-60 :rule/kind :break-min :rule/over-hours 8 :rule/minutes 60
      :rule/citation "労働基準法 第34条第1項"}
     {:rule/id :jp-weekly-rest :rule/kind :weekly-rest-min :rule/hours 24
      :rule/citation "労働基準法 第35条第1項"
      :rule/note "毎週少なくとも1回の休日。4週4日の変形休日制は本ルールでは扱わない"}]}

   [:us]
   {:law/name "Fair Labor Standards Act (federal)"
    :law/as-of "2026-07-31"
    :law/rules
    [{:rule/id :us-weekly-ot-40 :rule/kind :weekly-overtime-from :rule/hours 40
      :rule/citation "29 U.S.C. §207(a)(1)"
      :rule/note "1.5× over 40 hours in a workweek for non-exempt employees"}]
    :law/absent
    ;; Named explicitly. A reader who sees no daily rule should know that
    ;; is the statute's silence, not this library's gap — and should also
    ;; know that several states fill that silence.
    [{:absent/kind :daily-hours-max
      :absent/note "federal law sets no daily hour cap and no daily overtime; several states do"}
     {:absent/kind :break-min
      :absent/note "federal law mandates no meal or rest break; many states do"}]}

   [:eu]
   {:law/name "Directive 2003/88/EC (Working Time Directive)"
    :law/as-of "2026-07-31"
    :law/rules
    [{:rule/id :eu-weekly-48 :rule/kind :weekly-hours-max :rule/hours 48
      :rule/citation "Directive 2003/88/EC Art. 6(b)"
      :rule/note "average over a reference period, including overtime. This library checks a single week, which is stricter than the directive requires"}
     {:rule/id :eu-daily-rest-11 :rule/kind :daily-rest-min :rule/hours 11
      :rule/citation "Directive 2003/88/EC Art. 3"}
     {:rule/id :eu-break-6 :rule/kind :break-min :rule/over-hours 6 :rule/minutes 0
      :rule/citation "Directive 2003/88/EC Art. 4"
      :rule/note "a break is required but its length is left to member states, so the minimum here is 0 and the rule only records that one must exist"}
     {:rule/id :eu-weekly-rest-24 :rule/kind :weekly-rest-min :rule/hours 24
      :rule/citation "Directive 2003/88/EC Art. 5"
      :rule/note "in addition to the 11-hour daily rest"}]
    :law/note "a directive binds member states, not employers directly. National transposition is stricter in many states and is not modelled here"}})

(defn known-jurisdictions [] (set (keys rules)))

(defn- levels-of
  "Every prefix of a jurisdiction path, longest last: [:us :ca] ->
  ([:us] [:us :ca])."
  [j]
  (for [n (range 1 (inc (count j)))] (vec (take n j))))

;; ---------------------------------------------------------------------------
;; Checking
;; ---------------------------------------------------------------------------

(defn- hours [ms] (/ (double ms) ms-per-hour))

(defn- day-groups
  "Worked spans grouped by the caller's date function."
  [worked date-of]
  (group-by #(date-of (:worked/start %)) worked))

(defn- check-rule [rule day-key spans]
  (let [total-ms (reduce + 0 (map :worked/ms spans))
        break-ms (reduce + 0 (map #(or (:worked/break-ms %) 0) spans))
        h (hours total-ms)]
    (case (:rule/kind rule)
      :daily-hours-max
      (when (> h (:rule/hours rule))
        {:violation/rule rule :violation/day day-key
         :violation/actual h :violation/limit (:rule/hours rule)
         :violation/detail (str h "h worked, limit " (:rule/hours rule) "h")})

      :break-min
      (when (and (> h (:rule/over-hours rule))
                 (< (/ break-ms 60000.0) (:rule/minutes rule)))
        {:violation/rule rule :violation/day day-key
         :violation/actual (/ break-ms 60000.0) :violation/limit (:rule/minutes rule)
         :violation/detail (str (/ break-ms 60000.0) " min break on a " h "h day, minimum "
                                (:rule/minutes rule) " min")})
      nil)))

(defn- check-weekly [rule worked]
  (let [h (hours (reduce + 0 (map :worked/ms worked)))]
    (case (:rule/kind rule)
      :weekly-hours-max
      (when (> h (:rule/hours rule))
        {:violation/rule rule :violation/actual h :violation/limit (:rule/hours rule)
         :violation/detail (str h "h in the week, limit " (:rule/hours rule) "h")})

      :weekly-overtime-from
      (when (> h (:rule/hours rule))
        {:violation/rule rule :violation/actual h :violation/limit (:rule/hours rule)
         :violation/kind :overtime-due
         :violation/detail (str (- h (:rule/hours rule)) "h beyond " (:rule/hours rule)
                                "h attract the statutory overtime premium")})
      nil)))

(def period-dependent-rules
  "Rules that cannot be evaluated without the period's bounds. A week with
  Monday to Friday worked and nothing after it does not say whether the
  weekend was a day off or simply outside the data, and guessing either
  way is wrong in a different direction."
  #{:weekly-rest-min})

(defn- rest-gaps
  "Gaps between consecutive worked spans, plus — when a period is given —
  the gap before the first span and after the last.

  Edge gaps may only be used to SATISFY a rule, never to violate one. A
  window that happens to start at midnight before an 09:00 shift shows a
  nine-hour leading gap that is an artifact of where the query was cut,
  not a rest period anyone was denied. So they are returned separately
  and only the rules that look for a long-enough gap consult them."
  [worked period]
  (let [sorted (sort-by :worked/start worked)
        inner (map (fn [[a b]] {:from (:worked/end a) :to (:worked/start b)
                                :hours (hours (- (:worked/start b) (:worked/end a)))})
                   (partition 2 1 sorted))
        [pf pt] period
        edges (when (and pf pt (seq sorted))
                [{:from pf :to (:worked/start (first sorted))
                  :hours (hours (- (:worked/start (first sorted)) pf))}
                 {:from (:worked/end (last sorted)) :to pt
                  :hours (hours (- pt (:worked/end (last sorted))))}])]
    {:inner (vec inner) :edges (vec edges)}))

(defn- check-rest [rule worked period]
  (let [{:keys [inner edges]} (rest-gaps worked period)]
    (case (:rule/kind rule)
      :daily-rest-min
      ;; Inner gaps only: a short gap is a violation, and an edge gap is
      ;; not evidence of one.
      (keep (fn [g]
              (when (< (:hours g) (:rule/hours rule))
                {:violation/rule rule :violation/actual (:hours g)
                 :violation/limit (:rule/hours rule)
                 :violation/detail (str (:hours g) "h rest between shifts, minimum "
                                        (:rule/hours rule) "h")}))
            inner)

      :weekly-rest-min
      (let [all (concat inner edges)]
        (when (and period (seq all)
                   (not (some #(>= (:hours %) (:rule/hours rule)) all)))
          [{:violation/rule rule
            :violation/actual (apply max (map :hours all))
            :violation/limit (:rule/hours rule)
            :violation/detail (str "no rest period of " (:rule/hours rule)
                                   "h in the period; longest was "
                                   (apply max (map :hours all)) "h")}]))
      nil)))

(defn check
  "Check one person's worked spans for one week against `jurisdiction`.

  `worked` is a collection of `kotoba.shift` worked spans (or anything
  with `:worked/start :worked/end :worked/ms` and optionally
  `:worked/break-ms`). `date-of` maps an instant to a day key — the
  caller owns the timezone, and in this domain that is not a detail:
  which side of midnight a night shift falls on decides whether a daily
  cap was broken.

  `opts` may carry `:period [from to]`. Some rules — see
  `period-dependent-rules` — cannot be evaluated without it and are
  listed in `:worklaw/unevaluated` when it is absent.

  Returns

    {:worklaw/coverage    :full | :partial | :none
     :worklaw/checked     [[:us] ...]      levels that had rules
     :worklaw/unchecked   [[:us :ca] ...]  levels that had none
     :worklaw/unevaluated [:jp-weekly-rest ...]  rules that needed a period
     :worklaw/violations  [...]
     :worklaw/citations   [...]}

  Read `:worklaw/coverage` and `:worklaw/unevaluated` before
  `:worklaw/violations`. An empty violation list under coverage `:none`
  means nothing was checked, and one with a non-empty `:unevaluated`
  means part of the statute was skipped."
  ([worked jurisdiction date-of] (check worked jurisdiction date-of {}))
  ([worked jurisdiction date-of opts]
   (let [period (:period opts)
         levels (levels-of jurisdiction)
         {checked true unchecked false} (group-by #(contains? rules %) levels)
         applicable (mapcat #(get-in rules [% :law/rules]) checked)
         by-day (day-groups worked date-of)
         daily (for [r applicable
                     [day spans] by-day
                     :let [v (check-rule r day spans)]
                     :when v]
                 v)
         weekly (keep #(check-weekly % worked) applicable)
         rest-v (mapcat #(or (check-rest % worked period) []) applicable)
         unevaluated (when-not period
                       (->> applicable
                            (filter #(contains? period-dependent-rules (:rule/kind %)))
                            (mapv :rule/id)))]
     {:worklaw/coverage    (cond (empty? checked)   :none
                                 (seq unchecked)    :partial
                                 :else              :full)
      :worklaw/checked     (vec checked)
      :worklaw/unchecked   (vec unchecked)
      :worklaw/unevaluated (vec unevaluated)
      :worklaw/violations  (vec (concat daily weekly rest-v))
      :worklaw/citations   (vec (distinct (map :rule/citation applicable)))})))

(defn compliant?
  "True only when the jurisdiction was FULLY covered, every rule could be
  evaluated, and nothing fired.

  Deliberately not `(empty? violations)`. A partially covered
  jurisdiction, or one where a period-dependent rule was skipped, is not
  compliant; it is unknown, and this predicate says false so that a
  caller who reaches for the convenient boolean gets the conservative
  answer rather than the flattering one."
  [result]
  (and (= :full (:worklaw/coverage result))
       (empty? (:worklaw/unevaluated result))
       (empty? (:worklaw/violations result))))

(defn describe
  "Human summary. Always states coverage first, so a report can never
  read as an all-clear it did not earn."
  [result]
  (let [{:worklaw/keys [coverage violations unchecked unevaluated]} result]
    (str (case coverage
           :none    (str "NOT CHECKED — no rules for " (pr-str unchecked))
           :partial (str "PARTIALLY CHECKED — no rules for " (pr-str unchecked))
           :full    "checked")
         (when (seq unevaluated)
           (str "; NOT EVALUATED without a period: " (pr-str unevaluated)))
         (if (seq violations)
           (str "; " (count violations) " violation(s): "
                (str/join ", " (map #(name (get-in % [:violation/rule :rule/id])) violations)))
           (if (and (= :full coverage) (empty? unevaluated)) "; no violations" "")))))

(defn absences
  "What a jurisdiction's statute is recorded as NOT regulating. Distinct
  from a missing rule set: `[:us]` has no daily hour cap because federal
  law sets none, and a caller building a roster tool should surface that
  rather than infer it."
  [jurisdiction]
  (vec (mapcat #(get-in rules [% :law/absent]) (levels-of jurisdiction))))
