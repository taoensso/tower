**[API docs](http://ptaoussanis.github.io/tower/)** | **[CHANGELOG](https://github.com/ptaoussanis/tower/blob/master/CHANGELOG.md)** | [contact & contributing](#contact--contributing) | [other Clojure libs](https://www.taoensso.com/clojure-libraries) | [Twitter](https://twitter.com/#!/ptaoussanis) | current [semantic](http://semver.org/) version:

```clojure
[com.taoensso/tower "1.7.1"]        ; Stable, needs Clojure 1.4+ as of 1.7.0
[com.taoensso/tower "2.0.0-alpha1"] ; Development (notes below)
```

v2 adds an improved, config-atom-free `t` API. It's a **mostly** backwards-compatible release. See the [CHANGELOG](https://github.com/ptaoussanis/tower/blob/master/CHANGELOG.md) for migration details.

# Tower, a Clojure i18n & L10n library

The Java platform provides some very capable tools for writing internationalized applications. Unfortunately, these tools can be disappointingly cumbersome when taken into a Clojure context.

Tower is an attempt to present a **simple, idiomatic internationalization and localization** story for Clojure. It wraps standard Java functionality where possible, but it isn't afraid to step away from Java conventions when there's a good reason to.

## What's in the box™?
 * Small, uncomplicated **all-Clojure** library.
 * Consistent, lightweight wrappers for standard Java **localization functions**.
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
[com.taoensso/tower "1.7.1"] ; project.clj
(ns my-app (:require [taoensso.tower :as tower
                      :refer (with-locale with-scope t style)])) ; ns
```

### Translation

Here Tower diverges from the standard Java approach in favour of something simpler and more agile. Let's look at the default config:

```clojure
@tower/config
=>
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

 :log-missing-translation!-fn (fn [{:keys [dev-mode? locale k-or-ks]}] ...)}
```

Note the format of the `:dictionary` map since **this is the map you'll change to set your own translations**. Work with the map in place using `set-config!`, or load translations from a ClassLoader resource:

```clojure
(tower/load-dictionary-from-map-resource! "my-dictionary.clj")
```

You can put `my-dictionary.clj` on your classpath or one of Leiningen's resource paths (e.g. `/resources/`).

For now let's play with the default dictionary to see how Tower handles translation:

```clojure
(with-locale :en-US (t :example/foo)) => ":en-US :example/foo text"
(with-locale :en    (t :example/foo)) => ":en :example/foo text"
(with-locale :en    (t :example/greeting "Steve")) => "Hello Steve, how are you?"
```

Translation strings are escaped and parsed as inline [Markdown](http://daringfireball.net/projects/markdown/) unless suffixed with `!` in the dictionary map:

```clojure
(with-locale :en (t :example/with-markdown)) => "&lt;tag&gt;<strong>strong</strong>&lt;/tag&gt;"
(with-locale :en (t :example/with-exclaim)) => "<tag>**strong**</tag>" ; Notice no "!" suffix here, only in dictionary map
```

If you're calling the translate fn repeatedly within a specific namespace context, you can specify a **translation scope**:

```clojure
(with-locale :en
  (with-scope :example
    (list (t :foo)
          (t :bar/baz)))) => (":en :example/foo text" ":en :example.bar/baz text")
```

Missing translations are handled gracefully. `(with-scope :en-US (t :example/foo))` searches for a translation as follows:
 1. `:example/foo` in the `:en-US` locale.
 2. `:example/foo` in the `:en` locale.
 3. `:example/foo` in the default locale, `(:default-locale @tower/config)`.
 4. `:missing` in any of the above locales.

You can also specify fallback keys that'll be tried before other locales. `(with-scope :en-US (t [:example/foo :example/bar]))` searches:
 1. `:example/foo` in the `:en-US` locale.
 2. `:example/bar` in the `:en-US` locale.
 3. `:example/foo` in the `:en` locale.
 4. `:example/bar` in the `:en` locale.
 5. `:example/foo` in the default locale.
 6. `:example/bar` in the default locale.
 7. `:missing` in any of the above locales.

In all cases, translation request is logged upon fallback to default locale or :missing key.

### Localization

If you're not using the provided Ring middleware, you'll need to call localization and translation functions from within a `with-locale` body:

#### Numbers

```clojure
(with-locale :en-ZA (tower/format-currency 200)) => "R 200.00"
(with-locale :en-US (tower/format-currency 200)) => "$200.00"

(with-locale :de (tower/format-number 2000.1))   => "2.000,1"
(with-locale :de (tower/parse-number "2.000,1")) => 2000.1
```

#### Dates and times

```clojure
(with-locale :de (tower/format-date (java.util.Date.))) => "12.06.2012"
(with-locale :de (tower/format-date (style :long) (java.util.Date.)))
=> "12. Juni 2012"

(with-locale :it (tower/format-dt (style :long) (style :long) (java.util.Date.)))
=> "12 giugno 2012 16.48.01 ICT"

(with-locale :it (tower/parse-date (style :long) "12 giugno 2012 16.48.01 ICT"))
=> #<Date Tue Jun 12 00:00:00 ICT 2012>
```

#### Text

```clojure
(with-locale :de (tower/format-msg "foobar {0}!" 102.22)) => "foobar 102,22!"
(with-locale :de (tower/format-msg "foobar {0,number,integer}!" 102.22))
=> "foobar 102!"

(with-locale :de
  (-> #(tower/format-msg "{0,choice,0#no cats|1#one cat|1<{0,number} cats}" %)
      (map (range 5)) doall))
=> ("no cats" "one cat" "2 cats" "3 cats" "4 cats")
```

#### Collation/sorting

```clojure
(with-locale :pl
  (sort tower/l-compare ["Warsaw" "Kraków" "Łódź" "Wrocław" "Poznań"]))
=> ("Kraków" "Łódź" "Poznań" "Warsaw" "Wrocław")
```

#### Country and language names

```clojure
(with-locale :pl (tower/sorted-localized-countries ["GB" "DE" "PL"]))
=> {:sorted-codes ["DE" "PL" "GB"],
    :sorted-names ["Niemcy" "Polska" "Wielka Brytania"]}

(with-locale :pl (tower/sorted-localized-languages ["en" "de" "pl"]))
=> {:sorted-codes ["en" "de" "pl"],
    :sorted-names ["angielski (English)" "niemiecki (Deutsch)" "polski"]}

(take 5 (:sorted-names (with-locale :en (tower/sorted-localized-countries))))
=> ("Afghanistan" "Åland Islands" "Albania" "Algeria" "American Samoa")
```

#### Timezones

```clojure
(tower/sorted-timezones)
=> {:sorted-ids   ["Pacific/Midway" "Pacific/Niue" ...]
    :sorted-names ["(GMT -11:00) Midway" "(GMT -11:00) Niue" ...]
```

### Ring middlware

Quickly internationalize your web application by adding `(taoensso.tower.ring/wrap-i18n-middleware)` to your middleware stack.

For each request, an appropriate locale will be selected from one of the following (descending preference):
 * Your *own locale selector* fn (e.g. for selection by IP address, domain, etc.).
 * `(-> request :session :locale)`.
 * `(-> request :params :locale)`, e.g. `"/my-uri?locale=en-US"`.
 * A URI selector, e.g. `"/my-uri/locale/en-US/"`.
 * The request's Accept-Language HTTP header.

## This project supports the CDS and ClojureWerkz goals

  * [CDS](http://clojure-doc.org/), the **Clojure Documentation Site**, is a **contributer-friendly** community project aimed at producing top-notch, **beginner-friendly** Clojure tutorials and documentation. Awesome resource.

  * [ClojureWerkz](http://clojurewerkz.org/) is a growing collection of open-source, **batteries-included Clojure libraries** that emphasise modern targets, great documentation, and thorough testing. They've got a ton of great stuff, check 'em out!

## Contact & contribution

Please use the [project's GitHub issues page](https://github.com/ptaoussanis/tower/issues) for project questions/comments/suggestions/whatever **(pull requests welcome!)**. Am very open to ideas if you have any!

Otherwise reach me (Peter Taoussanis) at [taoensso.com](https://www.taoensso.com) or on Twitter ([@ptaoussanis](https://twitter.com/#!/ptaoussanis)). Cheers!

## License

Copyright &copy; 2012, 2013 Peter Taoussanis. Distributed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html), the same as Clojure.
