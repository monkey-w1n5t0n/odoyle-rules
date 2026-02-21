(ns odoyle.examples.access-control-test
  "Self-configuring access control using meta-rules.

  Demonstrates: policy materializer (meta-rule → generates enforcement rules),
  audit trail generator (meta-meta-rule → watches enforcement rules → generates audit rules),
  conflict detector (condition introspection via ::o/conditions), truth maintenance
  ({:root? true}, cascading removal), and retroactive initialization."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.set :as set]
            [odoyle.rules :as o]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as st]))

(st/instrument)
(st/unstrument 'odoyle.rules/insert)

;; ---------------------------------------------------------------------------
;; Specs for our domain attributes
;; ---------------------------------------------------------------------------

;; User attributes
(s/def :user/name string?)
(s/def :user/role keyword?)

;; Policy attributes — a policy binds (resource, action) → required role
(s/def :policy/resource keyword?)
(s/def :policy/action keyword?)
(s/def :policy/role keyword?)

;; Access request attributes
(s/def :access/user-id qualified-keyword?)
(s/def :access/resource keyword?)
(s/def :access/action keyword?)

;; Access decision (inserted by enforcement rules)
(s/def :access/decision keyword?)
(s/def :access/by-policy qualified-keyword?)

;; ---------------------------------------------------------------------------
;; Helper: build the access control session
;; ---------------------------------------------------------------------------

(defn make-access-control-session
  "Returns a session with:
   - keeper rules (retain domain facts that predate generated rules)
   - policy-materializer (meta-rule: policy facts → enforcement rules)
   - audit-generator (meta-meta-rule: enforcement rules → audit rules, {:root? true})
   - conflict-detector (meta-rule: introspects ::o/conditions of enforcement rules)

   `*audit-log` and `*conflicts` are atoms the caller supplies for side-channel output."
  [*audit-log *conflicts]
  (reduce o/add-rule (o/->session)
    (o/ruleset
      {;; --- Keeper rules ---------------------------------------------------
       ;; O'Doyle discards facts matching no rule.  Keepers ensure domain facts
       ;; survive until the generated enforcement/audit rules arrive.

       ::keep-user-role
       [:what [uid :user/role role]]

       ::keep-access-user-id
       [:what [req :access/user-id uid]]

       ::keep-access-resource
       [:what [req :access/resource res]]

       ::keep-access-action
       [:what [req :access/action act]]

       ::keep-access-decision
       [:what [req :access/decision d]]

       ::keep-access-by-policy
       [:what [req :access/by-policy p]]

       ;; --- Meta-rule 1: Policy materializer --------------------------------
       ;; Watches policy facts.  For each (resource, action, role) triple,
       ;; generates an enforcement rule that joins access-requests with user
       ;; roles and inserts a :access/decision when they match.

       ::policy-materializer
       [:what
        [policy-id :policy/resource resource]
        [policy-id :policy/action   action]
        [policy-id :policy/role     role]
        :then
        (let [rule-name (keyword "enforcement" (name policy-id))]
          (o/add-rule!
            (o/->rule rule-name
              {:what [['req   :access/user-id  'user-id]
                      ['req   :access/resource resource]   ;; literal from policy
                      ['req   :access/action   action]     ;; literal from policy
                      ['user-id :user/role     role]]      ;; literal from policy, joined on user-id
               :then (fn [session match]
                       (o/insert! (:req match) :access/decision  :granted)
                       (o/insert! (:req match) :access/by-policy policy-id))})))]

       ;; --- Meta-meta-rule: Audit trail generator ---------------------------
       ;; Watches ::o/conditions of ANY rule in the "enforcement" namespace.
       ;; That means it reacts to the enforcement rules created by
       ;; policy-materializer — rules about rules about rules.
       ;;
       ;; For each enforcement rule, generates an audit rule scoped to the
       ;; specific (resource, action) pair extracted from the enforcement
       ;; rule's conditions.  This showcases deep condition introspection:
       ;; the audit-generator reads the *structure* of generated rules to
       ;; parameterize its own output.
       ;;
       ;; Uses {:root? true} so audit rules survive enforcement rule removal.

       ::audit-generator
       [:what
        [rule-name ::o/rule       true]
        [rule-name ::o/conditions conditions]
        :when
        ;; Only enforcement rules
        (and (= "enforcement" (namespace rule-name))
             (some #(= (get-in % [:attr :value]) :access/resource)
                   conditions))
        :then
        (let [audit-rule-name (keyword "audit" (name rule-name))
              ;; Extract literal resource AND action from the enforcement rule's
              ;; conditions — deep condition introspection in action
              resource-val (some #(when (= (get-in % [:attr :value]) :access/resource)
                                    (get-in % [:value :value]))
                                 conditions)
              action-val   (some #(when (= (get-in % [:attr :value]) :access/action)
                                    (get-in % [:value :value]))
                                 conditions)]
          (o/add-rule!
            (o/->rule audit-rule-name
              {:what [['req :access/decision  :granted]
                      ['req :access/by-policy 'policy-id]
                      ['req :access/resource  resource-val]   ;; scoped to this resource
                      ['req :access/action    action-val]]    ;; AND this action
               :then (fn [session match]
                       (swap! *audit-log conj
                              {:event    :access-granted
                               :request  (:req match)
                               :policy   (:policy-id match)
                               :resource resource-val
                               :action   action-val}))})
            {:root? true}))]

       ;; --- Meta-rule 2: Conflict detector ----------------------------------
       ;; Joins ::o/conditions of pairs of enforcement rules.  When two
       ;; enforcement rules cover the same resource + action but require
       ;; different roles, reports a conflict.  This showcases condition
       ;; introspection: the rule reads the *structure* of other rules.

       ::conflict-detector
       [:what
        [rule1 ::o/rule       true]
        [rule1 ::o/conditions conds1]
        [rule2 ::o/rule       true]
        [rule2 ::o/conditions conds2]
        :when
        (and (not= rule1 rule2)
             (= "enforcement" (namespace rule1))
             (= "enforcement" (namespace rule2))
             ;; Extract the literal resource and action from each rule's conditions
             (let [extract (fn [conds attr]
                             (some #(when (= (get-in % [:attr :value]) attr)
                                      (get-in % [:value :value]))
                                   conds))
                   res1  (extract conds1 :access/resource)
                   res2  (extract conds2 :access/resource)
                   act1  (extract conds1 :access/action)
                   act2  (extract conds2 :access/action)
                   role1 (some #(when (= (get-in % [:attr :value]) :user/role)
                                  (get-in % [:value :value]))
                               conds1)
                   role2 (some #(when (= (get-in % [:attr :value]) :user/role)
                                  (get-in % [:value :value]))
                               conds2)]
               ;; Conflict: same resource+action, different required role
               (and res1 res2 (= res1 res2)
                    act1 act2 (= act1 act2)
                    role1 role2 (not= role1 role2))))
        :then
        (let [extract (fn [conds attr]
                        (some #(when (= (get-in % [:attr :value]) attr)
                                 (get-in % [:value :value]))
                              conds))
              resource (extract conds1 :access/resource)
              action   (extract conds1 :access/action)]
          (swap! *conflicts conj
                 {:resource resource
                  :action   action
                  :rules    #{rule1 rule2}}))]})))

;; ---------------------------------------------------------------------------
;; Tests
;; ---------------------------------------------------------------------------

(deftest basic-policy-enforcement
  (testing "Policies generate enforcement rules that grant access"
    (let [*audit  (atom [])
          *confl  (atom #{})
          session (-> (make-access-control-session *audit *confl)
                      ;; Users
                      (o/insert ::alice {:user/name "Alice" :user/role :editor})
                      (o/insert ::bob   {:user/name "Bob"   :user/role :viewer})
                      ;; Policies
                      (o/insert ::policy-read-docs
                                {:policy/resource :documents
                                 :policy/action   :read
                                 :policy/role     :viewer})
                      (o/insert ::policy-edit-docs
                                {:policy/resource :documents
                                 :policy/action   :write
                                 :policy/role     :editor})
                      ;; Access requests
                      (o/insert ::req-1 {:access/user-id ::bob
                                         :access/resource :documents
                                         :access/action   :read})
                      (o/insert ::req-2 {:access/user-id ::alice
                                         :access/resource :documents
                                         :access/action   :write})
                      o/fire-rules)]

      ;; Enforcement rules should have been generated
      (is (o/contains-rule? session :enforcement/policy-read-docs)
          "policy-materializer should create :enforcement/policy-read-docs")
      (is (o/contains-rule? session :enforcement/policy-edit-docs)
          "policy-materializer should create :enforcement/policy-edit-docs")

      ;; Both requests should be granted (Bob=viewer→read, Alice=editor→write)
      (let [decisions (o/query-all session ::keep-access-decision)]
        (is (= 2 (count decisions)) "Both requests should produce decisions")
        (is (every? #(= :granted (:d %)) decisions)))

      ;; Audit trail should have logged both grants
      (is (= 2 (count @*audit)) "Audit log should have 2 entries")
      (is (every? #(= :access-granted (:event %)) @*audit))

      ;; No conflicts (different actions: read vs write)
      (is (empty? @*confl) "No conflicts for different actions"))))

(deftest conflict-detection
  (testing "Conflict detector finds overlapping policies with different roles"
    (let [*audit  (atom [])
          *confl  (atom #{})
          session (-> (make-access-control-session *audit *confl)
                      ;; Two policies grant :read on :documents to different roles
                      (o/insert ::policy-viewer-reads
                                {:policy/resource :documents
                                 :policy/action   :read
                                 :policy/role     :viewer})
                      (o/insert ::policy-editor-reads
                                {:policy/resource :documents
                                 :policy/action   :read
                                 :policy/role     :editor})
                      o/fire-rules)]

      ;; Both enforcement rules exist
      (is (o/contains-rule? session :enforcement/policy-viewer-reads))
      (is (o/contains-rule? session :enforcement/policy-editor-reads))

      ;; Conflict detected
      (is (pos? (count @*confl))
          "Should detect at least one conflict")
      (let [conflict (first @*confl)]
        (is (= :documents (:resource conflict)))
        (is (= :read (:action conflict)))
        (is (= #{:enforcement/policy-viewer-reads :enforcement/policy-editor-reads}
               (:rules conflict)))))))

(deftest truth-maintenance-cascading-removal
  (testing "Removing enforcement rule cascades; audit rules survive via {:root? true}"
    (let [*audit  (atom [])
          *confl  (atom #{})
          session (-> (make-access-control-session *audit *confl)
                      (o/insert ::alice {:user/name "Alice" :user/role :editor})
                      (o/insert ::policy-edit-docs
                                {:policy/resource :documents
                                 :policy/action   :write
                                 :policy/role     :editor})
                      (o/insert ::req-1 {:access/user-id ::alice
                                         :access/resource :documents
                                         :access/action   :write})
                      o/fire-rules)]

      ;; Before removal: enforcement + audit rules exist
      (is (o/contains-rule? session :enforcement/policy-edit-docs))
      (is (o/contains-rule? session :audit/policy-edit-docs))
      (is (= 1 (count @*audit)) "One grant should be logged")

      ;; Directly remove the enforcement rule and verify audit survives
      (let [session (o/remove-rule session :enforcement/policy-edit-docs)]
        (is (not (o/contains-rule? session :enforcement/policy-edit-docs))
            "Enforcement rule should be removed")
        ;; Audit rule survives! (it was created with {:root? true})
        (is (o/contains-rule? session :audit/policy-edit-docs)
            "Audit rule should survive because it was added with {:root? true}")))))

(deftest truth-maintenance-materializer-removal
  (testing "Removing the policy-materializer cascades to all its derived enforcement rules"
    (let [*audit  (atom [])
          *confl  (atom #{})
          session (-> (make-access-control-session *audit *confl)
                      (o/insert ::policy-read-docs
                                {:policy/resource :documents
                                 :policy/action   :read
                                 :policy/role     :viewer})
                      (o/insert ::policy-edit-docs
                                {:policy/resource :documents
                                 :policy/action   :write
                                 :policy/role     :editor})
                      o/fire-rules)]

      ;; Both enforcement rules exist
      (is (o/contains-rule? session :enforcement/policy-read-docs))
      (is (o/contains-rule? session :enforcement/policy-edit-docs))

      ;; Remove the materializer itself — all enforcement rules should cascade away
      (let [session (o/remove-rule session ::policy-materializer)]
        (is (not (o/contains-rule? session :enforcement/policy-read-docs))
            "Derived enforcement rule should cascade away")
        (is (not (o/contains-rule? session :enforcement/policy-edit-docs))
            "Derived enforcement rule should cascade away")
        ;; Audit rules survive (root: true) — they were derived from audit-generator,
        ;; not from policy-materializer
        (is (o/contains-rule? session :audit/policy-read-docs)
            "Audit rule should survive (root? true)")
        (is (o/contains-rule? session :audit/policy-edit-docs)
            "Audit rule should survive (root? true)")))))

(deftest retroactive-initialization
  (testing "Facts inserted before enforcement rules exist are still matched"
    (let [*audit  (atom [])
          *confl  (atom #{})
          session (-> (make-access-control-session *audit *confl)
                      ;; Insert user and access request BEFORE any policy
                      (o/insert ::alice {:user/name "Alice" :user/role :editor})
                      (o/insert ::req-1 {:access/user-id ::alice
                                         :access/resource :documents
                                         :access/action   :write})
                      o/fire-rules)]

      ;; No enforcement rules yet, no decisions
      (is (not (o/contains-rule? session :enforcement/policy-edit-docs)))
      (is (empty? (o/query-all session ::keep-access-decision)))

      ;; Now insert the policy — enforcement rule is created and retroactively
      ;; finds the pre-existing request + user role in the alpha nodes
      (let [session (-> session
                        (o/insert ::policy-edit-docs
                                  {:policy/resource :documents
                                   :policy/action   :write
                                   :policy/role     :editor})
                        o/fire-rules)]

        (is (o/contains-rule? session :enforcement/policy-edit-docs)
            "Enforcement rule should be created")

        ;; The pre-existing request should be granted via retroactive init
        (let [decisions (o/query-all session ::keep-access-decision)]
          (is (= 1 (count decisions))
              "Pre-existing request should be retroactively matched")
          (is (= :granted (:d (first decisions)))))

        ;; Audit should have logged it too
        (is (= 1 (count @*audit)))))))

(deftest condition-introspection
  (testing "The structured condition maps expose rule internals accurately"
    (let [*audit  (atom [])
          *confl  (atom #{})
          session (-> (make-access-control-session *audit *confl)
                      (o/insert ::policy-read-docs
                                {:policy/resource :documents
                                 :policy/action   :read
                                 :policy/role     :viewer})
                      o/fire-rules)]

      ;; Inspect the generated enforcement rule's metadata
      (let [meta (o/query-all-meta session :enforcement/policy-read-docs)]
        (is (some? meta) "Enforcement rule should have metadata")
        (is (true? (::o/rule meta)))

        ;; The enforcement rule should have 4 conditions:
        ;; [req :access/user-id user-id]
        ;; [req :access/resource :documents]
        ;; [req :access/action :read]
        ;; [user-id :user/role :viewer]
        (let [conditions (::o/conditions meta)]
          (is (= 4 (count conditions))
              "Enforcement rule should have 4 conditions")

          ;; Check that literal values appear correctly in structured conditions
          (let [resource-cond (some #(when (= (get-in % [:attr :value]) :access/resource) %)
                                    conditions)]
            (is (= {:kind :value :value :documents}
                   (:value resource-cond))
                "Resource condition should have literal :documents"))

          (let [role-cond (some #(when (= (get-in % [:attr :value]) :user/role) %)
                                conditions)]
            (is (= {:kind :value :value :viewer}
                   (:value role-cond))
                "Role condition should have literal :viewer")))

        ;; Raw conditions should round-trip correctly
        (let [raw (::o/conditions-raw meta)]
          (is (= 4 (count raw)))
          ;; One of them should be [<sym> :access/resource :documents]
          (is (some #(and (= :access/resource (second %))
                          (= :documents (nth % 2)))
                    raw)))))))

(deftest multiple-users-multiple-policies
  (testing "Complex scenario with multiple users, policies, and requests"
    (let [*audit  (atom [])
          *confl  (atom #{})
          session (-> (make-access-control-session *audit *confl)
                      ;; Three users with different roles
                      (o/insert ::alice  {:user/name "Alice"  :user/role :admin})
                      (o/insert ::bob    {:user/name "Bob"    :user/role :editor})
                      (o/insert ::carol  {:user/name "Carol"  :user/role :viewer})
                      ;; Policies
                      (o/insert ::policy-view-docs
                                {:policy/resource :documents
                                 :policy/action   :read
                                 :policy/role     :viewer})
                      (o/insert ::policy-edit-docs
                                {:policy/resource :documents
                                 :policy/action   :write
                                 :policy/role     :editor})
                      (o/insert ::policy-admin-settings
                                {:policy/resource :settings
                                 :policy/action   :write
                                 :policy/role     :admin})
                      ;; Requests
                      (o/insert ::req-carol-read
                                {:access/user-id  ::carol
                                 :access/resource :documents
                                 :access/action   :read})
                      (o/insert ::req-bob-write
                                {:access/user-id  ::bob
                                 :access/resource :documents
                                 :access/action   :write})
                      (o/insert ::req-alice-settings
                                {:access/user-id  ::alice
                                 :access/resource :settings
                                 :access/action   :write})
                      ;; Bob tries to write settings (no policy grants editor→settings/write)
                      (o/insert ::req-bob-settings
                                {:access/user-id  ::bob
                                 :access/resource :settings
                                 :access/action   :write})
                      o/fire-rules)]

      ;; Three enforcement rules generated
      (is (o/contains-rule? session :enforcement/policy-view-docs))
      (is (o/contains-rule? session :enforcement/policy-edit-docs))
      (is (o/contains-rule? session :enforcement/policy-admin-settings))

      ;; Three audit rules generated (one per enforcement rule)
      (is (o/contains-rule? session :audit/policy-view-docs))
      (is (o/contains-rule? session :audit/policy-edit-docs))
      (is (o/contains-rule? session :audit/policy-admin-settings))

      ;; Carol (viewer) reads documents → granted
      ;; Bob (editor) writes documents → granted
      ;; Alice (admin) writes settings → granted
      ;; Bob (editor) writes settings → NOT granted (no matching policy)
      (let [decisions (o/query-all session ::keep-access-decision)]
        (is (= 3 (count decisions))
            "Three requests should be granted, one denied by absence")
        (is (every? #(= :granted (:d %)) decisions)))

      ;; Audit log has exactly 3 entries
      (is (= 3 (count @*audit)))

      ;; Bob's settings request should have no decision (implicit deny)
      (is (not (o/contains? session ::req-bob-settings :access/decision))
          "Bob should be implicitly denied settings access"))))

(deftest dynamic-policy-addition
  (testing "Adding a new policy at runtime creates enforcement for existing requests"
    (let [*audit  (atom [])
          *confl  (atom #{})
          session (-> (make-access-control-session *audit *confl)
                      (o/insert ::bob {:user/name "Bob" :user/role :viewer})
                      ;; Request exists but no policy yet
                      (o/insert ::req-1 {:access/user-id  ::bob
                                         :access/resource :reports
                                         :access/action   :read})
                      o/fire-rules)]

      ;; No decision yet
      (is (empty? (o/query-all session ::keep-access-decision)))

      ;; Add a policy at runtime
      (let [session (-> session
                        (o/insert ::policy-read-reports
                                  {:policy/resource :reports
                                   :policy/action   :read
                                   :policy/role     :viewer})
                        o/fire-rules)]

        ;; Enforcement rule created, retroactively matches Bob's request
        (is (o/contains-rule? session :enforcement/policy-read-reports))
        (is (= 1 (count (o/query-all session ::keep-access-decision))))
        (is (= 1 (count @*audit)))))))
