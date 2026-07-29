(ns et.mu.server.category-handler
  (:require [clojure.string :as str]
            [et.mu.server.common :as common]
            [et.mu.db.category :as db.category]))

(defn list-categories-handler
  "GET /api/categories — the owner's categories, each with its entities nested,
  both alphabetical. Owner-only: 401 for an anonymous request."
  [req]
  (if (common/authenticated? req)
    {:status 200 :body (db.category/list-categories (common/ensure-ds))}
    common/unauthorized))

(defn add-category-handler
  "POST /api/categories — create a category from {:name}. 400 on a blank name or
  one already taken."
  [req]
  (let [name (some-> (get-in req [:body :name]) str str/trim)]
    (if (str/blank? name)
      {:status 400 :body {:error "Name required"}}
      (if-let [category (db.category/add-category (common/ensure-ds) name)]
        {:status 201 :body category}
        {:status 400 :body {:error "That category already exists"}}))))

(defn delete-category-handler
  "DELETE /api/categories/:id — remove a category together with its entities and
  every assignment those entities had. 404 when the id matches nothing."
  [req]
  (let [id (common/parse-int-opt (get-in req [:params :id]))
        result (when id (db.category/delete-category (common/ensure-ds) id))]
    (if (:success result)
      {:status 200 :body result}
      {:status 404 :body {:error "Category not found"}})))

(defn add-entity-handler
  "POST /api/categories/:id/entities — add an entity named {:name} to the
  category. 400 on a blank name or one already taken inside it, 404 when the
  category is unknown."
  [req]
  (let [ds (common/ensure-ds)
        category-id (common/parse-int-opt (get-in req [:params :id]))
        name (some-> (get-in req [:body :name]) str str/trim)]
    (cond
      (nil? (some->> category-id (db.category/get-category ds)))
      {:status 404 :body {:error "Category not found"}}

      (str/blank? name)
      {:status 400 :body {:error "Name required"}}

      :else
      (if-let [entity (db.category/add-entity ds category-id name)]
        {:status 201 :body entity}
        {:status 400 :body {:error "That entity already exists"}}))))

(defn delete-entity-handler
  "DELETE /api/entities/:id — remove an entity and every assignment it had. 404
  when the id matches nothing."
  [req]
  (let [id (common/parse-int-opt (get-in req [:params :id]))
        result (when id (db.category/delete-entity (common/ensure-ds) id))]
    (if (:success result)
      {:status 200 :body result}
      {:status 404 :body {:error "Entity not found"}})))
