(require
  '[clojure.test :as t]
  '[odoyle.rules-test]
  '[odoyle.examples.access-control-test]
  '[odoyle.examples.elemental-combos-test])

(t/run-tests 'odoyle.rules-test 'odoyle.examples.access-control-test 'odoyle.examples.elemental-combos-test)
