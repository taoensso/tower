## v1.7.1 → 2.0.0-alpha8

So there's good news and bad news. The bad news is Tower v2's API is almost **completely different to the v1 API**.

The good news is the new API is (with the exception of `t`) **entirely self-contained**. Meaning: v2 **contains** the (now deprecated) v1 API and it should be possible to use v2 as an (almost) drop-in replacement for v1 while you migrate at your convenience.

**If you just want v2 to "work" without migrating**: rename your `t` calls to `t'`.

**If you want to migrate**:

  * **BREAKING**: `t` now takes an explicit locale and a config map of the same form as the v1 `config` atom.
  * **DEPRECATED**: `parse-Locale` -> `locale`. Only the name has changed.
  * **DEPRECATED**: `format-str` -> `fmt-str`, `format-msg` -> `fmt-msg`. The new fns take an explicit locale arg.
  * **DEPRECATED**: `l-compare` -> `lcomparator`. The new fn takes an explicit locale arg and returns a comparator fn.
  * **DEPRECATED**: `format-*` -> `fmt`, `parse-*` -> `parse`. So these 2 fns now handles the job of ~14 fns from the old API. See the appropriate docstring for details.
  * **DEPRECATED**: `sorted-localized-countries` -> `countries`, `sorted-localized-languages` -> `languages`, `sorted-timezones` -> `timezones`. The new fns take an explicit locale arg and provide their result as a vector rather than a map.
  * **DEPRECATED**: `config`, `set-config!`, `merge-config!`, `load-dictionary-from-map-resource!` have all been dropped. Instead, `t` now takes an explicit `config` argument of the same form as the old `config` atom. The `:dictionary` value may now be a resource name like "tower-dictionary.clj". When `:dictionary` is a resource name and `:dev-mode?` is true, the resource will be watched for changes.
  * **DEPRECATED**: `ring/wrap-i18n-middleware` -> `ring/wrap-tower-middleware`. Args have changed and a number of new features have been added. See the docstring for details.

So, basically, idiomatic Tower usage has been simplified:

  * The `fmt` and `parse` fns have replaced most individual localization fns.
  * All API fns now take an explicit locale argument.
  * The Ring middleware provides a `*locale*` thread-local binding and a `:locale` request key to help with the above (use whichever is more convenient).
  * The `t` fn now takes an explicit config map rather than depending on a global config atom.
  * The Ring middleware provides a `:t (partial tower/t locale config)` key to help with the above.

## v1.6.0 → v1.7.1
  * `load-dictionary-from-map-resource!` now supports optionally overwriting (vs merging) with new optional `merge?` arg.
  * **BREAKING**: Drop Clojure 1.3 support.


## v1.5.1 → v1.6.0
  * A number of bug fixes.
  * Added support for translation aliases. If a dictionary entry's value is a keyword, it will now function as a pointer to another entry's value. See the default dictionary for an example.


## For older versions please see the [commit history][]

[commit history]: https://github.com/ptaoussanis/tower/commits/master
[API docs]: http://ptaoussanis.github.io/tower
