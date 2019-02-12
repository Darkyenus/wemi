package wemi.dependency

import WemiVersion
import com.darkyen.dave.Request
import com.darkyen.dave.Response
import com.darkyen.dave.Webb
import com.darkyen.dave.WebbException
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.xml.sax.*
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.helpers.XMLReaderFactory
import wemi.dependency.PomBuildingXMLHandler.Companion.SupportedModelVersion
import wemi.publish.InfoNode
import wemi.util.*
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.collections.HashMap

private val LOG = LoggerFactory.getLogger("Maven2")

/*
Snapshot resolution logic abstract:
- Non-unique snapshots are overwritten on publish, so the resolution schema is the same
    - Handled without any extra logic by retrieveFile
- Unique snapshots use maven-metadata.xml, which is handled in retrievePom
 */

/** Attempt to resolve [dependencyId] in [repository], using [repositories] for direct immediate dependencies (parent POMs, etc.). */
fun resolveInM2Repository(dependencyId: DependencyId, repository: Repository, repositories: List<Repository>): ResolvedDependency {
    val snapshot = dependencyId.isSnapshot
    if (snapshot && !repository.snapshots) {
        return ResolvedDependency(dependencyId, "Release only repository skipped for snapshot dependency", repository)
    } else if (!snapshot && !repository.releases) {
        return ResolvedDependency(dependencyId, "Snapshot only repository skipped for release dependency", repository)
    }

    // Retrieve basic POM data
    val (retrievedPom, resolvedDependencyId) = retrievePom(repository, dependencyId, snapshot).use({ it }, { return it })

    if (resolvedDependencyId.type.equals("pom", ignoreCase = true)) {
        return ResolvedDependency(resolvedDependencyId, emptyList(), repository, retrievedPom)
    }

    val resolvedPom = resolveRawPom(retrievedPom.originalUrl, retrievedPom.data!!, repository, repositories).fold { rawPom ->
        resolvePom(rawPom, repository, repositories)
    }

    val pom = resolvedPom.use( { it }, {  log ->
        LOG.debug("Failed to resolve POM for {} from {}: {}", resolvedDependencyId, repository, log)
        return ResolvedDependency(resolvedDependencyId, log, repository)
    })

    when (resolvedDependencyId.type) {
        "jar", "bundle" -> { // TODO Should osgi bundles have different handling?
            val jarPath = artifactPath(resolvedDependencyId.group, resolvedDependencyId.name, resolvedDependencyId.version, resolvedDependencyId.classifier, "jar", resolvedDependencyId.snapshotVersion)
            val retrieved = retrieveFile(repository, jarPath, snapshot)

            if (retrieved == null) {
                LOG.warn("Failed to retrieve jar at '{}' in {}", jarPath, repository)
                return ResolvedDependency(resolvedDependencyId, "Failed to retrieve jar", repository)
            } else {
                // Purge retrieved data, storing it would only create a memory leak, as the value is rarely used,
                // can always be lazily loaded and the size of all dependencies can be quite big.
                retrieved.data = null
                return ResolvedDependency(resolvedDependencyId, pom.dependencies, repository, retrieved)
            }
        }
        else -> {
            LOG.warn("Unsupported packaging {} of {}", resolvedDependencyId.type, resolvedDependencyId)
            return ResolvedDependency(resolvedDependencyId, "Unsupported dependency type \"${resolvedDependencyId.type}\"", repository)
        }
    }
}

private val WEBB = Webb(null).apply {
    // NOTE: When User-Agent is not set, it defaults to "Java/<version>" and some servers (Sonatype Nexus)
    // then return gutted version of some resources (at least maven-metadata.xml) for which the checksums don't match
    // This seems to be due to: https://issues.sonatype.org/browse/NEXUS-6171 (not a bug, but a feature!)
    setDefaultHeader("User-Agent", "Wemi/$WemiVersion")
    // Just for consistency
    setDefaultHeader("Accept", "*/*")
    setDefaultHeader("Accept-Language", "*")
}

private fun httpGet(url:URL, ifModifiedSince:Long = -1): Request {
    val request = WEBB.get(url.toExternalForm())
    request.useCaches(false) // Do not use local caches, we do the caching ourselves
    request.retry(2, true)
    if (ifModifiedSince > 0) {
        request.ifModifiedSince(ifModifiedSince)
        request.header("Cache-Control", "no-transform")
    } else {
        request.header("Cache-Control", "no-transform, no-cache")
    }

    return request
}

private sealed class DownloadResult {
    object Failure:DownloadResult()
    object UseCache:DownloadResult()
    class Success(val remoteLastModifiedTime:Long, val remoteArtifactData:ByteArray, val checksums:Array<String?>, val checksumMismatches:Int):DownloadResult()
}

private fun retrieveFileDownloadAndVerify(repositoryArtifactUrl: URL, cacheControlMs: Long, snapshot: Boolean):DownloadResult {
    val response: Response<ByteArray>
    // Fallbacks to current time if headers are invalid/missing, we don't have any better metric
    var remoteLastModifiedTime = System.currentTimeMillis()
    try {
        response = httpGet(repositoryArtifactUrl, cacheControlMs).executeBytes()

        val lastModified = response.lastModified
        val date = response.date
        if (lastModified > 0) {
            remoteLastModifiedTime = lastModified
        } else if (date > 0) {
            remoteLastModifiedTime = date
        }
    } catch (e: WebbException) {
        if (e.cause is FileNotFoundException) {
            LOG.debug("Failed to retrieve '{}', file not found", repositoryArtifactUrl)
        } else {
            LOG.debug("Failed to retrieve '{}'", repositoryArtifactUrl, e)
        }
        return DownloadResult.Failure
    }

    if (snapshot && response.statusCode == 304 /* Not modified */) {
        return DownloadResult.UseCache
    } else if (!response.isSuccess) {
        LOG.debug("Failed to retrieve '{}' - status code {}", repositoryArtifactUrl, response.statusCode)
        return DownloadResult.Failure
    }
    val remoteArtifactData = response.body

    // Step 4: download and verify checksums
    val checksums = arrayOfNulls<String>(CHECKSUMS.size) // checksum file content
    var checksumMismatches = 0
    for (checksum in CHECKSUMS) {
        val checksumUrl = repositoryArtifactUrl.appendToPath(checksum.suffix)
        val checksumFileBody: String? = try {
            val checksumResponse = httpGet(checksumUrl).executeString()
            if (!checksumResponse.isSuccess) {
                LOG.debug("Failed to retrieve checksum '{}' (code: {})", checksumUrl, checksumResponse.statusCode)
                continue
            }
            checksumResponse.body
        } catch (e: WebbException) {
            if (e.cause is FileNotFoundException) {
                LOG.debug("Failed to retrieve checksum '{}', file not found", checksumUrl)
            } else {
                LOG.debug("Failed to retrieve checksum '{}'", checksumUrl, e)
            }
            continue
        }

        val expectedChecksum = parseHashSum(checksumFileBody)
        if (checksumFileBody == null || expectedChecksum.isEmpty()) {
            LOG.warn("Failed to retrieve checksum '{}': file is malformed", checksumUrl)
            checksumMismatches++
        }

        // Compute checksum
        val computedChecksum = checksum.checksum(remoteArtifactData)

        if (hashMatches(expectedChecksum, computedChecksum, repositoryArtifactUrl.path.takeLastWhile { it != '/' })) {
            LOG.trace("{} checksum of '{}' is valid", checksum, repositoryArtifactUrl)
            checksums[checksum.ordinal] = checksumFileBody
        } else {
            if (LOG.isWarnEnabled) {
                LOG.warn("{} checksum of '{}' mismatch! Computed {}, got {}", checksum, repositoryArtifactUrl, toHexString(computedChecksum), toHexString(expectedChecksum))
            }
            checksumMismatches++
        }
    }

    return DownloadResult.Success(remoteLastModifiedTime, remoteArtifactData, checksums, checksumMismatches)
}

/**
 * Retrieve file from repository, handling cache and checksums (TODO: And signatures).
 * NOTE: Does not check whether [repository] holds snapshots and/or releases.
 * Will fail if the file is a directory.
 *
 * @param repository to retrieve from. It's [Repository.cache] will be checked too.
 * @param path to the file in a repository
 * @param snapshot resolve as if this file was a snapshot file? (i.e. may change on the remote)
 */
private fun retrieveFile(repository: Repository, path: String, snapshot:Boolean, cachePath:String = path): ArtifactPath? {
    /*
    File retrieval logic:
    1. If repository is local: (Local) file exists?
        1. Yes: Return it, done
        2. No: Fail
    2. (Repository is remote) Cache local path exists?
        1. Yes && snapshot: Still fresh? (snapshotControlSec)
            1. Yes: Return it, done
            2. No: Note the last modification time for cache control
        2. Yes && !snapshot: Return it, done
        3. No: Continue
    3. Download remote path to memory (possibly with cache control from 2.1), note response time
        1. Skipped, cache valid: Return cache local path, done
        2. Succeeded: continue
        3. Failed: Fail
    4. Download checksums one by one and verify
        1. All checksums 404: Warn, continue
        2. Any checksum mismatch: Warn, fail, try again! (if repository allows to ignore checksums, do not fail completely)
        3. All checksums match (or 404): continue
    5. Store downloaded checksums locally
    6. Store downloaded artifact locally, overwriting existing if it is known that it exists (2.1)
    7. Done
     */

    val repositoryArtifactUrl = repository.url / path

    // Step 1: check if local repository
    if (repository.local) {
        val localFile = repositoryArtifactUrl.toPath() ?: throw AssertionError("local repository URL is not valid Path")
        LOG.debug("Retrieving local artifact: {}", localFile)
        try {
            val attributes = Files.readAttributes<BasicFileAttributes>(localFile, BasicFileAttributes::class.java)
            if (attributes.isDirectory) {
                LOG.warn("Local artifact is a directory: {}", localFile)
                return null
            }
            LOG.trace("Using local artifact: {}", localFile)
            return ArtifactPath(localFile, repositoryArtifactUrl,null)
        } catch (fileDoesNotExist: IOException) {
            LOG.debug("Failed to retrieve local artifact: {}", localFile, fileDoesNotExist)
            return null
        }

        // unreachable
    }

    // This will not fail, due to constraints imposed by repository.cache initializer
    val cacheRepositoryRoot = repository.cache?.url ?: throw AssertionError("non local repository does not have cache repository")
    val cacheFile = (cacheRepositoryRoot / cachePath).toPath() ?: throw AssertionError("cache repository URL is not valid Path")
    var cacheFileExists = false

    // Step 2: check local cache
    var cacheControlMs:Long = -1
    try {
        LOG.debug("Checking local artifact cache: {}", cacheFile)
        val attributes = Files.readAttributes<BasicFileAttributes>(cacheFile, BasicFileAttributes::class.java)
        if (attributes.isDirectory) {
            LOG.warn("Local artifact cache is a directory: {}", cacheFile)
            return null
        }
        if (!snapshot) {
            LOG.trace("Using local artifact cache (not snapshot): {}", cacheFile)
            return ArtifactPath(cacheFile, repositoryArtifactUrl, null)
        }
        val modified = attributes.lastModifiedTime().toMillis()
        if (modified + TimeUnit.SECONDS.toMillis(repository.snapshotUpdateDelaySeconds) > System.currentTimeMillis()) {
            LOG.trace("Using local artifact cache (snapshot still fresh): {}", cacheFile)
            return ArtifactPath(cacheFile, repositoryArtifactUrl, null)
        }

        cacheFileExists = true
        cacheControlMs = modified
    } catch (fileDoesNotExist: IOException) {
        LOG.trace("Local artifact cache does not exist: {}", cacheFile, fileDoesNotExist)
    }

    // Step 3 & 4: download from remote to memory and verify checksums
    LOG.info("Retrieving file '{}' from {}", path, repository)

    // Download may fail, or checksums may fail, so try multiple times
    var downloadFileSuccess:DownloadResult.Success? = null
    val retries = 3
    for (downloadTry in 1 .. retries) {
        val downloadFileResult = retrieveFileDownloadAndVerify(repositoryArtifactUrl, cacheControlMs, snapshot)
        when (downloadFileResult) {
            DownloadResult.Failure -> return null
            DownloadResult.UseCache -> {
                LOG.trace("Using local artifact cache (snapshot not modified): {}", cacheFile)
                return ArtifactPath(cacheFile, repositoryArtifactUrl, null)
            }
            is DownloadResult.Success -> {
                val mismatches = downloadFileResult.checksumMismatches
                if (mismatches > 0) {
                    if (downloadTry < retries) {
                        LOG.warn("Retrying download after {} checksum(s) mismatched", mismatches)
                    } else if (repository.tolerateChecksumMismatch) {
                        LOG.warn("Settling on download with {} mismatched checksum(s)", mismatches)
                    } else {
                        LOG.warn("Download failed due to {} mismatched checksum(s)", mismatches)
                        return null
                    }
                }
                downloadFileSuccess = downloadFileResult
            }
        }
    }
    downloadFileSuccess!!

    // Step 5: Store downloaded checksums locally
    Files.createDirectories(cacheFile.parent)
    var validChecksums = 0
    for ((i, checksum) in downloadFileSuccess.checksums.withIndex()) {
        checksum ?: continue
        val checksumCachePath = cachePath + CHECKSUMS[i].suffix
        val filePath = (cacheRepositoryRoot / checksumCachePath).toPath() ?: throw AssertionError("cache repository checksum path is not a valid path")
        filePath.writeText(checksum)
        validChecksums++
    }
    if (validChecksums == 0) {
        LOG.warn("No checksums found for {}, can't verify its correctness", repositoryArtifactUrl)
    }

    // Step 6: Store downloaded artifact
    val remoteArtifactData: ByteArray = downloadFileSuccess.remoteArtifactData
    try {
        val openOptions =
                if (cacheFileExists) {
                    arrayOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                } else {
                    arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
        Files.newOutputStream(cacheFile, *openOptions).use {
            it.write(remoteArtifactData, 0, remoteArtifactData.size)
        }
        LOG.debug("Artifact from {} cached successfully", repositoryArtifactUrl)
    } catch (e: IOException) {
        LOG.warn("Failed to save artifact from {} to cache in {}", repositoryArtifactUrl, cacheFile, e)
        return null
    }
    if (snapshot) {
        try {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(downloadFileSuccess.remoteLastModifiedTime))
        } catch (e: IOException) {
            LOG.warn("Failed to change artifact's '{}' modify time, snapshot cache control may be slightly off", cacheFile, e)
        }
    }

    // Done
    return ArtifactPath(cacheFile, repositoryArtifactUrl, remoteArtifactData)
}

private fun retrieveRawPom(dependencyId: DependencyId,
                           repository: Repository,
                           repositories: List<Repository>,
                           pomPath:String = pomPath(dependencyId.group, dependencyId.name, dependencyId.version, dependencyId.snapshotVersion)): Failable<RawPom, String> {
    // Create path to pom
    val pomUrl = repository.url / pomPath
    LOG.trace("Retrieving pom at '{}' in {} for {}", pomPath, repository, dependencyId)
    val pomData = retrieveFile(repository, pomPath, dependencyId.isSnapshot)?.data
            ?: return Failable.failure("File not retrieved")

    return resolveRawPom(pomUrl, pomData, repository, repositories)
}

private fun resolveRawPom(pomUrl:URL, pomData:ByteArray,
                          repository: Repository, repositories: List<Repository>): Failable<RawPom, String> {
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
        var parent: RawPom? = null
        val parentPomId = DependencyId(pomBuilder.parentGroupId,
                pomBuilder.parentArtifactId,
                pomBuilder.parentVersion,
                preferredRepository = repository,
                type = "pom")

        if (pomBuilder.parentRelativePath.isNotBlank()) {
            // Create new pom-path
            val parentPomPath = (pomUrl.path / ".." / pomBuilder.parentRelativePath).toString()
            LOG.trace("Retrieving parent pom of '{}' from relative '{}'", pomUrl, parentPomPath)
            retrieveRawPom(parentPomId, repository, repositories, parentPomPath).success {
                parent = it
            }
        }

        if (parent == null) {
            LOG.trace("Retrieving parent pom of '{}' by coordinates '{}', in same repository", pomUrl, parentPomId)
            val resolvedPom = resolveSingleDependency(parentPomId, repositories)
            val artifact = resolvedPom.artifact
            if (artifact != null) {
                resolveRawPom(artifact.originalUrl, artifact.data!!, resolvedPom.resolvedFrom!!, repositories)
                        .success { pom ->
                            parent = pom
                        }
            }
        }

        if (parent == null) {
            LOG.warn("Pom at '{}' in {} claims to have a parent, but it has not been found (resolved to {})", pomUrl, repository, parentPomId)
        } else {
            rawPom.parent = parent
        }
    }

    return Failable.success(rawPom)
}


/** Retrieve raw pom file for given [dependencyId] in [repository].
 * If [snapshot] and it is unique snapshot, it resolves maven-metadata.xml and returns the pom for the newest version. */
private fun retrievePom(repository:Repository, dependencyId:DependencyId, snapshot:Boolean):Failable<Pair<ArtifactPath, DependencyId>, ResolvedDependency> {
    LOG.trace("Retrieving pom at '{}' for {}", repository, dependencyId)
    val retrievedPom = retrieveFile(repository, pomPath(dependencyId.group, dependencyId.name, dependencyId.version, dependencyId.snapshotVersion), snapshot)
    if (retrievedPom != null) {
        return Failable.success(retrievedPom to dependencyId)
    }
    if (snapshot && dependencyId.snapshotVersion.isEmpty()) {
        // Query for maven-metadata.xml (https://github.com/strongbox/strongbox/wiki/Maven-Metadata)
        val mavenMetadataPath = mavenMetadataPath(dependencyId, null)
        val mavenMetadataCachePath = mavenMetadataPath(dependencyId, repository)
        val metadataFileArtifact = retrieveFile(repository, mavenMetadataPath, true, mavenMetadataCachePath)
                ?: return Failable.failure(ResolvedDependency(dependencyId, "Failed to resolve snapshot metadata", repository))
        val metadataBuilder = MavenMetadataBuildingXMLHandler.buildFrom(metadataFileArtifact)
        val snapshotVersion = metadataBuilder.use({ metadata ->
            val timestamp = metadata.versioningSnapshotTimestamp
                    ?: return Failable.failure(ResolvedDependency(dependencyId, "Failed to parse metadata xml: timestamp is missing", repository))
            val buildNumber = metadata.versioningSnapshotBuildNumber
            "$timestamp-$buildNumber"
        }, { log ->
            return Failable.failure(ResolvedDependency(dependencyId, "Failed to parse metadata xml: $log", repository))
        })

        LOG.info("Resolving {} in {} with snapshot version {}", dependencyId, repository, snapshotVersion)

        val newRetrievedPom = retrieveFile(repository, pomPath(dependencyId.group, dependencyId.name, dependencyId.version, snapshotVersion), snapshot)

        if (newRetrievedPom != null) {
            return Failable.success(newRetrievedPom to dependencyId.copy(snapshotVersion = snapshotVersion))
        }

        return Failable.failure(ResolvedDependency(dependencyId, "Failed to resolve pom xml for deduced snapshot version \"$snapshotVersion\"", repository))
    }

    return Failable.failure(ResolvedDependency(dependencyId, "Failed to resolve pom xml", repository))
}

private fun resolvePom(rawPom:RawPom, repository: Repository, repositories: List<Repository>): Failable<Pom, String> {
    val pom = rawPom.resolve(repository)

    // Resolve <dependencyManagement> <scope>import
    // http://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope
    //TODO Untested, no known project that uses it
    val flatDependencyManagement = ArrayList<Dependency>(pom.dependencyManagement.size * 2)

    for (dependency in pom.dependencyManagement) {
        val dep = dependency.dependencyId
        if (!dep.type.equals("pom", ignoreCase = true)
                || !dep.scope.equals("import", ignoreCase = true)) {
            flatDependencyManagement.add(dependency)
            continue
        }

        LOG.trace("Resolving dependencyManagement import {} in '{}'", dep, rawPom.url)
        retrieveRawPom(dep, repository, repositories).fold { retrievedRawPom ->
            resolvePom(retrievedRawPom, repository, repositories)
        }.use({ importedPom ->
            val imported = importedPom.dependencyManagement
            if (imported.isNotEmpty()) {
                LOG.trace("dependencyManagement of {} imported {} from {}", rawPom.url, imported, dep)

                // Specification says, that it should be replaced
                flatDependencyManagement.addAll(imported)
            }
        }, { error ->
            LOG.warn("dependencyManagement import in {} of {} failed: '{}'", rawPom.url, dep, error)
        })
    }
    val fullyResolvedPom = pom.resolveEffectiveDependencies(flatDependencyManagement)

    return Failable.success(fullyResolvedPom)
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

private fun artifactPath(group:String, name:String, version:String, classifier:Classifier, extension: String, snapshotVersion:String):String {
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

private fun mavenMetadataPath(dependencyId: DependencyId, repositoryIfCache:Repository?):String {
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
internal fun publish(repository:Repository, metadata: InfoNode, artifacts: List<Pair<Path, Classifier>>): URI {
    val lock = repository.directoryPath()

    return if (lock != null) {
        directorySynchronized(lock) {
            repository.publishLocked(metadata, artifacts)
        }
    } else {
        repository.publishLocked(metadata, artifacts)
    }
}

// TODO(jp): This should be replaced with Files.exists and use unique snapshots
private fun checkValidForPublish(path:Path, snapshot:Boolean) {
    if (Files.exists(path)) {
        if (snapshot) {
            LOG.info("Overwriting {}", path)
        } else {
            throw UnsupportedOperationException("Can't overwrite published non-snapshot file $path")
        }
    } else {
        Files.createDirectories(path.parent)
    }
}

private fun Repository.publishLocked(metadata: InfoNode, artifacts: List<Pair<Path, Classifier>>): URI {
    val path = directoryPath() ?: throw UnsupportedOperationException("Can't publish to non-local repository")

    val groupId = metadata.findChild("groupId")?.text ?: throw IllegalArgumentException("Metadata is missing a groupId:\n$metadata")
    val artifactId = metadata.findChild("artifactId")?.text ?: throw IllegalArgumentException("Metadata is missing a artifactId:\n$metadata")
    val version = metadata.findChild("version")?.text ?: throw IllegalArgumentException("Metadata is missing a version:\n$metadata")

    val snapshot = version.endsWith("-SNAPSHOT")

    val pomPath = path / pomPath(groupId, artifactId, version, DEFAULT_SNAPSHOT_VERSION)
    LOG.debug("Publishing metadata to {}", pomPath)
    checkValidForPublish(pomPath, snapshot)
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

    return pomPath.parent.toUri()
}

/** Translated and resolved [RawPom], ready to be used. */
private class Pom(val groupId:String?, val artifactId:String?, val version:String?, val packaging:String,
                  val dependencies:List<Dependency>, val dependencyManagement:List<Dependency>) {

    /**
     * Resolves against [dependencyManagement].
     * http://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Management
     */
    private fun resolveDependencyManagement(dependency: Dependency, dependencyManagement:List<Dependency>): Dependency {
        for (templateDep in dependencyManagement) {
            // Check if dependency is full subset of templateDep, and use templateDep instead if it is
            // At least groupId, artifactId, type and classifier must all match to consider for replacement
            val depId = dependency.dependencyId
            val tempDepId = templateDep.dependencyId
            if (depId.group == tempDepId.group
                    && depId.name == tempDepId.name
                    && depId.type == tempDepId.type
                    && depId.classifier == tempDepId.classifier
                    && (depId.version.isBlank() || depId.version == tempDepId.version)) {
                // Check for full exclusions subset
                if (!templateDep.exclusions.containsAll(dependency.exclusions)) {
                    continue
                }

                // Matches
                LOG.trace("Dependency {} replaced with {} from dependencyManagement", dependency, templateDep)
                return templateDep
            }
        }
        return dependency
    }

    /**
     * Returns copy of this [Pom] with [dependencies] resolved against flattened [dependencyManagement] and with
     * non-transitive dependencies filtered out.
     * Retrieves dependencies that usage of this POM mandates.
     * Handles things like dependencyManagement correctly, but not on transitive dependencies,
     * as that is handled in higher layer of dependency resolution process.
     *
     * TODO: dependencyManagement of transitive dependencies is not handled to be Maven-like
     */
    fun resolveEffectiveDependencies(dependencyManagement:List<Dependency>):Pom {
        val newDependencies = ArrayList<Dependency>()
        for (dependency in dependencies) {
            val resolved = resolveDependencyManagement(dependency, dependencyManagement)

            // Check if the scope it is in is transitive
            // https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope
            when (resolved.dependencyId.scope.toLowerCase()) {
                "compile", "runtime" -> {
                    // Kept
                    newDependencies.add(resolved)
                }
                "provided", "test", "system" -> {
                    // Omitted
                    LOG.trace("Dependency {} of {} omitted", dependency, this)
                }
                else -> {
                    // Illegal
                    LOG.warn("Illegal scope of {} in {}", dependency, this)
                }
            }
        }

        return Pom(groupId, artifactId, version, packaging, newDependencies, dependencyManagement)
    }
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

    fun resolve(repository:Repository):Pom {
        val dependencies = ArrayList<Dependency>()
        val dependencyManagement = ArrayList<Dependency>()
        var pom = this
        var pomIsParent = false
        while (true) {
            if (pomIsParent) {
                val packaging = pom.packaging.translate()
                if (!packaging.equals("pom", ignoreCase = true)) {
                    LOG.warn("Parent of '{}' in {} has parent with invalid packaging {} (expected 'pom')", this, pom.url, packaging)
                }
            }
            pom.dependencies.mapTo(dependencies) { translate(it, repository) }
            pom.dependencyManagement.mapTo(dependencyManagement) { translate(it, repository) }
            pom = pom.parent ?: break
            pomIsParent = true
        }

        return Pom(get{groupId}?.translate(), artifactId?.translate(), get{version}?.translate(),
                packaging.translate(), dependencies, dependencyManagement)
    }

    class RawPomDependency(val group: String?, val name: String?, val version: String?,
                           val classifier:String?, val type:String?, val scope:String?, val optional:String?,
                           val exclusions: List<DependencyExclusion>)

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

    private fun String.translate(): String {
        if (!startsWith("\${", ignoreCase = false) || !endsWith('}', ignoreCase = false)) {
            return this
        }

        val key = substring(2, length - 1)
        var explicitValuePom = this@RawPom
        var explicitValue: String?
        do {
            explicitValue = explicitValuePom.properties[key]
            explicitValuePom = explicitValuePom.parent ?: break
        } while (explicitValue == null)

        if (explicitValue != null) {
            LOG.debug("Unreliable Pom resolution: property '{}' resolved through explicit properties to '{}'", key, explicitValue)
            return explicitValue
        }

        val envPrefix = "env."
        if (key.startsWith(envPrefix)) {
            val env = System.getenv(key.substring(envPrefix.length))
            return if (env == null) {
                LOG.warn("Unreliable Pom resolution: property '{}' not resolved", key)
                this
            } else {
                LOG.warn("Unreliable Pom resolution: property '{}' resolved to '{}'", key, env)
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
                    return this
                }
            }
            LOG.debug("Unreliable Pom resolution: property '{}' resolved through project properties to '{}'", key, project)
            return project
        }

        if (key.startsWith("settings.")) {
            LOG.warn("Unreliable Pom resolution: property '{}' not resolved - settings.* properties are not supported", key)
            return this
        }

        val systemProperty = System.getProperty(key)
        if (systemProperty != null) {
            LOG.warn("Unreliable Pom resolution: property '{}' resolved to system property '{}'", key, systemProperty)
            return systemProperty
        }

        LOG.warn("Unreliable Pom resolution: property '{}' not resolved", key)
        return this
    }

    private fun translate(raw:RawPomDependency, repository:Repository): Dependency {
        val translatedDependencyId = DependencyId(
                raw.group?.translate() ?: "",
                raw.name?.translate() ?: "",
                raw.version?.translate() ?: "",
                preferredRepository = repository,
                classifier = raw.classifier?.translate() ?: NoClassifier,
                type = raw.type?.translate() ?: DEFAULT_TYPE,
                scope = raw.scope?.translate() ?: DEFAULT_SCOPE,
                optional = raw.optional?.translate()?.equals("true", ignoreCase = true) ?: DEFAULT_OPTIONAL
        )

        val translatedExclusions = raw.exclusions.map { exclusion ->
            DependencyExclusion(exclusion.group?.translate(),
                    exclusion.name?.translate(),
                    exclusion.version?.translate(),
                    exclusion.classifier?.translate(),
                    exclusion.type?.translate(),
                    exclusion.scope?.translate(),
                    exclusion.optional /* Not set by POMs anyway. */)
        }

        return Dependency(translatedDependencyId, translatedExclusions)
    }

    private companion object {
        private val LOG = LoggerFactory.getLogger(RawPom::class.java)
    }
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

    private var lastDependencyExclusionGroupId = ""
    private var lastDependencyExclusionArtifactId = ""
    private var lastDependencyExclusionVersion = "*"

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
        else if (atElement(DependencyGroupId) || atElement(DependencyManagementGroupId)) {
            lastDependencyGroupId = characters()
        } else if (atElement(DependencyArtifactId) || atElement(DependencyManagementArtifactId)) {
            lastDependencyArtifactId = characters()
        } else if (atElement(DependencyVersion) || atElement(DependencyManagementVersion)) {
            lastDependencyVersion = characters()
        } else if (atElement(DependencyClassifier) || atElement(DependencyManagementClassifier)) {
            lastDependencyClassifier = characters()
        } else if (atElement(DependencyOptional) || atElement(DependencyManagementOptional)) {
            lastDependencyOptional = characters()
        } else if (atElement(DependencyScope) || atElement(DependencyManagementScope)) {
            lastDependencyScope = characters()
        } else if (atElement(DependencyType) || atElement(DependencyManagementType)) {
            lastDependencyType = characters()
        } else if (atElement(Dependency)) {
            pom.dependencies.add(RawPom.RawPomDependency(
                    lastDependencyGroupId,
                    lastDependencyArtifactId,
                    lastDependencyVersion,
                    lastDependencyClassifier,
                    lastDependencyType,
                    lastDependencyScope,
                    lastDependencyOptional,
                    lastDependencyExclusions
            ))

            lastDependencyGroupId = null
            lastDependencyArtifactId = null
            lastDependencyVersion = null
            lastDependencyClassifier = null
            lastDependencyType = null
            lastDependencyScope = null
            lastDependencyOptional = null

            lastDependencyExclusions = ArrayList()
        } else if (atElement(DependencyManagementDependency)) {
            pom.dependencyManagement.add(RawPom.RawPomDependency(
                    lastDependencyGroupId,
                    lastDependencyArtifactId,
                    lastDependencyVersion,
                    lastDependencyClassifier,
                    lastDependencyType,
                    lastDependencyScope,
                    lastDependencyOptional,
                    lastDependencyExclusions
            ))

            lastDependencyGroupId = null
            lastDependencyArtifactId = null
            lastDependencyVersion = null
            lastDependencyClassifier = null
            lastDependencyType = null
            lastDependencyScope = null
            lastDependencyOptional = null

            lastDependencyExclusions = ArrayList()
        }

        // Dependency (management) exclusions
        else if (atElement(DependencyExclusionGroupId) || atElement(DependencyManagementExclusionGroupId)) {
            lastDependencyExclusionGroupId = characters()
        } else if (atElement(DependencyExclusionArtifactId) || atElement(DependencyManagementExclusionArtifactId)) {
            lastDependencyExclusionArtifactId = characters()
        } else if (atElement(DependencyExclusionVersion) || atElement(DependencyManagementExclusionVersion)) {
            lastDependencyExclusionVersion = characters()
        } else if (atElement(DependencyExclusion) || atElement(DependencyManagementExclusion)) {
            lastDependencyExclusions.add(DependencyExclusion(
                    lastDependencyExclusionGroupId, lastDependencyExclusionArtifactId, lastDependencyExclusionVersion))
            lastDependencyExclusionGroupId = ""
            lastDependencyExclusionArtifactId = ""
            lastDependencyExclusionVersion = "*"
        }

        // Properties
        else if (atElement(Property, 1)) {
            val propertyName = elementStack.last()
            val propertyContent = characters()

            pom.properties[propertyName] = propertyContent
        }

        // Repositories
        else if (atElement(RepoReleases)) {

        } else if (atElement(RepoSnapshots)) {

        } else if (atElement(RepoId)) {

        } else if (atElement(RepoName)) {

        } else if (atElement(RepoUrl)) {

        } else if (atElement(RepoLayout)) {

        } else if (atElement(Repo)) {
            //TODO Add support
            LOG.debug("Pom at {} uses custom repositories, which are not supported yet", pom.url)
        }
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

        val DependencyManagementDependency = arrayOf("project", "dependencyManagement", "dependencies", "dependency")
        val DependencyManagementGroupId = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "groupId")
        val DependencyManagementArtifactId = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "artifactId")
        val DependencyManagementVersion = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "version")
        val DependencyManagementClassifier = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "classifier")
        val DependencyManagementOptional = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "optional")
        val DependencyManagementScope = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "scope")
        val DependencyManagementType = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "type")

        val DependencyExclusion = arrayOf("project", "dependencies", "dependency", "exclusions", "exclusion")
        val DependencyExclusionGroupId = arrayOf("project", "dependencies", "dependency", "exclusions", "exclusion", "groupId")
        val DependencyExclusionArtifactId = arrayOf("project", "dependencies", "dependency", "exclusions", "exclusion", "artifactId")
        val DependencyExclusionVersion = arrayOf("project", "dependencies", "dependency", "exclusions", "exclusion", "version")

        val DependencyManagementExclusion = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "exclusions", "exclusion")
        val DependencyManagementExclusionGroupId = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "exclusions", "exclusion", "groupId")
        val DependencyManagementExclusionArtifactId = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "exclusions", "exclusion", "artifactId")
        val DependencyManagementExclusionVersion = arrayOf("project", "dependencyManagement", "dependencies", "dependency", "exclusions", "exclusion", "version")


        val Property = arrayOf("project", "properties")

        val Repo = arrayOf("project", "repositories", "repository")
        val RepoReleases = arrayOf("project", "repositories", "repository", "releases")
        val RepoSnapshots = arrayOf("project", "repositories", "repository", "snapshots")
        val RepoId = arrayOf("project", "repositories", "repository", "id")
        val RepoName = arrayOf("project", "repositories", "repository", "name")
        val RepoUrl = arrayOf("project", "repositories", "repository", "url")
        val RepoLayout = arrayOf("project", "repositories", "repository", "layout")
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

        fun buildFrom(file:ArtifactPath):Failable<MavenMetadataBuildingXMLHandler, String> {
            val xmlData = file.data ?: return Failable.failure("Failed to load xml content")

            val reader = XMLReaderFactory.createXMLReader()
            val metadataBuilder = MavenMetadataBuildingXMLHandler(file.originalUrl)
            reader.contentHandler = metadataBuilder
            reader.errorHandler = metadataBuilder
            try {
                reader.parse(InputSource(ByteArrayInputStream(xmlData)))
            } catch (se: SAXException) {
                LOG.warn("Error during parsing {}", file.originalUrl, se)
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
