(ns odoyle.examples.process-orchestrator-test
  "Self-healing process orchestrator using meta-rules.

   A workflow engine where transitions between order states are declared as data.
   Meta-rules materialize transition rules, validate states, monitor the topology
   for dead ends, and reject invalid requests. When structural violations occur
   (invalid states, dead-end topologies), the system detects and repairs itself.

   Demonstrates: transition materialization, `remove-rule!` for regeneration,
   self-correcting state validation, self-healing topology monitoring,
   truth maintenance cascading, and retroactive initialization."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [clojure.string :as str]
            [odoyle.rules :as o]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]))

(st/instrument)
(st/unstrument 'odoyle.rules/insert)

;; ---------------------------------------------------------------------------
;; Specs for domain attributes
;; ---------------------------------------------------------------------------

;; Order attributes
(s/def :order/state keyword?)
(s/def :order/request keyword?)
(s/def :order/error string?)
(s/def :order/last-good-state keyword?)

;; Transition definitions
(s/def :transition/from keyword?)
(s/def :transition/to keyword?)

;; State metadata
(s/def :state/terminal? boolean?)

;; Domain metadata on generated transition rules (for topology monitor)
(s/def :transition-rule/from keyword?)
(s/def :transition-rule/to keyword?)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- extract-transition-pairs
  "Extracts #{[from-state to-state]} from a metadata map by introspecting
  transition rule conditions."
  [rule-meta-store]
  (reduce-kv
    (fn [acc rn meta]
      (if (and (= "transition" (namespace rn))
               (some #(= (get-in % [:attr :value]) :order/state)
                     (::o/conditions meta)))
        (let [from-state (some #(when (= (get-in % [:attr :value]) :order/state)
                                  (get-in % [:value :value]))
                               (::o/conditions meta))
              to-state   (some #(when (= (get-in % [:attr :value]) :order/request)
                                  (get-in % [:value :value]))
                               (::o/conditions meta))]
          (if (and from-state to-state)
            (conj acc [from-state to-state])
            acc))
        acc))
    #{}
    rule-meta-store))

(defn- extract-valid-states
  "Extracts the set of valid states from transition pairs."
  [pairs]
  (into #{:error}
        (mapcat (fn [[from to]] [from to]))
        pairs))

(defn- query-all-meta-map
  "Returns metadata as {rule-name {attr value}}."
  [session]
  (reduce
    (fn [m [rule-name attr value]]
      (assoc-in m [rule-name attr] value))
    {}
    (o/query-all-meta session)))

;; ---------------------------------------------------------------------------
;; Session builder
;; ---------------------------------------------------------------------------

(defn make-orchestrator-session
  "Returns a session with:
   - keeper rules (retain domain facts that predate generated rules)
   - transition-materializer (meta-rule: transition facts -> per-transition rules)
   - state-validator (meta-rule: introspects transitions -> correction rule)
   - topology-monitor (meta-rule: detects dead-end states -> emergency egress rules)
   - rejection-rule-generator (meta-rule: introspects transitions -> rejection rule)

   `*transitions-log`, `*corrections-log`, and `*healing-log` are atoms for output."
  [*transitions-log *corrections-log *healing-log]
  ;; Atoms to track current valid-states/valid-pairs for change detection.
  ;; This prevents infinite remove-rule!/add-rule! cycles: the :then-finally
  ;; blocks only regenerate when the actual set of states/pairs has changed.
  (let [*prev-valid-states (atom nil)
        *prev-valid-pairs  (atom nil)]
    (reduce o/add-rule (o/->session)
      (o/ruleset
        {;; --- Keeper rules ---------------------------------------------------

         ::keep-order-state
         [:what [order :order/state state]]

         ::keep-order-request
         [:what [order :order/request req]]

         ::keep-order-error
         [:what [order :order/error err]]

         ::keep-order-last-good-state
         [:what [order :order/last-good-state s]]

         ::keep-state-terminal
         [:what [state-id :state/terminal? t]]

         ::keep-transition-meta
         [:what [rule-name :transition-rule/from from]
                [rule-name :transition-rule/to to]]

         ;; --- Meta-rule 1: Transition Materializer ----------------------------
         ;; Watches transition definitions. For each (from, to) pair, generates
         ;; a rule that fires when an order is in from-state and requests to-state.

         ::transition-materializer
         [:what
          [t :transition/from from-state]
          [t :transition/to to-state]
          :then
          (let [rule-name (keyword "transition" (str (name from-state) "->" (name to-state)))]
            ;; Domain metadata for topology monitor
            (o/insert! rule-name :transition-rule/from from-state)
            (o/insert! rule-name :transition-rule/to to-state)
            (o/add-rule!
              (o/->rule rule-name
                {:what [['order :order/state from-state]
                        ['order :order/request to-state]]
                 :then (fn [session match]
                         (let [order (:order match)]
                           (o/insert! order :order/last-good-state from-state)
                           (o/insert! order :order/state to-state)
                           (o/retract! order :order/request)
                           (swap! *transitions-log conj
                                  {:order order
                                   :from from-state
                                   :to to-state})))})))]

         ;; --- Meta-rule 2: State Validator (self-correcting) -------------------
         ;; Watches transition rules' metadata to build valid-state set.
         ;; Generates a correction rule (with closed-over valid-states) that
         ;; detects invalid states and forces :error.
         ;; Uses atom-based change detection to avoid regeneration loops.

         ::state-validator
         [:what
          [rule-name ::o/rule true]
          [rule-name ::o/conditions conditions]
          :when
          (and (= "transition" (namespace rule-name))
               (some #(= (get-in % [:attr :value]) :order/state) conditions))
         :then-finally
          (let [pairs (extract-transition-pairs (query-all-meta-map session))
                valid-states (extract-valid-states pairs)
                correction-rule-name :validator/state-correction]
            ;; Only regenerate when valid-states actually changed
            (when (not= valid-states @*prev-valid-states)
              (reset! *prev-valid-states valid-states)
              (when (o/contains-rule? session correction-rule-name)
                (o/remove-rule! correction-rule-name))
              (o/add-rule!
                (o/->rule correction-rule-name
                  {:what [['order :order/state 'state]]
                   :when (fn [session match]
                           (not (contains? valid-states (:state match))))
                   :then (fn [session match]
                           (let [order (:order match)
                                 bad-state (:state match)]
                             (o/insert! order :order/state :error)
                             (o/insert! order :order/error (str "Invalid state: " bad-state))
                             (swap! *corrections-log conj
                                    {:order order
                                     :invalid-state bad-state})))}))))]

         ;; --- Meta-rule 3: Topology Monitor (self-healing) --------------------
         ;; Joins transition-rule domain metadata to reconstruct adjacency graph.
         ;; For each non-terminal state with no outbound transition, generates
         ;; an emergency egress rule. When dead-end is resolved, removes it.

         ::topology-monitor
         [:what
          [rule-name :transition-rule/from from-state]
          [rule-name :transition-rule/to to-state]
          :then-finally
          (let [;; build adjacency: source-state -> set of target-states
                adjacency
                (reduce
                  (fn [acc {:keys [from to]}]
                    (update acc from (fnil conj #{}) to))
                  {}
                  (o/query-all session ::keep-transition-meta))
                ;; collect all known states
                all-states (into (set (keys adjacency))
                                 (mapcat val adjacency))
                ;; terminal states are exempt
                terminal-states
                (into #{}
                      (map :state-id)
                      (o/query-all session ::keep-state-terminal))
                ;; dead-end states: non-terminal with no outbound transition
                dead-ends (set/difference
                            (set/difference all-states (set (keys adjacency)))
                            terminal-states
                            #{:error})
                fallback :error]
            ;; Generate emergency egress for new dead ends
            (doseq [dead-state dead-ends]
              (let [egress-name (keyword "emergency-egress" (str (name dead-state) "->error"))]
                (when-not (o/contains-rule? session egress-name)
                  (o/add-rule!
                    (o/->rule egress-name
                      {:what [['order :order/state dead-state]]
                       :then (fn [session match]
                               (let [order (:order match)]
                                 (o/insert! order :order/last-good-state dead-state)
                                 (o/insert! order :order/state fallback)
                                 (o/insert! order :order/error (str "Dead-end state: " dead-state ". Emergency fallback to " fallback))
                                 (swap! *healing-log conj
                                        {:order order
                                         :dead-end dead-state
                                         :fallback fallback})))})))))
            ;; Remove egress rules for states no longer dead ends
            (doseq [[rn meta] (query-all-meta-map session)
                    :when (= "emergency-egress" (namespace rn))]
              (let [egress-state (some #(when (= (get-in % [:attr :value]) :order/state)
                                          (get-in % [:value :value]))
                                       (::o/conditions meta))]
                (when (and egress-state (not (contains? dead-ends egress-state)))
                  (o/remove-rule! rn)))))]

         ;; --- Meta-rule 4: Rejection Rule Generator (self-enforcing) ----------
         ;; Introspects transition rules to extract valid (from, to) pairs.
         ;; Generates a rejection rule for invalid transition requests.
         ;; Uses atom-based change detection to avoid regeneration loops.

         ::rejection-rule-generator
         [:what
          [rule-name ::o/rule true]
          [rule-name ::o/conditions conditions]
          :when
          (and (= "transition" (namespace rule-name))
               (some #(= (get-in % [:attr :value]) :order/state) conditions))
         :then-finally
          (let [valid-pairs (extract-transition-pairs (query-all-meta-map session))
                rejection-rule-name :validator/rejection]
            ;; Only regenerate when valid-pairs actually changed
            (when (not= valid-pairs @*prev-valid-pairs)
              (reset! *prev-valid-pairs valid-pairs)
              (when (o/contains-rule? session rejection-rule-name)
                (o/remove-rule! rejection-rule-name))
              (o/add-rule!
                (o/->rule rejection-rule-name
                  {:what [['order :order/request 'requested]
                          ['order :order/state 'current]]
                   :when (fn [session match]
                           (not (contains? valid-pairs [(:current match) (:requested match)])))
                   :then (fn [session match]
                           (let [order (:order match)]
                             (o/insert! order :order/error
                                        (str "Invalid transition: " (:current match) " -> " (:requested match)))
                             ;; Guard: request may already be consumed by a concurrent transition rule
                             ;; in the same fire-rules cycle
                             (try
                               (o/retract! order :order/request)
                               (catch Exception _))
                             (swap! *transitions-log conj
                                    {:order order
                                     :rejected true
                                     :from (:current match)
                                     :to (:requested match)})))}))))]}))))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest basic-transitions
  (testing "Orders flow through the full lifecycle via declared transitions"
    (let [*trans  (atom [])
          *corr   (atom [])
          *heal   (atom [])
          session (-> (make-orchestrator-session *trans *corr *heal)
                      ;; Declare workflow states and transitions
                      (o/insert ::t1 {:transition/from :pending
                                      :transition/to :validated})
                      (o/insert ::t2 {:transition/from :validated
                                      :transition/to :paid})
                      (o/insert ::t3 {:transition/from :paid
                                      :transition/to :shipped})
                      (o/insert ::t4 {:transition/from :shipped
                                      :transition/to :delivered})
                      ;; Mark :delivered as terminal
                      (o/insert :delivered :state/terminal? true)
                      o/fire-rules)]

      ;; Transition rules should have been generated
      (is (o/contains-rule? session :transition/pending->validated))
      (is (o/contains-rule? session :transition/validated->paid))
      (is (o/contains-rule? session :transition/paid->shipped))
      (is (o/contains-rule? session :transition/shipped->delivered))

      ;; Process an order through the full lifecycle
      (let [session (-> session
                        (o/insert ::order-1 :order/state :pending)
                        (o/insert ::order-1 :order/request :validated)
                        o/fire-rules)]

        ;; First transition: pending -> validated
        (is (some #(and (= ::order-1 (:order %))
                        (= :pending (:from %))
                        (= :validated (:to %)))
                  @*trans)
            "pending->validated should fire")

        ;; Continue through remaining transitions
        (let [session (-> session
                          (o/insert ::order-1 :order/request :paid)
                          o/fire-rules
                          (o/insert ::order-1 :order/request :shipped)
                          o/fire-rules
                          (o/insert ::order-1 :order/request :delivered)
                          o/fire-rules)]

          ;; All 4 transitions should have fired
          (is (= 4 (count (filter #(and (= ::order-1 (:order %))
                                        (not (:rejected %)))
                                  @*trans)))
              "All 4 transitions should fire")

          ;; Final state should be :delivered
          (let [states (o/query-all session ::keep-order-state)]
            (is (some #(and (= ::order-1 (:order %))
                            (= :delivered (:state %)))
                      states)
                "Order should be in :delivered state")))))))

(deftest invalid-request-rejected
  (testing "Requesting an invalid transition is rejected"
    (let [*trans  (atom [])
          *corr   (atom [])
          *heal   (atom [])
          session (-> (make-orchestrator-session *trans *corr *heal)
                      (o/insert ::t1 {:transition/from :pending
                                      :transition/to :validated})
                      (o/insert ::t2 {:transition/from :validated
                                      :transition/to :paid})
                      (o/insert :paid :state/terminal? true)
                      o/fire-rules)]

      ;; Try to jump from :pending straight to :paid (not a valid transition)
      (let [session (-> session
                        (o/insert ::order-1 :order/state :pending)
                        (o/insert ::order-1 :order/request :paid)
                        o/fire-rules)]

        ;; Should be rejected
        (is (some #(and (= ::order-1 (:order %))
                        (true? (:rejected %)))
                  @*trans)
            "Invalid transition should be rejected")

        ;; Error should be recorded
        (let [errors (o/query-all session ::keep-order-error)]
          (is (some #(and (= ::order-1 (:order %))
                          (str/includes? (:err %) "Invalid transition"))
                    errors)
              "Error message should mention invalid transition"))))))

(deftest invalid-state-corrected
  (testing "Manually inserting an invalid state triggers correction to :error"
    (let [*trans  (atom [])
          *corr   (atom [])
          *heal   (atom [])
          session (-> (make-orchestrator-session *trans *corr *heal)
                      (o/insert ::t1 {:transition/from :pending
                                      :transition/to :validated})
                      (o/insert :validated :state/terminal? true)
                      o/fire-rules)]

      ;; Insert an invalid state directly
      (let [session (-> session
                        (o/insert ::order-1 :order/state :bogus)
                        o/fire-rules)]

        ;; State validator should correct to :error
        (is (pos? (count @*corr))
            "Correction log should have at least one entry")
        (is (some #(and (= ::order-1 (:order %))
                        (= :bogus (:invalid-state %)))
                  @*corr)
            "Correction should record the invalid state")

        ;; Order should now be in :error state
        (let [states (o/query-all session ::keep-order-state)]
          (is (some #(and (= ::order-1 (:order %))
                          (= :error (:state %)))
                    states)
              "Order should be corrected to :error state"))))))

(deftest dead-end-healing
  (testing "Removing a transition rule creates a dead end; topology monitor heals it"
    (let [*trans  (atom [])
          *corr   (atom [])
          *heal   (atom [])
          session (-> (make-orchestrator-session *trans *corr *heal)
                      (o/insert ::t1 {:transition/from :pending
                                      :transition/to :validated})
                      (o/insert ::t2 {:transition/from :validated
                                      :transition/to :paid})
                      (o/insert ::t3 {:transition/from :paid
                                      :transition/to :shipped})
                      (o/insert :shipped :state/terminal? true)
                      o/fire-rules)]

      ;; Move an order to :validated state
      (let [session (-> session
                        (o/insert ::order-1 :order/state :pending)
                        (o/insert ::order-1 :order/request :validated)
                        o/fire-rules)]

        (is (some #(and (= ::order-1 (:order %))
                        (= :validated (:state %)))
                  (o/query-all session ::keep-order-state))
            "Order should be in :validated")

        ;; Remove the validated->paid transition rule directly (simulating an admin
        ;; disabling a workflow step). Also retract domain metadata so the topology
        ;; monitor sees the change.
        (let [session (-> session
                          (o/remove-rule :transition/validated->paid)
                          (o/retract :transition/validated->paid :transition-rule/from)
                          (o/retract :transition/validated->paid :transition-rule/to)
                          o/fire-rules)]

          ;; Topology monitor should detect :validated as a dead end
          ;; and generate an emergency egress rule to migrate stuck entities
          (is (pos? (count @*heal))
              "Healing log should have entries")
          (is (some #(and (= ::order-1 (:order %))
                          (= :validated (:dead-end %)))
                    @*heal)
              "Order at :validated should be healed"))))))

(deftest healing-resolved
  (testing "Restoring a transition removes the emergency egress rule"
    (let [*trans  (atom [])
          *corr   (atom [])
          *heal   (atom [])
          session (-> (make-orchestrator-session *trans *corr *heal)
                      (o/insert ::t1 {:transition/from :pending
                                      :transition/to :validated})
                      (o/insert ::t2 {:transition/from :validated
                                      :transition/to :paid})
                      (o/insert :paid :state/terminal? true)
                      o/fire-rules)]

      ;; Remove validated->paid to create dead end
      (let [session (-> session
                        (o/remove-rule :transition/validated->paid)
                        (o/retract :transition/validated->paid :transition-rule/from)
                        (o/retract :transition/validated->paid :transition-rule/to)
                        o/fire-rules)]

        ;; Emergency egress should exist for :validated
        (is (o/contains-rule? session :emergency-egress/validated->error)
            "Emergency egress rule should exist for dead-end :validated")

        ;; Restore the transition (materializer will re-create the rule)
        (let [session (-> session
                          (o/insert ::t2-restored {:transition/from :validated
                                                   :transition/to :paid})
                          o/fire-rules)]

          ;; Emergency egress should be removed since :validated is no longer a dead end
          (is (not (o/contains-rule? session :emergency-egress/validated->error))
              "Emergency egress should be removed when dead end is resolved"))))))

(deftest truth-maintenance-cascade
  (testing "Removing the transition materializer cascades all derived transition rules"
    (let [*trans  (atom [])
          *corr   (atom [])
          *heal   (atom [])
          session (-> (make-orchestrator-session *trans *corr *heal)
                      (o/insert ::t1 {:transition/from :pending
                                      :transition/to :validated})
                      (o/insert ::t2 {:transition/from :validated
                                      :transition/to :paid})
                      (o/insert :paid :state/terminal? true)
                      o/fire-rules)]

      ;; Both transition rules should exist
      (is (o/contains-rule? session :transition/pending->validated))
      (is (o/contains-rule? session :transition/validated->paid))

      ;; Remove the materializer — all derived rules should cascade away
      (let [session (o/remove-rule session ::transition-materializer)]
        (is (not (o/contains-rule? session :transition/pending->validated))
            "Derived transition rule should cascade away")
        (is (not (o/contains-rule? session :transition/validated->paid))
            "Derived transition rule should cascade away")))))

(deftest retroactive-initialization
  (testing "Orders inserted before transitions exist are matched retroactively"
    (let [*trans  (atom [])
          *corr   (atom [])
          *heal   (atom [])
          session (-> (make-orchestrator-session *trans *corr *heal)
                      ;; Declare a single transition first so the materializer generates
                      ;; one rule. This establishes the keeper infrastructure.
                      (o/insert ::t0 {:transition/from :new
                                      :transition/to :pending})
                      o/fire-rules
                      ;; Now insert order facts BEFORE the pending->validated transition
                      (o/insert ::order-1 :order/state :pending)
                      (o/insert ::order-1 :order/request :validated)
                      o/fire-rules)]

      ;; No pending->validated transition yet, so the valid transition hasn't fired
      (is (not (some #(and (= ::order-1 (:order %))
                           (= :pending (:from %))
                           (= :validated (:to %))
                           (not (:rejected %)))
                     @*trans))
          "Transition should not fire without a matching transition declaration")

      ;; Now show retroactive initialization: insert order facts and declare
      ;; the transition in the SAME fire-rules cycle. The order facts are already
      ;; in the alpha nodes when the transition materializer generates the rule,
      ;; and retroactive init matches them.
      (let [*trans2 (atom [])
            *corr2  (atom [])
            *heal2  (atom [])
            session (-> (make-orchestrator-session *trans2 *corr2 *heal2)
                        ;; Insert order state BEFORE declaring transitions
                        ;; (keeper rules retain :order/state and :order/request)
                        (o/insert ::order-1 :order/state :pending)
                        (o/insert ::order-1 :order/request :validated)
                        ;; Declare transition in same batch — the generated rule
                        ;; retroactively finds the pre-existing order facts
                        (o/insert ::t1 {:transition/from :pending
                                        :transition/to :validated})
                        (o/insert :validated :state/terminal? true)
                        o/fire-rules)]

        ;; The pre-existing order should be matched retroactively
        (is (some #(and (= ::order-1 (:order %))
                        (= :pending (:from %))
                        (= :validated (:to %))
                        (not (:rejected %)))
                  @*trans2)
            "Pre-existing order should be matched retroactively")))))

(deftest dynamic-workflow-modification
  (testing "Adding a new transition at runtime is immediately usable"
    (let [*trans  (atom [])
          *corr   (atom [])
          *heal   (atom [])
          session (-> (make-orchestrator-session *trans *corr *heal)
                      (o/insert ::t1 {:transition/from :pending
                                      :transition/to :validated})
                      (o/insert :validated :state/terminal? true)
                      o/fire-rules)]

      ;; Only pending->validated exists
      (is (o/contains-rule? session :transition/pending->validated))
      (is (not (o/contains-rule? session :transition/validated->paid)))

      ;; Add a new transition at runtime
      (let [session (-> session
                        (o/insert ::t2 {:transition/from :validated
                                        :transition/to :paid})
                        (o/insert :paid :state/terminal? true)
                        o/fire-rules)]

        ;; New transition rule should exist
        (is (o/contains-rule? session :transition/validated->paid)
            "Newly added transition should create a rule")

        ;; Use the new transition
        (let [session (-> session
                          (o/insert ::order-1 :order/state :pending)
                          (o/insert ::order-1 :order/request :validated)
                          o/fire-rules
                          (o/insert ::order-1 :order/request :paid)
                          o/fire-rules)]

          ;; Both transitions should have fired
          (is (= 2 (count (filter #(and (= ::order-1 (:order %))
                                        (not (:rejected %)))
                                  @*trans)))
              "Both transitions should fire")

          ;; Final state should be :paid
          (let [states (o/query-all session ::keep-order-state)]
            (is (some #(and (= ::order-1 (:order %))
                            (= :paid (:state %)))
                      states)
                "Order should be in :paid state")))))))
