(ns rdm-spec.publishing.site-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [rdm-spec.publishing.site :as site]))

(def model
  {:system {:id :s :name "Sauna" :description "Books turns"}
   :containers [{:id :app :name "App" :kind :runs
                 :contexts [{:id :booking :name "booking" :description "Takes bookings"
                             :components [{:id :booking/turns :name "Turns" :spec "booking/turns" :description "The turns"}
                                          {:id :booking/calendar :name "Calendar" :spec "booking/calendar" :description "The calendar"}]}]}]
   :kernel [{:id :kernel/cli :name "CLI" :spec "kernel/cli" :description "The command"}]
   :views [{:key "context" :level :context} {:key "booking-components" :level :component :context :booking}]})

(def scenarios
  [{:id "spec-0001" :title "Booking a turn" :level :e2e :needs ["UN-001"] :wip? false :component :booking/turns
    :background [{:keyword "Given" :text "a sauna"}] :steps [{:keyword "When" :text "a turn is booked"}]
    :rows [] :examples []}
   {:id "spec-0002" :title "Later" :level :contract :needs ["UN-001"] :wip? true :component :booking/turns
    :background [] :steps [{:keyword "Then" :text "it waits"}] :rows [] :examples []}])

(def input
  {:model model :scenarios scenarios
   :needs [{:id "UN-001" :text "A bather books a turn."}]
   :statuses {"spec-0001" :verified "spec-0002" :planned}
   :results? true
   :prose {"booking/turns" "Turns are an hour long.\n"}
   :views #{"context" "booking-components"}
   :wiki {"index.md" "# Wiki\n\n- [A](pages/a.md)\n\n[^1]: `loyly:README.md@17d68d2`, \"x\".\n"
          "pages/a.md" "# A page\n\nA claim.[^1]\n\n[^1]: `loyly:docs/x.md@17d68d2`, \"y\".\n"}
   :sources {"loyly" "https://github.com/skallinen/loyly"}})

(deftest pages-follow-the-model
  (let [ps (site/pages input)]
    (is (= #{"index.md" "booking/index.md" "booking/turns.md" "booking/calendar.md" "kernel/index.md" "kernel/cli.md"
             "wiki/index.md" "wiki/pages/a.md"}
           (set (keys ps))))
    (testing "a component page: description, prose, scenarios with id, level, steps and status"
      (let [p (get ps "booking/turns.md")]
        (is (str/includes? p "The turns"))
        (is (str/includes? p "Turns are an hour long."))
        (is (str/includes? p "spec-0001"))
        (is (str/includes? p "Given a sauna"))
        (is (str/includes? p "When a turn is booked"))
        (is (re-find #"Status: verified" p))
        (is (re-find #"Status: planned" p))
        (is (re-find #"Level: e2e" p))))
    (testing "a context page shows its view and links its components"
      (let [p (get ps "booking/index.md")]
        (is (str/includes? p "](../views/booking-components.svg)"))
        (is (str/includes? p "](turns.md)"))
        (is (str/includes? p "](calendar.md)"))))
    (testing "the home page: views, needs with their scenarios, the wiki"
      (let [p (get ps "index.md")]
        (is (str/includes? p "](views/context.svg)"))
        (is (str/includes? p "UN-001"))
        (is (str/includes? p "spec-0001"))
        (is (str/includes? p "](wiki/index.md)"))))))

(deftest status-is-shown-never-decided
  (let [ps (site/pages (assoc input :results? false :statuses nil))]
    (doseq [[rel p] ps]
      (is (str/includes? p "no status was derived because no results were given") rel))
    (is (re-find #"Status: planned" (get ps "booking/turns.md")) "planned whatever the results")))

(deftest footnotes-link-their-sources
  (is (= "[^1]: [`loyly:README.md@17d68d2`](https://github.com/skallinen/loyly/blob/17d68d2/README.md), \"x\"."
         (site/link-footnotes "[^1]: `loyly:README.md@17d68d2`, \"x\"." {"loyly" "https://github.com/skallinen/loyly.git"})))
  (is (= "A claim `loyly:README.md@17d68d2` outside a footnote."
         (site/link-footnotes "A claim `loyly:README.md@17d68d2` outside a footnote." {"loyly" "https://x"})))
  (is (= "[^1]: `other:a.md@abc1234`" (site/link-footnotes "[^1]: `other:a.md@abc1234`" {}))))

(deftest the-navigation
  (let [y (site/mkdocs-yml input (site/pages input) "../site")]
    (is (str/includes? y "site_dir: \"../site\""))
    (is (< (str/index-of y "\"Home\"") (str/index-of y "\"booking\"") (str/index-of y "\"kernel\"")
           (str/index-of y "\"wiki\"")))
    (is (< (str/index-of y "booking/index.md") (str/index-of y "booking/turns.md") (str/index-of y "booking/calendar.md")))))

(deftest broken-links-are-findings
  (let [out (str "INFO    -  Building documentation...\n"
                 "WARNING -  Doc file 'booking/turns.md' contains a link 'missing.md', but the target 'booking/missing.md' is not found among documentation files.\n"
                 "Aborted with 1 warnings in strict mode!\n")
        [f] (site/build-findings "site-src" out)]
    (is (= "site-src/docs/booking/turns.md" (:file f)))
    (is (= "site-link-broken" (:rule f)))
    (is (str/includes? (:message f) "missing.md"))))

(deftest the-same-inputs-give-the-same-site
  (is (= (site/pages input) (site/pages input))))
