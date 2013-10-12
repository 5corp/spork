;;A testing suite for all things graph related.
(ns spork.cljgraph.tests
  (:require [clojure.test :refer :all]
            [spork.cljgraph.core :refer :all]))

;;helper macro for some error catching I want to ensure.
(defmacro get-error [expr]
  `(try (do ~expr nil) 
      (catch Exception ~'e  ~'e)))

;a tree of elements:
  ;              a
  ;            b c d 
  ;           e f    g
  ;          h   i     j
  ;         k l  m n o  p q
  ;                        r s t u v w x y z a1 a2 a3 
(defn ->tree-arc [from to] [from to 1])
(defn tree-arcs [from xs] (map #(->tree-arc from %) xs))
(def  the-tree (-> empty-graph
                  (add-arcs (tree-arcs :a [:b :c :d]))
                  (add-arcs (conj (tree-arcs :b [:e :f]) (->tree-arc :d :g)))
                  (add-arcs [(->tree-arc :e :h) 
                             (->tree-arc :f :i) 
                             (->tree-arc :g :j)])
                  (add-arcs [(->tree-arc :h :k) (->tree-arc :h :l) 
                             (->tree-arc :i :m) (->tree-arc :i :n) 
                             (->tree-arc :i :o) (->tree-arc :j :p) 
                             (->tree-arc :j :q)])
                  (add-arcs (tree-arcs :q  
                                       [:r :s :t :u :v :w :x :y :z :a1 :a2 :a3]))))
(deftest graph-walks
  (is (= (depth-nodes the-tree :a)
         [:a :d :g :j :q :a3 :a2 :a1 :z :y :x :w :v :u :t :s :r :p :c :b 
          :f :i :o :n :m :e :h :l :k])
      "depth first node ordering")  
  (is (= (ordered-nodes the-tree :a)
         [:a :b :e :h :k :l :f :i :m :n :o :c :d :g :j :p :q :r :s :t :u :v 
          :w :x :y :z :a1 :a2 :a3])
      "conj-ordered node ordering")  
  (is (= (breadth-nodes the-tree :a)
         [:a :b :c :d :e :f :g :h :i :j :k :l :m :n :o :p :q :r :s :t :u :v 
          :w :x :y :z :a1 :a2 :a3])
      "breadth-first node ordering")  
  (is (= (undirected-nodes the-tree :a) 
         [:a :d :g :j :q :a3 :a2 :a1 :z :y :x :w :v :u :t :s :r :p :c :b :f 
          :i :o :n :m :e :h :l :k])
      "undirected, depth-first node ordering")  
  (is (= (topsort-nodes the-tree)
         [:a :c :b :d :f :g :e :j :i :h :l :k :m :n :o :q 
          :p :a3 :r :z :y :x :v :w :t :u :a2 :a1 :s])
      "top-sort node ordering"))
 
(deftest tree-searching 
  (is (= (path? (depth-first-search the-tree :a :q)) 4)
      "Simple depth path in the-tree.  :q should be 4 hops from :a")
  (is (= (path? (breadth-first-search the-tree :a :q)) 4)
      "Simple breadth path in the-tree.  :q should be 4 hops from :a")
  (is (= (path? (priority-first-search the-tree :a :q)) 4)
      "Simple priority path in the-tree.  :q should be 4 hops from :a")
  (is (= (path? (random-search the-tree :a :q)) 4)
      "Simple random path in the-tree.  :q should be 4 hops from :a")
  )
  
 ;a directed graph...
 ;    a -> b -> d
 ;          \. ^
 ;           c/
 ;    e -> f/^
 
(def the-graph 
  (arcs->graph [[:a :b] [:b :c] [:c :d] [:e :f] [:f :c] [:b :d]]))
 ;a directed graph with 2 components
 ;    a -> b -> d
 ;          \. ^
 ;           c/
 ;    e -> f
 
(def the-graph2 
  (-> empty-graph
      (add-arcs [[:a :b] [:b :c] [:c :d] [:e :f] [:b :d]])))
 ;a directed graph with 2 components
 ;    a -> b -> d
 ;          \. ^
 ;           c/
 ;    e -> f
 ;    g 
 ;    h
 
(def the-graph3
  (-> (arcs->graph [[:a :b] [:b :c] [:c :d] [:e :f] [:b :d]])
      (conj-node :g)
      (conj-node :h)))

(deftest  connectivity-queries
  (is (= (components the-graph)  {6 [#{:a :c :b :f :d :e}]})
      "Should be one component in the-graph, containing all six nodes.")
  (is (= (components the-graph2) {4 [#{:a :c :b :d}], 2 [#{:f :e}]}) 
      "Should be two components in the-graph2, containing 4 and 2 nodes.")
  (is (= (components the-graph3) 
         {4 [#{:a :c :b :d}], 2 [#{:f :e}], 1 [#{:g} #{:h}]}) 
      "Should be 4 components in the-graph3, containing 4, 2, 1,1 nodes.")
  (is (empty? (islands the-graph)) "There should be no islands in the-graph.")
  (is (empty? (islands the-graph2)) "There should be no islands in the-graph2.")
  (is (= (count (islands the-graph3)) 2) 
      "There should be 2 islands in the-graph3."))     

 ;class-graph: a weighted directed graph with  components
 ;                  |-----4-->G--1-->F
 ;                  |                ^
 ;                  |                |
 ;    A--2-->B--2-->D--3-|           |
 ;    |             ^    |           |
 ;    ---3-->C--4---|    >E-----5----|
 ;           |            ^
 ;           |----1-------|
 ;
 ;    X--1-->Y--1-->Z
 ;    
 ;    M--1-->N

(def class-graph 
  (-> (arcs->graph
       [["A" "B" 2]     ;1st eq class
        ["A" "C" 3]     ;1st eq class
        ["B" "D" 2]     ;1st eq class
        ["C" "D" 4]     ;1st eq class
        ["C" "E" 1]     ;1st eq class
        ["D" "E" 3]     ;1st eq class
        ["D" "G" 4]     ;1st eq class
        ["E" "F" 5]     ;1st eq class
        ["G" "F" 1]     ;1st eq class
        ["Y" "Z" 1]     ;2nd eq class
        ["X" "Y" 1]     ;2nd eq class
        ["M" "N" 1]])   ;3rd eq class
      (conj-node "P")))  ;4th eq class, island

(deftest decomposition-test
  (is (= (map nodes (decompose class-graph))
         ({"A" 0, "B" 1, "C" 2, "D" 3, "E" 4, "F" 6, "G" 5} 
           {"N" 11, "M" 10} 
           {"P" 12} 
           {"Z" 8, "Y" 7, "X" 9}))
      "class-graph should decompose into 4 smaller graphs."))

 ;sample-dag: a weighted directed acyclic graph with 
 ;            1 component
 ;
 ;Start
 ; |                    |-----4-->G--1-->F--0->Destination
 ; 0                    |                ^
 ; |                    |                |
 ; ---->A--2-->B--2-->D--3-|             |
 ;      |             ^    |             |
 ;      ---3-->C--4---|    >E-----5------|
 ;      |       |            ^           |
 ;      1       |----1-------|           0
 ;      |                                |
 ;      ->X--7-->Y---1------>ShortCut!---|
 ;        |
 ;        --2--->Z            

(def sample-dag
  (arcs->graph
   [[ "Start"  "A"  0]
    [ "A"  "B"  2]
    [ "A"  "C"  3]
    [ "B"  "D"  2]
    [ "C"  "D"  4]
    [ "C"  "E"  1]
    [ "D"  "E"  3]
    [ "D"  "G"  4]
    [ "E"  "F"  5]
    [ "G"  "F"  1]
    [ "A"  "X"  1]
    [ "X"  "Y"  7]
    [ "X"  "Z"  2]
    [ "Y"  "Shortcut!"   1]
    [ "Shortcut!"  "F"   0]
    [ "F"  "Destination" 0]]))

(deftest sssp-sample-dag
  (is (= (topsort-nodes sample-dag)
         ["Start" "A" "B" "C" "X" "D" "Y" "Z" "E" "G" "Shortcut!" "F" "Destination"])
      "Topological ordering of sample-dag.")
  (is (= (get-weighted-paths (depth-first-search sample-dag "Start" "Destination"))
         '([6 ("Start" "A" "X" "Y" "Shortcut!" "F" "Destination")]))
      "Depth distance should be 6 for sample-dag")
  (is (= (get-weighted-paths (breadth-first-search sample-dag "Start" "Destination"))
         '([5 ("Start" "A" "C" "E" "F" "Destination")]))
      "Breadth distance should be 5 for sample-dag.")
  (is (= (get-weighted-paths (dijkstra sample-dag "Start" "Destination"))
         '([9 ("Start" "A" "C" "E" "F" "Destination")] 
           [9 ("Start" "A" "B" "D" "G" "F" "Destination")] 
           [9 ("Start" "A" "X" "Y" "Shortcut!" "F" "Destination")]))      
      "Dijkstra and priority-first-search should produce 3 9-weight paths for sample-dag.")
  (is (= (get-weighted-paths 
           (a-star sample-dag (fn [source target] 0) "Start" "Destination"))
         (get-weighted-paths (dijkstra sample-dag "Start" "Destination")))
      "a-star with a 0-weight heuristic is identical to dijkstra's algorithm")
  (is (= (get-weighted-paths (bellman-ford sample-dag "Start" "Destination"))
         '([9 ("Start" "A" "C" "E" "F" "Destination")] 
           [9 ("Start" "A" "B" "D" "G" "F" "Destination")] 
           [9 ("Start" "A" "X" "Y" "Shortcut!" "F" "Destination")]))
      "Bellman-Ford should produce 3 9-weight paths for sample-dag, albeit slower."))


;;positive-graph:
;;A----5---->B----10-->D---200--->E
;;|          ^         ^
;;|          |         |
;;|          3         |
;;---2-->C---|-----1---|

;;A has 2 shortest paths to B
;;A has 1 shortest path to C and D.
(def positive-graph 
  (arcs->graph [["A" "B" 5] 
                ["A" "C" 2] 
                ["C" "B" 3] 
                ["B" "D" 10]
                ["C" "D" 1] 
                ["D" "E" 200]]))

(deftest positive-graph-tests
  (is (= (get-weighted-paths (dijkstra positive-graph "A" "B"))
         (get-weighted-paths (bellman-ford positive-graph "A" "B"))
         '([5 ("A" "B")] [5 ("A" "C" "B")]))
         "Should be 2 shortest paths in positive-graph from A to B")
  (is (= (get-weighted-paths (dijkstra positive-graph "A" "C"))
         (get-weighted-paths (bellman-ford positive-graph "A" "C"))
         '([2 ("A" "C")]))
         "Should be 1 path in positive-graph from A to C")
  (is (= (get-weighted-paths (dijkstra positive-graph "A" "D"))
         (get-weighted-paths (bellman-ford positive-graph "A" "D"))
         '([3 ("A" "C" "D")]))
         "Should be 1 path in positive-graph from A to C")
  (is (= (get-weighted-paths (dijkstra positive-graph "A" "E"))
         (get-weighted-paths (bellman-ford positive-graph "A" "E"))
         '([203 ("A" "C" "D" "E")]))
         "Should be one path from A to E in positive-graph."))

;;A variation on positive-graph to test Dijkstra's non-negativity requirement.
;;There is a negative arc from D to E.  If a caller requests a path that forces 
;;Djikstra to engage this arc in the search, it should throw an exception.
;;An alternative is to apply a quick check to all the arcs...but this is 
;;costly.  Still debating how best to approach this.

;;negative-graph:
;;A----5---->B----10-->D--(-200)--->E
;;|          ^         ^
;;|          |         |
;;|          3         |
;;---2-->C---|-----1---|
(def negative-graph 
  (arcs->graph [["A" "B" 5] 
                ["A" "C" 2] 
                ["C" "B" 3] 
                ["B" "D" 10]
                ["C" "D" 1] 
                ["D" "E" -200]]))

(deftest negative-graph-tests 
  (is (= (str (get-error (dijkstra negative-graph "A" "E")))
         "java.lang.Exception: Negative Arc Weight Detected in Dijkstra: [\"D\" \"E\" -200]")
      "dijkstra should throw negative arc exception on negative-graph [D E] .")
  (is (= (get-weighted-paths (bellman-ford negative-graph "A" "E"))
         '([-197 ("A" "C" "D" "E")]))
      "bellman-ford should find a path on negative-graph."))

;;negative-cycle-graph:
;;------------------0-----------------|  
;;|                                   |
;;|                                   |
;;> A----5---->B----10-->D--(-200)--->E
;;  |          ^         ^
;;  |          |         |
;;  |          3         |
;;  ---2-->C---|-----1---|
(def negative-cycle-graph 
  (arcs->graph [["A" "B" 5] 
                ["A" "C" 2] 
                ["C" "B" 3] 
                ["B" "D" 10]
                ["C" "D" 1] 
                ["D" "E" -200]
                ["E" "A" 0]]))

(deftest negative-cycle-tests 
  (is (= (str (get-error (dijkstra negative-cycle-graph "A" "E")))
         "java.lang.Exception: Negative Arc Weight Detected in Dijkstra: [\"D\" \"E\" -200]")
      "dijkstra should throw negative arc exception on negative-graph [D E] .")
  (is (= (:negative-cycles (bellman-ford negative-cycle-graph "A" "E"))
         ["E" "A"])
      "bellman-ford should find a negative cycle on negative-cycle-graph."))

;a directed graph with 2 components
;a - b - c - d - e
(def the-list 
  (-> empty-graph
      (add-arcs [[:a :b] [:b :c] [:c :d] [:d :e]])))
 
;;old test in migration
(comment 
  (def dlist (->double-list :a :e the-list))  
  (def the-root-tree  (-> empty-graph (conj-node :root)))
  (def the-other-tree (-> empty-graph (add-arcs (tree-arcs :a [:b :c]))))
  )
