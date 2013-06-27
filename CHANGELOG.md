## v1.6.0 → v1.7.1
  * `load-dictionary-from-map-resource!` now supports optionally overwriting (vs merging) with new optional `merge?` arg.
  * **BREAKING**: Drop Clojure 1.3 support.


## v1.5.1 → v1.6.0
  * A number of bug fixes.
  * Added support for translation aliases. If a dictionary entry's value is a keyword, it will now function as a pointer to another entry's value. See the default dictionary for an example.


## For older versions please see the [commit history][]

[commit history]: https://github.com/ptaoussanis/tower/commits/master
[API docs]: http://ptaoussanis.github.io/tower