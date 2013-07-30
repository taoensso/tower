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

(defn leaf-nodes
  "Takes a nested map and squashes it into a sequence of paths to leaf nodes.
  Based on 'flatten-tree' by James Reaves on Google Groups."
  [m]
  (if (map? m)
    (for [[k v] m
          w (leaf-nodes v)]
      (cons k w))
    (list (list m))))

(defn html-breaks [s] (str/replace s #"(\r?\n|\r)" "<br/>"))
(defn html-escape [s]
  (-> (str s)
      (str/replace "&"  "&amp;") ; First!
      (str/replace "<"  "&lt;")
      (str/replace ">"  "&gt;")
      ;;(str/replace "'"  "&#39;") ; NOT &apos;
      (str/replace "\"" "&quot;")))

(comment (html-escape "Hello, x>y & the cat's hat's fuzzy. <boo> \"Hello there\""))

(defn markdown
  [s & [{:keys [inline? auto-links?] :as opts}]]
  ;; TODO cond-> with Clojure 1.5 dep
  (let [s (str s)
        s (if-not auto-links? s (str/replace s #"https?://([\w/\.-]+)" "[$1]($0)"))
        s (if-not inline?     s (str/replace s #"(\r?\n|\r)+" " "))
        s (apply markdown.core/md-to-html-string s (reduce concat opts))
        s (if-not inline?     s (str/replace s #"^<p>(.*?)</p>$" "$1"))]
    s))

(comment (markdown "Hello *this* is a test! <tag> & thing" {:inline? true})
         (markdown "Visit http://www.cnn.com, yeah" {:auto-links? true}))

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
    (fn [resource-names]
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

(defn fq-name "Like `name` but includes namespace in string when present."
  [x] (if (string? x) x
          (let [n (name x)]
            (if-let [ns (namespace x)] (str ns "/" n) n))))

(comment (map fq-name ["foo" :foo :foo.bar/baz]))

(defn explode-keyword [k] (str/split (fq-name k) #"[\./]"))
(comment (explode-keyword :foo.bar/baz))

(defn merge-keywords [ks & [as-ns?]]
  (let [parts (->> ks (filterv identity) (mapv explode-keyword) (reduce into []))]
    (when-not (empty? parts)
      (if as-ns? ; Don't terminate with /
        (keyword (str/join "." parts))
        (let [ppop (pop parts)]
          (keyword (when-not (empty? ppop) (str/join "." ppop))
                   (peek parts)))))))

(comment (merge-keywords [:foo.bar nil :baz.qux/end nil])
         (merge-keywords [:foo.bar nil :baz.qux/end nil] true)
         (merge-keywords [:a.b.c "d.e/k"])
         (merge-keywords [:a.b.c :d.e/k])
         (merge-keywords [nil :k])
         (merge-keywords [nil]))

(defn merge-deep-with ; From clojure.contrib.map-utils
  "Like `merge-with` but merges maps recursively, applying the given fn
  only when there's a non-map at a particular level.

  (merge-deep-with + {:a {:b {:c 1 :d {:x 1 :y 2}} :e 3} :f 4}
                    {:a {:b {:c 2 :d {:z 9} :z 3} :e 100}})
  => {:a {:b {:z 3, :c 3, :d {:z 9, :x 1, :y 2}}, :e 103}, :f 4}"
  [f & maps]
  (apply
   (fn m [& maps]
     (if (every? map? maps)
       (apply merge-with m maps)
       (apply f maps)))
   maps))

;; Used by: Timbre, Tower
(def merge-deep (partial merge-deep-with (fn [x y] y)))

(comment (merge-deep {:a {:b {:c {:d :D :e :E}}}}
                     {:a {:b {:g :G :c {:c {:f :F}}}}}))