# Tower, a simple internationalization (i18n) library for Clojure.

### WARNING: This is just a prototype. It may disappear at any moment!

### Quick + Dirty Example
```clojure
(ns my-app (:use [tower.core :as tower :only (t)]))

(def translations-db
  (atom
   (tower/map->translations-db
    :en ; Default locale
    {:en {:root {:welcome             "Hello World!"
                 :welcome.note        "This is a note to translators!"
                 :some-markdown.md    "**This is strong**"
                 :some-html.html      "<strong>This is also strong</strong>"
                 :something-to-escape "This is <html tag> safe!"}}
     :en_US {:root {:welcome          "Hello World! Color doesn't have a 'u'!"}}
     :de    {:root {:welcome          "Hallo Welt!"}}})))


(tower/with-i18n {:locale (parse-locale "en")
                  :translations-db   translations-db
                  :translation-scope :root}
  [(t :welcome)
   (t :some-markdown)
   (t :some-html)
   (t :something-to-escape)])

=> ["Hello World!" "<strong>This is strong</strong>" "<strong>This is also strong</strong>" "This is &lt;html tag&gt; safe!"]

(with-i18n {:locale (parse-locale "de_CH")
            :translations-db   translations-db
            :translation-scope :root
            :dev-mode?         false}
  [(t :welcome)
   (t :some-html)
   (t :invalid)])

=> ["Hallo Welt!" "<strong>This is also strong</strong>" ""]

```

TODO: README