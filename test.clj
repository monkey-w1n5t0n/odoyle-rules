(require
  '[clojure.test :as t]
  '[odoyle.rules-test]
  '[odoyle.examples.access-control-test])

(t/run-tests 'odoyle.rules-test 'odoyle.examples.access-control-test)
