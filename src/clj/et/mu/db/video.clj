(ns et.mu.db.video
  "The one entity: a posted video.

  A post is a single YouTube video plus an optional note, newest first. Only what
  identifies the video is kept: `video_id` (the 11-character id, resolved from
  whatever URL was pasted) and `start_seconds` (the `t=` offset, when the link
  carried one). Share-tracking cruft like `si=` is dropped by construction — the
  pasted URL itself is never stored. The title comes from YouTube's oEmbed
  endpoint at post time.

  A post is **immutable**: it can be made and it can be deleted, never edited. So
  there is no update fn here, and no use for the `modified_at`
  optimistic-concurrency guard the other plurama apps rely on.

  Nothing is unique here on purpose: posting the same video twice is a legitimate
  thing to do, so it is two posts."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.mu.db :as db]))

(def select-columns [:id :video_id :title :note :start_seconds :created_at])

(defn add-video [ds user-id {:keys [video-id title note start-seconds]}]
  (let [result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:insert-into :videos
                              :values [{:video_id video-id
                                        :title title
                                        :note (or note "")
                                        :start_seconds start-seconds
                                        :user_id user-id}]
                              :returning select-columns})
                 db/jdbc-opts)]
    (tel/log! {:level :info :data {:id (:id result) :video-id video-id :user-id user-id}}
              "Video posted")
    result))

;; Reads are deliberately NOT scoped by user_id. Every post is public and there
;; is one poster, so the feed is the same for everyone — an anonymous visitor
;; must see exactly what the signed-in owner sees. Scoping reads was the bug that
;; made the live feed look empty when logged out: an anonymous request has no
;; user-id, which `user-id-where-clause` renders as `user_id IS NULL`, matching
;; none of the owner's rows. `user_id` is still recorded on insert and still
;; gates deletes.

(defn list-videos
  "Newest post first, optionally narrowed by a substring search over title and
  note. Public: returns every post regardless of who is asking."
  ([ds] (list-videos ds {}))
  ([ds {:keys [search-term]}]
   (let [search-clause (db/build-search-clause search-term [:title :note])]
     (jdbc/execute! (db/get-conn ds)
       (sql/format (cond-> {:select select-columns
                            :from [:videos]
                            :order-by [[:created_at :desc] [:id :desc]]}
                     search-clause (assoc :where search-clause)))
       db/jdbc-opts))))

(defn get-video
  "One post by id. Public, like the listing."
  [ds id]
  (jdbc/execute-one! (db/get-conn ds)
    (sql/format {:select select-columns
                 :from [:videos]
                 :where [:= :id id]})
    db/jdbc-opts))

(defn delete-video [ds user-id id]
  (let [result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:delete-from :videos
                              :where [:and [:= :id id] (db/user-id-where-clause user-id)]}))]
    (when (pos? (:next.jdbc/update-count result))
      (tel/log! {:level :info :data {:id id :user-id user-id}} "Video deleted")
      {:success true})))
