;;A graph data structure that maintains numerical indices 
;;internally, so that all graph operations are performed 
;;effeciently on primitives.  Graphs maintain a mapping 
;;of indices to labels, and can be coerced to and from 
;;indexed graphs.
(ns spork.data.indexedgraph
  (:require [spork.cljgraph [core :as graph]
                            [flow :as flow]
                            [jungapi :as viz]]
            [spork.protocols [core :as generic]]
            [spork.data      [searchstate :as searchstate]
                             [mutable :as m]]
            [spork.util      [array :as arr]]))

(def ^:const posinf Long/MAX_VALUE)
(def ^:const neginf Long/MIN_VALUE)

(defn index-nodes [supply-net] 
  (object-array  (-> (graph/topsort-nodes supply-net))))

(defrecord array-net  [g ^objects nodes nodenum nodemap ^long n 
                       ^objects flows ^objects capacities ^objects costs])

(defn ^array-net net->array-net
  "Create a mutable, array-backed network that can be efficiently searched and 
   mutated.  Highly useful for small dynamic networks.  Creates a mapping of nodes 
   to indices.  Internal graph operations are performed on primitive arrays."
  [supply-net]
  (let [^objects node-template (index-nodes supply-net)
        nodemap (into {} (map-indexed vector node-template))
        node->num (into {} (map-indexed (fn [i r] [r i]) node-template))
        nodes     (alength node-template)
        fill-node  (unchecked-dec nodes)
        demand-node  (unchecked-dec fill-node)
        flows        (arr/longs-2d nodes nodes)
        caps         (arr/longs-2d nodes nodes)
        costs        (arr/longs-2d nodes nodes)]
    (do (doseq [spork.cljgraph.flow.einfo e (flow/get-edge-infos supply-net)]
          (let [from (get node->num (.from e))
                to   (get node->num (.to e))
                cost (generic/-arc-weight supply-net (.from e) (.to e))]
            (do (arr/deep-aset longs flows from to (.flow e))
                (arr/deep-aset longs caps  from to (.capacity e))
                (arr/deep-aset longs costs from to cost))))
        (->array-net supply-net
                     node-template
                     node->num
                     nodemap
                     nodes
                     flows
                     caps
                     costs))))

(defn ^long array-flow-weight [^array-net a ^long from ^long to]
  (if (< from to)
      (arr/deep-aget longs (.costs a) from to)
      (unchecked-negate    (arr/deep-aget longs (.costs a) to from))))

;;returns neighbors which have flow.
;;since node indices are top sorted, we only have to 
;;check if (< from to)
(defn ^java.util.ArrayList array-flow-neighbors [^array-net a ^long v]
  (let [flows (.flows a)
        capacities (.capacities a)
        bound (.n a)
        acc   (java.util.ArrayOist.)]
    (do 
      (loop [to 0] ;forward arcs
        (when (not (== to bound))
          (if (== to v) (recur (unchecked-inc to))
              (do (when (> (arr/deep-aget longs capacities v to) 0)
                    (.add acc to))
                  (recur (unchecked-inc to))))))
      (loop [from 0]
        (when (not (== from bound))
          (if (== from v) (recur (unchecked-inc from))
              (do (when (and (not (zero? (arr/deep-aget longs flows from v)))
                             (pos? (arr/deep-aget longs capacities from v)))
                    (.add acc from))
                  (recur (unchecked-inc from))))))
      acc)))

(definterface IFlowSearch
  (newPath   [^long source ^long sink ^long w])
  (shorterPath [^long source ^long sink ^long wnew])
  (bestKnownDistance [^long source]))

(m/defmutable arraysearch [^long startnode ^long targetnode ^longs shortest ^longs distance 
                           ^booleans known fringe nodemap]
  generic/IGraphSearch
  (new-path [state source sink w]
            (let [^long source source
                  ^long sink sink
                  ^long w w]
              (do (aset known sink true)
                  (aset shortest sink source)
                  (aset distance sink w)
                  (set! fringe (generic/conj-fringe fringe sink w))
                  state)))
  (shorter-path [state source sink wnew wpast]
                (let [^long source source
                      ^long sink sink
                      ^long wnew wnew]
                  (do (aset shortest sink source)
                      (aset distance sink wnew)
                      (set! fringe (generic/conj-fringe fringe sink wnew))
                      state)))
  (equal-path [state source sink] state)
  (conj-visited [state source] state)
  (best-known-distance [state x] (if (aget known x) (aget distance x) nil))
  IFlowSearch
  (newpath [state ^long sink ^long w]
           (do (aset known sink true)
               (aset shortest sink source)
               (aset distance sink w)
               (set! fringe (generic/conj-fringe fringe sink w))
               state))
  (shorterPath [state ^long source ^long sink ^long wnew]
               (do (aset shortest sink source)
                   (aset distance sink wnew)
                   (set! fringe (generic/conj-fringe fringe sink wnew))
                   state))
  (bestKnownDistance [state ^long x] (if (aget known x) (aget distance x) nil))
  generic/IFringe
  (conj-fringe [this n w] (do (set! fringe (generic/conj-fringe fringe n w)) this))
  (next-fringe [this]     (generic/next-fringe fringe))
  (pop-fringe [this]      (do (set! fringe (generic/pop-fringe fringe)) this)))

(defn ^arraysearch empty-search [^array-net net ^long startnode ^long endnode fringe]
  (let [knowns (boolean-array (.n net))]
    (do (aset knowns 0 true)
        (arraysearch. startnode
                      endnode
                      (long-array (.n net))
                      (long-array (.n net))
                      knowns
                      fringe
                      (.nodemap net)))))

(defn ^arraysearch flow-relax
  "Given a shortest path map, a distance map, a source node, sink node, 
   and weight(source,sink) = w, update the search state.
   
   Upon visitation, sources are conjoined to the discovered vector.

   The implication of a relaxation on a sink, relative to the source, is that 
   source no longer exists in the fringe (it's permanently labeled).

   So a relaxation can mean one of three things:
   1:  sink is a newly discovered-node (as a consequence of visiting source);
   2:  sink was visited earlier (from a different source), but this visit exposes 
       a shorter path to sink, so it should be elevated in consideration in 
       the search fringe.
   3:  sink is a node of equal length to the currently shortest-known path from
       an unnamed startnode.  We want to record this equivalence, which means
       that we may ultimately end up with multiple shortest paths."
  [^arraysearch state ^long w ^long source ^long sink]
  (let [relaxed (unchecked-add (.bestKnownDistance state source) w)]
    (if-let [known (.bestKnownDistance state sink)]
      (if (< relaxed known) (.shorterPath state source sink relaxed)
          state)
      (.newPath state source sink relaxed))))

(defn array-flow-traverse
  "Custom function to walk an array-backed flow network."
  [^array-net g ^long startnode ^long targetnode startstate]
  (loop [state (-> (assoc! startstate :targetnode targetnode)
                   (generic/conj-fringe startnode 0))]
    (if-let [source (generic/next-fringe state)] ;next node to visit
      (let [visited (generic/visit-node state source)] ;record visit
        (if (= targetnode source) visited
            (recur (let [^java.util.ArrayList xs (array-flow-neighbors g source)
                         n (.size xs)]
                     (loop [acc visited
                            idx 0]
                       (if (== idx n) acc
                           (recur (generic/relax acc (array-flow-weight g source (.get xs idx))
                                                 source (.get xs idx))
                                  (unchecked-inc idx))))))))
                     state)))

(defn array-flow-traverse! 
    "Custom function to walk an array-backed flow network.  Using a custom search state."
    [^array-net g ^long startnode ^long targetnode fringe]
    (loop [^arraysearch state (-> (empty-search g startnode targetnode fringe)
                                  (generic/conj-fringe startnode 0))]
      (if-let [source (generic/next-fringe state)] ;next node to visit
        (let [visited (generic/pop-fringe state)] ;record visit
          (if (== targetnode source) visited 
              (recur (let [^java.util.ArrayList xs (array-flow-neighbors g source)
                           n (.size xs)]
                       (loop [acc visited
                              idx 0]
                         (if (== idx n) acc
                             (recur (generic/relax acc (array-flow-weight g source (.get xs idx))
                                                   source (.get xs idx))
                                    (unchecked-inc idx))))))))
        state)))
                                                                        
                                                                        
(defn array-flow-traverse!! 
    "Custom function to walk an array-backed flow network.  Using a custom search state."
    [^array-net g ^long startnode ^long targetnode fringe]
    (loop [^arraysearch state (-> (empty-search g startnode targetnode fringe)
                                  (generic/conj-fringe startnode 0))]
      (if-let [source (generic/next-fringe state)] ;next node to visit
        (let [visited (generic/pop-fringe state)] ;record visit
          (if (== targetnode source) visited 
              (recur (let [^java.util.ArrayList xs (array-flow-neighbors g source)
                           n (.size xs)]
                       (loop [acc visited
                              idx 0]
                         (if (== idx n) acc
                             (recur (flow-relax (array-flow-weight g source (.get xs idx))
                                                   source (.get xs idx))
                                    (unchecked-inc idx))))))))
        state)))

(definline array-mincost-aug-path [g from to]
  `(searchstate/first-path (array-flow-traverse ~g ~from ~to (searchstate/mempty-PFS ~from))))

(defn first-path [^array-search a]
  (let [^long target (:targetnode a)]
    (when (generic/best-known-distance a (:targetnode a))
      (let [^long source (:startnode a)
            ^longs spt   (:shortest a)]
        (loop [idx target
               acc '()]
          (if (== idx source) (cons source acc)
              (recur (aget spt idx)
                     (cons idx acc))))))))

(definline array-mincost-aug-path!! [g from to]
  `(first-path (array-flow-traverse!! ~g ~from ~to (java.util.PriorityQueue.))))

(defn ^long array-max-flow [^objects flows ^objects capacities ^longs p]
  (let [bound (alength p)]
    (loop [to 1
           flow flow/posinf]
      (if (== to bound) flow
          (let [l (aget p (unchecked-dec to))
                r (aget p to)]
            (recur (unchecked-inc to)
                   (min (if (< l r) (arr/deep-aget longs capacities l r)
                                    (arr/deep-aget longs flows r l))
                        flow)))))))

(defn ^long get-flow [^array-net a ^long from ^long to]
  (arr/deep-aget longs (.flows a) from to))

(defn ^array-net set-flow! [^array-net a ^long from ^long to ^long flow]
  (do (arr/deep-aset longs (.flows a) from to flow)
      a))

(defn ^long get-capacity [^array-net a ^long from ^long to]
  (arr/deep-aget longs (.capacities a) from to))

(defn ^array-net set-capacity! [^array-net a ^long from ^long to ^long cap]
  (do (arr/deep-aset longs (.capacities a) from to cap)
      a))

(defn ^array-net array-inc-flow! [^array-net a ^long from ^long to ^long flow]
  (let [flows      (.flows a)
        capacities (.capacities a)]
    (do (arr/deep-aset longs flows from to (+ (arr/deep-aget longs flows from to) flow))
        (arr/deep-aset longs capacities from to (- (arr/deep-aget longs capacities from to) flow))
        a)))

(defn ^array-net array-dec-flow! [^array-net a ^long from ^Long to ^long flow]
  (let [flows (.flows a)
        capacities (.capacities a)]
    (do (arr/deep-aset longs flows from to (- (arr/deep-aget longs flows from to) flow))
        (arr/deep-aset longs capacities from to (+ (arr/deep-aget longs capacities from to) flow))
        a)))

(defmacro do-edges [arr axp i j expr]
  (let [a (with-meta axp {:tag 'array-net})]
    `(let [~a ~arr
           bound# (.n ~a)]
       (loop [~i 0]
         (if (== ~i bound#) ~a
             (do (loop [~j 0]
                   (if (== ~i ~j) (recur (unchecked-inc ~j))
                       (if (== ~j bound#) nil
                           (do ~expr 
                               (recur (unchecked-inc ~j)))))
                   (recur (unchecked-inc ~i)))))))))

(defn ^array-net reset-flow! [^array-net a]
  (do-edges a arr i j
            (let [flow (get-flow arr i j)]
              (when (not (zero? flow))
                (array-dec-flow! arr i j flow)))))

(defn ^array-net zero-flow! [^array-net a]
  (do-edges a arr i j (do (set-flow! arr i j 0))))


(defn ^array-net disable-edge! [^array-net a ^long from ^long to]  
  (set-capacity! a from to neginf))

(defn ^array-net enable-edge! [^array-net a ^long from ^long to]  
  (set-capacity! a from to posinf))

(defn array-augment-flow [^array-net the-net p]
  (let [^longs xs (long-array p)
        ^objects flows (.flows the-net)
        ^objects capacities (.capacities the-net)
        flow (array-max-flow flows capacities xs)
        n (unchecked-dec (alength xs))]
    (do (loop [idx 0]
          (if (== idx n) the-net
              (let [from (aget xs ids)
                    to   (aget xs (unchecked-inc idx))]
                (if (< from to)
                  (do (array-inc-flow! the-net from to ^long flow)
                      (recur (unchecked-inc idx)))
                  (do (array-dec-flow! the-net to from ^long flow)
                      (recur (unchecked-inc idx)))))))
        the-net)))

(defn array-mincost-flow [^array-net the-net ^long from ^long to]
  (loop [acc the-net]
    (if-let [p (array-mincost-aug-path!! acc from to)]
      (recur (array-augment-flow acc p))
      acc)))

(defn augmentations [n ^array-net the-net ^long from ^long to]
  (let [res (java.util.ArrayList.)
        nm  (:nodemap the-net)]
    (loop [acc the-net]
      (if-let [p (array-mincost-aug-path!! acc from to)]
        (let [f (array-max-flow (:flows acc) (:capacities acc) (long-array p))
              _ (.add res [f (map (fn [n] [n (nm n)]) p)])]
          (if (> (count res) n)
              [res acc]
              (recur (array-augment-flow acc p))))
        [res acc]))))

(defn get-edge-infos! [^array-net the-net]
  (for [i (range (.n the-net))
        j (range (.n the-net))
        :when (and (not= i j) (> (get-flow the-net i j) 0))]
    (spork.cljgraph.flow.einfo. i j 
       (get-capacity the-net i j) (get-flow the-net i j) :increment)))

(defn get-labeled-edge-infos! [^array-net the-net]
  (let [nm (.nodemap the-net)]
    (for [i (range (.n the-net))
          j (range (.n the-net))
          :when (and (not= i j) (> (get-flow the-net i j) 0))]
      (let [from (nm i)
            to   (nm j)]
        (spork.cljgraph.flow.einfo. from to 
           (get-capacity the-net i j) (get-flow the-net i j) :increment)))))
         

      


