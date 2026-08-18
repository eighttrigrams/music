(ns et.mu.projects-integration-test
  "Projects are the owner's notes, and the first thing in music that is private
  on the read side. So two things are pinned here: the CRUD itself, and that an
  anonymous caller is turned away from *every* route of it — including the GETs,
  which everywhere else in this app answer anybody."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [et.mu.db.project :as db.project]
            [et.mu.integration-helpers :refer [with-integration-db with-real-auth
                                               with-prod-app *ds* *user-id* API token-for
                                               GET-json POST-json PUT-json DELETE-json]]))

(use-fixtures :each with-integration-db)

(defn- project! [title body]
  (:body (POST-json "/api/projects" {:title title :body body})))

(deftest project-crud
  (let [created (POST-json "/api/projects" {:title "Sampler rack" :body "# Plan\n\nBuy cables."})]
    (is (= 201 (:status created)))
    (is (= "Sampler rack" (:title (:body created))))
    (is (= "# Plan\n\nBuy cables." (:body (:body created))))
    (let [id (:id (:body created))]
      (testing "listed, and readable at its own id"
        (is (= ["Sampler rack"] (map :title (:body (GET-json "/api/projects")))))
        (is (= 200 (:status (GET-json (str "/api/projects/" id)))))
        (is (= "# Plan\n\nBuy cables." (:body (:body (GET-json (str "/api/projects/" id)))))))
      (testing "newest first"
        (project! "Later" "")
        (is (= ["Later" "Sampler rack"] (map :title (:body (GET-json "/api/projects"))))))
      (testing "saved"
        (let [saved (PUT-json (str "/api/projects/" id) {:title "Sampler rack v2"
                                                         :body "## Plan\n\nCables bought."})]
          (is (= 200 (:status saved)))
          (is (= "Sampler rack v2" (:title (:body saved))))
          (is (= "## Plan\n\nCables bought." (:body (:body saved))))))
      (testing "delete answers 404 the second time"
        (is (= 200 (:status (DELETE-json (str "/api/projects/" id)))))
        (is (= 404 (:status (DELETE-json (str "/api/projects/" id)))))
        (is (= 404 (:status (GET-json (str "/api/projects/" id)))))))))

(deftest a-body-is-optional-a-title-is-not
  (is (= 201 (:status (POST-json "/api/projects" {:title "Title only"}))))
  (is (= "" (:body (first (:body (GET-json "/api/projects")))))
      "a note is often a title first and prose later")
  (doseq [bad [{:title ""} {:title "   "} {} {:title 42} {:title {:a 1}} {:title ["a"]}]]
    (testing (str "refused: " (pr-str bad))
      (is (= 400 (:status (POST-json "/api/projects" bad))))))
  (is (= 1 (count (:body (GET-json "/api/projects")))) "and none of them wrote anything"))

(deftest a-put-touches-only-the-fields-it-carries
  (let [project (project! "Rack" "the body")
        id (:id project)]
    (testing "title alone leaves the body where it was"
      (let [saved (PUT-json (str "/api/projects/" id) {:title "Rack, renamed"})]
        (is (= 200 (:status saved)))
        (is (= "Rack, renamed" (:title (:body saved))))
        (is (= "the body" (:body (:body saved))))))
    (testing "body alone leaves the title"
      (let [saved (PUT-json (str "/api/projects/" id) {:body "rewritten"})]
        (is (= "Rack, renamed" (:title (:body saved))))
        (is (= "rewritten" (:body (:body saved))))))
    (testing "an empty body is a legitimate thing to save; an empty title is not"
      (is (= "" (:body (:body (PUT-json (str "/api/projects/" id) {:body ""})))))
      (is (= 400 (:status (PUT-json (str "/api/projects/" id) {:title "  "})))))
    (testing "neither field is nothing to save, not a timestamp bump"
      (is (= 400 (:status (PUT-json (str "/api/projects/" id) {})))))
    (testing "a field that is not a string is refused rather than coerced"
      (is (= 400 (:status (PUT-json (str "/api/projects/" id) {:title 42}))))
      (is (= 400 (:status (PUT-json (str "/api/projects/" id) {:body {:a 1}})))))
    (testing "and after all that refusing, the project reads as the last good save"
      (let [current (:body (GET-json (str "/api/projects/" id)))]
        (is (= "Rack, renamed" (:title current)))
        (is (= "" (:body current)))))
    (testing "404 on an unknown id"
      (is (= 404 (:status (PUT-json "/api/projects/9999" {:title "ghost"})))))))

(deftest modified-at-guards-a-save-against-one-that-landed-first
  (let [project (project! "Shared" "first")
        id (:id project)
        stale "2000-01-01 00:00:00"
        conflicted (PUT-json (str "/api/projects/" id) {:body "mine" :modified_at stale})]
    (is (= 409 (:status conflicted)))
    (is (= "first" (:body (:current (:body conflicted))))
        "the 409 carries the project as it stands, so the client can show what it would have lost")
    (is (= "first" (:body (:body (GET-json (str "/api/projects/" id)))))
        "and nothing was written")
    (testing "the timestamp that was actually read is accepted"
      (let [current (:body (GET-json (str "/api/projects/" id)))
            saved (PUT-json (str "/api/projects/" id)
                            {:body "mine" :modified_at (:modified_at current)})]
        (is (= 200 (:status saved)))
        (is (= "mine" (:body (:body saved))))))
    (testing "leaving it out is last-write-wins, as before"
      (is (= 200 (:status (PUT-json (str "/api/projects/" id) {:body "theirs"})))))
    (testing "a stale save against an id that is not there at all is still a 404"
      (is (= 404 (:status (PUT-json "/api/projects/9999" {:body "x" :modified_at stale})))))))

(deftest every-route-is-the-owners-reads-included
  (with-real-auth
    (let [project (db.project/add-project *ds* *user-id* {:title "Private" :body "prose"})
          id (:id project)
          anonymous (fn [method path & [body]]
                      (API method path (cond-> {:anonymous? true} body (assoc :body body))))]
      (doseq [[method path body] [[:get "/api/projects" nil]
                                  [:get (str "/api/projects/" id) nil]
                                  [:post "/api/projects" {:title "theirs"}]
                                  [:put (str "/api/projects/" id) {:title "theirs"}]
                                  [:delete (str "/api/projects/" id) nil]]]
        (testing (str method " " path)
          (let [response (anonymous method path body)]
            (is (= 401 (:status response)))
            (is (= {:error "Authentication required"} (:body response))
                "the same body wrap-auth answers, so the two never disagree about a URI"))))
      (testing "nothing an anonymous caller sent was written"
        (is (= [{:title "Private" :body "prose"}]
               (map #(select-keys % [:title :body]) (db.project/list-projects *ds* *user-id*)))))
      (testing "and the owner reads it all with a token"
        (let [listed (API :get "/api/projects" {:token (token-for *user-id*)})]
          (is (= 200 (:status listed)))
          (is (= ["Private"] (map :title (:body listed)))))))))

(deftest somebody-elses-project-is-answered-as-one-that-does-not-exist
  (let [project (project! "Mine" "prose")
        id (:id project)
        as-stranger (fn [method & [body]]
                      (API method (str "/api/projects/" id)
                           (cond-> {:as-user 9999} body (assoc :body body))))
        unknown (API :get "/api/projects/9999" {:as-user 9999})]
    (doseq [[label response] [["get" (as-stranger :get)]
                              ["put" (as-stranger :put {:title "yours"})]
                              ["delete" (as-stranger :delete)]]]
      (testing (str label " is a 404, byte for byte the one an unknown id gets")
        (is (= 404 (:status response)))
        (is (= (:body unknown) (:body response))
            "a 403 would confirm it is there and let a stranger count the owner's notes")))
    (testing "the stranger's own listing is empty rather than refused"
      (is (= [] (:body (API :get "/api/projects" {:as-user 9999})))))
    (testing "and the owner still has what he had"
      (is (= "prose" (:body (:body (GET-json (str "/api/projects/" id)))))))))

(deftest the-mutating-project-routes-are-gated-by-wrap-auth-too
  (with-prod-app
    (is (= 401 (:status (API :post "/api/projects" {:anonymous? true :body {:title "x"}}))))
    (is (= 401 (:status (API :post "/api/projects" {:body {:title "x"}})))
        "the dev skip-logins header buys nothing once the wrapper is active")
    (let [token (token-for *user-id*)
          created (API :post "/api/projects" {:token token :body {:title "Real" :body "prose"}})]
      (is (= 201 (:status created)))
      (is (= ["Real"] (map :title (:body (API :get "/api/projects" {:token token})))))
      (is (= 401 (:status (API :get "/api/projects" {:anonymous? true})))
          "and the read stays shut whether or not wrap-auth is the one answering"))))

(deftest describe-lists-the-project-routes
  (let [paths (->> (:body (GET-json "/api/describe"))
                   (map (juxt :method :path))
                   set)]
    (doseq [route [["GET" "/api/projects"]
                   ["POST" "/api/projects"]
                   ["GET" "/api/projects/:id"]
                   ["PUT" "/api/projects/:id"]
                   ["DELETE" "/api/projects/:id"]]]
      (is (contains? paths route)))))

(deftest an-audio-url-rides-along-with-the-note
  (let [mp3 "https://files.example.com/take-3.mp3"
        created (POST-json "/api/projects" {:title "Take 3" :body "flat in bar 9"
                                            :audio_url mp3})
        id (:id (:body created))]
    (is (= 201 (:status created)))
    (is (= mp3 (:audio_url (:body created))))
    (testing "and comes back on both reads, since the player is drawn from the list"
      (is (= [mp3] (map :audio_url (:body (GET-json "/api/projects")))))
      (is (= mp3 (:audio_url (:body (GET-json (str "/api/projects/" id)))))))
    (testing "a note made without one reads as empty, never nil"
      (is (= "" (:audio_url (:body (POST-json "/api/projects" {:title "No audio"}))))))
    (testing "swapped for another"
      (let [other "https://files.example.com/take-4.mp3"]
        (is (= other (:audio_url (:body (PUT-json (str "/api/projects/" id)
                                                  {:audio_url other})))))))
    (testing "blank takes the player off the note again, rather than being ignored"
      (is (= "" (:audio_url (:body (PUT-json (str "/api/projects/" id)
                                             {:audio_url ""})))))
      (is (= "" (:audio_url (:body (GET-json (str "/api/projects/" id)))))))
    (testing "and it is a field like the others: left out, it keeps what it had"
      (PUT-json (str "/api/projects/" id) {:audio_url mp3})
      (is (= mp3 (:audio_url (:body (PUT-json (str "/api/projects/" id)
                                              {:title "Take 3, mixed"})))))
      (is (= "Take 3, mixed" (:title (:body (GET-json (str "/api/projects/" id)))))))
    (testing "carried alone it is still something to save, not a bare timestamp bump"
      (is (= 200 (:status (PUT-json (str "/api/projects/" id) {:audio_url mp3})))))))

(deftest only-a-link-a-browser-could-fetch-gets-in
  (let [id (:id (project! "Host" ""))
        refused (fn [value]
                  (testing (str "refused: " (pr-str value))
                    (is (= 400 (:status (POST-json "/api/projects"
                                                   {:title "x" :audio_url value}))))
                    (is (= 400 (:status (PUT-json (str "/api/projects/" id)
                                                  {:audio_url value}))))))]
    (doseq [value ["files.example.com/take.mp3"     ;; no scheme: relative, not a link
                   "/take.mp3"
                   "ftp://files.example.com/take.mp3"
                   "file:///Users/dan/take.mp3"
                   "javascript:alert(1)"            ;; never into an <audio src>
                   "data:audio/mpeg;base64,AAAA"
                   "http://"                        ;; a scheme and no host
                   "https://"
                   "ht tp://files.example.com/x"    ;; will not parse at all
                   42
                   {:url "https://files.example.com/x.mp3"}
                   ["https://files.example.com/x.mp3"]]]
      (refused value))
    (testing "and none of it was written"
      (is (= "" (:audio_url (:body (GET-json (str "/api/projects/" id))))))
      (is (= ["Host"] (map :title (:body (GET-json "/api/projects"))))))))

(deftest http-is-a-dev-convenience-and-production-takes-https-only
  (let [plain "http://localhost:8000/take.mp3"
        id (:id (project! "Host" ""))]
    (testing "in dev it goes through — the page is plain http too, so it plays"
      (is (= 201 (:status (POST-json "/api/projects" {:title "Local"
                                                      :audio_url plain}))))
      (is (= plain (:audio_url (:body (PUT-json (str "/api/projects/" id)
                                                {:audio_url plain}))))))
    (testing "in production it is refused: an https page cannot play http audio"
      (with-prod-app
        (let [token (token-for *user-id*)
              post (fn [body] (API :post "/api/projects" {:token token :body body}))
              put (fn [body] (API :put (str "/api/projects/" id) {:token token :body body}))]
          (is (= 400 (:status (post {:title "Local" :audio_url plain}))))
          (is (= "Audio URL must be https" (:error (:body (post {:title "Local"
                                                                :audio_url plain})))))
          (is (= 400 (:status (put {:audio_url plain}))))
          (testing "https is what it wants, and blank is still how it is cleared"
            (is (= 201 (:status (post {:title "Remote"
                                       :audio_url "https://files.example.com/x.mp3"}))))
            (is (= "" (:audio_url (:body (put {:audio_url ""}))))))
          (testing "and the http one that was already there is left alone until touched"
            (is (= 200 (:status (put {:title "Host, renamed"}))))))))
    (testing "back in dev the stored http link still reads back"
      (is (= plain (:audio_url (:body (PUT-json (str "/api/projects/" id)
                                                {:audio_url plain}))))))))
