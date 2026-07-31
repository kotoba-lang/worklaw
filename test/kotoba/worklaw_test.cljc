(ns kotoba.worklaw-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.worklaw :as law]))

(def ^:private hour 3600000)
(def ^:private day (* 24 hour))
(def ^:private t0 1767225600000) ;; 2026-01-01T00:00:00Z, a Thursday

(defn- worked
  "A worked span on day `d` from hour `from` to hour `to`, with `break-h`
  of break inside it."
  ([d from to] (worked d from to 0))
  ([d from to break-h]
   (let [start (+ t0 (* d day) (long (* from hour)))
         end   (+ t0 (* d day) (long (* to hour)))]
     {:worked/person   "w-1"
      :worked/start    start
      :worked/end      end
      :worked/gross-ms (- end start)
      :worked/break-ms (long (* break-h hour))
      :worked/ms       (- end start (long (* break-h hour)))})))

(defn- date-of [ms] (quot (- ms t0) day))

(def ^:private week {:period [t0 (+ t0 (* 7 day))]})

;; ---------------------------------------------------------------------------
;; The invariant — silence is never compliance
;; ---------------------------------------------------------------------------

(deftest an-unknown-jurisdiction-is-not-checked-and-not-compliant
  (let [r (law/check [(worked 0 9 23)] [:atlantis] date-of week)]
    (testing "23 hours in a day; nothing fires because nothing was checked"
      (is (empty? (:worklaw/violations r))))
    (is (= :none (:worklaw/coverage r)))
    (is (= [[:atlantis]] (:worklaw/unchecked r)))
    (testing "and the convenient boolean says false, not true"
      (is (not (law/compliant? r))))
    (is (= "NOT CHECKED — no rules for [[:atlantis]]" (law/describe r)))))

(deftest a-subnational-level-with-no-rules-makes-coverage-partial
  (testing "US federal has no daily overtime; California does. A 12-hour day
            in [:us :ca] must not read as compliant"
    (let [r (law/check [(worked 0 8 20)] [:us :ca] date-of week)]
      (is (= :partial (:worklaw/coverage r)))
      (is (= [[:us]] (:worklaw/checked r)))
      (is (= [[:us :ca]] (:worklaw/unchecked r)))
      (is (not (law/compliant? r)))
      (is (= "PARTIALLY CHECKED — no rules for [[:us :ca]]" (law/describe r))))))

(deftest full-coverage-with-nothing-firing-is-the-only-compliant-case
  (let [r (law/check [(worked 0 9 17 1)] [:jp] date-of week)]
    (is (= :full (:worklaw/coverage r)))
    (is (law/compliant? r))
    (is (= "checked; no violations" (law/describe r)))))

(deftest compliant?-is-not-just-an-empty-violation-list
  (let [unchecked (law/check [] [:atlantis] date-of week)
        checked   (law/check [] [:jp] date-of week)]
    (is (empty? (:worklaw/violations unchecked)))
    (is (empty? (:worklaw/violations checked)))
    (testing "same violation list, opposite verdicts"
      (is (not (law/compliant? unchecked)))
      (is (law/compliant? checked)))))

(deftest a-rule-that-needs-the-period-is-not-quietly-skipped
  (testing "without :period, weekly rest cannot be judged — Mon–Fri worked and
            nothing after says nothing about whether the weekend was off"
    (let [w (for [d (range 5)] (worked d 9 18 1))
          r (law/check w [:jp] date-of)]
      (is (= [:jp-weekly-rest] (:worklaw/unevaluated r)))
      (is (empty? (:worklaw/violations r)))
      (testing "coverage is full, yet the week is not compliant"
        (is (= :full (:worklaw/coverage r)))
        (is (not (law/compliant? r))))
      (is (= "checked; NOT EVALUATED without a period: [:jp-weekly-rest]"
             (law/describe r)))))
  (testing "with :period it resolves"
    (let [w (for [d (range 5)] (worked d 9 18 1))
          r (law/check w [:jp] date-of week)]
      (is (empty? (:worklaw/unevaluated r)))
      (is (law/compliant? r)))))

(deftest an-edge-gap-may-satisfy-a-rule-but-never-violate-one
  (testing "the window opens at midnight before an 09:00 start — a 9h leading
            gap that is an artifact of where the query was cut"
    (let [w [(worked 0 9 18 1) (worked 1 9 18 1)]
          r (law/check w [:eu] date-of {:period [t0 (+ t0 (* 7 day))]})]
      (testing "no daily-rest violation is manufactured from that edge"
        (is (empty? (filter #(= :eu-daily-rest-11 (get-in % [:violation/rule :rule/id]))
                            (:worklaw/violations r)))))
      (testing "but the trailing gap does satisfy weekly rest"
        (is (empty? (filter #(= :eu-weekly-rest-24 (get-in % [:violation/rule :rule/id]))
                            (:worklaw/violations r))))))))

;; ---------------------------------------------------------------------------
;; Japan
;; ---------------------------------------------------------------------------

(deftest jp-daily-cap
  (let [r (law/check [(worked 0 9 19 1)] [:jp] date-of week)]   ;; 9h worked
    (is (some #(= :jp-daily-8 (get-in % [:violation/rule :rule/id])) (:worklaw/violations r)))
    (testing "the violation carries its provision"
      (is (= "労働基準法 第32条第2項"
             (some #(when (= :jp-daily-8 (get-in % [:violation/rule :rule/id]))
                      (get-in % [:violation/rule :rule/citation]))
                   (:worklaw/violations r)))))))

(deftest jp-break-thresholds
  (testing "over 6h needs 45 min"
    (let [r (law/check [(worked 0 9 16 0.5)] [:jp] date-of week)]  ;; 6.5h worked, 30 min break
      (is (some #(= :jp-break-45 (get-in % [:violation/rule :rule/id]))
                (:worklaw/violations r)))))
  (testing "over 8h needs 60 min — 45 is enough for the 6h rule but not the 8h one"
    (let [r (law/check [(worked 0 9 18 0.75)] [:jp] date-of week)] ;; 8.25h worked, 45 min break
      (is (not (some #(= :jp-break-45 (get-in % [:violation/rule :rule/id]))
                     (:worklaw/violations r))))
      (is (some #(= :jp-break-60 (get-in % [:violation/rule :rule/id]))
                (:worklaw/violations r))))))

(deftest jp-weekly-cap
  (let [w (for [d (range 5)] (worked d 9 19 1))]   ;; 5 × 9h = 45h
    (is (some #(= :jp-weekly-40 (get-in % [:violation/rule :rule/id]))
              (:worklaw/violations (law/check w [:jp] date-of week))))))

(deftest jp-weekly-rest
  (testing "seven consecutive days with no 24h gap"
    (let [w (for [d (range 7)] (worked d 9 17 1))
          r (law/check w [:jp] date-of week)]
      (is (some #(= :jp-weekly-rest (get-in % [:violation/rule :rule/id]))
                (:worklaw/violations r)))))
  (testing "a genuine day off satisfies it"
    (let [w (concat (for [d (range 3)] (worked d 9 17 1))
                    (for [d (range 5 7)] (worked d 9 17 1)))
          r (law/check w [:jp] date-of week)]
      (is (not (some #(= :jp-weekly-rest (get-in % [:violation/rule :rule/id]))
                     (:worklaw/violations r)))))))

;; ---------------------------------------------------------------------------
;; United States (federal)
;; ---------------------------------------------------------------------------

(deftest us-overtime-is-a-premium-not-a-prohibition
  (let [w (for [d (range 5)] (worked d 9 19 1))   ;; 45h
        r (law/check w [:us] date-of week)
        v (first (:worklaw/violations r))]
    (is (= :us-weekly-ot-40 (get-in v [:violation/rule :rule/id])))
    (testing "flagged as overtime due, not as an illegal week"
      (is (= :overtime-due (:violation/kind v))))
    (is (= "29 U.S.C. §207(a)(1)" (get-in v [:violation/rule :rule/citation])))))

(deftest us-federal-has-no-daily-cap-and-says-so
  (let [r (law/check [(worked 0 8 20)] [:us] date-of week)]   ;; 12h day
    (is (= :full (:worklaw/coverage r)))
    (testing "12 hours breaks no federal rule, and that is the statute's silence"
      (is (empty? (:worklaw/violations r))))
    (testing "the silence is recorded rather than left to be inferred"
      (is (some #(= :daily-hours-max (:absent/kind %)) (law/absences [:us]))))))

(deftest a-40-hour-week-is-clean
  (let [w (for [d (range 5)] (worked d 9 18 1))]  ;; 5 × 8h
    (is (law/compliant? (law/check w [:us] date-of week)))))

;; ---------------------------------------------------------------------------
;; EU
;; ---------------------------------------------------------------------------

(deftest eu-weekly-48
  (let [w (for [d (range 6)] (worked d 8 17 0))]  ;; 6 × 9h = 54h
    (is (some #(= :eu-weekly-48 (get-in % [:violation/rule :rule/id]))
              (:worklaw/violations (law/check w [:eu] date-of week))))))

(deftest eu-daily-rest-of-11-hours
  (testing "finish at 23:00, start again at 07:00 — 8 hours' rest"
    (let [w [(worked 0 14 23) (worked 1 7 15)]
          r (law/check w [:eu] date-of week)
          v (first (filter #(= :eu-daily-rest-11 (get-in % [:violation/rule :rule/id]))
                           (:worklaw/violations r)))]
      (is (some? v))
      (is (= 8.0 (:violation/actual v))))))

(deftest eu-single-week-check-is-stricter-than-the-directive-and-says-so
  (testing "the directive averages over a reference period; the note records that"
    (let [rule (first (filter #(= :eu-weekly-48 (:rule/id %))
                              (get-in law/rules [[:eu] :law/rules])))]
      (is (some? (:rule/note rule)))
      (is (re-find #"reference period" (:rule/note rule))))))

;; ---------------------------------------------------------------------------
;; Rule-set hygiene
;; ---------------------------------------------------------------------------

(deftest every-rule-carries-a-citation
  (doseq [[j {:law/keys [rules]}] law/rules
          r rules]
    (is (string? (:rule/citation r)) (str j " " (:rule/id r)))
    (is (seq (:rule/citation r)) (str j " " (:rule/id r)))))

(deftest every-jurisdiction-carries-an-as-of-date
  (doseq [[j m] law/rules]
    (is (string? (:law/as-of m)) (str j))))

(deftest citations-travel-with-the-result
  (let [r (law/check [(worked 0 9 17 1)] [:jp] date-of week)]
    (is (seq (:worklaw/citations r)))
    (is (every? string? (:worklaw/citations r)))))

(deftest the-shipped-rule-set-is-small-and-known
  (testing "three jurisdictions. Anything else is :none, by construction"
    (is (= #{[:jp] [:us] [:eu]} (law/known-jurisdictions)))))
