(ns et.mu.visibility-integration-test
  "The annotation layer is the owner's. The server is what enforces that: an
  anonymous response must not contain the data at all, so there is nothing a
  client could be trusted (or fail) to hide."
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
        video (db.video/add-video *ds* *user-id* {:video-id "dQw4w9WgXcQ" :title "Bunny Is a Rider"
                                                 :note "public note" :start-seconds nil})]
    (db.video/update-video *ds* *user-id* (:id video)
                           {:description "private description" :entity-ids [(:id entity)]})
    {:entity-id (:id entity) :video-id (:id video)}))

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

(deftest categories-are-owner-only
  (with-real-auth
    (seed!)
    (is (= 401 (:status (API :get "/api/categories" {:anonymous? true}))))
    (is (= 200 (:status (API :get "/api/categories" {:token (token-for *user-id*)}))))))

(deftest the-entity-filter-is-owner-only
  (with-real-auth
    (let [{:keys [entity-id]} (seed!)]
      (is (= 401 (:status (API :get (str "/api/videos?entities=" entity-id) {:anonymous? true}))))
      (is (= 401 (:status (API :get "/api/videos?entities=" {:anonymous? true})))
          "the param is the owner-only capability, empty or not")
      (is (= 200 (:status (API :get (str "/api/videos?entities=" entity-id)
                               {:token (token-for *user-id*)}))))
      (testing "a plain search stays public"
        (is (= 200 (:status (API :get "/api/videos?search=bunny" {:anonymous? true}))))))))

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
  (seed!)
  (is (= "private description" (:description (first (:body (API :get "/api/videos" {}))))))
  (is (= 200 (:status (API :get "/api/categories" {})))))
