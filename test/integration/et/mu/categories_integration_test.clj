(ns et.mu.categories-integration-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.mu.db.video :as db.video]
            [et.mu.integration-helpers :refer [with-integration-db *ds* API
                                               GET-json POST-json PUT-json DELETE-json]]))

(use-fixtures :each with-integration-db)

(defn- category! [name]
  (:body (POST-json "/api/categories" {:name name})))

(defn- entity! [category-id name]
  (:body (POST-json (str "/api/categories/" category-id "/entities") {:name name})))

(defn- post! [title]
  (:body (POST-json "/api/videos" {:input "dQw4w9WgXcQ" :title title})))

(defn- entity-names [video-id]
  (map :name (:entities (db.video/get-video *ds* video-id {:authed? true}))))

(deftest category-crud
  (let [created (POST-json "/api/categories" {:name "artist"})]
    (is (= 201 (:status created)))
    (is (= "artist" (:name (:body created))))
    (testing "listed with an entities key from the start"
      (let [listed (GET-json "/api/categories")]
        (is (= 200 (:status listed)))
        (is (= [{:id (:id (:body created)) :name "artist" :entities []}] (:body listed)))))
    (testing "a duplicate or blank name is a 400"
      (is (= 400 (:status (POST-json "/api/categories" {:name "artist"}))))
      (is (= 400 (:status (POST-json "/api/categories" {:name "   "}))))
      (is (= 400 (:status (POST-json "/api/categories" {})))))
    (testing "so is a name that is not a string"
      (is (= 400 (:status (POST-json "/api/categories" {:name {:a 1}}))))
      (is (= 400 (:status (POST-json "/api/categories" {:name 42}))))
      (is (= 400 (:status (POST-json "/api/categories" {:name ["a"]}))))
      (is (= ["artist"] (map :name (:body (GET-json "/api/categories"))))))
    (testing "delete answers 404 the second time"
      (is (= 200 (:status (DELETE-json (str "/api/categories/" (:id (:body created)))))))
      (is (= 404 (:status (DELETE-json (str "/api/categories/" (:id (:body created)))))))
      (is (= [] (:body (GET-json "/api/categories")))))))

(deftest entity-crud
  (let [category (category! "artist")
        created (POST-json (str "/api/categories/" (:id category) "/entities")
                           {:name "Caroline Polachek"})]
    (is (= 201 (:status created)))
    (is (= {:id (:id (:body created)) :name "Caroline Polachek" :category_id (:id category)}
           (:body created)))
    (testing "duplicate within the category, blank name, unknown category"
      (is (= 400 (:status (POST-json (str "/api/categories/" (:id category) "/entities")
                                     {:name "Caroline Polachek"}))))
      (is (= 400 (:status (POST-json (str "/api/categories/" (:id category) "/entities")
                                     {:name ""}))))
      (is (= 400 (:status (POST-json (str "/api/categories/" (:id category) "/entities")
                                     {:name {:a 1}}))))
      (is (= 404 (:status (POST-json "/api/categories/9999/entities" {:name "Nobody"})))))
    (testing "the same name is free inside another category"
      (let [other (category! "band")]
        (is (= 201 (:status (POST-json (str "/api/categories/" (:id other) "/entities")
                                       {:name "Caroline Polachek"}))))))
    (testing "delete answers 404 the second time"
      (is (= 200 (:status (DELETE-json (str "/api/entities/" (:id (:body created)))))))
      (is (= 404 (:status (DELETE-json (str "/api/entities/" (:id (:body created))))))))))

(deftest put-replaces-description-and-the-whole-entity-set
  (let [category (category! "artist")
        polachek (entity! (:id category) "Caroline Polachek")
        stevens (entity! (:id category) "Sufjan Stevens")
        video (post! "A post")
        first-put (PUT-json (str "/api/videos/" (:id video))
                            {:description "first" :entity-ids [(:id polachek) (:id stevens)]})]
    (is (= 200 (:status first-put)))
    (is (= "first" (:description (:body first-put))))
    (is (= #{"Caroline Polachek" "Sufjan Stevens"}
           (set (map :name (:entities (:body first-put))))))
    (testing "the post itself is untouched"
      (is (= "A post" (:title (:body first-put))))
      (is (= (:video_id video) (:video_id (:body first-put)))))
    (testing "a second put replaces both wholesale"
      (let [second-put (PUT-json (str "/api/videos/" (:id video))
                                 {:description "second" :entity-ids [(:id stevens)]})]
        (is (= "second" (:description (:body second-put))))
        (is (= ["Sufjan Stevens"] (map :name (:entities (:body second-put)))))
        (is (= ["Sufjan Stevens"] (entity-names (:id video))))))
    (testing "an empty set clears the assignments and the description"
      (let [cleared (PUT-json (str "/api/videos/" (:id video))
                              {:description "" :entity-ids []})]
        (is (= "" (:description (:body cleared))))
        (is (= [] (:entities (:body cleared))))))
    (testing "404 on an unknown video"
      (is (= 404 (:status (PUT-json "/api/videos/9999"
                                    {:description "x" :entity-ids []})))))))

(deftest put-drops-entity-ids-that-name-nothing
  (let [category (category! "artist")
        entity (entity! (:id category) "Roland")
        video (post! "Phantom")
        response (PUT-json (str "/api/videos/" (:id video))
                           {:description "" :entity-ids [(:id entity) 4242]})]
    (is (= 200 (:status response)))
    (is (= ["Roland"] (map :name (:entities (:body response)))))
    (testing "the phantom row never landed, so it cannot answer a filter either"
      (is (= [] (:body (GET-json "/api/videos?entities=4242")))))))

(deftest put-rejects-entity-ids-that-are-not-a-list
  (let [category (category! "artist")
        entity (entity! (:id category) "Roland")
        video (post! "Annotated")]
    (PUT-json (str "/api/videos/" (:id video))
              {:description "kept" :entity-ids [(:id entity)]})
    (doseq [bad-shape [(:id entity) (str (:id entity)) {:id (:id entity)}]]
      (testing (str "entity-ids as " (pr-str bad-shape))
        (let [response (PUT-json (str "/api/videos/" (:id video))
                                 {:description "clobbered" :entity-ids bad-shape})]
          (is (= 400 (:status response)))
          (is (= "entity-ids must be a list of ids" (:error (:body response))))))
      (testing "the annotation layer is left as it was"
        (is (= "kept" (:description (db.video/get-video *ds* (:id video) {:authed? true}))))
        (is (= ["Roland"] (entity-names (:id video))))))))

(deftest put-is-scoped-by-user
  (let [video (post! "Mine")
        stolen (API :put (str "/api/videos/" (:id video))
                    {:as-user 9999 :body {:description "yours" :entity-ids []}})]
    (is (= 404 (:status stolen)))
    (is (= "" (:description (db.video/get-video *ds* (:id video) {:authed? true}))))))

(deftest deleting-a-video-cleans-its-assignments
  (let [category (category! "artist")
        entity (entity! (:id category) "Roland")
        video (post! "Doomed")
        filtered #(db.video/list-videos *ds* {:authed? true :entity-ids [(:id entity)]})]
    (PUT-json (str "/api/videos/" (:id video)) {:description "" :entity-ids [(:id entity)]})
    (is (= 1 (count (filtered))))
    (is (= 200 (:status (DELETE-json (str "/api/videos/" (:id video))))))
    (is (= [] (filtered)) "the join row went with the post")))

(deftest deleting-through-the-api-shrinks-a-posts-entities
  (let [artists (category! "artist")
        makers (category! "maker")
        polachek (entity! (:id artists) "Caroline Polachek")
        roland (entity! (:id makers) "Roland")
        video (post! "Both")]
    (PUT-json (str "/api/videos/" (:id video))
              {:description "" :entity-ids [(:id polachek) (:id roland)]})
    (is (= #{"Caroline Polachek" "Roland"} (set (entity-names (:id video)))))
    (DELETE-json (str "/api/entities/" (:id polachek)))
    (is (= ["Roland"] (entity-names (:id video))))
    (DELETE-json (str "/api/categories/" (:id makers)))
    (is (= [] (entity-names (:id video))))
    (is (= ["artist"] (map :name (:body (GET-json "/api/categories")))))))

(deftest describe-lists-the-new-routes
  (let [paths (->> (:body (GET-json "/api/describe"))
                   (map (juxt :method :path))
                   set)]
    (is (contains? paths ["GET" "/api/categories"]))
    (is (contains? paths ["POST" "/api/categories"]))
    (is (contains? paths ["DELETE" "/api/categories/:id"]))
    (is (contains? paths ["POST" "/api/categories/:id/entities"]))
    (is (contains? paths ["DELETE" "/api/entities/:id"]))
    (is (contains? paths ["PUT" "/api/videos/:id"]))))
