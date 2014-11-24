;A collection of macros and functions useful for metaprogramming and library
;development. 
(ns spork.util.metaprogramming
  (:require [spork.util [general :as gen]]))


(definline id 
  "Like identity, but acts at compile time.  Acts as a stand-in for 
   identity to avoid un-necessary function calls."
  [expr]
  `~expr)

(defmacro tagged 
  "Like gensym, but tags the symbol with a type hint."
  [type name]
  `(with-meta (gensym ~name) {:tag ~type}))

(defmacro defmany
  "Define multiple definitions inline.  Takes a collection of bindings, and 
   converts them into def expressions."
  [bindings]
    `(do ~@(->> (partition 2 bindings)
             (map (fn [b] `(def ~(first b) ~(second b)))))))

(defn keyvals->constants
  "Given a map of {symbol val} or {:symbol val}, 
   creates def bindings for each symbol."
  [m]
  (let [as-symbol (fn [k] 
                    (cond (symbol? k) k
                          (keyword? k) (symbol (subs (str k) 1))
                          :else (throw (Exception. "unknown symbol type"))))]
    (eval `(defmany ~(flatten (for [[k v] m] [(as-symbol k) v]))))))

;defines a path to a resource, specifically a function that can get a nested 
;resource from an associative structure.
;A ton of our work will be in dissecting nested structures, particularly the 
;simcontext.
(defmacro defpath
  "Allows definitions of nested paths into associative structures.  Creates 
   a function, named pathname, that consume a map and apply get-in 
   using the supplied path denoted by a sequence of literals, ks."
  [pathname & ks] 
  `(do 
     (defn ~(symbol (str "get-" pathname)) 
       ~(str "Accessor for associatives. Fetches " pathname)   
       [m#] (gen/deep-get m# ~@ks))
     (defn ~(symbol (str "set-" pathname))    
       ~(str "Accessor for associatives. Sets " pathname " to second arg.")
       [m# v#] (gen/deep-assoc m# ~@ks v#))
     (defn ~(symbol (str "update-" pathname)) 
       ~(str "Accessor for associatives. Sets " pathname 
             " to second arg applied to current val at " pathname)
       [m# f#]
       (gen/deep-update m# ~@ks f#))))


(defmacro defpaths
  "Allows multiple paths to be defined at once, with the possibility of sharing 
   a common prefix.  Consumes a map of [pathname path] and applies defpath to 
   each in turn.  A common prefix may be supplied to the paths. "
  ([kvps]         `(defpaths [] ~kvps))
  ([prefix kvps]  
     (let [prefix (if (coll? prefix) prefix [prefix])]
       `(do ~@(map (fn [[n p]] `(defpath ~n ~(into prefix p))) kvps)))))

(defn key->symb [k]  (symbol (subs (str k) 1)))
(defn key->gensymb [k]  (symbol (str (subs (str k) 1) \#)))
(defn key->var [k]  (symbol (str \* (subs (str k) 1) \*)))

(defmacro binding-keys [opts & body]
  `(let ~(reduce-kv (fn [acc k v] 
                           (-> acc
                               (conj (key->var k))
                               (conj v)))
                         []
                         (if (map? opts) opts (eval opts)))
     ~@body))


;;One useful extrapolation I've found is defining macros that take 
;;"hooks", or forms that allow the user access to forms within.
;;I have built this pattern by hand so far, but I need to encode it
;;into a metaprogramming macro.


;;A useful binding form that replaces the - unperformant - 
;;varargs idiom in clojure with something that 
;;takes a map of varargs and unpacks it.

(defn blah [x & {:keys [op y z] :or {op + y 2 z 3}}]
  (op x y z))


;; (defn blah-opt 
;;   ([x opts]
;;      (let [op (get opts :op +)
;;            y  (get opts :y 2)
;;            z  (get opts :z 3)]
;;        (op x y z)))
;;   ([x]      
;;      (let [op  +
;;            y   2
;;            z   3]
;;        (op  y z))))


   
(comment 
(defmacro defn-curried-options
  [name doc args [user-opts opts-val] body]
  (let [folded-body (clojure.walk/postwalk-replace {user-opts opts-val} body)]
    `(defn ~name ~doc  
       ([~@args ~user-opts] 
          ~body)
       ([~@args] 
          ~folded-body))))

(defn symbolize [k] 
  (cond (symbol? k)  k
        (keyword? k) (symbol (subs  (str k) 1))
        (string? k)  (symbol k)
        :else (throw (Exception. (str "No way to make a symbol from " k)))))

(defmacro with-keys [ks env & body]
  `(let [~'*env* ~env
         ~@(mapcat (fn [k] `(~(symbolize k) (get ~'*env* ~(if (symbol? k) (list 'quote k) 
                                                                       k)))) ks)]
     ~@body))    

;;This provides a much faster alternative to the RestFn generating,
;;yet "idiomatic" form of defn, where optional args are elided as 
;;maps or vectors.  Here, we imply that the optional args will
;;definitely take the form of a finite map as a final arg. 
;;Otherwise, a normal function body with pre-evaluated defaults is used.
(defmacro defn-curried-options
  [name doc args opts-map body]
  (let [locals (vec (keys opts-map))]
    `(with-keys  ~locals  ~opts-map
      (defn ~name ~doc  
        ([~@args ~'user-env] 
           (with-keys ~locals ~'user-env
             ~body))
        ([~@args]  ~body)))))

(defn split-args-by [symb coll ]
  (loop [xs coll
         acc []]
    (if (empty? xs) [acc nil]
        (let [x  (first xs)
              ys (rest  xs)]
          (if (= x symb) [acc (first ys)]
            (recur ys 
                   (conj acc x)))))))

(defn options->spec [m]
  (let [vars     (get m :keys)
        defaults (get m :or)
        name     (get m :as 'options)]
    [vars defaults name]))
        
;;the easiest thing to do is to over-ride the 
;;defn behavior and augment it...
;;something like, with-curried-varargs 
(defmacro defn-curried-options
  [name doc args body]
  (let [[args opts-map]           (split-args-by '&optional args)
        [locals defaults mapname] (options->spec opts-map)]
    `(let [~mapname  ~defaults
           ~opts-map ~mapname]
         (defn ~name ~doc  
           ([~@args ~'user-env]  
              (let [~mapname (reduce-kv (fn [m# k# v#] (assoc m# k# (get ~'user-env v#)))
                                        ~defaults ~locals)
                    ~opts-map ~mapname]
                ~body))              
           ([~@args]  ~body)))))
)



;;Code from Zachary Tellman's excellent Potemkin library, 


;;specifically for importing vars.  Rather than add a full dependency 
;;on potemkin, I chose to extract the pieces I needed here.
;;Copyright � 2013 Zachary Tellman
;;Distributed under the MIT License. This means that pieces of this library may be 
;;copied into other libraries if they don't wish to have this as an explicit 
;;dependency, as long as it is credited within the code.

(defn link-vars
  "Makes sure that all changes to `src` are reflected in `dst`."
  [src dst]
  (add-watch src dst
    (fn [_ src old new]
      (alter-var-root dst (constantly @src))
      (alter-meta! dst merge (dissoc (meta src) :name)))))

(defmacro import-fn
  "Given a function in another namespace, defines a function with the
   same name in the current namespace.  Argument lists, doc-strings,
   and original line-numbers are preserved."
  ([sym]
     `(import-fn ~sym nil))
  ([sym name]
     (let [vr (resolve sym)
           m (meta vr)
           n (or name (:name m))
           arglists (:arglists m)
           protocol (:protocol m)]
       (when-not vr
         (throw (IllegalArgumentException. (str "Don't recognize " sym))))
       (when (:macro m)
         (throw (IllegalArgumentException.
                  (str "Calling import-fn on a macro: " sym))))

       `(do
          (def ~(with-meta n {:protocol protocol}) (deref ~vr))
          (alter-meta! (var ~n) merge (dissoc (meta ~vr) :name))
          (link-vars ~vr (var ~n))
          ~vr))))

(defmacro import-macro
  "Given a macro in another namespace, defines a macro with the same
   name in the current namespace.  Argument lists, doc-strings, and
   original line-numbers are preserved."
  ([sym]
     `(import-macro ~sym nil))
  ([sym name]
     (let [vr (resolve sym)
           m (meta vr)
           n (or name (with-meta (:name m) {}))
           arglists (:arglists m)]
       (when-not vr
         (throw (IllegalArgumentException. (str "Don't recognize " sym))))
       (when-not (:macro m)
         (throw (IllegalArgumentException.
                  (str "Calling import-macro on a non-macro: " sym))))
       `(do
          (def ~n ~(resolve sym))
          (alter-meta! (var ~n) merge (dissoc (meta ~vr) :name))
          (.setMacro (var ~n))
          (link-vars ~vr (var ~n))
          ~vr))))

(defmacro import-def
  "Given a regular def'd var from another namespace, defined a new var with the
   same name in the current namespace."
  ([sym]
     `(import-def ~sym nil))
  ([sym name]
     (let [vr (resolve sym)
           m (meta vr)
           n (or name (:name m))
           n (with-meta n (if (:dynamic m) {:dynamic true} {}))
           nspace (:ns m)]
       (when-not vr
         (throw (IllegalArgumentException. (str "Don't recognize " sym))))
       `(do
          (def ~n @~vr)
          (alter-meta! (var ~n) merge (dissoc (meta ~vr) :name))
          (link-vars ~vr (var ~n))
          ~vr))))

(defmacro import-vars
  "Imports a list of vars from other namespaces."
  [& syms]
  (let [unravel (fn unravel [x]
                  (if (sequential? x)
                    (->> x
                         rest
                         (mapcat unravel)
                         (map
                          #(symbol
                            (str (first x)
                                 (when-let [n (namespace %)]
                                   (str "." n)))
                            (name %))))
                    [x]))
        syms (mapcat unravel syms)]
    `(do
       ~@(map
          (fn [sym]
            (let [vr (resolve sym)
                  m (meta vr)]
              (cond
               (:macro m) `(import-macro ~sym)
               (:arglists m) `(import-fn ~sym)
               :else `(import-def ~sym))))
          syms))))


;;end Potemkin
