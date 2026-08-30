(ns et.mu.ui.core
  (:require [reagent.dom.client :as rdomc]
            [reagent.core :as r]
            [et.mu.ui.state :as state]
            [et.mu.ui.views.videos :as videos]
            [et.mu.ui.views.categories :as categories]
            [et.mu.ui.views.projects :as projects]
            ;; Compiled from source off "../corvo/src/lib" on :source-paths.
            [net.eighttrigrams.corvo.app :as corvo]))

(defn login-form []
  (let [username (r/atom "")
        password (r/atom "")]
    (fn []
      (let [do-login #(state/login @username @password
                                   (fn [] (reset! username "") (reset! password "")))]
        [:div.login-form
         [:input {:type "text" :auto-complete "off" :placeholder "Username"
                  :value @username
                  :on-change #(reset! username (-> % .-target .-value))
                  :on-key-down #(when (= (.-key %) "Enter") (do-login))}]
         [:input {:type "password" :placeholder "Password"
                  :value @password
                  :on-change #(reset! password (-> % .-target .-value))
                  :on-key-down #(when (= (.-key %) "Enter") (do-login))}]
         [:button {:on-click do-login} "Sign in"]]))))

(defn- page-nav
  "No router: the page is a key in app-state. Only the owner has a second page to
  go to, so this shows up only when signed in."
  [page]
  [:div.page-nav
   [:button.page-link {:class (when (= page :feed) "active")
                       :on-click #(state/set-page :feed)} "Feed"]
   [:button.page-link {:class (when (= page :categories) "active")
                       :on-click #(state/set-page :categories)} "Categories"]
   [:button.page-link {:class (when (= page :projects) "active")
                       :on-click #(state/set-page :projects)} "Projects"]
   [:button.page-link {:class (when (= page :corvo) "active")
                       :on-click #(state/set-page :corvo)} "Chords"]])

(defn- public-nav
  "The pages anyone may see. Corvo is public, so its link has to be reachable
  signed out — but Categories and Projects are the owner's alone, so this is a
  short separate list rather than page-nav shown to everyone."
  [page]
  [:div.page-nav
   [:button.page-link {:class (when (not= page :corvo) "active")
                       :on-click #(state/set-page :feed)} "Feed"]
   [:button.page-link {:class (when (= page :corvo) "active")
                       :on-click #(state/set-page :corvo)} "Chords"]])

(defn- top-bar []
  (let [{:keys [auth-required? logged-in? show-login? dark-mode page]} @state/*app-state]
    [:div.top-bar
     [:div.brand
      [:span.brand-mark "♫"]
      [:span.brand-name "Music"]]
     [:div.top-bar-right
      (when logged-in? [page-nav page])
      ;; Added rather than folded into the line above, so the signed-in path is
      ;; exactly what it was.
      (when-not logged-in? [public-nav page])
      [:button.dark-mode-toggle
       {:on-click state/toggle-dark-mode
        :title (if dark-mode "Switch to light" "Switch to dark")}
       (if dark-mode "☀" "☾")]
      (cond
        (not auth-required?) nil
        logged-in? [:button.secondary {:on-click state/logout} "Sign out"]
        show-login? nil
        :else [:button.secondary
               {:on-click #(swap! state/*app-state assoc :show-login? true)} "Sign in"])]]))

(defn app []
  (let [{:keys [auth-required? logged-in? show-login? error page]} @state/*app-state]
    (if (nil? auth-required?)
      [:div.loading "Loading…"]
      [:div
       [top-bar]
       (when error
         [:div.error error [:button.error-dismiss {:on-click state/clear-error} "×"]])
       (when (and auth-required? (not logged-in?) show-login?)
         [login-form])
       [:div.main-layout
        ;; `logged-in?` is asked again here rather than trusted from the nav:
        ;; signing out sets the page back to the feed, and this is what makes
        ;; that one line's failing not enough to show a private page.
        (cond
          ;; Corvo is public: an explicit allow *above* the check, so the
          ;; invariant the comment describes is untouched for every other page.
          (= page :corvo) [corvo/app]
          (not logged-in?) [videos/videos-tab]
          (= page :categories) [categories/categories-tab]
          (= page :projects) [projects/projects-tab]
          :else [videos/videos-tab])]])))

(defonce root (rdomc/create-root (.getElementById js/document "app")))

(defn init []
  (state/setup-dark-mode!)
  (state/fetch-auth-required)
  (rdomc/render root [app]))
