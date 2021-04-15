import wemi.ArchetypeDelegate
import wemi.CommandDelegate
import wemi.ConfigurationDelegate
import wemi.KeyDelegate
import wemi.ProjectDelegate

/*
 * Types that should be visible in build scripts without any explicit imports
 */

// Types
typealias Key<Value> = wemi.Key<Value>
typealias Archetype = wemi.Archetype
typealias Configuration = wemi.Configuration
typealias Project = wemi.Project
typealias Scope = wemi.Scope
typealias EvalScope = wemi.EvalScope
typealias Repository = wemi.dependency.Repository
typealias DependencyId = wemi.dependency.DependencyId
typealias Dependency = wemi.dependency.Dependency
typealias DependencyExclusion = wemi.dependency.DependencyExclusion
typealias LocatedPath = wemi.util.LocatedPath

typealias Archetypes = wemi.Archetypes
typealias Configurations = wemi.Configurations
typealias Keys = wemi.Keys

typealias project = ProjectDelegate
typealias key<T> = KeyDelegate<T>
typealias configuration = ConfigurationDelegate
typealias archetype = ArchetypeDelegate
typealias command<T> = CommandDelegate<T>

typealias Path = java.nio.file.Path
typealias Paths = java.nio.file.Paths
typealias Files = java.nio.file.Files

// Build script directive annotations
typealias BuildDependency = wemi.boot.BuildDependency
typealias BuildDependencyRepository = wemi.boot.BuildDependencyRepository
typealias BuildClasspathDependency = wemi.boot.BuildClasspathDependency
typealias BuildDependencyPlugin = wemi.boot.BuildDependencyPlugin
