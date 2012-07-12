(ns taoensso.tower.utils
  {:author "Peter Taoussanis"}
  (:require [clojure.string :as str]
            [clojure.java.io :as io])
  (:import  [java.io File]))

(defn leaf-paths
  "Takes a nested map and squashes it into a sequence of paths to leaf nodes.
  Based on 'flatten-tree' by James Reaves on Google Groups.

  (leaf-map {:a {:b {:c \"c-data\" :d \"d-data\"}}}) =>
  ((:a :b :c \"c-data\") (:a :b :d \"d-data\"))"
  [map]
  (if (map? map)
    (for [[k v] map
          w (leaf-paths v)]
      (cons k w))
    (list (list map))))

(defn escape-html
  "Changes some common special characters into HTML character entities."
  [s]
  (-> (str s)
      (str/replace "&"  "&amp;")
      (str/replace "<"  "&lt;")
      (str/replace ">"  "&gt;")
      (str/replace "\"" "&quot;")))

(comment (escape-html "\"Word\" & <tag>"))

(defn inline-markdown->html
  "Uses regex to parse given markdown string into HTML. Supports only strong,
  emph, and a context-spexific alternative style tag. Doesn't do any escaping."
  [markdown]

  (-> markdown
      (str/replace #"\*\*(.+?)\*\*" "<strong>$1</strong>")
      (str/replace #"__(.+?)__"     "<strong>$1</strong>")
      (str/replace #"\*(.+?)\*"     "<emph>$1</emph>")
      (str/replace #"_(.+?)_"       "<emph>$1</emph>")

      ;; "alt" span (define in surrounding CSS scope)
      (str/replace #"~~(.+?)~~" "<span class=\"alt\">$1</span>")))

(comment (inline-markdown->html "**strong** *emph* ~~alt~~ <tag>"))

(defmacro defmem-
  "Defines a type-hinted, private memoized fn."
  [name type-hint fn-params fn-body]
  ;; To allow type-hinting, we'll actually wrap a closed-over memoized fn
  `(let [memfn# (memoize (~'fn ~fn-params ~fn-body))]
     (defn ~(with-meta (symbol name) {:private true})
       ~(with-meta '[& args] {:tag type-hint})
       (apply memfn# ~'args))))

(defn memoize-ttl
  "Like `memoize` but invalidates the cache for a set of arguments after TTL
  msecs has elapsed."
  [ttl f]
  (let [cache (atom {})]
    (fn [& args]
      (let [{:keys [time-cached d-result]} (get @cache args)
            now (System/currentTimeMillis)]

        (if (and time-cached (< (- now time-cached) ttl))
          @d-result
          (let [d-result (delay (apply f args))]
            (swap! cache assoc args {:time-cached now :d-result d-result})
            @d-result))))))

(def some-file-resources-modified?
  "Returns true iff any of the files backing given named resources have changed
  since this function was last called. Ignores invalid files."
  (let [times (atom {})]
    (fn modified?
      ([resource-name & more] (seq (filter modified? (cons resource-name more)))
         (some modified? (cons resource-name more)))
      ([resource-name]
         (when-let [^File file (try (->> resource-name io/resource io/file)
                                    (catch Exception _ nil))]
           (let [last-modified (.lastModified file)]
             (let [file-name (str file)
                   modified? (> last-modified (@times file-name 0))]
               (when modified? (swap! times assoc file-name last-modified))
               modified?)))))))

(defn parse-http-accept-header
  "Parses HTTP Accept header and returns sequence of [choice weight] pairs
  sorted by weight."
  [header]
  (->> (for [choice (->> (str/split (str header) #",")
                         (filter (complement str/blank?)))]
         (let [[lang q] (str/split choice #";")]
           [(-> lang str/trim)
            (or (when q (Float/parseFloat (second (str/split q #"="))))
                1)]))
       (sort-by second) reverse))

(comment (parse-http-accept-header nil)
         (parse-http-accept-header "en-GB")
         (parse-http-accept-header "en-GB,en;q=0.8,en-US;q=0.6")
         (parse-http-accept-header "en-GB  ,  en; q=0.8, en-US;  q=0.6")
         (parse-http-accept-header "a,"))