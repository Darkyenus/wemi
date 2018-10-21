# 0.7

# 0.6 2018-10-21
- Fix kotlin 1.2.71 incremental compilation
- Add "key trace" status line to the UI
- IDE: Add "convert to Wemi" action
- IDE: Add "(re)install Wemi launcher" action
- IDE: Show what is Wemi doing during import (which task is running)

# 0.5 2018-10-09
- Add support for Kotlin 1.2.71
- Fix `resourceRoots` not being set according to `projectRoot`
- Fix Maven dependency resolution not considering parent poms
- Initial import into IntelliJ will succeed even when build scripts are broken
- Rewritten how internal cache system works
	- Now caching is explicit

# 0.4 2018-06-12
- Add hotswap plugin
- Add support for Kotlin 1.2.41
- Remove support for Kotlin 1.1.3-4 and 1.2.20
- Add `Lines` and `UniqueLines` MergeStrategy
- Add `assemblyMapFilter` key for more fine-grained control over assembled files
- Improve `keys`, `configurations` and `projects` commands by adding a filter parameter to them
- Add `inspect` command for checking detailed information about keys, including where it is bound, configurations and projects
- Fix launcher script to be valid bash by requiring bash instead of sh
- Internal refactorings, some mildly breaking to build scripts
	- WCollections no longer needed, used only internally
	- Build script is now exposed as project, not configuration
- Build script directives are now annotation based, instead of line command based
- Harden key binding resolution and fix result caching by simplifying it
- Importing to IDE is now possible even with no/broken build scripts
- Partial support of <dependencyManagement> of Maven poms
- Buildable using jitpack.io (see jitpack.yml for use in your own projects)

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