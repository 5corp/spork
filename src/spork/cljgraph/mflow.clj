;;A library for apply various types of network flow 
;;algorithms, as well as defining networks.  Canonical 
;;implementations of augmenting path algorithms, as well
;;as mincost flow algorithms are included.
;;Using mutable containers to represent the network.  
;;Since path augmentation is slowing us down, let's reduce 
;;the cost of updating flows.
(ns spork.cljgraph.mflow
  (:require [spork.cljgraph [core :as graph]
                            [search :as search]
                            [flow    :as flow]]
            [spork.data [searchstate :as searchstate]]
            [spork.protocols [core :as generic]]
            [spork.util [array :as arr]]))

(def sample 
  '{["55530LJ00" :filled]
    {:from "55530LJ00", :to :filled, :capacity 31.0, :flow 0},
    [RCAD "55530LJ00"]
    {:from RCAD, :to "55530LJ00", :capacity 9223372036854775807, :flow 0},
    [AC "5530LJ00"]
    {:from AC, :to "55530LJ00", :capacity 9223372036854775807, :flow 0},
    [RC RCAD-BIG] {:from RC, :to RCAD-BIG, :capacity 25000, :flow 0},
    [RC RCAD] {:from RC, :to RCAD, :capacity 25000, :flow 0},
    [Supply RC] {:from Supply, :to RC, :capacity 530000, :flow 0},
    [Supply AC] {:from Supply, :to AC, :capacity 450000, :flow 0},
    [Total Supply] {:from Total, :to Supply, :capacity 980000, :flow 0}})


(defn net->node-map [edges]
  (let [add-node (fn [m nd] (if (contains? m nd) m (assoc m nd (count m))))]
    (loop [acc {} 
           es     edges]
      (if (empty? es) acc
          (let [e (first es)]
            (recur (-> acc (add-node (:from e)) (add-node (:to e)))
                   (rest es)))))))
     
;;definterface gets us more into java, but unlike defprotocol, allows
;;us to define primitive types and return types on the args.  Note
;;that also unlike defprotocol, the interface spec does NOT have an
;;initial argument representing the argument.  Instead, it looks
;;identical to a java interface.  When we go to implement the
;;interface, as with deftype or defrecord, we have to add the initial
;;argument for the object to the args list of the interface.  Other
;;than that, it's identical to the interface spec.  Also note the
;;restriction on names in the java interface.  We cannot use clojurian
;;names, i.e. no '- in the symbol name.
(definterface IMFlow 
  (^long getFlow      [from to])
  (^long getCapacity  [from to])
  (incFlow            [from to ^long amt])
  (setFlow            [from to ^long x])
  (setCapacity        [from to ^long cap]))

(defrecord netinfo [nodes ^objects flows ^objects capacities]
  IMFlow
  (^long getFlow   [m from to]     
    (let [i (get nodes from)
          j (get nodes to)]
      (arr/deep-aget longs flows i j)))
  
  (setFlow   [m from to ^long x]
    (let [i (get nodes from)
          j (get nodes to)]
      (arr/deep-aset longs flows i j x)))
  
  (incFlow      [m from to ^long amt] 
    (let [i (get nodes from)
          j (get nodes to)]
    (do (arr/deep-aset longs flows i j (+ amt (arr/deep-aget longs flows i j))) 
        m)))  
  (^long getCapacity [m from to]
    (let [i (get nodes from)
          j (get nodes to)]
      (arr/deep-aget longs capacities i j)))  
  (setCapacity [m from to ^long cap]
    (let [i (get nodes from)
          j (get nodes to)]
      (arr/deep-aset longs capacities i j cap))))

(defn ^netinfo edges->netinfo [edges]
  (let [nm (net->node-map edges)
        flows      (arr/longs-2d (count nm) (count nm))
        capacities (arr/longs-2d (count nm) (count nm))]
    (doseq [e edges]
      (let [from (:from e)
            to   (:to   e)
            cap  (:capacity e)
            flow (:flow e)
            i (get nm from)
            j (get nm to)]
        (do (arr/deep-aset longs flows i j (long flow))
            (arr/deep-aset longs capacities i j (long cap)))))
    (->netinfo nm flows capacities)))
      
(def info (edges->netinfo (vals sample)))      
(def posinf Long/MAX_VALUE)

;;We define an operation to cast a persistent network into a mutable
;;network.  Basically, we store the network info in our netinfo
;;object, and define operations on that.  Things like apply-flow, and
;;other stateful functions should benefit greatly from smashing on a
;;mutable array, rather than smashing on 

(defn as-mutable-net [n]
  ;append netinfo to.  So that operations for flows and augmenting
  ;paths use netinfo for computing flows.  Keeps the original
  ;persistent data around.  Performance optimization only.
    ))

;;Flows and Augmenting Paths
;;==========================

;;This is different....
(definline update-edge*  
  [g from to flow cap]
  `(assoc ~g :flow-info                  
     (assoc 
         (get ~g :flow-info {})
       [~from ~to] (einfo. ~from ~to ~cap ~flow :increment))))

;;->edge-info is called a lot here.
(defn update-edge 
  ([g from to flow cap]
     (assoc g :flow-info                  
       (assoc 
         (get g :flow-info {})
           [from to] (einfo. from to cap flow :increment))))
  ([g from to m] 
    (assoc-in g [:flow-info [from to]] (merge (edge-info g from to) m))))

(defn current-capacity 
  ([info] (- (:capacity info) (:flow info)))
  ([g from to] (current-capacity (edge-info g from to))))

(defn set-capacity [g from to cap] (update-edge g from to {:capacity cap}))
(defn set-flow [g from to flow]    (update-edge g from to {:flow flow}))

;;Hold off on this...implementation may be faulty.
(defn swap-capacities [net l c r] net)
(defn forward? 
  ([g from to] (contains? (get (:sinks g) from) to))
  ([g info] (forward? g (:from info) (:to info))))

(defn inc-flow 
  ([g info flow]
    (update-edge* g (:from info) (:to info) 
                 (+ (:flow info) flow) (- (:capacity info) flow)))
  ([g from to flow] (inc-flow g (edge-info g from to) flow)))

(defn dec-flow 
  ([g info flow]
    (update-edge* g  (:from info) (:to info) 
                 (- (:flow info) flow) (+ (:capacity info) flow)))
  ([g from to flow] (dec-flow g (edge-info g from to) flow)))

(defn flows [g] 
  (for [[k v] (:flow-info g)] [k (select-keys v [:capacity :flow])]))

(defn active-flows [g] 
  (reduce (fn [acc [k info]] (if (> (:flow info) 0)
                               (assoc acc k (:flow info)) acc)) 
          {} (:flow-info g)))

(defn total-flow 
  ([g active-edges] 
    (->> active-edges 
      (filter (fn [[k v]] (graph/terminal-node? g (second k))))
      (map second)
      (reduce + 0)))
  ([g] (total-flow g (active-flows g))))

(defn flow-provider-type [g nd]
  (if (not (graph/island? g nd))
      (cond (graph/terminal-node? g nd) :sinks
            (empty? (graph/sinks g nd))  :source
            :else :trans)
      :island))

(defn flow-topology [g start-node]
  (group-by (partial flow-provider-type g)
            (graph/succs g start-node)))

;;refactored to eliminate reduce and destructuring.
(defn total-cost 
  ([g active-edges]
    (generic/loop-reduce 
     (fn [acc info]
       (let [flow (second info)
             from (first (first info))
             to   (second (first info))]
         (+ acc (* flow (graph/arc-weight g from to)))))
            0 active-edges))
  ([g] (total-cost g (active-flows g))))


;;Might disable this guy...

;;Disable this for mutable flows...
;;;add a capacitated arc to the graph
;; (defn conj-cap-arc [g from to w cap]
;;   (let [finfo (:flow-info g)]
;;     (-> (graph/conj-arc g from to w)
;;         (assoc :flow-info finfo)
;;         (update-edge from to 0 cap))))

;;Probable hotspot in at least one use case.  We add arcs to the
;;network repeatedly...calls to merge and reduce and destructuring 
;;will slow us down.


;;Disabled for mutable flows...
;;this is a hacked way to go
;;add multiple capacitated arcs to the network.
;; (defn conj-cap-arcs [g arcs]
;;   (let [finfo (:flow-info g)]
;;     (->> arcs 
;;         (reduce 
;;           (fn [[gr flows] [from to w cap]]  [(graph/conj-arc gr from to w) 
;;                                              (update-edge flows from to 0 cap)])
;;              [g {:flow-info finfo}])
;;         (apply merge))))

;;Rewrite, may be faster using netinfo.
;;bi-directional flow neighbors.  We allow all forward neighbors with untapped 
;;capacity, and allow any backward neighbors with flow.
(defn flow-neighbors 
  [g v _]
  (let [xs (atom (transient []))]
    (do (doseq [to (graph/sinks g v)]
          (when (> (:capacity (edge-info g v to)) 0) 
            (reset! xs (conj! @xs to) )))
        (doseq [from (graph/sources g v)]
          (when (> (:flow (edge-info g from v)) 0)
            (reset! xs (conj! @xs from))))
        (persistent! @xs))))

;;this is a special walk for helping us with greedy flows, where we don't 
;;try to find an augmenting flow.
(defn forward-only-flow-neighbors 
  [g v _]
  (let [info (partial edge-info g)
        capacity (fn [to] (:capacity (info v to)))]
    (filter (fn [to]   (> (capacity to) 0)) (graph/sinks g v))))

;;the flow-cost for g from to.  Forward arcs are positive cost.
;;Backward arcs are negative-cost.
(defn flow-weight [g from to]
  (if (forward? g from to) (graph/arc-weight g from to) ;forward arc
      (- (graph/arc-weight g to from))))

(defn flow-walk [g startnode endnode]
  (search/priority-walk g startnode :endnode endnode 
                        :weightf flow-weight :neighborf flow-neighbors))

(defn ford-fulkerson-walk [g startnode endnode]
  (search/depth-walk g startnode :endnode endnode :neighborf flow-neighbors))

(defn edmonds-karp-walk [g startnode endnode]
  (search/breadth-walk g startnode :endnode endnode :neighborf flow-neighbors))
(defn pushflow-walk [g startnode endnode]
  (search/priority-walk g startnode 
        :endnode endnode :neighborf forward-only-flow-neighbors))
(defn pushflow-aug-path [g from to]
  (first (graph/get-paths (pushflow-walk g from to))))
(defn maxflow-aug-path [g from to]
  (first (graph/get-paths (edmonds-karp-walk g from to))))

;;Changed from using flow-walk, due to overhead from function
;;invocation.  This guy gets called a lot.  Function overhead adds up.
;;Also, defwalk forms use merge internally...so runtime costs are
;;incurred in tight loops (i.e. lots of flow calcs).
(definline mincost-aug-path [g from to]
  `(first (graph/get-paths 
           (search/traverse ~g ~from ~to (searchstate/empty-PFS ~from)
                            :weightf flow-weight :neighborf flow-neighbors))))

;;This may be unnecessary...We only need to augment along active
;;flows.  Building edge-info is unnecessary, we just mutate the flow
;;along the path.

;;optimized?
;; (defn path->edge-info [g p]
;;   (loop [acc []
;;          xs  p]
;;     (if (nil? (next xs)) acc
;;         (let [from (first  xs)
;;               to   (second xs)]
;;           (recur (conj acc (if (forward? g from to)
;;                              (assoc (edge-info g from to) :dir :increment)
;;                              (assoc (edge-info g to from) :dir :decrement)))
;;                  (next xs))))))
      
;;Also needs optimizing.  We now use netinfo instead of infos...
;;find the maximum flow that the path can support 
(defn maximum-flow [g infos]
  (loop [info (first infos)
         xs   (rest infos)
         flow posinf]
    (let [new-flow (if (= :increment (:dir info))
                       (:capacity info)
                       (:flow info))
          next-flow (min flow new-flow)]
      (if (empty? xs) next-flow
          (recur (first xs) (rest xs) next-flow)))))

;;Apply flow should now be using netinfo.  Instead of edges, we just
;;walk the path in order and inc flow or dec flow depending on whether
;;the edges are forward or backward.  Should reduce allocation a lot.

;;apply an amount of flow along the path, dropping any nodes that 
;;become incapacitated.
(defn apply-flow [g edges flow]
  (generic/loop-reduce 
      (fn [gr info]
        (if (= :increment (:dir info))
          (inc-flow gr info flow)
          (dec-flow gr info flow)))
      g edges))

;;Replace edges with something else.  Given a path, we just traverse
;;the path and mutate the flows as necessary.  Should be simplified
;;now, no edge-info objects, no call to path->edge-info.
;;helper function to apply flow.
(defn augment-flow [g p]
  (let [edges (path->edge-info g p)]
    (apply-flow g edges (maximum-flow g edges))))


;;These guys are all the same.  The difference is, we inject a call to
;;create a mutable netinfo, using as-mutable, and smash on that with
;;our custom mutable operators.  The resulting graph is then converted
;;to a persistent flow structure.  So, wrap the libs from
;;cljgraph.flow.  

;;find the mincost flow, in graph, from -> to, where graph is a directed graph 
;;and contains a key :flow-info with compatible network flow information.
(defn mincost-flow 
  ([graph from to]
    (loop [g graph]
      (if-let [p (mincost-aug-path g from to)]
        (recur (augment-flow g p))
        (let [active (active-flows g)]
          {
           ;:cost (total-cost graph active)
           ;:flow (total-flow g active)
           :active active
           :net g}))))
  ([flow-info graph from to]
    (mincost-flow (assoc graph :flow-info flow-info) from to)))

(defn maxflow 
  ([graph from to]
    (loop [g graph]
      (if-let [p (maxflow-aug-path g from to)]
        (recur (augment-flow g p))
        (let [active (active-flows g)]
          {:flow (total-flow g active)
           :active active
           :net g}))))
  ([flow-info graph from to]
    (maxflow (assoc graph :flow-info flow-info) from to)))

(defn max-pushflow 
  ([graph from to]
    (loop [g graph]
      (if-let [p (pushflow-aug-path g from to)]
        (recur (augment-flow g p))
        (let [active (active-flows g)]
          {:flow (total-flow g active)
           :active active
           :net g}))))
  ([flow-info graph from to]
    (max-pushflow (assoc graph :flow-info flow-info) from to)))

;;testing 
(comment 
(def net-data 
 [[:s   :chi  0 300]
  [:s   :dc   0 300]
  [:dc  :hou  4 280]
  [:dc  :bos  6 350]
  [:chi :bos  6 200]
  [:chi :hou  7 200]
  [:hou :t    0 300]
  [:bos :t    0 300]])
(def the-net 
  (-> empty-network 
    (conj-cap-arcs net-data)))

)



        
        
        
            

  
  
  
