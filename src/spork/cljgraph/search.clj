;;Generic graph search libraries, with default implementations for depth,
;;breadth, priority, random searches and traversals (walks).
(ns spork.cljgraph.search
  (:require [spork.protocols [core :as generic]]
            [spork.data      [searchstate :as searchstate]]
            [spork.util      [topographic :as top]]))

(defn- possible?
  "Are start and target possibly transitively related in graph g?" 
  [g startnode targetnode]
  (if (and (top/sinks g startnode) (top/sources g targetnode)) true 
    false))
   
(defn- default-neighborf 
  "Return a list of valid fringe nodes, adjacent to node nd in graph g, 
   relative to searchstate.  Ignores the state.  This will allow multiple 
   visits.  Useful for some algorithms."
  [g nd state] (top/sinks g nd))

(defn unit-weight [g source sink] 1)
;;Note: We could probably refactor these guys.  The only thing that's different
;;about them is the function generating neighbors.

(defn- visit-once
  "Screen nodes that have already been visited.  If we have visited any nodes 
   at least once, they will show up in the shortest path tree."
  [g nd {:keys [shortest] :as state}] 
  (filter #(not (contains? shortest %)) (top/sinks g nd))) 

(defn- visit-ordered-once
  "Screen nodes that have already been visited.  If we have visited any nodes 
   at least once, they will show up in the shortest path tree.  Additionally, 
   this will visit the nodes in the order the incident arcs were appended to the 
   graph, if the underlying the graph supports it."
  [g nd {:keys [shortest] :as state}] 
  (filter #(not (contains? shortest %)) (rseq (top/sinks g nd))))

(defn- visit-neighbors-once
  "Treats the graph as if it's undirected.  Screen nodes that have already been 
   visited.  If we have visited any nodes at least once, they will show up in 
   the shortest path tree."
  [g nd {:keys [shortest] :as state}] 
  (filter #(not (contains? shortest %)) (top/neighbors g nd)))

(defn- default-halt?  [state nextnode]
  (or (= (:targetnode state) nextnode) 
          (generic/empty-fringe? (:fringe state))))

;;_POSSIBLE OPTIMIZATION__ Maintain the open set explicitly in the search state
;;vs explicitly computing __unexplored__ .

(defn- unexplored
  "Given a search state, with a shortest path tree, and a graph g, 
   determine which nodes have not been explored in g."
  [g state]
  (clojure.set/difference (set (top/get-node-labels g)) 
                          (set (keys (:spt state)))))

;the normal mode for graph walking/searching.  
(def default-walk {:halt? default-halt?
                   :weightf top/arc-weight
                   :neighborf default-neighborf})

(def limited-walk (assoc default-walk :neighborf visit-once))
(def unit-walk    (assoc limited-walk :weightf unit-weight))

(defn next-candidate
  "To support hueristics, we allow candidate nodes to be encoded as entries, 
   rather than just simple keywords.  If we detect the"
  [fringe]
  (generic/next-fringe fringe))    


;;__TODO__ Reformat to use nested entries, or build your own primitive type. 

(defn traverse
  "Generic fn to walk a graph.  The type of walk can vary by changing the 
   fringe of the searchstate, the halting criteria, the weight-generating 
   function, or criteria for filtering candidates.  Returns a searchstate 
   of the walk, which contains the shortest path trees, distances, etc. for 
   multiple kinds of walks, depending on the searchstate's fringe structure."
  [g startnode targetnode startstate & {:keys [halt? weightf neighborf] 
                                         :or  {halt?     default-halt?
                                               weightf   top/arc-weight
                                               neighborf default-neighborf}}]
    (let [get-weight    (partial weightf   g)
          get-neighbors (partial neighborf g)
          relaxation    (fn [source s sink]
                          (generic/relax s get-weight source sink))]
      (loop [state (generic/conj-fringe startstate startnode 0)]
        (if (generic/empty-fringe? state) state 
            (let [candidate (generic/next-fringe state) ;returns an entry, with a possibly estimated weight.
                  nd (generic/entry-node candidate)]    ;next node to visit
              (if (halt? state nd)  state
                  (recur (reduce (partial relaxation nd) 
                                 (generic/pop-fringe state) 
                                 (get-neighbors nd state))))))))) 

(defn depth-traversal
  "Returns a function that explores all of graph g in depth-first topological 
   order from startnode.  This is not a search.  Any paths returned will be 
   relative to unit-weight."
  [g startnode & endnode ] 
  (traverse g startnode (generic/maybe endnode ::nullnode) 
              (searchstate/empty-DFS startnode)
              :neighborf visit-once
              :weightf unit-weight))
 
(defn breadth-traversal
  "Returns a function that explores all of graph g in a breadth-first 
   topological order from startnode.  This is not a search, any paths returned 
   will be relative to unit-weight."
  [g startnode & endnode] 	  
  (traverse g startnode 
              (generic/maybe endnode ::nullnode) 
              (searchstate/empty-BFS startnode) 
              :weightf unit-weight 
              :neighborf visit-once))
 
(defn ordered-traversal
  "Returns a function that explores all of graph g in depth-first topological 
   order from startnode.  This is not a search.  Any paths returned will be 
   relative to unit-weight."
  [g startnode & endnode] 
  (traverse g startnode (generic/maybe endnode ::nullnode) 
              (searchstate/empty-DFS startnode)
              :neighborf visit-ordered-once
              :weightf unit-weight))

(defn random-traversal
  "Returns a function that explores all of graph g in depth-first topological 
   order from startnode.  This is not a search.  Any paths returned will be 
   relative to unit-weight."
  [g startnode & endnode] 
  (traverse g startnode (generic/maybe endnode ::nullnode) 
              (searchstate/empty-RFS startnode)
              :neighborf visit-once
              :weightf unit-weight))

(defn undirected-traversal
  "Returns a function that explores all of graph g in depth-first topological 
   order from startnode.  This is not a search.  Any paths returned will be 
   relative to unit-weight."
  [g startnode & endnode] 
  (traverse g startnode (generic/maybe endnode ::nullnode) 
              (searchstate/empty-RFS startnode)
              :neighborf visit-neighbors-once
              :weightf unit-weight))

(defn priority-traversal
  "Returns a function that explores all of graph g in a priority-first 
  topological order from a startnode. Weights returned will be in terms of the 
  edge weights in the graph."
  [g startnode & endnode] 	  
  (traverse g startnode 
              (generic/maybe endnode ::nullnode) 
              (searchstate/empty-PFS startnode)))

;;explicit searches, merely enforces a walk called with an actual destination
;;node.

(defn depth-first-search
  "Starting from startnode, explores g using a depth-first strategy, looking for
   endnode.  Returns a search state, which contains the shortest path tree or 
   precedence tree, the shortest distance tree.  Note: depth first search is 
   not guaranteed to find the actual shortest path, thus the shortest path tree
   may be invalid."
  [g startnode endnode]
  (depth-traversal g startnode endnode))

(defn bread-first-search
  "Starting from startnode, explores g using a breadth-first strategy, looking 
   for endnode. Returns a search state, which contains the shortest path tree 
   or precedence tree, the shortest distance tree.  Note: breadth first search 
   is not guaranteed to find the actual shortest path, thus the shortest path 
   tree may be invalid."
  [g startnode endnode]
  (breadth-traversal g startnode endnode))

(defn priority-first-search
  "Starting from startnode, explores g using a priority-first strategy, looking 
   for endnode. Returns a search state, which contains the shortest path tree or 
   precedence tree, the shortest distance tree.  The is equivalent to djikstra's
   algorithm.  Note: Requires that arc weights are non-negative.  For negative 
   arc weights, use Bellman-Ford, or condition the graph."
  [g startnode endnode]
  (priority-traversal g startnode endnode))

(defn djikstra
  "Starting from startnode, explores g using djikstra's algorithm, looking for
   endnode.  Gradually relaxes the shortest path tree as new nodes are found.  
   If a relaxation provides a shorter path, the new path is recorded.  Returns a 
   search state, which contains the shortest path tree or precedence tree, the 
   shortest distance tree.  Note: Requires that arc weights are non-negative.  
   For negative arc weights, use Bellman-Ford, or condition the graph."
  [g startnode endnode] 
  (priority-traversal g startnode endnode))

;;__TODO__ Check implementation of a-star, I think this is generally correct.

(defn a*
  "Given a heuristic function, searches graph g for the shortest path from 
   start node to end node.  Operates similarly to djikstra or 
   priority-first-search, but uses a custom weight function that applies the 
   hueristic to the weight.  heuristic should be a function that takes a 
   a source node and target node, and returns an estimated weight to be 
   added to the actual weight.  Note, the estimated value must be non-negative."
  [g heuristic-func startnode endnode]
  (traverse g startnode endnode 
    (assoc (searchstate/empty-PFS startnode) :estimator heuristic-func)))

;;This is identical to get components, or decompose.
(defn graph-forest 
  "Given a graph g, and an arbitrary startnode, return the sequence of all 
   depth-first shortest path trees.  This should return a sequence of 
   components within the graph, a series of depth-walks from a monotonically 
   decreasing set of startnodes."
  [g startnode]
  (let [walk (partial depth-traversal g)
        take-step (fn take-step [g found state]
                    (if-let [remaining 
                             (clojure.set/difference found 
                                                     (unexplored g state))]
                      (lazy-seq 
                        (let [nextstart (first remaining)
                              nextstate (walk nextstart)
                              remaining (disj remaining nextstart)]
                          (concat 
                            (list state) (take-step g remaining nextstate))))
                       state))]
    (take-step g #{} (walk startnode))))