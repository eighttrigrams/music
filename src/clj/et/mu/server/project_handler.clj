(ns et.mu.server.project-handler
  "Projects: the owner's notes — title, markdown body, and optionally one audio
  file to play beside them.

  This is the first surface in music that is private on the **read** side too.
  Everything else answers anybody — the feed, a post, the vocabulary, the entity
  filter — and only the writes are gated, by `wrap-auth`. A project is nobody's
  business but the owner's, so every route here begins by asking who is calling.

  `wrap-auth` gates mutations in production only, so the guard cannot be left to
  it: it is `require-auth` below, in front of reads and writes alike, answering
  the same 401 body `wrap-auth` answers so the two never disagree about a URI.
  In dev with `:dangerously-skip-logins?` everybody is the owner, which is what
  that flag means everywhere else in the app.

  Below that line the id is what decides, and an id that belongs to somebody
  else is answered exactly as an id that belongs to nobody: 404, same body. A
  403 would confirm the project is there and let a stranger count the owner's
  notes by walking the ids."
  (:require [clojure.string :as str]
            [et.mu.server.common :as common]
            [et.mu.db.project :as db.project])
  (:import [java.net URI]))

(def ^:private unauthorized
  {:status 401 :body {:error "Authentication required"}})

(def ^:private not-found
  {:status 404 :body {:error "Project not found"}})

(defn- require-auth
  "Run `f` with the acting user's id, or answer 401. Reads included — that is the
  whole point of this page."
  [req f]
  (if (common/authenticated? req)
    (f (common/get-user-id req))
    unauthorized))

(defn- posted-string
  "The value a request offers under `k`, trimmed, or nil when it offered no
  string. JSON can hand over anything, and a number or an object coerced into a
  title would be something nobody typed."
  [req k]
  (let [value (get-in req [:body k])]
    (when (string? value) (str/trim value))))

(def ^:private audio-schemes #{"http" "https"})

(defn- audio-url-error
  "nil when the request's `audio_url` is a link a browser could actually fetch
  from *this* deployment, an error string when it is not. A request carrying no
  `audio_url` at all is fine — that is what leaves the stored one alone — and so
  is a blank one, which is how it is cleared.

  Only `http` and `https` get through, so a `file:` or `javascript:` URL cannot
  be parked in a field the page later hands to an `<audio src>`. And in
  production only `https`: the page itself is served over TLS there, so a plain
  `http` audio file is blocked by the browser as mixed content — a link that
  would silently never play is better refused at the door than stored. In dev the
  page is plain `http` too, so `http` is allowed and a file on a local server can
  be pointed at."
  [req]
  (let [body (:body req)]
    (when (contains? body :audio_url)
      (let [value (:audio_url body)]
        (if-not (string? value)
          "Audio URL must be a string"
          (let [url (str/trim value)
                ;; A URI that will not parse at all, and one that parses to
                ;; something relative, are the same mistake to the caller.
                uri (try (URI. url) (catch Exception _ nil))
                scheme (some-> uri .getScheme str/lower-case)]
            (when-not (str/blank? url)
              (cond
                (not (contains? audio-schemes scheme))
                "Audio URL must be an http(s) URL"

                (str/blank? (str (some-> uri .getHost)))
                "Audio URL must name a host"

                (and (= "http" scheme) (common/prod-mode?))
                "Audio URL must be https"))))))))

(defn list-projects-handler
  "GET /api/projects — the caller's projects, newest first. 401 when nobody is
  signed in: unlike the feed, this page has no anonymous half."
  [req]
  (require-auth req
    (fn [user-id]
      {:status 200 :body (db.project/list-projects (common/ensure-ds) user-id)})))

(defn get-project-handler
  "GET /api/projects/:id — one project. 401 when nobody is signed in, and 404
  when the id is not one of the caller's — the same 404, body and all, that an
  id matching nothing gets."
  [req]
  (require-auth req
    (fn [user-id]
      (let [id (common/parse-int-opt (get-in req [:params :id]))
            project (when id (db.project/get-project (common/ensure-ds) user-id id))]
        (if project
          {:status 200 :body project}
          not-found)))))

(defn add-project-handler
  "POST /api/projects — create a project from {:title :body :audio_url}. The
  title is required and must be a non-blank string; the body is markdown and
  defaults to empty, because a note is often a title first and prose later.

  `audio_url` is optional and names one audio file — an mp3 on some web server —
  to be played beside the note. It must be an `http(s)` URL, and in production
  `https` only; see `audio-url-error`."
  [req]
  (require-auth req
    (fn [user-id]
      (let [title (posted-string req :title)
            body (posted-string req :body)
            audio-url (posted-string req :audio_url)
            audio-error (audio-url-error req)]
        (cond
          (str/blank? title)
          {:status 400 :body {:error "Title required"}}

          audio-error
          {:status 400 :body {:error audio-error}}

          :else
          {:status 201
           :body (db.project/add-project (common/ensure-ds) user-id
                                         {:title title
                                          :body (or body "")
                                          :audio_url (or audio-url "")})})))))

(defn update-project-handler
  "PUT /api/projects/:id — save {:title :body :audio_url}. A field left out keeps
  its current value, so an edit meant for one cannot clear the others; a title
  given as blank, or as something that is not a string, is refused with 400
  rather than written. A request that carries no field at all is a 400 too: there
  is nothing to save, and a bare `modified_at` bump is not on offer.

  `audio_url` is the exception to blank-is-refused: a blank one is how the player
  is taken off the note again. Anything non-blank must be an `http(s)` URL, and
  in production `https` only — see `audio-url-error`.

  Pass `modified_at` as it was read to be told (409) that somebody saved in
  between, instead of overwriting what they wrote. The 409 carries the project
  as it now stands, so the client can show what it would have clobbered. Leave
  it out and the save is last-write-wins."
  [req]
  (require-auth req
    (fn [user-id]
      (let [ds (common/ensure-ds)
            id (common/parse-int-opt (get-in req [:params :id]))
            body (:body req)
            title (posted-string req :title)
            markdown (posted-string req :body)
            ;; A trimmed string, empty one included — and an empty one is
            ;; truthy, which is what lets `""` reach `:set` and clear the field
            ;; rather than being skipped as "not sent".
            audio-url (posted-string req :audio_url)
            audio-error (audio-url-error req)
            expected (get body :modified_at)
            fields (cond-> {}
                     title (assoc :title title)
                     markdown (assoc :body markdown)
                     audio-url (assoc :audio_url audio-url))]
        (cond
          (and (contains? body :title) (nil? title))
          {:status 400 :body {:error "Title must be a string"}}

          (and (contains? body :title) (str/blank? title))
          {:status 400 :body {:error "Title required"}}

          (and (contains? body :body) (nil? markdown))
          {:status 400 :body {:error "Body must be a string"}}

          audio-error
          {:status 400 :body {:error audio-error}}

          (empty? fields)
          {:status 400 :body {:error "Nothing to save"}}

          :else
          (if-let [saved (when id (db.project/update-project ds user-id id fields expected))]
            {:status 200 :body saved}
            ;; No row matched. Either it is not the caller's — 404, the same one
            ;; an unknown id gets — or the guard caught a save that landed in
            ;; between, which is a 409 and not a failure to find anything.
            (if-let [current (when id (db.project/get-project ds user-id id))]
              (if expected
                {:status 409 :body {:error "Saved by somebody else in the meantime"
                                    :current current}}
                not-found)
              not-found)))))))

(defn delete-project-handler
  "DELETE /api/projects/:id — remove a project. 404 when the id matches nothing
  the caller owns, and the same 404 the second time round."
  [req]
  (require-auth req
    (fn [user-id]
      (let [id (common/parse-int-opt (get-in req [:params :id]))
            result (when id (db.project/delete-project (common/ensure-ds) user-id id))]
        (if (:success result)
          {:status 200 :body result}
          not-found)))))
