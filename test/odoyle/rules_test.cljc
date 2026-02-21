(ns odoyle.rules-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.set]
            [odoyle.rules :as o]
            [clojure.spec.test.alpha :as st]))

(st/instrument)
(st/unstrument 'odoyle.rules/insert)

(deftest num-of-conditions-not=-num-of-facts
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::num-conds-and-facts
           [:what
            [b ::color "blue"]
            [y ::left-of z]
            [a ::color "maize"]
            [y ::right-of b]
            [x ::height h]
            :then
            (is (= a ::alice))
            (is (= b ::bob))
            (is (= y ::yair))
            (is (= z ::zach))]}))
      (o/insert ::bob ::color "blue")
      (o/insert ::yair ::left-of ::zach)
      (o/insert ::alice ::color "maize")
      (o/insert ::yair ::right-of ::bob)
      (o/insert ::xavier ::height 72)
      (o/insert ::thomas ::height 72)
      (o/insert ::george ::height 72)
      o/fire-rules
      ((fn [session]
         (is (= 3 (count (o/query-all session ::num-conds-and-facts))))
         session))))

(deftest adding-facts-out-of-order
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::out-of-order
           [:what
            [x ::right-of y]
            [y ::left-of z]
            [z ::color "red"]
            [a ::color "maize"]
            [b ::color "blue"]
            [c ::color "green"]
            [d ::color "white"]
            [s ::on "table"]
            [y ::right-of b]
            [a ::left-of d]
            :then
            (is (= a ::alice))
            (is (= b ::bob))
            (is (= y ::yair))
            (is (= z ::zach))]}))
      (o/insert ::xavier ::right-of ::yair)
      (o/insert ::yair ::left-of ::zach)
      (o/insert ::zach ::color "red")
      (o/insert ::alice ::color "maize")
      (o/insert ::bob ::color "blue")
      (o/insert ::charlie ::color "green")
      (o/insert ::seth ::on "table")
      (o/insert ::yair ::right-of ::bob)
      (o/insert ::alice ::left-of ::david)
      (o/insert ::david ::color "white")
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::out-of-order))))
         session))))

(deftest duplicate-facts
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::duplicate-facts
           [:what
            [x ::self y]
            [x ::color c]
            [y ::color c]]}))
      (o/insert ::bob ::self ::bob)
      (o/insert ::bob ::color "red")
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::duplicate-facts))))
         (is (= "red" (:c (first (o/query-all session ::duplicate-facts)))))
         session))
      (o/insert ::bob ::color "green")
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::duplicate-facts))))
         (is (= "green" (:c (first (o/query-all session ::duplicate-facts)))))
         session))))

(deftest removing-facts
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::removing-facts
           [:what
            [b ::color "blue"]
            [y ::left-of z]
            [a ::color "maize"]
            [y ::right-of b]]}))
      (o/insert ::bob ::color "blue")
      (o/insert ::yair ::left-of ::zach)
      (o/insert ::alice ::color "maize")
      (o/insert ::yair ::right-of ::bob)
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::removing-facts))))
         session))
      (o/retract ::yair ::right-of)
      ((fn [session]
         (is (= 0 (count (o/query-all session ::removing-facts))))
         session))
      (o/retract ::bob ::color)
      (o/insert ::bob ::color "blue")
      (o/insert ::yair ::right-of ::bob)
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::removing-facts))))
         session))))

(deftest updating-facts
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::updating-facts
           [:what
            [b ::color "blue"]
            [y ::left-of z]
            [a ::color "maize"]
            [y ::right-of b]]}))
      (o/insert ::bob ::color "blue")
      (o/insert ::yair ::left-of ::zach)
      (o/insert ::alice ::color "maize")
      (o/insert ::yair ::right-of ::bob)
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::updating-facts))))
         (is (= ::zach (:z (first (o/query-all session ::updating-facts)))))
         session))
      (o/insert ::yair ::left-of ::xavier)
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::updating-facts))))
         (is (= ::xavier (:z (first (o/query-all session ::updating-facts)))))
         session))))

(deftest updating-facts-in-different-alpha-nodes
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::updating-facts-diff-nodes
           [:what
            [b ::color "blue"]
            [y ::left-of ::zach]
            [a ::color "maize"]
            [y ::right-of b]]}))
      (o/insert ::bob ::color "blue")
      (o/insert ::yair ::left-of ::zach)
      (o/insert ::alice ::color "maize")
      (o/insert ::yair ::right-of ::bob)
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::updating-facts-diff-nodes))))
         session))
      (o/insert ::yair ::left-of ::xavier)
      o/fire-rules
      ((fn [session]
         (is (= 0 (count (o/query-all session ::updating-facts-diff-nodes))))
         session))))

(deftest facts-can-be-stored-in-different-alpha-nodes
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::rule1
           [:what
            [a ::left-of ::zach]]
           ::rule2
           [:what
            [a ::left-of z]]}))
      (o/insert ::alice ::left-of ::zach)
      o/fire-rules
      ((fn [session]
         (is (= ::alice (:a (first (o/query-all session ::rule1)))))
         (is (= ::zach (:z (first (o/query-all session ::rule2)))))
         session))))

(deftest complex-conditions
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::complex-cond
           [:what
            [b ::color "blue"]
            [y ::left-of z]
            [a ::color "maize"]
            [y ::right-of b]
            :when
            (not= z ::zach)]}))
      (o/insert ::bob ::color "blue")
      (o/insert ::yair ::left-of ::zach)
      (o/insert ::alice ::color "maize")
      (o/insert ::yair ::right-of ::bob)
      o/fire-rules
      ((fn [session]
         (is (= 0 (count (o/query-all session ::complex-cond))))
         session))
      (o/insert ::yair ::left-of ::charlie)
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::complex-cond))))
         session))))

(deftest out-of-order-joins-between-id-and-value
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::rule1
           [:what
            [b ::right-of ::alice]
            [y ::right-of b]
            [b ::color "blue"]]}))
      (o/insert ::bob ::right-of ::alice)
      (o/insert ::bob ::color "blue")
      (o/insert ::yair ::right-of ::bob)
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::rule1))))
         session))))

(deftest simple-conditions
  (let [*count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::simple-cond
             [:what
              [b ::color "blue"]
              :when
              false
              :then
              (swap! *count inc)]}))
        (o/insert ::bob ::color "blue")
        o/fire-rules
        ((fn [session]
           (is (= 0 @*count))
           session)))))

(deftest queries
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::get-person
           [:what
            [id ::color color]
            [id ::left-of left-of]
            [id ::height height]]}))
      (o/insert ::bob ::color "blue")
      (o/insert ::bob ::left-of ::zach)
      (o/insert ::bob ::height 72)
      (o/insert ::alice ::color "green")
      (o/insert ::alice ::left-of ::bob)
      (o/insert ::alice ::height 64)
      (o/insert ::charlie ::color "red")
      (o/insert ::charlie ::left-of ::alice)
      (o/insert ::charlie ::height 72)
      o/fire-rules
      ((fn [session]
         (is (= 3 (count (o/query-all session ::get-person))))
         session))))

(deftest query-all-facts
  (let [rules (o/ruleset
                {::get-person
                 [:what
                  [id ::color color]
                  [id ::left-of left-of]
                  [id ::height height]]})]
    (-> (reduce o/add-rule (o/->session) rules)
        (o/insert ::bob ::color "blue")
        (o/insert ::bob ::left-of ::zach)
        (o/insert ::bob ::height 72)
        (o/insert ::alice ::color "green")
        (o/insert ::alice ::left-of ::bob)
        (o/insert ::alice ::height 64)
        (o/insert ::charlie ::color "red")
        (o/insert ::charlie ::left-of ::alice)
        (o/insert ::charlie ::height 72)
        ;; insert and retract a fact to make sure
        ;; it isn't returned by query-all
        (o/insert ::zach ::color "blue")
        (o/retract ::zach ::color)
        ((fn [session]
           (let [facts (o/query-all session)
                 ;; make a new session and insert the facts we retrieved
                 new-session (reduce o/add-rule (o/->session) rules)
                 new-session (reduce o/insert new-session facts)]
             (is (= 9 (count facts)))
             (is (= 3 (count (o/query-all new-session ::get-person))))
             new-session))))))

(deftest creating-a-ruleset
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::bob
           [:what
            [b ::color "blue"]
            [b ::right-of a]
            :then
            (is (= a ::alice))
            (is (= b ::bob))]
           ::alice
           [:what
            [a ::color "red"]
            [a ::left-of b]
            :then
            (is (= a ::alice))
            (is (= b ::bob))]}))
      (o/insert ::bob ::color "blue")
      (o/insert ::bob ::right-of ::alice)
      (o/insert ::alice ::color "red")
      (o/insert ::alice ::left-of ::bob)
      o/fire-rules))

(deftest dont-trigger-rule-when-updating-certain-facts
  (let [*count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::dont-trigger
             [:what
              [b ::color "blue"]
              [a ::color c {:then false}]
              :then
              (swap! *count inc)]}))
        (o/insert ::bob ::color "blue")
        o/fire-rules
        (o/insert ::alice ::color "red")
        o/fire-rules
        (o/insert ::alice ::color "maize")
        o/fire-rules
        ((fn [session]
           (is (= 1 @*count))
           session)))))

(deftest inserting-inside-a-rule
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::rule1
           [:what
            [b ::color "blue"]
            [::alice ::color c {:then false}]
            :then
            (o/reset! (o/insert session ::alice ::color "maize"))]}))
      (o/insert ::bob ::color "blue")
      (o/insert ::alice ::color "red")
      o/fire-rules
      ((fn [session]
         (is (= "maize" (:c (first (o/query-all session ::rule1)))))
         session))))

(deftest inserting-inside-a-rule-can-trigger-more-than-once
  (let [*count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::rule1
             [:what
              [b ::color "blue"]
              :then
              (-> session
                  (o/insert ::alice ::color "maize")
                  (o/insert ::charlie ::color "gold")
                  o/reset!)]
             ::rule2
             [:what
              [::alice ::color c1]
              [other-person ::color c2]
              :when
              (not= other-person ::alice)
              :then
              (swap! *count inc)]}))
        (o/insert ::alice ::color "red")
        (o/insert ::bob ::color "blue")
        o/fire-rules
        ((fn [session]
           (is (= 3 @*count))
           session)))))

(deftest inserting-inside-a-rule-cascades
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::rule1
           [:what
            [b ::color "blue"]
            :then
            (o/reset! (o/insert session ::charlie ::right-of ::bob))]
           ::rule2
           [:what
            [c ::right-of b]
            :then
            (o/reset! (o/insert session b ::left-of c))]
           ::rule3
           [:what
            [b ::left-of c]]}))
      (o/insert ::bob ::color "blue")
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::rule1))))
         (is (= 1 (count (o/query-all session ::rule2))))
         (is (= 1 (count (o/query-all session ::rule3))))
         session))))

(deftest conditions-can-use-external-values
  (let [*allow-rule-to-fire (atom false)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::rule1
             [:what
              [a ::left-of b]
              :when
              @*allow-rule-to-fire]}))
        (o/insert ::alice ::left-of ::zach)
        o/fire-rules
        ((fn [session]
           (reset! *allow-rule-to-fire true)
           session))
        (o/insert ::alice ::left-of ::bob)
        o/fire-rules
        ((fn [session]
           (is (= 1 (count (o/query-all session ::rule1))))
           (reset! *allow-rule-to-fire false)
           session))
        (o/insert ::alice ::left-of ::zach)
        o/fire-rules
        ((fn [session]
           (is (= 0 (count (o/query-all session ::rule1))))
           session)))))

(deftest id+attr-combos-can-be-stored-in-multiple-alpha-nodes
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::get-alice
           [:what
            [::alice ::color color]
            [::alice ::height height]]
           ::get-person
           [:what
            [id ::color color]
            [id ::height height]]}))
      (o/insert ::alice ::color "blue")
      (o/insert ::alice ::height 60)
      o/fire-rules
      ((fn [session]
         (let [alice (first (o/query-all session ::get-alice))]
           (is (= "blue" (:color alice)))
           (is (= 60 (:height alice))))
         session))
      (o/retract ::alice ::color)
      ((fn [session]
         (is (= 0 (count (o/query-all session ::get-alice))))
         session))))

(deftest ids-can-be-arbitrary-integers
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::rule1
           [:what
            [b ::color "blue"]
            [y ::left-of z]
            [a ::color "maize"]
            [y ::right-of b]
            [z ::left-of b]
            :then
            (is (= a ::alice))
            (is (= b ::bob))
            (is (= y ::yair))
            (is (= z 1))]}))
      (o/insert ::bob ::color "blue")
      (o/insert ::yair ::left-of 1)
      (o/insert ::alice ::color "maize")
      (o/insert ::yair ::right-of ::bob)
      (o/insert 1 ::left-of ::bob)
      o/fire-rules))

(deftest join-value-with-id
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::rule1
           [:what
            [b ::left-of id]
            [id ::color color]
            [id ::height height]]}))
      (o/insert ::alice ::color "blue")
      (o/insert ::alice ::height 60)
      (o/insert ::bob ::left-of ::alice)
      (o/insert ::charlie ::color "green")
      (o/insert ::charlie ::height 72)
      (o/insert ::bob ::left-of ::charlie)
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::rule1))))
         session))))

(deftest multiple-joins
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::rule1
           [:what
            [pid ::kind :player]
            [pid ::color pcolor]
            [pid ::height pheight]
            [eid ::kind kind]
            [eid ::color ecolor {:then false}]
            [eid ::height eheight {:then false}]
            :when
            (not= kind :player)
            :then
            (-> session
                (o/insert eid ::color "green")
                (o/insert eid ::height 70)
                o/reset!)]}))
      (o/insert 1 {::kind :player
                   ::color "red"
                   ::height 72})
      (o/insert 2 {::kind :enemy
                   ::color "blue"
                   ::height 60})
      o/fire-rules
      ((fn [session]
         (is (= "green" (:ecolor (first (o/query-all session ::rule1)))))
         session))))

(deftest join-followed-by-non-join
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::rule1
           [:what
            [id ::x x]
            [id ::y y]
            [id ::xv xv]
            [id ::yv yv]
            [::bob ::left-of z]]}))
      (o/insert ::bob ::left-of ::zach)
      (o/insert ::alice {::x 0 ::y 0 ::xv 1 ::yv 1})
      (o/insert ::charlie {::x 1 ::y 1 ::xv 0 ::yv 0})
      o/fire-rules
      ((fn [session]
         (is (= 2 (count (o/query-all session ::rule1))))
         session))))

(deftest only-last-condition-can-fire
  (let [*count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::rule1
             [:what
              [id ::left-of ::bob {:then false}]
              [id ::color color {:then false}]
              [::alice ::height height]
              :then
              (swap! *count inc)]}))
        (o/insert ::alice ::height 60) ;; out of order
        (o/insert ::alice ::left-of ::bob)
        (o/insert ::alice ::color "blue")
        o/fire-rules
        ((fn [session]
           (is (= 1 @*count))
           session))
        (o/retract ::alice ::height)
        (o/retract ::alice ::left-of)
        (o/retract ::alice ::color)
        (o/insert ::alice ::height 60)
        (o/insert ::alice ::left-of ::bob)
        (o/insert ::alice ::color "blue")
        o/fire-rules
        ((fn [session]
           (is (= 2 @*count))
           session))
        (o/insert ::alice ::left-of ::bob)
        (o/insert ::alice ::color "blue")
        o/fire-rules
        ((fn [session]
           (is (= 2 @*count))
           session))
        (o/insert ::alice ::height 60)
        o/fire-rules
        ((fn [session]
           (is (= 3 @*count))
           session)))))

(deftest avoid-unnecessary-rule-firings
  (let [*count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::get-person
             [:what
              [id ::color color]
              [id ::left-of left-of]
              [id ::height height]
              :then
              (swap! *count inc)]}))
        (o/insert ::bob ::color "blue")
        (o/insert ::bob ::left-of ::zach)
        (o/insert ::bob ::height 72)
        (o/insert ::alice ::color "blue")
        (o/insert ::alice ::left-of ::zach)
        (o/insert ::alice ::height 72)
        o/fire-rules
        (o/insert ::alice ::color "blue")
        o/fire-rules
        ((fn [session]
           (is (= 3 @*count))
           session)))))

(deftest then-finally
  (let [*trigger-count (atom 0)
        *all-people (atom [])]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::get-person
             [:what
              [id ::color color]
              [id ::left-of left-of]
              [id ::height height]
              :then-finally
              (->> (o/query-all session ::get-person)
                   (o/insert session ::people ::all)
                   o/reset!)]
             ::all-people
             [:what
              [::people ::all all-people]
              :then
              (reset! *all-people all-people)
              (swap! *trigger-count inc)]}))
        (o/insert ::bob ::color "blue")
        (o/insert ::bob ::left-of ::zach)
        (o/insert ::bob ::height 72)
        (o/insert ::alice ::color "blue")
        (o/insert ::alice ::left-of ::zach)
        (o/insert ::alice ::height 72)
        o/fire-rules
        ((fn [session]
           (is (= 2 (count @*all-people)))
           (is (= 1 @*trigger-count))
           session))
        (o/retract ::alice ::color)
        o/fire-rules
        ((fn [session]
           (is (= 1 (count @*all-people)))
           (is (= 2 @*trigger-count))
           session)))))

;; based on https://github.com/raquo/Airstream#frp-glitches
(deftest frp-glitch
  (let [*output (atom [])]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::is-positive
             [:what
              [::number ::any any-num]
              :then
              (o/insert! ::number ::positive? (pos? any-num))]
             
             ::doubled-numbers
             [:what
              [::number ::any any-num]
              :then
              (o/insert! ::number ::doubled (* 2 any-num))]
             
             ::combined
             [:what
              [::number ::positive? positive?]
              [::number ::doubled doubled]
              :then
              (o/insert! ::number ::combined [doubled positive?])]
             
             ::print-combined
             [:what
              [::number ::combined combined]
              :then
              (swap! *output conj combined)]}))
        (o/insert ::number ::any -1)
        o/fire-rules
        (o/insert ::number ::any 1)
        o/fire-rules
        ((fn [session]
           (is (= @*output [[-2 false] [2 true]]))
           session)))))

(deftest recursion
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::get-person
           [:what
            [id ::color color]
            [id ::left-of left-of]
            [id ::height height]
            [id ::friends friends {:then not=}]
            :then-finally
            (->> (o/query-all session ::get-person)
                 (reduce #(assoc %1 (:id %2) %2) {})
                 (o/insert! ::people ::by-id))]

           ::update-friends
           [:what
            [id ::friend-ids friend-ids]
            [::people ::by-id id->person]
            :then
            (->> (mapv id->person friend-ids)
                 (o/insert! id ::friends))]}))
      (o/insert ::bob ::color "blue")
      (o/insert ::bob ::left-of ::zach)
      (o/insert ::bob ::height 72)
      (o/insert ::bob ::friend-ids [::alice ::charlie])
      (o/insert ::alice ::color "blue")
      (o/insert ::alice ::left-of ::zach)
      (o/insert ::alice ::height 72)
      (o/insert ::alice ::friend-ids [])
      (o/insert ::charlie ::color "red")
      (o/insert ::charlie ::left-of ::bob)
      (o/insert ::charlie ::height 70)
      (o/insert ::charlie ::friend-ids [::alice])
      (o/insert ::people ::by-id {})
      o/fire-rules
      ((fn [session]
         (let [people (o/query-all session ::get-person)
               bob (first (filter #(= ::bob (:id %)) people))
               alice (first (filter #(= ::alice (:id %)) people))
               charlie (first (filter #(= ::charlie (:id %)) people))]
           (is (= 3 (count people)))
           (is (= [alice charlie] (:friends bob)))
           (is (= [] (mapv :id (:friends alice))))
           (is (= [alice] (:friends charlie))))
         session))))

(deftest avoid-infinite-loop-when-updating-fact-whose-value-is-joined
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::rule1
           [:what
            ;; normally, the `id2` would just be `id`,
            ;; but since it's using the custom :then function `not=`,
            ;; we need to give it a different symbol and enforce
            ;; the join in the :when block instead
            [b ::left-of id2 {:then not=}]
            [id ::color color]
            [id ::height height]
            :when
            (= id id2)
            :then
            (o/insert! b ::left-of ::charlie)]}))
      (o/insert ::bob ::left-of ::alice)
      (o/insert ::alice ::color "blue")
      (o/insert ::alice ::height 60)
      (o/insert ::charlie ::color "green")
      (o/insert ::charlie ::height 72)
      o/fire-rules
      ((fn [session]
         (is (= ::charlie (-> (o/query-all session ::rule1)
                              first
                              :id)))
         session)))
  ;; make sure it correctly throws an error if a join is made in a
  ;; :what tuple that also uses a custom :then function
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (reduce o/add-rule (o/->session)
                 (o/ruleset
                   {::rule1
                    [:what
                     [b ::left-of id {:then not=}]
                     [id ::color color]
                     [id ::height height]
                     :then
                     (o/insert! b ::left-of ::charlie)]})))))

(deftest recursion-limit
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::rule1
           [:what
            [::alice ::color c]
            :then
            (o/reset! (o/insert session ::alice ::height 15))]
           
           ::rule2
           [:what
            [::alice ::height height]
            :then
            (o/reset! (o/insert session ::alice ::age 10))]
           
           ::rule3
           [:what
            [::alice ::age age]
            :then
            (o/reset! (-> session
                          (o/insert ::alice ::color "maize")
                          (o/insert ::bob ::age 10)))]
           
           ::rule4
           [:what
            [::bob ::age age]
            :then
            (o/reset! (o/insert session ::bob ::height 15))]
           
           ::rule5
           [:what
            [::bob ::height height]
            :then
            (o/reset! (o/insert session ::bob ::age 10))]
           
           ::rule6
           [:what
            [::bob ::color c]
            :then
            (o/reset! (o/insert session ::bob ::color c))]}))
      (o/insert ::alice ::color "red")
      (o/insert ::bob ::color "blue")
      ((fn [session]
        (is (thrown? #?(:clj Exception :cljs js/Error)
                     (o/fire-rules session)))))))

(deftest infinite-self-triggering-caught-by-recursion-limit
  ;; A rule that inserts a fact causing itself to re-fire indefinitely
  ;; should be caught by the recursion limit, not hang forever.
  (let [session (-> (reduce o/add-rule (o/->session)
                      (o/ruleset
                        {::self-trigger
                         [:what
                          [::counter ::value n]
                          :then
                          (o/insert! ::counter ::value (inc n))]}))
                    (o/insert ::counter ::value 0))]
    (is (thrown-with-msg? #?(:clj Exception :cljs js/Error)
                          #"Recursion limit hit"
                          (o/fire-rules session)))))

(deftest non-deterministic-behavior
  (let [*count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::rule1
             [:what
              [id ::color "blue"]
              :then
              (swap! *count inc)
              (o/insert! id ::color "green")]
             
             ::rule2
             [:what
              [id ::color "blue"]
              :then
              (swap! *count inc)]
             
             ::rule3
             [:what
              [id ::color "blue"]
              :then
              (swap! *count inc)]}))
        (o/insert ::alice ::color "blue")
        o/fire-rules
        ((fn [session]
           (is (= 3 @*count))
           session)))))

(deftest dynamic-rule
  (let [*then-count (atom 0)
        *then-finally-count (atom 0)]
    (-> (o/add-rule
          (o/->session)
          (o/->rule
            ::player
            {:what
             [['id :player/x 'x {:then not=}]
              ['id :player/y 'y {:then not=}]]
             :when
             (fn [session {:keys [x y] :as match}]
               (and (pos? x) (pos? y)))
             :then
             (fn [session {:keys [id] :as match}]
               (swap! *then-count inc))
             :then-finally
             (fn [session]
               (swap! *then-finally-count inc))}))
        (o/insert 1 {:player/x 3 :player/y 1})
        (o/insert 2 {:player/x 5 :player/y 2})
        (o/insert 3 {:player/x 7 :player/y -1})
        o/fire-rules
        (o/insert 1 {:player/x 3 :player/y 1})
        o/fire-rules
        ((fn [session]
           (is (= 2 (count (o/query-all session ::player))))
           (is (= 2 @*then-count))
           (is (= 1 @*then-finally-count))
           session)))))

;; this is a demonstration of how literal values can cause a rule to fire
;; more often than when a binding is used. the technical reason is that
;; literal values are checked earlier on (in the alpha network).
;; when the value changes and then returns to its original value,
;; the entire match is retracted and then re-inserted, causing the rule to fire
;; despite the {:then false} usage.
;; with bindings, this is usually an in-place update, and the match is never
;; fully retracted, so it doesn't need to fire the rule again.
;; this difference in behavior isn't ideal but for now i can't think of a nice fix...
(deftest literal-values-with-then-option-can-cause-extra-rule-firings
  (let [*count-1 (atom 0)
        *count-2 (atom 0)
        ruleset-1 (o/ruleset
                    {::rule1
                     [:what
                      [id ::retired retired {:then false}] ;; value is a binding
                      [id ::age age]
                      :when
                      (not retired)
                      :then
                      (swap! *count-1 inc)]
                     
                     ::rule2
                     [:what
                      [id ::age age]
                      :then
                      (o/insert! id ::retired true)]
                     
                     ::rule3
                     [:what
                      [id ::retired true]
                      :then
                      (o/insert! id ::retired false)]})
        ruleset-2 (o/ruleset
                    {::rule1
                     [:what
                      [id ::retired false {:then false}] ;; value is a literal
                      [id ::age age]
                      :then
                      (swap! *count-2 inc)]
                     
                     ::rule2
                     [:what
                      [id ::age age]
                      :then
                      (o/insert! id ::retired true)]
                     
                     ::rule3
                     [:what
                      [id ::retired true]
                      :then
                      (o/insert! id ::retired false)]})
        session-1 (reduce o/add-rule (o/->session) ruleset-1)
        session-2 (reduce o/add-rule (o/->session) ruleset-2)]
    (-> session-1
        (o/insert ::bob {::retired false ::age 50})
        o/fire-rules
        ((fn [session]
           (is (= 1 @*count-1))
           session)))
    (-> session-2
        (o/insert ::bob {::retired false ::age 50})
        o/fire-rules
        ((fn [session]
           (is (= 2 @*count-2))
           session)))))

(deftest contains
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::num-conds-and-facts
           [:what
            [b ::color "blue"]]}))
      (o/insert ::bob ::color "blue")
      ((fn [session]
         (is (o/contains? session ::bob ::color))
         session))
      (o/retract ::bob ::color)
      ((fn [session]
         (is (not (o/contains? session ::bob ::color)))
         session)))
  (let [*fired (atom false)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::num-conds-and-facts
             [:what
              [b ::color "blue"]
              :when
              (o/contains? session b ::age)
              :then
              (reset! *fired true)]}))
        (o/insert ::bob ::color "blue")
        o/fire-rules
        ((fn [session]
           (is (not @*fired))
           session)))))

;; this shows how we can intercept all rule fns before they fire
(deftest rule-fns
  (let [*what? (atom false)
        *when? (atom false)
        *then? (atom false)
        *then-finally? (atom false)]
    (-> (reduce o/add-rule (o/->session)
          (map (fn [rule]
                 (o/wrap-rule rule
                              {:what
                               (fn [f session new-fact old-fact]
                                 (reset! *what? true)
                                 (f session new-fact old-fact))
                               :when
                               (fn [f session match]
                                 (reset! *when? true)
                                 (f session match))
                               :then
                               (fn [f session match]
                                 (reset! *then? true)
                                 (f session match))
                               :then-finally
                               (fn [f session]
                                 (reset! *then-finally? true)
                                 (f session))}))
            (o/ruleset
              {::rule1
               [:what
                [b ::color "blue"]
                [b ::right-of a]
                :when
                true
                :then
                nil
                :then-finally
                nil]
               ::rule2
               [:what
                [b ::color "blue"]
                [b ::right-of a]]})))
        (o/insert ::bob ::color "blue")
        (o/insert ::bob ::right-of ::alice)
        o/fire-rules
        ((fn [session]
           (is @*what?)
           (is @*when?)
           (is @*then?)
           (is @*then-finally?)
           session)))))

(deftest attr-can-have-a-binding-symbol
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::remove-facts-with-id
           [:what
            [id ::remove? true]
            [id attr value]
            :then
            (o/retract! id attr)]}))
      (o/insert ::alice ::remove? false)
      (o/insert ::alice ::color "maize")
      (o/insert ::alice ::right-of ::bob)
      (o/insert ::alice ::height 72)
      o/fire-rules
      ((fn [session]
         (is (o/contains? session ::alice ::color))
         (is (o/contains? session ::alice ::right-of))
         (is (o/contains? session ::alice ::height))
         session))
      (o/insert ::alice ::remove? true)
      o/fire-rules
      ((fn [session]
         (is (not (o/contains? session ::alice ::color)))
         (is (not (o/contains? session ::alice ::right-of)))
         (is (not (o/contains? session ::alice ::height)))
         session))))

(deftest removing-a-rule
  (let [*rule1-count (atom 0)
        *rule2-count (atom 0)
        *rule3-count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::rule1
             [:what
              [id ::color color]
              :then
              (swap! *rule1-count inc)]
             ::rule2
             [:what
              [id ::height height]
              :then
              (swap! *rule2-count inc)]
             ::rule3
             [:what
              [id ::color color]
              :then-finally
              (swap! *rule3-count inc)]}))
        (o/insert ::alice ::color "red")
        (o/insert ::bob ::height 72)
        o/fire-rules
        ((fn [session]
           (is (= 1 @*rule1-count))
           (is (= 1 @*rule2-count))
           (is (= 1 @*rule3-count))
           session))
        (o/insert ::alice ::color "red")
        (o/insert ::bob ::height 72)
        (o/remove-rule ::rule2)
        o/fire-rules
        ((fn [session]
           (is (= 2 @*rule1-count))
           (is (= 1 @*rule2-count))
           (is (= 2 @*rule3-count))
           session))
        (o/insert ::alice ::color "red")
        (o/insert ::bob ::height 72)
        (o/remove-rule ::rule3)
        o/fire-rules
        ((fn [session]
           (is (= 3 @*rule1-count))
           (is (= 1 @*rule2-count))
           (is (= 2 @*rule3-count))
           session)))))

;; === Meta-rules (Phase 1) ===

(deftest rule-meta-store-populated-on-add-rule
  (let [rules (o/ruleset
                {::rule1
                 [:what
                  [id ::color color]
                  :when
                  (pos? 1)
                  :then
                  nil]
                 ::rule2
                 [:what
                  [id ::height height]]})
        session (reduce o/add-rule (o/->session) rules)
        meta1 (o/query-all-meta session ::rule1)
        meta2 (o/query-all-meta session ::rule2)]
    ;; rule1 has all three blocks
    (is (true? (::o/rule meta1)))
    (is (true? (::o/has-when? meta1)))
    (is (true? (::o/has-then? meta1)))
    (is (false? (::o/has-then-finally? meta1)))
    ;; rule2 only has :what
    (is (true? (::o/rule meta2)))
    (is (false? (::o/has-when? meta2)))
    (is (false? (::o/has-then? meta2)))
    (is (false? (::o/has-then-finally? meta2)))
    ;; conditions-raw round-trips
    (is (= 1 (count (::o/conditions-raw meta1))))
    (is (= 1 (count (::o/conditions-raw meta2))))
    ;; conditions structured has expected shape
    (let [cond1 (first (::o/conditions meta1))]
      (is (= :binding (:kind (:id cond1))))
      (is (= :value (:kind (:attr cond1))))
      (is (= ::color (get-in cond1 [:attr :value])))
      (is (= :binding (:kind (:value cond1)))))))

(deftest query-all-meta-returns-all-metadata
  (let [rules (o/ruleset
                {::rule1
                 [:what
                  [id ::color color]]
                 ::rule2
                 [:what
                  [id ::height height]]})
        session (reduce o/add-rule (o/->session) rules)
        all-meta (o/query-all-meta session)
        meta-set (set all-meta)]
    ;; 6 attrs per rule * 2 rules = 12 tuples
    (is (= 12 (count all-meta)))
    ;; each tuple is [rule-name attr value]
    (is (every? #(= 3 (count %)) all-meta))
    ;; verify specific expected content
    (is (contains? meta-set [::rule1 ::o/rule true]))
    (is (contains? meta-set [::rule2 ::o/rule true]))
    (is (contains? meta-set [::rule1 ::o/has-when? false]))
    (is (contains? meta-set [::rule2 ::o/has-then? false]))))

(deftest query-all-excludes-meta-facts
  (let [*seen-rules (atom [])
        rules (o/ruleset
                {::domain-rule
                 [:what
                  [id ::color color]]
                 ::meta-watcher
                 [:what
                  [rule-name ::o/rule true]
                  :then
                  (swap! *seen-rules conj rule-name)]})
        session (-> (reduce o/add-rule (o/->session) rules)
                    (o/insert ::bob ::color "blue")
                    o/fire-rules)
        all-facts (o/query-all session)]
    ;; meta-rule should have actually fired (meta-facts exist to exclude)
    (is (seq @*seen-rules) "meta-rule should have fired, proving meta-facts exist")
    (is (seq (o/query-all-meta session)) "query-all-meta should return non-empty results")
    ;; query-all should only have domain facts, no ::o/ attributes
    (is (every? (fn [[_id attr _val]]
                  (not (and (qualified-keyword? attr)
                            (= (namespace attr) (namespace ::o/rule)))))
                all-facts))
    ;; there should be exactly 1 domain fact
    (is (= 1 (count all-facts)))))

(deftest meta-rule-added-last-sees-prior-rules
  (let [*seen-rules (atom [])]
    (-> (o/->session)
        ;; add domain rules first
        (o/add-rule (first (o/ruleset {::rule1 [:what [id ::color color]]})))
        (o/add-rule (first (o/ruleset {::rule2 [:what [id ::height height]]})))
        ;; add meta-rule last — it should retroactively see both prior rules
        (o/add-rule (first (o/ruleset
                             {::meta-watcher
                              [:what
                               [rule-name ::o/rule true]
                               :then
                               (swap! *seen-rules conj rule-name)]})))
        o/fire-rules
        ((fn [session]
           ;; should have seen all 3 rules (including itself)
           (is (= 3 (count @*seen-rules)))
           (is (contains? (set @*seen-rules) ::rule1))
           (is (contains? (set @*seen-rules) ::rule2))
           (is (contains? (set @*seen-rules) ::meta-watcher))
           session)))))

(deftest meta-rule-added-first-also-works
  (let [*seen-rules (atom [])]
    (-> (o/->session)
        ;; add meta-rule first
        (o/add-rule (first (o/ruleset
                             {::meta-watcher
                              [:what
                               [rule-name ::o/rule true]
                               :then
                               (swap! *seen-rules conj rule-name)]})))
        ;; add domain rules after
        (o/add-rule (first (o/ruleset {::rule1 [:what [id ::color color]]})))
        (o/add-rule (first (o/ruleset {::rule2 [:what [id ::height height]]})))
        o/fire-rules
        ((fn [session]
           ;; should have seen all 3 rules
           (is (= 3 (count @*seen-rules)))
           (is (contains? (set @*seen-rules) ::rule1))
           (is (contains? (set @*seen-rules) ::rule2))
           (is (contains? (set @*seen-rules) ::meta-watcher))
           session)))))

(deftest meta-rule-can-query-conditions
  (let [*rules-watching-color (atom [])]
    (-> (o/->session)
        (o/add-rule (first (o/ruleset {::color-rule [:what [id ::color color]]})))
        (o/add-rule (first (o/ruleset {::height-rule [:what [id ::height height]]})))
        (o/add-rule (first (o/ruleset
                             {::condition-watcher
                              [:what
                               [rule-name ::o/rule true]
                               [rule-name ::o/conditions conditions]
                               :when
                               (some #(= (get-in % [:attr :value]) ::color) conditions)
                               :then
                               (swap! *rules-watching-color conj rule-name)]})))
        o/fire-rules
        ((fn [session]
           ;; only ::color-rule watches ::color
           (is (= [::color-rule] @*rules-watching-color))
           ;; height-rule should NOT be in the results
           (is (not (contains? (set @*rules-watching-color) ::height-rule)))
           session)))))

(deftest conditions-raw-reconstruction
  (let [rules (o/ruleset
                {::complex-rule
                 [:what
                  [id ::color "blue"]
                  [id ::height height {:then false}]
                  [::alice ::left-of target]]})
        session (reduce o/add-rule (o/->session) rules)
        meta (o/query-all-meta session ::complex-rule)
        raw (::o/conditions-raw meta)]
    ;; 3 conditions
    (is (= 3 (count raw)))
    ;; first condition: [id ::color "blue"]
    (is (= 'id (first (nth raw 0))))  ;; id is binding named 'id
    (is (= ::color (second (nth raw 0))))  ;; attr is literal
    (is (= "blue" (nth (nth raw 0) 2)))  ;; value is literal
    ;; second condition: [id ::height height {:then false}]
    (is (= 4 (count (nth raw 1))))
    (is (= 'id (first (nth raw 1))))
    (is (= ::height (second (nth raw 1))))
    (is (= 'height (nth (nth raw 1) 2)))  ;; value binding named 'height
    ;; third condition: [::alice ::left-of target]
    (is (= ::alice (first (nth raw 2))))  ;; id is literal
    (is (= ::left-of (second (nth raw 2))))  ;; attr is literal
    (is (= 'target (nth (nth raw 2) 2)))))  ;; value binding named 'target

;; === Phase 2: add-rule! and deferred queue ===

(deftest add-rule!-in-then-block
  (let [*generated-fired (atom false)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::trigger
             [:what
              [::trigger ::go? true]
              :then
              (o/add-rule!
                (o/->rule ::generated
                  {:what [['id ::color 'color]]
                   :then (fn [session match]
                           (reset! *generated-fired true))}))]}))
        (o/insert ::trigger ::go? true)
        o/fire-rules ;; this drains the deferred queue and adds ::generated
        ((fn [session]
           ;; the generated rule should exist now
           (is (get-in session [:rule-name->node-id ::generated]))
           session))
        ;; now insert a fact that the generated rule watches
        (o/insert ::bob ::color "blue")
        o/fire-rules
        ((fn [session]
           ;; the generated rule should have fired
           (is @*generated-fired)
           ;; can query it
           (is (= 1 (count (o/query-all session ::generated))))
           session)))))

(deftest remove-rule!-in-then-block
  (let [*rule2-count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::rule1
             [:what
              [::trigger ::remove? true]
              :then
              (o/remove-rule! ::rule2)]
             ::rule2
             [:what
              [id ::color color]
              :then
              (swap! *rule2-count inc)]}))
        (o/insert ::bob ::color "blue")
        o/fire-rules
        ((fn [session]
           (is (= 1 @*rule2-count))
           session))
        ;; trigger removal of rule2
        (o/insert ::trigger ::remove? true)
        o/fire-rules
        ((fn [session]
           ;; rule2 should have been removed after fire-rules drained the deferred queue
           (is (thrown? #?(:clj Exception :cljs js/Error)
                        (o/query-all session ::rule2)))
           session))
        ;; inserting a new color fact should NOT trigger rule2 (it's removed)
        (o/insert ::bob ::color "red")
        o/fire-rules
        ((fn [session]
           ;; count should still be 1 (only the initial firing)
           (is (= 1 @*rule2-count))
           session)))))

(deftest interleaved-add-then-remove-same-rule
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::trigger
           [:what
            [::trigger ::go? true]
            :then
            ;; add then remove the same rule
            (o/add-rule!
              (o/->rule ::ephemeral
                {:what [['id ::color 'color]]}))
            (o/remove-rule! ::ephemeral)]}))
      (o/insert ::trigger ::go? true)
      o/fire-rules
      ((fn [session]
         ;; net result: rule should not exist
         (is (thrown? #?(:clj Exception :cljs js/Error)
                      (o/query-all session ::ephemeral)))
         session))))

(deftest remove-rule-safe-doesnt-throw
  ;; remove-rule! of an already-removed rule should not throw in drain context
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::trigger1
           [:what
            [::trigger ::go? true]
            :then
            (o/remove-rule! ::target)]
           ::trigger2
           [:what
            [::trigger ::go? true]
            :then
            ;; this also tries to remove ::target — should not throw
            (o/remove-rule! ::target)]
           ::target
           [:what
            [id ::color color]]}))
      (o/insert ::trigger ::go? true)
      o/fire-rules
      ((fn [session]
         ;; target should be gone
         (is (thrown? #?(:clj Exception :cljs js/Error)
                      (o/query-all session ::target)))
         session))))

(deftest add-rule!-retroactive-init-with-existing-facts
  ;; When add-rule! creates a rule, it should see facts already in the session
  ;; (facts that are in id-attr-nodes because they match other rules)
  (let [*generated-fired (atom false)
        *seen-color (atom nil)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::keeper
             [:what
              [id ::color color]]
             ::trigger
             [:what
              [::trigger ::go? true]
              :then
              (o/add-rule!
                (o/->rule ::generated
                  {:what [['id ::color 'color]]
                   :then (fn [session {:keys [color]}]
                           (reset! *generated-fired true)
                           (reset! *seen-color color))}))]}))
        ;; insert color fact — it matches ::keeper so it stays in the session
        (o/insert ::bob ::color "blue")
        (o/insert ::trigger ::go? true)
        o/fire-rules
        ((fn [session]
           ;; ::generated should have retroactively seen ::bob's color
           (is @*generated-fired)
           (is (= "blue" @*seen-color))
           (is (= 1 (count (o/query-all session ::generated))))
           session)))))

(deftest retroactive-init-no-double-firing
  ;; When retroactive init replays facts, the :then block should fire exactly once
  (let [*fire-count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::keeper
             [:what
              [id ::color color]]
             ::trigger
             [:what
              [::trigger ::go? true]
              :then
              (o/add-rule!
                (o/->rule ::counter
                  {:what [['id ::color 'color]]
                   :then (fn [session match]
                           (swap! *fire-count inc))}))]}))
        (o/insert ::alice ::color "red")
        (o/insert ::bob ::color "blue")
        (o/insert ::trigger ::go? true)
        o/fire-rules
        ((fn [session]
           ;; should fire exactly twice (once per matching fact), not more
           (is (= 2 @*fire-count))
           session)))))

(deftest add-rule!-throws-outside-rule
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (o/add-rule! (o/->rule ::foo {:what [['id ::color 'color]]})))))

(deftest remove-rule!-throws-outside-rule
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (o/remove-rule! ::foo))))

;; === Phase 4: Truth maintenance ===

(deftest removing-rule-cleans-up-meta-store
  (let [rules (o/ruleset
                {::rule1 [:what [id ::color color]]
                 ::rule2 [:what [id ::height height]]})
        session (reduce o/add-rule (o/->session) rules)]
    ;; both rules have actual metadata content
    (is (true? (::o/rule (o/query-all-meta session ::rule1))))
    (is (true? (::o/rule (o/query-all-meta session ::rule2))))
    ;; remove rule1
    (let [session (o/remove-rule session ::rule1)]
      ;; rule1 metadata should be gone
      (is (nil? (o/query-all-meta session ::rule1)))
      ;; rule2 metadata should still have real content
      (is (true? (::o/rule (o/query-all-meta session ::rule2)))))))

(deftest derived-rule-cascading-removal
  ;; A rule created via add-rule! should be removed when its source rule is removed
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::source
           [:what
            [::trigger ::go? true]
            :then
            (o/add-rule!
              (o/->rule ::derived
                {:what [['id ::color 'color]]}))]}))
      (o/insert ::trigger ::go? true)
      o/fire-rules
      ((fn [session]
         ;; derived rule exists
         (is (get-in session [:rule-name->node-id ::derived]))
         session))
      ;; remove the source rule — derived should cascade
      (o/remove-rule ::source)
      ((fn [session]
         ;; derived should be gone too
         (is (nil? (get-in session [:rule-name->node-id ::derived])))
         ;; and its metadata
         (is (nil? (o/query-all-meta session ::derived)))
         session))))

(deftest root-rule-survives-source-removal
  ;; A rule created with {:root? true} should NOT be removed when its source is removed
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::source
           [:what
            [::trigger ::go? true]
            :then
            (o/add-rule!
              (o/->rule ::root-derived
                {:what [['id ::color 'color]]})
              {:root? true})]}))
      (o/insert ::trigger ::go? true)
      o/fire-rules
      ((fn [session]
         ;; root-derived rule exists
         (is (get-in session [:rule-name->node-id ::root-derived]))
         session))
      ;; remove source rule
      (o/remove-rule ::source)
      ((fn [session]
         ;; root-derived should SURVIVE
         (is (get-in session [:rule-name->node-id ::root-derived]))
         session))))

(deftest deep-cascade-chain
  ;; A -> B -> C: removing A should cascade to B and C
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {;; keeper rules ensure facts survive in the session
           ::keep-colors [:what [id ::color color]]
           ::keep-heights [:what [id ::height height]]
           ::rule-a
           [:what
            [::trigger ::go? true]
            :then
            (o/add-rule!
              (o/->rule ::rule-b
                {:what [['id ::color 'color]]
                 :then (fn [session match]
                         (o/add-rule!
                           (o/->rule ::rule-c
                             {:what [['id ::height 'height]]})))}))]}))
      (o/insert ::bob ::color "blue")
      (o/insert ::bob ::height 72)
      (o/insert ::trigger ::go? true)
      o/fire-rules
      ((fn [session]
         ;; all three should exist
         (is (get-in session [:rule-name->node-id ::rule-a]))
         (is (get-in session [:rule-name->node-id ::rule-b]))
         (is (get-in session [:rule-name->node-id ::rule-c]))
         session))
      ;; remove A — B and C should cascade
      (o/remove-rule ::rule-a)
      ((fn [session]
         (is (nil? (get-in session [:rule-name->node-id ::rule-b])))
         (is (nil? (get-in session [:rule-name->node-id ::rule-c])))
         session))))

;; === Phase 5: Integration tests ===

(deftest schema-driven-rule-generation
  ;; From the SPEC example: a meta-rule that generates getter rules from schema declarations
  (let [*getter-fired (atom false)
        *getter-matches (atom nil)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {;; meta-rule: when an entity schema is declared, generate a getter rule
             ::schema->getter
             [:what
              [entity-kw ::schema-attributes attrs]
              :then
              (let [rule-name (keyword (namespace entity-kw) (str (name entity-kw) "-getter"))]
                (o/add-rule!
                  (o/->rule rule-name
                    {:what (mapv (fn [attr] [(quote id) attr (symbol (name attr))]) attrs)
                     :then (fn [session match]
                             (reset! *getter-fired true)
                             (reset! *getter-matches match))})))]}))
        ;; insert some player data first (needs a keeper rule since no getter exists yet)
        (o/add-rule (first (o/ruleset {::keep-x [:what [id ::x x]]})))
        (o/add-rule (first (o/ruleset {::keep-y [:what [id ::y y]]})))
        (o/add-rule (first (o/ruleset {::keep-health [:what [id ::health h]]})))
        (o/insert ::player {::x 10 ::y 20 ::health 100})
        ;; declare the schema — this triggers the meta-rule to generate the getter
        (o/insert ::player ::schema-attributes [::x ::y ::health])
        o/fire-rules
        ((fn [session]
           ;; the getter rule should have been created
           (let [getter-name (keyword (namespace ::player) (str (name ::player) "-getter"))]
             (is (get-in session [:rule-name->node-id getter-name]))
             ;; and it should have fired with the pre-existing player data
             (is @*getter-fired)
             (is (= 10 (:x @*getter-matches)))
             (is (= 20 (:y @*getter-matches)))
             (is (= 100 (:health @*getter-matches))))
           session)))))

(deftest rule-analysis-detect-attribute-watchers
  ;; Meta-rule that detects when multiple rules watch the same attribute
  (let [*shared-attrs (atom #{})]
    (-> (o/->session)
        (o/add-rule (first (o/ruleset
                             {::analyze-shared-attrs
                              [:what
                               [rule1 ::o/rule true]
                               [rule1 ::o/conditions conditions1]
                               [rule2 ::o/rule true]
                               [rule2 ::o/conditions conditions2]
                               :when
                               (and (not= rule1 rule2)
                                    ;; find attributes watched by both rules
                                    (let [attrs1 (set (keep #(get-in % [:attr :value]) conditions1))
                                          attrs2 (set (keep #(get-in % [:attr :value]) conditions2))
                                          shared (clojure.set/intersection attrs1 attrs2)]
                                      (seq shared)))
                               :then
                               (let [attrs1 (set (keep #(get-in % [:attr :value]) conditions1))
                                     attrs2 (set (keep #(get-in % [:attr :value]) conditions2))]
                                 (swap! *shared-attrs into (clojure.set/intersection attrs1 attrs2)))]})))
        ;; add two rules that both watch ::color
        (o/add-rule (first (o/ruleset {::rule1 [:what [id ::color color] [id ::height height]]})))
        (o/add-rule (first (o/ruleset {::rule2 [:what [id ::color color] [id ::weight weight]]})))
        ;; and one that doesn't
        (o/add-rule (first (o/ruleset {::rule3 [:what [id ::age age]]})))
        o/fire-rules
        ((fn [session]
           ;; should detect that ::color is watched by both rule1 and rule2
           (is (contains? @*shared-attrs ::color))
           ;; ::height, ::weight, ::age are not shared
           (is (not (contains? @*shared-attrs ::height)))
           (is (not (contains? @*shared-attrs ::weight)))
           (is (not (contains? @*shared-attrs ::age)))
           session)))))

(deftest performance-100-rules
  ;; Add 100+ rules and measure that metadata is correctly populated
  (let [rules (mapv (fn [i]
                      (o/->rule (keyword "perf-test" (str "rule-" i))
                        {:what [['id (keyword "perf-test" (str "attr-" i)) 'v]]}))
                    (range 100))
        session (reduce o/add-rule (o/->session) rules)
        all-meta (o/query-all-meta session)]
    ;; all 100 rules should have metadata
    (is (= 100 (count (:rule-meta-store session))))
    ;; 6 meta-attrs per rule * 100 rules = 600 tuples
    (is (= 600 (count all-meta)))
    ;; spot-check a few rules with full content verification
    (let [meta-50 (o/query-all-meta session :perf-test/rule-50)]
      (is (true? (::o/rule meta-50)))
      (is (= 1 (count (::o/conditions-raw meta-50))))
      (is (false? (::o/has-when? meta-50)))
      (is (false? (::o/has-then? meta-50)))
      (is (false? (::o/has-then-finally? meta-50)))
      (is (= 1 (count (::o/conditions meta-50)))))))

;; === Bug fixes ===

(deftest add-rule!-idempotent-on-duplicate-name
  ;; A meta-rule that fires multiple times should not crash when
  ;; calling add-rule! with the same name repeatedly
  (let [*trigger-count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::trigger
             [:what
              [::trigger ::val v]
              :then
              (swap! *trigger-count inc)
              (o/add-rule!
                (o/->rule ::generated
                  {:what [['id ::color 'color]]}))]}))
        (o/insert ::trigger ::val 1)
        o/fire-rules
        ((fn [session]
           (is (= 1 @*trigger-count))
           (is (o/contains-rule? session ::generated))
           session))
        ;; re-trigger — should NOT crash
        (o/insert ::trigger ::val 2)
        o/fire-rules
        ((fn [session]
           (is (= 2 @*trigger-count))
           ;; generated rule still exists
           (is (o/contains-rule? session ::generated))
           session)))))

(deftest contains-rule?-works
  (let [session (-> (o/->session)
                    (o/add-rule (first (o/ruleset {::rule1 [:what [id ::color color]]}))))]
    (is (o/contains-rule? session ::rule1))
    (is (not (o/contains-rule? session ::nonexistent)))
    (let [session (o/remove-rule session ::rule1)]
      (is (not (o/contains-rule? session ::rule1))))))

;; === Missing test coverage ===

(deftest meta-rule-reacts-to-rule-removal
  ;; retract-meta-facts-from-rete should trigger meta-rules on removal
  (let [*tracked-rules (atom #{})]
    (-> (o/->session)
        (o/add-rule (first (o/ruleset
                             {::tracker
                              [:what
                               [rule-name ::o/rule true]
                               :then-finally
                               (->> (o/query-all session ::tracker)
                                    (mapv :rule-name)
                                    set
                                    (reset! *tracked-rules))]})))
        (o/add-rule (first (o/ruleset {::rule1 [:what [id ::color color]]})))
        (o/add-rule (first (o/ruleset {::rule2 [:what [id ::height height]]})))
        o/fire-rules
        ((fn [session]
           ;; tracker sees all 3 rules
           (is (= #{::tracker ::rule1 ::rule2} @*tracked-rules))
           session))
        (o/remove-rule ::rule1)
        o/fire-rules
        ((fn [session]
           ;; after removal, tracker should no longer see rule1
           (is (contains? @*tracked-rules ::tracker))
           (is (contains? @*tracked-rules ::rule2))
           (is (not (contains? @*tracked-rules ::rule1)))
           session)))))

(deftest add-rule!-from-then-finally
  ;; add-rule! should work from :then-finally blocks, not just :then
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::trigger
           [:what
            [::trigger ::go? true]
            :then-finally
            (o/add-rule!
              (o/->rule ::from-finally
                {:what [['id ::color 'color]]}))]}))
      (o/insert ::trigger ::go? true)
      o/fire-rules
      ((fn [session]
         (is (o/contains-rule? session ::from-finally))
         session))))

(deftest remove-rule!-from-then-finally
  ;; remove-rule! should work from :then-finally blocks
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::trigger
           [:what
            [::trigger ::remove? true]
            :then-finally
            (o/remove-rule! ::target)]
           ::target
           [:what
            [id ::color color]]}))
      (o/insert ::trigger ::remove? true)
      o/fire-rules
      ((fn [session]
         (is (not (o/contains-rule? session ::target)))
         session))))

(deftest derived-from-metadata-is-queryable
  ;; ::o/derived-from should be present in query-all-meta for derived rules
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::source
           [:what
            [::trigger ::go? true]
            :then
            (o/add-rule!
              (o/->rule ::derived
                {:what [['id ::color 'color]]}))]}))
      (o/insert ::trigger ::go? true)
      o/fire-rules
      ((fn [session]
         (let [meta (o/query-all-meta session ::derived)]
           (is (= ::source (::o/derived-from meta)))
           (is (true? (::o/rule meta))))
         session))))

(deftest root-metadata-is-queryable
  ;; ::o/root? should be present in query-all-meta for root rules
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::source
           [:what
            [::trigger ::go? true]
            :then
            (o/add-rule!
              (o/->rule ::root-child
                {:what [['id ::color 'color]]})
              {:root? true})]}))
      (o/insert ::trigger ::go? true)
      o/fire-rules
      ((fn [session]
         (let [meta (o/query-all-meta session ::root-child)]
           (is (true? (::o/root? meta)))
           (is (= ::source (::o/derived-from meta))))
         session))))

(deftest remove-then-readd-same-rule-in-deferred-queue
  ;; FIFO ordering: remove-rule! then add-rule! with same name should work
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::target
           [:what
            [id ::color color]]
           ::trigger
           [:what
            [::trigger ::go? true]
            :then
            (o/remove-rule! ::target)
            (o/add-rule!
              (o/->rule ::target
                {:what [['id ::height 'height]]}))]}))
      ;; target initially watches ::color
      (o/insert ::bob ::color "blue")
      o/fire-rules
      ((fn [session]
         (is (= 1 (count (o/query-all session ::target))))
         session))
      ;; trigger remove-then-readd
      (o/insert ::trigger ::go? true)
      o/fire-rules
      ((fn [session]
         ;; target should now watch ::height, not ::color
         (is (o/contains-rule? session ::target))
         ;; old color match should be gone (rule was replaced)
         ;; insert a height fact to see if new version works
         session))
      (o/insert ::bob ::height 72)
      o/fire-rules
      ((fn [session]
         ;; new target should match height facts
         (is (= 1 (count (o/query-all session ::target))))
         (is (= 72 (:height (first (o/query-all session ::target)))))
         session))))

(deftest add-rule!-multi-condition-retroactive-init
  ;; Generated rules with multiple conditions should retroactively match
  ;; pre-existing facts correctly
  (let [*fired (atom false)
        *match (atom nil)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::keep-x [:what [id ::x x]]
             ::keep-y [:what [id ::y y]]
             ::trigger
             [:what
              [::trigger ::go? true]
              :then
              (o/add-rule!
                (o/->rule ::multi-cond
                  {:what [['id ::x 'x]
                          ['id ::y 'y]]
                   :then (fn [session {:keys [id x y]}]
                           (reset! *fired true)
                           (reset! *match {:id id :x x :y y}))}))]}))
        (o/insert ::player {::x 10 ::y 20})
        (o/insert ::trigger ::go? true)
        o/fire-rules
        ((fn [session]
           (is @*fired "multi-condition rule should have fired retroactively")
           (is (= 10 (:x @*match)))
           (is (= 20 (:y @*match)))
           (is (= 1 (count (o/query-all session ::multi-cond))))
           session)))))

(deftest add-rule!-multi-condition-multiple-entities
  ;; Multiple entities should all be found by retroactive init of multi-condition rule
  (let [*fire-count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::keep-x [:what [id ::x x]]
             ::keep-y [:what [id ::y y]]
             ::trigger
             [:what
              [::trigger ::go? true]
              :then
              (o/add-rule!
                (o/->rule ::multi-cond
                  {:what [['id ::x 'x]
                          ['id ::y 'y]]
                   :then (fn [session match]
                           (swap! *fire-count inc))}))]}))
        (o/insert ::p1 {::x 10 ::y 20})
        (o/insert ::p2 {::x 30 ::y 40})
        (o/insert ::p3 {::x 50 ::y 60})
        (o/insert ::trigger ::go? true)
        o/fire-rules
        ((fn [session]
           ;; all 3 entities should match
           (is (= 3 @*fire-count))
           (is (= 3 (count (o/query-all session ::multi-cond))))
           session)))))

(deftest multiple-rules-add-different-rules-in-same-cycle
  ;; Two different rules both calling add-rule! in the same fire-rules cycle
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::trigger1
           [:what
            [::trigger ::go? true]
            :then
            (o/add-rule!
              (o/->rule ::gen1
                {:what [['id ::color 'color]]}))]
           ::trigger2
           [:what
            [::trigger ::go? true]
            :then
            (o/add-rule!
              (o/->rule ::gen2
                {:what [['id ::height 'height]]}))]}))
      (o/insert ::trigger ::go? true)
      o/fire-rules
      ((fn [session]
         ;; both generated rules should exist
         (is (o/contains-rule? session ::gen1))
         (is (o/contains-rule? session ::gen2))
         session))))

(deftest meta-rule-between-domain-rules
  ;; meta-rule-1, domain-rule, meta-rule-2: all should see each other
  (let [*tracker1-rules (atom #{})
        *tracker2-rules (atom #{})]
    (-> (o/->session)
        ;; first meta-rule
        (o/add-rule (first (o/ruleset
                             {::tracker1
                              [:what
                               [rule-name ::o/rule true]
                               :then
                               (swap! *tracker1-rules conj rule-name)]})))
        ;; domain rule in the middle
        (o/add-rule (first (o/ruleset {::domain [:what [id ::color color]]})))
        ;; second meta-rule
        (o/add-rule (first (o/ruleset
                             {::tracker2
                              [:what
                               [rule-name ::o/rule true]
                               :then
                               (swap! *tracker2-rules conj rule-name)]})))
        o/fire-rules
        ((fn [session]
           ;; tracker1 should see all 3 (domain + tracker1 + tracker2)
           (is (= #{::tracker1 ::domain ::tracker2} @*tracker1-rules))
           ;; tracker2 should also see all 3
           (is (= #{::tracker1 ::domain ::tracker2} @*tracker2-rules))
           session)))))

