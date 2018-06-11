# Kotlin Standard Library
- `kotlin-stdlib` base JVM standard library
- Versions with `-jreN` suffix are deprecated, renamed to `-jdkN`
- Versions with `-jdkN` are extensions of normal standard library,
	with transitive dependency on `-jdkN-1` or directly on `kotlin-stdlib`
- `kotlin-stdlib-common` is an conjunction of `kotlin-stdlib` and `kotlin-stdlib-js`, for platform independent projects
