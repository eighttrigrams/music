(ns et.mu.entity-filter-integration-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.mu.integration-helpers :refer [with-integration-db
                                               GET-json POST-json PUT-json]]))

(use-fixtures :each with-integration-db)

(defn- entity! [category-id name]
  (:id (:body (POST-json (str "/api/categories/" category-id "/entities") {:name name}))))

(defn- post! [title note]
  (:id (:body (POST-json "/api/videos" {:input "dQw4w9WgXcQ" :title title :note note}))))

(defn- titles [query]
  (set (map :title (:body (GET-json (str "/api/videos" query))))))

(defn- fixture []
  (let [category (:id (:body (POST-json "/api/categories" {:name "artist"})))
        polachek (entity! category "Caroline Polachek")
        stevens (entity! category "Sufjan Stevens")
        roland (entity! category "Roland")
        hers (post! "Bunny Is a Rider" "her track")
        his (post! "Chicago" "his track")
        both (post! "A collaboration" "both of them")
        neither (post! "Unassigned" "nobody")]
    (PUT-json (str "/api/videos/" hers) {:description "" :entity-ids [polachek]})
    (PUT-json (str "/api/videos/" his) {:description "" :entity-ids [stevens]})
    (PUT-json (str "/api/videos/" both) {:description "" :entity-ids [polachek stevens]})
    {:polachek polachek :stevens stevens :roland roland :neither neither}))

(deftest entities-are-ored-together
  (let [{:keys [polachek stevens roland]} (fixture)]
    (is (= #{"Bunny Is a Rider" "A collaboration"} (titles (str "?entities=" polachek))))
    (is (= #{"Chicago" "A collaboration"} (titles (str "?entities=" stevens))))
    (is (= #{"Bunny Is a Rider" "Chicago" "A collaboration"}
           (titles (str "?entities=" polachek "," stevens))))
    (testing "an entity nothing is assigned to narrows to nothing"
      (is (= #{} (titles (str "?entities=" roland)))))
    (testing "no param at all is no filter"
      (is (= 4 (count (titles "")))))))

(deftest the-filter-and-the-search-are-anded
  (let [{:keys [polachek stevens]} (fixture)]
    (is (= #{"A collaboration"} (titles (str "?entities=" polachek "&search=collaboration"))))
    (is (= #{"A collaboration"} (titles (str "?entities=" polachek "," stevens
                                             "&search=both"))))
    (testing "a search that only matches outside the filter finds nothing"
      (is (= #{} (titles (str "?entities=" polachek "&search=unassigned")))))
    (testing "the search still reaches the note"
      (is (= #{"Bunny Is a Rider"} (titles (str "?entities=" polachek "&search=her%20track")))))))

(deftest unparseable-ids-are-ignored
  (let [{:keys [polachek]} (fixture)]
    (is (= #{"Bunny Is a Rider" "A collaboration"}
           (titles (str "?entities=" polachek ",zzz,,"))))
    (testing "an effectively empty param narrows nothing"
      (is (= 4 (count (titles "?entities="))))
      (is (= 4 (count (titles "?entities=zzz")))))))

(deftest an-unassigned-post-is-reachable-only-without-a-filter
  (let [{:keys [polachek stevens]} (fixture)]
    (is (contains? (titles "") "Unassigned"))
    (is (not (contains? (titles (str "?entities=" polachek "," stevens)) "Unassigned")))))
