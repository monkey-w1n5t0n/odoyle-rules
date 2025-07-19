(ns odoyle.rules-test
  (:require [clojure.test :refer [deftest is]]
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

;; Test 1: Basic rule with :then block printing timestamp
(deftest basic-rule-with-timestamp
  (let [*output (atom [])]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::print-time
             [:what
              [::time ::total tt]
              :then
              (swap! *output conj tt)]}))
        (o/insert ::time ::total 100)
        o/fire-rules
        ((fn [session]
           (is (= [100] @*output))
           session))
        (o/insert ::time ::total 200)
        o/fire-rules
        ((fn [session]
           (is (= [100 200] @*output))
           session)))))

;; Test 2: Updating session from inside rule with o/insert!
(deftest updating-session-with-insert-bang
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::move-player
           [:what
            [::time ::total tt]
            :then
            (o/insert! ::player ::x tt)]
           ::player-query
           [:what
            [::player ::x x]]}))
      (o/insert ::time ::total 50)
      o/fire-rules
      ((fn [session]
         (is (= 50 (:x (first (o/query-all session ::player-query)))))
         session))))

;; Test 3: {:then not=} behavior for conditional triggering  
(deftest then-not-equals-conditional
  (let [*count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::conditional-rule
             [:what
              [::player ::health health {:then not=}]
              :then
              (swap! *count inc)]}))
        (o/insert ::player ::health 100)
        o/fire-rules
        ((fn [session]
           (is (= 1 @*count))
           session))
        (o/insert ::player ::health 100) ; same value, should not trigger
        o/fire-rules
        ((fn [session]
           (is (= 1 @*count))
           session))
        (o/insert ::player ::health 80) ; different value, should trigger
        o/fire-rules
        ((fn [session]
           (is (= 2 @*count))
           session)))))

;; Test 4: Complex joins between id and value columns
(deftest complex-weapon-damage-join
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::update-player-damage
           [:what
            [player-id ::weapon-id weapon-id]
            [player-id ::strength  strength]
            [weapon-id ::damage    damage]
            :then
            (o/insert! player-id ::damage (* damage strength))]
           ::get-damage
           [:what
            [id ::damage damage]]}))
      (o/insert ::player ::weapon-id ::sword)
      (o/insert ::player ::strength 10)
      (o/insert ::sword ::damage 5)
      o/fire-rules
      ((fn [session]
         (is (= 50 (:damage (first (o/query-all session ::get-damage)))))
         session))))

;; Test 5: Bulk insertion with integer IDs  
(deftest bulk-insertion-with-integers
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::character
           [:what
            [id ::x x]
            [id ::y y]]}))
      ((fn [session]
         (o/fire-rules
           (reduce (fn [session id]
                     (o/insert session id {::x (+ id 10) ::y (+ id 20)}))
                   session
                   (range 3)))))
      ((fn [session]
         (let [characters (o/query-all session ::character)]
           (is (= 3 (count characters)))
           (is (= #{0 1 2} (set (map :id characters))))
           session)))))

;; Test 6: Derived facts with then-finally from README example
(deftest derived-facts-all-characters
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::character
           [:what
            [id ::x x]
            [id ::y y]
            :then-finally
            (->> (o/query-all session ::character)
                 (o/insert session ::derived ::all-characters)
                 o/reset!)]
           ::print-all-characters
           [:what
            [::derived ::all-characters all-characters]]}))
      (o/insert ::player {::x 20 ::y 15})
      (o/insert ::enemy {::x 5 ::y 5})
      o/fire-rules
      ((fn [session]
         (let [all-chars (:all-characters (first (o/query-all session ::print-all-characters)))]
           (is (= 2 (count all-chars)))
           (is (some #(= ::player (:id %)) all-chars))
           (is (some #(= ::enemy (:id %)) all-chars))
           session)))))

;; Test 7: Serialization/deserialization of session facts
(deftest session-fact-serialization
  (let [rules (o/ruleset
                {::character
                 [:what
                  [id ::x x]
                  [id ::y y]]})]
    (-> (reduce o/add-rule (o/->session) rules)
        (o/insert ::player {::x 20 ::y 15})
        (o/insert ::enemy {::x 5 ::y 5})
        ((fn [session]
           (let [facts (o/query-all session)
                 new-session (reduce o/add-rule (o/->session) rules)
                 restored-session (reduce o/insert new-session facts)]
             (is (= 2 (count (o/query-all restored-session ::character))))
             restored-session))))))

;; Test 8: Performance pattern with derived character facts
(deftest performance-derived-character-pattern
  (-> (reduce o/add-rule (o/->session)
        (o/ruleset
          {::character
           [:what
            [id ::x x]
            [id ::y y]
            :then
            (o/insert! id ::character match)]
           ::move-character
           [:what
            [::time ::delta     dt]
            [id     ::character ch {:then false}]
            :then
            (o/insert! id {::x (+ (:x ch) dt) ::y (+ (:y ch) dt)})]}))
      (o/insert ::player {::x 10 ::y 5})
      (o/insert ::time ::delta 2)
      o/fire-rules
      ((fn [session]
         (is (= 12 (:x (first (o/query-all session ::character)))))
         (is (= 7 (:y (first (o/query-all session ::character)))))
         session))))

;; Test 9: Conditions with external atom values
(deftest conditions-with-external-atoms
  (let [*allow-rule (atom false)
        *count (atom 0)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::conditional-external
             [:what
              [::player ::action action]
              :when
              @*allow-rule
              :then
              (swap! *count inc)]}))
        (o/insert ::player ::action "move")
        o/fire-rules
        ((fn [session]
           (is (= 0 @*count))
           (reset! *allow-rule true)
           session))
        (o/insert ::player ::action "attack")
        o/fire-rules
        ((fn [session]
           (is (= 1 @*count))
           session)))))

;; Test 10: Multi-step cascading rule insertions
(deftest multi-step-cascading-insertions
  (let [*final-value (atom nil)]
    (-> (reduce o/add-rule (o/->session)
          (o/ruleset
            {::step1
             [:what
              [::trigger ::start value]
              :then
              (o/insert! ::intermediate ::step1 (* value 2))]
             ::step2
             [:what
              [::intermediate ::step1 value]
              :then
              (o/insert! ::intermediate ::step2 (+ value 10))]
             ::step3
             [:what
              [::intermediate ::step2 value]
              :then
              (o/insert! ::final ::result value)
              (reset! *final-value value)]}))
        (o/insert ::trigger ::start 5)
        o/fire-rules
        ((fn [session]
           (is (= 20 @*final-value)) ; (5 * 2) + 10 = 20
           session)))))

