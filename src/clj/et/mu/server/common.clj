(ns et.mu.server.common
  (:require [et.mu.db :as db]
            [et.mu.auth :as auth]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [aero.core :as aero]
            [next.jdbc :as jdbc]
            [honey.sql :as sql]
            [taoensso.telemere :as tel]))

(defonce ds (atom nil))
(defonce *config (atom nil))

(defn load-config []
  (let [config-file (io/file "config.edn")]
    (if (.exists config-file)
      (do
        (tel/log! :info "Loading configuration from config.edn")
        (aero/read-config config-file))
      (do
        (tel/log! :info "config.edn not found, using defaults")
        {}))))

(defn ensure-ds []
  (when (nil? @ds)
    (when (nil? @*config)
      (reset! *config (load-config)))
    (let [conn (db/init-conn (get @*config :db {:type :sqlite-memory}))]
      (reset! ds conn)))
  @ds)

(defn prod-mode? []
  (let [on-fly? (some? (System/getenv "FLY_APP_NAME"))
        dev-mode? (= "true" (System/getenv "DEV"))
        admin-pw (System/getenv "ADMIN_PASSWORD")]
    (cond
      (or on-fly? (not dev-mode?))
      (do (when-not admin-pw
            (throw (ex-info "ADMIN_PASSWORD required in production" {})))
          true)
      admin-pw
      true
      :else
      false)))

(defn allow-skip-logins? []
  (and (true? (:dangerously-skip-logins? @*config))
       (not (prod-mode?))))

(defn get-user-from-request
  "Resolve the acting user. In prod, from the verified JWT. In dev with
  skip-logins, defaults to the first user (or the nil-owner admin when there
  are none), optionally overridden by an x-user-id header."
  [req]
  (or (some-> (auth/extract-token req) auth/verify-token)
      (when (allow-skip-logins?)
        (let [user-id-str (get-in req [:headers "x-user-id"])]
          (if (or (nil? user-id-str) (= user-id-str "null"))
            (let [first-user (jdbc/execute-one! (db/get-conn (ensure-ds))
                               (sql/format {:select [:id] :from [:users]
                                            :order-by [[:id :asc]] :limit 1})
                               db/jdbc-opts)]
              {:user-id (:id first-user) :is-admin true})
            {:user-id (Integer/parseInt user-id-str) :is-admin false})))))

(defn get-user-id [req]
  (:user-id (get-user-from-request req)))

(defn admin-password []
  (or (System/getenv "ADMIN_PASSWORD")
      (when (= "true" (System/getenv "DEV")) "admin")
      (throw (ex-info "ADMIN_PASSWORD env var is required" {}))))

(defn is-admin? [req]
  (:is-admin (get-user-from-request req)))

(defn parse-int-opt [s]
  (when (and s (not (str/blank? (str s))))
    (try (Integer/parseInt (str/trim (str s)))
         (catch NumberFormatException _ nil))))

(def conflict-error
  "This item was changed elsewhere (e.g. another tab). Its current version has been reloaded — review your edit and save again.")

(defn conflict-or-not-found
  "Response for an optimistic-concurrency update that matched no row. `current`
  is the freshly-fetched row (nil when it no longer exists): when present the
  write lost a modified_at race (409 carrying the current row); otherwise 404."
  [current not-found-msg]
  (if current
    {:status 409 :body {:success false :error conflict-error :current current}}
    {:status 404 :body {:error not-found-msg}}))
