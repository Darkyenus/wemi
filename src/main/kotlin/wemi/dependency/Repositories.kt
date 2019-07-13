package wemi.dependency

import wemi.util.div
import java.net.URL
import java.nio.file.Path
import java.nio.file.Paths

/** Default local Maven repository stored in `~/.m2/repository`. Used for local releases. */
val LocalM2Repository = Repository("local", Paths.get(System.getProperty("user.home")) / ".m2/repository/", null)

/** Construct a path to the cache directory for repository named [repositoryName]. */
fun repositoryCachePath(repositoryName:String): Path {
    val safePath = StringBuilder(repositoryName.length)
    safePath.append(".m2/wemi-")
    for (c in repositoryName) {
        // https://superuser.com/questions/358855/what-characters-are-safe-in-cross-platform-file-names-for-linux-windows-and-os
        if (c in "\\/:*?\"<>|" || Character.isISOControl(c)) {
            safePath.append("%04x".format(c.toInt()))
        } else {
            safePath.append(c)
        }
    }
    safePath.append("-cache/")
    return Paths.get(System.getProperty("user.home")) / safePath
}
/** Path to the default Wemi cache repository stored in `~/.m2/wemi-cache`. Used as a local cache.*/
@Deprecated("Repository cache is now per-repository.", ReplaceWith("repositoryCachePath(name)"))
val LocalCacheM2RepositoryPath = Paths.get(System.getProperty("user.home")) / ".m2/wemi-cache/"

/** Maven Central repository at [maven.org](https://maven.org). Cached by [LocalM2Repository]. */
val MavenCentral = Repository("central", URL("https://repo1.maven.org/maven2/"), snapshots = false)

/** [Bintray JCenter repository](https://bintray.com/bintray/jcenter). Cached by [LocalM2Repository]. */
val JCenter = Repository("jcenter", URL("https://jcenter.bintray.com/"), snapshots = false)

/** [Jitpack repository](https://jitpack.io). Cached by [LocalM2Repository]. */
@Suppress("unused")
val Jitpack = Repository("jitpack", URL("https://jitpack.io/"))

/** [Sonatype Oss](https://oss.sonatype.org/) repository. Cached by [LocalM2Repository].
 * Most used [repository]-ies are `"releases"` and `"snapshots"`. */
@Suppress("unused")
fun sonatypeOss(repository:String): Repository {
    val releases:Boolean
    val snapshots:Boolean
    if (repository.contains("release", ignoreCase = true)) {
        releases = true
        snapshots = false
    } else if (repository.contains("snapshot", ignoreCase = true)) {
        releases = false
        snapshots = true
    } else {
        releases = true
        snapshots = true
    }

    return Repository("sonatype-oss-$repository", URL("https://oss.sonatype.org/content/repositories/$repository/"), releases = releases, snapshots = snapshots)
}

/** Repositories to use by default ([MavenCentral], [LocalM2Repository]) */
val DefaultRepositories:Set<Repository> = setOf(MavenCentral, LocalM2Repository)