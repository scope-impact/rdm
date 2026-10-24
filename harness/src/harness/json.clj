(ns harness.json
  "JSON the harness writes: keys sorted and two-space indentation, so the same
  input gives the same bytes."
  (:require [cheshire.core :as json]
            [clojure.walk :as walk]))

(defn- sorted [x]
  (walk/postwalk #(if (map? %) (into (sorted-map) %) %) x))

(defn write-str [x] (str (json/generate-string (sorted x) {:pretty true}) "\n"))

(defn read-file [path] (json/parse-string (slurp path) true))
