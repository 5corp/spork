;A collection of macros and functions useful for metaprogramming and library
;development. 
(ns spork.util.metaprogramming)


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
  [pathname & ks] `(~'defn ~pathname [~'m] (get-in ~'m ~@ks)))

(defmacro defpaths
  "Allows multiple paths to be defined at once, with the possibility of sharing 
   a common prefix.  Consumes a map of [pathname path] and applies defpath to 
   each in turn.  A common prefix may be supplied to the paths. "
  ([kvps]         `(defpaths [] ~kvps))
  ([prefix kvps]  
    `(do ~@(map (fn [[n p]] `(defpath ~n ~(into prefix p))) kvps))))

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


   
