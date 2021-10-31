(ns ts-update.rss
  (:require [clj-http.client :as http]
            [clj-http.cookies :as cookies]
            [clj-rss.core :as rss]
            [clojure.string :as string]
            [hickory.core :as hic]
            [hickory.select :as s]
            [lambdaisland.uri :as uri :refer [uri]])
  (:import java.util.Date))

;; Constants
(def base-uri (uri "https://tealswan.com"))

(def login-uri
  (assoc base-uri
         :path "/login"))

(def premium-uri
  (assoc base-uri
         :path "/premium"))

;; Helper functions
(defn title [upd]
  (->> upd
       (s/select (s/and (s/tag :span)
                        (s/not (s/has-descendant (s/tag :i)))))
       first
       :content
       first))

(defn link [upd]
  (-> upd
      :attrs
      :href))

(defn desc [upd]
  (->> (s/select (s/tag :p) upd)
       first
       :content
       (filter string?)
       (map string/trim)
       (filter #(-> % string/blank? not))
       (string/join "\n")))

(defn get-body  [uri cs]
  (println (str "Fetching: " uri))
  (-> uri
      str
      (http/get {:cookie-store cs})
      :body))

(defn get-hickory-data [uri cs]
  (-> uri
      (get-body cs)
      hic/parse
      hic/as-hickory))

(defn csrf-key [cs]
  (-> (s/select (s/attr :name #(= % "csrfKey"))
                (get-hickory-data login-uri cs))
      first
      :attrs
      :value))

(defn log-in [username password cs]
  (let [logged-in? (atom false)
        res        (http/post
                    (str login-uri)
                    {:cookie-store     cs
                     :form-params      {:csrfKey       (csrf-key cs)
                                        :auth          username
                                        :password      password
                                        :remember_me   "1"
                                        :_processLogin "usernamepassword"}
                     :throw-exceptions false})]
    (condp = (:status res)
      301 (reset! logged-in? true)
      403 (reset! logged-in? true)
      (reset! logged-in? false))
    @logged-in?))

(defn get-premium-page! [page cs]
  (reset! page (get-hickory-data premium-uri cs)))

(defn latest-updates [page]
  (->> page
       (s/select
        (s/and (s/tag :div)
               (s/id "newonpremium")
               (s/class "related-videos")))
       first
       :content
       (filter map?)))

(defn pub-date [uri cs]
  (-> (let [data (get-hickory-data uri cs)]
        (s/select (s/descendant
                   (s/and (s/tag :div)
                          (s/class "postheaderdeets"))
                   (s/tag :time)) data))
      first
      :attrs
      :datetime
      java.time.Instant/parse
      Date/from))

(defn rss-channel []
  {:title         "Teal Swan Premium"
   :link          (str premium-uri)
   :description   "Daily updates and insights from Teal Swan's premium content."
   :lastBuildDate (Date.)})

(defn build-feed [premium-page cs]
  (apply rss/channel-xml
         (cons (rss-channel)
               (pmap (fn [upd] {:title       (title upd)
                                :link        (link upd)
                                :description (desc upd)
                                :guid        (link upd)
                                :pubDate     (pub-date (uri (link upd)) cs)})
                     (latest-updates premium-page)))))

(defn rss-feed
  "Put in a username and password, get an RSS Feed."
  [{:keys [username password]}]
  (let [cs                (cookies/cookie-store)
        premium-page      (atom nil)]
    (if (log-in username password cs)
      (do (get-premium-page! premium-page cs)
          (build-feed @premium-page cs))
      (rss/channel-xml
       (rss-channel)
       {:title "Failed"
        :description "Incorrect login credentials. Can't log in to premium account."}))))
