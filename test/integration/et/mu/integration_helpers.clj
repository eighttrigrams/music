(ns et.mu.integration-helpers
  (:require [ring.mock.request :as mock]
            [et.mu.db :as db]
            [et.mu.db.user :as db.user]
            [et.mu.server :as server]
            [et.mu.server.common :as common]
            [et.mu.auth :as auth]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.params :refer [wrap-params]]
            [cheshire.core :as json]
            [taoensso.telemere :as tel]))

(tel/remove-handler! :default/console)

(def ^:dynamic *app* nil)
(def ^:dynamic *ds* nil)
(def ^:dynamic *user-id* nil)

(defn make-app []
  (-> server/app-routes
      (wrap-params)
      (wrap-json-body {:keywords? true})
      (wrap-json-response)))

(defn with-integration-db [f]
  (let [conn (db/init-conn {:type :sqlite-memory})]
    (try
      (reset! common/ds conn)
      (reset! common/*config {:dangerously-skip-logins? true})
      (let [user (db.user/create-user conn "test-user" "testpass")]
        (with-redefs [common/prod-mode? (constantly false)]
          (binding [*app* (make-app)
                    *ds* conn
                    *user-id* (:id user)]
            (f))))
      (finally
        (reset! common/ds nil)
        (reset! common/*config nil)
        (when-let [pc (:persistent-conn conn)]
          (.close pc))))))

(defn with-real-auth* [f]
  (with-redefs [common/allow-skip-logins? (constantly false)]
    (f)))

(defmacro with-real-auth
  "Build the app as if `:dangerously-skip-logins?` were false, so a request
  without a Bearer token is a genuinely anonymous one."
  [& body]
  `(with-real-auth* (fn [] ~@body)))

(defn token-for [user-id]
  (auth/create-token user-id "test-user" true))

(defn API
  "One request. Without :token the dev skip-logins header identifies the owner
  (:as-user to make that somebody else); with :anonymous? neither is sent, which
  is the only way to see what a visitor sees."
  [method path {:keys [body token anonymous? as-user]}]
  (let [req (cond-> (mock/request method path)
              token (mock/header "Authorization" (str "Bearer " token))
              (and (not token) (not anonymous?))
              (mock/header "X-User-Id" (str (or as-user *user-id*)))
              body (-> (mock/header "Content-Type" "application/json")
                       (mock/body (json/generate-string body))))]
    (update (*app* req) :body #(when (seq %) (json/parse-string % true)))))

(defn GET-json [path] (API :get path {}))
(defn POST-json [path body] (API :post path {:body body}))
(defn PUT-json [path body] (API :put path {:body body}))
(defn DELETE-json [path] (API :delete path {}))
