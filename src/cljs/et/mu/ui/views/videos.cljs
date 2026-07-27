(ns et.mu.ui.views.videos
  "The feed: one YouTube video per post, newest first.

  A card shows the title, the note and when it was posted. Clicking the header
  expands the embedded player in place, so several posts can sit play-ready at
  once without leaving the page. A post that was pasted with a `t=` offset starts
  there — both in the embed and in the outbound link — and shows the offset as a
  badge.

  Posts are **immutable**: there is no edit affordance anywhere, only Post and
  Delete. Reading the feed needs no login; the compose form and the Delete button
  appear once signed in."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.mu.ui.state :as state]))

(defn- embed-url [video-id start-seconds]
  (str "https://www.youtube-nocookie.com/embed/" video-id
       (when (pos? (or start-seconds 0)) (str "?start=" start-seconds))))

(defn- watch-url [video-id start-seconds]
  (str "https://www.youtube.com/watch?v=" video-id
       (when (pos? (or start-seconds 0)) (str "&t=" start-seconds))))

(defn- hms
  "Seconds as `m:ss`, or `h:mm:ss` once it runs past an hour."
  [total]
  (let [h (quot total 3600)
        m (quot (mod total 3600) 60)
        s (mod total 60)
        pad #(if (< % 10) (str "0" %) (str %))]
    (if (pos? h)
      (str h ":" (pad m) ":" (pad s))
      (str m ":" (pad s)))))

(defn- day [timestamp]
  (when (seq (str timestamp))
    (first (str/split (str timestamp) #" "))))

(defn- compose-form []
  (let [input (r/atom "")
        note (r/atom "")]
    (fn []
      (let [submit (fn []
                     (when-not (str/blank? @input)
                       (state/add-video @input @note
                                        (fn [] (reset! input "") (reset! note "")))))]
        [:div.compose
         [:input.compose-url
          {:type "text" :placeholder "YouTube URL or video id"
           :value @input
           :on-change #(reset! input (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         [:textarea.compose-note
          {:placeholder "Say something about it (optional)"
           :rows 2
           :value @note
           :on-change #(reset! note (-> % .-target .-value))}]
         [:button {:on-click submit :disabled (str/blank? @input)} "Post"]]))))

(defn- card [{:keys [id video_id title note start_seconds created_at]} {:keys [logged-in? open]}]
  (let [expanded? (contains? open id)
        start (or start_seconds 0)]
    [:div.card
     [:div.card-header {:on-click #(state/toggle-open id)}
      [:span.card-play (if expanded? "▾" "▸")]
      [:h2.card-title (or title video_id)]
      (when (pos? start)
        [:span.start-badge {:title "Starts partway in"} (hms start)])
      [:span.card-date (day created_at)]]
     (when expanded?
       [:div.player
        [:iframe {:src (embed-url video_id start)
                  :title (or title video_id)
                  :allow "accelerometer; clipboard-write; encrypted-media; picture-in-picture"
                  :allowFullScreen true
                  :frameBorder "0"}]])
     (when (seq note)
       [:div.card-note note])
     [:div.card-footer
      [:a.watch-link {:href (watch-url video_id start) :target "_blank" :rel "noreferrer"}
       "Watch on YouTube"]
      (when logged-in?
        [:span.card-actions
         [:button.secondary.danger {:on-click #(state/delete-video id)} "Delete"]])]]))

(defn videos-tab []
  (let [{:keys [videos search logged-in? open]} @state/*app-state]
    [:div.feed
     (when logged-in? [compose-form])
     [:input.search
      {:type "text" :placeholder "Search"
       :value search
       :on-change #(state/set-search (-> % .-target .-value))}]
     (if (empty? videos)
       [:div.empty (if (seq search) "Nothing matches." "No videos yet.")]
       (for [v videos]
         ^{:key (:id v)}
         [card v {:logged-in? logged-in? :open open}]))]))
