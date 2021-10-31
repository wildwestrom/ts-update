(ns ts-update.server
  (:require [buddy.auth.backends :as auth]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [clojure.string :as s]
            [org.httpkit.server :as http]
            [ring.util.response :as res]
            [ts-update.rss :refer [rss-feed]])
  (:import java.util.Base64))

(defn- byte-transform
  "Used to encode and decode strings.  Returns nil when an exception
  was raised."
  [direction-fn string]
  (try
    (s/join (map char (direction-fn (.getBytes string))))
    (catch Exception _)))

(defn- decode-base64
  "Will do a base64 decoding of a string and return a string."
  [^String string]
  (byte-transform #(.decode (Base64/getDecoder) %) string))

(defn credentials
  [request]
  (let [auth ((:headers request) "authorization")
        cred (and auth (decode-base64 (last (re-find #"^Basic (.*)$" auth))))
        [user pass] (and cred (s/split (str cred) #":" 2))]
    {:username user
     :password pass}))

(defn feed-handler
  [request]
  (let [feed (atom nil)
        creds (credentials request)]
    (reset! feed (rss-feed creds))
    (res/content-type (res/response @feed)
                      "application/rss+xml")))

;; TODO change this authfn from a stub to something working
(defn authfn
  [_ authdata]
  (:username authdata))

(def backend (auth/basic {:realm "MyApi"
                          :authfn authfn}))

(def app (-> feed-handler
             (wrap-authentication backend)
             (wrap-authorization backend)))

;; Server stuff
(defonce server (atom nil))

(defn stop []
  (when-not (nil? @server)
    (println "Stopping")
    (@server :timeout 100)
    (reset! server nil)
    (println "Stopped")))

(defn start [config]
  (stop)
  (println "Starting")
  (reset! server (http/run-server #'app config))
  (println (str "Started on port: " (:port config) ".") ))
