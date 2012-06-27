# Tower, a simple internationalization (i18n) library for Clojure.

The Java platform provides some very capable tools for writing internationalized applications. Unfortunately, these tools can be disappointingly cumbersome when taken into a Clojure context.

Tower is an attempt to present a **simple, idiomatic internationalization and localization** story for Clojure. It wraps standard Java functionality where possible, but it isn't afraid to step away from Java conventions when there's a good reason to.

## What's In The Box?
 * Consistent, lightweight wrappers for standard Java **localization functions**.
 * Rails-like, all-Clojure **translation function**.
 * **Simple, map-based** translation dictionary format. No XML or resource files!
 * Seamless **markdown support** for translators.
 * **Ring middleware** with **automatic dictionary reloading**.
 * TODO: export/import to allow use with **industry-standard tools for translators**.

## Status [![Build Status](https://secure.travis-ci.org/ptaoussanis/tower.png)](http://travis-ci.org/ptaoussanis/tower)

Tower is still currently *experimental*. It **has not yet been thoroughly tested in production** and its API is subject to change. To run tests against all supported Clojure versions, use:

```bash
lein2 all test
```

## Getting Started

### Leiningen

Depend on `[com.taoensso/tower "0.5.0-SNAPSHOT"]` in your `project.clj` and `use` the library:

```clojure
(ns my-app
  (:use [tower.core :as tower :only (with-locale with-scope t style)])
```

Note that in practice you'll usually use `:only (t)`. We're importing a few extra things here to help with the examples.

### Localization

If you're not using the provided Ring middleware, you'll need to call localization and translation functions from within a `with-locale` body:

#### Numbers

```clojure
(with-locale :en_ZA (tower/format-currency 200)) => "R 200.00"
(with-locale :en_US (tower/format-currency 200)) => "$200.00"

(with-locale :de (tower/format-number 2000.1))   => "2.000,1"
(with-locale :de (tower/parse-number "2.000,1")) => 2000.1
```

#### Dates and Times

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

#### Collation/Sorting

```clojure
(with-locale :pl
  (sort tower/l-compare ["Warsaw" "Kraków" "Łódź" "Wrocław" "Poznań"]))
=> ("Kraków" "Łódź" "Poznań" "Warsaw" "Wrocław")
```

#### Country and Language Names

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

### Translation

Here Tower diverges from the standard Java approach in favour of something simpler and more agile. Let's look at the *default configuration*:

```clojure
@tower/config
=>
{:dev-mode?      true
 :default-locale :en

 :dictionary-compiler-options {:escape-undecorated? true}

 :dictionary
 {:en         {:example {:foo ":en :example/foo text"
                         :bar ":en :example/bar text"
                         :decorated {:foo.html "<tag>"
                                     :foo.note "Translator note"
                                     :bar.md   "**strong**"
                                     :baz      "<tag>"}}}
  :en_US      {:example {:foo ":en_US :example/foo text"}}
  :en_US_var1 {:example {:foo ":en_US_var1 :example/foo text"}}}

 :missing-translation-fn (fn [{:keys [key locale]}] ...)}
```

Note the format of the `:dictionary` map since **this is the map you'll change to set your own translations**. Work with the map in place using `set-config!`, or load translations from a ClassLoader resource:

```clojure
(tower/load-dictionary-from-map-resource! "my-dictionary.clj")
```

You can put `my-dictionary.clj` on your classpath or one of Leiningen's resource paths (e.g. `/resources/`).

For now let's play with the default dictionary to see how Tower handles translation:

```clojure
(with-locale :en_US (t :example/foo)) => ":en_US :example/foo text"
(with-locale :en (t :example/foo))    => ":en :example/foo text"
```

So that's as expected. Note that the decorator suffixes (.html, .md, etc.) control cached **HTML escaping, Markdown rendering, etc.**:

```clojure
(with-locale :en (t :example/decorated/foo)) => "<tag>"
(with-locale :en (t :example/decorated/bar)) => "<strong>strong</strong>"
(with-locale :en (t :example/decorated/baz)) => "&lt;tag&gt;"
```

If you're calling the translate fn repeatedly within a specific namespace context, you can specify a **translation scope**:

```clojure
(with-locale :en
  (with-scope :example
    (list (t :foo)
          (t :bar)))) => (":en :example/foo text" ":en :example/bar text")
```

What happens if we request a key that doesn't exist?

```clojure
(with-locale :en_US (t :example/bar)) => ":en :example/bar text"
```

So the request for an `:en_US` translation fell back to the parent `:en` translation. This is great for sparse dictionaries (for example if you have only a few differences between your `:en_US` and `:en_UK` content).

But what if a key just doesn't exist at all?

```clojure
(with-locale :en (t :this-is-invalid)) => "**:this-is-invalid**"
```

The behaviour here is actually controlled by `(:missing-translation-fn @tower/config)` and is fully configurable. Please see the source code for further details.

### Ring Middlware

Quickly internationalize your web application by adding `(tower.ring/make-wrap-i18n-middleware)` to your middleware stack.

For each request, an appropriate locale will be selected from one of the following (descending preference):
 * Your *own locale selector* fn (e.g. for selection by IP address, domain, etc.).
 * `(-> request :session :locale)`.
 * `(-> request :params :locale)`, e.g. `"/my-uri?locale=en_US"`.
 * A URI selector, e.g. `"/my-uri/locale/en_US/"`.
 * The request's Accept-Language HTTP header.

In addition to locale selection, the middleware provides **automatic dictionary reloading** while in development mode. Just save changes to your dictionary resource file, and those changes will automatically and immediately reflect in your application.

## Tower Supports the ClojureWerkz Project Goals

ClojureWerkz is a growing collection of open-source, batteries-included [Clojure libraries](http://clojurewerkz.org/) that emphasise modern targets, great documentation, and thorough testing.

## Contact & Contribution

Reach me (Peter Taoussanis) at *ptaoussanis at gmail.com* for questions/comments/suggestions/whatever. I'm very open to ideas if you have any!

I'm also on Twitter: [@ptaoussanis](https://twitter.com/#!/ptaoussanis).

## License

Copyright &copy; 2012 Peter Taoussanis

Distributed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html), the same as Clojure.
