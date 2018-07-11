(ns refactor-nrepl.artifacts
  (:require [alembic.still :as alembic]
            [cheshire.core :as json]
            [clojure
             [edn :as edn]
             [string :as str]]
            [clojure.java.io :as io]
            [clojure.tools.namespace.find :as find]
            [org.httpkit.client :as http]
            [refactor-nrepl.ns.slam.hound.search :as slamhound]
            [refactor-nrepl.ns.slam.hound.regrow :as slamhound-regrow]
            [version-clj.core :as versions])
  (:import java.util.Date
           java.util.jar.JarFile))

;;  structure here is {"prismatic/schem" ["0.1.1" "0.2.0" ...]}
(defonce artifacts (atom {} :meta {:last-modified nil}))
(def millis-per-day (* 24 60 60 1000))

(defn- get-proxy-opts
  "Generates proxy options from JVM properties for httpkit-client "
  []
  (when-let [proxy-host (some #(System/getProperty %) ["https.proxyHost" "http.proxyHost"])]
    {:proxy-host proxy-host
     :proxy-port (some->> ["https.proxyPort" "http.proxyPort"]
                          (some #(System/getProperty %)) Integer/parseInt)}))

(defn- stale-cache?
  []
  (or (empty? @artifacts)
      (if-let [last-modified (some-> artifacts meta :last-modified .getTime)]
        (neg? (- millis-per-day (- (.getTime (java.util.Date.)) last-modified)))
        true)))

(defn get-clojars-artifacts!
  "Returns a vector of [[some/lib \"0.1\"]...]."
  []
  (try
    (->> "https://clojars.org/repo/all-jars.clj"
         java.net.URL.
         io/reader
         line-seq
         (map edn/read-string))
    (catch Exception _
      ;; In the event clojars is down just return an empty vector. See #136.
      [])))

(defn- get-mvn-artifacts!
  "All the artifacts under org.clojure in mvn central"
  [group-id]
  (let [search-prefix "http://search.maven.org/solrsearch/select?q=g:%22"
        search-suffix "%22+AND+p:%22jar%22&rows=2000&wt=json"
        search-url (str search-prefix group-id search-suffix)
        {:keys [_ _ body _]} @(http/get search-url (assoc (get-proxy-opts) :as :text))
        search-result (json/parse-string body true)]
    (map :a (-> search-result :response :docs))))

(defn- get-mvn-versions!
  "Fetches all the versions of particular artifact from maven repository."
  [for-artifact]
  (let [[group-id artifact] (str/split for-artifact #"/")
        search-prefix "http://search.maven.org/solrsearch/select?q=g:%22"
        {:keys [_ _ body _]} @(http/get (str search-prefix
                                             group-id
                                             "%22+AND+a:%22"
                                             artifact
                                             "%22&core=gav&rows=100&wt=json")
                                        (assoc (get-proxy-opts) :as :text))]
    (->> (json/parse-string body true)
         :response
         :docs
         (map :v))))

(defn- get-artifacts-from-mvn-central!
  []
  (let [group-ids #{"com.cognitect" "org.clojure"}]
    (mapcat (fn [group-id]
              (->> (get-mvn-artifacts! group-id)
                   (map #(vector (str group-id "/" %) nil))))
            group-ids)))

(defn- get-artifacts-from-clojars!
  []
  (reduce #(update %1 (str (first %2)) conj (second %2))
          (sorted-map)
          (get-clojars-artifacts!)))

(defn- update-artifact-cache!
  []
  (let [clojars-artifacts (future (get-artifacts-from-clojars!))
        maven-artifacts (future (get-artifacts-from-mvn-central!))]
    (reset! artifacts (into @clojars-artifacts @maven-artifacts))
    (alter-meta! artifacts update-in [:last-modified] (constantly (java.util.Date.)))))

(defn artifact-list
  [{:keys [force]}]
  (when (or (= force "true") (stale-cache?))
    (update-artifact-cache!))
  (->> @artifacts keys list*))

(defn artifact-versions
  [{:keys [artifact]}]
  (->> (or (get @artifacts artifact)
           (get-mvn-versions! artifact))
       distinct
       versions/version-sort
       reverse
       list*))

(defn- make-resolve-missing-aware-of-new-deps
  "Once the deps are available on cp we still have to load them and
  reset slamhound's cache to make resolve-missing work."
  [coords repos]
  (let [dep (->> (alembic/resolve-dependencies alembic/the-still coords repos nil)
                 (some (fn [dep] (when (= (:coords dep) (first coords)) dep))))
        jarfile (JarFile. (:jar dep))]
    (doseq [namespace (find/find-namespaces-in-jarfile jarfile)]
      (try
        (require namespace)
        (catch Exception _
          ;; I've seen this happen after adding core.async as a dependency.
          ;; It also happens if you try to require namespaces that no longer work,
          ;; like compojure.handler.
          ;; A failure here isn't a big deal, it only means that resolve-missing
          ;; isn't going to work until the namespace has been loaded manually.
          )))
    (slamhound/reset)
    (slamhound-regrow/clear-cache!)))

(defn- ensure-quality-coordinates [coordinates]
  (let [coords (->> coordinates read-string (take 2) vec)]
    (when-not (= (count coords) 2)
      (throw (IllegalArgumentException. (str "Malformed dependency vector: "
                                             coordinates))))
    (when (stale-cache?)
      (update-artifact-cache!))
    (if-let [versions (get @artifacts (str (first coords)))]
      (when-not ((set versions) (second coords))
        (throw (IllegalArgumentException.
                (str "Version " (second coords)
                     " does not exist for " (first coords)
                     ". Available versions are " (pr-str (vec versions))))))
      (throw (IllegalArgumentException. (str "Can't find artifact '"
                                             (first coords) "'"))))))

(defn distill [coords repos]
  ;; Just so we can mock this out during testing
  (alembic/distill coords :repositories repos))

(defn hotload-dependency
  [{:keys [coordinates]}]
  (let [dependency-vector (edn/read-string coordinates)
        coords [(->> dependency-vector (take 2) vec)]
        repos {"clojars" "http://clojars.org/repo"
               "central" "http://repo1.maven.org/maven2/"}]
    (ensure-quality-coordinates coordinates)
    (distill coords repos)
    (make-resolve-missing-aware-of-new-deps coords repos)
    (str/join " " dependency-vector)))
