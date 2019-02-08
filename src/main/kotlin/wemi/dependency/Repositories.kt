package wemi.dependency

import wemi.util.div
import java.net.URL
import java.nio.file.Paths

/** Default local Maven repository stored in `~/.m2/repository`. Used for local releases. */
val LocalM2Repository = Repository("local", (Paths.get(System.getProperty("user.home")) / ".m2/repository/").toUri().toURL(), null)

/** Default Wemi cache repository stored in `~/.m2/wemi-cache`. Used as a local cache. */
val LocalCacheM2Repository = Repository("local", (Paths.get(System.getProperty("user.home")) / ".m2/wemi-cache/").toUri().toURL(), null)

/** Maven Central repository at [maven.org](https://maven.org). Cached by [LocalM2Repository]. */
val MavenCentral = Repository("central", URL("https://repo1.maven.org/maven2/"), LocalCacheM2Repository, snapshots = false)

/** [Bintray JCenter repository](https://bintray.com/bintray/jcenter). Cached by [LocalM2Repository]. */
val JCenter = Repository("jcenter", URL("https://jcenter.bintray.com/"), LocalCacheM2Repository, snapshots = false)

/** [Jitpack repository](https://jitpack.io). Cached by [LocalM2Repository]. */
@Suppress("unused")
val Jitpack = Repository("jitpack", URL("https://jitpack.io/"), LocalCacheM2Repository)

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

    return Repository("sonatype-oss-$repository", URL("https://oss.sonatype.org/content/repositories/$repository/"), LocalCacheM2Repository, releases = releases, snapshots = snapshots)
}

/**
 * Repositories to use by default.
 *
 * @see MavenCentral
 * @see LocalM2Repository (included as cache of [MavenCentral])
 */
val DefaultRepositories:Set<Repository> = setOf(MavenCentral)