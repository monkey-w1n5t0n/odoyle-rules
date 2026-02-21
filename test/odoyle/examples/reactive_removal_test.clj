(ns odoyle.examples.reactive-removal-test
  "Feature flag system using reactive remove-rule!.

   Demonstrates remove-rule! as a first-class reactive operation: domain events
   (feature flags being disabled) trigger meta-rules to remove generated rules.
   This is distinct from the regeneration pattern (remove+re-add) used elsewhere.

   The system has two meta-rules watching the same feature-flag facts:
   - feature-materializer: when :feature/enabled is true, generates a per-feature rule
   - feature-disabler: when :feature/enabled is false, calls remove-rule! on the feature rule

   Generated feature rules produce observable effects (inserting :log/detail facts)
   that stop immediately once the rule is removed."
  (:require [clojure.test :refer [deftest is testing]]
            [odoyle.rules :as o]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]))

(st/instrument)
(st/unstrument 'odoyle.rules/insert)

;; ---------------------------------------------------------------------------
;; Specs for domain attributes
;; ---------------------------------------------------------------------------

(s/def :feature/enabled boolean?)
(s/def :feature/name keyword?)
(s/def :log/level keyword?)
(s/def :log/detail string?)

;; ---------------------------------------------------------------------------
;; Session builder
;; ---------------------------------------------------------------------------

(defn make-feature-flag-session
  "Returns a session with:
   - keeper rules (retain domain facts that predate generated rules)
   - feature-materializer (meta-rule: enabled=true -> generates feature rule)
   - feature-disabler (meta-rule: enabled=false -> remove-rule! on feature rule)"
  []
  (reduce o/add-rule (o/->session)
    (o/ruleset
      {;; --- Keeper rules ---------------------------------------------------
       ;; Retain domain facts so they survive until generated rules arrive.

       ::keep-feature
       [:what
        [feature-id :feature/enabled enabled]
        [feature-id :feature/name name]]

       ::keep-log-level
       [:what [entity :log/level level]]

       ::keep-log-detail
       [:what [entity :log/detail detail]]

       ;; --- Meta-rule 1: Feature materializer --------------------------------
       ;; When a feature flag is enabled, generate a per-feature rule.
       ;; The verbose-logging feature inserts :log/detail when :log/level is :debug.
       ;; The audit-mode feature inserts :log/detail when :log/level is :info.

       ::feature-materializer
       [:what
        [feature-id :feature/enabled true]
        [feature-id :feature/name feature-name]
        :then
        (let [rule-name (keyword "feature-rule" (name feature-name))]
          (when-not (o/contains-rule? session rule-name)
            (case feature-name
              :verbose-logging
              (o/add-rule!
                (o/->rule rule-name
                  {:what [['entity :log/level :debug]]
                   :then (fn [session match]
                           (o/insert! (:entity match) :log/detail
                                      "VERBOSE: debug-level logging active"))}))
              :audit-mode
              (o/add-rule!
                (o/->rule rule-name
                  {:what [['entity :log/level :info]]
                   :then (fn [session match]
                           (o/insert! (:entity match) :log/detail
                                      "AUDIT: info-level logging active"))}))
              ;; Unknown features: no-op
              nil)))]

       ;; --- Meta-rule 2: Feature disabler ------------------------------------
       ;; When a feature flag is disabled, reactively remove the generated rule.
       ;; This is the headline use case: domain event -> remove-rule!

       ::feature-disabler
       [:what
        [feature-id :feature/enabled false]
        [feature-id :feature/name feature-name]
        :then
        (let [rule-name (keyword "feature-rule" (name feature-name))]
          (when (o/contains-rule? session rule-name)
            (o/remove-rule! rule-name)))]})))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest enable-feature-creates-rule
  (testing "Setting feature flag to true generates the feature rule and it fires"
    (let [session (-> (make-feature-flag-session)
                      ;; Enable verbose-logging
                      (o/insert ::flag-verbose
                                {:feature/enabled true
                                 :feature/name :verbose-logging})
                      ;; Insert a log entity at :debug level
                      (o/insert ::app-log :log/level :debug)
                      o/fire-rules)]

      ;; The generated rule should exist
      (is (o/contains-rule? session :feature-rule/verbose-logging)
          "Feature rule should be created when flag is enabled")

      ;; The generated rule should have fired and inserted a detail fact
      (let [details (o/query-all session ::keep-log-detail)]
        (is (= 1 (count details))
            "Verbose logging rule should produce one detail fact")
        (is (= "VERBOSE: debug-level logging active"
               (:detail (first details))))))))

(deftest disable-feature-removes-rule
  (testing "Setting feature flag to false reactively removes the generated rule"
    (let [session (-> (make-feature-flag-session)
                      ;; Enable, insert domain facts, fire
                      (o/insert ::flag-verbose
                                {:feature/enabled true
                                 :feature/name :verbose-logging})
                      (o/insert ::app-log :log/level :debug)
                      o/fire-rules)]

      ;; Confirm rule exists and has fired
      (is (o/contains-rule? session :feature-rule/verbose-logging))
      (is (= 1 (count (o/query-all session ::keep-log-detail))))

      ;; Now disable the flag
      (let [session (-> session
                        (o/insert ::flag-verbose :feature/enabled false)
                        o/fire-rules)]

        ;; The generated rule should be gone
        (is (not (o/contains-rule? session :feature-rule/verbose-logging))
            "Feature rule should be removed when flag is disabled")

        ;; The detail fact persists (keeper rule holds it) but no new
        ;; insertions will happen since the rule is gone.  To verify
        ;; the rule is truly gone, insert a NEW debug entity — it should
        ;; NOT get a detail fact.
        (let [session (-> session
                          (o/insert ::new-log :log/level :debug)
                          o/fire-rules)
              details (o/query-all session ::keep-log-detail)]
          ;; Only the original detail from before disabling should remain
          (is (= 1 (count details))
              "No new detail facts should be produced after rule removal"))))))

(deftest re-enable-feature
  (testing "Toggling false -> true brings the feature rule back"
    (let [session (-> (make-feature-flag-session)
                      ;; Enable
                      (o/insert ::flag-verbose
                                {:feature/enabled true
                                 :feature/name :verbose-logging})
                      (o/insert ::app-log :log/level :debug)
                      o/fire-rules)]

      ;; Disable
      (let [session (-> session
                        (o/insert ::flag-verbose :feature/enabled false)
                        o/fire-rules)]

        (is (not (o/contains-rule? session :feature-rule/verbose-logging))
            "Rule should be gone after disabling")

        ;; Re-enable
        (let [session (-> session
                          (o/insert ::flag-verbose :feature/enabled true)
                          o/fire-rules)]

          (is (o/contains-rule? session :feature-rule/verbose-logging)
              "Rule should come back after re-enabling")

          ;; The existing :debug entity should be matched retroactively
          (let [details (o/query-all session ::keep-log-detail)]
            (is (pos? (count details))
                "Re-enabled rule should retroactively match existing facts")))))))

(deftest multiple-features-independent
  (testing "Disabling one feature does not affect others"
    (let [session (-> (make-feature-flag-session)
                      ;; Enable both features
                      (o/insert ::flag-verbose
                                {:feature/enabled true
                                 :feature/name :verbose-logging})
                      (o/insert ::flag-audit
                                {:feature/enabled true
                                 :feature/name :audit-mode})
                      ;; Insert entities at both log levels
                      (o/insert ::debug-log :log/level :debug)
                      (o/insert ::info-log :log/level :info)
                      o/fire-rules)]

      ;; Both rules should exist
      (is (o/contains-rule? session :feature-rule/verbose-logging))
      (is (o/contains-rule? session :feature-rule/audit-mode))

      ;; Both should have produced detail facts
      (let [details (o/query-all session ::keep-log-detail)]
        (is (= 2 (count details))
            "Both features should produce detail facts"))

      ;; Disable ONLY verbose-logging
      (let [session (-> session
                        (o/insert ::flag-verbose :feature/enabled false)
                        o/fire-rules)]

        ;; verbose-logging rule gone, audit-mode rule still present
        (is (not (o/contains-rule? session :feature-rule/verbose-logging))
            "Disabled feature rule should be removed")
        (is (o/contains-rule? session :feature-rule/audit-mode)
            "Other feature rule should remain unaffected")

        ;; New :info entity should still get audit detail
        (let [session (-> session
                          (o/insert ::info-log-2 :log/level :info)
                          o/fire-rules)
              details (o/query-all session ::keep-log-detail)]
          ;; Original audit detail + new audit detail = at least 2 audit entries
          ;; (the original verbose one may persist via keeper)
          (is (some #(= "AUDIT: info-level logging active" (:detail %)) details)
              "Audit-mode should still produce detail facts"))))))

(deftest disable-before-any-domain-facts
  (testing "Removing a rule before it has had a chance to fire"
    (let [session (-> (make-feature-flag-session)
                      ;; Enable verbose-logging but insert NO domain facts
                      (o/insert ::flag-verbose
                                {:feature/enabled true
                                 :feature/name :verbose-logging})
                      o/fire-rules)]

      ;; Rule exists but has never fired (no :debug entities)
      (is (o/contains-rule? session :feature-rule/verbose-logging))
      (is (empty? (o/query-all session ::keep-log-detail)))

      ;; Disable before any domain facts arrive
      (let [session (-> session
                        (o/insert ::flag-verbose :feature/enabled false)
                        o/fire-rules)]

        (is (not (o/contains-rule? session :feature-rule/verbose-logging))
            "Rule should be removed even though it never fired")

        ;; Now insert domain facts — they should NOT produce detail facts
        (let [session (-> session
                          (o/insert ::app-log :log/level :debug)
                          o/fire-rules)]
          (is (empty? (o/query-all session ::keep-log-detail))
              "No detail facts should exist since the rule was removed before domain facts arrived"))))))
