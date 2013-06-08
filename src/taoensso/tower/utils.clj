(ns taoensso.tower.utils
  {:author "Peter Taoussanis"}
  (:require [clojure.string      :as str]
            [clojure.java.io     :as io]
            [clojure.tools.macro :as macro])
  (:import  [java.io File]))

(defmacro defonce*
  "Like `clojure.core/defonce` but supports optional docstring and attributes
  map for name symbol."
  {:arglists '([name expr])}
  [name & sigs]
  (let [[name [expr]] (macro/name-with-attributes name sigs)]
    `(clojure.core/defonce ~name ~expr)))

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

(defn take-until
  "Like `take-while` but always takes first item from coll."
  ([pred coll] (take-until pred coll true))
  ([pred coll first?]
     (lazy-seq
      (when-let [s (seq coll)]
        (when (or first? (pred (first s)))
          (cons (first s) (take-until pred (rest s) false)))))))

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
    *x*   => <em>x</em>
    __x__ => <b>x</b>
    _x_   => <i>x</i>
    ~~x~~ => <span class=\"alt1\">x</span>
    ~x~   => <span class=\"alt2\">x</span>"
  [& strs]
  (-> (apply str strs)
      ;; Unescaped X is (?<!\\)X
      (str/replace #"(?<!\\)\*\*(.+?)(?<!\\)\*\*" "<strong>$1</strong>")
      (str/replace #"(?<!\\)\*(.+?)(?<!\\)\*"     "<em>$1</em>")
      (str/replace #"\\\*" "*") ; Unescape \*s

      (str/replace #"(?<!\\)__(.+?)(?<!\\)__" "<b>$1</b>")
      (str/replace #"(?<!\\)_(.+?)(?<!\\)_"   "<i>$1</i>")
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
                             (catch Exception _))]
    (.lastModified file)))

(def file-resources-modified?
  "Returns true iff any files backing the given group of named resources
  have changed since this function was last called."
  (let [;; {#{file1A file1B ...#} (time1A time1A ...),
        ;;  #{file2A file2B ...#} (time2A time2B ...), ...}
        group-times (atom {})]
    (fn [& resource-names]
      (let [file-group (into (sorted-set) resource-names)
            file-times (map file-resource-last-modified file-group)
            last-file-times (get @group-times file-group)]
        (when-not (= file-times last-file-times)
          (swap! group-times assoc file-group file-times)
          (boolean last-file-times))))))

(defn parse-http-accept-header
  "Parses HTTP Accept header and returns sequence of [choice weight] pairs
  sorted by weight."
  [header]
  (->> (for [choice (->> (str/split (str header) #",")
                         (remove str/blank?))]
         (let [[lang q] (str/split choice #";")]
           [(str/trim lang)
            (or (when q (Float/parseFloat (second (str/split q #"="))))
                1)]))
       (sort-by second >)))

(comment (parse-http-accept-header nil)
         (parse-http-accept-header "en-GB")
         (parse-http-accept-header "en-GB,en;q=0.8,en-US;q=0.6")
         (parse-http-accept-header "en-GB  ,  en; q=0.8, en-US;  q=0.6")
         (parse-http-accept-header "a,")
         (parse-http-accept-header "es-ES, en-US"))

(defn deep-merge-with ; From clojure.contrib.map-utils
  "Like `merge-with` but merges maps recursively, applying the given fn
  only when there's a non-map at a particular level.

  (deepmerge-with + {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
                    {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}})
  => {:a {:b {:z 3, :c 3, :d {:z 9, :x 1, :y 2}}, :e 103}, :f 4}"
  [f & maps]
  (apply
   (fn m [& maps]
     (if (every? map? maps)
       (apply merge-with m maps)
       (apply f maps)))
   maps))

(def deep-merge (partial deep-merge-with (fn [x y] y)))

(comment (deep-merge {:a {:b {:c {:d :D :e :E}}}}
                     {:a {:b {:g :G :c {:c {:f :F}}}}}))