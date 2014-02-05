**[API docs](http://ptaoussanis.github.io/tower/)** | **[CHANGELOG](https://github.com/ptaoussanis/tower/blob/master/CHANGELOG.md)** | [contact & contributing](#contact--contributing) | [other Clojure libs](https://www.taoensso.com/clojure-libraries) | [Twitter](https://twitter.com/#!/ptaoussanis) | current [semantic](http://semver.org/) version:

```clojure
[com.taoensso/tower "2.0.2"] ; Stable, see CHANGELOG for details
```

Special thanks to **Janne Asmala** ([GitHub](https://github.com/asmala) & [Twitter](https://twitter.com/janne_asmala)) for his awesome contributions to Tower's v2 design. He also has an i18n/L10n lib called [clj18n](https://github.com/asmala/clj18n) which is definitely worth checking out!

# Tower, a Clojure i18n & L10n library

The Java platform provides some very capable tools for writing internationalized applications. Unfortunately, they can be... cumbersome. We can do much better in Clojure.

Tower's an attempt to present a **simple, idiomatic internationalization and localization** story for Clojure. It wraps standard Java functionality where possible - with warm, fuzzy, functional love. It does go in its own direction for translation, but I suspect you'll like the direction it goes.

## What's in the box™?
  * Small, uncomplicated **all-Clojure** library.
  * Ridiculously simple, high-performance wrappers for standard Java **localization features**.
  * Rails-like, all-Clojure **translation function** (incl. experimental ClojureScript support).
  * **Simple, map-based** translation dictionary format. No XML or resource files!
  * Automatic dev-mode **dictionary reloading** for rapid REPL development.
  * Seamless **markdown support** for translators.
  * **Ring middleware**.
  * TODO: export/import to allow use with **industry-standard tools for translators**.

## Getting started

### Dependencies

Add the necessary dependency to your [Leiningen](http://leiningen.org/) `project.clj` and `require` the library in your ns:

```clojure
[com.taoensso/tower "2.0.2"] ; project.clj
(ns my-app (:require [taoensso.tower :as tower
                      :refer (with-locale with-tscope *locale*)])) ; ns
```

### Translation

The `make-t` fn handles translations. You give it a config map which includes your dictionary, and get back a `(fn [locale k-or-ks & fmt-args]):

```clojure
(def my-tconfig
  {:dictionary ; Map or named resource containing map
   {:en   {:example {:foo         ":en :example/foo text"
                     :foo_comment "Hello translator, please do x"
                     :bar {:baz ":en :example.bar/baz text"}
                     :greeting "Hello %s, how are you?"
                     :inline-markdown "<tag>**strong**</tag>"
                     :block-markdown* "<tag>**strong**</tag>"
                     :with-exclaim!   "<tag>**strong**</tag>"
                     :greeting-alias :example/greeting
                     :baz-alias      :example.bar/baz}
           :missing  "<Missing translation: [%1$s %2$s %3$s]>"}
    :en-US {:example {:foo ":en-US :example/foo text"}}
    :de    {:example {:foo ":de :example/foo text"}}
    :ja "test_ja.clj" ; Import locale's map from external resource
    }
   :dev-mode? true ; Set to true for auto dictionary reloading
   :fallback-locale :de
   :scope-fn  (fn [k] (scoped *tscope* k)) ; Experimental, undocumented
   :fmt-fn fmt-str ; (fn [loc fmt args])
   :log-missing-translation-fn
   (fn [{:keys [locale ks scope] :as args}]
     (timbre/logp (if dev-mode? :debug :warn)
       "Missing translation" args))})

(def t (tower/make-t my-tconfig)) ; Create translation fn

(t :en-US :example/foo) => ":en-US :example/foo text"
(t :en    :example/foo) => ":en :example/foo text"
(t :en    :example/greeting "Steve") => "Hello Steve, how are you?"

;;; Translation strings are escaped and parsed as inline or block Markdown:
(t :en :example/inline-markdown) => "&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;"
(t :en :example/block-markdown)  => "<p>&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;</p>" ; Notice no "*" suffix here, only in dictionary map
(t :en :example/with-exclaim)    => "<tag>**strong**</tag>" ; Notice no "!" suffix here, only in dictionary map
```

It's simple to get started, but there's a number of advanced features for if/when you need them:

**Loading dictionaries from disk/resources**: Just use a string for the `:dictionary` and/or any locale value(s) in your config map. Be sure to check that the appropriate files are available on your classpath or one of Leiningen's resource paths (e.g. `resources/`).

**Reloading dictionaries on modification**: Enable the `:dev-mode?` option and you're good to go!

**Scoping translations**: Use `with-tscope` if you're calling `t` repeatedly within a specific translation-namespace context:
```clojure
(with-tscope :example
  [(t :en :foo)
   (t :en :bar/baz)]) => [":en :example/foo text" ":en :example.bar/baz text"]
```

**Missing translations**: These are handled gracefully. `(t :en-US :example/foo)` will search for a translation as follows:
  1. `:example/foo` in the `:en-US` locale.
  2. `:example/foo` in the `:en` locale.
  3. `:example/foo` in the dictionary's fallback locale.
  4. `:missing` in any of the above locales.

You can also specify fallback keys that'll be tried before other locales. `(t :en-US [:example/foo :example/bar]))` searches:
  1. `:example/foo` in the `:en-US` locale.
  2. `:example/bar` in the `:en-US` locale.
  3. `:example/foo` in the `:en` locale.
  4. `:example/bar` in the `:en` locale.
  5. `:example/foo` in the fallback locale.
  6. `:example/bar` in the fallback locale.
  7. `:missing` in any of the above locales.

In all cases, translation requests are logged upon fallback to fallback locale or :missing key.

**ClojureScript translation support**: This is still **experimental**!

```clojure
(ns my-clojurescript-ns
  (:require [cljs.taoensso.tower :as tower :include-macros true]))

(def ^:private tconfig
  {:fallback-locale :en
   ;; Inlined (macro) dict => this ns needs rebuild for dict changes to reflect:
   :compiled-dictionary (tower/dict-compile "my-dict.clj")})

(def t (partial (tower/make-t tconfig) (:locale init-state)))
```

There's two notable differences from the JVM translator:
1. The dictionary is provided in a _pre-compiled_ form so that it can be inlined directly into your Cljs.
2. Since we lack a locale-aware Cljs `format` fn, your translations _cannot_ use JVM locale formatting patterns.

The API is otherwise exactly the same, including support for all decorators.

### Localization

Check out `fmt`, `parse`, `lsort`, `fmt-str`, `fmt-msg`:
```clojure
(tower/fmt   :en-ZA 200       :currency) => "R 200.00"
(tower/fmt   :en-US 200       :currency) => "$200.00"
(tower/parse :en-US "$200.00" :currency) => 200

(tower/fmt :de-DE 2000.1 :number)               => "2.000,1"
(tower/fmt :de-DE (java.util.Date.))            => "12.06.2012"
(tower/fmt :de-DE (java.util.Date.) :date-long) => "12. Juni 2012"
(tower/fmt :de-DE (java.util.Date.) :dt-long)   => "12 giugno 2012 16.48.01 ICT"

(tower/lsort :pl ["Warsaw" "Kraków" "Łódź" "Wrocław" "Poznań"])
=> ("Kraków" "Łódź" "Poznań" "Warsaw" "Wrocław")

(mapv #(tower/fmt-msg :de "{0,choice,0#no cats|1#one cat|1<{0,number} cats}" %)
        (range 5))
=> ["no cats" "one cat" "2 cats" "3 cats" "4 cats"]
```

Yes, seriously- it's that simple. See the appropriate docstrings for details.

### Country and languages names, timezones, etc.

Check out `countries`, `languages`, and `timezones`.

### Ring middleware

Quickly internationalize your Ring web apps by adding `taoensso.tower.ring/wrap-tower` to your middleware stack.

It'll select the best available locale for each request then establish a thread-local locale binding with `tower/*locale*`, and add `:locale` and `:t` request keys.

See the docstring for details.

## This project supports the CDS and ![ClojureWerkz](https://raw.github.com/clojurewerkz/clojurewerkz.org/master/assets/images/logos/clojurewerkz_long_h_50.png) goals

  * [CDS](http://clojure-doc.org/), the **Clojure Documentation Site**, is a **contributer-friendly** community project aimed at producing top-notch, **beginner-friendly** Clojure tutorials and documentation. Awesome resource.

  * [ClojureWerkz](http://clojurewerkz.org/) is a growing collection of open-source, **batteries-included Clojure libraries** that emphasise modern targets, great documentation, and thorough testing. They've got a ton of great stuff, check 'em out!

## Contact & contribution

Please use the [project's GitHub issues page](https://github.com/ptaoussanis/tower/issues) for project questions/comments/suggestions/whatever **(pull requests welcome!)**. Am very open to ideas if you have any!

Otherwise reach me (Peter Taoussanis) at [taoensso.com](https://www.taoensso.com) or on Twitter ([@ptaoussanis](https://twitter.com/#!/ptaoussanis)). Cheers!

## License

Copyright &copy; 2012, 2013 Peter Taoussanis. Distributed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html), the same as Clojure.
