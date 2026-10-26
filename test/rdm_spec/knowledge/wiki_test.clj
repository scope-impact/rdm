(ns rdm-spec.knowledge.wiki-test
  (:require [clojure.test :refer [deftest is]]
            [rdm-spec.knowledge.wiki :as w]))

(deftest index-links
  (is (= [["pages/a.md" 3]] (w/index-links "# I\n\n- [A](pages/a.md): x\n- [ext](https://x.org/y.md)\n"))))

(deftest footnotes
  (is (w/footnoted? "Text.[^1]\n\n[^1]: `a:b@c`\n"))
  (is (not (w/footnoted? "Text.\n"))))
