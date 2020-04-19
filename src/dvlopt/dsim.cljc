(ns dvlopt.dsim

  "Idiomatic, purely-functional discrete event simulation and more.
  
   See README first in order to make sense of all this."

  {:author "Adam Helinski"}

  (:require [dvlopt.dsim.util :as dsim.util]
            [dvlopt.void      :as void])
  #?(:clj (:import (clojure.lang ExceptionInfo
                                 PersistentQueue))))




;;;;;;;;;; API structure (searchable for easy navigation)
;;
;; @[datastruct]  Data structures
;; @[misc]        Miscellaneous functions
;; @[scale]       Scaling numerical values
;; @[ctx]         Generalities about contextes
;; @[events]      Adding, removing, and modifying events
;; @[timevecs]    Handling timevecs
;; @[jump]        Moving a context through time
;; @[wq]          Relative to the currently executed queue (aka. the "working queue")
;; @[op]          Operation handling
;; @[flows]       Creating and managing flows




;;;;;;;;;; MAYBEDO


;;  Hack persistent queues so that they can also act as a stack?
;;  
;;  The front is implemented as a seq, so prepending is efficient, but the seq is
;;  package private. Relying on non-public features is not recommended. On the other
;;  hand, it is fairly certain the implementation will stay like this.


;;  Parallelize by timevec?
;;
;;  By definition, all events for a given timevec are independent, meaning that they
;;  can be parallelized without a doubt if needed.




;;;;;;;;;; Gathering all declarations


(declare e-update
         op-std
         path
         wq-vary-meta)




;;;;;;;;;; @[datastruct]  Data structures


(def ^:private -rmap-empty

  ;; A bit weird, but using timevecs as keys will not work if the sorted map is not
  ;; initialized with at one (something to do with the comparator being used).

  (dissoc (sorted-map [0 0] nil)
          [0 0]))




(defn rmap

  ""

  ([]

   -rmap-empty)


  ([& kvs]

   (reduce (fn add [rmap [rvec v]]
             (assoc rmap
                    rvec
                    v))
           -rmap-empty
           (partition 2
                      kvs))))




(defn queue

  ""

  ([]

   #?(:clj  PersistentQueue/EMPTY
      :cljs cljs.core/PersistentQueue.EMPTY))


  ([& values]

   (into (queue)
         values)))




(defn queue?

  ""

  [x]

  (instance? #?(:clj  PersistentQueue
                :cljs cljs.core/PersistentQueue)
             x))




;;;;;;;;;; @[misc]  Miscellaneous functions


(defn millis->utime

  ;; TODO. Doc

  "Computes the number of steps needed for completing a transition in `millis` milliseconds for a phenomenon,
   such as the frame-rate, happening `hz` per second.
  
  
   Eg. Computing the number of frames needed in order to last 2000 milliseconds with a frame-rate of 60.

       (millis->n-steps 2000
                        60)
  
       => 120"

  [millis hz]

  (long (Math/round (double (* (/ hz
                                  1000)
                               millis)))))





;;;;;;;;;; @[scale]  Scaling numerical values


(defn- -minmax-denorm

  ;; Scale a percent value to an arbitrary range.

  [min-v interval v-norm]

  (double (+ (* v-norm
                interval)
             min-v)))




(defn scale
  
  ;; TODO. Obviously linear.

  "3 args : scales a `percent` value (between 0 and 1 inclusive) to a value between `scaled-a` and `scaled-b`.

   5 args : scales the `x` value between `a` and `b` to be between `scaled-a` and `scaled-b`.

  
   Eg. (scale 200
              100
              0.5)

       => 250


       (scale 200
              100
              2000
              1000
              2500)

       => 250"

  ([min-v interval percent]

   (-minmax-denorm min-v
                   interval
                   percent))

  ([scaled-min-v scaled-interval min-v interval v]

   (-minmax-denorm scaled-min-v
                   scaled-interval
                   (/ (- v
                        min-v)
                      interval))))




(defn minmax-norm

  "Min-max normalization, linearly scales `x` to fit between 0 and 1 inclusive.
  

   Eg. (min-max-norm 20
                     10
                     25)

       => 0.5"

  [min-v interval v]

  (double (/ (- v
                min-v)
             interval)))




;;;;;;;;; @[ctx]  Generalities about contextes


(def ctx

  "Empty context, waiting to be used for future endaveours."

  {::events -rmap-empty})




(defn e-path

  ;;

  ([]

   (list ::e-flat
         ::queue))


  ([timevec path]

   (cons ::events
         (cons timevec
               path))))




(defn f-path

  ;;

  ([]

   (f-path (path ctx)))


  ([path]

   (cons ::flows
         path)))




(defn empty-event?

  ""

  [event]

  (if (queue? event)
    (empty-event? (peek event))
    (nil? event)))




(defn flowing?

  "Is the given context or some part of it currently in transition?"

  ([ctx]

   (flowing? ctx
             nil))


  ([ctx path]

   (not (empty? (get-in ctx
                        (cons ::flows
                              path))))))



(defn next-ptime

  ""

  [ctx]

  (first (ffirst (::events ctx))))




(defn path

  ""

  [ctx]

  (::path (::e-flat ctx)))




(defn ptime

  ""

  [ctx]

  (or (::ptime (::e-flat ctx))
      (::ptime ctx)))




(defn reached?

  ""

  [ctx ptime-target]

  (>= (ptime ctx)
      ptime-target))




(defn scheduled?

  ""

  ([ctx]

   (not (empty? (get ctx
                     ::events))))


  ([ctx timevec]

   (not (empty? (get-in ctx
                        [::events
                         timevec])))))




(defn timevec

  ""

  [ctx]

  (::timevec (::e-flat ctx)))




;;;;;;;;;; @[events]  Adding, removing, and modifying events


(defn- -ex-ctx

  ;; TODO. Name.

  [ctx timevec path msg]

  (throw (ex-info msg
                  {::path    path
                   ::ctx     ctx
                   ::timevec timevec})))




(defn- -not-empty-event

  ;;

  [event]

  (when (empty-event? event)
    (throw (ex-info "Event is nil or empty"
                    {::event event})))
  event)




(defn e-assoc

  ""

  ([ctx event]

   (assoc-in ctx
             [::e-flat
              ::queue]
             (if (queue? event)
               event
               (queue event))))


  ([ctx timevec event]

   (e-assoc ctx
            timevec
            (path ctx)
            event))


  ([ctx timevec path event]

   (e-update ctx
             timevec
             path
             (fn check-e-flat [node]
               (if (map? node)
                 (-ex-ctx ctx
                          timevec
                          path
                          "Cannot assoc an event at a node")
                 event)))))




(defn e-conj

  ""

  ;; Clojure's `conj` can take several values, but this is messing with our arities.

  ([ctx event]

   (e-update ctx
             (fn -e-conj [q]
               (conj q
                     event))))


  ([ctx timevec event]

   (e-conj ctx
           timevec
           (path ctx)
           event))


  ([ctx timevec path event]

   (e-update ctx
             timevec
             path
             (fn -e-conj [node]
               (cond
                 (nil? node)   (queue event)
                 (fn? node)    (queue node
                                      event)
                 (queue? node) (conj node
                                     event)
                 :else         (-ex-ctx ctx
                                        timevec
                                        path
                                        "Can only `e-conj` to nil or an event"))))))




(defn e-dissoc
  
  ""

  ([ctx]

   (update ctx
           ::e-flat
           dissoc
           ::queue))


  ([ctx timevec path]

   (void/dissoc-in ctx
                   (e-path timevec
                           path))))




(defn e-into

  ""
  
  ([ctx events]
   
   (e-update ctx
             (fn -e-into [q]
               (into (vary-meta q
                                merge
                                (meta events))
                     events))))


  ([ctx timevec events]

   (e-into ctx
           timevec
           (path ctx)
           events))


  ([ctx timevec path events]

   (e-update ctx
             timevec
             path
             (fn -e-into [node]
               (cond
                 (nil? node)   (if (queue? events)
                                 events
                                 (into (with-meta (queue)
                                                  (meta events))
                                       events))
                 (fn? node)    (into (with-meta (queue node)
                                                (meta events))
                                     events)
                 (queue? node) (into (vary-meta node
                                                merge
                                                (meta events))
                                     events)
                 :else         (-ex-ctx ctx
                                        timevec
                                        path
                                        "Can only `e-into` to nil or an event"))))))




(defn e-get

  ""

  ([ctx]

   (get-in ctx
           [::e-flat
            ::queue]))


  ([ctx timevec path]

   (get-in ctx
           (e-path timevec
                   path))))





(defn e-isolate

  ""

  ([ctx]

   (e-update ctx
             (fn -e-isolate [q]
               (if (empty? q)
                 q
                 (queue q)))))


  ([ctx timevec]

   (e-isolate ctx
              timevec
              (path ctx)))


  ([ctx timevec path]

   (e-update ctx
             timevec
             path
             (fn -e-isole [event]
               (some-> event
                       queue)))))




(defn e-pop

  ""

  ([ctx]

   (update-in ctx
              [::e-flat
               ::queue]
              pop))


  ([ctx timevec]

   (e-pop ctx
          timevec
          (path ctx)))


  ([ctx timevec path]

   (let [e-path' (e-path timevec
                         path)
         event   (get-in ctx
                         e-path')]
     (cond
       (queue? event) (let [event-2 (pop event)]
                        (if (empty? event-2)
                          (void/dissoc-in ctx
                                          e-path')
                          (assoc-in ctx
                                    e-path'
                                    event-2)))
       (fn? event)    (void/dissoc-in ctx
                                      e-path')
       :else          ctx))))




(defn e-push

  ""

  ([ctx q]

   (e-update ctx
             (fn -e-push [q-old]
               (into (vary-meta q
                                merge
                                (meta q-old))
                     q-old))))
           

  ([ctx timevec q]

   (e-push ctx
           timevec
           (path ctx)
           q))


  ([ctx timevec path q]

   (e-update ctx
             timevec
             path
             (fn -e-push [node]
               (cond
                 (nil? node)   q
                 (fn? node)    (into (with-meta (queue node)
                                                (meta q))
                                     q)
                 (queue? node) (into (vary-meta q
                                                merge
                                                (meta node))
                                     node)
                 :else         (-ex-ctx ctx
                                        timevec
                                        path
                                        "Can only `e-push` to nil or an event"))))))




(defn e-update

  ""

  ([ctx f]

   (update-in ctx
              [::e-flat
               ::queue]
              (fn safe-f [wq]
                (when (nil? wq)
                  (throw (ex-info "No working queue at the moment"
                                  {::ctx ctx})))
                (f wq))))


  ([ctx timevec path f]

   (void/update-in ctx
                   (e-path timevec
                           path)
                   (comp -not-empty-event
                         f))))




;;;;;;;;;; @[timevecs]  Handling timevecs


(defn timevec+

  ""

  [timevec dtimevec]

  (if (empty? dtimevec)
    timevec
    (let [n-timevec  (count timevec)
          n-dtimevec (count dtimevec)
          [base
           [front
            rear]]   (if (<= n-timevec
                             n-dtimevec)
                       [timevec
                        (split-at n-timevec
                                 dtimevec)]
                       [dtimevec
                        (split-at n-dtimevec
                                  timevec)])]
      (when (neg? (first dtimevec))
        (throw (ex-info "Cannot add negative time interval to timevec"
                        {::dtimevec dtimevec
                         ::timevec  timevec})))
      (into (mapv +
                  base
                  front)
            rear))))




(defn wq-timevec+

  ""

  ([dtimevec]

   (fn ctx->timevec [ctx]
     (wq-timevec+ ctx
                  dtimevec)))


  ([ctx dtimevec]

   (timevec+ (timevec ctx)
             dtimevec)))




;;;;;;;;;; @[jump]  Moving a context through time


(defn- -fn-restore-q-outer

  ""

  [q-outer]

  (fn restore-q-outer [ctx]
    (assoc-in ctx
              [::e-flat
               ::queue]
              q-outer)))




(defn- -q-exec

  ;;

  ([e-handler ctx q]

   (-q-exec e-handler
            ctx
            q
            identity))


  ([e-handler ctx q after-q]

   (let [event  (peek q)
         q-2    (pop q)
         [ctx-2
          q-3]  (try
                  (if (queue? event)
                    [(-q-exec e-handler
                              ctx
                              event
                              (-fn-restore-q-outer q-2))
                     q-2]
                    (let [ctx-2 (e-handler (assoc-in ctx
                                                     [::e-flat
                                                      ::queue]
                                                     q-2)
                                           event)]
                      [ctx-2
                       (e-get ctx-2)]))
                  
                  (catch ExceptionInfo err
                    (let [err-data (ex-data err)]
                      (if-some [on-error (::on-error (meta q-2))]
                        (let [ctx-2 (e-handler (void/assoc {::ctx   ctx
                                                            ::error err}
                                                           ::ctx-inner (::ctx err-data))
                                               on-error)]
                          [ctx-2
                           (e-get ctx-2)])
                        (throw (if (contains? err-data
                                              ::ctx)
                                 err
                                 (ex-info (ex-message err)
                                          (assoc err-data
                                                 ::ctx
                                                 (e-dissoc ctx))
                                          (ex-cause err)))))))
                  (catch #?(:clj  Throwable
                            :cljs js/Error)
                         e
                    (if-some [on-error (::on-error (meta q-2))]
                      (let [ctx-2 (e-handler {:ctx   ctx
                                              :error e}
                                             e-handler)]
                        [ctx-2
                         (e-get ctx-2)])
                      (throw (ex-info "Throwing in the last computed context"
                                      {::ctx (e-dissoc ctx)}
                                      e)))))]
     (if (empty? q-3)
       (after-q ctx-2)
       (recur e-handler
              ctx-2
              q-3
              after-q)))))




(defn- -fn-u-exec

  ;;

  [path op]
  
  (fn u-exec [e-handler ctx]
    (let [ctx-2 (e-handler (update ctx
                                   ::e-flat
                                   merge
                                   {::path  path
                                    ::queue (queue)})
                           op)
          wq    (e-get ctx)]
      (if (empty? wq)
        ctx-2
        (-q-exec e-handler
                 ctx-2
                 wq)))))




(defn- -fn-q-exec

  ;;

  [path q]

  (fn q-exec [e-handler ctx]
    (-q-exec e-handler
             (assoc-in ctx
                       [::e-flat
                        ::path]
                       path)
             q)))




(defn- -e-fetch

  ;; TODO. Micro-optimize knowing we have a map in the first pass?

  ([node]

   (-e-fetch node
             []))


  ([node path]

   (cond
      (map? node)   (let [[k
                           node-next]  (first node)
                          [node-next-2
                           :as ret]    (-e-fetch node-next
                                                 (conj path
                                                       k))]
                      (assoc ret
                             0
                             (if (empty? node-next-2)
                               (dissoc node
                                       k)
                               (assoc node
                                      k
                                      node-next-2))))
      (queue? node) [nil
                     (-fn-q-exec path
                                 node)]
      :else         [nil
                     (-fn-u-exec path
                                 node)])))




(defn- -e-exec

  ;;

  [e-handler ctx timevec e-tree]

  (let [[popped-events
         leaf-handler] (-e-fetch e-tree)]
    (leaf-handler e-handler
                  (-> ctx
                      (update ::events
                              (fn update-popped [events]
                                (if (empty? popped-events)
                                  (dissoc events
                                          timevec)
                                  (assoc events
                                         timevec
                                         popped-events))))
                      (assoc ::e-flat
                             {::timevec timevec})))))




(defn- -validate-ctx-ptime

  ;;

  [ctx e-ptime]

  (when-some [ptime (::ptime ctx)]
    (when (<= e-ptime
              ptime)
      (throw (ex-info "Ptime of events must be > ctx ptime"
                      {::e-ptime e-ptime
                       ::ptime   ptime})))))




(defn- -throw-ptime-current

  ;;

  [e-ptime ptime]

  (throw (ex-info "Ptime of enqueued events is < current ptime"
                  {::e-ptime e-ptime
                   ::ptime   ptime})))




(defn- -e-next

  ;;

  [ctx]

  (first (::events ctx)))




(defn- -fn-before-ptime

  ;;

  [options]

  (let [before-ptime (or (::before-ptime options)
                         identity)]
    (fn before-ptime-2 [ctx ptime]
      (-> ctx
          (assoc ::ptime
                 ptime)
          before-ptime))))




(defn- -fn-after-ptime

  ;; MAYBEDO. Cleaning up some state for a ptime just as ::e-flat is cleaned up after execution?
  ;;          Would it be really useful to share some state between all events on a per ptime basis?

  [options]

  (let [after-ptime (or (::after-ptime options)
                        identity)]
    (fn after-ptime-2 [ctx]
      (after-ptime (dissoc ctx
                           ::e-flat)))))




(defn- -after-eager-jump

  ;;

  [ctx after-ptime]

  (-> ctx
      (dissoc ::e-flat)
      after-ptime))




(defn- -remove-e-handler

  ;;

  [ctx]

  (vary-meta ctx
             (fn clean-e-handler [mta]
               (not-empty (dissoc mta
                                  ::e-handler)))))




(defn- -e-handler-f

  ;;

  [ctx f]

  (f ctx))




(defn- -jump-until

  ;; A bit fugly, but straightforward, or is it...

  [ctx ptime e-timevec e-tree pred e-handler before-ptime after-ptime]

  (loop [ctx       ctx
         ptime     ptime
         e-timevec e-timevec
         e-tree    e-tree]
    (let [ctx-2                 (-e-exec e-handler
                                         ctx
                                         e-timevec
                                         e-tree)
          [[e-ptime-next
            :as e-timevec-next]
           e-tree-next
           :as e-next]          (-e-next ctx-2)]
    (if e-next 
      (cond
        (= e-ptime-next
           ptime)       (recur ctx-2
                               ptime
                               e-timevec-next
                               e-tree-next)
        (> e-ptime-next
           ptime)       (let [ctx-3 (after-ptime ctx-2)]
                          (or (pred ctx-3
                                    ptime
                                    e-ptime-next)
                              (recur (before-ptime ctx-3
                                                   e-ptime-next)
                                     e-ptime-next
                                     e-timevec-next
                                     e-tree-next)))
        :else             (throw (ex-info "Point in time of enqueued events is < current ptime"
                                          {::e-ptime e-ptime-next
                                           ::ptime   ptime})))
      (-after-eager-jump ctx-2
                         after-ptime)))))




(defn jump-until

  ""

  ([ctx pred]

   (jump-until ctx
               pred
               nil))


  ([ctx pred options]

   (let [[[ptime
           :as e-timevec]
          e-tree
          :as e-next]     (-e-next ctx)]
     (-validate-ctx-ptime ctx
                          ptime)
     (if e-next
       (or (pred ctx
                 nil
                 ptime)
           (let [e-handler    (or (::e-handler options)
                                  -e-handler-f)
                 before-ptime (-fn-before-ptime options)
                 after-ptime  (-fn-after-ptime options)]
             (-> ctx
                 (vary-meta assoc
                            ::e-handler
                            e-handler)
                 (before-ptime ptime)
                 (-jump-until ptime
                              e-timevec
                              e-tree
                              pred
                              e-handler
                              before-ptime
                              after-ptime)
                 -remove-e-handler)))
       ctx))))




(defn jump

  ""

  ([ctx]

   (jump ctx
         nil))


  ([ctx options]

   (jump-until ctx
               (fn single-ptime [ctx ptime-last _ptime-next]
                 (when ptime-last
                   ctx))
               options)))




(defn jump-to

  ""

  ([ctx ptime]

   (jump-to ctx
            ptime
            nil))


  ([ctx ptime options]

   (jump-until ctx
               (fn ptime-not-reached [ctx _ptime-last ptime-next]
                 (when (> ptime-next
                          ptime)
                   ctx))
               options)))




(defn jump-to-end

  ""

  ;; TODO. void

  ([ctx]

   (jump-to-end ctx
                nil))


  ([ctx options]

   (jump-until ctx
               (fn always [_ctx _ptime _ptime-next]
                 nil)
               options)))




(defn- -history

  ;;

  [ctx ptime e-timevec e-tree e-handler before-ptime after-ptime]

  (let [ctx-2 (-e-exec e-handler
                       ctx
                       e-timevec
                       e-tree)]
    (if-some [[[e-ptime-next
                :as e-timevec-next]
               e-tree-next]         (-e-next ctx-2)]
      (cond
        (= e-ptime-next
           ptime)       (recur ctx-2
                               ptime
                               e-timevec-next
                               e-tree-next
                               e-handler
                               before-ptime
                               after-ptime)
        (> e-ptime-next
           ptime)       (let [ctx-3 (after-ptime ctx-2)]
                          (cons (-remove-e-handler ctx-3)
                                (lazy-seq
                                  (-history (before-ptime ctx-3
                                                          e-ptime-next)
                                            e-ptime-next
                                            e-timevec-next
                                            e-tree-next
                                            e-handler
                                            before-ptime
                                            after-ptime))))
        :else           (-throw-ptime-current e-ptime-next
                                              ptime))
      (cons (after-ptime ctx-2)
            nil))))




(defn history

  ""

  ([ctx]

   (history ctx
            nil))


  ([ctx options]

   (lazy-seq
     (when-some [[[ptime
                  :as e-timevec]
                  e-tree]         (-e-next ctx)]
       (-validate-ctx-ptime ctx
                            ptime)
       (let [before-ptime (-fn-before-ptime options)
             after-ptime  (-fn-after-ptime options)
             e-handler    (or (::e-handler options)
                              -e-handler-f)]
         (-history (-> ctx
                       (vary-meta assoc
                                  ::e-handler
                                  e-handler)
                       (before-ptime ptime))
                   ptime
                   e-timevec
                   e-tree
                   e-handler
                   before-ptime
                   (fn after-ptime-2 [ctx]
                     (-after-eager-jump ctx
                                        after-ptime))))))))




;;;;;;;;;; @[wq]  Relative to the currently executed queue (aka. the "working queue")
;;
;;
;; Manipulating the working queue, creating events to do so, or quering data about it.
;;
;;
;; Arities are redundant but more user-friendly and API-consistent than partial application.
;; Let us not be too smart by imagining some evil macro.
;;


(defn wq-breaker

  ""

  ([pred?]

   (fn event [ctx]
     (wq-breaker ctx
                 pred?)))



  ([ctx pred?]

   (if (pred? ctx)
     ctx
     (e-dissoc ctx))))




(defn wq-capture

  ""

  ([]

   wq-capture)


  ([ctx]

   (wq-vary-meta ctx
                 (fn capture [mta]
                   (-> mta
                       (update ::captured
                               (fn save-captured [captured]
                                 (conj (or captured
                                           (list))
                                       (with-meta (e-get ctx)
                                                  nil))))
                       (update ::sreplay
                               (fn state-slot [sreplay]
                                 (if sreplay
                                   (conj sreplay
                                         nil)
                                   (list nil)))))))))




(defn wq-conj

  ""

  ([ctx->timevec event]

   (fn event [ctx]
     (wq-conj ctx
              ctx->timevec
              event)))


  ([ctx ctx->timevec event]

   (if (and (queue? event)
            (empty? event))
     ctx
     (e-conj ctx
             (ctx->timevec ctx)
             event))))




(defn wq-copy

  ""

  ([ctx->timevec]

   (fn event [ctx]
     (wq-copy ctx
               ctx->timevec)))


  ([ctx ctx->timevec]

   (wq-conj ctx
            ctx->timevec
            (e-get ctx))))




(defn wq-delay

  ""

  ([ctx->timevec]

   (fn event [ctx]
     (wq-delay ctx
                ctx->timevec)))


  ([ctx ctx->timevec]
  
   (e-dissoc (wq-copy ctx
                      ctx->timevec))))




(defn wq-do!

  ""

  ([side-effect]

   (fn event [ctx]
     (wq-do! ctx
             side-effect)))


  ([ctx side-effect]

   (side-effect)
   ctx))




(defn wq-exec

  ""

  ([q]

   (fn event [ctx]
     (wq-exec ctx
              q)))


  ([ctx q]

   (let [wq (e-get ctx)]
     (e-assoc ctx
              (if (empty? wq)
                q
                (queue wq
                       q))))))




(defn wq-meta

  ""

  [ctx]

  (meta (e-get ctx)))




(defn wq-mirror

  ""

  ;; TODO. Better name than event.

  ([event]

   (fn event-2 [ctx]
     (wq-mirror ctx
                event)))


  ([ctx event]

   (let [path' (path ctx)]
     (assoc-in ctx
               path'
               (event (get-in ctx
                              path')
                      (ptime ctx))))))




(defn wq-pred-repeat

  ""

  [_ctx n]

  (when (pos? n)
    (dec n)))




(defn- -replay-captured

  ;;

  [ctx]

  (let [mta (wq-meta ctx)]
    (if-some [q (peek (::captured mta))]
      (e-assoc ctx
               (with-meta q
                          mta))
      (throw (ex-info "There is nothing captured to replay"
                      {::ctx ctx})))))




(defn- -pop-stack

  ;;

  [hmap k]

  (if-some [stack (not-empty (pop (get hmap
                                       k)))]
    (assoc hmap
           k
           stack)
    (dissoc hmap
            k)))




(defn wq-replay

  ""

  ([pred?]

   (fn event [ctx]
     (wq-replay ctx
                pred?)))


  ([ctx pred?]

   (if (pred? ctx)
     (-replay-captured ctx)
     (wq-vary-meta ctx
                   (fn release-captured [mta]
                     (-pop-stack mta
                                 ::captured))))))




(defn wq-sreplay

  ""

  ([pred seed]

   (fn event [ctx]
     (wq-sreplay ctx
                 pred
                 seed)))


  ([ctx pred seed]

   (let [pred-state (first (::sreplay (wq-meta ctx)))]
     (if-let [pred-state-2 (pred ctx
                                 (or pred-state
                                     seed))]
       (-> ctx
           (wq-vary-meta (fn save-state [mta]
                           (update mta
                                   ::sreplay
                                   (fn update-stack [sreplay]
                                     (conj (pop sreplay)
                                           pred-state-2)))))
           -replay-captured)
       (wq-vary-meta ctx
                     (fn clean-state [mta]
                       (-> mta
                           (-pop-stack ::captured)
                           (-pop-stack ::sreplay))))))))




(defn wq-vary-meta

  ""

  ([f]

   (fn event [ctx]
     (wq-vary-meta ctx
                   f)))


  ([ctx f]

   (update-in ctx
              [::e-flat
               ::queue]
              vary-meta
              f)))




;;;;;;;;;; @[op]  Operation handling


(defn op-applier

  ""

  [k->f]

  (let [k->f-2 (merge k->f
                      op-std)]
    (fn e-handler
      
      ([k]

       (or (get k->f-2
                k)
           (throw (ex-info "Function not found for operation"
                           {::ctx  ctx
                            ::op-k k}))))

      ([ctx [k & args :as op]]

       (apply (e-handler k)
              ctx
              args)))))




(defn op-exec

  ""

  [ctx op]

  (if-some [e-handler (::e-handler (meta ctx))]
    (e-handler ctx
               op)
    (throw (ex-info "No operation handler has been provided"
                    {::ctx ctx}))))




(def op-std

  ""

  {::breaker     (fn event [ctx op-pred?]
                   (wq-breaker ctx
                               (fn pred? [ctx]
                                 (op-exec ctx
                                          op-pred?))))
   ::capture     wq-capture
   ::delay       (fn event [ctx op-ctx->timevec]
                   (wq-delay ctx
                             (fn ctx->timevec [ctx]
                               (op-exec ctx
                                        op-ctx->timevec))))
   ::do!         (fn event [ctx op-side-effect]
                   (wq-do! ctx
                           (fn side-effect-2 [ctx]
                             (op-exec ctx
                                      op-side-effect))))
   ::exec        wq-exec
   ::mirror      (fn event [ctx op-mirror]
                   (wq-mirror ctx
                              (fn mirror [data ptime]
                                (op-exec data
                                         (conj op-mirror
                                               ptime)))))
   ::pred-repeat wq-pred-repeat
   ::replay      (fn event [ctx op-pred?]
                   (wq-replay ctx
                              (fn pred? [ctx]
                                (op-exec ctx
                                         op-pred?))))
   ::sreplay     (fn event [ctx op-pred seed]
                   (wq-sreplay ctx
                               (fn pred [ctx state]
                                 (op-exec ctx
                                          (conj op-pred
                                                state)))
                               seed))
   ::timevec+    wq-timevec+})




;;;;;;;;;; @[flows]  Creating and managing flows


(def rank-flows

  ""

  (long 1e9))




(defn f-end

  ""

  [ctx]

  (let [flow-path (f-path (path ctx))
        flow-leaf (get-in ctx
                          flow-path)
        ctx-2     (void/dissoc-in ctx
                                  flow-path)]
    (if-some [q (not-empty (::queue flow-leaf))]
      (-q-exec (::e-handler (meta ctx-2))
               (assoc-in ctx-2
                         [::e-flat
                          ::timevec]
                         (assoc (::timevec-init flow-leaf)
                                0
                                (first (timevec ctx-2))))
               q)
      ctx-2)))




(defn- -f-sample*

  ;; TODO. void

  [ctx ptime path node]

  (if-some [flow (::flow node)]
    (flow (assoc ctx
                 ::e-flat
                 {::path    path
                  ::timevec (assoc (::timevec-init node)
                                   0
                                   ptime)}))
    (reduce-kv (fn deeper [ctx-2 k node-next]
                 (-f-sample* ctx
                             ptime
                             (conj path
                                   k)
                             node-next))
               ctx
               node)))




(defn f-sample*

  ""

  ([ctx]

   (f-sample* ctx
              (path ctx)))


  ([ctx path]

   (-f-sample* (e-assoc ctx
                        (queue))
               (first (timevec ctx))
               path
               (get-in ctx
                       (f-path path)))))




(defn f-sample

  ""

  ;; TODO. User provided ranking.

  ([]

   (fn event [ctx]
     (f-sample ctx)))


  ([ctx]

   (f-sample ctx
             (timevec ctx)))


  ([ctx timevec]

   (f-sample ctx
             timevec
             (path ctx)))


  ([ctx timevec path]

   (update-in ctx
              [::events
               (into [(first timevec)
                      rank-flows]
                     (rest timevec))]
              dsim.util/assoc-shortest
              path
              f-sample*)))




(defn- -f-assoc

  ;;

  ([ctx flow]

   (-f-assoc ctx
             flow
             nil))


  ([ctx flow hmap]

   (-> ctx
       (assoc-in (f-path (path ctx))
                 (merge hmap
                        {::flow         flow
                         ::timevec-init (timevec ctx)
                         ::queue        (e-get ctx)}))
       e-dissoc)))




(defn f-infinite 

  ""

  ([flow]

   (fn event [ctx]
     (f-infinite ctx
                 flow)))


  ([ctx flow]

   (-> ctx
       (-f-assoc flow)
       f-sample)))




(defn- -f-finite

  ;; Todo. void, assoc-some

  [ctx after-sample duration flow]

  (let [ptime       (::ptime ctx)
        ptime-end   (+ ptime
                       duration)
        norm-ptime  (partial minmax-norm
                             ptime
                             duration)]
    (-> ctx
        (-f-assoc (fn norm-flow [ctx]
                    (let [e-ptime (norm-ptime (::ptime ctx))
                          ctx-2   (flow (assoc-in ctx
                                                  [::e-flat
                                                   ::ptime]
                                                  e-ptime))]
                      (if (>= e-ptime
                              1)
                        (f-end (update ctx-2
                                       ::e-flat
                                       dissoc
                                       ::ptime))
                        (after-sample ctx-2
                                      ptime-end)))))
        f-sample 
        (f-sample (update (timevec ctx)
                          0
                          +
                          duration)))))




(defn f-finite

  ""

  ([duration flow]

   (fn event [ctx]
     (f-finite ctx
               duration
               flow)))


  ([ctx flow duration]

   (-f-finite ctx
              identity
              duration
              flow)))




(defn f-sampled

  ""

  ([ctx->timevec duration flow]

   (fn event [ctx]
     (f-sampled ctx
                ctx->timevec
                duration
                flow)))


  ([ctx ctx->timevec duration flow]

   (-f-finite ctx
              (fn schedule-sampling [ctx ptime-end]
                (let [timevec-sample (ctx->timevec ctx)]
                  (if (and timevec-sample
                           (< (first timevec-sample)
                              ptime-end))
                    (f-sample ctx
                                    timevec-sample)
                    ctx)))
              duration
              flow)))