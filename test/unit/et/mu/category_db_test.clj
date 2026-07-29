(ns et.mu.category-db-test
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [et.mu.db :as db]
            [et.mu.db.category :as db.category]
            [et.mu.db.video :as db.video]
            [et.mu.test-helpers :refer [*ds* *user-id* with-in-memory-db]]))

(use-fixtures :each with-in-memory-db)

(defn- join-rows []
  (jdbc/execute! (db/get-conn *ds*)
    (sql/format {:select [:video_id :entity_id] :from [:video_entities]})
    db/jdbc-opts))

(defn- post! [title]
  (db.video/add-video *ds* *user-id* {:video-id "dQw4w9WgXcQ" :title title
                                      :note "" :start-seconds nil}))

(defn- entities-of [video-id]
  (:entities (db.video/get-video *ds* video-id {:authed? true})))

(deftest categories-and-entities-are-nested-and-alphabetical
  (let [makers (:id (db.category/add-category *ds* "maker"))
        artists (:id (db.category/add-category *ds* "artist"))]
    (db.category/add-entity *ds* artists "Sufjan Stevens")
    (db.category/add-entity *ds* artists "Caroline Polachek")
    (db.category/add-entity *ds* makers "Roland")
    (let [listed (db.category/list-categories *ds*)]
      (is (= ["artist" "maker"] (map :name listed)))
      (is (= ["Caroline Polachek" "Sufjan Stevens"] (map :name (:entities (first listed)))))
      (is (= ["Roland"] (map :name (:entities (second listed)))))
      (testing "the categories listing is not the place for a category_id"
        (is (= [#{:id :name}] (distinct (map (comp set keys) (:entities (first listed))))))))))

(deftest duplicate-names-are-refused
  (let [artists (:id (db.category/add-category *ds* "artist"))
        makers (:id (db.category/add-category *ds* "maker"))]
    (is (nil? (db.category/add-category *ds* "artist")))
    (is (some? (db.category/add-entity *ds* artists "Roland")))
    (is (nil? (db.category/add-entity *ds* artists "Roland")))
    (testing "a name is only taken within its own category"
      (is (some? (db.category/add-entity *ds* makers "Roland"))))))

(deftest deleting-an-entity-shrinks-the-posts-that-had-it
  (let [category (:id (db.category/add-category *ds* "artist"))
        keeper (:id (db.category/add-entity *ds* category "Roland"))
        doomed (:id (db.category/add-entity *ds* category "Yamaha"))
        video (:id (post! "Two makers"))]
    (db.video/update-video *ds* *user-id* video {:description "" :entity-ids [keeper doomed]})
    (is (= 2 (count (join-rows))))
    (is (= {:success true} (db.category/delete-entity *ds* doomed)))
    (is (= ["Roland"] (map :name (entities-of video))))
    (is (= [{:video_id video :entity_id keeper}] (join-rows)))
    (is (nil? (db.category/delete-entity *ds* doomed)) "already gone")))

(deftest deleting-a-category-takes-its-entities-and-their-assignments
  (let [doomed (:id (db.category/add-category *ds* "maker"))
        keeper (:id (db.category/add-category *ds* "artist"))
        roland (:id (db.category/add-entity *ds* doomed "Roland"))
        yamaha (:id (db.category/add-entity *ds* doomed "Yamaha"))
        polachek (:id (db.category/add-entity *ds* keeper "Caroline Polachek"))
        video (:id (post! "Everything"))]
    (db.video/update-video *ds* *user-id* video
                           {:description "" :entity-ids [roland yamaha polachek]})
    (is (= 3 (count (join-rows))))
    (is (= {:success true} (db.category/delete-category *ds* doomed)))
    (is (= ["artist"] (map :name (db.category/list-categories *ds*))))
    (is (= ["Caroline Polachek"] (map :name (entities-of video))))
    (is (= [{:video_id video :entity_id polachek}] (join-rows)))
    (is (nil? (db.category/delete-category *ds* doomed)) "already gone")))

(deftest deleting-a-video-cleans-its-join-rows
  (let [category (:id (db.category/add-category *ds* "artist"))
        entity (:id (db.category/add-entity *ds* category "Roland"))
        doomed (:id (post! "Doomed"))
        keeper (:id (post! "Keeper"))]
    (db.video/update-video *ds* *user-id* doomed {:description "" :entity-ids [entity]})
    (db.video/update-video *ds* *user-id* keeper {:description "" :entity-ids [entity]})
    (is (= 2 (count (join-rows))))
    (is (= {:success true} (db.video/delete-video *ds* *user-id* doomed)))
    (is (= [{:video_id keeper :entity_id entity}] (join-rows)))))

(deftest reset-all-data-clears-the-annotation-tables-too
  (let [category (:id (db.category/add-category *ds* "artist"))
        entity (:id (db.category/add-entity *ds* category "Roland"))
        video (:id (post! "Gone"))]
    (db.video/update-video *ds* *user-id* video {:description "d" :entity-ids [entity]})
    (db/reset-all-data! *ds*)
    (is (empty? (db.video/list-videos *ds*)))
    (is (empty? (db.category/list-categories *ds*)))
    (is (empty? (join-rows)))))

(deftest set-video-entities-has-set-semantics
  (let [category (:id (db.category/add-category *ds* "artist"))
        entity (:id (db.category/add-entity *ds* category "Roland"))
        video (:id (post! "Repeats"))]
    (db.category/set-video-entities *ds* video [entity entity entity nil "nope"])
    (is (= [{:video_id video :entity_id entity}] (join-rows)))))

(deftest an-id-no-entity-has-is-not-assigned
  (let [category (:id (db.category/add-category *ds* "artist"))
        entity (:id (db.category/add-entity *ds* category "Roland"))
        gone (:id (db.category/add-entity *ds* category "Yamaha"))
        video (:id (post! "Phantoms"))]
    (db.category/delete-entity *ds* gone)
    (db.category/set-video-entities *ds* video [entity gone 4242])
    (is (= [{:video_id video :entity_id entity}] (join-rows)))
    (is (= ["Roland"] (map :name (entities-of video))))
    (testing "so nothing is left for a filter on the phantom to match"
      (is (empty? (db.video/list-videos *ds* {:authed? true :entity-ids [4242]})))
      (is (empty? (db.video/list-videos *ds* {:authed? true :entity-ids [gone]}))))
    (testing "and an id-only write clears what was there"
      (db.category/set-video-entities *ds* video [4242])
      (is (= [] (join-rows))))))
