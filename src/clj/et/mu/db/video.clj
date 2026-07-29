(ns et.mu.db.video
  "The one entity: a posted video.

  A post is a single YouTube video plus an optional note, newest first. Only what
  identifies the video is kept: `video_id` (the 11-character id, resolved from
  whatever URL was pasted) and `start_seconds` (the `t=` offset, when the link
  carried one). Share-tracking cruft like `si=` is dropped by construction — the
  pasted URL itself is never stored. The title comes from YouTube's oEmbed
  endpoint at post time.

  The **post is immutable**: title, video, note and start time can be made and
  deleted, never edited. `update-video` is not a counter-example — it writes only
  the owner's annotation layer (`description` and the entity assignments in
  `et.mu.db.category`), which sits beside the post rather than in it. So there is
  still no use for the `modified_at` optimistic-concurrency guard the other
  plurama apps rely on.

  That annotation layer is private to the signed-in owner, and this is where the
  visibility rule is kept honest: the reads take an `authed?` flag and the
  columns they select depend on it, so an anonymous response does not carry
  `:description` or `:entities` at all — there is nothing for a client to hide.

  Nothing is unique here on purpose: posting the same video twice is a legitimate
  thing to do, so it is two posts."
  (:require [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]
            [et.mu.db :as db]
            [et.mu.db.category :as db.category]))

(def select-columns [:id :video_id :title :note :start_seconds :created_at])

(def ^:private authed-select-columns (conj select-columns :description))

(defn- with-entities [ds videos]
  (let [by-video (db.category/entities-by-video ds (map :id videos))]
    (mapv (fn [{:keys [id] :as video}] (assoc video :entities (get by-video id []))) videos)))

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
  note and by an entity filter (any of `entity-ids`, ANDed with the search).
  Public: returns every post regardless of who is asking. `authed?` decides
  whether the annotation layer is part of the shape at all."
  ([ds] (list-videos ds {}))
  ([ds {:keys [search-term entity-ids authed?]}]
   (let [clauses (remove nil? [(db/build-search-clause search-term [:title :note])
                               (db.category/video-filter-clause entity-ids)])
         videos (jdbc/execute! (db/get-conn ds)
                  (sql/format (cond-> {:select (if authed? authed-select-columns select-columns)
                                       :from [:videos]
                                       :order-by [[:created_at :desc] [:id :desc]]}
                                (seq clauses) (assoc :where (into [:and] clauses))))
                  db/jdbc-opts)]
     (if authed? (with-entities ds videos) videos))))

(defn get-video
  "One post by id. Public, like the listing — and like the listing, only an
  `authed?` read carries the annotation layer."
  ([ds id] (get-video ds id {}))
  ([ds id {:keys [authed?]}]
   (let [video (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:select (if authed? authed-select-columns select-columns)
                              :from [:videos]
                              :where [:= :id id]})
                 db/jdbc-opts)]
     (cond
       (nil? video) nil
       authed? (first (with-entities ds [video]))
       :else video))))

(defn update-video
  "Replace the post's annotation layer: the description and the whole entity set,
  both wholesale. The post itself is untouched — title, video, note and start
  time stay immutable. Scoped by user_id like `delete-video`; nil when the id
  matches nothing the user owns."
  [ds user-id id {:keys [description entity-ids]}]
  (let [result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:update :videos
                              :set {:description (or description "")}
                              :where [:and [:= :id id] (db/user-id-where-clause user-id)]}))]
    (when (pos? (:next.jdbc/update-count result))
      (db.category/set-video-entities ds id entity-ids)
      (tel/log! {:level :info :data {:id id :user-id user-id :entities (count entity-ids)}}
                "Video annotated")
      (get-video ds id {:authed? true}))))

(defn delete-video [ds user-id id]
  (let [result (jdbc/execute-one! (db/get-conn ds)
                 (sql/format {:delete-from :videos
                              :where [:and [:= :id id] (db/user-id-where-clause user-id)]}))]
    (when (pos? (:next.jdbc/update-count result))
      (db.category/clear-video-entities ds id)
      (tel/log! {:level :info :data {:id id :user-id user-id}} "Video deleted")
      {:success true})))
