@file:BuildDependency("org.hamcrest:hamcrest:2.1")

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.*
import wemi.util.*
import wemi.dependency.*
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


val keyExtensionArchetype by archetype(Archetypes::Base) {
    keyWhichIsSetInArchetypeThenExtended set { "" }
}
val keyWhichIsExtended by key<String>("")
val keyWhichIsSetThenExtended by key<String>("")
val keyWhichIsSetInArchetypeThenExtended by key<String>("")
val configurationWhichBindsKey by configuration("")  {
    keyWhichIsExtended set { "a" }
    keyWhichIsSetThenExtended modify { it + "a" }
    keyWhichIsSetInArchetypeThenExtended modify { it + "a" }
}
val configurationWhichExtendsTheOneWhichBindsKey by configuration("", configurationWhichBindsKey) {}
val keySetInConfigurationAndThenExtendedTest by key<Unit>("")

val evaluationTest by project(archetypes = *arrayOf(keyExtensionArchetype)) {

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
        assertThat(using(multiplying, adding, adding){ numberKey.get() }, equalTo(6))
        assertThat(using(multiplying, multiplying){ numberKey.get() }, equalTo(3))
        assertThat(using(multiplying, subtracting){ numberKey.get() }, equalTo(0))
        assertThat(using(subtracting, multiplying){ numberKey.get() }, equalTo(-6))
    }
    autoRun(modifyEvaluationTest)


    keyWhichIsSetThenExtended set { "" }
    extend(configurationWhichBindsKey) {
        keyWhichIsExtended modify { it + "b" }
        keyWhichIsSetThenExtended modify { it + "b" }
        keyWhichIsSetInArchetypeThenExtended modify { it + "b" }
    }
    keySetInConfigurationAndThenExtendedTest set {
        assertThat(using(configurationWhichBindsKey) { keyWhichIsExtended.get() }, equalTo("ab"))
        assertThat(using(configurationWhichBindsKey) { keyWhichIsSetThenExtended.get() }, equalTo("ab"))
        assertThat(using(configurationWhichBindsKey) { keyWhichIsSetInArchetypeThenExtended.get() }, equalTo("ab"))
        assertThat(using(configurationWhichExtendsTheOneWhichBindsKey) { keyWhichIsExtended.get() }, equalTo("ab"))
        assertThat(using(configurationWhichExtendsTheOneWhichBindsKey) { keyWhichIsSetThenExtended.get() }, equalTo("ab"))
        assertThat(using(configurationWhichExtendsTheOneWhichBindsKey) { keyWhichIsSetInArchetypeThenExtended.get() }, equalTo("ab"))
    }
    autoRun(keySetInConfigurationAndThenExtendedTest)
}

val compileErrors by project(path("errors")) {
    sources set { FileSet(projectRoot.get() / "src") }

    compilerOptions[wemi.compile.JavaCompilerFlags.customFlags] = { it + "-Xlint:all" }
}

val CacheRepository = (wemi.boot.WemiCacheFolder / "-test-cache-repository").let {
    it.deleteRecursively()
    it
}

val checkResolution by key<Unit>("Check if resolved files contain what they should")

var classpathAssertions = 0
var classpathAssertionsFailed = 0

fun EvalScope.assertClasspathContains(vararg items:String) {
    classpathAssertions++
    val got = externalClasspath.get().map { Files.readAllBytes(it.file).toString(Charsets.UTF_8) }.toSet()
    val expected = items.toSet()
    if (got != expected) {
        classpathAssertionsFailed++
        //System.err.println("\n\n\nERROR: Got $got, expected $expected")
        assertThat(got, equalTo(expected))
    }
}

fun EvalScope.assertClasspathContainsFiles(vararg items:String) {
    classpathAssertions++
    val got = externalClasspath.get().map { it.file.name }.toSet()
    val expected = items.toSet()
    if (got != expected) {
        classpathAssertionsFailed++
        //System.err.println("\n\n\nERROR: Got $got, expected $expected")
        assertThat(got, equalTo(expected))
    }
}

val GROUP = "grp"
val UNIQUE_CACHE_DATE = "20190101"

class TestRepositoryData(val repositoryName:String) {

    data class TestArtifactDependency(val artifact:TestArtifact, val scope:String)

    class TestArtifact(val name:String, version:String, val buildNumber:Int, val artifact:String) {
        val dependsOn = ArrayList<TestArtifactDependency>()
        var cache:String? = null

        data class UniqueCacheEntry(val buildNumber:Int, val inCache:Boolean, val text:String)
        val uniqueCache = ArrayList<UniqueCacheEntry>()

        val outsideVersion:String = if (buildNumber < 0) {
            version
        } else {
            "$version-SNAPSHOT"
        }

        val snapshotTimestamp = "$UNIQUE_CACHE_DATE.${"%06d".format(buildNumber)}"

        val insideVersion:String = if (buildNumber < 0) {
            version
        } else {
            "$version-$snapshotTimestamp-$buildNumber"
        }

        fun dependsOn(other:TestArtifact, scope:String = "compile"):TestArtifact {
            this.dependsOn.add(TestArtifactDependency(other, scope))
            return this
        }

        fun hasCache(text:String):TestArtifact {
            cache = text
            return this
        }

        override fun toString(): String {
            return "$name:$outsideVersion/$insideVersion"
        }
    }

    private val artifacts = ArrayList<TestArtifact>()

    fun artifact(name:String, version:String, content:String = "$name $version"):TestArtifact {
        val artifact = TestArtifact(name, version, -1, artifact = content)
        artifacts.add(artifact)
        return artifact
    }

    fun snapshotArtifact(name:String, version:String, buildNumber:Int, content:String = "$name $version $buildNumber"):TestArtifact {
        val artifact = TestArtifact(name, version, buildNumber, artifact = content)
        artifacts.add(artifact)
        return artifact
    }

    private fun Path.write(text:String) {
        this.writeText(text)
        for (checksum in Checksum.values()) {
            this.appendSuffix(checksum.suffix).writeText(createHashSum(checksum.checksum(text.toByteArray()), null))
        }
    }

    fun buildIn(repoFolder:Path, cacheRepoFolder:Path) {
        for (artifact in artifacts) {
            val pom = """<?xml version="1.0" encoding="UTF-8"?>
<project
    xmlns="http://maven.apache.org/POM/4.0.0"
    xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
    xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>$GROUP</groupId>
  <artifactId>${artifact.name}</artifactId>
  <version>${artifact.insideVersion}</version>
  
  <dependencies>${
            artifact.dependsOn.map { (dependency, scope) ->
                """
    <dependency>
        <groupId>$GROUP</groupId>
        <artifactId>${dependency.name}</artifactId>
        <version>${dependency.outsideVersion}</version>
        <scope>${scope}</scope>
    </dependency> 
        """
            }.joinToString("")
  }</dependencies>
</project>
            """

            // Create artifact and pom
            val path = GROUP / artifact.name / artifact.outsideVersion

            (repoFolder / path).let { folder ->
                Files.createDirectories(folder)
                (folder / "${artifact.name}-${artifact.insideVersion}.jar").write(artifact.artifact)
                (folder / "${artifact.name}-${artifact.insideVersion}.pom").write(pom)
            }

            if (artifact.cache != null) {
                (cacheRepoFolder / path).let { folder ->
                    Files.createDirectories(folder)
                    (folder / "${artifact.name}-${artifact.insideVersion}.jar").write(artifact.cache!!)
                    (folder / "${artifact.name}-${artifact.insideVersion}.pom").write(pom)
                }
            }
        }

        fun writeMavenMetadata(path:Path, snapshots:List<TestArtifact>, fileSuffix:String = "") {
            if (snapshots.isEmpty()) {
                return
            }

            val metadata = """<?xml version="1.0" encoding="UTF-8"?>
<metadata modelVersion="1.1.0">
  <groupId>$GROUP</groupId>
  <artifactId>${snapshots.first().name}</artifactId>
  <version>${snapshots.first().outsideVersion}</version>
  <versioning>
    <snapshot>
      <timestamp>${snapshots.last().snapshotTimestamp}</timestamp>
      <buildNumber>${snapshots.last().buildNumber}</buildNumber>
    </snapshot>
    <lastUpdated>${snapshots.last().snapshotTimestamp.replace(".", "")}</lastUpdated>
    <snapshotVersions>${
            snapshots.map { snapshot ->
                """
        <snapshotVersion>
            <extension>jar</extension>
            <value>${snapshot.insideVersion}</value>
            <updated>${snapshot.snapshotTimestamp.replace(".", "")}</updated>
        </snapshotVersion>"""
            }.joinToString("")
    }</snapshotVersions>
  </versioning>
</metadata>
            """
            (path / "maven-metadata$fileSuffix.xml").write(metadata)
        }

        for ((path, snapshots) in artifacts.filter { it.buildNumber >= 0 }.sortedBy { it.buildNumber }.groupBy { (GROUP / it.name / it.outsideVersion).toString() }) {
            writeMavenMetadata(repoFolder / path, snapshots)
            writeMavenMetadata(cacheRepoFolder / path, snapshots.filter { it.cache != null }, "-$repositoryName")
        }
    }
}

val TEST_REPOSITORY_BASE = (wemi.boot.WemiCacheFolder / "-test-repository").apply {
    ensureEmptyDirectory()
}

fun BindingHolder.setTestRepository(name:String, snapshotUpdateDelaySeconds:Long = SnapshotCheckDaily, replace:Boolean = true, create:TestRepositoryData.()->Unit) {
    val repoFolder = TEST_REPOSITORY_BASE / name
    val repoCacheFolder = TEST_REPOSITORY_BASE / "$name-cache"

    val data = TestRepositoryData(name)
    data.create()
    data.buildIn(repoFolder, repoCacheFolder)

    val repo = Repository(name, repoFolder, repoCacheFolder, local = false, snapshotUpdateDelaySeconds = snapshotUpdateDelaySeconds)

    if (replace) {
        repositories set { setOf(repo) }
    } else {
        repositories add { repo }
    }
}

fun BindingHolder.setTestCacheRepository(name:String) {
    val repoCacheFolder = TEST_REPOSITORY_BASE / "$name-cache"
    
    repositories modify { it.map { repo -> if (repo.cache == null) repo else repo.copy(cache = repoCacheFolder) }.toSet() }
}

// -------------------------------------------- Basic tests ------------------------------------------------------

val release_1 by configuration("") {
    setTestRepository("release_1") {
        artifact("king", "1")
    }

    libraryDependencies set { setOf(dependency(GROUP, "king", "1")) }

    checkResolution set {
        assertClasspathContains("king 1")
    }
}

val release_2 by configuration("") {
    setTestRepository("release_2") {
        artifact("king", "1").dependsOn(artifact("queen", "1"))
    }

    libraryDependencies set { setOf(dependency(GROUP, "king", "1")) }

    checkResolution set {
        assertClasspathContains("queen 1" /* through dependency in king */, "king 1")
    }
}

// -------------------------------------- Non-unique snapshot tests ---------------------------------------------------

val non_unique_1 by configuration("") {
    setTestRepository("non_unique_1") {
        artifact("king", "1-SNAPSHOT")
    }

    libraryDependencies set { setOf(dependency(GROUP, "king", "1-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("king 1-SNAPSHOT")
    }
}

val non_unique_2 by configuration("") {
    setTestRepository("non_unique_2") {
        artifact("king", "1-SNAPSHOT").hasCache("cached king")
    }

    libraryDependencies set { setOf(dependency(GROUP, "king", "1-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("cached king")
    }
}

val non_unique_3 by configuration("") {
    setTestRepository("non_unique_3", SnapshotCheckAlways) {
        artifact("king", "1-SNAPSHOT").hasCache("cached king")
    }

    libraryDependencies set { setOf(dependency(GROUP, "king", "1-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("king 1-SNAPSHOT")
    }
}

// -------------------------------------- Unique snapshot tests ---------------------------------------------------

val unique_1 by configuration("") {
    setTestRepository("unique_1") {
        snapshotArtifact("king", "2", 1)
    }

    libraryDependencies set { setOf(dependency(GROUP, "king", "2-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("king 2 1")
    }
}

val unique_2 by configuration("") {
    setTestRepository("unique_2") {
        snapshotArtifact("king", "2", 1).hasCache("cached king")
        snapshotArtifact("king", "2", 2)
    }

    libraryDependencies set { setOf(dependency(GROUP, "king", "2-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("cached king")
    }
}

val unique_3 by configuration("") {
    setTestRepository("unique_3", SnapshotCheckAlways) {
        snapshotArtifact("king", "2", 1).hasCache("cached king")
        snapshotArtifact("king", "2", 2)
    }

    libraryDependencies set { setOf(dependency(GROUP, "king", "2-SNAPSHOT")) }

    checkResolution set {
        assertClasspathContains("king 2 2")
    }
}

val unique_4 by configuration("") {
    var snapshotVersion = ""
    setTestRepository("unique_4", SnapshotCheckAlways) {
        val older = snapshotArtifact("king", "2", 1)
        snapshotVersion = "${older.snapshotTimestamp}-${older.buildNumber}"
        snapshotArtifact("king", "2", 2)
    }

    libraryDependencies set { setOf(dependency(GROUP, "king", "2-SNAPSHOT", snapshotVersion = snapshotVersion)) }

    checkResolution set {
        assertClasspathContains("king 2 1")
    }
}

val mavenScopeFiltering by configuration("") {
    setTestRepository("mavenScopeFiltering") {
        val testingLib = artifact("testing-lib", "1")
        val someApi = artifact("some-api", "1")
        val someImplementation = artifact("some-implementation", "1")

        artifact("magnum-opus", "1")
            .dependsOn(testingLib, scope = "test") // Not present, because test is not transitive
            .dependsOn(someApi, scope = "provided") // Also not present, because provided is not transitive
            .dependsOn(someImplementation, scope = "runtime")

        artifact("frothy-chocolate", "1")
        artifact("run-like-hell", "2")
        artifact("testing-attention-please", "1")
    }

    libraryDependencies set { setOf(
            dependency(GROUP, "magnum-opus", "1"),
            dependency(GROUP, "frothy-chocolate", "1", scope = "provided"),
            dependency(GROUP, "run-like-hell", "2", scope = "runtime"),
            dependency(GROUP, "testing-attention-please", "1", scope = "test")
    ) }

    checkResolution set {
        // Must not resolve to testing jars which jline uses
        assertClasspathContains("magnum-opus 1", "some-implementation 1", "frothy-chocolate 1", "run-like-hell 2", "testing-attention-please 1")

        using(compiling) {
            // When compiling, runtime dependencies should be ommited
            assertClasspathContains("magnum-opus 1", "frothy-chocolate 1")
        }

        using(running) {
            // When running, provided dependencies should be ommited
            assertClasspathContains("magnum-opus 1", "some-implementation 1", "run-like-hell 2")
        }

        using(testing) {
            assertClasspathContains("magnum-opus 1", "some-implementation 1", "frothy-chocolate 1", "run-like-hell 2", "testing-attention-please 1")
        }
    }
}

val mavenScopeResolution_1 by configuration("") {
    setTestRepository("mavenScopeResolution_1") {
        val first = artifact("first", "1")
        val second = artifact("second", "1")

        val end = artifact("end", "1")

        first.dependsOn(end, scope = "compile")
        second.dependsOn(end, scope = "compile")
    }

    libraryDependencies set { setOf(
            dependency(GROUP, "first", "1", scope = "test"),
            dependency(GROUP, "second", "1", scope = "compile")
            ) }

    checkResolution set {
        assertClasspathContains("first 1", "second 1", "end 1")

        using(compiling) {
            assertClasspathContains("second 1", "end 1")
        }

        using(testing) {
            assertClasspathContains("first 1", "second 1", "end 1")
        }
    }

}

val problematic_1 by configuration("") {
    setTestCacheRepository("problematic_1")

    // Uses some properties
    libraryDependencies set { setOf(dependency("org.eclipse.jetty.websocket:websocket-client:9.4.15.v20190215")) }

    checkResolution set {
        assertClasspathContainsFiles("websocket-client-9.4.15.v20190215.jar", "jetty-client-9.4.15.v20190215.jar", "jetty-xml-9.4.15.v20190215.jar", "jetty-util-9.4.15.v20190215.jar", "jetty-io-9.4.15.v20190215.jar", "websocket-common-9.4.15.v20190215.jar", "jetty-http-9.4.15.v20190215.jar", "websocket-api-9.4.15.v20190215.jar")
    }
}

val problematic_2 by configuration("") {
    setTestCacheRepository("problematic_2")

    // Has cyclic dependency
    // (Fixed in newer versions. xml-apis seems to be missing from the Maven central, so it is excluded) 
    libraryDependencies set { setOf(dependency("org.apache.xmlgraphics", "batik-gvt", "1.6.1", exclusions = listOf(DependencyExclusion("xml-apis", "xml-apis")))) }

    checkResolution set {
        assertClasspathContainsFiles("batik-gvt-1.6.1.jar", "batik-awt-util-1.6.1.jar", "batik-bridge-1.6.1.jar", "batik-util-1.6.1.jar", "batik-gui-util-1.6.1.jar", "batik-css-1.6.1.jar", "batik-dom-1.6.1.jar", "batik-ext-1.6.1.jar", "batik-parser-1.6.1.jar", "batik-script-1.6.1.jar", "batik-svg-dom-1.6.1.jar", "batik-xml-1.6.1.jar")
    }
}

val problematic_3 by configuration("") {
    setTestCacheRepository("problematic_3")

    // Uses BOM in combination with dependencyManagement
    libraryDependencies set { setOf(dependency("org.jboss.arquillian.junit:arquillian-junit-container:1.5.0.Final")) }

    checkResolution set {
        assertClasspathContainsFiles("arquillian-junit-container-1.5.0.Final.jar", "arquillian-junit-core-1.5.0.Final.jar", "arquillian-test-api-1.5.0.Final.jar", "arquillian-test-spi-1.5.0.Final.jar", "arquillian-container-test-api-1.5.0.Final.jar", "arquillian-container-test-spi-1.5.0.Final.jar", "arquillian-core-impl-base-1.5.0.Final.jar", "arquillian-test-impl-base-1.5.0.Final.jar", "arquillian-container-impl-base-1.5.0.Final.jar", "arquillian-container-test-impl-base-1.5.0.Final.jar", "shrinkwrap-impl-base-1.2.6.jar", "arquillian-core-api-1.5.0.Final.jar", "arquillian-core-spi-1.5.0.Final.jar", "shrinkwrap-api-1.2.6.jar", "shrinkwrap-descriptors-api-base-2.0.0.jar", "arquillian-container-spi-1.5.0.Final.jar", "arquillian-config-api-1.5.0.Final.jar", "arquillian-config-impl-base-1.5.0.Final.jar", "shrinkwrap-descriptors-spi-2.0.0.jar", "shrinkwrap-spi-1.2.6.jar", "arquillian-config-spi-1.5.0.Final.jar")
    }
}

val problematic_4 by configuration("") {
    setTestCacheRepository("problematic_4")

    repositories add { JCenter }
    // Uses pom profile (in dependencies)
    libraryDependencies set { setOf(dependency("org.jetbrains.kotlinx", "kotlinx-html-jvm", "0.6.12")) }

    checkResolution set {
        assertClasspathContainsFiles("annotations-13.0.jar", "kotlin-stdlib-1.2.71.jar", "kotlinx-html-jvm-0.6.12.jar", "kotlin-stdlib-common-1.2.71.jar")
    }
}

val unsafeRepositoryDownload by configuration("") {
    repositories set { setOf(MavenCentral, Repository("jitpack", java.net.URL("https://jitpack.io/"), useUnsafeTransport = true)) }

    setTestCacheRepository("unsafeRepositoryDownload")

    // Something random
    libraryDependencies set { setOf(dependency("com.darkyen", "tproll", "v1.3.1")) }

    checkResolution set {
        assertClasspathContainsFiles("tproll-v1.3.1.jar", "joda-time-2.10.1.jar")
    }
}

val artifactInMultipleRepositories_1 by configuration("") {
    setTestRepository("artifactInMultipleRepositories_1_a") {
        artifact("foo", "1")
    }
    setTestRepository("artifactInMultipleRepositories_1_b", replace = false) {
        artifact("foo", "1")
    }

    libraryDependencies set { setOf(dependency(GROUP, "foo", "1")) }

    checkResolution set {
        assertClasspathContains("foo 1")
    }
}

val artifactInMultipleRepositories_2 by configuration("") {
    setTestRepository("artifactInMultipleRepositories_2_a") {
        artifact("foo", "1", content="foo 1 a")
    }
    setTestRepository("artifactInMultipleRepositories_2_b", replace = false) {
        artifact("foo", "1", content="foo 1 b")
    }

    libraryDependencies set { setOf(dependency(GROUP, "foo", "1")) }

    checkResolution set {
        assertClasspathContains("foo 1 a")
    }
}

val dependency_resolution by project() {
    val longRunning = true

    extend(testing) {
        // Remove automatically added JUnit stuff
        libraryDependencies modify { it.filter { dep -> !dep.dependencyId.group.startsWith("org.junit") }.toSet() }
    }

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

    // Check if correct dependency artifacts are downloaded
    autoRun(checkResolution, mavenScopeFiltering)
    autoRun(checkResolution, mavenScopeResolution_1)
    
    // Problematic dependencies that broke something previously or are weird
    if (longRunning) {
        autoRun(checkResolution, problematic_1)
        autoRun(checkResolution, problematic_2)
        autoRun(checkResolution, problematic_3)
        autoRun(checkResolution, problematic_4)
    }

    if (longRunning) {
        autoRun(checkResolution, unsafeRepositoryDownload)
    }
    autoRun(checkResolution, artifactInMultipleRepositories_1)
    autoRun(checkResolution, artifactInMultipleRepositories_2)

    checkResolution set {
        println()
        println("Assertions: $classpathAssertions")
        println("Failed As.: $classpathAssertionsFailed")
        println()
    }
    autoRun(checkResolution)
}