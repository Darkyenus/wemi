package wemi.dependency.internal

import WemiVersion
import com.darkyen.dave.*
import org.slf4j.LoggerFactory
import wemi.ActivityListener
import wemi.boot.WemiUnicodeOutputSupported
import wemi.collections.toMutable
import wemi.dependency.*
import wemi.util.*
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*


private val LOG = LoggerFactory.getLogger("MavenFileRetrieval")

private fun createWebb():Webb {
    return Webb(null).apply {
        // NOTE: When User-Agent is not set, it defaults to "Java/<version>" and some servers (Sonatype Nexus)
        // then return gutted version of some resources (at least maven-metadata.xml) for which the checksums don't match
        // This seems to be due to: https://issues.sonatype.org/browse/NEXUS-6171 (not a bug, but a feature!)
        setDefaultHeader("User-Agent", "Wemi/$WemiVersion")
        // Just for consistency
        setDefaultHeader("Accept", "*/*")
        setDefaultHeader("Accept-Language", "*")
        // Should be default, but just in case
        setFollowRedirects(true)
    }
}

private val WEBB = createWebb()

private val UNSAFE_WEBB = createWebb().apply {
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(object : X509TrustManager {
        override fun checkClientTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
        override fun checkServerTrusted(p0: Array<out X509Certificate>?, p1: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }), null)

    setSSLSocketFactory(sslContext.socketFactory)
    setHostnameVerifier { _, _ -> true }
}

private fun httpGet(url: URL, ifModifiedSince:Long = -1, useUnsafeTransport:Boolean = false): Request {
    val webb = if (useUnsafeTransport) {
        LOG.warn("Forgoing all cryptography verifications on GET of {}", url)
        UNSAFE_WEBB
    } else WEBB

    val request = webb.get(url.toExternalForm())
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

private class CacheArtifactPath(val cacheFile: Path, val cacheFileExists: Boolean, val cacheControlMs: Long)

/**
 * Retrieve file from a repository, handling cache and checksums (TODO: And signatures).
 * NOTE: Does not check whether [repository] holds snapshots and/or releases.
 * Will fail if the file is a directory.
 *
 * When returned value is failed, but the [CacheArtifactPath] is not null, it should be passed to [retrieveFileRemotely]
 * to complete the file resolution.
 *
 * @param repository to retrieve from. It's [Repository.cache] will be checked too.
 * @param path to the file in a repository
 * @param snapshot resolve as if this file was a snapshot file? (i.e. may change on the remote)
 */
private fun retrieveFileLocally(repository: Repository, path: String, snapshot:Boolean, cachePath:String = path): Failable<ArtifactPath, CacheArtifactPath?> {
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
        LOG.trace("Retrieving local artifact: {}", localFile)
        try {
            val attributes = Files.readAttributes<BasicFileAttributes>(localFile, BasicFileAttributes::class.java)
            if (attributes.isDirectory) {
                LOG.warn("Local artifact is a directory: {}", localFile)
                return Failable.failure(null)
            }
            LOG.debug("Using local artifact: {}", localFile)
            return Failable.success(ArtifactPath(localFile, null, repository, repositoryArtifactUrl,false))
        } catch (fileDoesNotExist: java.nio.file.NoSuchFileException) {
            LOG.trace("Local artifact does not exist: {}", localFile)
            return Failable.failure(null)
        } catch (ioProblem: IOException) {
            LOG.debug("Failed to retrieve local artifact: {}", localFile, ioProblem)
            return Failable.failure(null)
        }

        // unreachable
    }

    // This will not fail, due to constraints imposed by repository.cache initializer
    val cacheFile = (repository.cache ?: throw AssertionError("non local repository does not have cache repository")) / cachePath
    var cacheFileExists = false

    // Step 2: check local cache
    var cacheControlMs:Long = -1
    try {
        LOG.trace("Checking local artifact cache: {}", cacheFile)
        val attributes = Files.readAttributes<BasicFileAttributes>(cacheFile, BasicFileAttributes::class.java)
        if (attributes.isDirectory) {
            LOG.warn("Local artifact cache is a directory: {}", cacheFile)
            return Failable.failure(null)
        }
        if (!snapshot) {
            LOG.debug("Using local artifact cache (not snapshot): {}", cacheFile)
            return Failable.success(ArtifactPath(cacheFile, null, repository, repositoryArtifactUrl, true))
        }
        val modified = attributes.lastModifiedTime().toMillis()
        if (modified + TimeUnit.SECONDS.toMillis(repository.snapshotUpdateDelaySeconds) > System.currentTimeMillis()) {
            LOG.debug("Using local artifact cache (snapshot still fresh): {}", cacheFile)
            return Failable.success(ArtifactPath(cacheFile, null, repository, repositoryArtifactUrl, true))
        }

        cacheFileExists = true
        cacheControlMs = modified
    } catch (fileDoesNotExist: java.nio.file.NoSuchFileException) {
        LOG.trace("Local artifact cache does not exist: {}", cacheFile)
    } catch (io: IOException) {
        LOG.warn("Failed to check local artifact cache: {}", cacheFile, io)
    }

    return Failable.failure(CacheArtifactPath(cacheFile, cacheFileExists, cacheControlMs))
}

private sealed class DownloadResult {
    object Failure:DownloadResult()
    object UseCache:DownloadResult()
    class Success(val remoteLastModifiedTime:Long, val remoteArtifactData:DataWithChecksum, val checksumMismatches:Int):DownloadResult()
}

private fun retrieveFileDownloadAndVerify(repositoryArtifactUrl: URL, cacheControlMs: Long, snapshot: Boolean, useUnsafeTransport:Boolean, progressTracker: ActivityListener?):DownloadResult {
    val response: Response<ByteArray>
    // Falls back to current time if headers are invalid/missing, as we don't have any better metric
    var remoteLastModifiedTime = System.currentTimeMillis()
    try {
        response = httpGet(repositoryArtifactUrl, cacheControlMs, useUnsafeTransport = useUnsafeTransport).execute(progressTracker, ResponseTranslator.BYTES_TRANSLATOR)

        val lastModified = response.lastModified
        val date = response.date
        if (lastModified > 0) {
            remoteLastModifiedTime = lastModified
        } else if (date > 0) {
            remoteLastModifiedTime = date
        }
    } catch (e: WebbException) {
        when (e.cause) {
            is FileNotFoundException ->
                LOG.trace("Failed to retrieve '{}', file not found", repositoryArtifactUrl)
            is SSLException -> {
                LOG.warn("Failed to retrieve '{}', problem with SSL", repositoryArtifactUrl, e.cause)
            }
            else ->
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
    val remoteArtifactData = DataWithChecksum(response.body)

    // Step 4: download and verify checksums
    val checksumMismatches = retrieveChecksums(repositoryArtifactUrl, useUnsafeTransport, progressTracker, remoteArtifactData)

    return DownloadResult.Success(remoteLastModifiedTime, remoteArtifactData, checksumMismatches)
}

/**
 * Retrieve and verify checksums for given [artifactData] which has been found at [repositoryArtifactUrl].
 * @return amount of mismatched checksums, 0 if none, -1 if no checksums found
 */
private fun retrieveChecksums(repositoryArtifactUrl: URL, useUnsafeTransport: Boolean, progressTracker: ActivityListener?, artifactData: DataWithChecksum): Int {
    var checksumsChecked = 0
    var checksumMismatchBits = 0
    for (checksum in CHECKSUMS) {
        val checksumUrl = repositoryArtifactUrl.appendToPath(checksum.suffix)
        val checksumFileBody: String? = try {
            val checksumResponse = httpGet(checksumUrl, useUnsafeTransport = useUnsafeTransport).execute(progressTracker, ResponseTranslator.STRING_TRANSLATOR)
            if (!checksumResponse.isSuccess) {
                LOG.debug("Failed to retrieve checksum '{}' (code: {})", checksumUrl, checksumResponse.statusCode)
                continue
            }
            checksumResponse.body
        } catch (e: WebbException) {
            when (e.cause) {
                is FileNotFoundException ->
                    LOG.trace("Failed to retrieve checksum '{}', file not found", repositoryArtifactUrl)
                is SSLException -> {
                    LOG.warn("Failed to retrieve checksum '{}', problem with SSL", repositoryArtifactUrl, e.cause)
                }
                else ->
                    LOG.debug("Failed to retrieve checksum '{}'", repositoryArtifactUrl, e)
            }
            continue
        }

        val remoteChecksum = parseHashSum(checksumFileBody)
        if (checksumFileBody == null || remoteChecksum.isEmpty()) {
            LOG.warn("Failed to retrieve checksum '{}': file is malformed", checksumUrl)
        } else {
            checksumsChecked++
            // Compute checksum
            val artifactChecksum = artifactData.checksum(checksum)

            if (hashMatches(remoteChecksum, artifactChecksum, repositoryArtifactUrl.path.takeLastWhile { it != '/' })) {
                LOG.trace("{} checksum of '{}' is valid", checksum, repositoryArtifactUrl)
            } else {
                LOG.warn("{} checksum of '{}' mismatch! Computed {}, got {}", checksum, repositoryArtifactUrl, toHexString(artifactChecksum), toHexString(remoteChecksum))
                checksumMismatchBits = checksumMismatchBits or (1 shl checksum.ordinal)
            }
        }
    }

    if (checksumMismatchBits != 0) {
        return checksumMismatchBits
    }
    if (checksumsChecked <= 0) {
        return -1
    }
    return 0
}

/**
 * Download enough data to be able to verify that if [repositoryArtifactUrl] contains any artifact, it is the same as [verifyAgainst].
 * @return true if there is an artifact and it matches, false if there is an artifact and it does not match, null if there is no artifact
 */
private fun retrieveFileForVerificationOnly(repositoryArtifactUrl: URL, useUnsafeTransport:Boolean, verifyAgainst:DataWithChecksum, listener:ActivityListener?):Boolean? {
    // Verification step 1: Download all checksums
    val checksumMismatches = retrieveChecksums(repositoryArtifactUrl, useUnsafeTransport, listener, verifyAgainst)
    if (checksumMismatches == 0) {
        return true
    } else if (checksumMismatches > 0) {
        return false
    }

    // There were no checksum files, so we have to download the whole thing
    val response: Response<ByteArray>
    try {
        response = httpGet(repositoryArtifactUrl, useUnsafeTransport = useUnsafeTransport).execute(listener, ResponseTranslator.BYTES_TRANSLATOR)
    } catch (e: WebbException) {
        when (e.cause) {
            is FileNotFoundException ->
                LOG.trace("Failed to retrieve '{}', file not found", repositoryArtifactUrl)
            is SSLException -> {
                LOG.warn("Failed to retrieve '{}', problem with SSL", repositoryArtifactUrl, e.cause)
            }
            else ->
                LOG.debug("Failed to retrieve '{}'", repositoryArtifactUrl, e)
        }
        return null
    }
    if (!response.isSuccess) {
        LOG.debug("Failed to retrieve '{}' for verification - status code {}", repositoryArtifactUrl, response.statusCode)
        return null
    }

    val remoteArtifactData = response.body
    return verifyAgainst.data.contentEquals(remoteArtifactData)
}

/** Continuation of [retrieveFileLocally]. */
private fun retrieveFileRemotely(repository: Repository, path: String, snapshot:Boolean, cache:CacheArtifactPath, progressTracker: ActivityListener?): ArtifactPath? {
    val repositoryArtifactUrl = repository.url / path
    val cacheFile = cache.cacheFile

    // Step 3 & 4: download from remote to memory and verify checksums
    LOG.debug("Retrieving file '{}' from {}", path, repository)

    // Download may fail, or checksums may fail, so try multiple times
    var downloadFileSuccess:DownloadResult.Success? = null
    val retries = 3
    for (downloadTry in 1 .. retries) {
        val downloadFileResult = retrieveFileDownloadAndVerify(repositoryArtifactUrl, cache.cacheControlMs, snapshot, repository.useUnsafeTransport, progressTracker)
        when (downloadFileResult) {
            DownloadResult.Failure -> return null
            DownloadResult.UseCache -> {
                LOG.trace("Using local artifact cache (snapshot not modified): {}", cacheFile)
                return ArtifactPath(cacheFile, null, repository, repositoryArtifactUrl, true)
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

    Files.createDirectories(cacheFile.parent)

    // Step 5: Store downloaded checksums locally
    if (downloadFileSuccess.checksumMismatches < 0) {
        LOG.warn("No checksums found for {}, can't verify its correctness", repositoryArtifactUrl)
    } else {
        for (checksum in CHECKSUMS) {
            val checksumBytes = downloadFileSuccess.remoteArtifactData.checksumOrNull(checksum) ?: continue
            val filePath = cacheFile appendSuffix checksum.suffix
            filePath.writeText(toHexString(checksumBytes))
        }
    }

    // Step 6: Store downloaded artifact
    val remoteArtifactData = downloadFileSuccess.remoteArtifactData
    try {
        val openOptions =
                if (cache.cacheFileExists) {
                    arrayOf(StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)
                } else {
                    arrayOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                }
        Files.newOutputStream(cacheFile, *openOptions).use {
            it.write(remoteArtifactData.data)
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
    return ArtifactPath(cacheFile, null, repository, repositoryArtifactUrl, false).apply {
        this.dataWithChecksum = remoteArtifactData
    }
}

/** Variant of [retrieveFileRemotely] for verification that [repository] contains the same artifact as provided. */
private fun verifyArtifactMatches(repository: Repository, path: String, verifyAgainst:DataWithChecksum, listener:ActivityListener?): Boolean? {
    val repositoryArtifactUrl = repository.url / path
    LOG.debug("Retrieving file '{}' for verification from {}", path, repository)
    return retrieveFileForVerificationOnly(repositoryArtifactUrl, repository.useUnsafeTransport, verifyAgainst, listener)
}

private fun Repository.canResolve(snapshot:Boolean):Boolean {
    return if (snapshot) this.snapshots else this.releases
}

internal class ArtifactPathWithAlternateRepositories(
        /** The artifact retrieved. */
        val artifactPath:ArtifactPath,
        /** Repositories in which the artifact was also found. */
        val alternateRepositories:SortedRepositories,
        /** Repositories in which the artifact could also be found, but weren't checked yet. */
        val possibleAlternateRepositories:SortedRepositories
)

internal fun retrieveFile(path:String, snapshot:Boolean, repositories: CompatibleSortedRepositories, progressTracker: ActivityListener?, cachePath:(Repository) -> String = {path}):ArtifactPathWithAlternateRepositories? {
    val localFailures = arrayOfNulls<CacheArtifactPath>(repositories.size)
    for ((i, repository) in repositories.withIndex()) {
        retrieveFileLocally(repository, path, snapshot, cachePath(repository)).use<Unit>({
            return ArtifactPathWithAlternateRepositories(it, emptyList(), repositories.drop(i + 1))
        }, {
            localFailures[i] = it
        })
    }

    var result:ArtifactPath? = null
    var resultRepository:Repository? = null
    var alternateRepositories:SortedRepositories = emptyList()
    var possibleAlternateRepositories:SortedRepositories = emptyList()

    for ((i, cacheArtifactPath) in localFailures.withIndex()) {
        if (cacheArtifactPath == null)
            continue

        val repository = repositories[i]

        if (result == null) {
            result = retrieveFileRemotely(repository, path, snapshot, cacheArtifactPath, progressTracker) ?: continue
            resultRepository = repository

            if (repository.authoritative) {
                possibleAlternateRepositories = repositories.drop(i + 1)
                break
            }
        } else {
            val valid = verifyArtifactMatches(repository, path, result.dataWithChecksum!!, progressTracker) ?: continue
            if (valid) {
                // At two different locations, but same content, this could be fine
                val mut = alternateRepositories.toMutable()
                mut.add(repository)
                alternateRepositories = mut
            } else {
                // This is a problem! (https://blog.autsoft.hu/a-confusing-dependency/)
                LOG.warn("File {} has been found at both {} (used) and {} (ignored), with different content! Please verify the dependency, as it may have been compromised.", path, resultRepository, repository)
            }
        }
    }

    return result?.let { ArtifactPathWithAlternateRepositories(it, alternateRepositories, possibleAlternateRepositories) }
}

private fun uriToActivityName(uri:String):String {
    val maxCharacters = 64
    if (uri.length < maxCharacters) {
        return uri
    }

    val protocolEnd = uri.indexOf("//")
    if (protocolEnd == -1 || (!uri.startsWith("https://", ignoreCase = true) && !uri.startsWith("http://"))) {
        return uri
    }

    // Shorten to domain/...file
    val domainStart = protocolEnd + 2
    var domainEnd = uri.indexOf('/', startIndex = domainStart)
    if (domainEnd == -1)
        domainEnd = uri.length

    val remainingCharacters = uri.length - domainEnd
    val availableCharacters = maxCharacters - (domainEnd - domainStart)

    if (remainingCharacters <= availableCharacters) {
        return uri.substring(domainStart)
    } else {
        val result = StringBuilder(70)
        result.append(uri, domainStart, domainEnd)
        result.append('/').append(if (WemiUnicodeOutputSupported) "[…]" else "[...]")
        val remaining = maxCharacters - result.length
        result.append(uri, uri.length - remaining, uri.length)
        return result.toString()
    }
}

private fun <T> Request.execute(listener:ActivityListener?, responseTranslator:ResponseTranslator<T>):Response<T> {
    if (listener == null) {
        return execute(responseTranslator)
    } else {
        listener.beginActivity(uriToActivityName(uri))
        try {
            return execute(object : ResponseTranslator<T> {
                private val startNs = System.nanoTime()

                override fun decode(response: Response<*>, originalIn: InputStream): T {
                    val totalLength = response.headers.entries.find { it.key.equals("Content-Length", ignoreCase = true) }?.value?.first()?.toLongOrNull() ?: 0L

                    listener.activityDownloadProgress(0L, totalLength, System.nanoTime() - startNs)

                    return responseTranslator.decode(response, object : GaugedInputStream(originalIn) {

                        override var totalRead:Long = 0L
                            set(value) {
                                field = value
                                listener.activityDownloadProgress(value, totalLength, System.nanoTime() - startNs)
                            }
                    })
                }

                override fun decodeEmptyBody(response: Response<*>): T {
                    return responseTranslator.decodeEmptyBody(response)
                }
            })
        } finally {
            listener.endActivity()
        }
    }
}