(ns et.mu.server.video-handler
  (:require [clojure.string :as str]
            [et.mu.server.common :as common]
            [et.mu.db.video :as db.video]
            [et.mu.youtube :as youtube]))

(defn list-videos-handler
  "GET /api/videos — the posted videos, newest first, optionally filtered by
  ?search over title and note. Public: the same feed whether or not you are
  signed in."
  [req]
  (let [search (get-in req [:query-params "search"])]
    {:status 200 :body (db.video/list-videos (common/ensure-ds) {:search-term search})}))

(defn get-video-handler
  "GET /api/videos/:id — a single post. Public, like the listing."
  [req]
  (let [id (common/parse-int-opt (get-in req [:params :id]))
        video (when id (db.video/get-video (common/ensure-ds) id))]
    (if video
      {:status 200 :body video}
      {:status 404 :body {:error "Video not found"}})))

(defn add-video-handler
  "POST /api/videos — post a video. Takes {:input :note :title}, where `input` is
  a watch URL, a youtu.be link, an /embed/, /shorts/ or /live/ URL, or the bare
  11-character id. A `t=` offset on the link is kept as the post's start time;
  share-tracking params like `si=` are discarded. The title is fetched from
  YouTube unless one is given. 400 when the input names no video.

  There is no PUT counterpart — a post is immutable once made."
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

(defn delete-video-handler
  "DELETE /api/videos/:id — remove a post."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/parse-int-opt (get-in req [:params :id]))
        result (when id (db.video/delete-video (common/ensure-ds) user-id id))]
    (if (:success result)
      {:status 200 :body result}
      {:status 404 :body {:error "Video not found"}})))
