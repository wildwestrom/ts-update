(ns ts-update.core
  (:gen-class)
  (:require [clj-http.client :as http]
            [clj-http.cookies :as cookies]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [hickory.core :as hic]
            [hickory.select :as s]
            [tarayo.core :as tarayo]))

(defn load-config
  []
  (let [config-file (io/file (str (java.lang.System/getProperty "user.home")
                                  "/.config/ts-update/config.edn"))]
    (assert (.exists config-file))
    (edn/read-string (slurp config-file))))

(defonce config-file (atom (load-config)))

(defn get-csrf-key
  [body]
  (-> (s/select (s/attr :name #(= % "csrfKey"))
                (-> body
                    hic/parse
                    hic/as-hickory))
      first
      :attrs
      :value))

(defn get-premium-page
  [username password]
  (let [cs (cookies/cookie-store)
        csrf-key (get-csrf-key (:body (http/get "https://tealswan.com/login" {:cookie-store cs})))]
    (http/post "https://tealswan.com/login/"
               {:cookie-store cs
                :form-params {:csrfKey csrf-key
                              :auth username
                              :password password
                              :remember_me "1"
                              :_processLogin "usernamepassword"}})
    (:body (http/get "https://tealswan.com/premium/" {:cookie-store cs}))))

(defn get-hickory-data
  [resp-body]
  (-> resp-body
      hic/parse
      hic/as-hickory))

(defonce current-update (atom nil))
(defonce last-update (atom nil))

(defn get-latest-update!
  [{:keys [username password]}]
  (let [latest-update (-> (s/select
                           (s/follow
                            (s/and (s/tag :span)
                                   (s/class "related-title")
                                   (s/find-in-text #"Newest Videos"))
                            (s/tag :a))
                           (get-hickory-data (get-premium-page
                                              username
                                              password)))
                          first)
        title (-> (s/select (s/tag :h1) latest-update)
                  first
                  :content
                  first)
        link (-> latest-update
                 :attrs
                 :href)
        desc (->> (s/select (s/tag :section) latest-update)
                  first
                  :content
                  (filter string?)
                  (map string/trim)
                  (filter #(-> % string/blank? not))
                  (string/join "\n"))]
    (reset! current-update
            {:title title
             :desc desc
             :link link})))

(defn message-text
  [{:keys [title desc link]}]
  (str "Teal Swan just released a new video on premium!\n\n"
       "Title: " title  "\n"
       desc  "\n"
       link))

;; `sudo postfix start` on my mac seemed to do the trick
(defn send-update
  [message]
  (with-open [conn (tarayo/connect {:host "smtp.gmail.com" :port 587
                                    :starttls.enable true
                                    :user "c.westrom92@gmail.com"
                                    :password "jbcesxnufhvyrrhl"})]
    (tarayo/send! conn {:from "c.westrom92@gmail.com"
                        :to (:send-to @config-file)
                        :subject (str "New Premium Content! " (str (java.time.LocalDateTime/now)))
                        :body message})))

(defn send! []
  (when (not= @current-update @last-update)
    (send-update (message-text @current-update))))

(defn update! []
  (reset! last-update @current-update)
  (get-latest-update! {:username (:ts-username @config-file)
                       :password (:ts-password @config-file)})
  (send!))

(defn -main []
  (loop []
    (update!)
    (Thread/sleep (* 1000 60 15))
    (recur)))
