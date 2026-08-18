(ns et.mu.ui.audio
  "One audio file, played in place. SoundCloud minus the waveform: a play/pause
  circle, a bar that can be dragged or clicked, and the position against the
  length.

  No waveform, deliberately — drawing one means fetching and decoding the whole
  file before anything can be heard, and what is being played here is somebody
  else's mp3 across the internet. `preload=\"metadata\"` asks for the length and
  nothing more, so a page of notes costs a handful of small requests rather than
  a download per note.

  The state lives in this namespace and not in `et.mu.ui.state`: what is playing
  and where the caret sits belongs to the element on screen and is gone with it,
  unlike the app state, which survives a refetch. The one thing the caller owes
  us in return is a `^{:key url}` on the component, so a note whose URL is edited
  gets a fresh player instead of one still holding the old file's position.

  Seeking mid-file needs the far end to answer HTTP range requests. Most do; one
  that does not still plays from the start, and dragging the bar simply lands
  back where it was — which is why nothing here treats a failed seek as an
  error."
  (:require [reagent.core :as r]))

(defn- hms
  "Seconds as `m:ss`, or `h:mm:ss` once it runs past an hour. Its own copy rather
  than the feed's, as `day` is in both views — a `t=` badge and a running clock
  read the same and mean different things.

  Fractions are dropped: `currentTime` is a float, and a clock that ticks in
  hundredths is not a clock anybody reads. So is anything that is not a number at
  all — `duration` is NaN until the metadata lands, and `0:00` is the honest
  reading of a position nothing has reported yet."
  [total]
  (let [total (if (and (number? total) (js/isFinite total))
                (js/Math.floor (max 0 total))
                0)
        h (quot total 3600)
        m (quot (mod total 3600) 60)
        s (mod total 60)
        pad #(if (< % 10) (str "0" %) (str %))]
    (if (pos? h)
      (str h ":" (pad m) ":" (pad s))
      (str m ":" (pad s)))))

(defn- playable-length
  "The length to scale the bar by, or nil when there is nothing to scale it by
  yet. A file whose metadata has not landed reads as NaN, and a live stream as
  Infinity; both are a bar with nothing to seek along, not a bar of length zero."
  [duration]
  (when (and (number? duration) (js/isFinite duration) (pos? duration))
    duration))

(defn player
  "The player for `url`. Give it a `^{:key url}` — see the namespace docstring."
  [url]
  (r/with-let [!el (r/atom nil)            ;; the <audio> element itself
               playing? (r/atom false)
               position (r/atom 0)
               duration (r/atom nil)
               failed? (r/atom false)]
    (let [length (playable-length @duration)
          ;; Clamped into the bar before anything is drawn from it: a NaN or an
          ;; over-length position would reach `value` and `--played` as a React
          ;; warning and a bar drawn past its own end.
          pos (if (and (number? @position) (js/isFinite @position))
                (max 0 @position)
                0)
          at (if length (min pos length) pos)
          ;; Read by the CSS as the width of the played part of the bar. A
          ;; gradient rather than a second element, so there is one box to
          ;; click and the browser's own range behaviour — drag, arrow keys,
          ;; click-to-position — is kept.
          played (str (if length (* 100 (/ at length)) 0) "%")
          seek! (fn [e]
                  (let [t (js/parseFloat (.. e -target -value))]
                    (when (js/isFinite t)
                      (reset! position t)
                      (when-let [el @!el] (set! (.-currentTime el) t)))))
          toggle! (fn []
                    (when-let [el @!el]
                      (if (.-paused el)
                        ;; `play` rejects when the file cannot be decoded or
                        ;; reached. Unhandled that is a console error and a
                        ;; button that visibly does nothing; caught, it is the
                        ;; same message a load failure gets.
                        (some-> (.play el) (.catch #(reset! failed? true)))
                        (.pause el))))]
      [:div.audio-player
       [:audio
        {:ref #(reset! !el %)
         :src url
         :preload "metadata"
         :on-loaded-metadata #(do (reset! failed? false)
                                  (reset! duration (.. % -target -duration)))
         ;; A stream's length is only known once it ends, and some servers
         ;; revise it mid-file.
         :on-duration-change #(reset! duration (.. % -target -duration))
         :on-time-update #(reset! position (.. % -target -currentTime))
         :on-play #(reset! playing? true)
         :on-pause #(reset! playing? false)
         ;; Back to the start rather than parked at the end, so the button that
         ;; reads Play plays something.
         :on-ended #(do (reset! playing? false) (reset! position 0))
         :on-error #(reset! failed? true)}]
       [:button.audio-toggle
        {:on-click toggle!
         :disabled @failed?
         :title (if @playing? "Pause" "Play")
         :aria-label (if @playing? "Pause" "Play")}
        (if @playing? "❚❚" "▶")]
       (if @failed?
         ;; The file is the owner's own link, so the useful thing to offer is
         ;; the link — a URL that moved is fixed by opening it, not by staring
         ;; at a dead bar.
         [:a.audio-failed {:href url :target "_blank" :rel "noreferrer"}
          "Could not play this file — open it directly"]
         [:<>
          [:span.audio-time (hms at)]
          [:input.audio-seek
           {:type "range"
            :min 0
            :max (or length 0)
            :step "any"
            :value at
            ;; Nothing to seek along until the length is known. Left enabled it
            ;; would be a full-width bar reporting a position of 0 out of 0.
            :disabled (nil? length)
            :aria-label "Seek"
            :style {"--played" played}
            :on-change seek!}]
          [:span.audio-time (if length (hms length) "–:––")]])])))
