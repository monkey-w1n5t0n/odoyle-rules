(ns odoyle.examples.elemental-combos-test
  "Elemental combo system using meta-rules (à la Genshin Impact / Divinity: Original Sin).

   Elements and combo recipes are declared as plain data facts. Meta-rules materialize
   the actual game logic at runtime. A meta-meta-rule discovers chain reactions by
   introspecting the structure of generated combo rules — joining meta-facts
   (::o/conditions) with domain facts (:combo-rule/produces) to detect A→B chains.

   Demonstrates: element/combo materializers, chain reaction detection via structural
   introspection, cross-domain/meta-fact joins, truth maintenance ({:root? true} and
   cascading removal), and retroactive initialization."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [odoyle.rules :as o]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]))

(st/instrument)
(st/unstrument 'odoyle.rules/insert)

;; ---------------------------------------------------------------------------
;; Specs for domain attributes
;; ---------------------------------------------------------------------------

;; Element definitions
(s/def :element/name keyword?)
(s/def :element/tick-damage number?)

;; Combo recipes
(s/def :combo/element-a keyword?)
(s/def :combo/element-b keyword?)
(s/def :combo/result keyword?)
(s/def :combo/multiplier number?)

;; Domain metadata on generated combo-rule entities (for chain detection)
(s/def :combo-rule/produces keyword?)

;; Status effects (one per known element/combo result)
(s/def :status/fire boolean?)
(s/def :status/water boolean?)
(s/def :status/ice boolean?)
(s/def :status/steam boolean?)
(s/def :status/shatter boolean?)
(s/def :status/frozen boolean?)
(s/def :status/absolute-zero boolean?)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- extract-status-attrs
  "Extract :status/* attribute keywords from structured condition maps."
  [conditions]
  (into #{}
        (comp
          (map #(get-in % [:attr :value]))
          (filter #(and (keyword? %)
                        (= "status" (namespace %)))))
        conditions))

;; ---------------------------------------------------------------------------
;; Session builder
;; ---------------------------------------------------------------------------

(defn make-elemental-session
  "Returns a session with:
   - keeper rules (retain domain facts that predate generated rules)
   - element-materializer (element data → per-element status rules)
   - combo-materializer (combo recipe data → per-combo reaction rules)
   - chain-reaction-detector (meta-meta-rule: introspects combo rules to find chains)

   `*element-log`, `*combo-log`, and `*chain-log` are atoms for side-channel output."
  [*element-log *combo-log *chain-log]
  (reduce o/add-rule (o/->session)
    (o/ruleset
      {;; --- Keeper rules ---------------------------------------------------
       ;; O'Doyle discards facts matching no rule. Keepers ensure domain facts
       ;; survive until generated rules arrive.

       ::keep-status-fire
       [:what [entity :status/fire val]]

       ::keep-status-water
       [:what [entity :status/water val]]

       ::keep-status-ice
       [:what [entity :status/ice val]]

       ::keep-status-steam
       [:what [entity :status/steam val]]

       ::keep-status-shatter
       [:what [entity :status/shatter val]]

       ::keep-status-frozen
       [:what [entity :status/frozen val]]

       ::keep-status-absolute-zero
       [:what [entity :status/absolute-zero val]]

       ::keep-combo-rule-produces
       [:what [rule-name :combo-rule/produces product]]

       ;; --- Meta-rule 1: Element Materializer --------------------------------
       ;; Watches element definitions. For each element, generates a rule that
       ;; fires when an entity gains that element's status.

       ::element-materializer
       [:what
        [elem-id :element/name element]
        [elem-id :element/tick-damage dmg]
        :then
        (let [rule-name (keyword "element" (name element))
              status-attr (keyword "status" (name element))]
          (o/add-rule!
            (o/->rule rule-name
              {:what [['entity status-attr true]]
               :then (fn [session match]
                       (swap! *element-log conj
                              {:entity (:entity match)
                               :element element
                               :damage dmg}))})))]

       ;; --- Meta-rule 2: Combo Materializer ----------------------------------
       ;; Watches combo recipes. For each (element-a, element-b) → result triple,
       ;; generates a reaction rule that fires when an entity has BOTH trigger
       ;; elements, applies the result status, and logs the combo.
       ;;
       ;; Also inserts :combo-rule/produces on the generated rule's entity id,
       ;; enabling the chain reaction detector to join domain + meta facts.

       ::combo-materializer
       [:what
        [combo-id :combo/element-a elem-a]
        [combo-id :combo/element-b elem-b]
        [combo-id :combo/result result]
        [combo-id :combo/multiplier mult]
        :then
        (let [rule-name (keyword "combo" (name combo-id))
              status-a (keyword "status" (name elem-a))
              status-b (keyword "status" (name elem-b))
              result-status (keyword "status" (name result))]
          ;; Domain metadata: what this combo produces (chain detector joins on this)
          (o/insert! rule-name :combo-rule/produces result)
          (o/add-rule!
            (o/->rule rule-name
              {:what [['entity status-a true]
                      ['entity status-b true]]
               :then (fn [session match]
                       (o/insert! (:entity match) result-status true)
                       (swap! *combo-log conj
                              {:entity (:entity match)
                               :combo combo-id
                               :result result}))})))]

       ;; --- Meta-meta-rule: Chain Reaction Detector --------------------------
       ;; Joins meta-facts (::o/conditions) with domain facts (:combo-rule/produces)
       ;; to discover reaction chains. When combo A produces a status that combo B
       ;; consumes (detected via structural introspection of B's conditions),
       ;; generates a fused chain-reaction rule.
       ;;
       ;; Example: vaporize (fire+water→steam) + flash-freeze (steam+ice→absolute-zero)
       ;; → chain rule watches fire+water+ice, directly produces absolute-zero.
       ;;
       ;; Uses {:root? true} so chain rules survive individual combo removal.

       ::chain-reaction-detector
       [:what
        [rule-a ::o/rule true]
        [rule-a ::o/conditions conds-a]
        [rule-a :combo-rule/produces produced-a]
        [rule-b ::o/rule true]
        [rule-b ::o/conditions conds-b]
        [rule-b :combo-rule/produces produced-b]
        :when
        (and (not= rule-a rule-b)
             (= "combo" (namespace rule-a))
             (= "combo" (namespace rule-b))
             ;; B consumes what A produces (structural introspection!)
             (let [link-status (keyword "status" (name produced-a))]
               (some #(= (get-in % [:attr :value]) link-status) conds-b)))
        :then
        (let [a-triggers (extract-status-attrs conds-a)
              b-triggers (extract-status-attrs conds-b)
              link-status (keyword "status" (name produced-a))
              ;; Chain triggers = A's triggers ∪ (B's triggers - the link)
              chain-triggers (vec (sort-by str (into a-triggers
                                                     (disj b-triggers link-status))))
              chain-name (keyword "chain" (str (name rule-a) "+" (name rule-b)))
              result-status (keyword "status" (name produced-b))]
          (o/add-rule!
            (o/->rule chain-name
              {:what (mapv (fn [attr] ['entity attr true]) chain-triggers)
               :then (fn [session match]
                       (o/insert! (:entity match) result-status true)
                       (swap! *chain-log conj
                              {:entity (:entity match)
                               :chain [rule-a rule-b]
                               :result produced-b}))})
            {:root? true}))]})))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest basic-element-materialization
  (testing "Element definitions generate per-element status rules"
    (let [*elem  (atom [])
          *combo (atom [])
          *chain (atom [])
          session (-> (make-elemental-session *elem *combo *chain)
                      (o/insert ::fire-elem {:element/name :fire
                                             :element/tick-damage 5})
                      (o/insert ::water-elem {:element/name :water
                                              :element/tick-damage 2})
                      o/fire-rules)]

      ;; Element rules should have been generated
      (is (o/contains-rule? session :element/fire)
          "element-materializer should create :element/fire")
      (is (o/contains-rule? session :element/water)
          "element-materializer should create :element/water")

      ;; Apply fire to an entity
      (let [session (-> session
                        (o/insert ::goblin :status/fire true)
                        o/fire-rules)]
        (is (= 1 (count @*elem)))
        (is (= {:entity ::goblin :element :fire :damage 5}
               (first @*elem)))

        ;; Apply water too
        (let [session (-> session
                          (o/insert ::goblin :status/water true)
                          o/fire-rules)]
          (is (= 2 (count @*elem)))
          (is (some #(= {:entity ::goblin :element :water :damage 2} %) @*elem)))))))

(deftest combo-materialization
  (testing "Combo recipes generate reaction rules that fire on dual elements"
    (let [*elem  (atom [])
          *combo (atom [])
          *chain (atom [])
          session (-> (make-elemental-session *elem *combo *chain)
                      ;; Elements
                      (o/insert ::fire-elem {:element/name :fire
                                             :element/tick-damage 5})
                      (o/insert ::water-elem {:element/name :water
                                              :element/tick-damage 2})
                      ;; Combo recipe
                      (o/insert ::vaporize {:combo/element-a :fire
                                            :combo/element-b :water
                                            :combo/result :steam
                                            :combo/multiplier 2.0})
                      o/fire-rules)]

      ;; Combo rule should exist
      (is (o/contains-rule? session :combo/vaporize)
          "combo-materializer should create :combo/vaporize")

      ;; Apply both elements to trigger the combo
      (let [session (-> session
                        (o/insert ::goblin :status/fire true)
                        (o/insert ::goblin :status/water true)
                        o/fire-rules)]

        ;; Combo should have fired
        (is (= 1 (count @*combo)))
        (is (= {:entity ::goblin :combo ::vaporize :result :steam}
               (first @*combo)))

        ;; Result status should be applied
        (let [steam-matches (o/query-all session ::keep-status-steam)]
          (is (some #(and (= ::goblin (:entity %)) (true? (:val %)))
                    steam-matches)
              "Goblin should have :status/steam after vaporize"))))))

(deftest combo-does-not-fire-with-single-element
  (testing "Combo rule requires BOTH elements — single element alone does nothing"
    (let [*elem  (atom [])
          *combo (atom [])
          *chain (atom [])
          session (-> (make-elemental-session *elem *combo *chain)
                      (o/insert ::fire-elem {:element/name :fire
                                             :element/tick-damage 5})
                      (o/insert ::water-elem {:element/name :water
                                              :element/tick-damage 2})
                      (o/insert ::vaporize {:combo/element-a :fire
                                            :combo/element-b :water
                                            :combo/result :steam
                                            :combo/multiplier 2.0})
                      o/fire-rules
                      ;; Only apply fire — not water
                      (o/insert ::goblin :status/fire true)
                      o/fire-rules)]

      (is (empty? @*combo)
          "Combo should NOT fire with only one trigger element"))))

(deftest chain-reaction-auto-detection
  (testing "Chain detector finds A→B chains via condition introspection"
    (let [*elem  (atom [])
          *combo (atom [])
          *chain (atom [])
          session (-> (make-elemental-session *elem *combo *chain)
                      ;; Elements
                      (o/insert ::fire-elem {:element/name :fire
                                             :element/tick-damage 5})
                      (o/insert ::water-elem {:element/name :water
                                              :element/tick-damage 2})
                      (o/insert ::ice-elem {:element/name :ice
                                            :element/tick-damage 3})
                      ;; Combo recipes: vaporize produces steam, flash-freeze consumes it
                      (o/insert ::vaporize {:combo/element-a :fire
                                            :combo/element-b :water
                                            :combo/result :steam
                                            :combo/multiplier 2.0})
                      (o/insert ::flash-freeze {:combo/element-a :steam
                                                :combo/element-b :ice
                                                :combo/result :absolute-zero
                                                :combo/multiplier 3.0})
                      ;; Also add melt — should NOT create a chain with vaporize
                      (o/insert ::melt {:combo/element-a :fire
                                        :combo/element-b :ice
                                        :combo/result :shatter
                                        :combo/multiplier 1.5})
                      o/fire-rules)]

      ;; Chain rule should be auto-generated for vaporize → flash-freeze
      (is (o/contains-rule? session :chain/vaporize+flash-freeze)
          "Chain detector should create :chain/vaporize+flash-freeze")

      ;; No chain for melt (shatter is not consumed by any combo)
      (is (not (some #(o/contains-rule? session %)
                     [:chain/melt+vaporize :chain/vaporize+melt
                      :chain/melt+flash-freeze :chain/flash-freeze+melt]))
          "No spurious chains should be created"))))

(deftest chain-reaction-execution
  (testing "Triple-element application triggers the chain reaction"
    (let [*elem  (atom [])
          *combo (atom [])
          *chain (atom [])
          session (-> (make-elemental-session *elem *combo *chain)
                      ;; Elements
                      (o/insert ::fire-elem {:element/name :fire
                                             :element/tick-damage 5})
                      (o/insert ::water-elem {:element/name :water
                                              :element/tick-damage 2})
                      (o/insert ::ice-elem {:element/name :ice
                                            :element/tick-damage 3})
                      ;; Chain-forming combos
                      (o/insert ::vaporize {:combo/element-a :fire
                                            :combo/element-b :water
                                            :combo/result :steam
                                            :combo/multiplier 2.0})
                      (o/insert ::flash-freeze {:combo/element-a :steam
                                                :combo/element-b :ice
                                                :combo/result :absolute-zero
                                                :combo/multiplier 3.0})
                      o/fire-rules)]

      ;; Apply all three base elements
      (let [session (-> session
                        (o/insert ::goblin :status/fire true)
                        (o/insert ::goblin :status/water true)
                        (o/insert ::goblin :status/ice true)
                        o/fire-rules)]

        ;; Chain reaction should have fired
        (is (pos? (count @*chain))
            "Chain reaction log should have at least one entry")
        (is (some #(and (= ::goblin (:entity %))
                        (= :absolute-zero (:result %)))
                  @*chain)
            "Chain should produce :absolute-zero")

        ;; The individual vaporize combo should also have fired
        (is (some #(= ::vaporize (:combo %)) @*combo)
            "Vaporize combo should also fire independently")

        ;; Result status should be on the entity
        (let [az-matches (o/query-all session ::keep-status-absolute-zero)]
          (is (some #(and (= ::goblin (:entity %)) (true? (:val %)))
                    az-matches)
              "Goblin should have :status/absolute-zero"))))))

(deftest truth-maintenance-cascading-removal
  (testing "Removing combo-materializer cascades to combo rules; chain survives (root? true)"
    (let [*elem  (atom [])
          *combo (atom [])
          *chain (atom [])
          session (-> (make-elemental-session *elem *combo *chain)
                      (o/insert ::fire-elem {:element/name :fire
                                             :element/tick-damage 5})
                      (o/insert ::water-elem {:element/name :water
                                              :element/tick-damage 2})
                      (o/insert ::ice-elem {:element/name :ice
                                            :element/tick-damage 3})
                      (o/insert ::vaporize {:combo/element-a :fire
                                            :combo/element-b :water
                                            :combo/result :steam
                                            :combo/multiplier 2.0})
                      (o/insert ::flash-freeze {:combo/element-a :steam
                                                :combo/element-b :ice
                                                :combo/result :absolute-zero
                                                :combo/multiplier 3.0})
                      o/fire-rules)]

      ;; Before removal: combo + chain rules exist
      (is (o/contains-rule? session :combo/vaporize))
      (is (o/contains-rule? session :combo/flash-freeze))
      (is (o/contains-rule? session :chain/vaporize+flash-freeze))

      ;; Remove the combo-materializer — derived combo rules cascade away
      (let [session (o/remove-rule session ::combo-materializer)]
        (is (not (o/contains-rule? session :combo/vaporize))
            "Derived combo rule should cascade away")
        (is (not (o/contains-rule? session :combo/flash-freeze))
            "Derived combo rule should cascade away")

        ;; Chain rule SURVIVES — it was created with {:root? true}
        (is (o/contains-rule? session :chain/vaporize+flash-freeze)
            "Chain rule should survive (root? true)")))))

(deftest truth-maintenance-single-combo-removal
  (testing "Removing a single combo rule does not cascade to other combos"
    (let [*elem  (atom [])
          *combo (atom [])
          *chain (atom [])
          session (-> (make-elemental-session *elem *combo *chain)
                      (o/insert ::fire-elem {:element/name :fire
                                             :element/tick-damage 5})
                      (o/insert ::water-elem {:element/name :water
                                              :element/tick-damage 2})
                      (o/insert ::vaporize {:combo/element-a :fire
                                            :combo/element-b :water
                                            :combo/result :steam
                                            :combo/multiplier 2.0})
                      (o/insert ::melt {:combo/element-a :fire
                                        :combo/element-b :ice
                                        :combo/result :shatter
                                        :combo/multiplier 1.5})
                      o/fire-rules)]

      (is (o/contains-rule? session :combo/vaporize))
      (is (o/contains-rule? session :combo/melt))

      ;; Remove only vaporize
      (let [session (o/remove-rule session :combo/vaporize)]
        (is (not (o/contains-rule? session :combo/vaporize)))
        (is (o/contains-rule? session :combo/melt)
            "Melt should be unaffected by vaporize removal")))))

(deftest retroactive-initialization
  (testing "Statuses applied before combo recipes are retroactively matched"
    (let [*elem  (atom [])
          *combo (atom [])
          *chain (atom [])
          session (-> (make-elemental-session *elem *combo *chain)
                      ;; Elements needed so their rules exist as keepers
                      (o/insert ::fire-elem {:element/name :fire
                                             :element/tick-damage 5})
                      (o/insert ::water-elem {:element/name :water
                                              :element/tick-damage 2})
                      o/fire-rules)]

      ;; Apply statuses BEFORE any combo recipe exists
      (let [session (-> session
                        (o/insert ::goblin :status/fire true)
                        (o/insert ::goblin :status/water true)
                        o/fire-rules)]

        ;; Element rules should fire
        (is (= 2 (count @*elem)))
        ;; No combo yet
        (is (empty? @*combo))

        ;; Now add the combo recipe — retroactive init should match
        (let [session (-> session
                          (o/insert ::vaporize {:combo/element-a :fire
                                                :combo/element-b :water
                                                :combo/result :steam
                                                :combo/multiplier 2.0})
                          o/fire-rules)]

          (is (o/contains-rule? session :combo/vaporize))

          ;; The pre-existing statuses should trigger the combo retroactively
          (is (= 1 (count @*combo))
              "Combo should fire from retroactively initialized facts")
          (is (= {:entity ::goblin :combo ::vaporize :result :steam}
                 (first @*combo))))))))

(deftest dynamic-combo-triggers-chain-detection
  (testing "Adding a combo at runtime that completes a chain triggers detection"
    (let [*elem  (atom [])
          *combo (atom [])
          *chain (atom [])
          session (-> (make-elemental-session *elem *combo *chain)
                      (o/insert ::fire-elem {:element/name :fire
                                             :element/tick-damage 5})
                      (o/insert ::water-elem {:element/name :water
                                              :element/tick-damage 2})
                      (o/insert ::ice-elem {:element/name :ice
                                            :element/tick-damage 3})
                      ;; Only vaporize initially — no chain possible
                      (o/insert ::vaporize {:combo/element-a :fire
                                            :combo/element-b :water
                                            :combo/result :steam
                                            :combo/multiplier 2.0})
                      o/fire-rules)]

      ;; No chain yet
      (is (not (o/contains-rule? session :chain/vaporize+flash-freeze)))

      ;; Add the completing combo at runtime
      (let [session (-> session
                        (o/insert ::flash-freeze {:combo/element-a :steam
                                                  :combo/element-b :ice
                                                  :combo/result :absolute-zero
                                                  :combo/multiplier 3.0})
                        o/fire-rules)]

        ;; Chain should now be detected
        (is (o/contains-rule? session :chain/vaporize+flash-freeze)
            "Chain should be detected when completing combo is added at runtime")))))

(deftest condition-introspection-of-generated-rules
  (testing "Structured conditions of generated combo rules expose their triggers"
    (let [*elem  (atom [])
          *combo (atom [])
          *chain (atom [])
          session (-> (make-elemental-session *elem *combo *chain)
                      (o/insert ::fire-elem {:element/name :fire
                                             :element/tick-damage 5})
                      (o/insert ::water-elem {:element/name :water
                                              :element/tick-damage 2})
                      (o/insert ::vaporize {:combo/element-a :fire
                                            :combo/element-b :water
                                            :combo/result :steam
                                            :combo/multiplier 2.0})
                      o/fire-rules)]

      ;; Inspect vaporize combo rule metadata
      (let [meta (o/query-all-meta session :combo/vaporize)]
        (is (some? meta) "Combo rule should have metadata")
        (is (true? (::o/rule meta)))

        ;; Should have 2 conditions: [entity :status/fire true] [entity :status/water true]
        (let [conditions (::o/conditions meta)]
          (is (= 2 (count conditions))
              "Vaporize should have 2 conditions (fire + water)")

          ;; Both should have literal :status/* attributes
          (let [attrs (set (map #(get-in % [:attr :value]) conditions))]
            (is (= #{:status/fire :status/water} attrs)
                "Conditions should watch :status/fire and :status/water"))

          ;; Both should have literal `true` values
          (is (every? #(= {:kind :value :value true} (:value %)) conditions)
              "Conditions should match literal `true`"))))))

(deftest multiple-entities-independent-combos
  (testing "Combos fire independently per entity"
    (let [*elem  (atom [])
          *combo (atom [])
          *chain (atom [])
          session (-> (make-elemental-session *elem *combo *chain)
                      (o/insert ::fire-elem {:element/name :fire
                                             :element/tick-damage 5})
                      (o/insert ::water-elem {:element/name :water
                                              :element/tick-damage 2})
                      (o/insert ::vaporize {:combo/element-a :fire
                                            :combo/element-b :water
                                            :combo/result :steam
                                            :combo/multiplier 2.0})
                      o/fire-rules
                      ;; Two entities with different elements
                      (o/insert ::goblin :status/fire true)
                      (o/insert ::goblin :status/water true)
                      (o/insert ::dragon :status/fire true)
                      ;; Dragon only has fire — no combo
                      o/fire-rules)]

      ;; Only goblin should trigger vaporize (has both fire + water)
      (is (= 1 (count @*combo)))
      (is (= ::goblin (:entity (first @*combo)))
          "Only the entity with both elements should trigger the combo")

      ;; Both entities have element logs
      (is (some #(= ::goblin (:entity %)) @*elem))
      (is (some #(= ::dragon (:entity %)) @*elem)))))
