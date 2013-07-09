**[API docs](http://ptaoussanis.github.io/tower/)** | **[CHANGELOG](https://github.com/ptaoussanis/tower/blob/master/CHANGELOG.md)** | [contact & contributing](#contact--contributing) | [other Clojure libs](https://www.taoensso.com/clojure-libraries) | [Twitter](https://twitter.com/#!/ptaoussanis) | current [semantic](http://semver.org/) version:

```clojure
[com.taoensso/tower "2.0.0-alpha10"] ; Development (notes below)
[com.taoensso/tower "1.7.1"]        ; Stable, needs Clojure 1.4+ as of 1.7.0
```

v2 provides an improved API. It is a **BREAKING** release for `t` users. See the [CHANGELOG](https://github.com/ptaoussanis/tower/blob/master/CHANGELOG.md) for migration details. Note that the examples in this README are for the v2 API. See [here](https://github.com/ptaoussanis/tower/blob/master/v1-examples.md) for v1 examples.

Special thanks to **Janne Asmala** ([GitHub](https://github.com/asmala) & [Twitter](https://twitter.com/janne_asmala)) for his awesome contributions to Tower's v2 design. He also has an i18n/L10n lib called [clj18n](https://github.com/asmala/clj18n) which is definitely worth checking out!

# Tower, a Clojure i18n & L10n library

The Java platform provides some very capable tools for writing internationalized applications. Unfortunately, they can be... cumbersome. We can do much better in Clojure.

Tower's an attempt to present a **simple, idiomatic internationalization and localization** story for Clojure. It wraps standard Java functionality where possible - with warm, fuzzy, functional love. It does go in its own direction for translation, but I suspect you'll like the direction it goes.

## What's in the box™?
  * Small, uncomplicated **all-Clojure** library.
  * Ridiculously simple, high-performance wrappers for standard Java **localization features*.
  * Rails-like, all-Clojure **translation function**.
  * **Simple, map-based** translation dictionary format. No XML or resource files!
  * Automatic dev-mode **dictionary reloading** for rapid REPL development.
  * Seamless **markdown support** for translators.
  * **Ring middleware**.
  * TODO: export/import to allow use with **industry-standard tools for translators**.

## Getting started

### Dependencies

Add the necessary dependency to your [Leiningen](http://leiningen.org/) `project.clj` and `require` the library in your ns:

```clojure
[com.taoensso/tower "2.0.0-alpha10"] ; project.clj
(ns my-app (:require [taoensso.tower :as tower
                      :refer (with-locale with-scope t *locale*)])) ; ns
```

### Translation

The `t` fn handles translations. You give it a config map which includes your dictionary, and you're ready to go:
```clojure
(def my-tconfig
  {:dev-mode?      true
   :default-locale :en
   :dictionary
   {:en         {:example {:foo       ":en :example/foo text"
                           :foo_note  "Hello translator, please do x"
                           :bar {:baz ":en :example.bar/baz text"}
                           :greeting  "Hello {0}, how are you?"
                           :with-markdown "<tag>**strong**</tag>"
                           :with-exclaim! "<tag>**strong**</tag>"
                           :greeting-alias :example/greeting
                           :baz-alias      :example.bar/baz}
                 :missing  "<Translation missing: {0}>"}
    :en-US      {:example {:foo ":en-US :example/foo text"}}
    :en-US-var1 {:example {:foo ":en-US-var1 :example/foo text"}}}

   :log-missing-translation-fn (fn [{:keys [dev-mode? locale ks]}] ...)})

(t :en-US :example/foo) => ":en-US :example/foo text"
(t :en    :example/foo) => ":en :example/foo text"
(t :en    :example/greeting "Steve") => "Hello Steve, how are you?"

;;; Translation strings are escaped and parsed as inline Markdown:
(t :en :example/with-markdown) => "&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;"
(t :en :example/with-exclaim)  => "<tag>**strong**</tag>" ; Notice no "!" suffix here, only in dictionary map
```

It's simple to get started, but there's a number of advanced features for if/when you need them:

**Loading dictionaries from disk/resources**: Just use a string for the `:dictionary` value in your config map. For example, `:dictionary "my-dictionary.clj"` will load a dictionary map from the "my-dictionary.clj" file on your classpath or one of Leiningen's resource paths (e.g. `resources/`).

**Reloading dictionaries on modification**: Just make sure `:dev-mode? true` is in your config, and you're good to go!

**Scoping translations**: Use `with-scope` if you're calling `t` repeatedly within a specific translation-namespace context:
```clojure
(with-scope :example
  [(t :en :foo)
   (t :en :bar/baz)]) => [":en :example/foo text" ":en :example.bar/baz text"]
```

**Missing translations**: These are handled gracefully. `(t :en-US :example/foo)` will search for a translation as follows:
  1. `:example/foo` in the `:en-US` locale.
  2. `:example/foo` in the `:en` locale.
  3. `:example/foo` in the dictionary's default locale.
  4. `:missing` in any of the above locales.

You can also specify fallback keys that'll be tried before other locales. `(t :en-US [:example/foo :example/bar]))` searches:
  1. `:example/foo` in the `:en-US` locale.
  2. `:example/bar` in the `:en-US` locale.
  3. `:example/foo` in the `:en` locale.
  4. `:example/bar` in the `:en` locale.
  5. `:example/foo` in the default locale.
  6. `:example/bar` in the default locale.
  7. `:missing` in any of the above locales.

In all cases, translation requests are logged upon fallback to default locale or :missing key.

### Localization

Check out `fmt`, `parse`, `lsort`, `fmt-str`, `fmt-msg`:
```clojure
(tower/fmt   :en-ZA 200       :currency) => "R 200.00"
(tower/fmt   :en-US 200       :currency) => "$200.00"
(tower/parse :en-US "$200.00" :currency) => 200

(tower/fmt :de-DE 2000.1 :number)     => "2.000,1"
(tower/fmt :de-DE (Date.))            => "12.06.2012"
(tower/fmt :de-DE (Date.) :date-long) => "12. Juni 2012"
(tower/fmt :de-DE (Date.) :dt-long)   => "12 giugno 2012 16.48.01 ICT"

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

Quickly internationalize your Ring web apps by adding `tower.ring/wrap-tower-middleware` to your middleware stack.

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
