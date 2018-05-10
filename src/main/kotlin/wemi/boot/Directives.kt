@file:Suppress("unused")

package wemi.boot

/**
 * Since Java does not guarantee any persistency in ordering of annotation's fields/methods,
 * we have to store it ourselves.
 *
 * @param value ordered array of names of constructor fields of Build* directive annotation.
 */
@Target(AnnotationTarget.ANNOTATION_CLASS)
@Retention(AnnotationRetention.RUNTIME)
internal annotation class DirectiveFields(val value:Array<String>)

/**
 * Build script file annotation, which requests given mavenized library to be available in the build script's classpath.
 *
 * **NOTE**: These annotations are not parsed by the Kotlin compiler, but by Wemi itself.
 * This places some constraints on their use:
 * - annotations MUST be placed one per line, with nothing else on the line, except for surrounding whitespace
 * - single annotation MUST NOT be split across multiple lines
 * - annotations MUST have constructors which contain only plain string arguments, without any escapes
 * - annotation constructors MAY contain valid name tags (they will be used to detect argument reordering)
 *
 * @param group of the artifact coordinate OR full "group:name:version" for compact form
 * @param name of the artifact coordinate OR empty string (default) when using compact form
 * @param version of the artifact coordinate OR empty string (default) when using compact form
 * @see wemi.dependency function, which has similar arguments
 * @see wemi.Keys.libraryDependencies key, which stores library dependencies for built projects
 * @see BuildDependencyRepository to add more repositories to search in
 * @see BuildClasspathDependency to add files directly to the build script classpath, without resolution step
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
@DirectiveFields(["groupOrFull", "name", "version"])
annotation class BuildDependency(val groupOrFull:String, val name:String = "", val version:String = "")

/**
 * Build script file annotation, which adds a repository to search dependencies in (specified by [BuildDependency]).
 *
 * @param name of the repository
 * @param url of the repository
 * @see wemi.repository function, which has similar arguments
 * @see wemi.Keys.repositories key, which stores repositories for built projects
 * @see BuildDependency for important notes about build-script directive annotations.
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
@DirectiveFields(["name", "url"])
annotation class BuildDependencyRepository(val name:String, val url:String)

/**
 * Build script file annotation, which adds a given file to the build script's classpath.
 *
 * @param file path to the classpath file (typically .jar) or directory.
 * Absolute or relative to the [wemi.boot.WemiRootFolder] (=project's root directory)
 * @see BuildDependency for important notes about build-script directive annotations
 * @see wemi.Keys.externalClasspath key, which stores raw classpath dependencies of built projects
 */
@Target(AnnotationTarget.FILE)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
@DirectiveFields(["file"])
annotation class BuildClasspathDependency(val file:String)