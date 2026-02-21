(require
  '[clojure.test :as t]
  '[odoyle.rules-test]
  '[odoyle.examples.access-control-test]
  '[odoyle.examples.elemental-combos-test]
  '[odoyle.examples.process-orchestrator-test]
  '[odoyle.examples.schema-getter-test]
  '[odoyle.examples.rule-linter-test]
  '[odoyle.examples.reactive-removal-test])

(t/run-tests 'odoyle.rules-test 'odoyle.examples.access-control-test 'odoyle.examples.elemental-combos-test 'odoyle.examples.process-orchestrator-test 'odoyle.examples.schema-getter-test 'odoyle.examples.rule-linter-test 'odoyle.examples.reactive-removal-test)
