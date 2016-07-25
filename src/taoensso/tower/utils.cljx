(ns taoensso.tower.utils
  {:author "Peter Taoussanis"}
  (:require [clojure.string  :as str]
            [markdown.core   :as md-core]
            [taoensso.encore :as enc]))

(defn distinctv
  ([      coll] (distinctv identity coll))
  ([keyfn coll]
   (let [tr (reduce (fn [[v seen] in]
                      (let [in* (keyfn in)]
                        (if-not (contains? seen in*)
                          [(conj! v in) (conj seen in*)]
                          [v seen])))
              [(transient []) #{}]
              coll)]
     (persistent! (nth tr 0)))))

(defn leaf-nodes ; From u-core
  "Takes a nested map and squashes it into a sequence of paths to leaf nodes.
  Based on 'flatten-tree' by James Reaves on Google Groups."
  [m]
  (if (map? m)
    (for [[k v] m
          w (leaf-nodes v)]
      (cons k w))
    (list (list m))))

;;; From u-core
(defn html-breaks [s] (-> s (str/replace #"(\r?\n|\r)" "<br/>")))
(defn html-spaces [s] (-> s (str/replace #"(\r?\n|\r)" "<br/>")
                            (str/replace #"\t"   "&nbsp;&nbsp;")
                            (str/replace #"\s\s" " &nbsp;")))
(defn html-escape [s]
  (-> s
      (str/replace "&"  "&amp;") ; First!
      (str/replace "<"  "&lt;")
      (str/replace ">"  "&gt;")
      ;; (str/replace "'"  "&#39;") ; NOT &apos;
      (str/replace "\"" "&quot;")))

(comment (html-escape "Hello, x>y & the cat's hat's fuzzy. <boo> \"Hello there\""))

(defn markdown ; From u-core
  ([s] (markdown {} s))
  ([{:keys [inline? auto-links?] :as opts} s]
     (let [s (str s) ; No html-escaping!
           s (if-not auto-links? s (str/replace s #"https?://([\w/\.-]+)" "[$1]($0)"))
           s (if-not inline?     s (str/replace s #"(\r?\n|\r)+" " "))
           s (enc/mapply
               #+clj  md-core/md-to-html-string
               #+cljs md-core/mdToHtml
               s
               opts)
           s (if-not inline?     s (str/replace s #"^<p>(.*?)</p>$" "$1"))]
       s)))

(comment (markdown {:inline?     true} "Hello *this* is a test! <tag> & thing")
         (markdown {:auto-links? true} "Visit http://www.cnn.com, yeah"))

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
     (enc/thread-local-proxy ~fn-body)))

(defn parse-http-accept-header
  "Parses HTTP Accept header and returns sequence of [choice weight] pairs
  sorted by weight."
  [header]
  (sort-by second enc/rcompare
    (for [choice (remove str/blank? (str/split (str header) #","))]
      (let [[lang q] (str/split choice #";")]
        [(str/trim lang)
         (or (when q (enc/as-?float (get (str/split q #"=") 1)))
             1)]))))

(comment (parse-http-accept-header nil)
         (parse-http-accept-header "en-GB")
         (parse-http-accept-header "en-GB,en;q=0.8,en-US;q=0.6")
         (parse-http-accept-header "en-GB  ,  en; q=0.8, en-US;  q=0.6")
         (parse-http-accept-header "a,")
         (parse-http-accept-header "es-ES, en-US"))
