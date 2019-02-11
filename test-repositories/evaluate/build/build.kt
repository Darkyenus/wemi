@file:BuildDependency("org.hamcrest:hamcrest:2.1")

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import wemi.util.*
import wemi.*

val someKey by key<String>("")
val someConfig by configuration("") {
    someKey set { "someConfig" }
}
val extendedConfig by configuration("") {}
val simpleEvaluationTest by key<Unit>("Tests simple evaluation logic")



val numberKey by key<Int>("")
val multiplying by configuration("") {
    numberKey modify { it * 3 }
}
val adding by configuration("") {
    numberKey modify { it + 3 }
}
val subtracting by configuration("") {}
val modifyEvaluationTest by key<Unit>("Tests simple modifier evaluation logic")

val evaluationTest by project {

    someKey set { "project" }
    extend(extendedConfig) {
        someKey set { "extendedConfig" }
    }
    simpleEvaluationTest set {
        assertThat(someKey.get(), equalTo("project"))
        assertThat(using(someConfig){ someKey.get() }, equalTo("someConfig"))
        assertThat(using(extendedConfig){ someKey.get() }, equalTo("extendedConfig"))
        assertThat(using(someConfig, extendedConfig){ someKey.get() }, equalTo("extendedConfig"))
        assertThat(using(extendedConfig, someConfig){ someKey.get() }, equalTo("someConfig"))
    }
    autoRun(simpleEvaluationTest)
    
    
    numberKey set { 1 }
    extend(subtracting) {
        numberKey modify { it - 3 }
    }
    modifyEvaluationTest set {
        assertThat(numberKey.get(), equalTo(1))
        assertThat(using(multiplying){ numberKey.get() }, equalTo(3))
        assertThat(using(adding){ numberKey.get() }, equalTo(4))
        assertThat(using(subtracting){ numberKey.get() }, equalTo(-2))
        assertThat(using(multiplying, adding){ numberKey.get() }, equalTo(6))
        assertThat(using(multiplying, adding, adding){ numberKey.get() }, equalTo(9))
        assertThat(using(multiplying, multiplying){ numberKey.get() }, equalTo(9))
        assertThat(using(multiplying, subtracting){ numberKey.get() }, equalTo(0))
        assertThat(using(subtracting, multiplying){ numberKey.get() }, equalTo(-6))
    }
    
}

val compileErrors by project(path("errors")) {
    extend(compilingJava) {
        sources set { FileSet(projectRoot.get() / "src") }
        compilerOptions[wemi.compile.JavaCompilerFlags.customFlags] += "-Xlint:all"
    }

    extend(compilingKotlin) {
        sources set { FileSet(projectRoot.get() / "src") }
    }
}

val CacheRepository = (wemi.boot.WemiCacheFolder / "-test-cache-repository").let {
    it.deleteRecursively()
    Repository("test-cache", it)
}

val checkResolution by key<Unit>("Check if resolved files contain what they should")

fun EvalScope.assertClasspathContains(vararg items:String) {
    val got = externalClasspath.get().map { Files.readAllBytes(it.file).toString(Charsets.UTF_8) }.toSet()
    val expected = items.toSet()
    assertThat(expected, equalTo(got))
}

val release_1 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "release-1", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "1.0")) }

    checkResolution set {
        assertClasspathContains("v1.0")
    }
}

val release_2 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "release-2", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "1.1")) }

    checkResolution set {
        assertClasspathContains("v1.0" /* through dependency in 1.1 */, "v1.1")
    }
}

val non_unique_1 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "non-unique-1", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "1.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v1.0-SNAPSHOT-1")
    }
}

val non_unique_2 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "non-unique-2", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "1.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v1.0-SNAPSHOT-1")
    }
}

val non_unique_3 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "non-unique-2", CacheRepository, snapshotUpdateDelaySeconds = wemi.dependency.SnapshotCheckAlways, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "1.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v1.0-SNAPSHOT-2")
    }
}

val unique_1 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "unique-1", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "2.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v2.0-SNAPSHOT-1")
    }
}

val unique_2 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "unique-2", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "2.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v2.0-SNAPSHOT-1")
    }
}

val unique_3 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "unique-2", CacheRepository, snapshotUpdateDelaySeconds = wemi.dependency.SnapshotCheckAlways, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "2.0-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("v2.0-SNAPSHOT-2")
    }
}

val unique_4 by configuration("") {
    repositories set { setOf(Repository("test-repo", projectRoot.get() / "unique-2", CacheRepository, local = false)) }
    libraryDependencies set { setOf(dependency("some-group", "some-artifact", "2.0-SNAPSHOT", snapshotVersion = "20190101.123456-1")) }

    checkResolution set {
        assertClasspathContains("v2.0-SNAPSHOT-1")
    }
}

val dependency_resolution by project(path("dependency-resolution")) {
    // Test dependency resolution by resolving against changing repository 3 different libraries
    /*
    1. Release
        1. Exists
        2. Exists, with a new version which depends on the old one
        3. Does not exist (offline) but should still resolve, as it is in the cache
     */
    autoRun(checkResolution, release_1)
    autoRun(checkResolution, release_2)
    autoRun(checkResolution, Configurations.offline, release_2)

    /* 2. Non-unique snapshot
        1. Exists
        2. Exists, is different, but the cache is set to daily, so we still see the old one
        3. Exists and see the new one, because re-check delay has been set to zero
     */
    autoRun(checkResolution, non_unique_1)
    autoRun(checkResolution, non_unique_2)
    autoRun(checkResolution, non_unique_3)

    /* 3. Unique snapshot
        1. Exists
        2. Newer exists, but is not found yet due to recheck policy
        3. Newer exists and is found
        4. Newer exists and is ignored, because older version is forced
     */
    autoRun(checkResolution, unique_1)
    autoRun(checkResolution, unique_2)
    autoRun(checkResolution, unique_3)
    autoRun(checkResolution, unique_4)
}