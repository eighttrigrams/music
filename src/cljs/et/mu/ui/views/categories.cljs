(ns et.mu.ui.views.categories
  "The owner's vocabulary: categories (\"artist\", \"manufacturer\") holding entities
  (\"Caroline Polachek\", \"Roland\"). Posts are assigned entities from the Edit
  modal on a card, and the feed's filter menu narrows by them.

  Reading the vocabulary is public — it is what the feed's filter is made of, and
  anyone may filter. Making and unmaking it is not: this page is reachable only
  when signed in, and every write it does is a mutation the server gates."
  (:require [reagent.core :as r]
            [clojure.string :as str]
            [et.mu.ui.state :as state]))

(defn- name-input
  "One line, Enter to submit, cleared once the server has taken it."
  [_opts]
  (let [value (r/atom "")]
    (fn [{:keys [placeholder class label on-submit]}]
      (let [submit (fn []
                     (when-not (str/blank? @value)
                       (on-submit (str/trim @value) #(reset! value ""))))]
        [:div {:class class}
         [:input {:type "text" :placeholder placeholder
                  :value @value
                  :on-change #(reset! value (-> % .-target .-value))
                  :on-key-down #(when (= (.-key %) "Enter") (submit))}]
         [:button {:on-click submit :disabled (str/blank? @value)} label]]))))

(defn- entity-row [{:keys [id name]}]
  [:div.entity-row
   [:span.entity-name name]
   [:button.secondary.danger {:on-click #(state/delete-entity id)} "Delete"]])

(defn- category-block [{:keys [id name entities]}]
  [:div.category
   [:div.category-head
    [:h2.category-name name]
    [:button.secondary.danger {:on-click #(state/delete-category id)} "Delete"]]
   (if (empty? entities)
     [:div.category-empty "No entities yet."]
     [:div.entity-rows
      (for [entity entities]
        ^{:key (:id entity)}
        [entity-row entity])])
   [name-input {:placeholder "New entity" :class "add-entity" :label "Add"
                :on-submit (fn [value done] (state/add-entity id value done))}]])

(defn categories-tab []
  (let [{:keys [categories]} @state/*app-state]
    [:div.categories
     [name-input {:placeholder "New category" :class "add-category" :label "Add category"
                  :on-submit (fn [value done] (state/add-category value done))}]
     (if (empty? categories)
       [:div.empty "No categories yet."]
       (for [category categories]
         ^{:key (:id category)}
         [category-block category]))]))
