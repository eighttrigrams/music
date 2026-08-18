(ns et.mu.ui.markdown
  "A project's body is markdown, rendered through marked — the same library and
  the same three lines tracker and treina render theirs with, rather than a
  second way of doing it here. `markdown.css` keeps headings at body size, so a
  rendered note sits inside a card without shouting.

  Nothing else in music goes through here. A post's note and the private
  description beside it are plain text and stay plain text: they are shown with
  `white-space: pre-wrap`, which is what a line break in them has always meant."
  (:require [reagent.core :as r]
            ["marked" :refer [marked]]))

(defn render [text]
  [:div.markdown-content
   {:dangerouslySetInnerHTML (r/unsafe-html (marked (or text "")))}])
