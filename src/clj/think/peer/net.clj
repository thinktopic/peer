(ns think.peer.net
  (:require
    [taoensso.timbre :as log]
    [chord.http-kit :refer [with-channel]]
    [chord.format :as cf]
    [hiccup.core :refer [html]]
    [hiccup.page :refer [include-css include-js]]
    [org.httpkit.server :as http-kit]
    [ring.middleware.resource :refer [wrap-resource]]
    [ring.middleware.file :refer [wrap-file]]
    [bidi.ring :refer [make-handler]]
    [clojure.core.async :refer [<! >! go go-loop] :as async]
    [think.peer.api :as api])
  (:import [org.fressian.handlers WriteHandler ReadHandler]
           [org.fressian.impl ByteBufferInputStream BytesOutputStream]))

(def DEFAULT-PEER-PORT 4242)

(defn mapply
  "Apply a map as keyword args to a function.
   e.g.
      (mapply foo {:a 2}) => (foo :a 2)
  "
  [f & args]
  (apply f (apply concat (butlast args) (last args))))

(defn run-handler
  [handler {:keys [args]}]
  (let [{:keys [arglists partial-args]} (meta handler)
        n (count args)
        arg-counts (map count arglists)
        ok? (some #(= n %) arg-counts)
        optional? (first (filter #(= '& (first %)) arglists))]
    (cond
      ok?       (apply handler args)
      optional? (handler)
      ;; TODO: This is not surfacing from a System using think.peer...
      :default (throw (ex-info (str "Incorrect number of arguments passed to function: "
                                    n " for function " handler " with arglists " arglists)
                               {})))))

(defn event-handler
  [api req]
  (if-let [handler (get-in api [:event (:event req)])]
    (run-handler handler req)
    (log/info "Unhandled event: " req)))

(defn run-middleware
  [message fns]
  (reduce
    (fn [msg middleware-fn]
      (middleware-fn msg))
    message
    fns))

(defn rpc-event-handler
  [api on-error middleware {:keys [chan id] :as req}]
  (go
    (try
    (if-let [handler (get-in api [:rpc (:fn req)])]
      (let [v (run-handler handler req)
            res (if (satisfies? clojure.core.async.impl.protocols/ReadPort v)
                  (<! v)
                  v)
            response {:event :rpc-response :id id :result res}
            response (run-middleware response (:on-leave middleware))]
        (>! chan response))
      (throw (ex-info "Unhandled rpc-request: " req)))
    (catch Exception e
      (if (fn? on-error)
        (on-error e)
        (log/error e))
      (>! chan {:event :rpc-response :id id :result {:error (str e)}})))))

(defn subscription-event-handler
  [peers* peer-id api {:keys [chan id] :as req}]
  (go
    (if-let [handler (get-in api [:subscription (:fn req)])]
      (let [res (apply handler (:args req))
            res (if (map? res) res {:chan res})
            pub-chan (:chan res)]
        (if (satisfies? clojure.core.async.impl.protocols/ReadPort pub-chan)
          (let [event-wrapper (async/chan 1 (map (fn [v] {:event :publication :id id :value v})))]
            (async/pipe pub-chan event-wrapper)
            (async/pipe event-wrapper chan)
            (swap! peers* assoc-in [peer-id :subscriptions id] res))
          (throw (ex-info (str "Subscription function didn't return a publication channel:" req)
                          {}))))
      (throw (ex-info (str "Unhandled subscription request: " req)
                      {})))))

(defn unsubscription-event-handler
  [peers* peer-id {:keys [id] :as req}]
  (let [{:keys [chan stop]} (get-in @peers* [peer-id :subscriptions id])]
    (swap! peers* update-in [peer-id :subscriptions] dissoc id)
    (when (fn? stop)
      (stop))
    (async/close! chan)))

(defn disconnect-peer
  [peers* peer-id]
  (let [peer (get @peers* peer-id)]
    (swap! peers* dissoc peer-id)
    (if-let [peer-chan (:chan peer)]
      (async/close! peer-chan))
    (doseq [[sub-id {:keys [chan stop]}] (:subscriptions peer)]
      (when (fn? stop)
        (stop))
      (async/close! chan))))

(defn disconnect-all-peers
  [peers*]
  (doseq [peer-id (keys @peers*)]
    (disconnect-peer peers* peer-id)))

(defn- api-router
  "Setup a router go-loop to handle received messages from a peer."
  [{:keys [api* peers* on-error on-event on-disconnect middleware] :as listener}
   peer-id]
  (let [peer (get @peers* peer-id)
        peer-chan (:chan peer)]
    (go-loop []
      (let [{:keys [message error] :as packet} (<! peer-chan)]
        (if (or (nil? packet) error)
          (do
            (when on-disconnect
              (on-disconnect peer))
            (disconnect-peer peers* peer-id))
          (do
            (when message
              (try
                (let [message (assoc message
                                     :peer-id peer-id
                                     :chan peer-chan)
                      event-type (:event message)
                      message (run-middleware message (:on-enter middleware))]
                  (when on-event
                    (on-event message))
                  (cond
                    (= event-type :rpc) (rpc-event-handler @api* on-error middleware message)
                    (= event-type :subscription) (subscription-event-handler peers* peer-id @api* message)
                    (= event-type :unsubscription) (unsubscription-event-handler peers* peer-id message)
                    :default (event-handler @api* message)))
                (catch Exception e
                  (when on-error
                    (on-error e))))
              (recur))))))))

(defn connect-listener
  "Connect an API listener to a websocket.  Takes an http-kit request
  from a websocket endpoint.

  (connect-listener listener request)
  "
  [{:keys [peers* api* on-error on-connect packet-format] :as listener} req]
  (with-channel req ws-ch {:format packet-format}
    (go
      (try
      (let [{:keys [message error]} (<! ws-ch)]
        (if error
          (do
            (log/error error)
            (when on-error
              (on-error error)))
          (let [peer-id (:peer-id message)
                peer {:peer-id peer-id :chan ws-ch :subscriptions {} :request req}]
            (swap! peers* assoc peer-id peer)
            (api-router listener peer-id)
            (>! ws-ch {:type :connect-reply :success true})
            (when on-connect
              (on-connect peer)))))
      (catch Exception e
        (when on-error
          (on-error e))
        (throw e))))))


(defn- page-template
  [body css]
  {:status 200
   :headers {}
   :body (html
          [:html
           [:head
            [:meta {:charset "utf-8"}]
            [:meta {:name "viewport"
                    :content "width=device-width, initial-scale=1"}]
            (include-css css)]
           body])})

(defn- app-page
  [js css request]
  (page-template
    (apply conj
      [:body
       [:div#app
        [:h3 "Clojurescript has not been compiled..."]]]
      (include-js js))
    css))

(defn- atom?
  [x]
  (instance? clojure.lang.Atom x))

(defn peer-listener
  "A peer listener holds the state need to serve an API to a set of one or more peers.
  Requires a config map.
  * :api is an API map as described below
  * :on-event a handler to receive every network event (fn [event-map] ...)
  * :on-connect a peer connect event handler (fn [peer-map] ...)
  * :on-disconnect a peer disconnect event handler (fn [peer-map] ...)
  * :on-error an error handler (fn [error] ...)
  * packet-format: websocket packet format (default: :transit-json)

  The API map contains three maps of handler functions:
  * :event handlers are triggered when a peer fires an event
  * :rpc the return value of RPC calls is sent back to the client
  * :subscription subscription handlers should return a map with two keys
    - :chan a channel that will be piped to the remote peer
    - :stop an optional function to call on unsubscribe
  )

  (defn rand-eventer
    []
    (let [c (chan)]
      (go
        ; Using while to automatically exit the go form if the channel is closed
        (while (>! c (rand-int 1000))
          (<! (async/timeout 2000))

  (peer-listener
    {:api
      {:event {'ping #'my.ns/ping}
       :rpc   {'add-two #(+ %1 %2)}
       :subscription {'rand-val (fn [] {:chan (rand-eventer)})}}
     :middleware
       {:enter [request-logger]
        :leave []}
     :on-error #(log/error %)})
  "
  [{:keys [api middleware on-event on-error on-connect on-disconnect packet-format]}]
  {:peers* (atom {})
   :api* (if (atom? api) api (atom api))
   :middleware middleware
   :on-event on-event
   :on-error (or on-error #(log/error "Error: " %))
   :on-connect on-connect
   :on-disconnect on-disconnect
   :packet-format (or packet-format :transit-json)})


(defn listen
  "Serve an API over a websocket, and optionally other resources.
  Options:
    * api-ns: a namespace (symbol) to serve as a remote API)
    * api: an API map containing {:rpc [fns] :event [fns] :subscription [fns]}
    * ws-path: endpoint to host the websocket (default: \"/connect\")
    * app-path: endpoint for a minimal app-page, which contains a single #app div
      where you can mount an SPA using files from the js and css options - the
      default is an empty path.
    * js: vector of javascript files to be served with app page
    * css: vector of css files to be served with the app page

  (listen {:api-ns 'my-app.ws-api})
  (listen {:api-ns 'my-app.ws-api :ws-path \"/api\"})
  (listen {:api api-map :port 4242})
  (listen {:api-ns 'my-app.remote.api :js [\"js/client.js\"] :css [\"css/client.css\"]})
  "
  [{:keys [api-ns api listener packet-format
           port ws-path app-path js css] :as config}]
  (let [port     (or port DEFAULT-PEER-PORT)
        ws-path  (or ws-path "connect")
        app-path (or app-path "")
        listener (cond
                   (map? listener)  listener
                   (map? api)       (peer-listener {:api api})
                   (symbol? api-ns) (peer-listener {:api (api/ns-api api-ns)})
                   :error   (throw
                              (Exception.
                                "Must supply an ns symbol, api map, or peer listener.")))
        routes   {ws-path (partial connect-listener listener)}
        routes   (if (or js css)
                   (assoc routes app-path (partial app-page js css) )
                   routes)
        app      (-> (make-handler ["/" routes])
                     (wrap-resource "public")
                     (wrap-file "resources/public" {:allow-symlinks? true}))]
    (assoc listener
           :port port
           :server (http-kit/run-server app {:port port}))))

(defn event
  [{:keys [chan] :as peer} event & args]
  (let [e {:event event :args args :id (java.util.UUID/randomUUID)}]
    (async/put! chan e)))

(defn close
  [{:keys [server peers*] :as s}]
  (disconnect-all-peers peers*)
  (when-let [server (:server s)]
    (server)
    (dissoc s :server)))


