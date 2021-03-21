# 0.17
- Add `runEnvironment` key to control environment variables
- Remove `runMain` and `run` dry parameter, add `runProcess` instead

# 0.16 2020-12-21
- In plugin for intellij plugins: Added ability to instrument classes with not-null assertions and to compile .form files
- JUnit test dependencies are added in `testing:` again
- Add ability to machine-readable-print more types, namely `Pair`, `DokkaOptions`, `TestReport`, `TestParameters` and `InfoNode`
- Update JLine to 3.18.0, this should help with color codes on Windows
- Force hotfix: SimpleHistory did not implement some new methods from JLine

# 0.15 2020-11-16
- Housekeeping update
- Generated sources are now in `/build-generated/` to prevent IDE conflicts
- Changed what can be extended and what can extend (`ConfigurationExtension`s can no longer extend, but everything else can be extended, including projects and archetypes)
- Removed `Keys.javaExecutable`, the information is now carried in `Keys.javaHome`
- Added IntelliJ Plugin Verifier support to intellij ide plugin

# 0.14 2020-11-04
- **Do not upgrade to this version**
- Fix aggregate project's test attempting to test untestable subprojects
- Improved `java` detection on macOS
- Initial and work-in-progress support for Kotlin JavaScript backend and [TeaVM](http://teavm.org/)
- Internal robustness improvements in handling broken dependencies and Maven version ranges
- Add `runSystemProperties` key for easier handling of Java's system properties. The properties are included into `runOptions` by default.
- Having multiple keys with the same name should no longer completely break the build
- Maven dependency resolver now checks `WEMI_MAVEN_OS_NAME`, `WEMI_MAVEN_OS_ARCH`, `WEMI_MAVEN_OS_VERSION` and `WEMI_MAVEN_OS_FAMILY`
    environment variables to detect relevant profile activation properties
- Added a new `Dependency` type, `TypeChooseByPackaging`, which allows Wemi to choose the artifact automatically
- Added `SystemInfo` for all operating system and processor architecture detection needs
- Removed `archivingDocs` and `archivingSources` configurations, their purpose is now fulfilled through `archiveDocs` and `archiveSources` keys
- Removed `archiveOutput` key, modify the target file location through explicit move
- Removed `publishing` configuration
- Added `testSources` and `testResources` to simplify setting of these properties
- Generated files are now stored in a separate directory in ./build/generated
- Configurations can no longer have parents
- Removed resolvedLibraryScopes and the significance of compiling/running/archiving/etc. configurations
    in favor of dedicated scopesCompile, scopesRun and scopesTest keys along with scoping information directly inside externalClasspath
- Introduced aggregate scope to implement aggregate project dependency more cleanly
- Removed `running`, `compiling`, `assembling` and `archiving` configurations, they are not necessary anymore
- Removed `retrievingSources` and `retrievingDocs` configurations, use new keys `externalSources` and `externalDocs` instead

# 0.13 2020-06-23
- Add utility functions for source file and classpath generation, see the `wemi.generation` package
- Fix `system` crashing on infinite timeout
- Fix `--machine-readable=shell` stack overflow on `Path` printing and make it robust against self-referential collections
- Improve symlink handling in launcher (fixes issues on Windows, in particular)
- Fix non-deterministic dependency resolution when a dependency was present multiple times with different versions

# 0.12 2020-02-23
- Add support for Kotlin 1.3.61
- Add `system` function, for easy system command invocation
- Greatly improved and sped-up dependency resolution process, up to 33% faster than previous version
    - Downloading of multiple dependencies is now parallelized
    - Much better error messages
    - Added support for Maven's POM `<profile>`s
- Fix testing harness classes often conflicting with user libraries
- Add `--machine-readable=shell` mode, for better shell scripting integration
- Improve `javac` logging reliability 
- Cache downloaded dependencies in `~/.wemi`, instead of `~/.m2`
- Do not consider aborted tests as failures
- Add explicit `JavaCompilerFlag.encoding`, which defaults to UTF-8
- Fix archiving not using correct configurations
- Prevent Javadoc from failing on soft errors. To fail the task on soft errors, add javadoc flag `-Wemi-fail-on-error`

# 0.11 2019-12-29
- JUnit dependencies are now bundled by default, you don't have to add them manually
    - They are hidden in `testing` configuration
- Better resource handling in aggregate archetype
- `compilingJava` and `compilingKotlin` configurations are gone. Compiler flags can be set directly.
- Configuration hierarchies now form *configuration axis* which are mutualy exclusive
- Exit on first error when running non-interactively
- Ability to limit amount of printed collection elements, example: `trace elements=5 compile`
- Bug fixes

# 0.10 2019-12-28
- New distribution scheme - launcher is now a shell script, not a fat-jar
- It is possible to specify scope for `ProjectDependency`
- Rename `BlankJVMProject` to `AggregateJVMProject`
- Rename `libraryDependencyProjectMapper` to `libraryDependencyMapper`
- Minor improvements

# 0.9 2019-07-11
- Improve Maven dependency resolution system
    - Handles transitive dependencies, dependencyManagement, exclusions and scopes in the same way as Maven itself does
    - Attempts to properly detect multiple repositories which host same artifact and warns if their content differs
    - `DependencyId.scope` now works as expected, it is no longer just a dummy value
        - Previous approach of setting "scope" indirectly through `using(compiling/running/testing)` is still supported
- Default project can now be set directly in the build script, through `Project.makeDefault()`
- Progress for long running dependency downloads is now shown, along with download speed and ETA
- `run` key now supports `dry=true` input to just print the command used to run the program
- HTTPS security can now be relaxed on a per-repo basis. This is inherently unsafe, so it is accompanied by a healthy dose of warnings.
- Remove old Kotlin versions (1.2.21 and 1.2.41) and add latest one instead (1.3.41)
- *CLI:* `X/` where `X` is a project name is now an alias for `project X`, for switching session's default project
- *Internal:* Directory locking is now more reliable

# 0.8 2019-02-18
- Deprecate some utility methods in favor of using constructors directly (`FileSet`, `dependency` for project dependencies)
	- `import wemi.*` is now assumed to be present in build scripts for more fluent api
- When running through `run` key, SIGINT is forwarded to the running program
- Make `FileSet` more expressive
- Refactored and partially rewritten Maven dependency resolution and related classes
    - nicer API
    - support `-SNAPSHOT` versions
    - more control over checksum mismatches
- `Key.add` and similar extension methods no longer incorrectly allow arbitrary types to be added
- Fix `--log=<level>` startup parameter
- Improve `Path` and `URL` `div` extension operators to handle `.` and `..` paths correctly and be more intuitive in general

# 0.7 2019-02-05
- Replaced `(re)sourceRoots`, `sourceExtensions` and `(re)sourceFiles` with single `(re)sources` key, powered by `FileSet` class (inspired by Ant's `<fileSet>`)
- Add new key value caching system (see `EvalScope`)
- Remove anonymous configurations
- When evaluating keys, current evaluation stack is displayed in the CLI
- Input system rewritten
- It is now possible to specify pattern to filter which test should run in `test`
- Change how Wemi is bundled and launched internally, which should improve IDE behavior
- Many bug fixes and robustness improvements
- IDE: File paths are now clickable

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