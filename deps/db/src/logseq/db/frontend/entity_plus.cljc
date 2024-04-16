(ns logseq.db.frontend.entity-plus
  "Add map ops such as assoc/dissoc to datascript Entity.

   NOTE: This doesn't work for nbb/sci yet because of https://github.com/babashka/sci/issues/639"
  ;; Disable clj linters since we don't support clj
  #?(:clj {:clj-kondo/config {:linters {:unresolved-namespace {:level :off}
                                        :unresolved-symbol {:level :off}}}})
  (:require [cljs.core]
            #?(:org.babashka/nbb [datascript.db])
            [datascript.impl.entity :as entity :refer [Entity]]
            [logseq.db.frontend.content :as db-content]
            [datascript.core :as d]
            [logseq.db.frontend.property :as db-property]))

(defn db-based-graph?
  "Whether the current graph is db-only"
  [db]
  (= "db" (:db/type (d/entity db :logseq.kv/db-type))))

(def lookup-entity @#'entity/lookup-entity)
(defn lookup-kv-then-entity
  ([e k] (lookup-kv-then-entity e k nil))
  ([^Entity e k default-value]
   (case k
     :block/raw-content
     (lookup-entity e :block/content default-value)

     :block/raw-properties
     (lookup-entity e :block/properties default-value)

     :block/properties
     (let [db (.-db e)]
       (if (db-based-graph? db)
         (let [result (lookup-entity e k default-value)]
           (->>
            (keep (fn [pair-e]
                    (when pair-e
                      (if-let [pid (:db/ident (lookup-entity pair-e :property/pair-property nil))]
                        {pid (lookup-entity pair-e pid nil)}
                        (prn "Error: outdated property pair entity should be deleted: " pair-e)))) result)
            (into {})))
         (lookup-entity e :block/properties nil)))

     :block/content
     (or
      (get (.-kv e) k)
      (let [result (lookup-entity e k default-value)
            refs (:block/refs e)
            tags (:block/tags e)]
        (or
         (when (string? result)
           (db-content/special-id-ref->page-ref result (distinct (concat refs tags))))
         default-value)))

     (or (get (.-kv e) k)
         (if (and (not (db-property/db-attribute-properties k))
                  (db-property/property? k)
                  (db-based-graph? (.-db e))
                  (not (:property/pair-property e))) ; property pair will be direct access
           (k (first (filter #(some? (k %)) (lookup-entity e :block/properties nil))))
           (lookup-entity e k default-value))))))

#?(:org.babashka/nbb
   nil
   :default
   (extend-type Entity
     cljs.core/IEncodeJS
     (-clj->js [_this] nil)                 ; avoid `clj->js` overhead when entity was passed to rum components

     IAssociative
     (-assoc [this k v]
       (assert (keyword? k) "attribute must be keyword")
       (set! (.-kv this) (assoc (.-kv this) k v))
       this)
     (-contains-key? [e k] (not= ::nf (lookup-kv-then-entity e k ::nf)))

     IMap
     (-dissoc [this k]
       (assert (keyword? k) "attribute must be keyword")
       (set! (.-kv this) (dissoc (.-kv this) k))
       this)

     ICollection
     (-conj [this entry]
       (if (vector? entry)
         (let [[k v] entry]
           (-assoc this k v))
         (reduce (fn [this [k v]]
                   (-assoc this k v)) this entry)))

     ILookup
     (-lookup
       ([this attr] (lookup-kv-then-entity this attr))
       ([this attr not-found] (lookup-kv-then-entity this attr not-found)))))
