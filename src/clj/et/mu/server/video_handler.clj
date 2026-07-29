(ns et.mu.server.video-handler
  (:require [clojure.string :as str]
            [et.mu.server.common :as common]
            [et.mu.db.video :as db.video]
            [et.mu.youtube :as youtube]))

(defn- parse-entity-ids
  "`1,2,5`. Tokens that are not ids are ignored, so an effectively empty param
  narrows nothing."
  [param]
  (when param
    (->> (str/split param #",")
         (keep common/parse-int-opt)
         distinct
         vec)))

(defn list-videos-handler
  "GET /api/videos — the posted videos, newest first, optionally filtered by
  ?search over title and note and by ?entities=1,2,5 (posts assigned to any of
  them, ANDed with the search). Public: the same feed whether or not you are
  signed in, except that an authenticated response also carries each post's
  :description and :entities. ?entities is part of that owner-only layer, so an
  anonymous request using it gets 401."
  [req]
  (let [authed? (common/authenticated? req)
        entities-param (common/query-param req "entities")]
    (if (and (some? entities-param) (not authed?))
      common/unauthorized
      {:status 200
       :body (db.video/list-videos (common/ensure-ds)
                                   {:search-term (common/query-param req "search")
                                    :entity-ids (parse-entity-ids entities-param)
                                    :authed? authed?})})))

(defn get-video-handler
  "GET /api/videos/:id — a single post. Public, like the listing, and like the
  listing it carries :description and :entities only when authenticated."
  [req]
  (let [id (common/parse-int-opt (get-in req [:params :id]))
        video (when id (db.video/get-video (common/ensure-ds) id
                                           {:authed? (common/authenticated? req)}))]
    (if video
      {:status 200 :body video}
      {:status 404 :body {:error "Video not found"}})))

(defn add-video-handler
  "POST /api/videos — post a video. Takes {:input :note :title}, where `input` is
  a watch URL, a youtu.be link, an /embed/, /shorts/ or /live/ URL, or the bare
  11-character id. A `t=` offset on the link is kept as the post's start time;
  share-tracking params like `si=` are discarded. The title is fetched from
  YouTube unless one is given. 400 when the input names no video.

  The PUT counterpart reaches only the annotation layer — the post itself is
  immutable once made."
  [req]
  (let [user-id (common/get-user-id req)
        {:keys [input note title]} (:body req)
        video-id (youtube/resolve-video-id input)]
    (if (nil? video-id)
      {:status 400 :body {:error "Not a YouTube video URL or id"}}
      {:status 201
       :body (db.video/add-video (common/ensure-ds) user-id
                                 {:video-id video-id
                                  :title (or (when-not (str/blank? title) (str/trim title))
                                             (youtube/fetch-title video-id))
                                  :note (or note "")
                                  :start-seconds (youtube/resolve-start-seconds input)})})))

(defn- entity-ids-list?
  "Whether a request's `entity-ids` is something we can carry out. JSON can hand
  over anything, and the two ways of getting this wrong both end badly: a bare
  number blows the assignment write up, while a string or an object holds no ids
  the write can see and so would silently clear every assignment the post had.
  Absent is a list of none, which is a legitimate way to clear them on purpose."
  [entity-ids]
  (or (nil? entity-ids) (sequential? entity-ids)))

(defn update-video-handler
  "PUT /api/videos/:id — replace a post's owner-only annotation layer from
  {:description :entity-ids}, both wholesale (entity-ids has set semantics). The
  post itself is not editable: title, video, note and start time are untouched.
  400 when entity-ids is given as anything but a list of ids; 404 when the id
  matches nothing you own. Returns the post in the authenticated shape."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/parse-int-opt (get-in req [:params :id]))
        {:keys [description entity-ids]} (:body req)]
    (if-not (entity-ids-list? entity-ids)
      {:status 400 :body {:error "entity-ids must be a list of ids"}}
      (if-let [video (when id (db.video/update-video (common/ensure-ds) user-id id
                                                     {:description description
                                                      :entity-ids entity-ids}))]
        {:status 200 :body video}
        {:status 404 :body {:error "Video not found"}}))))

(defn delete-video-handler
  "DELETE /api/videos/:id — remove a post together with its entity assignments."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/parse-int-opt (get-in req [:params :id]))
        result (when id (db.video/delete-video (common/ensure-ds) user-id id))]
    (if (:success result)
      {:status 200 :body result}
      {:status 404 :body {:error "Video not found"}})))
