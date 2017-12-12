;;A useful set of parsing functions and parser definitions.
;;Typically used to help read string data effeciently.
(ns spork.util.parsing
  (:require [clojure.edn]
            [spork.util [general :as gen]]))


;;Note -> this isn't quite so hot, but it's a general failsafe.
;;It's much much better to use a standard parser for each field, if the 
;;field is a known type.

;;Borrowed from Incanter Source code, credit David Liebke 
(defn parse-string 
	"Parses a string, trying various number formats.  Note, scientific numbers,
	 or strings with digits sandwiching an E or an e will be parsed as numbers,
	 possibly as Double/POSITIVE_INFINITY, i.e. Infinity."
	[^String value]	
  (try (Integer/parseInt value)
    (catch NumberFormatException _
      (try (Double/parseDouble value)
        (catch NumberFormatException _ value)))))

(def scientific-reg 
	"A regular expression gleefully borrowed from Stack Overflow.  Matches 
	 strings that correspond to scientific numbers."
	#"-?\d*\.?\d+[Ee][+-]?\d+")
	 
(defn parse-string-nonscientific 
	"Parses a string, trying various number formats.  Scientific numbers,
	 or strings with digits sandwiching an E or an e will be kept as strings.  
	 Helpful in contexts where there are alphanumeric string values."
	[^String value]
	(if-let [res (re-find scientific-reg value)]
		value
		(parse-string value)))

;;a schema is just a map of field names to either keys or custom parse
;;functions.

;(def the-schema {:field1 :long :field2 :long :field3 :long :field4
;:double}

(defn get-key-or-string [m k default]
  (get m k
       (get m (if (keyword? k)
                (str (subs (str k) 1))
                (keyword k)) default)))

;;This should be moved to a spork.parse lib, since it's 
;;pretty general purpose.
;;Default parsers for a number of data types.
;;string parsers
(def parse-defaults 
  {:string identity
   :text   identity
   :boolean (^boolean fn parse-boolean [^String x] (Boolean/parseBoolean x))
   :number  (fn parse-number [^String x] (try (Integer/parseInt x)
                                              (catch NumberFormatException _
                                                (Double/parseDouble x))))
   :keyword  (^clojure.lang.Keyword fn [^String x] (keyword x))
   :float    (^double fn parse-float   [^String x]    (Double/parseDouble x))
   :double   (^double fn parse-double  [^String x]    (Double/parseDouble x))
   :int      (^int    fn parse-int     [^String x]    (Integer/parseInt x))
   :long     (^long   fn parse-long    [^String x]    (Long/parseLong x))
   :float?   (^double fn parse-float   [^String x]   (try (Double/parseDouble x)
                                                          (catch NumberFormatException _  Double/MIN_VALUE)))
   :double?  (^double fn parse-double  [^String x]   (try (Double/parseDouble x)
                                                           (catch NumberFormatException _ Double/MIN_VALUE)))
   :int?     (^int    fn parse-int     [^String x]   (try (Integer/parseInt x)
                                                           (catch NumberFormatException _ Integer/MIN_VALUE)))
   :long?    (^long   fn parse-long    [^String x]   (try (Long/parseLong x)
                                                           (catch NumberFormatException _ Long/MIN_VALUE)))
   :date    (^java.util.Date fn parse-date [^String x] (java.util.Date. x))
   :clojure clojure.edn/read-string
   :symbol  clojure.edn/read-string
   :literal clojure.edn/read-string
   :code    read-string})

(def ^:dynamic *parsers* parse-defaults) 

(defmacro with-parsers [parsemap & body]
  `(let [parsers# (merge *parsers* ~parsemap)]
     (binding [ *parsers* parsers#]
       ~@body)))

(defn lookup-parser [pfunc & [default]]
  (cond (keyword? pfunc) (get *parsers* pfunc) 
        (fn? pfunc) pfunc
        :otherwise default))

(defn parsing-scheme 
  "A parsing scheme is designed to associate a set of parsers with possibly 
   named fields in a table or record.  When parsing values, the field-parser 
   map is consulted to determine if a parser is defined for the field; If not, 
   the optional default-parser is used.  The standard default-parser is to 
   parse any input as an int, or a float, or a string.  This is slow, but 
   general.  Caller may supply a map of custom parsers, of {type parsing-fn} to
   override the built-in parsing defaults."
  [field-parser & {:keys [default-parser custom-parsers] 
                   :or   {default-parser parse-string}}]
  (let [get-parser (gen/memo-1  (fn [field] 
                                  (if-let [pfunc (get-key-or-string field-parser field default-parser)]
                                    (with-parsers  custom-parsers  (lookup-parser pfunc default-parser)) 
                                      parse-string)))]
      (fn [field ^String v] ((get-parser field) v))))

(defn nested-parser 
  "A nested-parser allows us to compose a sequence of parsers; where 
   the parsers will be tried, left-to-right.  If the left parse fails, 
   its default parse is the right, which either succeeds or defaults to 
   the parse to its right, eventually ending with the final parser."
  [schemes & {:keys [default-parser] 
              :or   {default-parser parse-string}}]
  (let [revschemes (reverse schemes)]
    (reduce (fn [r l]
              (parsing-scheme l :default-parser r))
            (parsing-scheme (first revschemes) :default-parser default-parser)
            (rest revschemes))))

(comment
(defn vec-parser 
  "Given a set of fields, and a function that maps a field name to 
   a parser::string->'a, returns a function that consumes a sequence
   of strings, and parses fields with the corresponding 
   positional parser.  Alternately, caller may supply a parser as a 
   single argument, to be applied to a vector of strings."
  ([fields field->value]
     (let [xs->values (vec (map #(partial field->value %) fields))]
       (fn [xs]
         (loop [acc (transient [])
                idx 0]
           (if (= idx (count xs->values)) (persistent! acc)
               (recur (conj! acc ((nth xs->values idx) (nth xs idx)))
                      (inc idx)))))))
  ([f] 
     (let [parsefunc (lookup-parser f identity)]
       (fn [xs]         
         (persistent! 
          (reduce (fn [acc x] 
                    (conj! acc (parsefunc x))) (transient []) 
                    xs))))))
)

;;There's some loosery-goosiness to tab delimited parsing, 
;;that happens when we're parsing tab delimited strings.
;;we want to allow a clean parse if the last field in a 
;;record is empty.

(defn partial2 [f arg] (fn part [x] (f arg x))) 

(defn vec-parser 
  "Given a set of fields, and a function that maps a field name to 
   a parser::string->'a, returns a function that consumes a sequence
   of strings, and parses fields with the corresponding 
   positional parser.  Alternately, caller may supply a parser as a 
   single argument, to be applied to a vector of strings."
  ([fields field->value]
   (let [xs->values  (vec (map #(partial2 field->value %) fields))
         bound       (count xs->values)
         uptolast    (dec bound)]
     (fn [xs]
       (let [res 
             (loop [acc []
                    idx 0]
               (if (= idx uptolast) acc
                   (recur (conj acc ((nth xs->values idx) (nth xs idx)))
                          (inc idx))))]
         (conj res 
                   (cond (== (count xs) bound) 
                         ((nth xs->values uptolast) (nth xs uptolast)) ;;append the last result
                         (== (count xs) uptolast) nil))))))      ;;add an empty value                 
  ([f] 
   (let [parsefunc (lookup-parser f identity)]
     (fn [xs]         
       (reduce (fn [acc x] 
                 (conj acc (parsefunc x)))  []
                 xs)))))

;;another option is...
;;as part of the parsing function, we conj the result onto the
;;vector..
;;Alternately, we return a reducible seq.
;;a way around this would be to have a reducible
;;line-splitter...

(defn vec-parser! 
  "Mutable.  Given a set of fields, and a function that maps a field name to 
   a parser::string->'a, returns a function that consumes a sequence
   of strings, and parses fields with the corresponding 
   positional parser.  Alternately, caller may supply a parser as a 
   single argument, to be applied to a vector/array of strings."
  ([fields field->value]
   (let [xs->values  (vec (map #(partial2 field->value %) fields))
         bound       (count xs->values)
         uptolast    (dec bound)
         row         (object-array bound)]
     (fn [xs]
       (let [res (loop [idx 0]
                   (if (== idx uptolast) row
                       (do (aset row idx ((nth xs->values idx) (nth xs idx)))
                           (recur (unchecked-inc idx)))))]
         (do (aset row uptolast
                   (if (== (count xs) bound) 
                     ((nth xs->values uptolast) (nth xs uptolast)) ;;append the last result
                     nil))
             row)))))      ;;add an empty value                 
  ([f] 
   (let [parsefunc (lookup-parser f identity)]
     (fn [xs]         
       (reduce (fn [acc x] 
                 (conj acc (parsefunc x)))  []
                 xs)))))

(defn record-parser 
  "Given an implied schema as indicated by the map, returns a function that
   parses maps with identical fields using the parser."
  [m & {:keys [default-parser] :or {default-parser parse-string}}]
  (let [field->value (parsing-scheme m :default-parser default-parser)
        fields       (vec  (keys m))]
    (fn [r]
      (->> fields
           (reduce (fn [acc k] (assoc! acc k (field->value k (get r k)))) (transient r))
           (persistent!)))))

;;testing
(comment 
  (time (dotimes [i  1000] (parse-string "2")))  
  (def the-parser  (record-parser {:name :string :age  :number}))
  (def generic-parser (record-parser {}))
  (def simple-records [ {:name "Leonidas" :age "40"}
                        {:name "Bart Simpson" :age "12"}
                        {:name "Bill Shatner" :age "68"}])

  (time  (dotimes [i 1000]   (map the-parser simple-records)))
  )
