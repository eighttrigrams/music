(ns et.mu.youtube
  "The little bit of YouTube music needs: turn whatever URL was pasted into the
  11-character video id, and ask YouTube what that video is called.

  The title comes from the public oEmbed endpoint — no API key, no quota. HTTP
  goes through the JDK client rather than a new dependency."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [taoensso.telemere :as tel])
  (:import [java.net URI URLEncoder]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]))

(def ^:private oembed-url "https://www.youtube.com/oembed?format=json&url=")

(def ^:private watch-url "https://www.youtube.com/watch?v=")

;; YouTube serves a stripped-down page to unknown agents.
(def ^:private user-agent "Mozilla/5.0")

(defonce ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 10))
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.build))))

(defn- http-get
  "Body of a 200 response, nil for anything else — an unreachable YouTube must
  not stop a post from being made, only leave it untitled."
  [url]
  (try
    (let [req (-> (HttpRequest/newBuilder (URI/create url))
                  (.header "User-Agent" user-agent)
                  (.timeout (Duration/ofSeconds 15))
                  (.GET)
                  (.build))
          resp (.send @client req (HttpResponse$BodyHandlers/ofString))]
      (when (= 200 (.statusCode resp))
        (.body resp)))
    (catch Exception e
      (tel/log! {:level :warn :data {:url url}} (str "youtube fetch failed: " (.getMessage e)))
      nil)))

(def ^:private video-id-pattern "[\\w-]{11}")

(def ^:private video-id-re (re-pattern video-id-pattern))

(def ^:private video-url-patterns
  "The shapes a video URL comes in — watch page, share link, embed, short, live."
  (mapv #(re-pattern (str % "(" video-id-pattern ")"))
        ["[?&]v=" "youtu\\.be/" "/embed/" "/shorts/" "/live/"]))

(defn resolve-video-id
  "The 11-character video id out of whatever is at hand — a watch URL, a youtu.be
  share link, an /embed/, /shorts/ or /live/ URL, or the bare id. nil when the
  input names no video."
  [input]
  (let [input (str/trim (or input ""))]
    (if (re-matches video-id-re input)
      input
      (some (fn [re] (second (re-find re input))) video-url-patterns))))

(def ^:private start-param-re
  "The start offset as YouTube writes it: `?t=`/`&t=` on a watch or share link,
  `#t=` on older ones, and `start=` on an embed."
  #"[?&#](?:t|start)=([\dhms]+)")

(def ^:private hms-re #"^(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s?)?$")

(defn resolve-start-seconds
  "The start offset in whole seconds from a pasted URL, nil when it names none.

  YouTube writes it two ways: bare seconds (`t=421`, `t=421s`) and a composite
  (`t=1h2m3s`, `t=7m1s`). Both land here as seconds. Anything unparseable is
  treated as no offset rather than an error — a link is still worth posting."
  [input]
  (when-let [raw (second (re-find start-param-re (str/trim (or input ""))))]
    (if-let [[_ h m s] (re-matches hms-re raw)]
      (let [n #(if % (parse-long %) 0)
            total (+ (* 3600 (n h)) (* 60 (n m)) (n s))]
        (when (pos? total) total))
      nil)))

(defn fetch-title
  "The video's title from the oEmbed endpoint, nil when YouTube won't describe it
  (private, deleted, or a bad id)."
  [video-id]
  (let [url (str oembed-url (URLEncoder/encode (str watch-url video-id) StandardCharsets/UTF_8))]
    (when-let [body (http-get url)]
      (try
        (let [{:strs [title]} (json/parse-string body)]
          (when (seq title) title))
        (catch Exception e
          (tel/log! {:level :warn :data {:video-id video-id}}
                    (str "youtube oembed unparseable: " (.getMessage e)))
          nil)))))
