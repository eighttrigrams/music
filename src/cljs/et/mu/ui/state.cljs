(ns et.mu.ui.state
  (:require [reagent.core :as r]
            [et.mu.ui.api :as api]))

(defonce *app-state
  (r/atom {:auth-required? nil   ;; nil = still loading
           :logged-in? false
           :token nil
           :current-user nil
           :error nil
           :videos []
           :search ""
           :show-login? false    ;; the sign-in form is only asked for
           :editing nil          ;; the post the edit form is open for
           :open #{}}))          ;; ids of posts whose player is expanded

;; ---------------------------------------------------------------------------
;; helpers

(defn auth-headers []
  (if-let [token (:token @*app-state)]
    {"Authorization" (str "Bearer " token)}
    {}))

(defn set-error [msg]
  (swap! *app-state assoc :error msg))

(defn clear-error []
  (swap! *app-state assoc :error nil))

(defn- err-handler [fallback]
  (fn [resp]
    (set-error (get-in resp [:response :error] fallback))))

;; ---------------------------------------------------------------------------
;; auth

(defn- save-token! [token user]
  (when token (.setItem js/localStorage "music-token" token))
  (when user (.setItem js/localStorage "music-user" (js/JSON.stringify (clj->js user)))))

(defn- clear-token! []
  (.removeItem js/localStorage "music-token")
  (.removeItem js/localStorage "music-user"))

(declare fetch-videos)

(defn fetch-auth-required
  "Reading is public, so the feed is fetched either way. `required` only decides
  whether the posting affordances show up."
  []
  (api/fetch-json "/api/auth/required" {}
    (fn [{:keys [required]}]
      (swap! *app-state assoc :auth-required? required)
      (if-not required
        (swap! *app-state assoc :logged-in? true)
        (let [token (.getItem js/localStorage "music-token")
              user-str (.getItem js/localStorage "music-user")]
          (when (and token user-str)
            (swap! *app-state assoc
                   :logged-in? true
                   :token token
                   :current-user (js->clj (js/JSON.parse user-str) :keywordize-keys true)))))
      (fetch-videos))))

(defn login [username password on-success]
  (api/post-json "/api/auth/login" {:username username :password password} {}
    (fn [{:keys [token user]}]
      (swap! *app-state assoc :logged-in? true :token token :current-user user :error nil)
      (save-token! token user)
      (fetch-videos)
      (when on-success (on-success)))
    (err-handler "Invalid credentials")))

(defn logout []
  (clear-token!)
  (swap! *app-state assoc :logged-in? false :token nil :current-user nil :editing nil))

;; ---------------------------------------------------------------------------
;; videos

(defn fetch-videos []
  (let [search (:search @*app-state)
        url (if (seq search)
              (str "/api/videos?search=" (js/encodeURIComponent search))
              "/api/videos")]
    (api/fetch-json url (auth-headers)
      (fn [videos] (swap! *app-state assoc :videos (vec videos))))))

(defn set-search [s]
  (swap! *app-state assoc :search s)
  (fetch-videos))

(defn add-video
  "`input` is whatever was pasted — a watch URL, a share link, or a bare id. The
  server resolves it and fetches the title."
  [input note on-success]
  (api/post-json "/api/videos" {:input input :note (or note "")} (auth-headers)
    (fn [_] (fetch-videos) (when on-success (on-success)))
    (err-handler "Could not post that video")))

(defn update-video [id fields on-success]
  (api/put-json (str "/api/videos/" id) fields (auth-headers)
    (fn [_] (fetch-videos) (when on-success (on-success)))
    (err-handler "Could not save")))

(defn delete-video [id]
  (api/delete-simple (str "/api/videos/" id) (auth-headers)
    (fn [_] (fetch-videos))
    (err-handler "Could not delete")))

;; ---------------------------------------------------------------------------
;; view state

(defn open-edit [video]
  (swap! *app-state assoc :editing video))

(defn close-edit []
  (swap! *app-state assoc :editing nil))

(defn toggle-open
  "Expand or collapse a post's embedded player. Several can be open at once."
  [id]
  (swap! *app-state update :open #(if (contains? % id) (disj % id) (conj % id))))
