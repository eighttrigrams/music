(ns et.mu.visibility-integration-test
  "Where the line runs. The vocabulary and the filter made of it are public —
  anyone may read the categories and narrow the feed by an entity. The annotation
  layer *on a post* is the owner's, and the server is what enforces that: an
  anonymous response must not contain the data at all, so there is nothing a
  client could be trusted (or fail) to hide. Hence the deliberate asymmetry — a
  visitor can filter by an entity yet is never told which posts carry it."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.mu.auth :as auth]
            [et.mu.db.category :as db.category]
            [et.mu.db.video :as db.video]
            [et.mu.integration-helpers :refer [with-integration-db with-real-auth
                                               with-prod-app *ds* *user-id* API token-for]]))

(use-fixtures :each with-integration-db)

(defn- seed! []
  (let [category (db.category/add-category *ds* "artist")
        entity (db.category/add-entity *ds* (:id category) "Caroline Polachek")
        unassigned (db.category/add-entity *ds* (:id category) "Sufjan Stevens")
        video (db.video/add-video *ds* *user-id* {:video-id "dQw4w9WgXcQ" :title "Bunny Is a Rider"
                                                 :note "public note" :start-seconds nil})]
    (db.video/update-video *ds* *user-id* (:id video)
                           {:description "private description" :entity-ids [(:id entity)]})
    {:entity-id (:id entity) :unassigned-id (:id unassigned) :video-id (:id video)}))

(deftest anonymous-responses-carry-no-annotation-layer
  (with-real-auth
    (let [{:keys [video-id]} (seed!)
          listed (API :get "/api/videos" {:anonymous? true})
          one (API :get (str "/api/videos/" video-id) {:anonymous? true})]
      (is (= 200 (:status listed)))
      (is (= 1 (count (:body listed))))
      (doseq [video (conj (:body listed) (:body one))]
        (is (not (contains? video :description)))
        (is (not (contains? video :entities)))
        (testing "the post itself is public as ever"
          (is (= "Bunny Is a Rider" (:title video)))
          (is (= "public note" (:note video))))))))

(deftest authenticated-responses-carry-it
  (with-real-auth
    (let [{:keys [video-id]} (seed!)
          token (token-for *user-id*)
          listed (API :get "/api/videos" {:token token})
          one (API :get (str "/api/videos/" video-id) {:token token})]
      (doseq [video (conj (:body listed) (:body one))]
        (is (= "private description" (:description video)))
        (is (= ["Caroline Polachek"] (map :name (:entities video))))))))

(deftest a-machine-token-verifies-like-any-other-and-sees-everything
  (with-real-auth
    (seed!)
    (let [token (auth/create-machine-token *user-id* "agent")
          listed (API :get "/api/videos" {:token token})]
      (is (= "private description" (:description (first (:body listed))))))))

(deftest the-vocabulary-is-public
  (with-real-auth
    (seed!)
    (let [listed (API :get "/api/categories" {:anonymous? true})]
      (is (= 200 (:status listed)))
      (is (= ["artist"] (map :name (:body listed))))
      (is (= ["Caroline Polachek" "Sufjan Stevens"]
             (mapcat #(map :name (:entities %)) (:body listed)))
          "with its entities nested, or there would be nothing to check in the filter")
      (is (= (:body listed) (:body (API :get "/api/categories" {:token (token-for *user-id*)})))
          "the same vocabulary either way"))))

(deftest the-entity-filter-is-public
  (with-real-auth
    (let [{:keys [entity-id unassigned-id]} (seed!)
          filter-by (fn [query] (API :get (str "/api/videos" query) {:anonymous? true}))
          filtered (filter-by (str "?entities=" entity-id))]
      (is (= 200 (:status filtered)))
      (is (= ["Bunny Is a Rider"] (map :title (:body filtered))))
      (testing "an entity nothing is assigned to narrows to nothing"
        (is (= [] (:body (filter-by (str "?entities=" unassigned-id))))))
      (testing "an empty param is no filter, as for the owner"
        (is (= 1 (count (:body (filter-by "?entities="))))))
      (testing "ANDed with the search, as for the owner"
        (is (= ["Bunny Is a Rider"]
               (map :title (:body (filter-by (str "?entities=" entity-id "&search=bunny"))))))
        (is (= [] (:body (filter-by (str "?entities=" entity-id "&search=chicago"))))))
      (testing "and what the filter hands back still carries no annotation layer"
        (let [video (first (:body filtered))]
          (is (not (contains? video :description)))
          (is (not (contains? video :entities))))))))

(deftest the-mutating-category-routes-are-gated-by-wrap-auth
  (with-prod-app
    (is (= 401 (:status (API :post "/api/categories" {:anonymous? true :body {:name "artist"}}))))
    (is (= 401 (:status (API :post "/api/categories" {:body {:name "artist"}})))
        "the dev skip-logins header buys nothing once the wrapper is active")
    (is (= [] (:body (API :get "/api/categories" {:token (token-for *user-id*)})))
        "neither 401 wrote anything")
    (let [created (API :post "/api/categories" {:token (token-for *user-id*)
                                                :body {:name "artist"}})]
      (is (= 201 (:status created)))
      (is (= "artist" (:name (:body created)))))))

(deftest dev-skip-logins-counts-as-the-owner
  (let [{:keys [video-id]} (seed!)]
    (testing "no token anywhere, yet the responses come back in the owner's shape"
      (is (= "private description" (:description (first (:body (API :get "/api/videos" {}))))))
      (is (= ["Caroline Polachek"]
             (map :name (:entities (:body (API :get (str "/api/videos/" video-id) {})))))))))
