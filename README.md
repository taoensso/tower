<a href="https://www.taoensso.com" title="More stuff by @ptaoussanis at www.taoensso.com">
<img src="https://www.taoensso.com/taoensso-open-source.png" alt="Taoensso open-source" width="400"/></a>

**[CHANGELOG]** | [API] | current [Break Version]:

```clojure
[com.taoensso/tower "3.0.2"]       ; Deprecated
[com.taoensso/tower "3.1.0-beta4"] ; EOL but stable
```

> See [here](https://taoensso.com/clojure/backers) if you're interested in helping support my open-source work, thanks! - Peter Taoussanis

# Tower: a Clojure/Script i18n & L10n library

The Java platform provides some very capable tools for writing internationalized applications. Unfortunately, they can be... cumbersome. We can do much better in Clojure.

Tower's an attempt to present a **simple, idiomatic internationalization and localization** story for Clojure. It wraps standard Java functionality where possible - with warm, fuzzy, functional love.

## Library status: EOL but stable

**Last updated:** Jan 2016

Tower's latest beta (`3.1.0-beta4`) is **stable and quite useable in production**, but is likely the last major release for the library (modulo unexpected bug fixes). **Future development work** is going to be focused on:

  * [Tempura] - Clojure/Script **translations** API
  * To be determined - Clojure/Script **localization** API

##### Why the EOL?

Tower's quite useable as it is but was initially designed before things like Reactjs (indeed ClojureScript) were available. The API does the job, but it's not what I'd write if I wrote it from scratch today w/o the historical baggage.

Rather than try to shoehorn a significant set of potentially-breaking changes into Tower (pressuring stable users to migrate), it seemed logical to instead focus future work on alternative lib/s that could be **optionally migrated to in stages**.

As I find time to publish these libs, I'll make a best effort to also document the procedure necessary for **migrating from Tower** for those users that want to do that.

I apologise for the stress/trouble that this might cause, but hope that the present solution will turn out to be relatively painless in most cases. Long term, I think that the benefits will be worth it.

\- [Peter Taoussanis]

## Features
 * Small **all-Clojure** library
 * Simple wrappers for standard Java **localization features**
 * **Simple, map-based** translation dictionary format. No XML or resource files!
 * Rails-like, pure-Clojure **translation function** with ClojureScript support
 * Automatic dev-mode **dictionary reloading** for rapid REPL development
 * Seamless **markdown support** for translators
 * **Ring middleware**
 * TODO: export/import to allow use with **industry-standard tools for translators**

## Getting started

Add the necessary dependency to your project:

```clojure
Leiningen: [com.taoensso/tower "3.0.2"] ; or
deps.edn:   com.taoensso/tower {:mvn/version "3.0.2"}
```

And setup your namespace imports:

```clojure
(ns my-clj-ns ; Clojure namespace
  (:require [taoensso.tower :as tower :refer (with-tscope)]))

;; Requires v3.1+
(ns my-cljs-ns ; ClojureScript namespace
  (:require [taoensso.tower :as tower :refer-macros (with-tscope)]))
```

### Translation

The `make-t` fn handles translations. You give it a config map which includes your dictionary, and get back a `(fn [locale-or-locales k-or-ks & fmt-args])`:

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
                     :with-arguments  "Num %d = %s"
                     :greeting-alias :example/greeting
                     :baz-alias      :example.bar/baz}
           :missing  "|Missing translation: [%1$s %2$s %3$s]|"}
    :en-US {:example {:foo ":en-US :example/foo text"}}
    :de    {:example {:foo ":de :example/foo text"}}
    :ja "test_ja.clj" ; Import locale's map from external resource
    }
   :dev-mode? true ; Set to true for auto dictionary reloading
   :fallback-locale :de})

(def t (tower/make-t my-tconfig)) ; Create translation fn

(t :en-US :example/foo) => ":en-US :example/foo text"
(t :en    :example/foo) => ":en :example/foo text"
(t :en    :example/greeting "Steve") => "Hello Steve, how are you?"

;;; Translation strings are escaped and parsed as inline or block Markdown:
(t :en :example/inline-markdown) => "&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;"
(t :en :example/block-markdown)  => "<p>&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;</p>" ; Notice no "*" suffix here, only in dictionary map
(t :en :example/with-exclaim)    => "<tag>**strong**</tag>" ; Notice no "!" suffix here, only in dictionary map
(t :en :example/with-arguments 42 "forty two") =>   "Num 42 = forty two"
```

It's simple to get started, but there's a number of advanced features for if/when you need them:

#### Loading dictionaries from disk/resources

Just use a string for the `:dictionary` and/or any locale value(s) in your config map. Be sure to check that the appropriate files are available on your classpath or one of Leiningen's resource paths (e.g. `resources/`).

#### Reloading dictionaries on modification**

Enable the `:dev-mode?` option and you're good to go!

#### Scoping translations**

Use `with-tscope` if you're calling `t` repeatedly within a specific translation-namespace context:
```clojure
(with-tscope :example
  [(t :en :foo)
   (t :en :bar/baz)]) => [":en :example/foo text" ":en :example.bar/baz text"]
```

#### Missing translations**

These are handled gracefully. `(t :en-US :example/foo)` will search for a translation as follows:

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

And even fallback locales. `(t [:fr-FR :en-US] :example/foo)` searches:

 1. `:example/foo` in the `:fr-FR` locale.
 2. `:example/foo` in the `:fr` locale.
 3. `:example/foo` in the `:en-US` locale.
 4. `:example/foo` in the `:en` locale.
 5. `:example/foo` in the fallback locale.
 6. `:missing` in any of the above locales.

In all cases, translation requests are logged upon fallback to fallback locale or :missing key.

#### ClojureScript translations (early support, v3.1+)

```clojure
(def ^:private tconfig
  {:fallback-locale :en
   ;; Inlined (macro) dict => this ns needs rebuild for dict changes to reflect.
   ;; (dictionary .clj file can be placed in project's `/resources` dir):
   :compiled-dictionary (tower-macros/dict-compile* "my-dict.clj")})

(def t (tower/make-t tconfig)) ; Create translation fn

(t :en-US :example/foo) => ":en-US :example/foo text"
```

There's two notable differences from JVM translations:

 1. The dictionary is provided in a _pre-compiled_ form so that it can be inlined directly into your Cljs.
 2. Since we lack a locale-aware Cljs `format` fn, your translations _cannot_ use JVM locale formatting patterns.

The API is otherwise exactly the same, including support for all decorators.

##### Use with React (Reagent/Om/etc.)

React presents a bit of a challenge to translations since it **automatically escapes all text content** as a security measure.

This has two important implications for use with Tower's translations:

 1. Content intended to allow _translator-controlled inline styles_ needs to provided to React with the `dangerouslySetInnerHTML` property.
 2. All other content should get a `:<key>!`-style translation to prevent double escaping (Tower already escapes translations not marked with an exlamation point).

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

It's that simple. See the [API] docs for details.

#### Country and languages names, timezones, etc.

Check out `countries`, `languages`, and `timezones`.

### Ring middleware

Quickly internationalize your Ring web apps by adding `taoensso.tower.ring/wrap-tower` to your middleware stack.

See the [API] docs for details.

## This project supports the ![ClojureWerkz-logo] goals

 * [ClojureWerkz] is a growing collection of open-source, **batteries-included Clojure libraries** that emphasise modern targets, great documentation, and thorough testing.

## Contacting me / contributions

Please use the project's [GitHub issues page] for all questions, ideas, etc. **Pull requests welcome**. See the project's [GitHub contributors page] for a list of contributors.

Otherwise, you can reach me at [Taoensso.com]. Happy hacking!

\- [Peter Taoussanis]

## License

Distributed under the [EPL v1.0] \(same as Clojure).  
Copyright &copy; 2012-2020 [Peter Taoussanis].

<!--- Standard links -->
[Taoensso.com]: https://www.taoensso.com
[Peter Taoussanis]: https://www.taoensso.com
[@ptaoussanis]: https://www.taoensso.com
[More by @ptaoussanis]: https://www.taoensso.com
[Break Version]: https://github.com/taoensso/encore/blob/master/BREAK-VERSIONING.md

<!--- Standard links (repo specific) -->
[CHANGELOG]: https://github.com/taoensso/tower/releases
[API]: http://taoensso.github.io/tower/
[GitHub issues page]: https://github.com/taoensso/tower/issues
[GitHub contributors page]: https://github.com/taoensso/tower/graphs/contributors
[EPL v1.0]: https://raw.githubusercontent.com/ptaoussanis/tower/master/LICENSE
[Hero]: https://raw.githubusercontent.com/ptaoussanis/tower/master/hero.png "Title"

<!--- Unique links -->
[Tempura]: https://github.com/taoensso/tempura
[ClojureWerkz-logo]: https://raw.github.com/clojurewerkz/clojurewerkz.org/master/assets/images/logos/clojurewerkz_long_h_50.png
[ClojureWerkz]: http://clojurewerkz.org/
