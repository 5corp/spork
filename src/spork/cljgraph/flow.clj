;;A library for apply various types of network flow 
;;algorithms, as well as defining networks.  Canonical 
;;implementations of augmenting path algorithms, as well
;;as mincost flow algorithms are included.
(ns spork.cljgraph.flow
  (:require [spork.cljgraph [core :as graph]
                            [search :as search]]))

(def posinf Long/MAX_VALUE)

;;Flows and Augmenting Paths
;;==========================

;;Network Flow data is stored as extra data in a 
;;digraph.  The API will be generalized behind a 
;;protocol, but for now this suffices, and it 
;;works on the existing digraph structure.

;A simple set of helper functions that let us embed flow data
;;in a spork.data.digraph record.

(def empty-network (assoc graph/empty-graph :flow-info {}))
(defn ->edge-info 
  [from to & {:keys [capacity flow] :or {capacity posinf flow 0}}]
  {:from from :to to :capacity capacity :flow flow})

(defn edge-info [g from to]
  (get-in g [:flow-info [from to]] (->edge-info from to)))

(defn update-edge 
  ([g from to flow cap]
    (assoc-in g [:flow-info [from to]] 
              (->edge-info from to :capacity cap :flow flow)))
  ([g from to m] 
    (assoc-in g [:flow-info [from to]] (merge (edge-info g from to) m))))

(defn current-capacity 
  ([info] (- (:capacity info) (:flow info)))
  ([g from to] (current-capacity (edge-info g from to))))

(defn set-capacity [g from to cap] (update-edge g from to {:capacity cap}))
(defn set-flow [g from to flow] (update-edge g from to {:flow flow}))

;;Hold off on this...implementation may be faulty.
(defn swap-capacities [net l c r] net)
(defn forward? 
  ([g from to] (contains? (get (:sinks g) from) to))
  ([g info] (forward? g (:from info) (:to info))))

(defn inc-flow 
  ([g info flow]
    (update-edge g (:from info) (:to info) 
                 (+ (:flow info) flow) (- (:capacity info) flow)))
  ([g from to flow] (inc-flow g (edge-info g from to) flow)))

(defn dec-flow 
  ([g info flow]
    (update-edge g (:from info) (:to info) 
                 (- (:flow info) flow) (+ (:capacity info) flow)))
  ([g from to flow] (dec-flow g (edge-info g from to) flow)))

(defn flows [g] 
  (for [[k v] (:flow-info g)] [k (select-keys v [:capacity :flow])]))
(defn active-flows [g] 
  (reduce (fn [acc [k info]](if (> (:flow info) 0)
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

(defn total-cost 
  ([g active-edges]
    (reduce (fn [acc [[from to] flow]]
              (+ acc (* flow (graph/arc-weight g from to))))
            0 active-edges))
  ([g] (total-cost g (active-flows g))))

;add a capacitated arc to the graph
(defn conj-cap-arc [g from to w cap]
  (let [finfo (:flow-info g)]
    (-> (graph/conj-arc g from to w)
        (assoc :flow-info finfo)
        (update-edge from to 0 cap))))
;;this is a hacked way to go
;;add multiple capacitated arcs to the network.
(defn conj-cap-arcs [g arcs]
  (let [finfo (:flow-info g)]
    (->> arcs 
        (reduce 
          (fn [[gr flows] [from to w cap]]  [(graph/conj-arc gr from to w) 
                                             (update-edge flows from to 0 cap)])
             [g {:flow-info finfo}])
        (apply merge))))

;;bi-directional flow neighbors.  We allow all forward neighbors with untapped 
;;capacity, and allow any backward neighbors with flow.
(defn flow-neighbors 
  [g v & args]
  (let [info (partial edge-info g)
        capacity (fn [to]   (:capacity (info v to)))
        flow     (fn [from] (:flow (info from v)))]
    (concat 
      (filter (fn [to]   (> (capacity to) 0)) (graph/sinks g v)) ;forward
      (filter (fn [from] (> (flow from)   0)) (graph/sources g v)))))
;;this is a special walk for helping us with greedy flows, where we don't 
;;try to find an augmenting flow.
(defn forward-only-flow-neighbors 
  [g v & args]
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
(defn mincost-aug-path [g from to]
  (first (graph/get-paths (flow-walk g from to))))
;convert a path into a list of edge-info 
(defn path->edge-info [g p]
  (map (fn [fromto]
         (let [from (first fromto)
               to (second fromto)]
           (if (forward? g from to)
             (assoc (edge-info g from to) :dir :increment)
             (assoc (edge-info g to from) :dir :decrement))))
       (partition 2 1 p)))
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
;;apply an amount of flow along the paht, dropping and nodes that 
;;become incapacitated.
(defn apply-flow [g edges flow]
  (reduce (fn [gr info]
            (if (= :increment (:dir info))
                (inc-flow gr info flow)
                (dec-flow gr info flow)))
          g edges))
;;helper function to apply flow.
(defn augment-flow [g p]
  (let [edges (path->edge-info g p)]
    (apply-flow g edges (maximum-flow g edges))))

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

