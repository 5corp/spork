;;Implementation of an auxillary map that is designed to
;;work with an assumably large database, upon which the
;;fields and values of the map are drawn.  This is
;;designed specifically for the use-case of a
;;column-based entitystore, where columns are
;;mappings of entityID to a particular field value.
;;Still, this will work with any abstract mapping,
;;where the "database" is an associative structure
;;of the type {field {id field-value}}, such that
;;(get some-passmap field) will use the backing
;;store to find the field associated with the entity -
;;basically perform a join.
;;As an optimization, we defer joins until necessary,
;;and we cache joins into a persistentmap.  "updates"
;;like assoc, dissoc, etc,. manifest as operations on
;;the cached map, so that the lazy-map will, over time,
;;build up a new map on-demand, and avoid joining all
;;fields of the entity.
(ns spork.data.passmap
  (:require [spork.data [mutable :as mutable]]))

;;We care about adds and drops....right?
;;If we just always assoc a sentinel value when we merge the map,
;;like ::dropped, we can check for it on entity merge...
;;Original design had the entity map diffing based on the fields.
;;We could alternately use a different type of mapentry...
;;Or maintain a hashmap of altered fields, and how they're altered.
;;Having a single mutable hashmap that's shared by all ancestors
;;of the fields could be useful....
;;Basically, since the entity was created, we have a journal of
;;all the modifications that occurred relative to the initial
;;pull....Can we handle dissocing better?  Maybe, explicitly
;;handle dissocing....if we assoc ::drop to the map then
;;on merge it'll get dropped....maybe wrap an acessor around it?
;;Most of the time, we just assoc nil anyway, but sometimes we
;;dissoc...we could always dissoc the db....to indicate inconsistency
;;with the original db, so that on merge, we can compute a diff.
;;alternately, we can define adds and drops...
;;right now, m is === adds, we don't keep track of drops.
;;If we drop a field and add it later, then what...
;;Is there a better way to do a diff?
;;We know from the fields in m which fields were actually
;;read.  We also know, which fields have changed if we
;;keep track of altered.  Currently, we infer that if
;;there's an altered field and not a field in m, that the
;;alteration implies a drop....

;;a passthrough map...a map that references a db of fields in the background to implement its map operations.
;;if we make the assumption that the db contains all possible fields for m, then db is just
;;the means for computing fields in m.  When we drop items from the passmap,
;;we assoc a sentinel value to it, rather than dropping outright (note...we're still
;;paying the cost of associating....)

;;implementing hashing for this guy.  Hashing will force us to flush the
;;backing map, effectively building up the entire map.  At that point, we
;;don't need the backing map anymore...  So it gets ditched.  The new map
;;now fully shadows/contains the old.

;;Cool..for map implementations, we can cheat and use APersistetMap
;;to carry us through...
(defn mapEquals [^clojure.lang.IPersistentMap m1 obj]
  (clojure.lang.APersistentMap/mapEquals m1 obj))
        

(defmacro some-set [x]
  `(if (identical? ~x #{}) nil
       ~x))

(defmacro join! [db-keys db]
  `(do (doseq [k# (or (some-set ~db-keys) (keys ~db))]
         (.entryAt ~'this k#)) ;"forces" the join
       (set! ~db-keys #{})
       (set! ~db nil)))

(defmacro get!
  "This is a quick performance hack to allow direct method invocation
   with a type-hinted java.util.Map object.  If we didn't do this,
   using the intermediate let binding, expressions aren't hinted and
   we wind up with reflections.  It may seem like overkill, but
   this ends up being ~3x faster than using clojure.core/get,
   which is still quite fast.  Still, we're on a hot path,
   so I'm using this to provide compatibility between
   persistent maps and hashmaps."
  [m k]
  (let [the-map (with-meta (gensym "the-map")  {:tag 'java.util.Map})]
    `(let [~the-map ~m]
       (.get ~the-map ~k))))

(deftype PassMap [id
                  ^:unsynchronized-mutable  ^clojure.lang.IPersistentMap m
                  ^:unsynchronized-mutable  ^clojure.lang.IPersistentMap db
                  ;;the original keys in the database, what we're lazily passing through.                  
                  ^:unsynchronized-mutable  ^clojure.lang.IPersistentSet db-keys
                  ^boolean mutable
                  ]
  clojure.lang.IHashEq
  (hasheq [this]   (if-not db (.hasheq ^clojure.lang.IHashEq m)
                       ;;we need to go ahead and do an eager join with the db.
                           (do  (join! db-keys db)
                                (.hasheq ^clojure.lang.IHashEq m))))
  (hashCode [this]
    (if-not db (.hashCode ^clojure.lang.IHashEq m)
                       ;;we need to go ahead and do an eager join with the db.
            (do  (join!  db-keys db)
                 (.hashCode ^clojure.lang.IHashEq m))))
  (equals [this o] (clojure.lang.APersistentMap/mapEquals this o))
  (equiv  [this o]
    (cond (identical? this o) true
          (instance? clojure.lang.IHashEq o) (== (hash this) (hash o))
          (or (instance? clojure.lang.Sequential o)
              (instance? java.util.List o))  (clojure.lang.Util/equiv (seq this) (seq o))
              :else nil))  
  clojure.lang.IObj
  (meta     [this]    (.meta ^clojure.lang.IObj m))
  (withMeta [this xs] (PassMap. id (with-meta ^clojure.lang.IObj m xs) db db-keys mutable))
  clojure.lang.IPersistentMap
  (valAt [this k]
    (let [^clojure.lang.MapEntry res (.entryAt m k)]
      (if res (.val res)
          (if-let #_[res  (.valAt  ^clojure.lang.IPersistentMap (.valAt db k {}) id)]
                  ;;temporarily rewritten to accomodate mutable hashmaps...
                  [res  (.get  ^java.util.Map (.valAt db k {}) id)]
          (do ;(println :caching k)
              (set! m (.assoc m k res))
              res)
          (do ;(println :nilcache)
              (set! m (.assoc m k nil))
              nil)))))
  (valAt [this k not-found]
    (if-let [res (.valAt this k)]
      res
      not-found))
  (entryAt [this k] (if-let [res (.entryAt m k)]
                      res
                      (when-let [k (if  (some-set db-keys) (db-keys k)
                                        k)]
                        ;;temporarily rewritten to be compatible with maps.
                        (when-let #_[^clojure.lang.MapEntry res (.entryAt ^clojure.lang.IPersistentMap (.valAt db k {}) id)]
                                  [v (get! (or (get! db k) {}) id)]
                          (do (set! m (.assoc m k v #_(.val res)))
                              (clojure.lang.MapEntry. k v #_(.val res))                              
                              )))))
  (assoc [this k v]   (PassMap. id (.assoc m k v)  db db-keys mutable))
  (cons  [this e]     (PassMap. id (.cons m e)     db  db-keys mutable))
  (without [this k]   (PassMap. id (.without m k) (if-not mutable (.without db k) db) (.disjoin db-keys k) mutable))
  clojure.lang.Seqable
  (seq [this] (concat (seq m)
                      (filter identity
                              (map (fn [^java.util.Map$Entry e]
                                     (if (.containsKey ^clojure.lang.IPersistentMap m (.getKey e))
                                       nil
                                       (.entryAt this (.getKey e)))) db))))
  clojure.lang.Counted
  (count [this]      (do (when db (join! db-keys db)) (.count m)))
  java.util.Map ;;some of these aren't correct....might matter.
  (put    [this k v]  (.assoc this k v))
  (putAll [this c] (PassMap. id (.putAll ^java.util.Map m c) db db-keys mutable))
  (clear  [this] (PassMap.  id {} nil #{} mutable))
  (containsKey   [this k]
    (or (.containsKey ^java.util.Map m k)
        (and db
             (when-let [k (if  (some-set db-keys) (db-keys k)
                               k)]            
               (.containsKey ^java.util.Map db k)))))
  (containsValue [this o] (throw (Exception. "containsValue not supported")))
  (entrySet [this]   (do  (when db (join!  db-keys db))
                          (.entrySet ^java.util.Map m))) 
  (keySet   [this]   (do (when db (join!  db-keys db)) 
                         (.keySet ^java.util.Map m)))   
  clojure.core.protocols/IKVReduce
  (kv-reduce [this f init]
    (reduce-kv (fn [acc k v]
                 (if (.containsKey ^clojure.lang.IPersistentMap m k)
                   acc
                   (if-let [^clojure.lang.MapEntry e (.entryAt this k)]
                     (f acc (.key e) (.val e))
                     acc))) (reduce-kv f init m) db))
  )


(defn lazy-join
  ([source k] (PassMap. k {} source #{} false))
  ([source k keyset]  (PassMap. k {} source keyset false)))

;;quick hack to allow wrapped hashmaps; re-uses the existing
;;passmap code.  Doesn't alter the underlying hashmap on .dissoc.
(defn lazy-join-mutable
  ([source k] (PassMap. k {} (mutable/hashmap->mutmap source) #{} true))
  ([source k keyset]  (PassMap. k {} (mutable/hashmap->mutmap source) keyset true)))

;;testing 

;;the purpose of having a reference like this...
;;is that we have a row into a columnar db...
;;The current entity-based idiom is that we
;;have a persistent reference to the rows in the db.
;;We can munge on that reference all we want, then
;;commit the result after we're done.  For the single-threaded
;;version, this works fine.

;;If we creata a mutable reference, what does it mean to modify
;;fields on the ref?  If we assoc a new field, do we modify the
;;underlying db?  Allow adding column entries?  Allow removing
;;column entries?  This is turning into an in-memory database..
;;with similar semantics.  Naive implementation just says:
;;mutate the entries directly.  It's up to the caller to
;;implement said functionality over top of this  responsibly.

;;Looking at a mutable view of entries in a database...debating
;;whether this fits our semantics...Currently, assumes non-concurrent
;;access...so this "can" work.

;;assuming we're not doing concurrent access...
;;just using mutation as an optimization.
;;we don't need locks and the like.
;;alternately...
;;we can have a model where new fields are appended to the
;;entity ref...dropped fields are mutably removed....
;;updated fields are passed through?

;;In the case where we're operating on a single entity,
;;I think this works okay.

;;Alternately, we just disallow row operations and
;;force everything to delegate to the store via
;;add/remove entry?





;;Updated thoughts on buffering the underlying db...
;;Semantics follow:
;;We maintain the local cache, the db, and a set of db-keys.
;;  If a key is dropped from the db,
;;     future assoc/dissoc should be routed to the cache...
;;     the db-keys should remove the dropped key.
;;  If a key is added,
;;     the key should be modified in the cache.
;;  IF a key is read
;;     the key should be looked up in the cache,
;;     then - if not dropped from the db -
;;      looked up in the db.

;;So, if we add an extra bit of info
