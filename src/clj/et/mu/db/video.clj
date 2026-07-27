(ns et.mu.db.video
  "The one entity: a posted video.

  A post is a single YouTube video plus an optional note, newest first. The
  `video_id` is the 11-character YouTube id, resolved from whatever URL was
  pasted; the title comes from YouTube's oEmbed endpoint at post time and can be
  edited afterwards like the note.

  Nothing is unique here on purpose: posting the same video twice is a legitimate
  thing to do, so it is two posts."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.mu.db :as db]))

(def select-columns [:id :video_id :title :note :created_at :modified_at])

(defn add-video [ds user-id {:keys [video-id title note]}]
  (let [result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:insert-into :videos
                              :values [{:video_id video-id
                                        :title title
                                        :note (or note "")
                                        :user_id user-id
                                        :modified_at [:raw "datetime('now')"]}]
                              :returning select-columns})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:id (:id result) :video-id video-id :user-id user-id}}
              "Video posted")
    result))

(defn list-videos
  "Newest post first, optionally narrowed by a substring search over title and
  note."
  ([ds user-id] (list-videos ds user-id {}))
  ([ds user-id {:keys [search-term]}]
   (let [search-clause (db/build-search-clause search-term [:title :note])
         where-clause (into [:and (db/user-id-where-clause user-id)]
                            (filter some? [search-clause]))]
     (jdbc/execute! (db/get-conn ds)
       (sql/format {:select select-columns
                    :from [:videos]
                    :where where-clause
                    :order-by [[:created_at :desc] [:id :desc]]})
       db/jdbc-opts))))

(defn get-video [ds user-id id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select select-columns
                 :from [:videos]
                 :where [:and [:= :id id] (db/user-id-where-clause user-id)]})
    db/jdbc-opts))

(defn update-video
  "Set title and/or note. Returns nil when the row is gone or was changed
  elsewhere (optimistic concurrency on modified_at)."
  ([ds user-id id fields] (update-video ds user-id id fields nil))
  ([ds user-id id fields expected-modified-at]
   (jdbc/execute-one! (db/get-conn ds)
     (sql/format {:update :videos
                  :set (assoc fields :modified_at [:raw "datetime('now')"])
                  :where (db/update-where id user-id expected-modified-at)
                  :returning select-columns})
     db/jdbc-opts)))

(defn delete-video [ds user-id id]
  (let [result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:delete-from :videos
                              :where [:and [:= :id id] (db/user-id-where-clause user-id)]}))]
    (when (pos? (:next.jdbc/update-count result))
      (tel/log! {:level :info :data {:id id :user-id user-id}} "Video deleted")
      {:success true})))
