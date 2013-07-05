## v1.7.1 → 2.0.0-alpha1
  * **BREAKING**: `parse-Locale` now takes `:jvm-default` rather than `:default` arg for JVM default locale.
  * `parse-Locale` now accepts (and passes through) Locales (i.e. the Java objects).
  * **BREAKING**: `config` and `load-dictionary-from-map-resource!` have been dropped. Instead, `t` now takes an explicit `config` argument of the same form as the old `config` atom. The `:dictionary` value may now be a resource name like "tower-dictionary.clj". When `:dictionary` is a resource name and `:dev-mode?` is true, the resource will be watched for changes.
  * **BREAKING**: `ring/wrap-i18n-middleware`'s arguments have changed. See the docstring for details.
  * `ring/wrap-i18n-middleware` now adds a `:t` key onto requests whose value is `(t partial t-config`). I.e. the attached `:t` can be used as a translation fn that does _not* require the explicit config arg.

So, basically, the idiomatic `t` (translation) usage has changed:
```clojure
;;; Old usage, with Ring middleware
(tower/set-config! [:dev-mode? true])
(tower/load-dictionary-from-map-resource! "my-dictionary.clj")
(defn my-handler [request] (tower/t :welcome-message))
(def  my-ring-app (tower.ring/wrap-i18n-middleware my-handler))

;;; New idiomatic usage, with Ring middleware
(defn my-handler [{:keys [t] :as request}] (t :welcome-message))
(def  my-ring-app (tower.ring/wrap-i18n-middleware my-handler
                    {:dev-mode? true :dictionary "my-dictionary.clj"}))


;;; Old usage, w/o Ring middleware
(tower/set-config! [:dev-mode? true])
(tower/load-dictionary-from-map-resource! "my-dictionary.clj")
(tower/t :my-translation-key)

;;; New idiomatic usage, w/o Ring middleware
(def my-tconfig {:dev-mode? true :dictionary "my-dictionary.clj"})
(tower/t my-tconfig :my-translation-key)
```

The localization API (i.e. everything besides `t`) is unchanged.


## v1.6.0 → v1.7.1
  * `load-dictionary-from-map-resource!` now supports optionally overwriting (vs merging) with new optional `merge?` arg.
  * **BREAKING**: Drop Clojure 1.3 support.


## v1.5.1 → v1.6.0
  * A number of bug fixes.
  * Added support for translation aliases. If a dictionary entry's value is a keyword, it will now function as a pointer to another entry's value. See the default dictionary for an example.


## For older versions please see the [commit history][]

[commit history]: https://github.com/ptaoussanis/tower/commits/master
[API docs]: http://ptaoussanis.github.io/tower