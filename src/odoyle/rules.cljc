(ns odoyle.rules
  (:require [clojure.spec.alpha :as s]
            [expound.alpha :as expound]
            [clojure.string :as str])
  #?(:cljs
      (:require-macros [odoyle.rules :refer [ruleset]]))
  (:refer-clojure :exclude [reset! contains?]))

;; parsing

(s/def ::id any?)
(s/def ::attr qualified-keyword?)
(s/def ::value any?)
(s/def ::what-id (s/or :binding symbol? :value ::id))
(s/def ::what-attr (s/or :binding symbol? :value ::attr))
(s/def ::what-value (s/or :binding symbol? :value ::value))
(s/def ::then (s/or :bool boolean? :func #(or (symbol? %) (fn? %))))
(s/def ::what-opts (s/keys :opt-un [::then]))
(s/def ::what-tuple (s/cat :id ::what-id, :attr ::what-attr, :value ::what-value, :opts (s/? ::what-opts)))
(s/def ::what-block (s/cat :header #{:what} :body (s/+ (s/spec ::what-tuple))))
(s/def ::when-block (s/cat :header #{:when} :body (s/+ #(not (keyword? %)))))
(s/def ::then-block (s/cat :header #{:then} :body (s/+ #(not (keyword? %)))))
(s/def ::then-finally-block (s/cat :header #{:then-finally} :body (s/+ #(not (keyword? %)))))

(s/def ::rule (s/cat
                :what-block ::what-block
                :when-block (s/? ::when-block)
                :then-block (s/? ::then-block)
                :then-finally-block (s/? ::then-finally-block)))

(s/def ::rules (s/map-of qualified-keyword? ::rule))

(s/def :odoyle.rules.dynamic-rule/what (s/+ (s/spec ::what-tuple)))
(s/def :odoyle.rules.dynamic-rule/when fn?)
(s/def :odoyle.rules.dynamic-rule/then fn?)
(s/def :odoyle.rules.dynamic-rule/then-finally fn?)
(s/def ::dynamic-rule (s/keys :opt-un [:odoyle.rules.dynamic-rule/what
                                       :odoyle.rules.dynamic-rule/when
                                       :odoyle.rules.dynamic-rule/then
                                       :odoyle.rules.dynamic-rule/then-finally]))

(s/def :odoyle.rules.wrap-rule/what fn?)
(s/def ::wrap-rule (s/keys :opt-un [:odoyle.rules.wrap-rule/what
                                    :odoyle.rules.dynamic-rule/when
                                    :odoyle.rules.dynamic-rule/then
                                    :odoyle.rules.dynamic-rule/then-finally]))

;; meta-rule attribute specs — registered so spec instrumentation doesn't throw on metadata insertion
;; NOTE: ::rule is NOT redefined here because it would conflict with the parsing spec above.
;; The ::rule attribute for meta-facts uses the same keyword but spec instrumentation on `insert`
;; must be disabled (as is typical) or the user must register their own spec for it.
(s/def ::conditions-raw any?)
(s/def ::conditions any?)
(s/def ::has-when? boolean?)
(s/def ::has-then? boolean?)
(s/def ::has-then-finally? boolean?)
(s/def ::derived-from qualified-keyword?)
(s/def ::root? boolean?)

(defn parse [spec content]
  (let [res (s/conform spec content)]
    (if (= ::s/invalid res)
      (throw (ex-info (expound/expound-str spec content) {}))
      res)))

(def ^{:dynamic true
       :doc "Provides the current value of the session from inside a :then or :then-finally block.
This is no longer necessary, because it is accessible via `session` directly."}
  *session* nil)

(def ^{:dynamic true
       :doc "Provides a map of all the matched values from inside a :then block.
This is no longer necessary, because it is accessible via `match` directly."}
  *match* nil)

;; private

(defrecord Fact [id attr value])
(defrecord Token [fact ;; Fact
                  kind ;; :insert, :retract, :update
                  old-fact ;; only used when updating
                  ])
(defrecord Binding [field ;; :id, :attr, or :value
                    sym ;; symbol
                    key ;; keyword
                    ])
(defrecord Match [vars ;; map of binding keywords -> values from facts
                  enabled ;; boolean indicating if this match should be returned in queries
                  ])
(defrecord AlphaNode [path ;; the get-in vector to reach this node from the root
                      test-field ;; :id, :attr, or :value
                      test-value ;; anything
                      children ;; vector of AlphaNode
                      successors ;; vector of JoinNode ids
                      facts ;; map of id -> (map of attr -> Fact)
                      ])
(defrecord MemoryNode [id
                       parent-id ;; JoinNode id
                       child-id ;; JoinNode id
                       leaf-node-id ;; id of the MemoryNode at the end (same as id if this is the leaf node)
                       condition ;; Condition associated with this node
                       matches ;; map of id+attrs -> Match
                       what-fn ;; fn
                       when-fn ;; fn
                       then-fn ;; fn
                       then-finally-fn ;; fn
                       trigger ;; boolean indicating that the :then block can be triggered
                       ])
(defrecord JoinNode [id
                     parent-id ;; MemoryNode id
                     child-id ;; MemoryNode id
                     alpha-node-path ;; the get-in vector to reach the parent AlphaNode from the root
                     condition ;; Condition associated with this node
                     id-key ;; the name of the id binding if we know it
                     old-id-attrs ;; a set of id+attr so the node can keep track of which facts are "new"
                     disable-fast-updates ;; boolean indicating it isn't safe to do fast updates
                     ])
(defrecord Condition [nodes ;; vector of AlphaNode
                      bindings ;; vector of Binding
                      opts ;; map of options
                      ])
(defrecord Rule [name ;; keyword
                 conditions ;; vector of Condition
                 what-fn ;; fn
                 when-fn ;; fn
                 then-fn ;; fn
                 then-finally-fn ;; fn
                 ])
(defrecord Session [alpha-node ;; AlphaNode
                    beta-nodes ;; map of int -> MemoryNode or JoinNode
                    last-id ;; last id assigned to a beta node
                    rule-name->node-id ;; map of rule name -> the id of the associated MemoryNode
                    node-id->rule-name ;; map of the id of a MemoryNode -> the associated rule name
                    id-attr-nodes ;; map of id+attr -> set of alpha node paths
                    fact-store ;; map of id -> map of attr -> {:current value :alive? boolean :history [values]}
                    attr-index ;; map of attr -> set of [id value] pairs for live facts
                    then-queue ;; set of (MemoryNode id, id+attrs) that need executed
                    then-finally-queue ;; set of MemoryNode ids that need executed
                    rule-meta-store ;; map of rule-name -> {attr -> value} for meta-rule facts
                    deferred-rules-queue ;; vector of [:add rule opts] / [:remove rule-name] for deferred execution
                    ])

(defn- add-to-condition [condition field [kind value]]
  (case kind
    :binding (update condition :bindings conj (->Binding field (list 'quote value) (keyword value)))
    :value (update condition :nodes conj (map->AlphaNode {:path nil
                                                          :test-field field
                                                          :test-value value
                                                          :children []
                                                          :successors []
                                                          :facts {}}))))

(defn- ->condition [{:keys [id attr value opts]}]
  (-> {:bindings [] :nodes [] :opts opts}
      (add-to-condition :id id)
      (add-to-condition :attr attr)
      (add-to-condition :value value)))

(defn ->rule
  "Returns a new rule. In most cases, you should use the `ruleset` macro to define rules,
  but if you want to define rules dynamically, you can use this function instead.
  See the README section \"Defining rules dynamically\".
  The one-argument arity is only meant for internal use."
  ([rule-name rule]
   (when (vector? rule)
     (throw (ex-info "The syntax for dynamic rules changed! It now should be a map, and the fns take an extra `session` arg. See the README for more." {})))
   (let [parsed-rule (parse ::dynamic-rule rule)
         parsed-rule (cond-> {:what-block {:body (:what parsed-rule)}}
                             (:when parsed-rule)
                             (assoc-in [:when-block :body] (:when parsed-rule))
                             (:then parsed-rule)
                             (assoc-in [:then-block :body] (:then parsed-rule))
                             (:then-finally parsed-rule)
                             (assoc-in [:then-finally-block :body] (:then-finally parsed-rule)))
         {:keys [rule-name conditions when-body then-body then-finally-body]} (->rule [rule-name parsed-rule])]
     (->Rule rule-name (mapv map->Condition conditions) nil when-body then-body then-finally-body)))
  ([[rule-name parsed-rule]]
   (let [{:keys [what-block when-block then-block then-finally-block]} parsed-rule
         conditions (mapv ->condition (:body what-block))
         when-body (:body when-block)
         then-body (:body then-block)
         then-finally-body (:body then-finally-block)
         syms (->> conditions
                   (mapcat :bindings)
                   (map :sym)
                   (map last) ;; must do this because we quoted it above
                   (filter simple-symbol?) ;; exclude qualified bindings from destructuring
                   set
                   vec)]
     {:rule-name rule-name
      :fn-name (-> (str (namespace rule-name) "-" (name rule-name))
                   (str/replace "." "-")
                   symbol)
      :conditions conditions
      :arg {:keys syms :as 'match}
      :when-body (cond
                   (fn? when-body) when-body
                   (> (count when-body) 1) (cons 'and when-body)
                   :else (first when-body))
      :then-body then-body
      :then-finally-body then-finally-body})))

(defn- add-alpha-node [node new-nodes *alpha-node-path]
  (let [[new-node & other-nodes] new-nodes]
    (if new-node
      (if-let [i (->> (:children node)
                      (map-indexed vector)
                      (some (fn [[i child]]
                              (when (= (select-keys child [:test-field :test-value])
                                       (select-keys new-node [:test-field :test-value]))
                                i))))]
        (do
          (vswap! *alpha-node-path conj :children i)
          (update node :children update i add-alpha-node other-nodes *alpha-node-path))
        (let [path (vswap! *alpha-node-path conj :children (-> node :children count))
              new-node (assoc new-node :path path)]
          (update node :children conj (add-alpha-node new-node other-nodes *alpha-node-path))))
      node)))

(defn- is-ancestor [session node-id1 node-id2]
  (loop [node-id node-id2]
    (if-let [parent-id (:parent-id (get-in session [:beta-nodes node-id]))]
      (if (= node-id1 parent-id)
        1
        (recur parent-id))
      -1)))

(defn- add-condition [session condition]
  (let [*alpha-node-path (volatile! [:alpha-node])
        session (update session :alpha-node add-alpha-node (:nodes condition) *alpha-node-path)
        alpha-node-path @*alpha-node-path
        *last-id (volatile! (:last-id session))
        join-node-id (vswap! *last-id inc)
        mem-node-id (vswap! *last-id inc)
        parent-mem-node-id (-> session :mem-node-ids last)
        mem-node (map->MemoryNode {:id mem-node-id
                                   :parent-id join-node-id
                                   :child-id nil
                                   :leaf-node-id nil
                                   :condition condition
                                   :matches {}
                                   :trigger false})
        join-node (map->JoinNode {:id join-node-id
                                  :parent-id parent-mem-node-id
                                  :child-id mem-node-id
                                  :alpha-node-path alpha-node-path
                                  :condition condition
                                  :id-key nil
                                  :old-id-attrs #{}
                                  :disable-fast-updates false})
        session (-> session
                    (assoc-in [:beta-nodes join-node-id] join-node)
                    (assoc-in [:beta-nodes mem-node-id] mem-node))
        successor-ids (conj (:successors (get-in session alpha-node-path))
                            join-node-id)
        ;; successors must be sorted by ancestry (descendents first) to avoid duplicate rule firings
        successor-ids (vec (sort (partial is-ancestor session) successor-ids))]
    (-> session
        (update-in alpha-node-path assoc :successors successor-ids)
        (cond-> parent-mem-node-id
                (assoc-in [:beta-nodes parent-mem-node-id :child-id] join-node-id))
        (assoc :last-id @*last-id)
        ;; these are only being added temporarily
        ;; they will be removed later
        (update :mem-node-ids (fn [node-ids]
                                (if node-ids
                                  (conj node-ids mem-node-id)
                                  [mem-node-id])))
        (update :join-node-ids (fn [node-ids]
                                 (if node-ids
                                   (conj node-ids join-node-id)
                                   [join-node-id])))
        (update :bindings (fn [bindings]
                            (reduce
                              (fn [bindings k]
                                (if (clojure.core/contains? (:all bindings) k)
                                  (update bindings :joins conj k)
                                  (update bindings :all conj k)))
                              (or bindings
                                  {:all #{} :joins #{}})
                              (->> condition :bindings (map :key))))))))

(defn- get-vars-from-fact [vars condition fact]
  (reduce
    (fn [m cond-var]
      (let [var-key (:key cond-var)]
        (case (:field cond-var)
          :id
          (if (and (clojure.core/contains? m var-key)
                   (not= (get m var-key) (:id fact)))
            (reduced nil)
            (assoc m var-key (:id fact)))
          :attr
          (if (and (clojure.core/contains? m var-key)
                   (not= (get m var-key) (:attr fact)))
            (reduced nil)
            (assoc m var-key (:attr fact)))
          :value
          (if (and (clojure.core/contains? m var-key)
                   (not= (get m var-key) (:value fact)))
            (reduced nil)
            (assoc m var-key (:value fact))))))
    vars
    (:bindings condition)))

(def ^:private get-id-attr (juxt :id :attr))

(declare left-activate-memory-node)
(declare get-alpha-nodes-for-fact)
(declare upsert-fact)
(declare add-rule)
(declare remove-rule)
(declare remove-rule-safe)
(declare insert-meta-facts-into-rete)
(declare meta-attr?)

(defn- left-activate-join-node
  ([session node-id id+attrs vars token]
   (let [join-node (get-in session [:beta-nodes node-id])
         alpha-node (get-in session (:alpha-node-path join-node))]
     ;; SHORTCUT: if we know the id, only loop over alpha facts with that id
     (if-let [id (some->> join-node :id-key (get vars))]
       (reduce
         (fn [session alpha-fact]
           (left-activate-join-node session join-node id+attrs vars token alpha-fact))
         session
         (vals (get-in alpha-node [:facts id])))
       (reduce
         (fn [session attr->fact]
           (reduce
             (fn [session alpha-fact]
               (left-activate-join-node session join-node id+attrs vars token alpha-fact))
             session
             (vals attr->fact)))
         session
         (vals (:facts alpha-node))))))
  ([session join-node id+attrs vars token alpha-fact]
   (if-let [new-vars (get-vars-from-fact vars (:condition join-node) alpha-fact)]
     (let [id+attr (get-id-attr alpha-fact)
           id+attrs (conj id+attrs id+attr)
           new-token (->Token alpha-fact (:kind token) nil)
           new? (not (clojure.core/contains? (:old-id-attrs join-node) id+attr))]
       (left-activate-memory-node session (:child-id join-node) id+attrs new-vars new-token new?))
     session)))

(def ^:private ^:dynamic *triggered-node-ids* nil)

(defn- left-activate-memory-node [session node-id id+attrs vars {:keys [kind] :as token} new?]
  (let [node-path [:beta-nodes node-id]
        node (get-in session node-path)
        ;; if this insert/update fact is new
        ;; and the condition doesn't have {:then false}
        ;; let the leaf node trigger
        session (if (and new?
                         (#{:insert :update} kind)
                         (if-let [what-fn (:what-fn node)]
                           ;; if a what fn was supplied via `wrap-rule`,
                           ;; run it so this fact insertion can be intercepted
                           (what-fn (if-let [[then-type then] (-> node :condition :opts :then)]
                                      (case then-type
                                        :bool (fn [session new-fact old-fact]
                                                then)
                                        :func (if-let [old-fact (:old-fact token)]
                                                (fn [session new-fact old-fact]
                                                  (then (:value new-fact) (:value old-fact)))
                                                (fn [session new-fact old-fact]
                                                  true)))
                                      (fn [session new-fact old-fact]
                                        true))
                                    session
                                    (:fact token)
                                    (:old-fact token))
                           ;; otherwise, just check the :then option to determine if this fact
                           ;; can trigger the rule
                           (if-let [[then-type then] (-> node :condition :opts :then)]
                             (case then-type
                               :bool then
                               :func (if-let [old-fact (:old-fact token)]
                                       (then (-> token :fact :value) (:value old-fact))
                                       true))
                             true)))
                  (do
                    (when *triggered-node-ids* ;; this is only used to improve errors. see `fire-rules`
                      (vswap! *triggered-node-ids* conj (:leaf-node-id node)))
                    (assoc-in session [:beta-nodes (:leaf-node-id node) :trigger] true))
                  session)
        node (get-in session node-path) ;; get node again since trigger may have updated
        leaf-node? (= (:id node) (:leaf-node-id node))
        ;; whether the matches in this node should
        ;; return in query results
        enabled? (boolean
                   (or (not leaf-node?)
                       (nil? (:when-fn node))
                       (binding [*session* session
                                 *match* vars]
                         ((:when-fn node) session vars))))
        ;; the id+attr of this token is the last one in the vector
        id+attr (peek id+attrs)
        ;; update session
        session (case kind
                  (:insert :update)
                  (as-> session $
                        (update-in $ node-path assoc-in [:matches id+attrs]
                                   (->Match vars enabled?))
                        (if (and leaf-node? (:trigger node))
                          (cond-> $
                                  (:then-fn node)
                                  (update :then-queue conj [node-id id+attrs])
                                  (:then-finally-fn node)
                                  (update :then-finally-queue conj node-id))
                          $)
                        (update-in $ [:beta-nodes (:parent-id node) :old-id-attrs]
                                   conj id+attr))
                  :retract
                  (as-> session $
                        (update-in $ node-path update :matches dissoc id+attrs)
                        (if (and leaf-node? (:then-finally-fn node))
                          (update $ :then-finally-queue conj node-id)
                          $)
                        (update-in $ [:beta-nodes (:parent-id node) :old-id-attrs]
                                   disj id+attr)))]
    (if-let [join-node-id (:child-id node)]
      (left-activate-join-node session join-node-id id+attrs vars token)
      session)))

(defn- right-activate-join-node [session node-id id+attr {:keys [fact] :as token}]
  (let [{:keys [condition child-id id-key] :as node} (get-in session [:beta-nodes node-id])]
    (if-let [parent-id (:parent-id node)]
      (reduce-kv
        (fn [session id+attrs {existing-vars :vars}]
          ;; SHORTCUT: if we know the id, compare it with the token right away
          (if (some->> id-key (get existing-vars) (not= (:id fact)))
            session
            (if-let [vars (get-vars-from-fact existing-vars condition fact)]
              (left-activate-memory-node session child-id (conj id+attrs id+attr) vars token true)
              session)))
        session
        (get-in session [:beta-nodes parent-id :matches]))
      ;; root node
      (if-let [vars (get-vars-from-fact {} condition fact)]
        (left-activate-memory-node session child-id [id+attr] vars token true)
        session))))

(defn- right-activate-alpha-node [session node-path {:keys [fact kind old-fact] :as token}]
  (let [[id attr :as id+attr] (get-id-attr fact)]
    (as-> session $
          (case kind
            :insert
            (-> $
                (update-in node-path assoc-in [:facts id attr] fact)
                (update-in [:id-attr-nodes id+attr]
                           (fn [node-paths]
                             (let [node-paths (or node-paths #{})]
                               (assert (not (clojure.core/contains? node-paths node-path)))
                               (conj node-paths node-path)))))
            :retract
            (-> $
                (update-in node-path update-in [:facts id] dissoc attr)
                (update :id-attr-nodes
                        (fn [nodes]
                          (let [node-paths (get nodes id+attr)
                                _ (assert (clojure.core/contains? node-paths node-path))
                                node-paths (disj node-paths node-path)]
                            (if (seq node-paths)
                              (assoc nodes id+attr node-paths)
                              (dissoc nodes id+attr))))))
            :update
            (-> $
                (update-in node-path update-in [:facts id attr]
                           (fn [existing-old-fact]
                             (assert (= old-fact existing-old-fact))
                             fact))))
          (reduce
            (fn [session child-id]
              (if (and (= :update kind)
                       (get-in session [:beta-nodes child-id :disable-fast-updates]))
                (-> session
                    (right-activate-join-node child-id id+attr (->Token old-fact :retract nil))
                    (right-activate-join-node child-id id+attr (->Token fact :insert old-fact)))
                (right-activate-join-node session child-id id+attr token)))
            $
            (:successors (get-in session node-path))))))

(defn- get-alpha-nodes-for-fact [session alpha-node id attr value root?]
  (if root?
    (reduce
      (fn [nodes child]
        (into nodes (get-alpha-nodes-for-fact session child id attr value false)))
      ;; if the root node has successors, that means
      ;; at least one condition had binding symbols
      ;; in all three columns. in that case, add the
      ;; root node to the nodes we are returning,
      ;; because all incoming facts must go through it.
      (if (seq (:successors alpha-node))
        #{(:path alpha-node)}
        #{})
      (:children alpha-node))
    (let [test-value (case (:test-field alpha-node)
                       :id id
                       :attr attr
                       :value value)]
      (when (= test-value (:test-value alpha-node))
        (reduce
          (fn [nodes child]
            (into nodes (get-alpha-nodes-for-fact session child id attr value false)))
          #{(:path alpha-node)}
          (:children alpha-node))))))

(defn- upsert-fact [session id attr value node-paths]
  (let [id+attr [id attr]
        fact (->Fact id attr value)]
    (if-let [existing-node-paths (get-in session [:id-attr-nodes id+attr])]
      (as-> session $
            ;; retract any facts from nodes that the new fact wasn't inserted in
            (reduce
              (fn [session node-path]
                (if (not (clojure.core/contains? node-paths node-path))
                  (let [node (get-in session node-path)
                        old-fact (get-in node [:facts id attr])]
                    (assert old-fact)
                    (right-activate-alpha-node session node-path (->Token old-fact :retract nil)))
                  session))
              $
              existing-node-paths)
            ;; update or insert facts, depending on whether the node already exists
            (reduce
              (fn [session node-path]
                (if (clojure.core/contains? existing-node-paths node-path)
                  (let [node (get-in session node-path)
                        old-fact (get-in node [:facts id attr])]
                    (assert old-fact)
                    (right-activate-alpha-node session node-path (->Token fact :update old-fact)))
                  (right-activate-alpha-node session node-path (->Token fact :insert nil))))
              $
              node-paths))
      (reduce
        (fn [session node-path]
          (right-activate-alpha-node session node-path (->Token fact :insert nil)))
        session
        node-paths))))

(defn- throw-recursion-limit [session limit executed-nodes]
  (let [;; make a hierarchical map of rule executions
        trigger-map (reduce
                      (fn [m node-id->triggered-node-ids]
                        (reduce-kv
                          (fn [m2 node-id triggered-node-ids]
                            (assoc m2 ((:node-id->rule-name session) node-id)
                                   (reduce
                                     (fn [m3 triggered-node-id]
                                       (let [rule-name ((:node-id->rule-name session) triggered-node-id)]
                                         (assoc m3 rule-name (get m rule-name))))
                                     {}
                                     triggered-node-ids)))
                          {}
                          node-id->triggered-node-ids))
                      {}
                      (reverse executed-nodes))
        ;; find all rules that execute themselves (directly or indirectly)
        find-cycles (fn find-cycles [cycles [k v] cyc]
                      (if (clojure.core/contains? (set cyc) k)
                        (conj cycles (vec (drop-while #(not= % k) (conj cyc k))))
                        (reduce
                          (fn [cycles pair]
                            (find-cycles cycles pair (conj cyc k)))
                          cycles
                          v)))
        cycles (reduce
                 (fn [cycles pair]
                   (find-cycles cycles pair []))
                 #{}
                 trigger-map)]
    (throw (ex-info (str "Recursion limit hit." \newline
                         "This may be an infinite loop." \newline
                         "The current recursion limit is " limit " (set by the :recursion-limit option of fire-rules)." \newline
                         (reduce
                           (fn [s cyc]
                             (str s "Cycle detected! "
                                  (if (= 2 (count cyc))
                                    (str (first cyc) " is triggering itself.")
                                    (str/join " -> " cyc))
                                  \newline))
                           \newline
                           cycles)
                         \newline "Try using {:then false} to prevent triggering rules in an infinite loop.")
                    {}))))

(defn- remove-from-attr-index [attr-index attr id val]
  (let [new-pairs (disj (get attr-index attr #{}) [id val])]
    (if (seq new-pairs)
      (assoc attr-index attr new-pairs)
      (dissoc attr-index attr))))

(defn- store-insert
  [fact-store attr-index id attr val]
  (let [entry (get-in fact-store [id attr])
        live-entry? (and entry (:alive? entry))
        old-val (:current entry)
        new-history (if live-entry?
                      (conj (or (:history entry) []) old-val)
                      (or (:history entry) []))
        fact-store (assoc-in fact-store [id attr]
                             {:current val
                              :alive? true
                              :history new-history})
        attr-index (if live-entry?
                     (remove-from-attr-index attr-index attr id old-val)
                     attr-index)
        attr-index (update attr-index attr (fnil conj #{}) [id val])]
    [fact-store attr-index]))

(defn- store-retract
  [fact-store attr-index id attr]
  (let [entry (get-in fact-store [id attr])]
    (if (and entry (:alive? entry))
      (let [old-val (:current entry)
            fact-store (assoc-in fact-store [id attr]
                                 {:current nil
                                  :alive? false
                                  :history (conj (or (:history entry) []) old-val)})
            attr-index (remove-from-attr-index attr-index attr id old-val)]
        [fact-store attr-index])
      [fact-store attr-index])))

(defn- store-purge
  [fact-store attr-index id attr]
  (let [entry (get-in fact-store [id attr])]
    (if entry
      (let [fact-store (update fact-store id dissoc attr)
            fact-store (if (seq (get fact-store id))
                         fact-store
                         (dissoc fact-store id))
            attr-index (if (:alive? entry)
                         (remove-from-attr-index attr-index attr id (:current entry))
                         attr-index)]
        [fact-store attr-index])
      [fact-store attr-index])))

(defn- store-purge-attrs
  [fact-store attr-index attr-set]
  (reduce
    (fn [[fact-store attr-index] attr]
      [(reduce-kv
         (fn [updated-store id attr->entry]
           (if (clojure.core/contains? attr->entry attr)
             (let [next-entry (dissoc attr->entry attr)]
               (if (seq next-entry)
                 (assoc updated-store id next-entry)
                 updated-store))
             (assoc updated-store id attr->entry)))
         {}
         fact-store)
       (dissoc attr-index attr)])
    [fact-store attr-index]
    attr-set))

(defn- store-query
  ([fact-store]
   (store-query fact-store {}))
  ([fact-store {:keys [include-meta? include-history?]}]
   (reduce-kv
     (fn [result id attr->entry]
       (let [filtered-attrs (reduce-kv
                              (fn [attrs attr entry]
                                (if (and (or include-meta? (not (meta-attr? attr)))
                                         (or include-history? (:alive? entry)))
                                  (assoc attrs attr (if include-history?
                                                      entry
                                                      (:current entry)))
                                  attrs))
                              {}
                              attr->entry)]
         (if (seq filtered-attrs)
           (assoc result id filtered-attrs)
           result)))
     {}
     fact-store)))

(defn- store-lookup-attr
  [attr-index attr]
  (get attr-index attr #{}))

(def ^:private ^:dynamic *mutable-session* nil)
(def ^:private ^:dynamic *firing-rule-name* nil)
(def ^:private ^:dynamic *recur-countdown* nil)
(def ^:private ^:dynamic *executed-nodes* nil)

(defn- initialize-rule-against-session
  "Retroactively initializes a newly added rule against all existing facts in the session.
  Walks the rule's join nodes and replays matching facts from their alpha nodes.
  For each join node, finds candidate facts from the session (even if they reside in
  ancestor alpha nodes) and replays them through the RETE network to populate the new
  rule's beta nodes."
  [session rule-name]
  (let [leaf-node-id (get-in session [:rule-name->node-id rule-name])]
    (if leaf-node-id
      (let [;; collect all join node ids for this rule by walking from leaf to root
            join-node-ids
            (loop [node-id leaf-node-id
                   ids []]
              (let [node (get-in session [:beta-nodes node-id])]
                (if (instance? JoinNode node)
                  (recur (:parent-id node) (conj ids node-id))
                  (if-let [parent-id (:parent-id node)]
                    (recur parent-id ids)
                    ids))))]
        ;; replay in root-to-leaf order so parent memory nodes have matches
        ;; before child join nodes try to join against them
        (reduce
          (fn [session join-node-id]
            (let [join-node (get-in session [:beta-nodes join-node-id])
                  alpha-node-path (:alpha-node-path join-node)
                  alpha-node (get-in session alpha-node-path)
                  ;; if the alpha node has facts, use them directly.
                  ;; otherwise, find matching facts from ancestor alpha nodes.
                  facts (if (seq (:facts alpha-node))
                          (mapcat vals (vals (:facts alpha-node)))
                          ;; walk all facts in session and check which ones reach this alpha path
                          (let [condition (:condition join-node)
                                ;; collect the test criteria from the alpha node and its ancestors
                                tests (loop [path alpha-node-path
                                             ts []]
                                        (let [node (get-in session path)]
                                          (if (:test-field node)
                                            (recur (subvec path 0 (- (count path) 2))
                                                   (conj ts [(:test-field node) (:test-value node)]))
                                            ts)))]
                            ;; scan all session facts and filter by alpha node criteria
                            (for [[[id attr] node-paths] (:id-attr-nodes session)
                                  :let [any-path (first node-paths)
                                        fact-node (get-in session any-path)
                                        fact (get-in fact-node [:facts id attr])]
                                  :when fact
                                  :when (every? (fn [[field test-val]]
                                                  (case field
                                                    :id (= (:id fact) test-val)
                                                    :attr (= (:attr fact) test-val)
                                                    :value (= (:value fact) test-val)))
                                                tests)]
                              fact)))]
              (reduce
                (fn [session fact]
                  (right-activate-join-node
                    session join-node-id
                    (get-id-attr fact)
                    (->Token fact :insert nil)))
                session
                facts)))
          session
          (rseq join-node-ids)))
      session)))

;; public

(s/def ::recursion-limit (s/nilable nat-int?))

(s/fdef fire-rules
  :args (s/cat :session ::session
               :opts (s/? (s/keys :opt-un [::recursion-limit]))))

(defn fire-rules
  "Fires :then and :then-finally blocks for any rules whose matches have been updated.
  The opts map may contain:
  
  :recursion-limit  -  Throws an error if rules recursively trigger this many times.
                       The default is 16. Pass nil to disable the limit entirely."
  ([session]
   (fire-rules session {}))
  ([session opts]
   (let [then-queue (:then-queue session)
         then-finally-queue (:then-finally-queue session)]
     (if (and (or (seq then-queue) (seq then-finally-queue))
              ;; don't fire while inside a rule
              (nil? *session*))
       (let [;; make an fn that will save which rules are triggered by the rules we're about to fire.
             ;; this will be useful for making a nice error message if an infinite loop happens.
             *node-id->triggered-node-ids (volatile! {})
             execute-fn (fn [f node-id]
                          (binding [*triggered-node-ids* (volatile! #{})]
                            (f)
                            (vswap! *node-id->triggered-node-ids update node-id #(into (or % #{}) @*triggered-node-ids*))))
             ;; reset state
             session (assoc session :then-queue #{} :then-finally-queue #{})
             session (reduce
                       (fn [session node-id]
                         (update-in session [:beta-nodes node-id] assoc :trigger false))
                       session
                       (into then-finally-queue (map first then-queue)))
             ;; keep a copy of the beta nodes before executing the :then functions.
             ;; if we pull the beta nodes from inside the reduce fn below,
             ;; it'll produce non-deterministic results because `matches`
             ;; could be modified by the reduce itself. see test: non-deterministic-behavior
             beta-nodes (:beta-nodes session)
             ;; execute :then functions
             session (reduce
                       (fn [session [node-id id+attrs]]
                         (let [{:keys [matches then-fn]} (get beta-nodes node-id)]
                           (or (when-let [{:keys [vars enabled]} (get matches id+attrs)]
                                 (when enabled
                                   (binding [*session* session
                                             *mutable-session* (volatile! session)
                                             *match* vars
                                             *firing-rule-name* (get-in session [:node-id->rule-name node-id])]
                                     (execute-fn #(then-fn session vars) node-id)
                                     @*mutable-session*)))
                               session)))
                       session
                       then-queue)
             ;; execute :then-finally functions
             session (reduce
                       (fn [session node-id]
                         (let [{:keys [then-finally-fn]} (get beta-nodes node-id)]
                           (binding [*session* session
                                     *mutable-session* (volatile! session)
                                     *firing-rule-name* (get-in session [:node-id->rule-name node-id])]
                             (execute-fn #(then-finally-fn session) node-id)
                             @*mutable-session*)))
                       session
                       then-finally-queue)]
         ;; recur because there may be new blocks to execute
         (if-let [limit (get opts :recursion-limit 16)]
           (if (= 0 *recur-countdown*)
             (throw-recursion-limit session limit *executed-nodes*)
             (binding [*recur-countdown* (if (nil? *recur-countdown*)
                                           limit
                                           (dec *recur-countdown*))
                       *executed-nodes* (conj (or *executed-nodes* [])
                                              @*node-id->triggered-node-ids)]
               (fire-rules session opts)))
           (fire-rules session opts)))
       ;; then/then-finally queues are empty — drain deferred-rules-queue if present
       (let [deferred (:deferred-rules-queue session)]
         (if (and (seq deferred)
                  ;; only drain at top level, not while inside a rule
                  (nil? *session*))
           (let [session (assoc session :deferred-rules-queue [])
                 session (reduce
                           (fn [session [op & args]]
                             (case op
                               :add (let [[rule opts] args
                                          rule-name (:name rule)]
                                      (if (get-in session [:rule-name->node-id rule-name])
                                        session ;; rule already exists, skip (idempotent)
                                        (let [session (add-rule session rule)
                                              ;; record provenance in rule-meta-store
                                              session (if-let [source (:source-rule opts)]
                                                        (-> session
                                                            (assoc-in [:rule-meta-store rule-name ::derived-from] source)
                                                            (insert-meta-facts-into-rete rule-name {::derived-from source}))
                                                        session)
                                              session (if (:root? opts)
                                                        (-> session
                                                            (assoc-in [:rule-meta-store rule-name ::root?] true)
                                                            (insert-meta-facts-into-rete rule-name {::root? true}))
                                                        session)]
                                          (initialize-rule-against-session session rule-name))))
                               :remove (let [[rule-name] args]
                                         (remove-rule-safe session rule-name))))
                           session
                           deferred)]
             ;; fire-rules again so newly added rules can fire
             (fire-rules session opts))
           session))))))

(defn- binding->raw
  "Reverses the (list 'quote sym) storage of bindings back to just the symbol."
  [{:keys [field sym]}]
  ;; sym is stored as (list 'quote actual-sym), so (second sym) gets the actual symbol
  (second sym))

(defn- condition->raw-tuple
  "Reconstructs the original [id attr value] vector (with optional opts map) from a Condition."
  [{:keys [nodes bindings opts]}]
  (let [;; build a map from field -> value (either a binding symbol or a literal test-value)
        field-map (reduce (fn [m {:keys [field] :as binding}]
                            (assoc m field (binding->raw binding)))
                          {}
                          bindings)
        field-map (reduce (fn [m {:keys [test-field test-value]}]
                            (assoc m test-field test-value))
                          field-map
                          nodes)
        tuple [(:id field-map) (:attr field-map) (:value field-map)]]
    (if (seq opts)
      (conj tuple opts)
      tuple)))

(defn- condition->structured-map
  "Transforms a Condition record into a queryable map per the SPEC."
  [{:keys [nodes bindings opts]}]
  (let [;; build maps for bindings and literal values
        field-map (reduce (fn [m {:keys [field] :as binding}]
                            (assoc m field {:kind :binding :sym (binding->raw binding)}))
                          {}
                          bindings)
        field-map (reduce (fn [m {:keys [test-field test-value]}]
                            (assoc m test-field {:kind :value :value test-value}))
                          field-map
                          nodes)]
    {:id    (:id field-map)
     :attr  (:attr field-map)
     :value (:value field-map)
     :opts  (or opts {})}))

(defn- rule->meta-facts
  "Returns a map of {attr -> value} metadata facts for a rule."
  [rule]
  {::rule true
   ::conditions-raw (mapv condition->raw-tuple (:conditions rule))
   ::conditions (mapv condition->structured-map (:conditions rule))
   ::has-when? (some? (:when-fn rule))
   ::has-then? (some? (:then-fn rule))
   ::has-then-finally? (some? (:then-finally-fn rule))})

(defn- meta-attr?
  "Returns true if attr is a meta-attribute (in the ::o/ namespace)."
  [attr]
  (and (qualified-keyword? attr)
       (= (namespace attr) (namespace ::rule))))

(defn- rule-watches-meta?
  "Returns true if a rule has any ::o/ attributes in its :what block."
  [rule]
  (some (fn [condition]
          (some (fn [{:keys [test-field test-value]}]
                  (and (= :attr test-field) (meta-attr? test-value)))
                (:nodes condition)))
        (:conditions rule)))

(defn- insert-meta-facts-into-rete
  "Inserts metadata facts for a rule into the RETE network (for meta-rules to react to).
  Uses the internal insert path directly, bypassing spec instrumentation on `insert`
  (which would fail because ::o/rule conflicts with the parsing spec)."
  [session rule-name meta-facts]
  (reduce-kv
    (fn [session attr value]
      (->> (get-alpha-nodes-for-fact session (:alpha-node session) rule-name attr value true)
           (upsert-fact session rule-name attr value)))
    session
    meta-facts))

(s/fdef add-rule
  :args (s/cat :session ::session
               :rule #(instance? Rule %)))

(defn add-rule
  "Adds a rule to the given session."
  [session rule]
  (when (get-in session [:rule-name->node-id (:name rule)])
    (throw (ex-info (str (:name rule) " already exists in session") {})))
  (let [conditions (:conditions rule)
        session (reduce add-condition session conditions)
        leaf-node-id (-> session :mem-node-ids last)
        ;; the bindings (symbols) from the :what block
        bindings (:bindings session)
        ;; update all memory nodes with
        ;; the id of their leaf node
        session (reduce (fn [session mem-node-id]
                          (update-in session [:beta-nodes mem-node-id]
                                     (fn [mem-node]
                                       (assoc mem-node :leaf-node-id leaf-node-id, :what-fn (:what-fn rule)))))
                        session
                        (:mem-node-ids session))
        ;; update all join nodes with:
        ;; 1. the name of the id binding, if it exists
        ;; 2. whether to disable fast updates
        session (reduce (fn [session join-node-id]
                          (update-in session [:beta-nodes join-node-id]
                                     (fn [join-node]
                                       (let [joined-key (some (fn [{:keys [field key]}]
                                                                (when (= :value field)
                                                                  key))
                                                              (-> join-node :condition :bindings))
                                             disable-fast-updates (clojure.core/contains?
                                                                    (:joins bindings)
                                                                    joined-key)]
                                         (when (and disable-fast-updates
                                                    (-> (get-in session [:beta-nodes (:child-id join-node)])
                                                        :condition :opts :then first (= :func)))
                                           (throw (ex-info (str "In " (:name rule) " you are making a join with the symbol `" (symbol joined-key) "`, "
                                                                "and passing a custom function in the {:then ...} option. This is not allowed due to "
                                                                "how the implementation works. Luckily, it's easy to fix! Get rid of this join in your :what "
                                                                "block by giving the symbol a different name, such as `" (symbol (str (name joined-key) 2)) "`, "
                                                                "and then enforce the join in your :when block like this: " (list '= (symbol joined-key)
                                                                                                                                  (symbol (str (name joined-key) 2))))
                                                           {})))
                                         (assoc join-node
                                                :id-key (some (fn [{:keys [field key]}]
                                                                (when (and (= :id field)
                                                                           (clojure.core/contains? (:joins bindings) key))
                                                                  key))
                                                              (-> join-node :condition :bindings))
                                                ;; disable fast updates for facts whose value is part of a join
                                                :disable-fast-updates disable-fast-updates)))))
                        session
                        (:join-node-ids session))]
    (let [session (-> session
                      (assoc-in [:beta-nodes leaf-node-id :when-fn] (:when-fn rule))
                      (assoc-in [:beta-nodes leaf-node-id :then-fn] (:then-fn rule))
                      (assoc-in [:beta-nodes leaf-node-id :then-finally-fn] (:then-finally-fn rule))
                      (assoc-in [:rule-name->node-id (:name rule)] leaf-node-id)
                      (assoc-in [:node-id->rule-name leaf-node-id] (:name rule))
                      ;; assoc'ed by add-condition
                      (dissoc :mem-node-ids :join-node-ids :bindings))
          ;; populate rule-meta-store unconditionally
          meta-facts (rule->meta-facts rule)
          session (assoc-in session [:rule-meta-store (:name rule)] meta-facts)
          ;; if this new rule is a meta-rule, replay all existing rule-meta-store entries through it
          ;; (this handles: meta-rule added AFTER domain rules)
          session (if (rule-watches-meta? rule)
                    (reduce-kv
                      (fn [session existing-rule-name existing-meta]
                        (insert-meta-facts-into-rete session existing-rule-name existing-meta))
                      session
                      (:rule-meta-store session))
                    session)
          ;; insert this rule's metadata into RETE for any already-present meta-rules to react
          ;; (this handles: domain rule added AFTER meta-rules)
          ;; insert silently discards facts that match no rule, so this is safe even without meta-rules
          session (insert-meta-facts-into-rete session (:name rule) meta-facts)]
      session)))

(s/fdef remove-rule
  :args (s/cat :session ::session
               :rule-name qualified-keyword?))

(defn- retract-meta-facts-from-rete
  "Retracts metadata facts for a rule from the RETE network."
  [session rule-name meta-facts]
  (reduce-kv
    (fn [session attr _value]
      (let [id+attr [rule-name attr]]
        (if (get-in session [:id-attr-nodes id+attr])
          ;; fact is in the RETE — retract it
          (let [node-paths (get-in session [:id-attr-nodes id+attr])]
            (reduce
              (fn [session node-path]
                (let [node (get-in session node-path)
                      fact (get-in node [:facts rule-name attr])]
                  (if fact
                    (right-activate-alpha-node session node-path (->Token fact :retract nil))
                    session)))
              session
              node-paths))
          session)))
    session
    meta-facts))

(defn- cascade-remove-derived-rules
  "Recursively removes rules derived from the given rule, unless they are root rules."
  [session rule-name]
  (let [derived-rules (reduce-kv
                        (fn [acc rname meta]
                          (if (= (get meta ::derived-from) rule-name)
                            (conj acc rname)
                            acc))
                        []
                        (:rule-meta-store session))]
    (reduce
      (fn [session derived-name]
        (if (get-in session [:rule-meta-store derived-name ::root?])
          session ;; root rules survive
          (remove-rule-safe session derived-name)))
      session
      derived-rules)))

(defn remove-rule
  "Removes a rule from the given session."
  [session rule-name]
  (if-let [node-id (get-in session [:rule-name->node-id rule-name])]
    (let [meta-facts (get-in session [:rule-meta-store rule-name])
          session (-> (loop [session session
                             node-id node-id]
                        (if node-id
                          (let [node (get-in session [:beta-nodes node-id])
                                session (update session :beta-nodes dissoc node-id)]
                            (if (instance? JoinNode node)
                              (-> session
                                  (update-in (:alpha-node-path node)
                                             (fn [alpha-node]
                                               (update alpha-node :successors (fn [successors]
                                                                                (vec (remove #(= % node-id) successors))))))
                                  (recur (:parent-id node)))
                              (recur session (:parent-id node))))
                          session))
                      (update :rule-name->node-id dissoc rule-name)
                      (update :node-id->rule-name dissoc node-id)
                      (update :then-queue (fn [then-queue]
                                            (reduce
                                              (fn [s [id _ :as tuple]]
                                                (if (= id node-id)
                                                  (disj s tuple)
                                                  s))
                                              then-queue
                                              then-queue)))
                      (update :then-finally-queue disj node-id))
          ;; clean up rule-meta-store
          session (update session :rule-meta-store dissoc rule-name)
          ;; retract metadata facts from RETE
          session (if meta-facts
                    (retract-meta-facts-from-rete session rule-name meta-facts)
                    session)
          ;; cascade: remove derived rules (truth maintenance)
          session (cascade-remove-derived-rules session rule-name)]
      session)
    (throw (ex-info (str rule-name " does not exist in session") {}))))

(defn- remove-rule-safe
  "Like remove-rule but returns session unchanged if the rule doesn't exist.
  Used in cascade/drain contexts where a rule may have already been removed."
  [session rule-name]
  (if (get-in session [:rule-name->node-id rule-name])
    (remove-rule session rule-name)
    session))

(defn add-rule!
  "Queues a rule addition for after the current fire-rules cycle completes.
  Must be called inside a :then or :then-finally block.
  Optional opts map supports :root? true to prevent truth-maintenance cascading."
  ([rule]
   (add-rule! rule {}))
  ([rule opts]
   (if *mutable-session*
     (let [source-rule *firing-rule-name*]
       (vswap! *mutable-session* update :deferred-rules-queue
               conj [:add rule (assoc opts :source-rule source-rule)]))
     (throw (ex-info "This function must be called in a :then or :then-finally block" {})))))

(defn remove-rule!
  "Queues a rule removal for after the current fire-rules cycle completes.
  Must be called inside a :then or :then-finally block."
  [rule-name]
  (if *mutable-session*
    (vswap! *mutable-session* update :deferred-rules-queue conj [:remove rule-name])
    (throw (ex-info "This function must be called in a :then or :then-finally block" {}))))

#?(:clj
 (defmacro ruleset
  "Returns a vector of rules after transforming the given map."
  [rules]
  (reduce
    (fn [v {:keys [rule-name fn-name conditions when-body then-body then-finally-body arg]}]
      (conj v `(->Rule ~rule-name
                       (mapv map->Condition ~conditions)
                       nil
                       ~(when (some? when-body) ;; need some? because it could be `false`
                          `(fn ~fn-name [~'session ~arg] ~when-body))
                       ~(when then-body
                          `(fn ~fn-name [~'session ~arg] ~@then-body))
                       ~(when then-finally-body
                          `(fn ~fn-name [~'session] ~@then-finally-body)))))
    []
    (mapv ->rule (parse ::rules rules)))))

(defn ->session
  "Returns a new session."
  []
  (map->Session
    {:alpha-node (map->AlphaNode {:path [:alpha-node]
                                  :test-field nil
                                  :test-value nil
                                  :children []
                                  :successors []
                                  :facts {}})
     :beta-nodes {}
     :last-id -1
     :rule-name->node-id {}
     :node-id->rule-name {}
     :id-attr-nodes {}
     :fact-store {}
     :attr-index {}
     :then-queue #{}
     :then-finally-queue #{}
     :rule-meta-store {}
     :deferred-rules-queue []}))

(s/def ::session #(instance? Session %))

(s/def ::insert-args
  (s/or
    :single-combo (s/cat :session ::session
                         :fact (s/tuple ::id ::attr ::value))
    :batch (s/cat :session ::session
                  :id ::id
                  :attr->value (s/map-of ::attr ::value))
    :single (s/cat :session ::session
                   :id ::id
                   :attr ::attr
                   :value ::value)))

(defn- check-insert-spec
  ([[attr value]]
   (check-insert-spec attr value))
  ([attr value]
   (if-let [spec (s/get-spec attr)]
     (when (= ::s/invalid (s/conform spec value))
       (throw (ex-info (str "Error when checking attribute " attr "\n\n"
                            (expound/expound-str spec value))
                       {})))
     (throw (ex-info (str "Couldn't find spec with name " attr \newline
                          "If you don't want o'doyle to require specs for attributes, call" \newline
                          "(clojure.spec.test.alpha/unstrument 'odoyle.rules/insert)" \newline)
                     {})))))

(def ^:private insert-conformer
  (s/conformer
    (fn [[kind args :as parsed-args]]
      (case kind
        :single-combo (check-insert-spec (nth (:fact args) 1) (nth (:fact args) 2))
        :batch (run! check-insert-spec (:attr->value args))
        :single (check-insert-spec (:attr args) (:value args)))
      parsed-args)))

(s/fdef insert
  :args (s/and ::insert-args insert-conformer))

(defn insert
  "Inserts a fact into the session. Can optionally insert multiple facts with the same id.
  
  Note: if the given fact doesn't match at least one rule, it will be discarded."
  ([session [id attr value]]
   (insert session id attr value))
  ([session id attr->value]
   (reduce-kv (fn [session attr value]
                (insert session id attr value))
              session attr->value))
  ([session id attr value]
   (->> (get-alpha-nodes-for-fact session (:alpha-node session) id attr value true)
        (upsert-fact session id attr value))))

(s/def ::insert!-args
  (s/or
    :batch (s/cat :id ::id
                  :attr->value (s/map-of ::attr ::value))
    :single (s/cat :id ::id
                   :attr ::attr
                   :value ::value)))

(s/fdef insert!
  :args ::insert!-args)

(defn insert!
  "Equivalent to:
  
  (o/reset! (o/insert o/*session* id attr value))"
  ([id attr->value]
   (run! (fn [[attr value]]
           (insert! id attr value))
         attr->value))
  ([id attr value]
   (if *mutable-session*
     (vswap! *mutable-session* insert id attr value)
     (throw (ex-info "This function must be called in a :then or :then-finally block" {})))))

(s/fdef retract
  :args (s/cat :session ::session, :id ::id, :attr ::attr))

(defn retract
  "Retracts the fact with the given id + attr combo."
  [session id attr]
  (let [id+attr [id attr]
        node-paths (get-in session [:id-attr-nodes id+attr])]
    (when-not node-paths
      (throw (ex-info (str id+attr " not in session") {})))
    (reduce
      (fn [session node-path]
        (let [node (get-in session node-path)
              fact (get-in node [:facts id attr])]
          (right-activate-alpha-node session node-path (->Token fact :retract nil))))
      session
      node-paths)))

(s/fdef retract!
  :args (s/cat :id ::id, :attr ::attr))

(defn retract!
  "Equivalent to:
  
  (o/reset! (o/retract o/*session* id attr))"
  [id attr]
  (if *mutable-session*
    (vswap! *mutable-session* retract id attr)
    (throw (ex-info "This function must be called in a :then or :then-finally block" {}))))

(s/fdef query-all
  :args (s/cat :session ::session, :rule-name (s/? qualified-keyword?)))

(defn query-all
  "When called with just a session, returns a vector of all inserted facts.
  Otherwise, returns a vector of maps containing all the matches for the given rule.
  Meta-facts (::o/ attributes) are excluded from the no-arg form; use query-all-meta instead."
  ([session]
   (into []
         (comp
           (remove (fn [[[_id attr] _nodes]] (meta-attr? attr)))
           (map (fn [[[id attr] nodes]]
                  (-> (get-in session (first nodes))
                      (get-in [:facts id attr])
                      ((juxt :id :attr :value))))))
         (:id-attr-nodes session)))
  ([session rule-name]
   (let [rule-id (or (get-in session [:rule-name->node-id rule-name])
                     (throw (ex-info (str rule-name " not in session") {})))
         rule (get-in session [:beta-nodes rule-id])]
     (reduce-kv
       (fn [v _ {:keys [vars enabled]}]
         (if enabled
           (conj v vars)
           v))
       []
       (:matches rule)))))

(defn query-all-meta
  "Returns rule metadata from the rule-meta-store.
  With just a session, returns all rule metadata as a vector of [rule-name attr value] tuples.
  With a rule-name, returns the metadata map for that specific rule."
  ([session]
   (into []
         (mapcat (fn [[rule-name meta-facts]]
                   (map (fn [[attr value]]
                          [rule-name attr value])
                        meta-facts)))
         (:rule-meta-store session)))
  ([session rule-name]
   (get (:rule-meta-store session) rule-name)))

(s/fdef reset!
  :args (s/cat :new-session ::session))

(defn reset!
  "Mutates the session from a :then or :then-finally block."
  [new-session]
  (if *mutable-session*
    (if (= *session* @*mutable-session*)
      (vreset! *mutable-session* new-session)
      (throw (ex-info "You may only call `reset!` once in a :then or :then-finally block" {})))
    (throw (ex-info "You may only call `reset!` in a :then or :then-finally block" {}))))

(s/fdef contains?
  :args (s/cat :session ::session, :id ::id, :attr ::attr))

(defn contains?
  "Returns true if the session contains a fact with the given id and attribute."
  [session id attr]
  (clojure.core/contains? (:id-attr-nodes session) [id attr]))

(defn contains-rule?
  "Returns true if the session contains a rule with the given name."
  [session rule-name]
  (clojure.core/contains? (:rule-name->node-id session) rule-name))

(s/fdef wrap-rule
  :args (s/cat :rule #(instance? Rule %)
               :rule-fns ::wrap-rule))

(defn wrap-rule
  "Wraps the functions of a rule so they can be conveniently intercepted
  for debugging or other purposes.
  See the README section \"Debugging\"."
  [rule {what-fn :what, when-fn :when, then-fn :then, then-finally-fn :then-finally}]
  (cond-> (assoc rule :what-fn what-fn)
          (and (:when-fn rule) when-fn)
          (update :when-fn
                  (fn wrap-when [f]
                    (fn [session match]
                      (when-fn f session match))))
          (and (:then-fn rule) then-fn)
          (update :then-fn
                  (fn wrap-then [f]
                    (fn [session match]
                      (then-fn f session match))))
          (and (:then-finally-fn rule) then-finally-fn)
          (update :then-finally-fn
                  (fn wrap-then-finally [f]
                    (fn [session]
                      (then-finally-fn f session))))))
