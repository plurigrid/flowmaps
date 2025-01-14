(ns flowmaps.core
  (:refer-clojure :exclude [abs update-vals update-keys])
  (:require [clojure.set :as cset]
            [flowmaps.utility :as ut]
            [flowmaps.web :as web]
            [flowmaps.db :as db]
            [flowmaps.rest :as rest]
            [debux.core :as dx]
            [clojure.walk :as walk]
            [clojure.edn :as edn]
            [clojure.string :as cstr]
            [websocket-layer.core :as wl] ;; temp until refactor
            [clojure.spec.alpha :as s]
            [chime.core :as chime]
            [clojure.spec.test.alpha :as stest]
            [flowmaps.examples.simple-flows :as fex]
            [clojure.core.async :as async])
  ;(:import [java.time LocalTime Duration Instant ZonedDateTime ZoneId Period DayOfWeek])
  (:import [java.lang.management ManagementFactory]
           java.time.Instant
           java.time.LocalTime
           java.time.LocalDate
           [java.time LocalTime Duration Instant ZonedDateTime ZoneId Period DayOfWeek]
           [java.text SimpleDateFormat]
           [java.util Date TimeZone Calendar]
           java.time.format.DateTimeFormatter))

(defmacro flow> [& forms]
  (let [step-count (atom 0)
        labels (doall (map (fn [_]
                             (do (swap! step-count inc)
                                 (keyword (str "step" @step-count))))
                           forms))
        conns (vec (map vector labels (rest labels)))
        components (vec (mapcat vector labels forms))]
    `{:components ~(apply hash-map components)
      :connections ~conns}))

;; - input ex
;; (flow> 10 + #(* 2 %) - #(+ 10 %) (fn [x] (str x "!")))

;; - output ex
;; {:components {:step1 10, 
;;               :step2 #<Fn@1e5c7bba clojure.core/_PLUS_>, 
;;               :step3 #<Fn@3c2e6df9 flowmaps.core/eval28467[fn]>, 
;;               :step4 #<Fn@3bed2205 clojure.core/_>,
;;               :step5 #<Fn@4386bdf3 flowmaps.core/eval28467[fn]>, 
;;               :step6 #<Fn@15e5fd09 flowmaps.core/eval28467[fn]>},
;;  :connections [[:step1 :step2] 
;;                [:step2 :step3] 
;;                [:step3 :step4] 
;;                [:step4 :step5] 
;;                [:step5 :step6]]}

;; some spec stuff

(s/def ::fn? (s/and ifn? #(not (map? %))))

(s/def ::components (s/or :function ::fn?
                          :map-with-function (s/and map? (s/keys :req-un [::fn?]))
                          :static-value #(and (not (fn? %)) (not (map? (:fn %))))))

(s/def ::connection-pair (s/and vector? (s/coll-of (s/or :keyword keyword? :any any?) :count 2)))

(s/def ::connections (s/and vector? (s/and (s/coll-of ::connection-pair) (complement empty?))))

(s/def ::network-map (s/keys :req-un [::connections ::components]))

(s/def ::opts-map (s/keys :opt-un [::debug? ::debux?]))

(s/def ::done-ch #(or (ut/chan? %) (and (seqable? %) (ut/chan? (first %))) (instance? clojure.lang.Atom %)))

(s/def ::subflow-map map?)

;; (s/fdef flow
;;   :args (s/cat :network-map ::network-map
;;                :opts-map (s/? ::opts-map)
;;                :done-ch (s/? ::done-ch)
;;                :subflow-map (s/? ::subflow-map)))

;; simple spec tests

;; (def sample-flow {:components {:go :go
;;                                :extract (fn [_] "hello")
;;                                :some-component {:fn (fn [_] "world")}}
;;                   :connections [[:go :extract]]})

;; (def bad-sample-flow {:components {:go :go
;;                                    :mmap {:map 45 :test 45}
;;                                    :extract (fn [_] "hello")
;;                                    :keye :keys
;;                                    "test" 34
;;                                    :some-component {:fn (fn [_] "world")}}
;;                   :connections [[:go :extract] 123 ["test" "test"]]})

;; (ut/ppln (into {} (for [[e b] [[sample-flow :sample-flow]
;;                                [bad-sample-flow :bad-sample-flow]
;;                                [fex/openai-calls ::fex/openai-calls]
;;                                [fex/my-network ::fex/my-network]
;;                                [fex/my-network-input ::fex/my-network-input]
;;                                [fex/odd-even ::fex/odd-even]
;;                                [fex/looping-net ::fex/looping-net]
;;                                [fex/ecommerce-flow ::fex/ecommerce-flow]
;;                                [fex/color-art-flow ::fex/color-art-flow]
;;                                [fex/ecosystem-flow ::fex/ecosystem-flow]
;;                                [fex/etl-flow ::fex/etl-flow]
;;                                [(flow> 10 (fn [x] (* 2 x)) (fn [x] (+ 1 x)) #(+ 10 %)) :macro-flow]]]
;;                     {(str b) (s/valid? ::network-map e)})))


(defn find-keys-with-path [paths-map path-segment]
  (let [segment-length (count path-segment)]
    (reduce
     (fn [acc [k v]]
       (if (some (fn [path]
                   (some #(= path-segment (subvec path % (+ % segment-length)))
                         (range 0 (+ 1 (- (count path) segment-length))))) v)
         (conj acc k)
         acc)) []
     paths-map)))

(defn build-all-paths [node connections visited]
  (let [next-nodes (map second (filter #(= (first %) node) connections))]
    (if (node (set visited))
      [[[node :loop-to] (first visited)]]
      (if (empty? next-nodes)
        [[node]]
        (for [next next-nodes
              path (build-all-paths next connections (cons node visited))]
          (cons node path))))))

(defn build-paths-from-all-nodes [connections]
  (mapcat #(build-all-paths % connections []) (set (map first connections))))

(defn path-map [connections]
  (into {} (for [v (build-paths-from-all-nodes connections)]
             {(hash v) (list (vec
                              ;(remove #{:loop-to} (flatten v)) ;; take out loop indicators
                              (flatten v)))})))

(defn- end-chain-msg [flow-id fp ff result the-path debug?] ;; TODO better calc of pathfinding to derive natural end points w/o :done blocks
  (let [fp-vals (vec (for [f the-path] [f (get-in @db/results-atom [flow-id f])]))
        res-paths (distinct (get @db/resolved-paths flow-id))
        chains (count res-paths)
        last-in-chain? (some #(= the-path %) res-paths)
        chains-run (+ (count (distinct @db/chains-completed)) 1)
        all-chains-done? (= chains chains-run)]
    (when debug?
      (ut/ppln [the-path :chain-done! ff :done-hop-or-empty  :res-paths res-paths]))

    (when last-in-chain?

      (do
        (when debug?
          (ut/ppln [chains-run :chain-done :done-hop ff :steps (count fp-vals) :final-result result
                    :value-path fp-vals chains-run :of chains :flow-chains-completed]))
        (swap! db/chains-completed conj the-path))

      (when (and all-chains-done? last-in-chain?)
        (when debug?
          (ut/ppln [:all-chains-done! :resolved-paths (vec (distinct (get @db/resolved-paths flow-id)))
                    ff :fp fp :results-atom (get @db/results-atom flow-id)]))))))

(defn close-channels! [flow-id]
  (doseq [[k c] (get @db/channels-atom flow-id)]
    (try #_{:clj-kondo/ignore [:redundant-do]}
     (do ;(ut/ppln [:closing-channel k c])
         (try 
           (when (not (nil? c))
             (do (swap! db/channels-atom ut/dissoc-in [flow-id k])
                 (async/close! c)))
           (catch Throwable e (do (swap! db/channels-atom assoc-in [flow-id k] c)
                                  (ut/ppln [:error-closing-channel-inner e k c])))) ;; close channel
         ) ;; remove from "live" channel atom
         (catch Throwable e 
           (do (swap! db/channels-atom assoc-in [flow-id k] c) ;; if failed, keep key val for now
             (ut/ppln [:error-closing-channel-outer e k c]))))))

(defn close-channel! [flow-id channel-id]
  ;(close-channels! [channel-id])
  (let [ch (get-in @db/channels-atom [flow-id channel-id])]
    ;(ut/ppln [:closing-channel flow-id channel-id])
    (try
      (when (not (nil? ch))
        (do (swap! db/channels-atom ut/dissoc-in [flow-id channel-id])
            (async/close! ch)))
      (catch Throwable e (ut/ppln [:error-closing-channel-inner e flow-id channel-id]))) ;; close channel
    (swap! db/channels-atom ut/dissoc-in [flow-id channel-id])))

(declare flow) ;; so we can call it in (process ..) even though it's still undefined at this point

(defn- process [flow-id connections input-chs output-chs ff fp block-function opts-map done-ch]
  (let [web-push? (true? @web/websocket?)
        {:keys [debug? debux? close-on-done?]} opts-map
        pp (if debug? ut/ppln ut/pplno) ;; TODO, grody
        channel-results (atom (into {} (map #(vector % nil) input-chs)))]
    (pp ["Starting processing " ff " with input channels: " input-chs " and output channels: " output-chs])
    (try
      (async/go-loop [pending-chs input-chs]
        (when (seq pending-chs)
          (let [wstart (System/currentTimeMillis) ;; start on channel (for waits)
                [data ch] (async/alts! pending-chs)
                data-from (get data :sender)
                data-val (get data :value :done)
                next-hops (map second (filter #(= ff (first %)) connections))
              ;is-done-next? (or (= next-hops '(:done)) (empty? next-hops))
                triples [data-from ff (first next-hops)]
                t2 triples ; (vec (remove #(= % :done) (remove nil? triples)))
                poss-path-keys (find-keys-with-path fp t2)
                is-accumulating-ports? (true? (get data-val :port-in?))
                start (System/currentTimeMillis) ;; non wait
                the-path (try
                           (apply max-key count (apply concat (vals (select-keys fp poss-path-keys))))
                           (catch Exception _ [:error-path-issue!]))
                valid-output-channels? (or (ut/chan? done-ch) ;; single output channel
                                           (try               ;; or several
                                             (ut/chan? (first done-ch))
                                             (catch Exception _ false)))
                done-channel-push? (and (= ff :done)
                                        valid-output-channels?)
                pre-print (get block-function :pre-print)
                post-print (get block-function :post-print)]



            (when (= ff :done)  ;; process any :done stuff, debug or not. ends this flow-chain-path basically
              (let [done-channel? valid-output-channels? ;(ut/chan? done-ch)
                    multi-ch? (seqable? done-ch)
                    done-atom? (instance? clojure.lang.Atom done-ch)
                    channels (+ (count (keys (get @db/channels-atom flow-id)))
                                (count (keys (get @db/condi-channels-atom flow-id))))]
                (cond done-atom? (reset! done-ch data-val)
                      (and done-channel? multi-ch?) (doseq [d done-ch] (async/>! d data-val))
                      done-channel? (async/>! done-ch data-val)
                      :else nil)
                (when close-on-done?
                  (close-channels! flow-id)) ;; kill all channels for this flow. we done.
              ;(println (str data-val))
                (pp [:done-block-reached (cond done-atom? [:sending-to-atom done-ch]
                                               done-channel? [:sending-to-channel done-ch]
                                               :else [:ending-flow-chain (if debug?
                                                                           [:debug-keeping channels :channels-open]
                                                                           [:closing channels :channels])])])))

            (when is-accumulating-ports?
              (swap! db/port-accumulator assoc ff (merge (get @db/port-accumulator ff {}) data-val))) ;; ff is the TO...

            (swap! channel-results assoc ch data-val)
            (swap! db/channel-history update flow-id conj (let [v (if (and (map? data-val) (get data-val :port-in?))
                                                                    (first (vals (dissoc data-val :port-in?)))
                                                                    data-val)]
                                                            {:path the-path ;; PRE RECUR HISTORY PUSH.
                                                             :type :channel
                                                             :channel [data-from ff]
                                                             :dest ff
                                                             :start start
                                                             :end (System/currentTimeMillis)
                                                             :value (ut/limited v flow-id)
                                                             :data-type (ut/data-typer v)}))

            (when (and (or (nil? data-val) (= data-val :done))
                       close-on-done?)

              (let [chk (ut/keypaths (get @db/channels-atom flow-id))
                    chkp (for [c chk] {(get-in @db/channels-atom [flow-id c]) c})
                    chp (get chkp ch)]
              ;(ut/ppln [:CLOSE [data-from ff chk chkp chp]])
                (close-channel! flow-id chp)))

            (if (or (and (not (ut/namespaced? ff))
                         (not (ut/namespaced? data-from)))
                    (not (some nil? (vals @channel-results))))
            ; ^^ check if we've received a value from every channel but only if multiple ins. otherwise default behavior
              (if (or (get data-val :error)
                    ;(and (nil? data-val) close-on-done?)
                      (= data-val :done)) ;; receiving :done/error keyword as a value is a poison pill to kill pathway
                (do
                ;; (when close-on-done? (do (ut/ppln [:CLOSE [data-from ff]])
                ;;                            (close-channel! [data-from ff])))
                  (pp [:chain-done! ff :done-signal])
                  (end-chain-msg flow-id fp ff nil the-path debug?))

                (let [block-fn-raw? (fn? block-function)
                      is-subflow? (s/valid? ::network-map block-function)
                      block-is-port? (and (not block-fn-raw?)
                                          (get block-function :fn)
                                          (ut/namespaced? ff))
                      inputs (get block-function :inputs [])
                      resolved-inputs (walk/postwalk-replace (get @db/port-accumulator ff) inputs)
                      fully-resolved? (= 0 (count (cset/intersection (set resolved-inputs) (set inputs))))
                      pre-in (cond (and fully-resolved? (not is-subflow?) ;; subflows namespaced keys are not multi-inputs, but overrides
                                        is-accumulating-ports?) resolved-inputs
                                   :else data-val)
                      _ (when pre-print (ut/ppln [flow-id ff (str (java.time.LocalDateTime/now)) :pre
                                                  (try (if (and fully-resolved? is-accumulating-ports?) ;; TODO
                                                         (apply pre-print pre-in) ;; resolved inputs
                                                         (pre-print pre-in))
                                                       (catch Exception e [:pre-print-error (str e)]))]))
                      pre-when? (try (if (and fully-resolved? is-accumulating-ports?)
                                       (apply (get block-function :pre-when? (fn [_] true)) pre-in) ;; resolved inputs
                                       ((get block-function :pre-when? (fn [_] true)) pre-in))
                                     (catch Exception _ true))   ;; hmm, maybe false instead?
                    ;;_ (ut/ppln [:port-accumulator ff fully-resolved? data-val (get @db/port-accumulator ff)])
                      subflow-overrides (when is-subflow? (into {}
                                                                (for [[k v] (dissoc (get @db/port-accumulator ff) ;data-val 
                                                                                    :port-in?)]
                                                                  {(keyword (last (cstr/split (str (ut/unkeyword k)) #"/"))) v})))
                      expression (delay (try
                                          (cond (not pre-when?) [:failed-pre-check :w-input pre-in] ;; failed pre-when input check - send error message
                                                (and fully-resolved? is-subflow?) ;(and is-subflow? fully-resolved?)
                                                (let [a (atom nil)] ;; use temp atom to hold subflow results and treat like regular fn
                                                  (flow block-function {:flow-id (str flow-id "/" (ut/unkeyword ff))
                                                                        :debug? debug?} a ;output-chs
                                                        subflow-overrides)
                                                  (while (nil? @a)
                                                    (Thread/sleep 100)) @a)
                                                (and fully-resolved? is-accumulating-ports?) (apply (get block-function :fn) resolved-inputs)
                                                block-fn-raw? (block-function data-val)
                                                block-is-port? {:port-in? true ff data-val} ;; just accumulating here?
                                                :else ((get block-function :fn) data-val))
                                          (catch Exception e
                                            (do (ut/ppln [:chain-dead! :exec-error-in ff :processing data-val
                                                          :from data-from :block-fns block-function :error e]) ; :done
                                                {:error (str e)}))))
                      expression-dbgn (delay (try ;; testing - to be refactored away shortly w macros
                                               (cond (not pre-when?) [:failed-pre-check :w-input pre-in] ;; failed pre-when input check
                                                     is-subflow? (let [a (atom nil)] ;; use temp atom to hold subflow results and treat like regular fn
                                                                   (flow block-function {:flow-id (str flow-id "/" (ut/unkeyword ff))
                                                                                         :debug? debug?} a ;output-chs
                                                                         subflow-overrides)
                                                                   (while (nil? @a)
                                                                     (Thread/sleep 100)) @a)
                                                     (and fully-resolved?
                                                          is-accumulating-ports?) (dx/dbgt_ (apply (get block-function :fn) resolved-inputs))
                                                     block-fn-raw? (dx/dbgt_ (block-function data-val))
                                                     block-is-port? (dx/dbgt_ {:port-in? true ff data-val}) ;; just accumulating here?
                                                     :else (dx/dbgt_ ((get block-function :fn) data-val)))
                                               (catch Exception e
                                                 (do (ut/ppln [:chain-dead! :exec-error-in ff :processing data-val
                                                               :from data-from :block-fns block-function :error e]) ; :done
                                                     {:error (str e)}))))
                      {:keys [result fn-start fn-end elapsed-ms dbgn-output]}
                      (merge
                       (ut/timed-exec @expression)
                       (if debux? {:dbgn-output (with-out-str @expression-dbgn)} {}))
                    ;; result (try
                    ;;          (cond (and fully-resolved?
                    ;;                     is-accumulating-ports?) (apply (get block-function :fn) resolved-inputs)
                    ;;                block-fn-raw? (block-function data-val)
                    ;;                block-is-port? {:port-in? true ff data-val} ;; just accumulating here?
                    ;;                :else ((get block-function :fn) data-val))
                    ;;          (catch Exception e
                    ;;            (do (ut/ppln [:chain-dead! :exec-error-in ff :processing data-val
                    ;;                          :from data-from :block-fns block-function :error e])
                    ;;                :done
                    ;;                ;{:error (str e)}
                    ;;                ))) ;; should kill the chain - should send to block :view override instead?
                      condis (into {}
                                   (for [[path f] (get block-function :cond)]
                                     {path (try (f result) (catch Exception e
                                                             (do (ut/ppln [:block ff :cond path :condi-error e])
                                                                 false)))}))
                      condi-path (for [[k v] condis :when v] k)
                      condi-channels (vec (remove nil? (for [c condi-path] (get-in @db/condi-channels-atom [flow-id [ff c]]))))
                    ;; result-map {:incoming data-val :result result ;; debug only
                    ;;             :port-accumulator (get @db/port-accumulator ff)
                    ;;             :block-function block-function
                    ;;             ;:channel-results @channel-results
                    ;;             :is-accumulating-ports? is-accumulating-ports?
                    ;;             :fully-resolved? fully-resolved?
                    ;;             :resolved-inputs resolved-inputs
                    ;;             :inputs inputs
                    ;;             :from data-from :to-this ff
                    ;;             :triple triples :t2 t2
                    ;;             :next next-hops
                    ;;             ;:poss-path-keys poss-path-keys
                    ;;             ;:poss-paths (apply concat (vals (select-keys fp poss-path-keys)))
                    ;;             :path the-path
                    ;;             :ts (System/currentTimeMillis)}
                      error? (get result :error)
                      post-when? (try ((get block-function :post-when? (fn [_] true)) result) (catch Exception _ true)) ;; default to true on error?
                      view (if error? ;; if error, we'll force a view to better surface it in the UI
                             (fn [x] [:re-com/v-box
                                      :style {:font-family "Poppins"
                                              :font-size "16px"}
                                      :padding "4px"
                                      :children [[:re-com/box
                                                  :align :center :justify :center
                                                  :style {:font-size "30px"
                                                          :font-weight 700
                                                          :color "red"}
                                                  :child (str "error:")]
                                                 [:re-com/box
                                                  :padding "6px"
                                                  :style {:color "orange"}
                                                  :child (str (get x :error))]]])
                             (get block-function :view))
                      speak-fn (get block-function :speak) ;; or [:speak :fn] later
                      speak (when (fn? speak-fn) (try (speak-fn result) (catch Exception _ nil)))
                      view-out (when (fn? view) (try (let [vv (view result)] ;; if view isn't hiccup and just a string, let's make it "pretty"?
                                                       (if (string? vv)
                                                         [:re-com/box
                                                          :width :width-px
                                                          :height :height-px
                                                          :child vv
                                                          :align :center :justify :center
                                                          :padding "10px"
                                                          :size "none"
                                                          :style {:font-size "18px"
                                                                  :margin-top "30px"
                                                                  :font-weight 700
                                                                  :color :vcolor}] vv))
                                                     (catch Exception e {:cant-eval-view-struct e :block ff})))
                      output-chs (remove nil? (cond ;; output channel, condis, or regular output switch
                                             ; is-subflow? []
                                                (or (not pre-when?)
                                                    (not post-when?)) [] ;; failed post/pre-when, send nowhere.
                                                (and done-channel-push?
                                                     (seqable? done-ch)) done-ch
                                                done-channel-push? [done-ch]
                                                #_{:clj-kondo/ignore [:not-empty?]}
                                                (not (empty? condi-path)) condi-channels
                                                :else output-chs))
                      web-val-map (merge
                                   (cond (and fully-resolved?
                                              is-accumulating-ports?)
                                         {:v (ut/limited result flow-id) ;; since it's going to the front-end
                                          :input (ut/limited resolved-inputs flow-id)}
                                         (and block-is-port? (ut/namespaced? ff)) ;; only true input ports
                                         {:v (ut/limited data-val flow-id) ;; since it's going to the front-end
                                          :port-of (first next-hops)}
                                         :else {:v (ut/limited result flow-id) ;; since it's going to the front-end
                                                :input (ut/limited data-val flow-id)})
                                   (if #_{:clj-kondo/ignore [:not-empty?]}
                                    (not (empty? condis)) {:cond condis} {})
                                   (if view {:view view-out} {})
                                   (if speak {:speak speak} {}))
                      value-only (fn [x] (if (and (map? x) (get x :port-in?))
                                           (first (vals (dissoc x :port-in?))) x))]

                  (when post-print (ut/ppln [flow-id ff (str (java.time.LocalDateTime/now)) :post
                                             (try (post-print result)
                                                  (catch Exception e [:post-print-error (str e)]))]))

                  (when (and web-push?
                             (not (= ff :done))) ;; :done block is hidden, so no pushing data for it.
                    (rest/push! flow-id ff [flow-id :blocks ff :body] web-val-map start (System/currentTimeMillis)))

                  (swap! db/resolved-paths assoc flow-id (conj (get @db/resolved-paths flow-id) the-path))
                  (swap! db/results-atom assoc-in [flow-id ff] (ut/limited result flow-id)) ;; final / last value sent to block, not history
                  (swap! db/fn-history assoc flow-id (conj (get @db/fn-history flow-id []) {:block ff :from data-from
                                                                                            :path the-path
                                                                                            :value (ut/limited (value-only result) flow-id)
                                                                                            :type :function
                                                                                            :dest ff
                                                                                            :channel [ff]
                                                                                            :dbgn (str dbgn-output)
                                                                                            :data-type (ut/data-typer (ut/limited (get web-val-map :v) flow-id))
                                                                                            :start fn-start :end fn-end
                                                                                            :elapsed-ms elapsed-ms}))
               ; (swap! db/history-atom conj result-map) ;; everything map log

                  (try (pp [:block ff [data-from ff]
                            :has-received (ut/limited (value-only data-val) flow-id)
                            :to-web (ut/limited (get web-val-map :v) flow-id)
                            :from data-from
                            :next-channel output-chs
                            :has-output (ut/limited (value-only result) flow-id)])
                       (catch Exception e (ut/ppln [:?test? e])))

                  (try (when #_{:clj-kondo/ignore [:not-empty?]}
                        (not (empty? output-chs))
                         (do
                        ;;  (swap! db/channel-history2 update flow-id conj (let [v (if (and (map? data-val) (get data-val :port-in?))  ;; trying to get channel wait times
                        ;;                                                           (first (vals (dissoc data-val :port-in?)))
                        ;;                                                           data-val)]
                        ;;                                                   {:path the-path ;; PRE RECUR HISTORY PUSH.
                        ;;                                                    :channel [data-from ff]
                        ;;                                                    :dest ff
                        ;;                                                    :start wstart
                        ;;                                                    :type :waiting
                        ;;                                                    :end (System/currentTimeMillis)
                        ;;                                                    :value (ut/limited v flow-id)
                        ;;                                                    :data-type (ut/data-typer v)}))
                           (if (seq output-chs)
                             (doseq [oo output-chs]  ;; forward processed data to the output channels   
                               (try (async/>! oo (if done-channel-push? result ;; no map wrapper for return channel
                                                     {:sender ff :value result}))
                                    (catch Exception e (ut/ppln [:caught-in-push-loop e :outputs (count output-chs)
                                                                 :this oo :output-chs output-chs]))))
                             (async/>! output-chs {:sender ff :value result}))))
                       (catch Exception e (ut/ppln [:caught-in-doseq-push-loop e :output-chs output-chs])))
                  (if
                   (empty? pending-chs)
                    (end-chain-msg flow-id fp ff result the-path debug?) ;; never runs in current form. remove.
                    (recur pending-chs))))
              (recur pending-chs)))))
              (catch Throwable e (ut/ppln [:main-go-loop-in-process-error! e])))))

;; (Thread/setDefaultUncaughtExceptionHandler ;; brute force. temp until close-channel/thread cyclical issue fixed
;;  (reify Thread$UncaughtExceptionHandler
;;    (uncaughtException [_ thread _]
;;      ;                 (ut/ppln ["force closing thread via channel shutdown:" thread]) ;; debug
;;      )))


(defn flow [network-map & [opts-map done-ch subflow-map]]
  (try ;; to pretty print the exception data only and not tons of other unhelpful garbage
    (cond ;; check spec, was instrumenting but the error msgs were horrible
      (not (s/valid? ::network-map network-map))
      (throw (ex-info "Invalid network-map"
                      {:exception :flow-cannot-run!
                       :failed-spec-on :flow-map
                       :issue (s/explain-str ::network-map network-map)
                       :input network-map}))

      (and opts-map (not (s/valid? ::opts-map opts-map)))
      (throw (ex-info "Invalid opts-map"
                      {:exception :flow-cannot-run!
                       :failed-spec-on :flow-opts-map
                       :issue (s/explain-str ::opts-map opts-map)
                       :input opts-map}))

      (and done-ch (not (s/valid? ::done-ch done-ch)))
      (throw (ex-info "Invalid done-ch"
                      {:exception :flow-cannot-run!
                       :failed-spec-on :output-channel-or-atom
                       :issue (s/explain-str ::done-ch done-ch)}))

      (and subflow-map (not (s/valid? ::subflow-map subflow-map)))
      (throw (ex-info "Invalid subflow-map"
                      {:exception :flow-cannot-run!
                       :failed-spec-on :subflow-override-map
                       :issue (s/explain-str ::subflow-map subflow-map)
                       :input subflow-map}))

      :else

      ;; procedural clean up - TODO when DB
      (let [flow-id (or (get opts-map :flow-id) (ut/generate-name))
            flow-id (if (not (= flow-id "live-scratch-flow"))
                      (str flow-id "-" (count (filter #(cstr/starts-with? % flow-id) (keys @db/channel-history))))
                      flow-id)] ;; don't number the scratch-flow iterations...
        (swap! db/results-atom dissoc flow-id)
        (swap! db/channel-history dissoc flow-id)
        ;(swap! db/channel-history2 dissoc flow-id)
        (swap! db/fn-history dissoc flow-id)
        (swap! db/block-dump dissoc flow-id)
        (swap! db/waffle-data dissoc flow-id)
        (swap! db/block-defs dissoc flow-id) ;; save and ship these in case the UI somehow missed a creation (saves the UI from weird state)

        ;; channel clean up?
        (when #_{:clj-kondo/ignore [:not-empty?]} ;; clean up old channels on new run, if 
         (and (not (empty? (get @db/channels-atom flow-id)))    ;; - we have channels
              (not (true? @web/websocket?))        ;; - web ui is NOT running
              (not (get opts-map :debug? true)))   ;; - debug is NOT enabled
          ;(get opts-map :close? false))       ;; - close channels opt?
          (doseq [[k c] (get @db/channels-atom flow-id)]
            (try #_{:clj-kondo/ignore [:redundant-do]}
             (do (ut/ppln [:closing-channel k c])
                 (async/close! c)) (catch Exception e (ut/ppln [:error-closing-channel e k c])))))

        (try
          (let [{:keys [components connections]} network-map
                opts-map (merge {:debug? true :debux? false :close-on-done? false} opts-map) ;; merge w defaults, pass as map to process
                {:keys [debug? debux?]} opts-map
                pp (if debug? ut/ppln ut/pplno) ;; TODO, gross.
                web-push? (true? @web/websocket?)
                gen-connections (for [[_ f] connections
                                      :let [base (ut/unkeyword (first (cstr/split (str f) #"/")))]
                                      :when (cstr/includes? (str f) "/")]
                                  [f (keyword base)])
                components (into {} ;; todo, integrate this into the logic better. this whole binding = very "procedural"
                                 (for [[k v] components] ;; <- sub-flow overrides need to be modeled as multi-input ports befer we proceed
                                   {k (if (s/valid? ::network-map v)
                                        (merge v {:inputs (vec (for [c (filter #(cstr/includes? (str %) (str k "/"))
                                                                               (distinct (flatten connections)))]
                                                                 (keyword (last (cstr/split (str c) #"/")))))}) v)}))
                named-connections connections
                connections (vec (distinct (into connections gen-connections)))
                channels (into {} (for [conn connections] ;; TODO connection opts map for channel size, etc. Now def'd 1.
                                    {conn (async/chan 1)}))
                components (assoc
                            (merge ;; filter out comps that have no connections, no need for them - TODO, kinda grody
                             (merge (into {} (for [[_ v] components
                                                   :when (get v :cond)]
                                               (get v :cond)))
                                    (into {}
                                          (flatten (for [[k v] components
                                                         :let [ins (get v :inputs)]
                                                         :when ins]
                                                     (for [i ins]
                                                       {(keyword (str (ut/unkeyword k) "/"
                                                                      (ut/unkeyword i))) {:fn :port}})))))
                             components) :done {:fn (fn [x] x)}) ;; add in 'implied' "blocks" (ports, condis, done, etc)
                condi-connections (vec (apply concat (for [[k v] components
                                                           :when (get v :cond)]
                                                       (for [c (keys (get v :cond))] [k c]))))
          ;; TODO - don't build implicit condi paths if the parent doesn't exist in the connection pathways
                condi-channels (into {} (for [conn condi-connections]
                                          {conn (async/chan 1)}))
                connections (vec (distinct (into connections condi-connections))) ;; add after channel maps
                coords-map (ut/coords-map connections)
                fp (path-map connections)
                ;chains (count (keys flow-paths-map))
                ;chains-run (count (cset/intersection (set (keys flow-paths-map)) (set (keys @results-atom))))
                ;chains-done? (= chains chains-run)
                sources (fn [to] (vec (distinct (for [[ffrom tto] connections
                                                      :when (= tto to)] ffrom))))
                components (select-keys components (vec (distinct (flatten (apply conj connections))))) ;; no reason to bother with unlinked blocks for now
                input-mappings (into {} ;; mostly for web
                                     (for [b (keys components)]
                                       {b (vec (remove #{b} (flatten (filter #(= (second %) b) connections))))}))
                started (atom #{})]
            (when web-push? (rest/push! flow-id :none [flow-id :blocks] {}))
            (swap! db/working-data assoc
                   flow-id ;; network-map 
                   {:connections named-connections
                    :points (get network-map :points)
                    :hide (get network-map :hide)
                    :limit (get network-map :limit db/sample-limit)
                    :rewinds (get network-map :rewinds db/rewinds)
                    :description (get network-map :description)
                    :colors (get network-map :colors :Paired)
                    :docs (into {} (remove empty? (for [[k {:keys [doc]}]
                                                        (get network-map :components)
                                                        :when (not (nil? doc))] {k doc})))
                    :gen-connections gen-connections
                    :components-list (keys components)
                    :components (pr-str components)
                    :condis condi-connections})

            (pp [:gen-connections gen-connections :connections connections :fp fp
                 :comps components :coords-map coords-map :condi-connections condi-connections])

            (when web-push?
              (rest/push! flow-id :none [flow-id :network-map] (get @db/working-data flow-id)))

            (when web-push?
              #_{:clj-kondo/ignore [:redundant-do]} ;; lol, it is, in fact, NOT redundant.
              (do
                (doseq [[bid v] components
                        :when (not (= bid :done))
                        :let [from-val (get components bid) ;; incoming fn or static - TODO consolidate this logic w channel loops
                              static-input? (and (not (fn? from-val)) ;; not a raw fn block
                                                 (nil? (get from-val :fn)))
                              is-subflow? (s/valid? ::network-map from-val)
                              view-mode (get-in network-map [:canvas bid :view-mode])
                              hidden1? (get-in network-map [:canvas bid :hidden?])
                              hidden? (if (and (nil? hidden1?) (ut/namespaced? bid)) true hidden1?)
                          ;text-input? (true? (some #(= :text-input %) (flatten [from-val])))
                              block-def (merge {:y (or (get-in network-map [:canvas bid :y]) (get v :y (get-in coords-map [bid :y]))) ;; deprecated locations TODO
                                                :x (or (get-in network-map [:canvas bid :x]) (get v :x (get-in coords-map [bid :x]))) ;; deprecated locations
                                                :base-type :artifacts
                                                :width (or (get-in network-map [:canvas bid :w]) (get v :w 240))  ;; deprecated locations
                                                :height (or (get-in network-map [:canvas bid :h]) (get v :h 255)) ;; deprecated locations
                                                :type :text
                                                :flowmaps-created? true
                                                :block-type "map2"
                                                :hidden? hidden?
                                                :hidden-by (when (ut/namespaced? bid)
                                                             (try (edn/read-string (first (cstr/split (str bid) #"/"))) (catch Exception _ nil)))
                                                :view-mode (if (get v :view) "view" "data")
                                                :options [{:prefix ""
                                                           :suffix ""
                                                           :block-keypath [:view-mode]
                                                           :values (vec (remove nil?
                                                                                ["data"
                                                                                 (when (get v :view) "view")
                                                                                 (when (get v :doc) "doc")
                                                                                 "grid" "text"
                                                                                 (when (and (not static-input?) debux?) "dbgn")
                                                                                 (when (and (not is-subflow?)
                                                                                            static-input?) "input")]))}]}
                                               (when is-subflow? {:subflow? true})
                                               (when static-input? {:text-input? true ;; override the view mode w input
                                                                    :view-mode "input"})
                                               (when view-mode {:view-mode view-mode}))]]
                  (do (swap! db/block-defs assoc-in [flow-id bid] block-def)
                      (rest/push! flow-id bid [flow-id :blocks bid] block-def)))

                (doseq [[bid v] (get network-map :canvas) ;; decorative front-end-only blocks ("map pulls" / visual get-in)
                        :when (string? bid)
                        :let [block-def {:y (or (get-in network-map [:canvas bid :y]) (get v :y (get-in coords-map [bid :y]))) ;; deprecated locations TODO
                                         :x (or (get-in network-map [:canvas bid :x]) (get v :x (get-in coords-map [bid :x]))) ;; deprecated locations
                                         :base-type :artifacts
                                         :width (or (get-in network-map [:canvas bid :w]) (get v :w 240))  ;; deprecated locations
                                         :height (or (get-in network-map [:canvas bid :h]) (get v :h 255)) ;; deprecated locations
                                         :type :text
                                         :inputs (get-in network-map [:canvas bid :inputs])
                                         :block-type "text"}]]
                  (do (swap! db/block-defs assoc-in [flow-id bid] block-def)
                      (rest/push! flow-id bid [flow-id :blocks bid] block-def)))

                (doseq [[bid inputs] input-mappings
                        :when (not (= bid :done))
                        :let [inputs (vec (conj
                                           (vec (for [i inputs]
                                                  [i [:text [:no nil]]]))
                                           [nil [:text [:paragraph 0]]]))]] ;; not rendering :done blocks, so we sure as hell dont want to draw links to them
                  (do (swap! db/block-defs assoc-in [flow-id bid :inputs] inputs)
                      (rest/push! flow-id bid [flow-id :blocks bid :inputs] inputs)))))

            (swap! db/channels-atom assoc flow-id channels)
            (swap! db/condi-channels-atom assoc flow-id condi-channels)

            (doseq [c connections] ;;; "boot" channels into history even if they never get used...
              (swap! db/channel-history update flow-id conj {:path [:creating-channels :*]
                                                             :channel c
                                                             :start (System/currentTimeMillis)
                                                             :end (System/currentTimeMillis)
                                                             :value nil
                                                             :data-type "boot"}))
            (doseq [from (keys components)]
              (let [start (System/currentTimeMillis)
                    subflow-override? (not (nil? (get subflow-map from)))
                    fn-is-subflow? (s/valid? ::network-map (get components from))
                    from-val (if subflow-override?
                               (get subflow-map from)
                               (get components from))
                    out-conns (filter #(= from (first %)) connections)
                    in-conns (filter #(= from (second %)) connections)
                    in-condi-conns (filter #(= from (second %)) condi-connections)
                    to-chans (map channels (filter #(= from (first %)) connections))]
                (pp (str "  ... Processing: " from))

                (if (and (not (fn? from-val)) ;; not a raw fn
                         (nil? (get from-val :fn))
                         ;; (not (fn? (get from-val :fn)))
                         (not fn-is-subflow?)) ;; make sure its not a sub-flow map / we will treat that as a fn here

          ;; STATIC VALUE / INPUT BLOCK - assume some static value "starting" and pass it as is
                  (let [text-input? (true? (some #(= :text-input %) (flatten [from-val])))
                        from-val (if text-input? (last from-val) from-val)
                        ;from-val (get from-val :fn from-val) ;; heh. temp fix, for static blocks and pushing, refactor right before v1.0
                        ;from-val (if (get from-val :fn) (get from-val :fn) from-val)
                        ]
                    (when (some #(= % from) (get network-map :hide))
                      (swap! db/hidden-values assoc-in [flow-id from-val] "***HIDDEN-FROM-UI***"))
                    (when web-push? (rest/push! flow-id from [flow-id :blocks from :body]
                                                (str
                                                 {:v (ut/limited from-val flow-id)})))
                    (pp (vec (remove nil? [:block from :pushing-static-value from-val :to (vec (map last out-conns)) (when subflow-override? :subflow-value-override!)])))
                    (swap! started conj from)
                    (swap! db/results-atom assoc-in [flow-id from] from-val)
                    (when web-push? ;; seeding the block history with this static value, just to be consistent, also imp for text-inputs history...
                      (rest/push! flow-id from [flow-id :blocks from :body] {:v (ut/limited from-val flow-id)} start (System/currentTimeMillis)))
                    (swap! db/fn-history assoc flow-id (conj (get @db/fn-history flow-id []) {:block from :from :static
                                                                                              :path [:from :static from]
                                                                                              :value (ut/limited from-val flow-id)
                                                                                              :type :function
                                                                                              :dest from
                                                                                              :channel [from]
                                                                                              :data-type (ut/data-typer (ut/limited from-val flow-id))
                                                                                              :start start
                                                                                              :end (System/currentTimeMillis)
                                                                                              :elapsed-ms (- (System/currentTimeMillis) start)})) ;; temp modded from other, unimportant
                    (doseq [c to-chans] (async/put! c {:sender from :value from-val})))

          ;; FN BLOCK / SUBFLOW BLOCK
                  (let [;in-chans (map channels in-conns)
                        in-chans (remove nil? (into (map channels in-conns) (map condi-channels in-condi-conns)))
                        ;condi-chans (map condi-channels in-condi-conns)
                        has-starter? (get from-val :starter)
                        out-chans (map channels out-conns)
                        tt {:inputs (vec (for [c (filter #(cstr/includes? (str %) (str from "/")) (distinct (flatten connections)))]
                                           (keyword (last (cstr/split (str c) #"/")))))}
                        from-val (if (map? from-val) ;(and (map? from-val) (not (s/valid? ::network-map from-val))) ;; need to fully qualify in/out port keywords
                                   (merge from-val
                                          {:inputs ;(if (s/valid? ::network-map from-val)
                                                   ;  (vec (filter #(cstr/includes? (str %) (str from "/"))
                                                   ;               (distinct (flatten connections)))) ;; "fake" ports for subflow
                                                     (vec (for [i (get from-val :inputs)]
                                                          (keyword (str (ut/unkeyword from) "/"
                                                                        (ut/unkeyword i)))))})
                               ;(if debux? (dx/dbgn from-val 500) from-val)
                                   from-val)
                        srcs (sources from)]
                    (pp ["Creating channels for" from :-> srcs  :already-started (count (set @started)) :blocks])

                    (let [] ;do ;when parents-ready? ;; placeholder
                      (pp ["Starting block " from " with channel(s): " in-chans])
                  ;(Thread/sleep 1000) ;; just for debugging
                      (swap! started conj from)
                      (async/thread
                        (pp ["In thread for " from " reading from channel " in-chans])
                        (process flow-id connections in-chans out-chans from fp from-val opts-map done-ch)))

                    (when has-starter? ;; 95% dupe code from above - refactor TODO - used to SEED an unrun function into the flow
                      (let [;text-input? false ;(true? (some #(= :text-input %) (flatten [from-val])))
                            ;from-val (if text-input? (last from-val) from-val)
                            from-val (get from-val :starter)] ;; lazy override temp for this form
                        (when web-push? (rest/push! flow-id from [flow-id :blocks from :body] (str {:v from-val})))
                        (pp (vec (remove nil? [:block-starter from :pushing-static-value from-val :to (vec (map last out-conns)) (when subflow-override? :subflow-value-override!)])))
                        (swap! started conj from)
                        (swap! db/results-atom assoc-in [flow-id from] from-val)
                        (when web-push? ;; seeding the block history with this static value, just to be consistent, also imp for text-inputs history...
                          (rest/push! flow-id from [flow-id :blocks from :body] {:v from-val} start (System/currentTimeMillis)))
                        (swap! db/fn-history assoc flow-id (conj (get @db/fn-history flow-id []) {:block from :from :starter
                                                                                                  :path [:from :starter from]
                                                                                                  :value (ut/limited from-val flow-id)
                                                                                                  :type :function
                                                                                                  :dest from
                                                                                                  :channel [from]
                                                                                                  :data-type (ut/data-typer (ut/limited from-val flow-id))
                                                                                                  :start start
                                                                                                  :end (System/currentTimeMillis)
                                                                                                  :elapsed-ms (- (System/currentTimeMillis) start)})) ;; temp modded from other, unimportant
                        (doseq [c to-chans] (async/put! c {:sender from :value from-val})))))))))
          (catch Exception e (ut/ppln {:flow-error e})))))
    (catch clojure.lang.ExceptionInfo e
          ; Printing only the exception message and not lots of useless garbage
      (ut/ppln (.getData e)))))

;; (stest/instrument `flow)

(defmethod wl/handle-request :push-live-flow [{:keys [value canvas]}]
  (try ;; live eval and send it (needs access to above fns w/o circ-loop) TODO refactor out namespaces
    (let [evl (eval ;; TODO default to *this* namespace
               (read-string value))
          evl-canvas (get evl :canvas {})
          merged-canvas (merge evl-canvas canvas)
          evl (assoc evl :canvas merged-canvas)
          valid? (s/valid? ::network-map evl)
          spec-demangle {:issue (s/explain-str ::network-map evl)}]
      (when valid? (try ;; no point to try and run a bogus flow
                     (flow evl {:flow-id "live-scratch-flow"})
                     (catch Exception e (ut/ppln {:live-flow-exec-error e}))))
      (ut/ppln [:incoming-live-flow canvas evl-canvas merged-canvas value evl])
      [:live-flow-return [:live-flow-return] (merge
                                              {:sent value
                                               :received (System/currentTimeMillis)
                                               :return (pr-str evl)
                                               :valid? valid?}
                                              (when (not valid?)
                                                spec-demangle))])
    (catch Exception e (do (ut/ppln [:incoming-live-flow :error value (str e)])
                           [:live-flow-return [:live-flow-return] {:error (str e)
                                                                   :received (System/currentTimeMillis)}]))))

(defn flow-results [] ;; somewhat pointless, but hey. TODO
  (into {}
        (for [flow-id (keys @db/resolved-paths)]
          {flow-id
           (let [res (dissoc (into {}
                                   (for [p (vec (distinct (get @db/resolved-paths flow-id)))]
                                     {p (for [pp p]
                                          [pp (get-in @db/results-atom [flow-id pp])])}))
                             [:error-path-issue!])] ;; TODO
             ;(ut/ppln {:resolved-paths-end-values res})
             res)})))

(defn ask [flow-id point-name value]
  ;; fn version of rest/flow-point-push 
  )

(defn schedule! [time-seq1 flowmap & args]
  (let [[opts chan-out override] args
        opts (if (nil? opts) {} opts)
        times (if (and (vector? time-seq1) (keyword? (first time-seq1)))
                (doall (take 1000 (ut/time-seq time-seq1)))
                time-seq1)] ;; if custom time list, just send it.
    ;(ut/ppln [:times times :time-seq time-seq1])
    (swap! db/live-schedules conj {:flow-id (get opts :flow-id "unnamed-flow-sched")
                                   :override override
                                   :schedule (if (vector? time-seq1) time-seq1 [:custom-time-fn])})

    (chime/chime-at
     times ;; [] chime time seq, https://github.com/jarohen/chime#recurring-schedules
     (fn [time]
       (let [opts (merge opts {:schedule-started (str time)})]
         (flow flowmap opts chan-out override)))
     {:on-finished (fn [] (ut/ppln [:schedule-finished! opts time-seq1]))
      :error-handler (fn [e] (ut/ppln [:scheduler-error e]))})))

;; snippets for testing

;(web/start!) ;; boots the webserver and socket server - TODO, will be a fn to start the REST server by itself w/o dev UI
;(web/stop!)
;(db/re-init :file "mem-test") ;; persistent atoms (can even change after regular atoms have been used)
;(flow fex/my-network {:flow-id "baloney-space-men-ahoy-mustard"} nil {:comp1 4545 :comp2 2323}) ;; override subflow values
;(flow fex/looping-net)
;(flow fex/my-network)
;(flow fex/my-network-input)
;(flow fex/odd-even {:flow-id "odds-and-evens"})
;(flow fex/odd-even {:flow-id "odds-and-evens-2"} nil {:int1 42 :int2 42})
;(flow fex/ecommerce-flow)
;(flow fex/color-art-flow) 
;(schedule! [:minutes 21] fex/looping-net {:flow-id "loop-time1!" :close-on-done? true :debug? false})
;(schedule! [:seconds 23] fex/looping-net {:flow-id "loop-time2!" :close-on-done? true :debug? false})
;(schedule! [:seconds 24] fex/looping-net {:flow-id "loop-time3!" :close-on-done? true :debug? false})
;(schedule! [:seconds 120] fex/looping-net {:flow-id "loop-time4!" :close-on-done? true :debug? false})
;(schedule! [:seconds 26] fex/my-network {:flow-id "simple!" :close-on-done? true :debug? false})
;(schedule! [:minutes 5] fex/etl-flow {:flow-id "etl-flow!" :close-on-done? true :debug? false})
;(schedule! [:seconds 30] fex/my-network {:flow-id "loop-time-debug!" :close-on-done? true :debug? false})
;(schedule! [:minutes 2] fex/looping-net {:flow-id "test-macro?"  :close-on-done? true :debug? false})
;(schedule! [:minutes 1] fex/ecosystem-flow {:flow-id "eco-system?"  :close-on-done? true :debug? false})
;(schedule! [:seconds 20] fex/my-network)
;(flow fex/ecosystem-flow) 
;(flow fex/etl-flow) 
;(flow fex/sub-flow)
;(flow fex/ask-buffy {:flow-id "ask-buffy" :debug? false})
;(flow fex/memory-watcher {:flow-id "memory-watcher" :debug? false})
;(flow fex/openai-calls {:flow-id "openai-history-loop"})
;(flow bad-sample-flow)
;(flow (flow> 10 + #(* 2 %) - #(+ 10 %) (fn [x] (str x "!"))))

;; (chime/chime-at (chime/periodic-seq (Instant/now) (Duration/ofSeconds 30))
;;                 (fn [time]
;;                   (ut/ppln {:time time 
;;                             :memory (ut/get-memory-usage)
;;                             :threads (ut/get-thread-count)})))

;; (ut/ppln [[:days 2] 
;;           (take 10 (ut/time-seq [:days 2]))])
;; (ut/ppln [[:days 2 :starts "2022-09-03"]
;;           (take 10 (ut/time-seq [:days 2 :starts "2022-09-03"]))])
;; (ut/ppln [[:days 1 :at 2000 :tz "America/New_York"]
;;           (take 10 (ut/time-seq [:days 1 :at 2000 :tz "America/New_York"]))])
;; (ut/ppln [[:days 7 :at 600]
;;           (take 10 (ut/time-seq [:days 7 :at 600]))])
;; (ut/ppln [[:minutes 2]
;;           (take 10 (ut/time-seq [:minutes 2]))])
;; (ut/ppln [[:seconds 20]
;;           (take 10 (ut/time-seq [:seconds 20]))])
;; (ut/ppln [[:weeks 1]
;;           (take 10 (ut/time-seq [:weeks 1]))])
;; (ut/ppln [[:months 1]
;;           (take 10 (ut/time-seq [:months 1]))])
;; (ut/ppln [[:months 3 :starts "2022-09-23"]
;;           (take 10 (ut/time-seq [:months 1 :starts "2022-09-23"]))])
;; (ut/ppln [[:months 1 :at 2300 :starts "2022-09-23"]
;;           (take 10 (ut/time-seq [:months 1 :at 2300 :starts "2022-09-23"]))])
;; (ut/ppln [[:weeks 1 :at 800 :starts "2023-10-10"]
;;           (take 10 (ut/time-seq [:weeks 1 :at 800 :starts "2023-10-10"]))])

