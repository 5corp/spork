;;TOM SPOON 9 July 2012 
;;A simple library for reading and writing tabular representations of data.
;;Uses map as a container.
;;Vector-of-vector columnar data store.  Backing vectors may be primitive,
;;or rrb-trees or anything that implements persistentvector.
;;Pending implementation of clojure.core.matrix.dataset protocols to
;;enable compatibility with other apps.
(ns spork.util.table
  (:require [clojure [string :as strlib]]
            [clojure [set :as setlib]]
            [clojure.core.reducers :as r]
            [spork.util.reducers]
            [spork.util [clipboard :as board] [parsing :as parse]]
            [spork.cljgui.components [swing :as gui]]
            [spork.util.general  :as general :refer [align-by]]
            [clojure.core.rrb-vector :as rrb])
  (:use [spork.util.vector]
        [spork.util.record  :only [serial-field-comparer key-function]]
        [clojure.pprint :only [pprint]])) 

;Moved generic protocols to util.protocols
;note-> a field is just a table with one field/column....
;thus a table is a set of joined fields....

(defprotocol ITabular 
  (table-fields [x] "Get a vector of fields for table x")
  (table-columns [x] "Get a nested vector of the columns for table x"))
 
(defn tabular? [x] (satisfies? ITabular x))
  
(defprotocol IUnOrdered
  (-unordered? [x] 
   "Helper protocol to indicate that table fields are not ordered."))

(defprotocol ITabularMaker 
  (-make-table [x fields columns] "Allows types to define constructors."))

(defprotocol IFieldDropper
  (-drop-field [x fieldname] "Allows types to implement fast drop operations."))

(defprotocol IField
  (field-name [x] "Returns the name of a field")
  (field-vals [x] "Returns a vector of values for a field"))

(extend-protocol IField 
  nil 
    (field-name [x] nil) 
    (field-vals [x] nil) 
  clojure.lang.PersistentArrayMap 
    (field-name [x] ((comp first keys) x)) 
    (field-vals [x] ((comp first vals) x)) 
  clojure.lang.PersistentHashMap 
    (field-name [x] ((comp first keys) x)) 
    (field-vals [x] ((comp first vals) x)) 
  clojure.lang.PersistentVector 
    (field-name [x] (first  x)) 
    (field-vals [x] (or (second x) []))
    clojure.lang.MapEntry
    (field-name [x] (key  x)) 
    (field-vals [x] (or (val x) [])))

(declare empty-table 
         -conj-field
         -disj-field
         make-table
         table->map
         conj-field)

(defn find-where
  "Similar to clojure.core/some...except it returns the indices where any member 
   of items can be found.  Used to quickly address entries in a vector."
  [items v] 
  (let [valid? (set items)] 
    (->> (map-indexed (fn [i x] (when (valid? x) [i x])) v) 
         (filter #(not (nil? %)))))) 
 
(defn- empty-columns [n] (vec (map (fn [_] []) (range n)))) 
(defn- normalized? 
  "Returns true if every column in the table has the same number of rows." 
  [tbl] 
  (every?  
    (fn [col] (= (count col)  
                 (count (first (table-columns tbl)))))  
    (rest (table-columns tbl)))) 
 
(defn- nil-column [n]  
  (persistent!   
    (reduce conj! (transient []) (take n (repeat nil)))))  
 
(defn- normalize-column [col n] 
  (cond (empty? col) (nil-column n) 
        (vector? col) (if (zero? n) 
                        col 
                        (let [colcount (count col)] 
                          (cond  
                            (= colcount n)  col 
                            (> colcount n) (subvec col 0 n) 
                            :else (persistent! 
                                    (reduce conj! (transient col)  
                                            (nil-column (- n (count col)))))))) 
        :else (normalize-column (vec col) n))) 
 
(defn- normalize-columns [cols] 
  (loop [maxcount (count (first cols)) 
         remaining (rest cols) 
         dirty? false] 
    (if (empty? remaining) 
      (if dirty? 
        (vec (map (fn [c] (normalize-column c maxcount)) cols)) 
        cols)         
      (let [nextcount (count (first remaining))] 
        (if (= nextcount maxcount) 
          (recur maxcount (rest remaining) dirty?) 
          (recur (max maxcount nextcount) (rest remaining) true)))))) 

;;We're offering primitive columns now, using rrb-vectors (until clojure
;;core actually accepts the patch for transients in vector-of for gvec).
;;This should allow us to have a more efficient storage of our tables,
;;particularly if we provide types for them (like int and friends).
;;The biggest reason for this change is to help with memory restricted
;;environs, specifically when we're working with larger datasets. Ideally,
;;we can keep 99% of our interface and get some serious space savings without
;;sacrifing performance.  It may be that space savings end up going toward
;;our strings though, which is a beneifit/limitation of the JVM.
;;RRBVectors may also be desireable for our CES...

;;Consider making this seqable...turning it into a deftype.
(defrecord column-table [fields columns] 
  ITabular  
    (table-fields [x]  fields) 
    (table-columns [x] columns) 
  ITabularMaker 
    (-make-table [x fields columns]  
      (column-table. fields (normalize-columns columns)))    
  clojure.core.protocols/CollReduce
  (coll-reduce [this f]  
    (let [bound   (count fields)
          rbound  (dec (count (first columns)))
          cursor  {} ;(transient {})
          idx (atom 0)
          fetch-record (fn [n m] 
                         (loop [m m
                                i 0]
                           (if (== i bound) m
                               (recur 
                                (assoc ;assoc!
                                 m (nth fields i)
                                          (nth (nth columns i) n))
                                (unchecked-inc i)))))
          next-record! (fn [] (if (== @idx rbound) nil
                                  (fetch-record (swap! idx inc)
                                                cursor)))]
      (when-let [r (zipmap fields (mapv #(nth % 0) columns))]
        (cond (zero? rbound) r
              (== rbound 1) (f r (next-record!))
              :else         (let [init (next-record!)]
                              (loop [ret (f r init )]
                                (if (reduced? ret) @ret
                                    (if-let [nxt (next-record!)]                
                                      (recur (f ret nxt))
                                      ret))))))))
  (coll-reduce [this f init]
    (let [bound   (count fields)
          rbound  (dec (count (first columns)))
          cursor  {};(transient {})
          idx (atom -1) ;ugh...negative really?
          fetch-record (fn [n m] 
                         (loop [m m
                                i 0]
                           (if (== i bound) m
                               (recur 
                                (assoc ;assoc!
                                 m (nth fields i)
                                          (nth (nth columns i) n))
                                (unchecked-inc i)))))
          next-record! (fn [] (if (== @idx rbound) nil
                                  (fetch-record (swap! idx inc)
                                               cursor)))]
      (when-let [r (next-record!)]
        (loop [ret (f init r)]
          (if (reduced? ret) @ret
              (if-let [nxt (next-record!)]                
                  (recur (f ret nxt))
                  ret))))))
  )

(defn make-table  
  "Constructs a new table either directly from a vector of fields and  
   vector of columns, or from a collection of IFields, of the form 
   [[field1 & [column1-values...]] 
    [field2 & [column2-values...]]]  or  
   {:field1 [column1-values]  
    :field2 [column2-values]} " 
  ([fields columns] 
    (->column-table fields (normalize-columns columns))) 
  ([Ifields] (reduce (fn [acc fld]
                      (conj-field  [(field-name fld) (field-vals fld)] acc)) (->column-table [] []) (seq Ifields))))
              
(def empty-table (make-table [] [] ))
(defn ordered-table?
  "Indicates if tbl implements table fields in an unordered fashion."
  [tbl]
  (not (satisfies? IUnOrdered tbl)))

(defn enumerate-fields 
  "Returns a sequence of [fld [column-values]]" 
  [flds cols]  
  (for [[id f] (map-indexed vector flds)] [f (get cols id)]))

(extend-protocol  ITabular
  nil
    (table-fields [x] nil) 
    (table-columns [x] nil)
  clojure.lang.PersistentArrayMap
    (table-fields [x] (vec (keys x)))
    (table-columns [x] (vec (vals x)))
  clojure.lang.PersistentHashMap 
    (table-fields [x] (vec (keys x))) 
    (table-columns [x] (vec (vals x))) 
  clojure.lang.PersistentVector
    (table-fields [x] (vec (map first x))) 
    (table-columns [x] (vec (map #(get % 1) x))))

(extend-protocol ITabularMaker
  nil
    (-make-table [x fields columns] (make-table fields columns))  
  clojure.lang.PersistentArrayMap
    (-make-table [x fields columns]  
       (#(into {} (reverse (enumerate-fields %1 %2))) fields columns)) 
  clojure.lang.PersistentHashMap
    (-make-table [x fields columns] 
       (#(into {} (reverse (enumerate-fields %1 %2))) fields columns)) 
  clojure.lang.PersistentVector 
    (-make-table [x fields columns]  
       (comp vec enumerate-fields) fields columns)) 

(extend-protocol IFieldDropper
  clojure.lang.PersistentArrayMap 
    (-drop-field [x fieldname] (dissoc x fieldname)) 
  clojure.lang.PersistentHashMap 
    (-drop-field [x fieldname] (dissoc x fieldname)))                  

(extend-protocol IUnOrdered 
  clojure.lang.PersistentHashMap
    (-unordered? [x] true)
  clojure.lang.PersistentArrayMap
    (-unordered? [x] true))

(defn count-rows [tbl] 
  (count (first (table-columns tbl))))

(defn has-fields?
  "Determines if every field in fnames exists in tbl as well."
  [fnames tbl]
  (every? (set (table-fields tbl)) fnames))

(defn has-field?
  "Determines if tbl has a field entry for fname."
  [fname tbl] (has-fields? [fname] tbl))

(defn get-field
  "Returns the fieldspec for the field associated with fname, if any.
   Field entry is in the form of {:fieldname [& column-values]}"
  [fname tbl]
  (when (has-field? fname tbl)
    (assoc {} fname (->> (find-where #{fname} (table-fields tbl))
                      (ffirst) 
                      (get (table-columns tbl))))))
 
(defn conj-field 
  "Conjoins a field, named fname with values col, onto table tbl. 
   If no column values are provided, conjoins a normalized column of  
   nils.  If values are provided, they are normalized to fit the table. 
   If the field already exists, it will be shadowed by the new field." 
  ([[fname & [col]] tbl]  
    (if-not (has-field? fname tbl)  
       (-make-table tbl 
         (conj (table-fields tbl) fname)  
         (conj (table-columns tbl)  
               (normalize-column col (count-rows tbl)))) 
       (let [flds  (table-fields tbl) 
             idx (ffirst (find-where #{fname} flds))] 
         (-make-table tbl 
           flds  
           (assoc (table-columns tbl) idx  
                  (normalize-column col (count-rows tbl))))))))  

(defn rename-fields
  "Rename existing fields according to a mapping of old to new fields."
  [lookup tbl]
  (reduce (fn [acc fldname] 
            (if-let [new-name (get lookup fldname)]
              (let [[_ xs] (vec (first (get-field fldname tbl)))] 
                (conj-field [new-name xs] acc))
              (conj-field (vec (first (get-field fldname tbl))) acc)))
          empty-table
          (table-fields tbl)))

(defn conj-fields
  "Conjoins multiple fieldspecs into tbl, where each field is defined by a  
   vector of [fieldname & [columnvalues]]"
  [fieldspecs tbl]
  (let [fieldspecs   (if (tabular? fieldspecs)
                         (enumerate-fields 
                           (table-fields fieldspecs) 
                           (table-columns fieldspecs))
                         fieldspecs)]
    (reduce #(conj-field %2 %1) tbl fieldspecs)))

(defn drop-fields
  "Returns a tbl where the column associated with fld is no longer present."
  [flds tbl]
  (let [keep-set (clojure.set/difference (set (table-fields tbl)) (set flds))
        cols     (table-columns tbl)]
    (reduce (fn [newtbl [j fld]]
              (if (keep-set fld)
                (conj-field [fld (get cols j)] newtbl)
                newtbl))
            (-make-table tbl [] [])
            (map-indexed vector (table-fields tbl)))))
 
(defn drop-field
  "Returns a tbl where fld is removed.  Structures that implement IFieldDropper
   for effecient removal are preferred, otherwise we build a table without 
   the dropped fields."
  [fld tbl]
  (if (satisfies? IFieldDropper tbl)
    (-drop-field tbl fld )
    (drop-fields [fld] tbl)))


;;Ran across a bug in clojure 1.6, where the type information was not
;;equating an instance of column-table  as a column-table, and the 
;;optimization in here passed through.  Produced invalid results...
(defn table->map 
  "Extracts a map representation of a table, where keys are  
   fields, and values are column values. This is an unordered table." 
  [tbl]  
;  (if (and (map? tbl) (not= (type tbl) spork.util.table.column-table)) tbl 
    (let [cols (table-columns tbl)] 
      (reduce (fn [fldmap [j fld]]  (assoc fldmap fld (get cols j))) {}  
              (reverse (map-indexed vector (table-fields tbl))))))
;)  
 
(defn map->table
  "Converts a map representation of a table into an ordered table."
  [m] 
  (assert (map? m)) 
  (conj-fields (seq m) empty-table)) 
 
(defn order-fields-by 
  "Returns a tbl where the fields are re-arranged to conform with the ordering  
   specified by applying orderfunc to a sequence of  [fieldname column-values], 
   where f returns a sequence of field names.  Resorts to a default ordered
   table representation.  If orderfunc is a vector of fields, like [:a :b :c],
   rather than applying the function, the fields will be extracted in order." 
  [orderfunc tbl]   
  (let [fieldmap (table->map tbl)        
        ordered-fields 
        (cond (vector? orderfunc)  (do (assert (clojure.set/subset? 
                                                  (set orderfunc) 
                                                  (set (table-fields tbl)))  
                                         (str "Table is missing fields!"  
                                              orderfunc (table-fields tbl)))
                                     (align-by orderfunc (table-fields tbl))) 
              (fn? orderfunc) (orderfunc (seq fieldmap))
              :else 
                (throw (Exception. "Ordering function must be vector or fn")))] 
    (reduce (fn [newtbl fld] (conj-field [fld (get fieldmap fld)] newtbl))  
            empty-table ordered-fields)))

(defn all-fields? [fieldnames] 
  (or (= fieldnames :*) (= fieldnames :all)))

(defn select-fields 
  "Returns a table with only fieldnames selected.  The table returned by the  
   select statement will have field names in the order specified by fieldnames." 
  [fieldnames tbl]
  (if (all-fields? fieldnames) 
    tbl 
    (let [res (drop-fields (clojure.set/difference (set (table-fields tbl))
                                                   (set fieldnames))  
                           tbl)]     
    (order-fields-by  
      (if (vector? fieldnames) fieldnames (vec fieldnames)) res)))) 

(defn field->string [f] (cond (string? f) f 
                              (keyword? f) (str (subs (str f) 1)) 
                              :else (str f)))
(defn keywordize-field-names
  "Flips the fields to be keywords instead of strings."
  [t] 
  (make-table  (reduce #(conj %1 (keyword %2)) [] (table-fields t))
               (table-columns t)))

(defn stringify-field-names 
  "Flips the fields to be string instead of keywords." 
  [t]  
  (make-table (reduce #(conj %1 (field->string %2)) [] (table-fields t)) 
              (table-columns t)))

(defn valid-row?
  "Ensures n is within the bounds of tbl."
  [tbl n]  (and (>= n 0) (< n (count-rows tbl))))
  
(defn nth-row 
  [tbl n]
  "Extracts the nth row of data from tbl, returning a vector."
  (assert (valid-row? tbl n) (str "Index " n " Out of Bounds"))
  (mapv #(nth % n) (table-columns tbl)))

(defn table-rows 
  "Returns a vector of the rows of the table." 
  [tbl]  (vec (map #(nth-row tbl %) (range (count-rows tbl))))) 

;;#Optimize!
;;Dogshit slow....should clean this up...both zipmap and 
;;the varargs are bad for performance.  This will get called a lot.
(defn nth-record
  "Coerces a column-oriented vector representation of tables into 
   a map where keys are field names, and vals are column values at 
   the nth record."
  [tbl n & [flds]]
  (assert (valid-row? tbl n) (str "Index " n " Out of Bounds"))  
  (zipmap (if flds flds (reverse (table-fields tbl))) 
          (reverse (nth-row tbl n))))

(defn table-records
  "Fetches a sequence of n records, where records are maps where keys correspond
   to fields in the table, and values correspond to the values at row (n - 1)."
  [tbl]
  (let [flds (reverse (table-fields tbl))]
    (map (fn [n] (nth-record tbl n flds)) (range (count-rows tbl)))))

(defn last-record 
  "Fetches the last record from table.  Returns nil for empty tables."
  [tbl]
  (when (> (count-rows tbl) 0) 
    (nth-record tbl (dec (count-rows tbl)))))

(defn first-record  
  "Fetches the first record from table.  Returns nil for empty tables." 
  [tbl]
  (when (>  (count-rows tbl) 0) 
    (nth-record tbl 0)))

(defn conj-row
  "Conjoins a rowvector on a vector of columns."
  [columns rowvector]
  (assert (= (count rowvector) (count columns)))
  (reduce (fn [acc [j x]] (assoc acc j (conj (get acc j) x)))
          columns (map-indexed vector rowvector)))

;;we're using lazy seq here..
;;map-indexed vector...
;;another optimization is to use a reducer..
;;since it's a reduction, we could just go low-level here.
;;could also use transducer...
(comment 
(defn- conj-row! [transientcolumns rowvector] 
  (reduce (fn [acc [j x]] (assoc! acc j (conj! (get acc j) x))) 
          transientcolumns (map-indexed vector rowvector)))
)

(defn- conj-row! [transientcolumns rowvector]
  (let [bound (count rowvector)]
    (loop [j 0
           acc transientcolumns]
      (if (== j bound) acc
          (recur (unchecked-inc j)
                 (assoc! acc j (conj! (get acc j)
                                      (nth rowvector j))))))))

(defn first-any [xs]
  (if (seq? xs)  (first xs)
      (r/first xs)))
      
(defn conj-rows
  "Conjoins multiple rowvectors.  Should be fast, using transients.
   Returns a persistent collection."
  [columns rowvectors]
  (assert (= (count (first-any rowvectors)) (count columns)))
  (persistent-columns! 
    (reduce conj-row! (transient-columns columns) rowvectors)))  

(defn records->table
  "Interprets a sequence of records as a table, where fields are the keys of 
   the records, and rows are the values."
  [recs]
  (let [flds (vec (reverse (keys (first recs))))]
    (make-table flds (conj-rows (vec (map (fn [_] []) flds))
                                (map (comp vals reverse) recs)))))

(defn filter-rows
  "Returns a subset of rows where f is true.  f is a function of 
   type f::vector->boolean, since rows are vectors...."
  [f tbl ]
  (vec (filter f (table-rows tbl)))) 

(defn filter-records
  [f tbl]
  "Returns a subtable, where the rows of tbl have been filtered according to 
   function f, where f is of type ::record->boolean, where record is a map 
   where the keys correspond to tbl fields, and the values correspond to row  
   values."
  (records->table (filter f (table-records tbl))))

(defn map-field-indexed 
  "Maps function f to field values drawn from tbl.  f takes arguments akin to  
   map-indexed, [i v], treating the mapping as an indexed traversal over the  
   field entries.  Returns a table with the result of the mapping." 
  [field f tbl] 
  (let [newvals (map-indexed f (field-vals (get-field field tbl)))] 
    (conj-field [field (vec newvals)] tbl)))

(defn map-field 
  "Maps function f to field values drawn from tbl, akin to clojure.core/map. 
  Returns a table with the results of the mapping." 
  [field f tbl]
  (map-field-indexed field (fn [_ x] (f x)) tbl))


(defn negate [n] (- n))

(defn order-with 
    "Returns a new table, where the rows of tbl have been ordered according to  
     function f, where f is of type ::record->key, where record is a map  
     where the keys correspond to tbl fields, and the values correspond to row   
     values."
    [f tbl]
  (let [t (->> (table-records tbl) 
            (sort-by f) 
            (records->table))] 
    (make-table (vec (reverse (table-fields t))) 
                (vec (reverse (table-columns t))))))
                            
(defn order-by
  "Similar to the SQL clause.  Given a sequence of orderings, sorts the 
   table accordingly.  orderings are of the form :
   :field, 
   [comparison-function :ascending|:descending],
   [fieldname comparison-function :ascending|:descending]     
   [fieldname :ascending|:descending]"
  [orderings tbl]
  (let [t (->> (table-records tbl)
            (sort (serial-field-comparer orderings))
            (records->table))]
    (make-table (vec (reverse (table-fields t)))
                (vec (reverse (table-columns t))))))

(defn concat-tables
  "Concatenates two or more tables.  Concatenation operates like union-select
   in SQL, in that fields between all tables must be identical [including 
   positionally].  Returns a single table that is the result of merging all 
   rows together."
  [tbls]
  (let [flds (table-fields (first tbls))]
    (assert (every? #(= (table-fields %) flds) tbls))
     (->> (mapcat (fn [tbl] (table-rows tbl)) tbls)
       ((comp vec distinct))
       (conj-rows (empty-columns (count flds)))
       (make-table flds))))

(defn view-table [tbl] (pprint (table-records tbl)))
(defn select-distinct
  "Select only distinct records from the table.  This treats each record as a 
   tuple, and performs a set union on all the records.  The resulting table is 
   returned (likely a subset of the original table).  Used to build lookup 
   tables for intermediate queries."
  [tbl]
  (make-table (table-fields tbl) 
              (transpose (vec (distinct (table-rows tbl))))))

(defn group-records  
  "Convenience function, like group-by, but applies directly to ITabular 
   structures.  Allows an optional aggregator function that can be run on  
   each group, allowing domain aggregation queries and the like.  If no  
   aggregator is supplied via the :aggregator key argument, acts identical to  
   clojure.core/group-by" 
  [keygen table & {:keys [aggregator] :or {aggregator identity}}] 
  (let [get-key (key-function keygen)] 
    (let [groups  
          (->> (select-distinct table) 
            (table-records) 
            (group-by get-key))] 
      (into {} (map (fn [[k v]] [k (aggregator v)]) (seq groups)))))) 
   
(defn make-lookup-table 
  "Creates a lookup table from a table and a list of fields.  If more than one 
   field is specified, composes the field values into a vector, creating a  
   compound key.  If more than one result is returned, returns the first  
   value in the grouped vectors." 
  [fields table] (group-records fields table :aggregator first))


(defn join-on 
  "Given a field or a list of fields, joins each table that shares the field." 
  [fields tbl1 tbl2]  
  (let [lookup (partial group-records (key-function fields))
        l (lookup tbl1)
        r (lookup tbl2)
        joins (clojure.set/intersection (set (keys l)) (set (keys r)))]
    (persistent! 
      (reduce (fn [acc k] (->> (for [x  (get l k)
                                     y  (get r k)]
                                 (merge x y))
                            (reduce conj! acc))) (transient #{}) joins))))     

(defn join-tables 
  "Given a field or a list of fields, joins each table that shares the field." 
  [fields tbls]
  (throw (Exception. "Currently not performing correctly, use join-on"))
  (assert (coll? fields)) 
  (let [field-set    (set fields) 
        valid-tables (->> tbls
                          (filter #(clojure.set/subset?  field-set 
                                     (set (table-fields %)))) 
                          (sort-by count-rows))
        joiner (partial join-on fields)]
    (when valid-tables
       (reduce (fn [l r] (records->table (joiner l r))) valid-tables)))) 
             

;protocol-derived functions 

(defn- process-if [pred f x] (if pred (f x) x))

(defn database? [xs]  
  (and (seq xs) (every? tabular? xs)))



(defmulti  computed-field  (fn [x] (if (fn? x) :function (type x))))
(defmethod computed-field :function [f] (fn [i rec] (f i rec)))
(defmethod computed-field clojure.lang.Keyword [k]  (fn [i rec] (get rec k)))

;implementation for 'as statements pending....


(defn- select- 
  "A small adaptation of Peter Seibel's excellent mini SQL language from 
   Practical Common Lisp.  This should make life a little easier when dealing 
   with abstract tables...."   
  [columns from where unique orderings]
  (if (and (not (database? from)) (tabular? from)) ;simple selection....
    (->> (select-fields columns from) ;extract the fields.
      (process-if where (partial filter-records where))
      (process-if unique select-distinct)
      (process-if order-by (partial order-by orderings)))))

(defn select 
  "Allows caller to compose simple SQL-like queries on abstract tables.  
   Joins are implemented but not supported just yet.  The :from key for the 
   select query is intended to be a table, specifically something supporting 
   ITabular."
  [& {:keys [fields from where unique orderings]
      :or   {fields :* from nil where nil
             unique true orderings nil }}]
  (select- fields from where unique orderings)) 


;;Parsing tables from external files...

;;We'd like to include the notion of a schema, so that if we have
;;strongly typed fields, we can parse them relatively quickly.
;;If a field doesn't exist in the schema, we can still fall back to 
;;parse string.  Parse-string is the biggest bottleneck at the 
;;moment.

(defn pair [a b] [a b])
(def re-tab (re-pattern (str \tab)))
;;split-by-tab is hurting is.
;;specifically, because of the overhead of having
;;to create all the intermediate vectors we're
;;parsing.
;; (def split-by-tab
;;   #(strlib/split % re-tab))
;;Roughly 2X as fast as clojure.string/split
;;Note, it doesn't cost us much to wrap it as a
;;persistent vector.
(defn split-by-tab [^String s]
  (clojure.lang.LazilyPersistentVector/createOwning
   (.split s "\t")
   )
)

(defn ^java.io.BufferedReader get-reader [s]
  (if (general/path? s) (clojure.java.io/reader s)
      (general/string-reader s)))

;;probably replace this with iota...
(defn lines [^String s]
  (reify 
    clojure.core.protocols/CollReduce
    (coll-reduce [this f]
      (with-open [^java.io.BufferedReader rdr (get-reader s)]
        (if-let [l1 (.readLine rdr)]
          (if-let [l2 (.readLine rdr)]            
            (loop [acc (f l1 l2)]
              (if (reduced? acc) acc
                  (if-let [line (.readLine rdr)]
                    (recur (f acc line))
                    acc)))
            l1)
          nil)))
    (coll-reduce [this f init]
      (with-open [^java.io.BufferedReader rdr (get-reader s)]
        (loop [acc init]
          (if (reduced? acc) acc
              (if-let [line (.readLine rdr)]
                (recur (f acc line))
                acc)))))))    

;older table abstraction, based on maps and records...
(defn lines->table 
  "Return a map-based table abstraction from reading lines of tabe delimited text.  
   The default string parser tries to parse an item as a number.  In 
   cases where there is an E in the string, parsing may return a number or 
   infinity.  Set the :parsemode key to any value to anything other than 
   :scientific to avoid parsing scientific numbers."
   [lines & {:keys [parsemode keywordize-fields? schema] 
             :or   {parsemode :scientific
                    keywordize-fields? true
                    schema {}}}] 
  (let [tbl   (->column-table 
                 (vec (map (if keywordize-fields?  
                             (comp keyword clojure.string/trim)
                             identity) (split-by-tab (first-any lines)))) 
                 [])
        parsef (parse/parsing-scheme schema :default-parser  
                 (if (= parsemode :scientific) parse/parse-string
                     parse/parse-string-nonscientific))
        fields (table-fields tbl)      
        parse-rec (comp (parse/vec-parser! fields parsef) split-by-tab)]
      (->> (conj-rows (empty-columns (count (table-fields tbl))) 
                      (r/map parse-rec (r/drop 1 lines)))
           (assoc tbl :columns))))
(comment 
(defn tabdelimited->table 
  "Return a map-based table abstraction from reading a string of tabdelimited 
   text.  The default string parser tries to parse an item as a number.  In 
   cases where there is an E in the string, parsing may return a number or 
   infinity.  Set the :parsemode key to any value to anything other than 
   :scientific to avoid parsing scientific numbers."
   [s & {:keys [parsemode keywordize-fields? schema] 
         :or   {parsemode :scientific
                keywordize-fields? true
                schema {}}}]
   (let [lines (strlib/split-lines s)
         tbl   (->column-table 
                (vec (map (if keywordize-fields?  
                            (comp keyword clojure.string/trim)
                            identity) (split-by-tab (first lines)))) 
                [])
         parsef (parse/parsing-scheme schema :default-parser  
                                      (if (= parsemode :scientific) parse/parse-string
                                          parse/parse-string-nonscientific))
         fields (table-fields tbl)      
         parse-rec (comp (parse/vec-parser fields parsef) split-by-tab)]
     (->> (conj-rows (empty-columns (count (table-fields tbl))) 
                     (map parse-rec (rest lines)))
          (assoc tbl :columns))))
)


(comment 
(defn words-by
  "Efficient implementation of tab delimited value splitter."
  ([f init  delim ^CharSequence x]
   (let [delim (char delim)
         bound (count x)
         lastc (unchecked-dec bound)
         chars (char ...)
         lr->word (fn [l r]
                    (loop [idx l]
                      ))
         ]
     (loop [idx   0
            l     0
            acc   init]
       (if (or (reduced? acc)
               (== idx bound))
         (if (pos? (- lastc l))
           (f acc (.subSequence x l bound))
           acc )
         (let [ c (.charAt x idx)
               delim?  (= c delim)]
           (cond (and delim? (< l idx)) ;found a word
                 (let [nxt (.subSequence x l idx)]
                   (recur idx
                         idx
                         (f acc nxt)))
                 delim? ;new word..
                 (recur (unchecked-inc idx)
                        (unchecked-inc idx)
                        acc)
                 :else
                 ;(or (zero? idx) (zero? l))
                 (recur (unchecked-inc idx)
                        l
                        acc)
                  ;(throw (Exception. (str [:uncaught-case acc])))
                   ))))))
  ([delim x] (words-by conj [] delim x))
  ([x] (words-by \tab x)))

                    ;;new word....
                     
                             
                  
                 
;    (reduce [o f])
 ; (reify clojure.core.ISeq
 ;   (seq [obj]

  
(defn select-text-fields
  "Allows us to select a subset of the text fields
   from a sequence of lines.  In other words, it helps 
   us to eliminate parsing, and thus storing, lots of 
   extraneous data.  For in memory parsing, this should
   be a pretty significant benefit.  Right now, it appears
   that text is killing us in overhead."
  [flds ls]
  (let [headers (first ls)
        ks (if (map? flds) (keys flds) flds)
        nums  (vec (sort (mapv first ks)))
        extract (fn [^String ln]
                  (reduce ... ))
        ]))
    ;;we now know the positions to retain.  Everything else is dropped.
    ;;we want to return a modified view of the underlying sequence that
    ;;only keeps the nth positions we identified.
  
)

(defn tabdelimited->table 
  "Return a map-based table abstraction from reading a string of tabdelimited 
   text.  The default string parser tries to parse an item as a number.  In 
   cases where there is an E in the string, parsing may return a number or 
   infinity.  Set the :parsemode key to any value to anything other than 
   :scientific to avoid parsing scientific numbers."
   [s & {:keys [parsemode keywordize-fields? schema] 
         :or   {parsemode :scientific
                keywordize-fields? true
                schema {}}}]
   (with-open [^java.io.Reader rdr (if (general/path? s)
                                     (clojure.java.io/reader s)
                                     (general/string-reader s))]
     (lines->table (line-seq rdr)
                   :parsemode parsemode
                   :keywordize-fields? keywordize-fields?
                   :schema schema)))



;;another option here is to parse the lines into columns, then simply
;;append the columns.  As it stands, we have a lot of intermediate vectors
;;being created...If we have a vector of parsers, it makes it easier to
;;just build of n vectors of columns, rather than produce an intermediate
;;vector for each row, and then convert to rows at the end...

;;We can also save some time and space if we just use a single array
;;buffer...as long as we're copying the array we're okay..
(defn lines->columns 
  "Return a vector of columns from reading lines of tabe delimited text.  
   The default string parser tries to parse an item as a number.  In 
   cases where there is an E in the string, parsing may return a number or 
   infinity.  Set the :parsemode key to any value to anything other than 
   :scientific to avoid parsing scientific numbers.  Avoid creating 
   intermediate row collections.  If we have a vector parser, we just 
   reduce over it, building up the columns as we go.  This should 
   allow us to avoid lots of intermediate vectors as the current scheme 
   follows."
   [lines & {:keys [parsemode keywordize-fields? schema] 
             :or   {parsemode :scientific
                    keywordize-fields? true
                    schema {}}}] 
  (let [tbl   (->column-table 
                 (vec (map (if keywordize-fields?  
                             (comp keyword clojure.string/trim)
                             identity)
                           (split-by-tab (first lines)))) 
                 [])
        parsef (parse/parsing-scheme schema :default-parser  
                 (if (= parsemode :scientific)
                     parse/parse-string
                     parse/parse-string-nonscientific))
        fields (table-fields tbl)
        ;;if we can give a reducible seq to conj-rows, it'll work...       
        ;;We could use mutable record to get the fields...
        parse-rec (comp (parse/vec-parser fields parsef)
                        split-by-tab)]
    (->> (conj-rows (empty-columns (count (table-fields tbl)))
                    ;We don't really want to create a lazy seq here.  parse-rec makes a vector.
                    ;so we create a million vectors.
                    (map parse-rec (rest lines)) ;lazy sequence.
                    )
         (assoc tbl :columns))))

(defn record-seq  
	"Returns a sequence of records from the underlying table representation. 
	 Like a database, all records have identical fieldnames.
   Re-routed to use the new table-records function built on the ITabular lib." 
	[tbl]
 (table-records tbl))



(defn get-record  
	"Fetches the nth record from a tabular map.  
   Rerouted to use the new API.  nth-record." 
	[tbl n]
 (nth-record tbl n))

(defn record-count [t] (count-rows t))
(defn get-fields   [t] (table-fields t))
(defn last-record  [t] (get-record t (dec (record-count t))))

(defn row->string 
  ([separator r] (strlib/join separator r))
  ([r]           (strlib/join \tab r)))

(defn record->string [rec separator] (row->string (vals rec) separator)) 
(defn table->string  
  "Render a table into a string representation.  Uses clojure.string/join for
   speed, which uses a string builder internally...if you pass it a separator.
   By default, converts keyword-valued field names into strings.  Caller 
   may supply a different function for writing each row via row-writer, as 
   well as a different row-separator.  row-writer::vector->string, 
   row-separator::char||string" 
  [tbl & {:keys [stringify-fields? row-writer row-separator] 
          :or   {stringify-fields? true 
                 row-writer  row->string
                 row-separator \newline}}]
  (let [xs (concat (if stringify-fields? 
                     [(vec (map field->string (table-fields tbl)))]  
                     [(table-fields tbl)]) 
                   (table-rows tbl))]
    (strlib/join row-separator (map row-writer xs))))

(defn table->tabdelimited
  "Render a table into a tab delimited representation."  
  [tbl & {:keys [stringify-fields?] :or {stringify-fields? true}}] 
  (table->string tbl :stringify-fields? stringify-fields?))

(defn infer-format [path]
  (case (strlib/lower-case (last (strlib/split path #"\.")))
    "txt" :tab
    "clj" :clj 
    nil))

(defmulti table->file (fn [tbl path & {:keys [stringify-fields? data-format]}]
                        data-format))

(defmethod table->file :tab 
  [tbl path & {:keys [stringify-fields? data-format]}] 
  (spit (clojure.java.io/file path)  
        (table->tabdelimited tbl :stringify-fields? stringify-fields?))) 
 
(defmethod table->file :clj 
  [tbl path & {:keys [stringify-fields? data-format]}] 
  (with-open [dest (clojure.java.io/writer (clojure.java.io/file path))] 
    (binding [*out* dest] 
      (print tbl)))) 
 
(defmethod table->file :clj-pretty 
  [tbl path & {:keys [stringify-fields? data-format]}] 
  (with-open [dest (clojure.java.io/writer (clojure.java.io/file path))] 
    (binding [*out* dest] 
      (pprint tbl)))) 

(defmethod table->file :default 
  [tbl path & {:keys [stringify-fields? data-format]}]
  (spit (clojure.java.io/file path) 
        (table->tabdelimited tbl :stringify-fields? stringify-fields?)))  
 
(defmulti as-table
  "Generic function to create abstract tables."
  (fn [t] (class t)) :default :empty)
 
(defmethod as-table java.lang.String [t] (tabdelimited->table t))
(defmethod as-table clojure.lang.PersistentArrayMap [t] t)

(defmulti read-table (fn [t & opts] (class t))) 
(defmethod read-table  java.io.File [t & opts] (as-table (slurp t))) 

(defn copy-table!
  "Copies a table from the system clipboard, assuming that the clipboard
   contains a string of tab-delimited text."
  [& [parsemode keywordize-fields?]]
  (tabdelimited->table (board/copy!) :parsemode (or parsemode :no-science)
                                     :keywordize-fields? keywordize-fields?))
(defn copy-table-literal! []
  "Copes a table from the system clipboard.  Does not keywordize anything..."
  (copy-table! :no-science false))

(defn slurp-records 
  "Parses string s into a record sequence."
  [s] (record-seq (as-table s)))

(defn copy-records!
  "Copies a string from the system clipboard, assumed to be in tab delimited 
   format, and returns a record sequence."
  [] (slurp-records (board/copy!)))

(defn spit-records
  "Spits a sequence of records, xs, assumed to be identical in keys, as if it 
   were a tab delimited table."
  [xs] 
  (table->tabdelimited (records->table xs)))

(defn paste-records!
  "Pastes a sequence of records, xs, to the clipboard as a string that assumes 
   xs are records in a tabdelimited table."
  [xs]
  (board/paste! (spit-records xs)))

;establishes a simple table-viewer.
;we can probably create more complicated views later....
(defmethod gui/view spork.util.table.column-table [obj & {:keys [title sorted]  
                                :or {title "Table" sorted false}}] 
    (gui/->scrollable-view 
      (gui/->swing-table (get-fields obj)   
                         (table-rows obj) :sorted sorted)
      :title title))

(defmethod gui/as-JTable spork.util.table.column-table [obj & {:keys [sorted]}]
  (gui/->swing-table (get-fields obj)   
                     (table-rows obj) :sorted sorted)) 

(defn visualize   [obj & {:keys [title sorted] :or {title "some data" sorted true}}]
  (gui/->scrollable-view 
   (gui/->swing-table (get-fields obj)   
                      (table-rows obj) :sorted sorted)))

;;Additional patches for extended table functionality.
 (defn paste-table! [t]  (spork.util.clipboard/paste! (table->tabdelimited t)))
 (defn add-index [t] (conj-field [:index (take (record-count t) (iterate inc 0))] t))
 (defn no-colon [s]   (if (or (keyword? s)
                              (and (string? s) (= (first s) \:)))
                        (subs (str s) 1)))

 (defn collapse [t root-fields munge-fields key-field val-field]
   (let [root-table (select :fields root-fields   :from t)]
     (->>  (for [munge-field munge-fields]
             (let [munge-col  (select-fields [munge-field] t)
                   munge-name (first (get-fields munge-col))
                   
                   key-col    [key-field (into [] (take (record-count root-table) 
                                                        (repeat munge-name)))]
                   val-col    [val-field  (first (vals (get-field munge-name munge-col)))]]
               (conj-fields [key-col val-col] root-table)))
           (concat-tables)          
           (select-fields  (into root-fields [key-field val-field])))))

 (defn rank-by  
   ([trendf rankf trendfield rankfield t]
      (->> (for [[tr xs] (group-by trendf  (table-records t))] 
             (map-indexed (fn [idx r] (assoc r trendfield tr  rankfield idx)) (sort-by rankf xs)))
           (reduce      concat)
           (records->table)
           (select-fields (into (table-fields t) [trendfield rankfield]))))
   ([trendf rankf t] (rank-by trendf rankf :trend :rank t)))

 (defn ranks-by [names-trends-ranks t]    
   (let [indexed (add-index t)
         new-fields (atom [])
         idx->rankings 
         (doall (for [[name [trendf rankf]] names-trends-ranks]
                  (let [rankfield (keyword (str (no-colon name) "-rank"))
                        _ (swap! new-fields into [name rankfield])]
                    (->> indexed
                         (rank-by trendf rankf name rankfield)
                         (select-fields [:index name rankfield])
                         (reduce (fn [acc r]
                                   (assoc acc (:index r) [(get r name) (get r rankfield)])) {})))))]
     (conj-fields      
      (->> idx->rankings
           (reduce (fn [l r] 
                     (reduce-kv (fn [acc idx xs]
                                  (update-in acc [idx]
                                             into xs))
                                l r)))
           (sort-by first)
           (mapv second)
           (spork.util.vector/transpose)
           (mapv vector @new-fields)
           )
      indexed)))

(comment   ;testing.... 
  (def mytable  (conj-fields [[:first ["tom" "bill"]] 
                              [:last  ["spoon" "shatner"]]] empty-table)) 
  (def mymaptable {:first ["tom" "bill"] 
                   :last  ["spoon" "shatner"]}) 
   
  (def othertable (->> empty-table  
                    (conj-fields [[:first ["bilbo"]] 
                                  [:last  ["baggins"]]]))) 
  (def conctable (concat-tables [mytable othertable])) 
  (def query (->> [mytable othertable] 
               (concat-tables) 
               (conj-field  
                 [:age [31 65 400]]))) 
   
  (def sortingtable (->> query  
                      (conj-field [:xcoord [2 2 55]]) 
                      (conj-field [:home   ["USA" "Canada" "Shire"]]))) 
  (def closest-geezer (->> sortingtable 
                        (order-by [:xcoord 
                                   [:age :descending]]))) 
  (defn compound-query []  
    (->> closest-geezer  
      (select-fields [:home :first :last]) 
      (vector {:home ["PA"] 
               :first ["Barry"] 
               :last ["Groves"]}) 
      (map #(order-fields-by [:home :first :last] %)) 
      (concat-tables)                                               
      view-table))  

  (defn join-test []
    (let [names ["Able" "Baker" "Charlie"]
          professions {:name names
                       :profession [:soldier :farmer :baker]}
          instruments {:name (conj names "Dan") 
                       :instrument [:rifle :plow :oven :guitar]}]
      (join-on :name professions instruments)))
                  
  
  (defn join-test2 [] 
    (let [names ["Able" "Baker" "Charlie"] 
          professions {:name names 
                       :profession [:soldier :farmer :baker]} 
          instruments {:name (conj names "Dan")  
                       :instrument [:rifle :plow :oven :guitar]}
          ages {:name names 
                :age [20 30 40]}] 
      (join-tables [:name] [professions instruments ages])))
  
  (defn select-example [] 
    (->> closest-geezer  
      (select :fields [:home :first :last] 
              :ordering [[:home :descending]] 
              :from) 
      (conj-field [:instruments [:guitar :fiddle]])))
  
  (defn select-from-join []
    (let [names ["Able" "Baker" "Charlie"] 
          professions {:name names 
                       :profession [:soldier :farmer :baker]} 
          instruments {:name (conj names "Dan")  
                       :instrument [:rifle :plow :oven :guitar]} 
          ages {:name names  
                :age [20 30 40]}] 
      (select :fields [:instrument :profession]
              :from (join-tables [:name] [professions instruments ages]))))
  
  (defn map-field-test [] 
    (let [names ["Able" "Baker" "Charlie"] 
          professions {:name names 
                       :profession [:soldier :farmer :baker]} 
          instruments {:name (conj names "Dan")  
                       :instrument [:rifle :plow :oven :guitar]} 
          ages {:name names  
                :age [20 30 40]}
          joined (join-tables [:name] [professions instruments ages])
          names  (field-vals (get-field :name joined))]
           
      (->> joined
           (map-field :name (fn [x] (.toLowerCase x)))
           (conj-field [:upper-name 
                        (field-vals 
                          (get-field :name 
                            (map-field :name (fn [x] (.toUpperCase x)) 
                               joined)))])
           (order-fields-by [:age 
                             :name 
                             :upper-name 
                             :profession 
                             :instrument])))) 
  ;performance testing...
  (def small-table-path "C:\\Users\\thomas.spoon.NAE\\Documents\\sampledata\\djdata.txt")
  ;;Read in some pre-baked data
  (def the-string (clojure.string/replace (slurp small-table-path) \; \tab))
  ;Some data is encoded as "32,35" etc.
  (defn drop-quotes [x] (subs  x 1 (dec (count x))))
  (defn comma-numbers [^String x] x )
  ;; (defn comma-numbers [^String x] 
  ;;   ((parse/vec-parser :int) 
  ;;    (clojure.string/split (drop-quotes x) #",")))

  (def parseinfo (partition 2 [:id      :int
                               :date    :string ;:date
                               :open    comma-numbers
                               :am      comma-numbers
                               :pm      comma-numbers
                               :close   comma-numbers
                               :total   :number
                               :average comma-numbers
                               :stock   :string]))
  
  (def fields (mapv first  parseinfo))
  (def types  (mapv second parseinfo))
  
  (def dj-schema (zipmap fields types))

  ;;not working as expected.  Binding isn't updating like I expected.
  (parse/with-parsers {:comma-numbers comma-numbers} 
    (def rec-parser  (parse/record-parser  dj-schema))
    (def line-parser (parse/vec-parser fields (parse/parsing-scheme dj-schema))))
  
  ;  (defn select-as []
;    (let [names ["Able" "Baker" "Charlie"] 
;          professions {:name names 
;                       :profession [:soldier :farmer :baker]} 
;          instruments {:name (conj names "Dan")  
;                       :instrument [:rifle :plow :oven :guitar]} 
;          ages {:name names  
;                :age [20 30 40]}] 
;      (select :fields [:instrument 
;                       :profession 
;                       [:age   (fn [i rec] (* 10 i))]
;                       [:index (fn [i rec] i)]]               
;              :from (join-tables [:name] [professions instruments ages]))))
  )
  

;it'd be nice to have simple sql-like operators....
;we've already got select 
;in SQL, we use
