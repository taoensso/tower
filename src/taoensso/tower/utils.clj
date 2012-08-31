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
  "Uses regex to parse given markdown string into HTML. Doesn't do any escaping.
    **x** => <strong>x</strong>
    *x*   => <emph>x</emph>
    __x__ => <b>x</b>
    _x_   => <i>x</i>
    ~~x~~ => <span class=\"alt1\">x</span>
    ~x~   => <span class=\"alt2\">x</span>"
  [& strs]
  (-> (apply str strs)
      ;; Unescaped X is (?<!\\)X
      (str/replace #"(?<!\\)\*\*(.+?)(?<!\\)\*\*" "<strong>$1</strong>")
      (str/replace #"(?<!\\)\*(.+?)(?<!\\)\*"     "<emph>$1</emph>")
      (str/replace #"\\\*" "*") ; Unescape \*s

      (str/replace #"(?<!\\)__(.+?)(?<!\\)__" "<b>$1</b>")
      (str/replace #"(?<!\\)_(.+?)(?<!\\)__"  "<i>$1</i>")
      (str/replace #"\\\_" "_") ; Unescape \_s

      (str/replace #"(?<!\\)~~(.+?)(?<!\\)~~" "<span class=\"alt1\">$1</span>")
      (str/replace #"(?<!\\)~(.+?)(?<!\\)~"   "<span class=\"alt2\">$1</span>")
      (str/replace #"\\\~" "~") ; Unescape \~s
      ))

(comment (println "[^\\\\]") ;; Note need to double-escape (Clojure+Regex)
         (inline-markdown->html "**strong** __b__ ~~alt1~~ <tag>")
         (inline-markdown->html "*emph* _i_ ~alt2~ <tag>")
         (println "**foo\\*bar**")
         (println "**foo\\*bar**")
         (println (inline-markdown->html "**foo\\*bar**"))
         (println (inline-markdown->html "*foo\\*bar*"))
         (println (inline-markdown->html "\\*foo\\*bar*"))
         (println "This\\* is starred, and *this* is emphasized."))

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
      (let [{:keys [time-cached d-result]} (@cache args)
            now (System/currentTimeMillis)]

        (if (and time-cached (< (- now time-cached) ttl))
          @d-result
          (let [d-result (delay (apply f args))]
            (swap! cache assoc args {:time-cached now :d-result d-result})
            @d-result))))))

(defn file-resource-last-modified
  "Returns last-modified time for file backing given named resource, or nil if
  file doesn't exist."
  [resource-name]
  (when-let [^File file (try (->> resource-name io/resource io/file)
                             (catch Exception _ nil))]
    (.lastModified file)))

(def file-resource-modified?
  "Returns true iff the file backing given named resource has changed since this
  function was last called."
  (let [;; {file1 time1, file2 time2, ...}
        previous-times (atom {})]
    (fn [resource-name]
      (let [time (file-resource-last-modified resource-name)]
        (if-not (= time (get @previous-times resource-name))
          (do (swap! previous-times assoc resource-name time) true)
          false)))))

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