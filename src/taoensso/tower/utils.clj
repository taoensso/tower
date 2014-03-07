(ns taoensso.tower.utils
  {:author "Peter Taoussanis"}
  (:require [clojure.string :as str]
            [markdown.core]
            [taoensso.encore :as encore]))

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

(defmacro defmem-*
  "Like `defmem-` but wraps body with `thread-local-proxy`."
  [name fn-params fn-body]
  `(defmem- ~name ThreadLocal ~fn-params
     (encore/thread-local-proxy ~fn-body)))

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
