(ns et.mu.server.recording-mode
  "Read-only-by-default gate for machine callers (agents, scripts).

  A token minted by `auth/create-machine-token` may read the whole API freely,
  but its writes are dropped unless the human has switched recording mode on.
  That way an agent can discover the surface via /api/describe and explore it
  without any risk of mutating data. Browser/human tokens are unaffected.

  Unlike tracker, music has no events table, so dropped writes are logged
  rather than persisted as an audit trail."
  (:require [clojure.string :as str]
            [taoensso.telemere :as tel]
            [et.mu.auth :as auth]))

(defonce ^:private *recording? (atom false))

(defn enabled? [] @*recording?)

(defn toggle! [] (swap! *recording? not))

(defn- mutating? [req] (#{:post :put :delete} (:request-method req)))

(defn- api-request? [req]
  (str/starts-with? (or (:uri req) "") "/api/"))

(defn- machine-claims [req]
  (when-let [token (auth/extract-token req)]
    (when-let [claims (auth/verify-token token)]
      (when (:machine? claims) claims))))

(defn- read-body-string
  "Best-effort read of the body for the log line. This middleware runs before
  wrap-json-body, so the body is still an unconsumed stream here; we only slurp
  it when we are about to short-circuit the request anyway."
  [req]
  (try
    (when-let [b (:body req)]
      (cond
        (string? b) b
        (instance? java.io.InputStream b) (slurp b)
        :else (str b)))
    (catch Throwable _ nil)))

(defn wrap-machine-write-guard
  "When a verified token marks the caller as a machine user and the request
  mutates an /api/* endpoint, only let it through while recording mode is on.
  Otherwise log the intent and return a stub `{\"dropped\":true}` response —
  read access stays open regardless."
  [handler]
  (fn [req]
    (if (and (api-request? req) (mutating? req))
      (if-let [claims (machine-claims req)]
        (if (enabled?)
          (do (tel/log! {:level :info
                         :data {:intent :machine-write
                                :uri (:uri req)
                                :method (:request-method req)
                                :machine-user-id (:user-id claims)
                                :recording true}}
                        "MACHINE WRITE")
              (handler req))
          (do (tel/log! {:level :info
                         :data {:intent :machine-write
                                :uri (:uri req)
                                :method (:request-method req)
                                :machine-user-id (:user-id claims)
                                :recording false
                                :body (read-body-string req)}}
                        "MACHINE WRITE DROPPED")
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body "{\"dropped\":true}"}))
        (handler req))
      (handler req))))
