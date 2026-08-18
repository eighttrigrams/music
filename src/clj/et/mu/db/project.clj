(ns et.mu.db.project
  "A project: a note the owner keeps — a title, a markdown body, and optionally
  the URL of one audio file to play beside it.

  Nothing else in music is like this. A post is public and immutable; a project
  is **private and editable**, and both halves of that sentence show up here.

  Private, so unlike `et.mu.db.video` the reads are scoped by `user_id` — the
  same clause the writes use. That is not a repeat of the bug the feed had (a
  public listing scoped by a user-id an anonymous request does not have, which
  rendered as `user_id IS NULL` and matched nothing): here an anonymous caller
  is turned away in the handler and never reaches this namespace at all, so the
  clause only ever runs with the owner's id.

  Editable, so `modified_at` finally earns the optimistic-concurrency guard the
  other plurama apps rely on and music has so far had no use for: pass
  `expected-modified-at` and the update matches only while the stored timestamp
  is the one that was read. Second granularity is all SQLite's `datetime('now')`
  gives, so two saves inside the same second cannot be told apart — the same
  bound tracker's guard has."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.mu.db :as db]))

(def select-columns [:id :title :body :audio_url :created_at :modified_at])

(def ^:private now [:datetime "now"])

(defn- owned
  "id + owner, and optionally the modified_at the caller read. Without that last
  clause a write is last-write-wins; with it, a save that lost a race matches no
  row and the handler can say so instead of overwriting prose it never saw."
  ([id user-id] (owned id user-id nil))
  ([id user-id expected-modified-at]
   (cond-> [:and [:= :id id] (db/user-id-where-clause user-id)]
     expected-modified-at (conj [:= :modified_at expected-modified-at]))))

(defn list-projects
  "The owner's projects, newest first."
  [ds user-id]
  (jdbc/execute! (db/get-conn ds)
    (sql/format {:select select-columns
                 :from [:projects]
                 :where (db/user-id-where-clause user-id)
                 :order-by [[:created_at :desc] [:id :desc]]})
    db/jdbc-opts))

(defn get-project
  "One project of the owner's. nil for an id that is somebody else's, which is
  what lets the handler answer it exactly as it answers an id that is nothing."
  [ds user-id id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select select-columns
                 :from [:projects]
                 :where (owned id user-id)})
    db/jdbc-opts))

(defn add-project [ds user-id {:keys [title body audio_url]}]
  (let [result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:insert-into :projects
                              :values [{:title title
                                        :body (or body "")
                                        :audio_url (or audio_url "")
                                        :user_id user-id}]
                              :returning select-columns})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:id (:id result) :user-id user-id}} "Project created")
    result))

(defn update-project
  "Write the fields the caller actually sent, and stamp `modified_at`. A field
  left out keeps what it had, so an edit meant for the body cannot silently
  clear the title. nil when nothing matched — either the id is not the caller's,
  or `expected-modified-at` says somebody saved in between; `get-project` is
  what tells those two apart.

  A call with no fields at all writes nothing: honeysql renders an empty `:set`
  as `UPDATE projects SET WHERE ...`, a syntax error rather than the no-op it
  looks like. The handler refuses that request before it reaches here — a bare
  timestamp bump is not something the API offers."
  ([ds user-id id fields] (update-project ds user-id id fields nil))
  ([ds user-id id fields expected-modified-at]
   (when (seq fields)
     (let [result (jdbc/execute-one! (db/get-conn ds)
                    (sql/format {:update :projects
                                 :set (assoc fields :modified_at now)
                                 :where (owned id user-id expected-modified-at)
                                 :returning select-columns})
                    db/jdbc-opts)]
       (when result
         (tel/log! {:level :info :data {:id id :user-id user-id}} "Project saved"))
       result))))

(defn delete-project [ds user-id id]
  (let [result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:delete-from :projects :where (owned id user-id)}))]
    (when (pos? (:next.jdbc/update-count result))
      (tel/log! {:level :info :data {:id id :user-id user-id}} "Project deleted")
      {:success true})))
