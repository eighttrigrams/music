(ns et.mu.ui.views.projects
  "Projects: the owner's notes — a title, a markdown body, and optionally one
  audio file played in place between the two.

  The page is the odd one out in music, in the way that matters most: a post is
  public and cannot be edited, a project is private and is nothing but editable.
  So both affordances the feed refuses are here — Edit opens the note itself,
  not a layer beside it — and none of it is reachable, or fetched, without being
  signed in. The server says the same: an anonymous GET is a 401, not a thinner
  shape of the same page.

  The body is markdown, written in the same IJKL CodeMirror the compose box and
  the annotate modal use, and rendered with `marked` the way tracker and treina
  render theirs."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.mu.ui.audio :as audio]
            [et.mu.ui.codemirror :as cm]
            [et.mu.ui.markdown :as markdown]
            [et.mu.ui.state :as state]))

(defn- day [timestamp]
  (when (seq (str timestamp))
    (first (str/split (str timestamp) #" "))))

(defn- compose-form
  "A title and, if there is anything to say yet, a body and an audio file. Only
  the title is required — the server takes a note that is a title and nothing
  else."
  []
  (let [title (r/atom "")
        body (r/atom "")
        audio-url (r/atom "")]
    (fn []
      (let [submit (fn []
                     (when-not (str/blank? @title)
                       (state/add-project @title @body @audio-url
                                          (fn [] (reset! title "")
                                                 (reset! body "")
                                                 (reset! audio-url "")))))]
        [:div.compose
         [:input.compose-url
          {:type "text" :placeholder "Project title"
           :value @title
           :on-change #(reset! title (-> % .-target .-value))
           ;; Enter submits from the title, as in the compose box on the feed.
           ;; Not from the body: a newline is what Enter means in markdown.
           :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         ;; `type="url"` for the keyboard it brings up on a phone, not for
         ;; validation: what counts as an acceptable link is the server's call
         ;; and depends on whether this is dev or production, which the browser
         ;; has no way to know.
         [:input.compose-url
          {:type "url" :placeholder "Audio URL — an mp3 to play here (optional)"
           :value @audio-url
           :on-change #(reset! audio-url (-> % .-target .-value))
           :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         [cm/editor {:placeholder "Markdown (optional)"
                     :height "94px"
                     :value @body
                     :on-change #(reset! body %)}]
         [:button {:on-click submit :disabled (str/blank? @title)} "Create"]]))))

(defn- discard-confirmation
  "The one way out of the modal that loses writing, so it is the one that asks.

  An in-app layer rather than the `js/confirm` the deletes use, and the
  difference is what is at stake: a delete confirm guards a row that has been
  saved and read at least once, this guards text that exists nowhere but in
  front of you. Keep editing is the primary button — the safe way out of a
  question about losing prose should be the one the hand goes to."
  [{:keys [on-discard on-keep]}]
  [:div.confirm-layer {:on-click on-keep}
   [:div.confirm-box {:on-click #(.stopPropagation %)}
    [:h2 "Discard your changes?"]
    [:div.confirm-text "This note has edits that were never saved."]
    [:div.modal-actions
     [:button {:on-click on-keep} "Keep editing"]
     [:button.secondary.danger {:on-click on-discard} "Discard"]]]])

(defn- edit-modal
  "The note itself, both halves of it.

  **Ways out.** ⌘9 saves and closes, Escape closes, and both buttons do what
  they say. What none of them do is lose writing quietly: leaving with unsaved
  edits — by Escape or by Cancel, they are the same act — asks first. There is
  deliberately no dismiss-on-backdrop, unlike the annotate modal on the feed:
  the nearest miss of a full-window editor is the backdrop, and one stray click
  is a poor reason to throw a draft away.

  The bindings are read off `e.code` in the capture phase, which is the rule the
  IJKL scheme is written to (on macOS `e.key` is composed by Option, so a map
  written against it fails silently for exactly the wordwise keys). Capture, and
  on `document`, so they answer wherever the caret is — the title input, the
  editor, or neither. ⌘9 and Escape are both free in `kw-codemirror`, so nothing
  is being shadowed.

  The `modified_at` the project was read at rides along with the save, so one
  that lands on a version written somewhere else in the meantime is refused
  rather than silently winning. When that happens the modal stays open with the
  draft in it — see `state/save-project` — while the list underneath refreshes
  to the version that landed. Saving a second time then goes through and
  overwrites it, deliberately: the guard is there to make sure nobody's writing
  disappears without being seen, not to have the last word about which one
  stays."
  [project]
  (r/with-let [project-id (:id project)
               initial-title (or (:title project) "")
               initial-body (or (:body project) "")
               initial-audio (or (:audio_url project) "")
               title (r/atom initial-title)
               body (r/atom initial-body)
               audio-url (r/atom initial-audio)
               confirming? (r/atom false)
               dirty? #(or (not= @title initial-title)
                           (not= @body initial-body)
                           (not= @audio-url initial-audio))
               ;; The row as the list now holds it, not as the modal opened it:
               ;; after a refused save that is the version that landed, and its
               ;; `modified_at` is the one a second save has to carry.
               latest #(or (first (filter (fn [p] (= project-id (:id p)))
                                          (:projects @state/*app-state)))
                           project)
               save! #(when-not (str/blank? @title)
                        (state/save-project project-id @title @body @audio-url
                                            (:modified_at (latest))
                                            state/stop-editing-project))
               leave! #(if (dirty?)
                         (reset! confirming? true)
                         (state/stop-editing-project))
               on-key (fn [e]
                        (let [cmd? (or (.-metaKey e) (.-ctrlKey e))]
                          (case (.-code e)
                            "Digit9" (when (and cmd? (not @confirming?))
                                       (.preventDefault e)
                                       (.stopPropagation e)
                                       (save!))
                            "Escape" (do (.preventDefault e)
                                         (.stopPropagation e)
                                         ;; Escape backs out of the question
                                         ;; before it backs out of the note.
                                         (if @confirming?
                                           (reset! confirming? false)
                                           (leave!)))
                            nil)))
               _ (.addEventListener js/document "keydown" on-key true)]
    [:div.modal-backdrop
     [:div.modal.project-modal
      [:h2 "Edit project"]
      [:input.modal-title
       {:type "text" :placeholder "Project title"
        :value @title
        :on-change #(reset! title (-> % .-target .-value))
        :on-key-down #(when (= (.-key %) "Enter") (save!))}]
      ;; Emptying this is how the player comes off the note again — the server
      ;; treats a blank `audio_url` as a clear rather than as nothing sent.
      [:input.modal-audio-url
       {:type "url" :placeholder "Audio URL — an mp3 to play here (optional)"
        :value @audio-url
        :on-change #(reset! audio-url (-> % .-target .-value))
        :on-key-down #(when (= (.-key %) "Enter") (save!))}]
      ;; The editor takes whatever the title and the buttons leave, rather
      ;; than a height of its own: the modal is a share of the window, so a
      ;; fixed one would either fall short of it or overrun it.
      [:div.project-editor
       [cm/editor {:placeholder "Markdown"
                   :height "100%"
                   :value @body
                   :on-change #(reset! body %)}]]
      [:div.modal-actions
       [:button {:on-click save! :disabled (str/blank? @title)} "Save"]
       [:button.secondary {:on-click leave!} "Cancel"]]]
     (when @confirming?
       [discard-confirmation
        {:on-discard state/stop-editing-project
         :on-keep #(reset! confirming? false)}])]
    (finally
      (.removeEventListener js/document "keydown" on-key true))))

(defn- project-card [{:keys [id title body audio_url created_at]}]
  [:div.card.project
   [:div.project-head
    [:h2.project-title title]
    [:span.card-date (day created_at)]]
   ;; Between the title and the prose, because that is the order they are read
   ;; in: what this note is, then what it sounds like, then what is said about
   ;; it. Keyed on the URL so editing the link builds a new player rather than
   ;; handing the old one a different file mid-play.
   (when (seq audio_url)
     ^{:key audio_url} [audio/player audio_url])
   (when (seq body)
     [:div.project-body [markdown/render body]])
   [:div.card-footer
    [:span.card-actions
     [:button.secondary {:on-click #(state/start-editing-project id)} "Edit"]
     [:button.secondary.danger
      {:on-click #(when (js/confirm (str "Delete \"" title "\"? The note is gone for good."))
                    (state/delete-project id))}
      "Delete"]]]])

(defn projects-tab []
  (let [{:keys [projects editing-project]} @state/*app-state]
    [:div.projects
     [compose-form]
     (if (empty? projects)
       [:div.empty "No projects yet."]
       (for [project projects]
         ^{:key (:id project)}
         [project-card project]))
     ;; Outside the cards, for the reason the feed's modal is: a card's
     ;; backdrop-filter would become the containing block for the modal's fixed
     ;; positioning and pin it to that one card instead of to the viewport.
     (when-let [project (first (filter #(= editing-project (:id %)) projects))]
       [edit-modal project])]))
