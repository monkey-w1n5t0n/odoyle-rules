(ns odoyle.examples.rule-linter-test
  "Rule analysis and linting using meta-rules.

   Demonstrates the metadata attributes that other examples don't exercise:
   ::o/has-when?, ::o/has-then?, ::o/has-then-finally?, ::o/conditions-raw,
   and ::o/derived-from. Meta-rules inspect the structure of other rules
   to detect potential issues and trace provenance."
  (:require [clojure.test :refer [deftest is testing]]
            [odoyle.rules :as o]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]))

(st/instrument)
(st/unstrument 'odoyle.rules/insert)

;; ---------------------------------------------------------------------------
;; Specs for domain attributes
;; ---------------------------------------------------------------------------

(s/def :player/health number?)
(s/def :player/x number?)
(s/def :player/y number?)
(s/def :player/score number?)

;; ---------------------------------------------------------------------------
;; Helper: build the linter session
;; ---------------------------------------------------------------------------

(defn make-linter-session
  "Returns a session with four linter meta-rules:
   - no-guard-linter: flags rules with :then but no :when
   - overlap-detector: finds pairs of rules watching the same attribute
   - provenance-tracer: reads ::o/derived-from to log derivation chains
   - then-finally-auditor: flags rules using :then-finally blocks

   Atoms are caller-supplied for side-channel output."
  [*no-guard-warnings *overlap-warnings *provenance-log *then-finally-warnings]
  (reduce o/add-rule (o/->session)
    (o/ruleset
      {;; --- Keeper rules ---------------------------------------------------
       ;; Ensure domain facts survive until dynamically-generated rules arrive.

       ::keep-player-health
       [:what [player :player/health hp]]

       ::keep-player-x
       [:what [player :player/x x]]

       ::keep-player-y
       [:what [player :player/y y]]

       ::keep-player-score
       [:what [player :player/score s]]

       ;; --- Linter 1: No-guard linter --------------------------------------
       ;; Flags rules that have a :then block but no :when guard.
       ;; Uses: ::o/has-when?, ::o/has-then?

       ::no-guard-linter
       [:what
        [rule-name ::o/rule true]
        [rule-name ::o/has-when? has-when]
        [rule-name ::o/has-then? has-then]
        :when
        (and has-then (not has-when)
             ;; Skip keeper/linter rules themselves
             (not= "odoyle.examples.rule-linter-test"
                   (namespace rule-name)))
        :then
        (swap! *no-guard-warnings conj
               {:rule rule-name
                :message "Rule has :then but no :when guard"})]

       ;; --- Linter 2: Attribute overlap detector ----------------------------
       ;; Joins two rules' ::o/conditions to find shared watched attributes.
       ;; Also reads ::o/conditions-raw for the raw tuple in the warning.
       ;; Uses: ::o/conditions, ::o/conditions-raw

       ::overlap-detector
       [:what
        [rule-a ::o/rule true]
        [rule-a ::o/conditions conds-a]
        [rule-a ::o/conditions-raw raw-a]
        [rule-b ::o/rule true]
        [rule-b ::o/conditions conds-b]
        [rule-b ::o/conditions-raw raw-b]
        :when
        (and (not= rule-a rule-b)
             ;; Order the pair to avoid duplicate warnings
             (neg? (compare (str rule-a) (str rule-b)))
             ;; Skip linter/keeper rules
             (not= "odoyle.examples.rule-linter-test"
                   (namespace rule-a))
             (not= "odoyle.examples.rule-linter-test"
                   (namespace rule-b))
             ;; Check for a shared attribute
             (let [attrs-a (set (map #(get-in % [:attr :value]) conds-a))
                   attrs-b (set (map #(get-in % [:attr :value]) conds-b))]
               (seq (clojure.set/intersection attrs-a attrs-b))))
        :then
        (let [attrs-a (set (map #(get-in % [:attr :value]) conds-a))
              attrs-b (set (map #(get-in % [:attr :value]) conds-b))
              shared (clojure.set/intersection attrs-a attrs-b)
              ;; Find the raw tuple from rule-a that matches the first shared attr
              raw-tuple (some (fn [[raw cond]]
                                (when (shared (get-in cond [:attr :value]))
                                  raw))
                              (map vector raw-a conds-a))]
          (swap! *overlap-warnings conj
                 {:rules #{rule-a rule-b}
                  :shared-attrs shared
                  :sample-raw-tuple raw-tuple}))]

       ;; --- Linter 3: Provenance tracer ------------------------------------
       ;; Reads ::o/derived-from to log the derivation chain.
       ;; Uses: ::o/derived-from

       ::provenance-tracer
       [:what
        [rule-name ::o/rule true]
        [rule-name ::o/derived-from source]
        :then
        (swap! *provenance-log conj
               {:derived-rule rule-name
                :source source})]

       ;; --- Linter 4: Then-finally auditor ----------------------------------
       ;; Flags rules that use :then-finally blocks.
       ;; Uses: ::o/has-then-finally?

       ::then-finally-auditor
       [:what
        [rule-name ::o/rule true]
        [rule-name ::o/has-then-finally? has-tf]
        :when
        (and has-tf
             (not= "odoyle.examples.rule-linter-test"
                   (namespace rule-name)))
        :then
        (swap! *then-finally-warnings conj
               {:rule rule-name
                :message "Rule uses :then-finally block"})]})))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest no-guard-linter-flags-unguarded-rules
  (testing "Rules with :then but no :when are flagged"
    (let [*ng  (atom [])
          *ov  (atom [])
          *pr  (atom [])
          *tf  (atom [])
          session (-> (make-linter-session *ng *ov *pr *tf)
                      ;; Add a rule with :then but NO :when
                      o/fire-rules)]
      ;; Add an unguarded dynamic rule
      (let [session (-> session
                        (o/add-rule
                          (o/->rule :game/health-logger
                            {:what [['player :player/health 'hp]]
                             :then (fn [session match] nil)}))
                        o/fire-rules)]
        (is (pos? (count @*ng))
            "No-guard linter should flag :game/health-logger")
        (is (some #(= :game/health-logger (:rule %)) @*ng))))))

(deftest no-guard-linter-skips-guarded-rules
  (testing "Rules with both :then and :when are NOT flagged"
    (let [*ng  (atom [])
          *ov  (atom [])
          *pr  (atom [])
          *tf  (atom [])
          session (-> (make-linter-session *ng *ov *pr *tf)
                      (o/add-rule
                        (o/->rule :game/health-check
                          {:what [['player :player/health 'hp]]
                           :when (fn [session match] (< (:hp match) 10))
                           :then (fn [session match] nil)}))
                      o/fire-rules)]
      (is (not (some #(= :game/health-check (:rule %)) @*ng))
          "Guarded rule should NOT be flagged"))))

(deftest attribute-overlap-detection
  (testing "Two rules watching the same attribute are detected"
    (let [*ng  (atom [])
          *ov  (atom [])
          *pr  (atom [])
          *tf  (atom [])
          session (-> (make-linter-session *ng *ov *pr *tf)
                      ;; Both rules watch :player/health
                      (o/add-rule
                        (o/->rule :game/damage-tracker
                          {:what [['player :player/health 'hp]]
                           :then (fn [session match] nil)}))
                      (o/add-rule
                        (o/->rule :game/heal-check
                          {:what [['player :player/health 'hp]
                                  ['player :player/score 'sc]]
                           :when (fn [session match] (> (:hp match) 50))
                           :then (fn [session match] nil)}))
                      o/fire-rules)]

      (is (pos? (count @*ov))
          "Overlap detector should find shared :player/health")
      (let [warning (first @*ov)]
        (is (= #{:game/damage-tracker :game/heal-check} (:rules warning)))
        (is (contains? (:shared-attrs warning) :player/health))
        ;; Verify ::o/conditions-raw was actually read — sample-raw-tuple should be a vector
        (is (vector? (:sample-raw-tuple warning))
            "Raw tuple from ::o/conditions-raw should be a vector")
        (is (some #{:player/health} (:sample-raw-tuple warning))
            "Raw tuple should contain the overlapping attribute")))))

(deftest provenance-tracer-reads-derived-from
  (testing "::o/derived-from is correctly set and read for dynamically generated rules"
    (let [*ng  (atom [])
          *ov  (atom [])
          *pr  (atom [])
          *tf  (atom [])
          session (-> (make-linter-session *ng *ov *pr *tf)
                      ;; Add a meta-rule that generates a derived rule
                      (o/add-rule
                        (first
                          (o/ruleset
                            {:game/position-watcher
                             [:what
                              [player :player/x x]
                              [player :player/y y]
                              :then
                              (o/add-rule!
                                (o/->rule :game/derived-distance
                                  {:what [['p :player/x 'px]
                                          ['p :player/y 'py]]
                                   :when (fn [session match]
                                           (> (+ (Math/abs (double (:px match)))
                                                 (Math/abs (double (:py match))))
                                              100.0))
                                   :then (fn [session match] nil)}))]})))
                      ;; Insert facts to trigger the meta-rule
                      (o/insert ::player-1 {:player/x 60.0 :player/y 60.0})
                      o/fire-rules)]

      ;; The derived rule should exist
      (is (o/contains-rule? session :game/derived-distance)
          "Meta-rule should generate :game/derived-distance")

      ;; Provenance tracer should have logged the derivation
      (is (pos? (count @*pr))
          "Provenance tracer should log the derivation")
      (let [entry (first (filter #(= :game/derived-distance (:derived-rule %)) @*pr))]
        (is (some? entry)
            "Should have a provenance entry for :game/derived-distance")
        (is (= :game/position-watcher (:source entry))
            "Source should be :game/position-watcher")))))

(deftest then-finally-auditor-detects-usage
  (testing "Rules with :then-finally blocks are flagged"
    (let [*ng  (atom [])
          *ov  (atom [])
          *pr  (atom [])
          *tf  (atom [])
          session (-> (make-linter-session *ng *ov *pr *tf)
                      (o/add-rule
                        (o/->rule :game/score-summary
                          {:what [['player :player/score 'sc]]
                           :then-finally (fn [session] nil)}))
                      o/fire-rules)]

      (is (pos? (count @*tf))
          "Then-finally auditor should flag :game/score-summary")
      (is (some #(= :game/score-summary (:rule %)) @*tf)))))

(deftest conditions-raw-contains-expected-tuples
  (testing "::o/conditions-raw round-trips correctly for dynamic rules"
    (let [*ng  (atom [])
          *ov  (atom [])
          *pr  (atom [])
          *tf  (atom [])
          session (-> (make-linter-session *ng *ov *pr *tf)
                      (o/add-rule
                        (o/->rule :game/position-rule
                          {:what [['entity :player/x 'px]
                                  ['entity :player/y 42]]
                           :then (fn [session match] nil)}))
                      o/fire-rules)]

      ;; Inspect raw conditions via query-all-meta
      (let [meta (o/query-all-meta session :game/position-rule)
            raw (::o/conditions-raw meta)]
        (is (= 2 (count raw))
            "Should have 2 raw conditions")
        ;; First condition: [entity :player/x px] — all bindings
        (is (some #(= :player/x (second %)) raw)
            "Should have a :player/x condition")
        ;; Second condition has a literal 42
        (is (some #(= 42 (nth % 2)) raw)
            "Should have a condition with literal value 42")))))
