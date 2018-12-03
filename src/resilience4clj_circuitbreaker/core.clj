(ns resilience4clj-circuitbreaker.core
  (:import
   (io.github.resilience4j.circuitbreaker CircuitBreakerConfig
                                          CircuitBreaker)
   (io.github.resilience4j.core EventConsumer)
   (io.vavr.control Try)
   (java.util.function Supplier)
   (java.time Duration)))

(defn ^:private anom-map
  [category msg]
  {:resilience4clj.anomaly/category (keyword "resilience4clj.anomaly" (name category))
   :resilience4clj.anomaly/message msg})

(defn ^:private anomaly!
  ([name msg]
   (throw (ex-info msg (anom-map name msg))))
  ([name msg cause]
   (throw (ex-info msg (anom-map name msg) cause))))

(defn ^:private get-failure-handler [{:keys [fallback]}]
  (if fallback
    (fn [e] (fallback e))
    (fn [e] (throw e))))

;; FIXME: needs to deal with RecordFailurePredicate
(defn ^:private config-data->circuit-breaker-config
  "the failure rate threshold in percentage above which the
  CircuitBreaker should trip open and start short-circuiting calls

  the wait duration which specifies how long the CircuitBreaker should
  stay open, before it switches to half open

  the size of the ring buffer when the CircuitBreaker is half open

  the size of the ring buffer when the CircuitBreaker is closed

  a custom CircuitBreakerEventListener which handles CircuitBreaker
  events

  a custom Predicate which evaluates if an exception should be
  recorded as a failure and thus increase the failure rate"
  [{:keys [failure-rate-threshold
           ring-buffer-size-in-closed-state
           ring-buffer-size-in-half-open-state
           wait-duration-in-open-state
           automatic-transition-from-open-to-half-open-enabled?]}]
  (.build
   (cond-> (CircuitBreakerConfig/custom)
     failure-rate-threshold
     (.failureRateThreshold failure-rate-threshold)

     ring-buffer-size-in-closed-state
     (.ringBufferSizeInClosedState ring-buffer-size-in-closed-state)

     ring-buffer-size-in-half-open-state
     (.ringBufferSizeInHalfOpenState ring-buffer-size-in-half-open-state)

     wait-duration-in-open-state
     (.waitDurationInOpenState (Duration/ofMillis wait-duration-in-open-state))

     (not (nil? automatic-transition-from-open-to-half-open-enabled?))
     (.enableAutomaticTransitionFromOpenToHalfOpen))))

;; FIXME: needs to deal with RecordFailurePredicate
(defn ^:private circuit-breaker-config->config-data
  [cb-config]
  {:failure-rate-threshold (.getFailureRateThreshold cb-config)
   :ring-buffer-size-in-closed-state (.getRingBufferSizeInClosedState cb-config)
   :ring-buffer-size-in-half-open-state (.getRingBufferSizeInHalfOpenState cb-config)
   :wait-duration-in-open-state (.toMillis (.getWaitDurationInOpenState cb-config))
   #_:record-failure-predicate #_(.getRecordFailurePredicate cb-config)
   :automatic-transition-from-open-to-half-open-enabled? (.isAutomaticTransitionFromOpenToHalfOpenEnabled cb-config)})

(defn ^:private event-consumer [f]
  (reify EventConsumer
    (consumeEvent [this e]
      (f e))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public functions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create
  ([n]
   (create n nil))
  ([n opts]
   (if opts
     (CircuitBreaker/of ^String n
                        ^CircuitBreakerConfig (config-data->circuit-breaker-config opts))
     (CircuitBreaker/ofDefaults n))))

(defn config
  [breaker]
  (-> breaker
      .getCircuitBreakerConfig
      circuit-breaker-config->config-data))

(defn decorate
  ([f breaker]
   (decorate f breaker nil))
  ([f breaker opts]
   (fn [& args]
     (let [callable (reify Callable (call [_] (apply f args)))
           decorated-callable (CircuitBreaker/decorateCallable breaker callable)
           failure-handler (get-failure-handler opts)
           result (Try/ofCallable decorated-callable)]
       (if (.isSuccess result)
         (.get result)
         (failure-handler (.getCause result)))))))

(defn metrics
  "the failure rate in percentage.
  the current number of buffered calls.
  the current number of failed calls."
  [cb]
  (let [metrics (.getMetrics cb)]
    {:failure-rate                  (.getFailureRate metrics)
     :number-of-buffered-calls      (.getNumberOfBufferedCalls metrics)
     :number-of-failed-calls        (.getNumberOfFailedCalls metrics)
     :number-of-not-permitted-calls (.getNumberOfNotPermittedCalls metrics)
     :max-number-of-buffered-calls  (.getMaxNumberOfBufferedCalls metrics)
     :number-of-successful-calls    (.getNumberOfSuccessfulCalls metrics)}))

(defn state
  [breaker]
  (-> breaker
      .getState
      .toString
      keyword))

(defn reset
  [cb]
  (.reset cb))

(defn listen-event
  ([cb f]
   (listen-event cb :all f))
  ([cb event-key f]
   (let [event-publisher (.getEventPublisher cb)
         consumer (event-consumer f)]
     (case event-key
       :success (.onSuccess event-publisher consumer)
       :error (.onError event-publisher consumer)
       :ignored-error (.onIgnoredError event-publisher consumer)
       :reset (.onReset event-publisher consumer)
       :state-transition (.onStateTransition event-publisher consumer)
       :all (.onEvent event-publisher consumer)))))






(comment
  ;; set up a breaker for the service (possibly from states/mount)
  (def service-breaker (create "MyService"))

  (config service-breaker)
  
  ;; mock for an external call
  (defn external-call
    ([n]
     (external-call n nil))
    ([n {:keys [fail? wait]}]
     (when wait
       (Thread/sleep wait))
     (if-not fail?
       (str "Hello " n "!")
       (anomaly! :broken-hello "Couldn't say hello"))))

  ;; circuit breaker protecetd function
  (def protected-call (decorate external-call
                                service-breaker))

  ;; call the protected version (will throw if failure)
  (protected-call) ;; => "Does external call here"

  (dotimes [n 10]
    (protected-call "World!!"))

  (metrics service-breaker)
  
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  ;; protected with fallback (will return fallback if failure/timeout/open cb/etc)
  (def protected-call-fallback (decorate external-call
                                         service-breaker
                                         {:fallback
                                          (fn [e]
                                            (str "I should say Hello but got " (.getMessage e)))}))

  ;; call the protected version
  (protected-call-fallback) ;; => "Default for service" (if failure)

  (dotimes [n 100]
    (protected-call-fallback))

  (metrics service-breaker)
  
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  (metrics service-breaker)

  (reset service-breaker)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

  ;; create a breaker with custom settigns
  (def service-breaker2 (create "MyService2"
                                {:failure-rate-threshold 20.0
                                 :ring-buffer-size-in-closed-state 2
                                 :ring-buffer-size-in-half-open-state 2
                                 :wait-duration-in-open-state 60000}))


  (info service-breaker2)

  (defn iffy-dependency [fail]
    (if fail
      (throw (ex-info "No good!" {}))
      "Very good!"))

  (def protected-iffy (decorate iffy-dependency service-breaker2))

  (reset service-breaker2)
  
  (protected-iffy false)

  (metrics service-breaker2)
  
  )
