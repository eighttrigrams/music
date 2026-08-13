(ns et.mu.ui.codemirror
  "Daniel's IJKL keyboard scheme on music's two writing surfaces: the compose
  note and the annotate modal's description.

  The bindings are not here. They are `@eighttrigrams/kw-codemirror`, the library
  in the keyboard-wizardry repo that also holds his VSCode and Obsidian keymaps,
  so there is one implementation of the scheme rather than one per app — blog,
  personalist, tracker, rhizome and treina call the same `install`. This
  namespace is only the reagent side of it, and it is personalist's, adapted:
  music's textareas are controlled components, so the editor cannot be
  `fromTextarea` (mirroring a document into a textarea's `.value` fires no input
  event and reagent would never hear about the typing). It mounts on a div and
  reports changes through `:on-change`, the shape the textarea already had.

  The theme is written in music's own CSS variables rather than the colours they
  currently resolve to, so the editor follows the light/dark switch in base.css
  the way the textarea did."
  (:require [reagent.core :as r]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :refer [EditorView keymap placeholder]]
            ["@codemirror/commands" :as commands]
            ["@eighttrigrams/kw-codemirror" :as ijkl]))

(defn- theme
  "Built from the `textarea` rule in css/music.css, so nothing about the page
  moves. What is lost is the drag-handle: `resize: vertical` has no CodeMirror
  equivalent, and past its height the editor scrolls instead of growing."
  [{:keys [height]}]
  (.theme EditorView
          #js {"&" #js {:height (or height "58px")
                        :fontSize "0.95em"
                        :fontFamily "inherit"
                        :border "1px solid var(--input-border)"
                        :borderRadius "10px"
                        :backgroundColor "var(--glass-bg-subtle)"
                        :color "var(--text-primary)"}
               "&.cm-focused" #js {:outline "none"
                                   :borderColor "var(--accent)"
                                   :boxShadow "0 0 0 3px rgba(13, 148, 136, 0.18)"}
               ".cm-scroller" #js {:overflow "auto" :fontFamily "inherit"}
               ".cm-content" #js {:padding "10px 14px"
                                  :fontFamily "inherit"
                                  :caretColor "var(--text-primary)"}
               ".cm-line" #js {:padding "0"}
               ".cm-gutters" #js {:display "none"}
               ".cm-activeLine" #js {:backgroundColor "transparent"}
               ".cm-cursor" #js {:borderLeftColor "var(--text-primary)"}
               ".cm-placeholder" #js {:color "var(--muted-text)"}}))

(defn- create-view [element {:keys [value on-change] :as props}]
  (let [listener (.of (.-updateListener EditorView)
                      (fn [^js update]
                        (when (and (.-docChanged update) on-change)
                          (on-change (.. update -state -doc toString)))))
        extensions (cond-> [(theme props)
                            (.-lineWrapping EditorView)
                            ;; history, so ⌥` (undo in the scheme) and ⌘Z have
                            ;; something to undo. A textarea had the browser's
                            ;; own undo stack; a CodeMirror without this
                            ;; extension has none at all.
                            (commands/history)
                            (.of keymap commands/historyKeymap)
                            (.of keymap commands/defaultKeymap)
                            listener]
                     (:placeholder props) (conj (placeholder (:placeholder props))))
        state (.create EditorState
                       #js {:doc (or value "")
                            ;; into-array, not clj->js: these are CodeMirror
                            ;; extension objects and have no business being walked.
                            :extensions (into-array extensions)})
        view (new EditorView #js {:state state :parent element})]
    ;; The scheme. Capture phase inside the library, so these win over the two
    ;; keymaps above.
    (ijkl/install view commands)
    view))

(defn- set-doc! [^js view value]
  (.dispatch view
             #js {:changes #js {:from 0
                                :to (.. view -state -doc -length)
                                :insert (or value "")}}))

(defn editor
  "A CodeMirror with the IJKL bindings, in place of a textarea.

  Props:
    :value        the document
    :on-change    (fn [string]) on every change
    :height       CSS height; default 58px, which is what rows=2 measured
    :placeholder  shown while the document is empty
    :auto-focus?  focus it once mounted"
  [_props]
  (let [!view (atom nil)
        !element (atom nil)]
    (r/create-class
      {:display-name "ijkl-editor"

       :component-did-mount
       (fn [this]
         (let [{:keys [auto-focus?] :as props} (second (r/argv this))
               view (create-view @!element props)]
           (reset! !view view)
           (when auto-focus? (.focus view))))

       ;; Reagent re-renders on every keystroke, because :value comes out of an
       ;; atom that :on-change writes. Replacing the document each time would
       ;; fight the editor — the caret would jump to the end after every letter.
       ;; So only when the two have actually diverged, which is when something
       ;; *else* set the text (a draft cleared after posting, another video
       ;; opened in the modal).
       :component-did-update
       (fn [this _]
         (let [{:keys [value]} (second (r/argv this))
               ^js view @!view]
           (when (and view (not= (or value "") (.. view -state -doc toString)))
             (set-doc! view value))))

       :component-will-unmount
       (fn [_]
         (when-let [^js view @!view]
           (.destroy view)
           (reset! !view nil)))

       :reagent-render
       (fn [{:keys [height]}]
         [:div {:ref #(when % (reset! !element %))
                :style {:width "100%" :height (or height "58px")}}])})))
