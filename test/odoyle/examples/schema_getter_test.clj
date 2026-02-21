(ns odoyle.examples.schema-getter-test
  "Minimal meta-rules example: schema-driven getter rule generation.

   Demonstrates the core meta-rules pattern in ~30 lines of setup:
   a meta-rule watches schema declarations and generates getter rules
   that collect entity attributes."
  (:require [clojure.test :refer [deftest is testing]]
            [odoyle.rules :as o]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]))

(st/instrument)
(st/unstrument 'odoyle.rules/insert)

;; ---------------------------------------------------------------------------
;; Specs for domain attributes
;; ---------------------------------------------------------------------------

(s/def :schema/attributes (s/coll-of qualified-keyword?))
(s/def :player/x number?)
(s/def :player/y number?)
(s/def :player/health number?)

;; ---------------------------------------------------------------------------
;; Session builder
;; ---------------------------------------------------------------------------

(defn make-schema-session
  "Returns a session with:
   - keeper rules (retain domain facts until generated getter rules arrive)
   - schema->getter meta-rule (watches :schema/attributes, generates getters)"
  []
  (reduce o/add-rule (o/->session)
    (o/ruleset
      {;; Keeper rules: O'Doyle discards facts matching no rule.
       ;; These ensure domain facts survive until generated getters arrive.
       ::keep-x      [:what [id :player/x v]]
       ::keep-y      [:what [id :player/y v]]
       ::keep-health [:what [id :player/health v]]

       ;; Meta-rule: when a schema declaration appears, generate a getter rule
       ;; that watches every attribute listed in the schema.
       ::schema->getter
       [:what
        [entity-kw :schema/attributes attrs]
        :then
        (let [rule-name (keyword (namespace entity-kw)
                                 (str (name entity-kw) "-getter"))]
          (o/add-rule!
            (o/->rule rule-name
              {:what (mapv (fn [attr] [entity-kw attr (symbol (name attr))])
                           attrs)})))]})))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest schema-generates-getter-rule
  (testing "A schema declaration generates a getter rule"
    (let [session (-> (make-schema-session)
                      (o/insert :player/schema :schema/attributes
                                [:player/x :player/y :player/health])
                      o/fire-rules)]
      (is (o/contains-rule? session :player/schema-getter)
          "Meta-rule should generate :player/schema-getter"))))

(deftest getter-collects-domain-facts
  (testing "Domain facts inserted after the schema are collected by the getter"
    (let [session (-> (make-schema-session)
                      ;; Schema first, then domain facts
                      (o/insert :player/schema :schema/attributes
                                [:player/x :player/y :player/health])
                      (o/insert :player/schema {:player/x 10
                                                :player/y 20
                                                :player/health 100})
                      o/fire-rules)
          results (o/query-all session :player/schema-getter)]
      (is (= 1 (count results))
          "Getter should produce exactly one match")
      (is (= {:x 10 :y 20 :health 100}
             (select-keys (first results) [:x :y :health]))))))

(deftest retroactive-facts-matched
  (testing "Domain facts inserted BEFORE the schema are matched retroactively"
    (let [session (-> (make-schema-session)
                      ;; Domain facts first (keeper rules retain them)
                      (o/insert :player/schema {:player/x 5
                                                :player/y 15
                                                :player/health 75})
                      ;; Schema arrives later — getter is generated and
                      ;; retroactively finds the pre-existing facts
                      (o/insert :player/schema :schema/attributes
                                [:player/x :player/y :player/health])
                      o/fire-rules)
          results (o/query-all session :player/schema-getter)]
      (is (= 1 (count results))
          "Getter should retroactively match pre-existing facts")
      (is (= {:x 5 :y 15 :health 75}
             (select-keys (first results) [:x :y :health]))))))
