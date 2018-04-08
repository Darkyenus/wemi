# 0.4
- Add `Lines` and `UniqueLines` MergeStrategy

# 0.3 2018-04-08
- **Change CLI options to follow GNU standard, including long/short options and `--help`/`--version` convention.**
Old Java-style options are no longer supported.
- Allow to debug `run` and `test` tasks with new `debug:` configuration
- Improve JUnit test reports (mostly with colors)
- Add explicit color/unicode toggles, to be more bad-OS friendly
- Disallow the `.wemi` build script extension, as it only added complexity and broke things. Use `.kt` instead.
- New API for artifact customization
- `DependenciesOnly` archetype is now replaced by `BlankJVMProject`
- Many fixes for Windows, that made the core more robust
- Support `reload` command to reload build scripts, ask to reload when scripts fail to compile on startup
- Improved dependency resolution
- Show file sizes in task results
- Unified Kotlin and Java compiler error reports
- Initial support for Kotlin's incremental compilation
- New `dependency()` DSL overloads for specifying preferred repository
- Change signature of `Project.dependency(Configuration*)` to `dependency(Project, aggreagate:Boolean, Configuration*)` for better consistency
and less conflicts. Adds explicit (non)aggregate functionality.
- Remove `startYear` key, use `publishMetadata.inceptionYear` instead