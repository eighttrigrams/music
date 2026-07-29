(ns et.mu.db.category
  "The owner's annotation vocabulary, and the join that hangs it off a post.

  A category (\"artist\", \"manufacturer\") holds entities (\"Caroline Polachek\",
  \"Roland\"); `video_entities` assigns any number of entities to a post. None of
  this is public — it exists for the signed-in owner alone, which is why the
  visibility decision lives one layer up, in the handlers and in
  `et.mu.db.video`.

  Names are unique per scope: category names globally, entity names within their
  category. The add fns answer nil rather than throwing when a name is taken.

  Foreign keys are not enforced on this connection, so ON DELETE CASCADE would be
  a promise nothing keeps: every delete fn clears the join rows it orphans, and a
  write only ever assigns entities that exist."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.mu.db :as db]))

(defn get-category [ds id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select [:id :name]
                 :from [:categories]
                 :where [:= :id id]})
    db/jdbc-opts))

(defn list-categories
  "Every category with its entities nested, both alphabetical."
  [ds]
  (let [conn (db/get-conn ds)
        categories (jdbc/execute! conn
                     (sql/format {:select [:id :name]
                                  :from [:categories]
                                  :order-by [[[:lower :name] :asc]]})
                     db/jdbc-opts)
        by-category (->> (jdbc/execute! conn
                           (sql/format {:select [:id :name :category_id]
                                        :from [:entities]
                                        :order-by [[[:lower :name] :asc]]})
                           db/jdbc-opts)
                         (group-by :category_id))]
    (mapv (fn [{:keys [id] :as category}]
            (assoc category :entities (mapv #(dissoc % :category_id) (get by-category id))))
          categories)))

(defn add-category
  "nil when the name is already taken."
  [ds name]
  (let [conn (db/get-conn ds)]
    (when-not (jdbc/execute-one! conn
                (sql/format {:select [:id] :from [:categories]
                             :where [:= [:lower :name] [:lower name]]})
                db/jdbc-opts)
      (let [result (jdbc/execute-one! conn
                     (sql/format {:insert-into :categories
                                  :values [{:name name}]
                                  :returning [:id :name]})
                     db/jdbc-opts)]
        (tel/log! {:level :info :data {:id (:id result) :name name}} "Category created")
        (assoc result :entities [])))))

(defn add-entity
  "nil when the name is already taken inside that category."
  [ds category-id name]
  (let [conn (db/get-conn ds)]
    (when-not (jdbc/execute-one! conn
                (sql/format {:select [:id] :from [:entities]
                             :where [:and [:= :category_id category-id]
                                     [:= [:lower :name] [:lower name]]]})
                db/jdbc-opts)
      (let [result (jdbc/execute-one! conn
                     (sql/format {:insert-into :entities
                                  :values [{:name name :category_id category-id}]
                                  :returning [:id :name :category_id]})
                     db/jdbc-opts)]
        (tel/log! {:level :info :data {:id (:id result) :name name :category-id category-id}}
                  "Entity created")
        result))))

(defn delete-entity
  "Remove an entity and every assignment it had."
  [ds id]
  (let [conn (db/get-conn ds)]
    (jdbc/execute-one! conn (sql/format {:delete-from :video_entities
                                         :where [:= :entity_id id]}))
    (let [result (jdbc/execute-one! conn (sql/format {:delete-from :entities
                                                      :where [:= :id id]}))]
      (when (pos? (:next.jdbc/update-count result))
        (tel/log! {:level :info :data {:id id}} "Entity deleted")
        {:success true}))))

(defn delete-category
  "Remove a category, the entities under it, and every assignment those entities
  had."
  [ds id]
  (let [conn (db/get-conn ds)
        entity-ids (->> (jdbc/execute! conn
                          (sql/format {:select [:id] :from [:entities]
                                       :where [:= :category_id id]})
                          db/jdbc-opts)
                        (map :id))]
    (when (seq entity-ids)
      (jdbc/execute-one! conn (sql/format {:delete-from :video_entities
                                           :where [:in :entity_id entity-ids]})))
    (jdbc/execute-one! conn (sql/format {:delete-from :entities
                                         :where [:= :category_id id]}))
    (let [result (jdbc/execute-one! conn (sql/format {:delete-from :categories
                                                      :where [:= :id id]}))]
      (when (pos? (:next.jdbc/update-count result))
        (tel/log! {:level :info :data {:id id :entities (count entity-ids)}} "Category deleted")
        {:success true}))))

(defn clear-video-entities
  "Drop every assignment a post had — the cleanup a video delete owes."
  [ds video-id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:delete-from :video_entities :where [:= :video_id video-id]})))

(defn- existing-entity-ids
  "Which of `ids` name an entity that is actually there. One query: the schema
  keeps its own referential integrity because SQLite will not, and that obligation
  is the writer's too — a join row for an id nobody can see would still answer a
  filter for it."
  [conn ids]
  (into #{}
        (map :id)
        (jdbc/execute! conn
          (sql/format {:select [:id] :from [:entities] :where [:in :id ids]})
          db/jdbc-opts)))

(defn set-video-entities
  "Replace a post's assignments wholesale. Set semantics: repeats collapse, and
  anything that is not the id of an entity that exists is ignored."
  [ds video-id entity-ids]
  (let [conn (db/get-conn ds)
        candidates (vec (distinct (filterv int? entity-ids)))
        ids (when (seq candidates)
              (filterv (existing-entity-ids conn candidates) candidates))]
    (clear-video-entities ds video-id)
    (when (seq ids)
      (jdbc/execute-one! conn
        (sql/format {:insert-into :video_entities
                     :values (mapv (fn [entity-id] {:video_id video-id :entity_id entity-id}) ids)})))))

(defn entities-by-video
  "The assigned entities for many posts at once, keyed by post id. One query, so
  rendering the feed never goes N+1."
  [ds video-ids]
  (if (empty? video-ids)
    {}
    (->> (jdbc/execute! (db/get-conn ds)
           (sql/format {:select [[:ve.video_id :video_id] [:e.id :id]
                                 [:e.name :name] [:e.category_id :category_id]]
                        :from [[:video_entities :ve]]
                        :join [[:entities :e] [:= :e.id :ve.entity_id]]
                        :where [:in :ve.video_id video-ids]
                        :order-by [[[:lower :e.name] :asc]]})
           db/jdbc-opts)
         (group-by :video_id)
         (reduce-kv (fn [acc video-id rows]
                      (assoc acc video-id (mapv #(dissoc % :video_id) rows)))
                    {}))))

(defn video-filter-clause
  "A where fragment narrowing `videos.id` to the posts assigned to ANY of
  `entity-ids` — nil when there is nothing to narrow by."
  [entity-ids]
  (when (seq entity-ids)
    [:in :id {:select-distinct [:video_id]
              :from [:video_entities]
              :where [:in :entity_id entity-ids]}]))
