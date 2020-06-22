package wemi.dependency.internal

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.xml.sax.Attributes
import org.xml.sax.ErrorHandler
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.helpers.XMLReaderFactory
import wemi.ActivityListener
import wemi.dependency.ArtifactPath
import wemi.dependency.CHECKSUMS
import wemi.dependency.Classifier
import wemi.dependency.CompatibleSortedRepositories
import wemi.dependency.DEFAULT_OPTIONAL
import wemi.dependency.DEFAULT_SCOPE
import wemi.dependency.DEFAULT_SNAPSHOT_VERSION
import wemi.dependency.DEFAULT_TYPE
import wemi.dependency.Dependency
import wemi.dependency.DependencyExclusion
import wemi.dependency.DependencyId
import wemi.dependency.NoClassifier
import wemi.dependency.Repository
import wemi.dependency.ResolvedDependency
import wemi.dependency.SortedRepositories
import wemi.dependency.internal.PomBuildingXMLHandler.Companion.SupportedModelVersion
import wemi.publish.InfoNode
import wemi.submit
import wemi.util.Color
import wemi.util.Failable
import wemi.util.Partial
import wemi.util.createHashSum
import wemi.util.directorySynchronized
import wemi.util.div
import wemi.util.format
import wemi.util.name
import wemi.util.pathExtension
import wemi.util.toPath
import wemi.util.writeText
import java.io.ByteArrayInputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Future
import kotlin.collections.HashMap

private val LOG = LoggerFactory.getLogger("Maven2")

/*
New maven resolution strategy:
1. Put all dependencies into a queue
2. Take dep from queue, mark it as being resolved
3. Resolve its pom fully, including parent pom.
    1. First check all caches
    2. then all local repositories
    3. finally all remote repositories (to detect multi-publish attack, but don't block, just warn)
    - Parent pom can be in a different repository (will happen for jitpack things with parents)
    - Save to cache after it has been resolved
4. Resolve the POM's dependencies, this requires dependencyManagement, but only from the pom and its parents
    - Keep all dependency scopes for now, but don't resolve the ones which don't need to be resolved
    - If dependency matches the one in dependencyManagement, it is overwritten by it
    - Transitive dependencies are also resolved using dependencyManagement, but "our" dependencyManagement has precedence
        - It is thus propagated through the system
5. Record that it has been resolved, save corresponding POM to pom map
6. For any conflicting dependencies, warn about them, then do mediation algorithm
    - Use the nearest or the first dependency
    - Their scope may change: https://cwiki.apache.org/confluence/display/MAVENOLD/Dependency+Mediation+and+Conflict+Resolution (bottom) (Yes, but will it really?)
    - Optional dependencies are noted, but no artifacts are downloaded for them
7. When all metadata is read, start downloading dependencies themselves
 */

private class DependencyManagementKey(val groupId:String, val name:String, val classifier:String, val type:String) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DependencyManagementKey) return false

        if (groupId != other.groupId) return false
        if (name != other.name) return false
        if (classifier != other.classifier) return false
        if (type != other.type) return false

        return true
    }

    override fun hashCode(): Int {
        var result = groupId.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + classifier.hashCode()
        result = 31 * result + type.hashCode()
        return result
    }
}

/**
 * Resolves artifacts for [dependencies] and their transitive dependencies.
 *
 * Remembers errors, but does not stop on them and tries to resolve as much as possible.
 *
 * @param dependencies to resolve
 * @param repositories to use
 * @param mapper to modify which dependency is actually resolved
 */
internal fun resolveArtifacts(dependencies: Collection<Dependency>,
                              repositories: SortedRepositories,
                              mapper: (Dependency) -> Dependency,
                              progressTracker: ActivityListener?):Partial<Map<DependencyId, ResolvedDependency>> {
    if (dependencies.isEmpty()) {
        return Partial(emptyMap(), true)
    } else if (repositories.isEmpty()) {
        LOG.warn("Can't resolve {}: No repositories specified", dependencies)
        return Partial(emptyMap(), false)
    }

    val resolved = HashMap<DependencyId, RawResolvedDependency>()
    val resolvedScope = HashMap<DependencyId, String>()
    val resolvedPartials = HashMap<DependencyManagementKey, DependencyId>()

    val pool = ForkJoinPool(10)

    class Resolved(val dependency:Dependency, val resolvedDependency:RawResolvedDependency) {
        operator fun component1() = dependency
        operator fun component2() = resolvedDependency
    }

    fun submitDependency(dep:Dependency): Future<Resolved>? {
        LOG.trace("submitDependency({})", dep)
        val depId = dep.dependencyId
        val partialId = DependencyManagementKey(depId.group, depId.name, depId.classifier, depId.type)

        // Check if we haven't resolved this dependency (or its different version) previously
        val resolvedPreviouslyPartialId = resolvedPartials[partialId]
        val resolvedPreviouslyScope = resolvedScope[resolvedPreviouslyPartialId ?: depId]
        if (resolvedPreviouslyScope != null) {
            if (resolvedPreviouslyPartialId == null) {
                LOG.debug("{} already resolved, skipping", dep)
            } else {
                LOG.debug("{} already resolved by {}, skipping", dep, resolvedPreviouslyPartialId)
            }

            // We did already resolve it, its scope may need adjusting.
            val newScope = resolveScopes(resolvedPreviouslyScope, dep.scope)
            if (newScope != resolvedPreviouslyScope) {
                resolvedScope[resolvedPreviouslyPartialId ?: depId] = newScope
            }
            return null
        }

        resolvedPartials[partialId] = depId
        resolvedScope[depId] = dep.scope

        return pool.submit({ tracker ->
            val resolvedDep = resolveInM2Repository(depId, dep.dependencyManagement, repositories, tracker)
            Resolved(dep, resolvedDep)
        }, progressTracker, dep.dependencyId.toString())
    }

    var noError = true
    var level = dependencies.mapNotNull(::submitDependency)

    while (level.isNotEmpty()) {
        val nextLevel = ArrayList<Future<Resolved>>()
        for (future in level) {
            val (dep, resolvedDep) = future.get()

            resolved[dep.dependencyId] = resolvedDep

            if (resolvedDep.hasError) {
                noError = false
                continue
            }

            for (transitiveDependency in resolvedDep.dependencies) {
                if (transitiveDependency.optional) {
                    LOG.debug("Excluded optional {} (dependency of {})", transitiveDependency.dependencyId, dep.dependencyId)
                    continue
                }

                val exclusionRule = dep.exclusions.find { it.excludes(transitiveDependency.dependencyId) }
                if (exclusionRule != null) {
                    LOG.debug("Excluded {} with rule {} (dependency of {})", transitiveDependency.dependencyId, exclusionRule, dep.dependencyId)
                    continue
                }

                // Resolve scope
                // (See the table at https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope)
                val resolvedToScope:String? = when (transitiveDependency.scope) {
                    "compile" -> when (dep.scope) {
                        "compile" -> "compile"
                        "provided" -> "provided"
                        "runtime" -> "runtime"
                        "test" -> "test"
                        else -> null
                    }
                    "runtime" -> when (dep.scope) {
                        "compile" -> "runtime"
                        "provided" -> "provided"
                        "runtime" -> "runtime"
                        "test" -> "test"
                        else -> null
                    }
                    else -> null
                }

                if (resolvedToScope == null) {
                    LOG.debug("Excluded {} due to transitive scope change (dependency of {})", transitiveDependency.dependencyId, dep.dependencyId)
                    continue
                }

                val transitivelyAdjusted = if (transitiveDependency.scope == resolvedToScope && dep.exclusions.isEmpty()) {
                    transitiveDependency
                } else {
                    if (transitiveDependency.scope != resolvedToScope) {
                        LOG.debug("Changing the scope of {} to {}", transitiveDependency, resolvedToScope)
                    }
                    Dependency(transitiveDependency.dependencyId, resolvedToScope, transitiveDependency.optional, dep.exclusions + transitiveDependency.exclusions, transitiveDependency.dependencyManagement)
                }

                val mapped = mapper(transitivelyAdjusted)
                if (mapped != transitivelyAdjusted) {
                    LOG.debug("{} mapped to {}", transitivelyAdjusted, mapped)
                }

                submitDependency(mapped)?.let { nextLevel.add(it) }
            }
        }
        level = nextLevel
    }

    pool.shutdown()

    if (LOG.isInfoEnabled) {
        val stack = ArrayList<DependencyId>()
        for (root in dependencies) {
            reportCyclicDependencies(resolved, stack, root.dependencyId)
            assert(stack.isEmpty())
        }
    }

    val resolvedComplete = resolved.mapValues { (k, v) ->
        v.complete(resolvedScope[k]!!)
    }

    return Partial(resolvedComplete, noError)
}

/** Returns the scope of an artifact, which is required by two different scopes. */
private fun resolveScopes(nearest:String, furthest:String):String {
    // Based on https://cwiki.apache.org/confluence/display/MAVENOLD/Dependency+Mediation+and+Conflict+Resolution
    return when (nearest) {
        "compile" -> "compile"
        "provided" -> when (furthest) {
            "compile" -> "compile"
            "runtime" -> "runtime"
            else -> nearest
        }
        "test" -> when (furthest) {
            "compile" -> "compile"
            "runtime" -> "runtime"
            else -> nearest
        }
        else -> when (furthest) {
            "compile" -> "compile"
            else -> nearest
        }
    }
}

/** Recursive depth-first-search of resolved dependencies to find and report circular dependencies. */
private fun reportCyclicDependencies(resolved:Map<DependencyId, RawResolvedDependency>, stack:ArrayList<DependencyId>, root:DependencyId) {
    val loopIndex = stack.indexOf(root)
    if (loopIndex != -1) {
        // Cyclic dependency detected
        val arrow = " → "

        val sb = StringBuilder()
        sb.format(Color.White)
        for (i in 0 until loopIndex) {
            sb.append(stack[i]).append(arrow)
        }
        sb.format(Color.Black)
        sb.append('↪').format().append(' ')
        for (i in loopIndex until stack.size) {
            sb.append(stack[i].toString()).append(arrow)
        }
        sb.setLength(maxOf(sb.length - arrow.length, 0))
        sb.append(' ').format(Color.Black).append('↩').format()

        LOG.info("Circular dependency: {}", sb)
        return
    }

    val resolvedRoot = resolved[root] ?: return
    stack.add(root)
    for (dependency in resolvedRoot.dependencies) {
        reportCyclicDependencies(resolved, stack, dependency.dependencyId)
    }
    stack.removeAt(stack.lastIndex)
}

/** Maven type to extension mapping for types that are not equal to their extensions.
 * See https://maven.apache.org/ref/3.6.1/maven-core/artifact-handlers.html */
private val TYPE_TO_EXTENSION_MAPPING:Map<String, String> = mapOf(
        "bundle" to "jar",
        "ejb" to "jar",
        "ejb-client" to "jar",
        "test-jar" to "jar",
        "maven-plugin" to "jar",
        "java-source" to "jar",
        "javadoc" to "jar"
)

/** Same as [ResolvedDependency], but without some fields which are filled in later, such as [ResolvedDependency.scope]. */
private class RawResolvedDependency private constructor(
        val id: DependencyId, val dependencies: List<Dependency>,
        val resolvedFrom: Repository?, val log: CharSequence?, val artifact:ArtifactPath?) {

    /** Error constructor */
    constructor(id:DependencyId, log:CharSequence, resolvedFrom:Repository? = null)
            : this(id, emptyList(), resolvedFrom, log, null)

    /** Success constructor */
    constructor(id:DependencyId, dependencies:List<Dependency>, resolvedFrom:Repository, artifact:ArtifactPath)
            :this(id, dependencies, resolvedFrom, null, artifact)

    /** `true` if this dependency failed to resolve (partially or completely), for any reason */
    val hasError: Boolean
        get() = log != null

    fun complete(scope: String): ResolvedDependency {
        return if (hasError) {
            ResolvedDependency(id, log!!, resolvedFrom)
        } else {
            ResolvedDependency(id, scope, dependencies, resolvedFrom!!, artifact!!)
        }
    }
}

/** Attempt to resolve [dependencyId] in [repositories] */
private fun resolveInM2Repository(
        dependencyId: DependencyId,
        transitiveDependencyManagement:List<Dependency>,
        repositories: SortedRepositories,
        progressTracker: ActivityListener?): RawResolvedDependency {
    val snapshot = dependencyId.isSnapshot
    val compatibleRepositories:CompatibleSortedRepositories = repositories.filter { repository ->
        if (if (snapshot) repository.snapshots else repository.releases) {
            true
        } else {
            LOG.debug("Skipping {} for {} due to incompatible release/snapshot policy", repository, dependencyId)
            false
        }
    }

    var usedRepositories = compatibleRepositories
    while (true) {
        // Retrieve basic POM data
        val retrievedPom = retrievePom(dependencyId, usedRepositories, snapshot, progressTracker).use({ it }, { return it })
        val resolvedDependencyId = retrievedPom.id
        val repository = retrievedPom.path.artifactPath.repository

        if (resolvedDependencyId.type.equals("pom", ignoreCase = true)) {
            return RawResolvedDependency(resolvedDependencyId, emptyList(), repository, retrievedPom.path.artifactPath)
        }

        val resolvedPom = resolveRawPom(retrievedPom.path.artifactPath, transitiveDependencyManagement, usedRepositories, progressTracker).fold {
            it.resolve(transitiveDependencyManagement, usedRepositories, progressTracker)
        }

        val pom = resolvedPom.use({ it }, { log ->
            LOG.debug("Failed to resolve POM for {} from {}: {}", resolvedDependencyId, repository, log)
            return RawResolvedDependency(resolvedDependencyId, log, repository)
        })

        val extension = TYPE_TO_EXTENSION_MAPPING.getOrDefault(resolvedDependencyId.type, resolvedDependencyId.type)

        val filePath = artifactPath(resolvedDependencyId.group, resolvedDependencyId.name, resolvedDependencyId.version, resolvedDependencyId.classifier, extension, resolvedDependencyId.snapshotVersion)
        val artifactRepositories = ArrayList<Repository>(1 + retrievedPom.path.alternateRepositories.size)
        artifactRepositories.add(repository)
        retrievedPom.path.alternateRepositories.forEach { artifactRepositories.add(it) }

        retrieveFile(filePath, snapshot, artifactRepositories, progressTracker = progressTracker).use<Unit>({ retrieved ->
            if (retrieved.alternateRepositories.isNotEmpty()) {
                LOG.info("{} has been also found in these repositories: {}", dependencyId, retrieved.alternateRepositories)
            }
            if (retrieved.possibleAlternateRepositories.isNotEmpty()) {
                LOG.trace("{} may also be in these repositories that weren't checked: {}", dependencyId, retrieved.possibleAlternateRepositories)
            }

            // Purge retrieved data, storing it would only create a memory leak, as the value is rarely used,
            // can always be lazily loaded and the size of all dependencies can be quite big.
            val retrievedArtifact = retrieved.artifactPath
            retrievedArtifact.data = null
            return RawResolvedDependency(resolvedDependencyId, pom.dependencies, repository, retrievedArtifact)
        }, { failure ->
            if (retrievedPom.path.possibleAlternateRepositories.isNotEmpty()) {
                usedRepositories = retrievedPom.path.possibleAlternateRepositories.toList()
            } else {
                LOG.warn("Failed to retrieve file at '{}' in {}", filePath, compatibleRepositories)
                return RawResolvedDependency(resolvedDependencyId, failure)
            }
        })
    }
}

private fun resolveRawPom(pomArtifact:ArtifactPath, transitiveDependencyManagement:List<Dependency>, repositories: SortedRepositories, progressTracker: ActivityListener?): Failable<RawPom, String> {
    val pomUrl:URL = pomArtifact.url
    val pomData:ByteArray = pomArtifact.data ?: return Failable.failure("Failed to retrieve pom data")

    val rawPom = RawPom(pomUrl)

    val reader = XMLReaderFactory.createXMLReader()
    val pomBuilder = PomBuildingXMLHandler(rawPom)
    reader.contentHandler = pomBuilder
    reader.errorHandler = pomBuilder
    try {
        reader.parse(InputSource(ByteArrayInputStream(pomData)))
    } catch (se: SAXException) {
        LOG.warn("Error during parsing {}", pomUrl, se)
        return Failable.failure("Malformed xml")
    }

    if (pomBuilder.hasParent) {
        val parentPomId = DependencyId(pomBuilder.parentGroupId,
                pomBuilder.parentArtifactId,
                pomBuilder.parentVersion,
                type = "pom")

        if (pomBuilder.parentRelativePath.isNotBlank()) {
            LOG.debug("Ignoring relative path to parent pom of '{}'", pomUrl)
        }

        LOG.trace("Retrieving parent pom of '{}' by coordinates '{}', in same repository", pomUrl, parentPomId)
        val resolvedPom = resolveInM2Repository(parentPomId, transitiveDependencyManagement, repositories, progressTracker)
        val artifact = resolvedPom.artifact

        var parent: RawPom? = null
        if (artifact != null) {
            resolveRawPom(artifact, transitiveDependencyManagement, repositories, progressTracker)
                    .success { pom ->
                        parent = pom
                    }
        }

        if (parent == null) {
            LOG.warn("Pom at '{}' in {} claims to have a parent, but it has not been found (resolved to {})", pomUrl, pomArtifact.repository, parentPomId)
        } else {
            rawPom.parent = parent
        }
    }

    return Failable.success(rawPom)
}

private class RetrievedPom(
        /** ID of the actually retrieved pom. May be different than requested pom in case of unique snapshots,
         *  in which case the snapshot version will be filled out. */
        val id:DependencyId,
        val path:ArtifactPathWithAlternateRepositories)

/** Retrieve raw pom file for given [dependencyId] in [repositories].
 * If [snapshot] and it is unique snapshot, it resolves maven-metadata.xml and returns the pom for the newest version. */
private fun retrievePom(dependencyId: DependencyId, repositories: CompatibleSortedRepositories, snapshot:Boolean, progressTracker: ActivityListener?):Failable<RetrievedPom, RawResolvedDependency> {
    LOG.trace("Retrieving pom for {} at '{}'", dependencyId, repositories)
    // Handles normal poms and old non-unique snapshot poms
    val basePom = retrieveFile(pomPath(dependencyId.group, dependencyId.name, dependencyId.version, dependencyId.snapshotVersion), snapshot, repositories, progressTracker)
    basePom.success { pom ->
        return Failable.success(RetrievedPom(dependencyId, pom))
    }

    // Handle unique snapshots with maven-metadata.xml file
    if (snapshot && dependencyId.snapshotVersion.isEmpty()) {
        // Query for maven-metadata.xml (https://github.com/strongbox/strongbox/wiki/Maven-Metadata)
        val mavenMetadataPath = mavenMetadataPath(dependencyId, null)
        val staleRepositories = ArrayList<Repository>()
        val freshestRepositories = ArrayList<Repository>()
        var freshestRepositoryVersion:String? = null

        val failures = StringBuilder()
        for (repository in repositories) {
            val mavenMetadataFileFailable = retrieveFile(mavenMetadataPath, true,
                    listOf(repository), progressTracker) { repo -> mavenMetadataPath(dependencyId, repo) }
            val mavenMetadataFile = mavenMetadataFileFailable.use({ it }, { failure ->
                if (failures.isNotEmpty()) {
                    failures.append(' ')
                }
                failures.append("${repository.name}: $failure")
                null
            }) ?: continue

            val metadataBuilder = MavenMetadataBuildingXMLHandler.buildFrom(mavenMetadataFile.artifactPath)
            val snapshotVersion = metadataBuilder.use({ metadata ->
                val timestamp = metadata.versioningSnapshotTimestamp
                val buildNumber = metadata.versioningSnapshotBuildNumber
                if (timestamp == null) {
                    LOG.warn("Failed to parse snapshot metadata xml of {} in {}: timestamp is missing", dependencyId, repository)
                    null
                } else "$timestamp-$buildNumber"
            }, { log ->
                LOG.warn("Failed to parse snapshot metadata xml of {} in {}: {}", dependencyId, repository, log)
                null
            }) ?: continue

            if (freshestRepositoryVersion == null) {
                freshestRepositories.add(repository)
                freshestRepositoryVersion = snapshotVersion
            } else if (freshestRepositoryVersion == snapshotVersion) {
                freshestRepositories.add(repository)
            } else if (freshestRepositoryVersion < snapshotVersion) {
                staleRepositories.addAll(freshestRepositories)
                freshestRepositories.clear()
                freshestRepositories.add(repository)
                freshestRepositoryVersion = snapshotVersion
            } else /* freshestRepositoryVersion > snapshotVersion */ {
                staleRepositories.add(repository)
            }
        }

        if (freshestRepositoryVersion == null) {
            return Failable.failure(RawResolvedDependency(dependencyId, "Failed to resolve snapshot metadata: $failures"))
        }

        LOG.debug("Resolving {} in {} with snapshot version {}", dependencyId, freshestRepositories, freshestRepositoryVersion)
        if (staleRepositories.isNotEmpty()) {
            LOG.debug("Less fresh snapshots of {} are also available in {}", dependencyId, staleRepositories)
        }

        val newRetrievedPom = retrieveFile(pomPath(dependencyId.group, dependencyId.name, dependencyId.version, freshestRepositoryVersion), snapshot, freshestRepositories, progressTracker)

        newRetrievedPom.use<Unit>({ pom ->
            return Failable.success(RetrievedPom(dependencyId.copy(snapshotVersion = freshestRepositoryVersion), pom))
        }, { failMessage ->
            return Failable.failure(RawResolvedDependency(dependencyId, "Failed to resolve pom xml for deduced snapshot version \"$freshestRepositoryVersion\" in $freshestRepositories: $failMessage"))
        })
    }

    return Failable.failure(RawResolvedDependency(dependencyId, "Failed to resolve pom xml: ${basePom.failure}"))
}

private fun pomPath(group:String, name:String, version:String, snapshotVersion:String):String {
    val sb = group.replace('.', '/') / name / version
    sb.append('/').append(name).append('-')
    val SNAPSHOT = "SNAPSHOT"
    if (version.endsWith(SNAPSHOT) && snapshotVersion.isNotEmpty()) {
        sb.append(version, 0, version.length - SNAPSHOT.length).append(snapshotVersion)
    } else {
        sb.append(version)
    }
    sb.append(".pom")
    return sb.toString()
}

private fun artifactPath(group:String, name:String, version:String, classifier: Classifier, extension: String, snapshotVersion:String):String {
    val fileName = StringBuilder()
    fileName.append(name).append('-')

    val SNAPSHOT = "SNAPSHOT"
    if (version.endsWith(SNAPSHOT) && snapshotVersion.isNotEmpty()) {
        fileName.append(version, 0, version.length - SNAPSHOT.length).append(snapshotVersion)
    } else {
        fileName.append(version)
    }
    if (classifier.isNotEmpty()) {
        fileName.append('-').append(classifier)
    }
    fileName.append('.').append(extension)

    return (group.replace('.', '/') / name / version / fileName).toString()
}

private fun mavenMetadataPath(dependencyId: DependencyId, repositoryIfCache: Repository?):String {
    val base = (dependencyId.group.replace('.', '/') / dependencyId.name / dependencyId.version)
    base.append("/maven-metadata")
    if (repositoryIfCache != null) {
        base.append('-').append(repositoryIfCache.name)
    }
    base.append(".xml")
    return base.toString()
}

/**
 * Publish [artifacts]s to this [repository], under given [metadata]
 * and return the general location to where it was published.
 *
 * @param artifacts list of artifacts and their classifiers if any
 */
internal fun publish(repository: Repository, metadata: InfoNode, artifacts: List<Pair<Path, Classifier>>): Path {
    val lock = repository.directoryPath()

    return if (lock != null) {
        directorySynchronized(lock) {
            publishLocked(repository, metadata, artifacts)
        }
    } else {
        publishLocked(repository, metadata, artifacts)
    }
}

private fun checkValidForPublish(path:Path, snapshot:Boolean) {
    if (Files.exists(path)) {
        if (snapshot) {
            LOG.info("Overwriting {}", path)
        } else {
            throw UnsupportedOperationException("Can't overwrite published non-snapshot file $path")
        }
    }
}

private fun publishLocked(repository: Repository, metadata: InfoNode, artifacts: List<Pair<Path, Classifier>>): Path {
    if (!repository.local) {
        throw UnsupportedOperationException("Can't publish to non-local repository $repository")
    }
    val path = repository.url.toPath()!!

    val groupId = metadata.findChild("groupId")?.text ?: throw IllegalArgumentException("Metadata is missing a groupId:\n$metadata")
    val artifactId = metadata.findChild("artifactId")?.text ?: throw IllegalArgumentException("Metadata is missing a artifactId:\n$metadata")
    val version = metadata.findChild("version")?.text ?: throw IllegalArgumentException("Metadata is missing a version:\n$metadata")

    val snapshot = version.endsWith("-SNAPSHOT")

    val pomPath = path / pomPath(groupId, artifactId, version, DEFAULT_SNAPSHOT_VERSION)
    LOG.debug("Publishing metadata to {}", pomPath)
    checkValidForPublish(pomPath, snapshot)
    Files.createDirectories(pomPath.parent)

    val pomXML = metadata.toXML()
    Files.newBufferedWriter(pomPath, Charsets.UTF_8).use {
        it.append(pomXML)
    }
    // Create pom.xml hashes
    run {
        val pomXMLBytes = pomXML.toString().toByteArray(Charsets.UTF_8)
        for (checksum in CHECKSUMS) {
            val digest = checksum.digest().digest(pomXMLBytes)

            val publishedName = pomPath.name
            val checksumFile = pomPath.parent.resolve("$publishedName${checksum.suffix}")
            checkValidForPublish(checksumFile, snapshot)

            checksumFile.writeText(createHashSum(digest, publishedName))
            LOG.debug("Publishing metadata {} to {}", checksum, checksumFile)
        }
    }


    for ((artifact, classifier) in artifacts) {
        val publishedArtifact = path / artifactPath(groupId, artifactId, version, classifier, artifact.name.pathExtension(), DEFAULT_SNAPSHOT_VERSION)
        LOG.debug("Publishing {} to {}", artifact, publishedArtifact)
        checkValidForPublish(publishedArtifact, snapshot)

        Files.copy(artifact, publishedArtifact, StandardCopyOption.REPLACE_EXISTING)
        // Create hashes
        val checksums = CHECKSUMS
        val digests = Array(checksums.size) { checksums[it].digest() }

        Files.newInputStream(artifact).use { input ->
            val buffer = ByteArray(4096)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) {
                    break
                }
                for (digest in digests) {
                    digest.update(buffer, 0, read)
                }
            }
        }
        for (i in checksums.indices) {
            val digest = digests[i].digest()

            val publishedName = publishedArtifact.name
            val checksumFile = publishedArtifact.parent.resolve("$publishedName${checksums[i].suffix}")
            checkValidForPublish(checksumFile, snapshot)

            checksumFile.writeText(createHashSum(digest, publishedName))
            LOG.debug("Publishing {} to {}", publishedArtifact, checksumFile)
        }

        LOG.info("Published {} with {} checksum(s)", publishedArtifact, checksums.size)
    }

    return pomPath.parent
}

/** Translated and resolved [RawPom], ready to be used. */
private class Pom constructor(
        val groupId:String?, val artifactId:String?, val version:String?,
        val packaging:String,
        val dependencies:List<Dependency>,
        val dependencyManagement:List<Dependency>)

/**
 * Resolves against [transitiveDependencyManagement] and [ownDependencyManagement].
 * See http://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Management
 * and https://www.davidjhay.com/maven-dependency-management/
 */
private fun resolveDependencyManagement(
        dependency: RawPom.TranslatedRawPomDependency,
        transitiveDependencyManagement: List<Dependency>,
        ownDependencyManagement: List<Dependency>,
        combinedDependencyManagement: List<Dependency>): Partial<Dependency> {
    /*
    (Discovered from docs and experimentally)
    1. Check dependency management of whoever is requesting this as a dependency
    2. If that does not bear fruit, check own dependency management (and that of pom's parents, preferring own, then trying parents)
       - Version, if not empty, must match
     */

    val usedTransitiveDependencyStandIn = transitiveDependencyManagement.find { template ->
        val templateDepId = template.dependencyId
        // Parent dependency management has to match only in group, name, type and classifier
        // https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Management
        dependency.group == templateDepId.group
                && dependency.name == templateDepId.name
                && dependency.type == templateDepId.type
                && dependency.classifier == templateDepId.classifier
    }

    val standIn = usedTransitiveDependencyStandIn ?: ownDependencyManagement.find { template ->
        val templateDepId = template.dependencyId
        // Own dependency management may also use the template if it is missing its own version
        dependency.group == templateDepId.group
                && dependency.name == templateDepId.name
                && dependency.type == templateDepId.type
                && dependency.classifier == templateDepId.classifier
                && (dependency.version.isNullOrBlank() || dependency.version == templateDepId.version)
    }

    /*
    When applying dependency management templates:
    1. group, name, classifier, type & optional is kept
    2. version & scope is replaced if own is empty or when template's origin is transitive
    3. exclusions are merged
     */

    val usedDependency = if (standIn == null) {
        dependency
    } else {
        val transitive = usedTransitiveDependencyStandIn != null
        LOG.trace("Dependency {} replaced with {} from {} dependencyManagement", dependency, standIn, if (transitive) "transitive" else "own")

        RawPom.TranslatedRawPomDependency(
                dependency.group,
                dependency.name,
                standIn.dependencyId.version,
                dependency.classifier,
                dependency.type,
                // NOTE(jp): I am not entirely sure how is this supposed to work, so this is my best guess.
                //  http://maven.40175.n5.nabble.com/DependencyManagement-to-force-scope-td112273.html suggests,
                //  that "provided" scope should get some special treatment, but I couldn't find any other source for that claim.
                if (dependency.scope.isNullOrBlank()) {
                    if (standIn.scope.isBlank()) {
                        DEFAULT_SCOPE
                    } else {
                        standIn.scope
                    }
                } else dependency.scope,
                dependency.optional,
                dependency.exclusions + standIn.exclusions
        )
    }

    return usedDependency.resolve(combinedDependencyManagement)
}

/** Given a [dependencyManagement] list, replace all elements of scope import with their imported counterparts.
 * @param pomUrl url of the pom in which the [dependencyManagement] was found, for logging
 * @param repositories in which to resolve the imports */
private fun flattedDependencyManagementImports(dependencyManagement:List<Dependency>, transitiveDependencyManagement:List<Dependency>, pomUrl:URL, repositories:SortedRepositories, progressTracker:ActivityListener?):Failable<List<Dependency>, String> {
    // Resolve <dependencyManagement> <scope>import
    // http://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope
    val flatDependencyManagement = ArrayList<Dependency>(dependencyManagement.size * 2)

    for (dependency in dependencyManagement) {
        val dep = dependency.dependencyId
        if (!dependency.scope.equals("import", ignoreCase = true)) {
            flatDependencyManagement.add(dependency)
            continue
        }
        if (!dep.type.equals("pom", ignoreCase = true)) {
            LOG.warn("Dependency {} has scope \"import\", but type is not \"pom\", ignoring", dep)
            continue
        }

        LOG.trace("Resolving dependencyManagement import {} in '{}'", dep, pomUrl)

        val resolvedPom = resolveInM2Repository(dep, dependency.dependencyManagement, repositories, progressTracker)
        if (resolvedPom.hasError) {
            LOG.warn("dependencyManagement import in {} of {} failed: failed to retrieve pom - {}", pomUrl, dep, resolvedPom)
            return Failable.failure(resolvedPom.log?.toString() ?: "failed to retrieve")
        }
        val artifact = resolvedPom.artifact!!
        resolveRawPom(artifact, transitiveDependencyManagement, repositories, progressTracker).fold {
            it.resolve(transitiveDependencyManagement, repositories, progressTracker)
        }.use({ importedPom ->
            val imported = importedPom.dependencyManagement
            if (imported.isNotEmpty()) {
                LOG.trace("dependencyManagement of {} imported {} from {}", pomUrl, imported, dep)

                // Specification says, that it should be replaced
                flatDependencyManagement.addAll(imported)
            }
        }, { error ->
            LOG.warn("dependencyManagement import in {} of {} failed: {}", pomUrl, dep, error)
            return Failable.failure(error)
        })
    }

    return Failable.success(flatDependencyManagement)
}

/**
 * Contains raw data from a Pom file.
 * Note that when accessing some fields, it is also necessary to check the parent if not set.
 *
 * Before using, resolve with [resolve].
 */
private class RawPom(
        val url:URL,
        var groupId: String? = null,
        var artifactId: String? = null,
        var version: String? = null,
        var packaging: String = "jar",
        val properties: MutableMap<String, String> = HashMap()) {

    var parent: RawPom? = null
    val dependencies = ArrayList<RawPomDependency>()
    val dependencyManagement = ArrayList<RawPomDependency>()

    val profiles = ArrayList<PomProfile>()

    private fun checkActivationJdk(jdk:String):Boolean {
        val currentVersion = System.getProperty("java.version")
        if (currentVersion.startsWith(jdk)) {
            return true
        }

        return currentVersion in parseMavenVersionRange(jdk)
    }

    private fun checkActivationOsName(osName:String):Boolean {
        return OS_NAME.equals(osName, ignoreCase = true)
    }
    private fun checkActivationOsFamily(osFamily:String):Boolean {
        return OS_FAMILY.equals(osFamily, ignoreCase = true)
    }
    private fun checkActivationOsArch(osArch:String):Boolean {
        return OS_ARCH.equals(osArch, ignoreCase = true)
    }
    private fun checkActivationOsVersion(osVersion:String):Boolean {
        return OS_VERSION.equals(osVersion, ignoreCase = true)
    }

    private fun checkProfileActivation(profile:PomProfile):Boolean {
        // All present activators must be true, but if there are no activators, the profile is not active
        var matches = 0
        var mismatches = 0

        fun check(value:String?, function:(String) -> Boolean) {
            if (value == null) {
                return
            }
            val match = if (value.startsWith('!')) {
                !function(value.substring(1))
            } else {
                function(value)
            }
            if (match) {
                matches++
            } else {
                mismatches++
            }
        }

        check(profile.activationJdk, ::checkActivationJdk)
        check(profile.activationOsName, ::checkActivationOsName)
        check(profile.activationOsFamily, ::checkActivationOsFamily)
        check(profile.activationOsArch, ::checkActivationOsArch)
        check(profile.activationOsVersion, ::checkActivationOsVersion)

        // Check properties
        for ((key, value) in profile.activationProperties) {
            var successMatch = true
            val normalizedKey = if (key.startsWith('!')) {
                successMatch = false
                key.substring(1)
            } else {
                key
            }

            val baseValue = getProperty(normalizedKey, false)
            val match = if (value == null) {
                baseValue != null
            } else (baseValue != null) && baseValue.equals(value, ignoreCase = true)

            if (match == successMatch) {
                matches++
            } else {
                mismatches++
            }
        }

        return mismatches == 0 && matches > 0
    }

    private fun applyProfiles() {
        val allProfiles = ArrayList(profiles)
        var parent = parent
        while (parent != null) {
            allProfiles.addAll(parent.profiles)
            parent = parent.parent
        }

        // First collect profiles that are activated explicitly
        val activeProfiles = ArrayList<PomProfile>()
        for (profile in allProfiles) {
            if (checkProfileActivation(profile)) {
                LOG.trace("{} - profile {} activated", url, profile.id)
                activeProfiles.add(profile)
            }
        }

        // If there are no explicitly activated profiles, use those activated by default
        if (activeProfiles.isEmpty()) {
            for (profile in allProfiles) {
                if (profile.activeByDefault) {
                    LOG.trace("{} - profile {} activated (by default)", url, profile.id)
                    activeProfiles.add(profile)
                }
            }
        }

        for (profile in activeProfiles) {
            // Apply the profile
            this.dependencies.addAll(profile.dependencies)
            this.dependencyManagement.addAll(profile.dependencyManagement)
            this.properties.putAll(profile.properties)
        }
    }

    fun resolve(transitiveDependencyManagement: List<Dependency>, repositories: CompatibleSortedRepositories, progressTracker: ActivityListener?): Failable<Pom, String> {
        applyProfiles()

        val resolvedGroupId = get { groupId }?.translate(true)
        val resolvedArtifactId = artifactId?.translate(true)
        val resolvedVersion = get { version }?.translate(true)
        val resolvedPackaging = packaging.translate(true)

        val translatedDependencies = ArrayList<TranslatedRawPomDependency>()
        val ownDependencyManagement = ArrayList<Dependency>()
        var pom = this
        var pomIsParent = false
        while (true) {
            if (pomIsParent) {
                val packaging = pom.packaging.translate(true)
                if (!packaging.equals("pom", ignoreCase = true)) {
                    LOG.warn("Parent of '{}' in {} has parent with invalid packaging {} (expected 'pom')", this, pom.url, packaging)
                }
            }
            pom.dependencies.mapTo(translatedDependencies) { it.translate(true) }
            // Child dependencyManagement takes precedence over parent. No deduplication is done, but child is added (and thus) checked first.
            pom.dependencyManagement.mapTo(ownDependencyManagement) {
                // Do not warn about missing properties in dependency management,
                // as the system can handle it and some dependencies seem to use it?
                // Eg. in org.jboss.xnio:xnio-all:3.3.8.Final property version.org.wildfly.common is used in dependencyManagement,
                // but it is not defined anywhere. This just leads to a lot of warnings for nothing.
                // If it is an error, it will fail somewhere down the line.
                it.translate(false).resolveAsDependencyManagement()
            }
            pom = pom.parent ?: break
            pomIsParent = true
        }

        val expandedOwnDependencyManagement = flattedDependencyManagementImports(ownDependencyManagement, transitiveDependencyManagement, url, repositories, progressTracker).let {
            if (it.successful) {
                it.value!!
            } else {
                return it.reFail()
            }
        }

        // Further dependencies should use this thing's dependencyManagement, but only after what parent provided is considered
        val combinedDependencyManagement = transitiveDependencyManagement + expandedOwnDependencyManagement

        val newDependencies = ArrayList<Dependency>(dependencies.size)
        translatedDependencies.mapTo(newDependencies) { dependency ->
            val (dep, depComplete) = resolveDependencyManagement(dependency, transitiveDependencyManagement, expandedOwnDependencyManagement, combinedDependencyManagement)
            if (!depComplete) {
                return Failable.failure("Could not resolve $dependency through dependency management")
            }
            dep
        }

        return Failable.success(Pom(resolvedGroupId, resolvedArtifactId, resolvedVersion, resolvedPackaging, newDependencies, ownDependencyManagement))
    }

    class RawPomDependency(val group: String?, val name: String?, val version: String?,
                           val classifier:String?, val type:String?, val scope:String?, val optional:String?,
                           val exclusions: List<DependencyExclusion>)

    class TranslatedRawPomDependency constructor(val group: String?, val name: String?, val version: String?,
                           val classifier:String, val type:String, val scope:String?, val optional:Boolean?,
                           val exclusions: List<DependencyExclusion>) {



        fun resolveAsDependencyManagement():Dependency {
            return Dependency(
                    DependencyId(
                            group ?: "",
                            name ?: "",
                            version ?: "",
                            classifier,
                            type,
                            DEFAULT_SNAPSHOT_VERSION
                    ),
                    scope?.trim()?.toLowerCase() ?: "",
                    optional ?: DEFAULT_OPTIONAL,
                    exclusions)
        }

        fun resolve(dependencyManagement:List<Dependency>): Partial<Dependency> {
            var complete = true

            val group = group ?: ""
            val name = name ?: ""
            val version = version ?: ""

            if (group.isEmpty()) {
                LOG.warn("{} has no group specified", this)
                complete = false
            }

            if (name.isEmpty()) {
                LOG.warn("{} has no name specified", this)
                complete = false
            }

            if (version.isEmpty()) {
                LOG.warn("{} has no version specified", this)
                complete = false
            }

            return Partial(Dependency(
                    DependencyId(
                            group,
                            name,
                            version,
                            classifier,
                            type,
                            DEFAULT_SNAPSHOT_VERSION
                    ),
                    scope ?: DEFAULT_SCOPE,
                    optional ?: DEFAULT_OPTIONAL,
                    exclusions,
                    dependencyManagement), complete)
        }

        override fun toString(): String {
            return "TranslatedRawPomDependency(group=$group, name=$name, version=$version, classifier='$classifier', type='$type', scope=$scope, optional=$optional, exclusions=$exclusions)"
        }
    }

    private fun RawPomDependency.translate(warn:Boolean):TranslatedRawPomDependency {
        return TranslatedRawPomDependency(
                group?.translate(warn),
                name?.translate(warn),
                version?.translate(warn),
                classifier?.translate(warn)?.toLowerCase().let { if (it == null || it.isEmpty()) NoClassifier else it },
                type?.translate(warn)?.toLowerCase().let { if (it == null || it.isEmpty()) DEFAULT_TYPE else it },
                scope?.translate(warn)?.toLowerCase(),
                optional?.translate(warn)?.toLowerCase()?.equals("true", ignoreCase = true),
                exclusions.map { exclusion ->
                    DependencyExclusion(
                            exclusion.group?.translate(warn),
                            exclusion.name?.translate(warn),
                            exclusion.version?.translate(warn),
                            exclusion.classifier?.translate(warn)?.toLowerCase(),
                            exclusion.type?.translate(warn)?.toLowerCase())
                })
    }

    /** Get value from this or parent POMs.
     * Use this to fulfill [inheritance rules](https://maven.apache.org/pom.html#Inheritance). */
    private inline fun <T> get(getter: RawPom.()->T?):T? {
        var pom = this
        while (true) {
            val element = pom.getter()
            if (element == null) {
                pom = pom.parent ?: return null
            } else {
                return element
            }
        }
    }

    private fun getProperty(key:String, warn:Boolean):String? {
        var explicitValuePom = this@RawPom
        var explicitValue: String?
        do {
            explicitValue = explicitValuePom.properties[key]
            explicitValuePom = explicitValuePom.parent ?: break
        } while (explicitValue == null)

        if (explicitValue != null) {
            LOG.trace("Pom resolution: property '{}' resolved through explicit properties to '{}'", key, explicitValue)
            return explicitValue.trim()
        }

        val envPrefix = "env."
        if (key.startsWith(envPrefix)) {
            val env = System.getenv(key.substring(envPrefix.length))
            return if (env == null) {
                if (warn) {
                    LOG.warn("Unreliable Pom resolution: property '{}' not resolved", key)
                } else {
                    LOG.debug("Unreliable Pom resolution: property '{}' not resolved", key)
                }
                null
            } else {
                if (warn) {
                    LOG.warn("Unreliable Pom resolution: property '{}' resolved to '{}'", key, env)
                } else {
                    LOG.debug("Unreliable Pom resolution: property '{}' resolved to '{}'", key, env)
                }
                env
            }
        }

        if (key.startsWith("project.")) {
            val project = when (key) {
                "project.modelVersion" -> SupportedModelVersion
                "project.groupId" -> get { groupId } ?: ""
                "project.artifactId" -> get { artifactId } ?: ""
                "project.version" -> get { version } ?: ""
                "project.packaging" -> packaging
                else -> {
                    LOG.warn("Unreliable Pom resolution: property '{}' not resolved - this project.* property is not supported", key)
                    return null
                }
            }
            LOG.trace("Pom resolution: property '{}' resolved to '{}'", key, project)
            return project.trim()
        }

        if (key.startsWith("settings.")) {
            if (warn) {
                LOG.warn("Unreliable Pom resolution: property '{}' not resolved - settings.* properties are not supported", key)
            } else {
                LOG.debug("Unreliable Pom resolution: property '{}' not resolved - settings.* properties are not supported", key)
            }
            return null
        }

        val systemProperty = System.getProperty(key)
        if (systemProperty != null) {
            LOG.debug("Unreliable Pom resolution: property '{}' resolved to system property '{}'", key, systemProperty)
            return systemProperty.trim()
        }

        if (warn) {
            LOG.warn("Unreliable Pom resolution: property '{}' not resolved", key)
        }

        return null
    }

    private fun String.translate(warn:Boolean): String {
        if (!startsWith("\${", ignoreCase = false) || !endsWith('}', ignoreCase = false)) {
            return this.trim()
        }

        val key = substring(2, length - 1)
        return getProperty(key, warn) ?: this.trim()
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(RawPom::class.java)
    }
}

private class PomProfile {
    var id = ""
    var activeByDefault = false

    var activationJdk:String? = null
    var activationOsName:String? = null
    var activationOsFamily:String? = null
    var activationOsArch:String? = null
    var activationOsVersion:String? = null
    val activationProperties = ArrayList<Pair<String, String?>>()

    val properties = HashMap<String, String>()
    val dependencies = ArrayList<RawPom.RawPomDependency>()
    val dependencyManagement = ArrayList<RawPom.RawPomDependency>()
}

private class PomBuildingXMLHandler(private val pom: RawPom) : XMLHandler(LOG, pom.url) {

    var parentGroupId = ""
    var parentArtifactId = ""
    var parentVersion = ""
    var parentRelativePath = ""
    var hasParent = false

    private var lastDependencyGroupId:String? = null
    private var lastDependencyArtifactId:String? = null
    private var lastDependencyVersion:String? = null
    private var lastDependencyClassifier:String? = null
    private var lastDependencyType:String? = null
    private var lastDependencyScope:String? = null
    private var lastDependencyOptional:String? = null
    private var lastDependencyExclusions = ArrayList<DependencyExclusion>()

    private var lastDependencyExclusionGroupId:String? = null
    private var lastDependencyExclusionArtifactId:String? = null

    private var lastProfile:PomProfile = PomProfile()
    private var lastProfilePropertyName:String? = null
    private var lastProfilePropertyValue:String? = null

    override fun doElement() {
        // Version
        if (atElement(ModelVersion)) {
            val characters = characters()
            if (characters != SupportedModelVersion) {
                throw SAXException("Unsupported modelVersion: $characters")
            }
        }

        // General
        else if (atElement(GroupId)) {
            pom.groupId = characters()
        } else if (atElement(ArtifactId)) {
            pom.artifactId = characters()
        } else if (atElement(Version)) {
            pom.version = characters()
        } else if (atElement(Packaging)) {
            pom.packaging = characters()
        }

        // Parent
        else if (atElement(ParentGroupId)) {
            parentGroupId = characters()
        } else if (atElement(ParentArtifactId)) {
            parentArtifactId = characters()
        } else if (atElement(ParentVersion)) {
            parentVersion = characters()
        } else if (atElement(ParentRelativePath)) {
            LOG.debug("Ignoring relative path {}", elementCharacters)
            // Relative path seems to be useful only during development, and is irrelevant for published poms
            //parentRelativePath = characters()
        } else if (atElement(Parent)) {
            hasParent = true
        }

        // Dependencies (normal and dependencyManagement)
        else if (atElement(DependencyGroupId) || atElement(DependencyManagementGroupId) || atElement(ProfileDependencyGroupId) || atElement(ProfileDependencyManagementGroupId)) {
            lastDependencyGroupId = characters()
        } else if (atElement(DependencyArtifactId) || atElement(DependencyManagementArtifactId) || atElement(ProfileDependencyArtifactId) || atElement(ProfileDependencyManagementArtifactId)) {
            lastDependencyArtifactId = characters()
        } else if (atElement(DependencyVersion) || atElement(DependencyManagementVersion) || atElement(ProfileDependencyVersion) || atElement(ProfileDependencyManagementVersion)) {
            lastDependencyVersion = characters()
        } else if (atElement(DependencyClassifier) || atElement(DependencyManagementClassifier) || atElement(ProfileDependencyClassifier) || atElement(ProfileDependencyManagementClassifier)) {
            lastDependencyClassifier = characters()
        } else if (atElement(DependencyOptional) || atElement(DependencyManagementOptional) || atElement(ProfileDependencyOptional) || atElement(ProfileDependencyManagementOptional)) {
            lastDependencyOptional = characters()
        } else if (atElement(DependencyScope) || atElement(DependencyManagementScope) || atElement(ProfileDependencyScope) || atElement(ProfileDependencyManagementScope)) {
            lastDependencyScope = characters()
        } else if (atElement(DependencyType) || atElement(DependencyManagementType) || atElement(ProfileDependencyType) || atElement(ProfileDependencyManagementType)) {
            lastDependencyType = characters()
        } else if (atElement(Dependency) || atElement(DependencyManagementDependency) || atElement(ProfileDependency) || atElement(ProfileDependencyManagementDependency)) {
            val dependency = RawPom.RawPomDependency(
                    lastDependencyGroupId,
                    lastDependencyArtifactId,
                    lastDependencyVersion,
                    lastDependencyClassifier,
                    lastDependencyType,
                    lastDependencyScope,
                    lastDependencyOptional,
                    lastDependencyExclusions
            )

            lastDependencyGroupId = null
            lastDependencyArtifactId = null
            lastDependencyVersion = null
            lastDependencyClassifier = null
            lastDependencyType = null
            lastDependencyScope = null
            lastDependencyOptional = null

            lastDependencyExclusions = ArrayList()

            if (atElement(Dependency)) {
                pom.dependencies.add(dependency)
            } else if (atElement(DependencyManagementDependency)) {
                pom.dependencyManagement.add(dependency)
            } else if (atElement(ProfileDependency)) {
                lastProfile.dependencies.add(dependency)
            } else /*if (atElement(ProfileDependencyManagementDependency))*/ {
                lastProfile.dependencyManagement.add(dependency)
            }
        }

        // Dependency (management) exclusions
        else if (atElement(DependencyExclusionGroupId) || atElement(DependencyManagementExclusionGroupId) || atElement(ProfileDependencyExclusionGroupId) || atElement(ProfileDependencyManagementExclusionGroupId)) {
            lastDependencyExclusionGroupId = characters()
        } else if (atElement(DependencyExclusionArtifactId) || atElement(DependencyManagementExclusionArtifactId) || atElement(ProfileDependencyExclusionArtifactId) || atElement(ProfileDependencyManagementExclusionArtifactId)) {
            lastDependencyExclusionArtifactId = characters()
        } else if (atElement(DependencyExclusion) || atElement(DependencyManagementExclusion) || atElement(ProfileDependencyExclusion) || atElement(ProfileDependencyManagementExclusion)) {
            if (lastDependencyExclusionArtifactId == null || lastDependencyExclusionGroupId == null) {
                LOG.warn("Incomplete exclusion: {}:{} in {}", lastDependencyExclusionArtifactId, lastDependencyGroupId)
            } else {
                lastDependencyExclusions.add(DependencyExclusion(
                        if (lastDependencyExclusionGroupId == "*") null else lastDependencyExclusionGroupId,
                        if (lastDependencyExclusionArtifactId == "*") null else lastDependencyExclusionArtifactId
                ))
            }

            lastDependencyExclusionGroupId = null
            lastDependencyExclusionArtifactId = null
        }

        // Properties
        else if (atElement(Property, 1)) {
            val propertyName = elementStack.last()
            val propertyContent = characters()

            pom.properties[propertyName] = propertyContent
        }

        else if (atElement(ProfileProperty, 1)) {
            val propertyName = elementStack.last()
            val propertyContent = characters()

            lastProfile.properties[propertyName] = propertyContent
        }

        // Repositories
        /*else if (atElement(RepoReleases)) {

        } else if (atElement(RepoSnapshots)) {

        } else if (atElement(RepoId)) {

        } else if (atElement(RepoName)) {

        } else if (atElement(RepoUrl)) {

        } else if (atElement(RepoLayout)) {

        }*/ else if (atElement(Repo)) {
            //TODO Add support
            LOG.debug("Pom at {} uses custom repositories, which are not supported yet", pom.url)
        }

        // Profiles
        // https://maven.apache.org/guides/introduction/introduction-to-profiles.html
        // https://books.sonatype.com/mvnref-book/reference/profiles.html
        else if (atElement(Profile)) {
            pom.profiles.add(lastProfile)
            lastProfile = PomProfile()
        } else if (atElement(ProfileId)) {
            lastProfile.id = characters()
        } else if (atElement(ProfileActivationActiveByDefault)) {
            lastProfile.activeByDefault = characters().equals("true", ignoreCase = true)
        } else if (atElement(ProfileActivationJDK)) {
            lastProfile.activationJdk = characters()
        } else if (atElement(ProfileActivationOsName)) {
            lastProfile.activationOsName = characters()
        } else if (atElement(ProfileActivationOsFamily)) {
            lastProfile.activationOsFamily = characters()
        } else if (atElement(ProfileActivationOsArch)) {
            lastProfile.activationOsArch = characters()
        } else if (atElement(ProfileActivationOsVersion)) {
            lastProfile.activationOsVersion = characters()
        } else if (atElement(ProfileActivationProperty)) {
            val propertyName = lastProfilePropertyName
            if (propertyName != null) {
                lastProfile.activationProperties.add(propertyName to lastProfilePropertyValue)
            }
            lastProfilePropertyName = null
            lastProfilePropertyValue = null
        } else if (atElement(ProfileActivationPropertyName)) {
            lastProfilePropertyName = characters()
        } else if (atElement(ProfileActivationPropertyValue)) {
            lastProfilePropertyValue = characters()
        }// TODO(jp): Support for repositories in profiles
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(PomBuildingXMLHandler::class.java)

        val ModelVersion = arrayOf("project", "modelVersion")
        const val SupportedModelVersion = "4.0.0"

        val GroupId = arrayOf("project", "groupId")
        val ArtifactId = arrayOf("project", "artifactId")
        val Version = arrayOf("project", "version")
        val Packaging = arrayOf("project", "packaging")

        val Parent = arrayOf("project", "parent")
        val ParentGroupId = arrayOf("project", "parent", "groupId")
        val ParentArtifactId = arrayOf("project", "parent", "artifactId")
        val ParentVersion = arrayOf("project", "parent", "version")
        val ParentRelativePath = arrayOf("project", "parent", "relativePath")

        val Dependency = arrayOf("project", "dependencies", "dependency")
        val DependencyGroupId = arrayOf("project", "dependencies", "dependency", "groupId")
        val DependencyArtifactId = arrayOf("project", "dependencies", "dependency", "artifactId")
        val DependencyVersion = arrayOf("project", "dependencies", "dependency", "version")
        val DependencyClassifier = arrayOf("project", "dependencies", "dependency", "classifier")
        val DependencyOptional = arrayOf("project", "dependencies", "dependency", "optional")
        val DependencyScope = arrayOf("project", "dependencies", "dependency", "scope")
        val DependencyType = arrayOf("project", "dependencies", "dependency", "type")

        val DependencyExclusion = arrayOf("project", "dependencies", "dependency", "exclusions", "exclusion")
        val DependencyExclusionGroupId = arrayOf("project", "dependencies", "dependency", "exclusions", "exclusion", "groupId")
        val DependencyExclusionArtifactId = arrayOf("project", "dependencies", "dependency", "exclusions", "exclusion", "artifactId")

        val DependencyManagementDependency = arrayOf("project", "dependencyManagement", "dependencies", "dependency")
        val DependencyManagementGroupId = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "groupId")
        val DependencyManagementArtifactId = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "artifactId")
        val DependencyManagementVersion = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "version")
        val DependencyManagementClassifier = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "classifier")
        val DependencyManagementOptional = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "optional")
        val DependencyManagementScope = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "scope")
        val DependencyManagementType = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "type")

        val DependencyManagementExclusion = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "exclusions", "exclusion")
        val DependencyManagementExclusionGroupId = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "exclusions", "exclusion", "groupId")
        val DependencyManagementExclusionArtifactId = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "exclusions", "exclusion", "artifactId")


        val Property = arrayOf("project", "properties")

        val Repo = arrayOf("project", "repositories", "repository")
        val RepoReleases = arrayOf("project", "repositories", "repository", "releases")
        val RepoSnapshots = arrayOf("project", "repositories", "repository", "snapshots")
        val RepoId = arrayOf("project", "repositories", "repository", "id")
        val RepoName = arrayOf("project", "repositories", "repository", "name")
        val RepoUrl = arrayOf("project", "repositories", "repository", "url")
        val RepoLayout = arrayOf("project", "repositories", "repository", "layout")

        val Profile = arrayOf("project", "profiles", "profile")
        val ProfileId = arrayOf("project", "profiles", "profile", "id")
        val ProfileActivationActiveByDefault = arrayOf("project", "profiles", "profile", "activation", "activeByDefault")
        val ProfileActivationJDK = arrayOf("project", "profiles", "profile", "activation", "jdk")
        val ProfileActivationOsName = arrayOf("project", "profiles", "profile", "activation", "os", "name")
        val ProfileActivationOsFamily = arrayOf("project", "profiles", "profile", "activation", "os", "family")
        val ProfileActivationOsArch = arrayOf("project", "profiles", "profile", "activation", "os", "arch")
        val ProfileActivationOsVersion = arrayOf("project", "profiles", "profile", "activation", "os", "version")
        val ProfileActivationProperty = arrayOf("project", "profiles", "profile", "activation", "property")
        val ProfileActivationPropertyName = arrayOf("project", "profiles", "profile", "activation", "property", "name")
        val ProfileActivationPropertyValue = arrayOf("project", "profiles", "profile", "activation", "property", "value")

        val ProfileRepo = Repo.inProfile()
        val ProfileRepoReleases = RepoReleases.inProfile()
        val ProfileRepoSnapshots = RepoSnapshots.inProfile()
        val ProfileRepoId = RepoId.inProfile()
        val ProfileRepoName = RepoName.inProfile()
        val ProfileRepoUrl = RepoUrl.inProfile()
        val ProfileRepoLayout = RepoLayout.inProfile()

        val ProfileDependency = Dependency.inProfile()
        val ProfileDependencyGroupId = DependencyGroupId.inProfile()
        val ProfileDependencyArtifactId = DependencyArtifactId.inProfile()
        val ProfileDependencyVersion = DependencyVersion.inProfile()
        val ProfileDependencyClassifier = DependencyClassifier.inProfile()
        val ProfileDependencyOptional = DependencyOptional.inProfile()
        val ProfileDependencyScope = DependencyScope.inProfile()
        val ProfileDependencyType = DependencyType.inProfile()

        val ProfileDependencyExclusion = DependencyExclusion.inProfile()
        val ProfileDependencyExclusionGroupId = DependencyExclusionGroupId.inProfile()
        val ProfileDependencyExclusionArtifactId = DependencyExclusionArtifactId.inProfile()

        val ProfileDependencyManagementDependency = DependencyManagementDependency.inProfile()
        val ProfileDependencyManagementGroupId = DependencyManagementGroupId.inProfile()
        val ProfileDependencyManagementArtifactId = DependencyManagementArtifactId.inProfile()
        val ProfileDependencyManagementVersion = DependencyManagementVersion.inProfile()
        val ProfileDependencyManagementClassifier = DependencyManagementClassifier.inProfile()
        val ProfileDependencyManagementOptional = DependencyManagementOptional.inProfile()
        val ProfileDependencyManagementScope = DependencyManagementScope.inProfile()
        val ProfileDependencyManagementType = DependencyManagementType.inProfile()
        val ProfileDependencyManagementExclusion = DependencyManagementExclusion.inProfile()
        val ProfileDependencyManagementExclusionGroupId = DependencyManagementExclusionGroupId.inProfile()
        val ProfileDependencyManagementExclusionArtifactId = DependencyManagementExclusionArtifactId.inProfile()

        val ProfileProperty = Property.inProfile()

        @Suppress("UNCHECKED_CAST")
        private fun Array<String>.inProfile():Array<String> {
            val inProfile = arrayOfNulls<String>(this.size + 2)
            inProfile[0] = "project"
            inProfile[1] = "profiles"
            inProfile[2] = "profile"
            System.arraycopy(this, 1, inProfile, 3, this.size - 1)
            return inProfile as Array<String>
        }
    }
}

private class MavenMetadataBuildingXMLHandler(metadataUrl:URL) : XMLHandler(LOG, metadataUrl) {

    var groupId:String? = null
    var artifactId:String? = null
    var version:String? = null

    var versioningSnapshotTimestamp:String? = null
    var versioningSnapshotBuildNumber:String = "0"
    var versioningSnapshotLocalCopy:String = "false"

    override fun doElement() {
        when {
            atElement(GroupId) -> groupId = characters()
            atElement(ArtifactId) -> artifactId = characters()
            atElement(Version) -> version = characters()
            atElement(VersioningSnapshotTimestamp) -> versioningSnapshotTimestamp = characters()
            atElement(VersioningSnapshotBuildNumber) -> versioningSnapshotBuildNumber = characters()
            atElement(VersioningSnapshotLocalCopy) -> versioningSnapshotLocalCopy = characters()
        }
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(MavenMetadataBuildingXMLHandler::class.java)

        private val GroupId = arrayOf("metadata", "groupId")
        private val ArtifactId = arrayOf("metadata", "artifactId")
        private val Version = arrayOf("metadata", "version")

        private val VersioningSnapshotTimestamp = arrayOf("metadata", "versioning", "snapshot", "timestamp")
        private val VersioningSnapshotBuildNumber = arrayOf("metadata", "versioning", "snapshot", "buildNumber")
        private val VersioningSnapshotLocalCopy = arrayOf("metadata", "versioning", "snapshot", "localCopy")

        fun buildFrom(file: ArtifactPath):Failable<MavenMetadataBuildingXMLHandler, String> {
            val xmlData = file.data ?: return Failable.failure("Failed to load xml content")

            val reader = XMLReaderFactory.createXMLReader()
            val metadataBuilder = MavenMetadataBuildingXMLHandler(file.url)
            reader.contentHandler = metadataBuilder
            reader.errorHandler = metadataBuilder
            try {
                reader.parse(InputSource(ByteArrayInputStream(xmlData)))
            } catch (se: SAXException) {
                LOG.warn("Error during parsing {}", file.url, se)
                return Failable.failure("Malformed xml")
            }

            return Failable.success(metadataBuilder)
        }
    }
}

private abstract class XMLHandler(private val LOG: Logger, private val parsing:Any) : DefaultHandler(), ErrorHandler {
    protected val elementStack = ArrayList<String>()
    protected val elementCharacters = StringBuilder()

    protected fun atElement(path: Array<String>, ignoreLast: Int = 0): Boolean {
        if (elementStack.size - ignoreLast != path.size) {
            return false
        }
        var i = path.size - 1
        while (i >= 0) {
            if (!elementStack[i].equals(path[i], ignoreCase = true)) {
                return false
            }
            i--
        }
        return true
    }

    final override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
        elementStack.add(localName)
        elementCharacters.setLength(0)
    }

    abstract fun doElement()

    final override fun endElement(uri: String, localName: String, qName: String) {
        doElement()
        elementStack.removeAt(elementStack.size - 1)
        elementCharacters.setLength(0)
    }

    protected fun characters(): String {
        // in default implementation trim() returns itself, so toString() returns itself
        return elementCharacters.trim().toString()
    }

    final override fun characters(ch: CharArray, start: Int, length: Int) {
        elementCharacters.append(ch, start, length)
    }

    override fun warning(exception: SAXParseException?) {
        LOG.warn("warning during parsing {}", parsing, exception)
    }

    override fun error(exception: SAXParseException?) {
        LOG.warn("error during parsing {}", parsing, exception)
    }
}
