(ns et.mu.auth
  (:require [buddy.sign.jwt :as jwt]
            [clojure.string :as str]))

(defn jwt-secret []
  (or (System/getenv "ADMIN_PASSWORD") "dev-secret"))

(defn create-token
  ([user-id username is-admin]
   (create-token {:user-id user-id :username username :is-admin is-admin}))
  ([claims]
   (jwt/sign claims (jwt-secret))))

(defn create-machine-token
  "Token for a non-human API client (agent, script). Marked `:machine? true`,
  which makes it **read-only by default**: reads pass, writes are dropped unless
  recording mode is on (see et.mu.server.recording-mode)."
  [user-id username]
  (create-token {:user-id user-id :username username :is-admin false :machine? true}))

(defn verify-token [token]
  (try
    (jwt/unsign token (jwt-secret))
    (catch Exception _ nil)))

(defn extract-token [req]
  (when-let [auth-header (get-in req [:headers "authorization"])]
    (when (str/starts-with? auth-header "Bearer ")
      (subs auth-header 7))))
