(ns et.mu.ui.views.videos
  "The feed: one YouTube video per post, newest first.

  A card shows the title, the note and when it was posted. Clicking the header
  expands the embedded player in place, so several posts can sit play-ready at
  once without leaving the page. A post that was pasted with a `t=` offset starts
  there and shows the offset as a badge.

  The **post is immutable**: title, video and note can be posted and deleted,
  never edited. What Edit reaches is the annotation layer beside it — the owner's
  private description and the entities the post is assigned to. That layer, Edit
  and Delete appear only once signed in; an anonymous visitor is never sent the
  data behind them, so there is nothing here that hides it.

  The filter menu is not part of that layer: the vocabulary is public and so is
  filtering by it, so everyone gets the menu. A visitor may therefore narrow the
  feed to an entity and still see no chips on what comes back — intended."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.mu.ui.state :as state]))

(defn- embed-url [video-id start-seconds]
  (str "https://www.youtube-nocookie.com/embed/" video-id
       (when (pos? (or start-seconds 0)) (str "?start=" start-seconds))))

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

(defn- toggle [s x]
  (if (contains? s x) (disj s x) (conj s x)))

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

(defn- entity-checkbox [{:keys [id name]} chosen on-toggle]
  [:label.entity-check
   [:input {:type "checkbox"
            :checked (contains? chosen id)
            :on-change #(on-toggle id)}]
   [:span name]])

(defn- entity-groups
  "Every entity there is, under its category heading."
  [categories chosen on-toggle]
  [:div.entity-groups
   (for [{:keys [id name entities]} categories]
     ^{:key id}
     [:div.entity-group
      [:div.entity-group-name name]
      (if (empty? entities)
        [:div.entity-group-empty "nothing here yet"]
        (for [entity entities]
          ^{:key (:id entity)}
          [entity-checkbox entity chosen on-toggle]))])])

(defn- no-vocabulary
  "Nothing to check. Only the owner can do anything about that, and only the owner
  can reach the page where it is done, so a visitor is told the fact and left
  there."
  []
  [:div.entity-groups-empty
   (if (:logged-in? @state/*app-state)
     "No entities yet — make some on the Categories page."
     "Nothing to filter by yet.")])

(defn- no-entities?
  "Categories with nothing in them are as empty as no categories at all: either
  way there is nothing to check, so the pointer at the Categories page is what to
  show rather than a heading with `nothing here yet` under it."
  [categories]
  (empty? (mapcat :entities categories)))

(defn- edit-modal
  "Only the annotation layer is on offer here. Cancel simply drops the draft."
  [video]
  (let [description (r/atom (or (:description video) ""))
        chosen (r/atom (set (map :id (:entities video))))]
    (fn [video]
      (let [{:keys [categories]} @state/*app-state]
        [:div.modal-backdrop {:on-click state/stop-editing}
         [:div.modal {:on-click #(.stopPropagation %)}
          [:h2 "Annotate"]
          [:div.modal-subtitle (or (:title video) (:video_id video))]
          [:textarea.modal-description
           {:placeholder "A description, for your eyes only"
            :rows 4
            :value @description
            :on-change #(reset! description (-> % .-target .-value))}]
          (if (no-entities? categories)
            [no-vocabulary]
            [entity-groups categories @chosen #(swap! chosen toggle %)])
          [:div.modal-actions
           [:button {:on-click #(state/update-video (:id video) @description @chosen
                                                    state/stop-editing)}
            "Save"]
           [:button.secondary {:on-click state/stop-editing} "Cancel"]]]]))))

(defn- card [{:keys [id video_id title note description entities start_seconds created_at]}
             {:keys [logged-in? open]}]
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
     (when (seq description)
       [:div.card-description
        [:span.card-description-mark "Private"]
        [:span.card-description-text description]])
     (when (seq entities)
       [:div.card-entities
        (for [entity entities]
          ^{:key (:id entity)}
          [:span.entity-chip (:name entity)])])
     (when logged-in?
       [:div.card-footer
        [:span.card-actions
         [:button.secondary {:on-click #(state/start-editing id)} "Edit"]
         [:button.secondary.danger
          {:on-click #(when (js/confirm (str "Delete \"" (or title video_id)
                                             "\"? The post is gone for good."))
                        (state/delete-video id))}
          "Delete"]]])]))

(defn- filter-menu
  "Opens on hover — the panel is a child of the trigger's wrapper and sits flush
  under it, so there is no gap to fall through. The click toggle is what makes it
  usable on touch, where `mobile.css` turns the hover rule off."
  []
  (let [pinned? (r/atom false)]
    (fn []
      (let [{:keys [categories filter-entities]} @state/*app-state
            active (count filter-entities)]
        [:div.filter-menu {:class (when @pinned? "pinned")
                           :on-mouse-leave #(reset! pinned? false)}
         [:button.secondary.filter-trigger {:on-click #(swap! pinned? not)}
          "Filter"
          (when (pos? active) [:span.filter-count active])]
         [:div.filter-panel
          [:div.filter-panel-head
           [:span.filter-panel-title "Assigned to"]
           (when (pos? active)
             [:button.filter-clear {:on-click state/clear-filter-entities} "clear"])]
          (if (no-entities? categories)
            [no-vocabulary]
            [entity-groups categories filter-entities state/toggle-filter-entity])]]))))

(defn videos-tab []
  (let [{:keys [videos search logged-in? open editing filter-entities]} @state/*app-state]
    [:div.feed
     (when logged-in? [compose-form])
     [:div.search-row
      [:input.search
       {:type "text" :placeholder "Search"
        :value search
        :on-change #(state/set-search (-> % .-target .-value))}]
      [filter-menu]]
     (if (empty? videos)
       [:div.empty (cond
                     (and (seq filter-entities) (seq search)) "Nothing matches inside that filter."
                     (seq filter-entities) "Nothing is assigned to that."
                     (seq search) "Nothing matches."
                     :else "No videos yet.")]
       (for [v videos]
         ^{:key (:id v)}
         [card v {:logged-in? logged-in? :open open}]))
     ;; Deliberately outside the cards: a card's backdrop-filter would make it
     ;; the containing block for the modal's fixed positioning, which pins the
     ;; modal to that one card instead of to the viewport.
     (when-let [video (first (filter #(= editing (:id %)) videos))]
       [edit-modal video])]))
