# Tower, a simple internationalization (i18n) library for Clojure.

The Java platform provides some very capable tools for writing internationalized applications. But these tools can be disappointingly cumbersome when taken into a Clojure context.

Tower is an attempt to present a **simple, idiomatic internationalization and localization** story for Clojure. It wraps standard Java functionality where possible, but isn't afraid to step away from Java conventions when there's a good reason to.

## What's In The Box?
 * Lightweight wrappers for standard Java **localization functions**.
 * Rails-like, *all-Clojure* **translation function**.
 * **Simple, map-based** translation dictionary format. No XML or resource files!
 * Seamless **markdown support** for translators.
 * TODO: export/import to allow use with **industry-standard translator tools**.
 * TODO: **Ring middleware** for rapidly internationalizing web applications.

## Status [![Build Status](https://secure.travis-ci.org/ptaoussanis/tower.png)](http://travis-ci.org/ptaoussanis/tower)

Tower is still currently *experimental*. It **has not yet been thoroughly tested in production** and its API is subject to change. To run tests against all supported Clojure versions, use:

```bash
lein2 all test
```

## Getting Started

### Leiningen

Depend on `[tower "0.3.0-SNAPSHOT"]` in your `project.clj` and `use` the library:

```clojure
(ns my-app
  (:use [tower.core :as tower :only (with-i18n with-locale with-scope t style)])
  (:import [java.util Date Locale])
```

### Localization

If you're not using the provided Ring middleware, you'll need to call localization and translation functions from within a `with-i18n` or `with-locale` body.

#### Numbers

```clojure
(with-locale :en_ZA (tower/format-currency 200)) => "R 200.00"
(with-locale :en_US (tower/format-currency 200)) => "$200.00"

(with-locale :de (tower/format-number 2000.1))   => "2.000,1"
(with-locale :de (tower/parse-number "2.000,1")) => 2000.1
```

#### Dates and Times

```clojure
(with-locale :de (tower/format-date (Date.))) => "12.06.2012"
(with-locale :de (tower/format-date (style :long) (Date.))) => "12. Juni 2012"

(with-locale :it (tower/format-dt (style :long) (style :long) (Date.)))
=> "12 giugno 2012 16.48.01 ICT"

(with-locale :it (tower/parse-date (style :long) "12 giugno 2012 16.48.01 ICT"))
=> #<Date Tue Jun 12 00:00:00 ICT 2012>
```

#### Text

```clojure
(with-locale :de (tower/format-msg "foobar {0}!" 102.22)) => "foobar 102,22!"
(with-locale :de (tower/format-msg "foobar {0,number,integer}!" 102.22))
=> "foobar 102!"

(-> #(tower/format-msg "{0,choice,0#no cats|1#one cat|1<{0,number} cats}" %)
      (map (range 5)))
=> ("no cats" "one cat" "2 cats" "3 cats" "4 cats")
```

#### Collation/Sorting

```clojure
(with-locale :pl
  (sort tower/l-compare ["Warsaw" "Kraków" "Łódź" "Wrocław" "Poznań"]))
=> ("Kraków" "Łódź" "Poznań" "Warsaw" "Wrocław")
```

### Translation

Here Tower diverges from the standard Java resource approach in favour of something simpler and more agile. Let's look at the **default configuration**:

```clojure
@tower/translation-config
=>
{:dictionary-compiler-options {:escape-undecorated? true}

 :dictionary
 {:en         {:example {:foo ":en :example/foo text"
                         :bar ":en :example/bar text"
                         :decorated {:foo.html "<tag>"
                                     :foo.note "Translator note"
                                     :bar.md   "**strong**"
                                     :baz      "<tag>"}}}
  :en_US      {:example {:foo ":en_US :example/foo text"}}
  :en_US_var1 {:example {:foo ":en_US_var1 :example/foo text"}}}

 ;; <Cut some other optional/advanced stuff for this example>
 }
```

Note the format of the `:dictionary` map and the optional decorator suffixes (.html, .md, etc.).

**To change translations, you'll just change this map**: load it from file, tweak it during development with `set-translation-config!`, etc.

Let's play with the default dictionary to see what we can do:

```clojure
(with-i18n :en_US nil (t :example/foo)) => ":en_US :example/foo text"
(with-i18n :en nil (t :example/foo))    => ":en :example/foo text"
```

So that's as expected. What are the decorators for? They control **HTML escaping, Markdown rendering, etc.**:

```clojure
(with-i18n :en nil (t :example/decorated/foo)) => "<tag>"
(with-i18n :en nil (t :example/decorated/bar)) => "<strong>strong</strong>"
(with-i18n :en nil (t :example/decorated/baz)) => "&lt;tag&gt;"
```

Note that decorator effects are cached to avoid any performance hit.

What's the nil given to `with-i18n` for? That's a **translation scope**:

```clojure
(with-i18n :en :example
  (list (t :foo)
        (t :bar))) => (":en :example/foo text" ":en :example/bar text")
```

You can also control scope like this:

```clojure
(with-i18n :en nil
  (with-scope :example
    (list (t :foo)
          (t :bar)))) => (":en :example/foo text" ":en :example/bar text")
```

What happens if we request a key that doesn't exist?

```clojure
(with-i18n :en_US nil (t :example/bar)) => ":en :example/bar text"
```

So the request for an `:en_US` translation fell back to the parent `:en` translation. This is great for sparse dictionaries (for example if you have only a few differences between your `:en_US` and `:en_UK` translations).

But what if a key just doesn't exist at all?

```clojure
(with-i18n :en nil (t :this-is-invalid)) => "**:this-is-invalid**"
```

The behaviour here is actually controlled by `(:missing-key-fn @translation-config)` and is fully configurable. Please see the source code for further details.

## Contact & Contribution

Reach me (Peter Taoussanis) at *ptaoussanis at gmail.com* for questions/comments/suggestions/whatever. I'm very open to ideas if you have any!

I'm also on Twitter: [@ptaoussanis](https://twitter.com/#!/ptaoussanis).

## License

Distributed under the [Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html), the same as Clojure.