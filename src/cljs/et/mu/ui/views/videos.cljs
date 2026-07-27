(ns et.mu.ui.views.videos
  "The feed: one YouTube video per post, newest first.

  A card shows the title, the note and when it was posted. Clicking the header
  expands the embedded player in place, so several posts can play-ready at once
  without leaving the page. The compose form and the per-card edit/delete
  affordances only appear once signed in — reading the feed needs no login."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.mu.ui.state :as state]))

(defn- embed-url [video-id]
  (str "https://www.youtube-nocookie.com/embed/" video-id))

(defn- watch-url [video-id]
  (str "https://www.youtube.com/watch?v=" video-id))

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

(defn- edit-form [{:keys [id title note modified_at]}]
  (let [t (r/atom (or title ""))
        n (r/atom (or note ""))]
    (fn []
      [:div.edit-form
       [:input {:type "text" :placeholder "Title"
                :value @t
                :on-change #(reset! t (-> % .-target .-value))}]
       [:textarea {:rows 3
                   :value @n
                   :on-change #(reset! n (-> % .-target .-value))}]
       [:div.edit-actions
        [:button {:on-click #(state/update-video id
                                                 {:title @t :note @n :modified_at modified_at}
                                                 state/close-edit)} "Save"]
        [:button.secondary {:on-click state/close-edit} "Cancel"]]])))

(defn- card [{:keys [id video_id title note created_at] :as video} {:keys [logged-in? open editing]}]
  (let [expanded? (contains? open id)]
    [:div.card
     [:div.card-header {:on-click #(state/toggle-open id)}
      [:span.card-play (if expanded? "▾" "▸")]
      [:h2.card-title (or title video_id)]
      [:span.card-date (day created_at)]]
     (when expanded?
       [:div.player
        [:iframe {:src (embed-url video_id)
                  :title (or title video_id)
                  :allow "accelerometer; clipboard-write; encrypted-media; picture-in-picture"
                  :allowFullScreen true
                  :frameBorder "0"}]])
     (when (seq note)
       [:div.card-note note])
     [:div.card-footer
      [:a.watch-link {:href (watch-url video_id) :target "_blank" :rel "noreferrer"}
       "Watch on YouTube"]
      (when logged-in?
        [:span.card-actions
         [:button.secondary {:on-click #(state/open-edit video)} "Edit"]
         [:button.secondary.danger {:on-click #(state/delete-video id)} "Delete"]])]
     (when (= id (:id editing))
       [edit-form editing])]))

(defn videos-tab []
  (let [{:keys [videos search logged-in? open editing]} @state/*app-state]
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
         [card v {:logged-in? logged-in? :open open :editing editing}]))]))
