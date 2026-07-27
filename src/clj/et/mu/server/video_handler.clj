(ns et.mu.server.video-handler
  (:require [clojure.string :as str]
            [et.mu.server.common :as common]
            [et.mu.db.video :as db.video]
            [et.mu.youtube :as youtube]))

(defn list-videos-handler
  "GET /api/videos — the posted videos, newest first, optionally filtered by
  ?search over title and note."
  [req]
  (let [user-id (common/get-user-id req)
        search (get-in req [:query-params "search"])]
    {:status 200 :body (db.video/list-videos (common/ensure-ds) user-id {:search-term search})}))

(defn get-video-handler
  "GET /api/videos/:id — a single post."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/parse-int-opt (get-in req [:params :id]))
        video (when id (db.video/get-video (common/ensure-ds) user-id id))]
    (if video
      {:status 200 :body video}
      {:status 404 :body {:error "Video not found"}})))

(defn add-video-handler
  "POST /api/videos — post a video. Takes {:input :note :title}, where `input` is
  a watch URL, a youtu.be link, an /embed/, /shorts/ or /live/ URL, or the bare
  11-character id. The title is fetched from YouTube unless one is given.
  400 when the input names no video."
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
                                  :note (or note "")})})))

(defn update-video-handler
  "PUT /api/videos/:id — update title and/or note. 409 with the current row when
  the post was changed elsewhere, 404 when it is gone."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/parse-int-opt (get-in req [:params :id]))
        {:keys [title note modified_at]} (:body req)
        fields (cond-> {}
                 (some? title) (assoc :title (str/trim title))
                 (some? note) (assoc :note note))]
    (cond
      (nil? id) {:status 404 :body {:error "Video not found"}}
      (empty? fields) {:status 400 :body {:error "nothing to update"}}
      :else
      (if-let [result (db.video/update-video (common/ensure-ds) user-id id fields modified_at)]
        {:status 200 :body result}
        (common/conflict-or-not-found
         (db.video/get-video (common/ensure-ds) user-id id)
         "Video not found")))))

(defn delete-video-handler
  "DELETE /api/videos/:id — remove a post."
  [req]
  (let [user-id (common/get-user-id req)
        id (common/parse-int-opt (get-in req [:params :id]))
        result (when id (db.video/delete-video (common/ensure-ds) user-id id))]
    (if (:success result)
      {:status 200 :body result}
      {:status 404 :body {:error "Video not found"}})))
