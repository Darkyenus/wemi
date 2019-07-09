package wemi.dependency.internal

import WemiVersion
import com.darkyen.dave.*
import org.slf4j.LoggerFactory
import wemi.ActivityListener
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
 * Retrieve file from repository, handling cache and checksums (TODO: And signatures).
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
    } catch (fileDoesNotExist: IOException) {
        LOG.trace("Local artifact cache does not exist: {}", cacheFile, fileDoesNotExist)
    }

    return Failable.failure(CacheArtifactPath(cacheFile, cacheFileExists, cacheControlMs))
}

private sealed class DownloadResult {
    object Failure:DownloadResult()
    object UseCache:DownloadResult()
    class Success(val remoteLastModifiedTime:Long, val remoteArtifactData:ByteArray, val checksums:Array<String?>, val checksumMismatches:Int):DownloadResult()
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
        if (e.cause is FileNotFoundException) {
            LOG.trace("Failed to retrieve '{}', file not found", repositoryArtifactUrl)
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
            val checksumResponse = httpGet(checksumUrl, useUnsafeTransport = useUnsafeTransport).execute(progressTracker, ResponseTranslator.STRING_TRANSLATOR)
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

/** Continuation of [retrieveFileLocally]. */
private fun retrieveFileRemotely(repository: Repository, path: String, snapshot:Boolean, cache:CacheArtifactPath, storeArtifact:Boolean, progressTracker: ActivityListener?): ArtifactPath? {
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

    if (storeArtifact) {
        Files.createDirectories(cacheFile.parent)
    }

    // Step 5: Store downloaded checksums locally
    if (downloadFileSuccess.checksums.all { it == null }) {
        LOG.warn("No checksums found for {}, can't verify its correctness", repositoryArtifactUrl)
    } else if (storeArtifact) {
        for ((i, checksum) in downloadFileSuccess.checksums.withIndex()) {
            checksum ?: continue
            val filePath = cacheFile appendSuffix CHECKSUMS[i].suffix
            filePath.writeText(checksum)
        }
    }

    // Step 6: Store downloaded artifact
    val remoteArtifactData: ByteArray = downloadFileSuccess.remoteArtifactData
    if (storeArtifact) {
        try {
            val openOptions =
                    if (cache.cacheFileExists) {
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
    }

    // Done
    return ArtifactPath(cacheFile, remoteArtifactData, repository, repositoryArtifactUrl, false)
}

private fun Repository.canResolve(snapshot:Boolean):Boolean {
    return if (snapshot) this.snapshots else this.releases
}

internal fun retrieveFile(path:String, snapshot:Boolean, repositories: CompatibleSortedRepositories, progressTracker: ActivityListener?, cachePath:(Repository) -> String = {path}):ArtifactPath? {
    val localFailures = arrayOfNulls<CacheArtifactPath>(repositories.size)
    for ((i, repository) in repositories.withIndex()) {
        retrieveFileLocally(repository, path, snapshot, cachePath(repository)).use<Unit>({
            return it
        }, {
            localFailures[i] = it
        })
    }

    var result:ArtifactPath? = null
    var resultRepository:Repository? = null

    for ((i, cacheArtifactPath) in localFailures.withIndex()) {
        if (cacheArtifactPath == null)
            continue

        val repository = repositories[i]
        val success = retrieveFileRemotely(repository, path, snapshot, cacheArtifactPath, result == null, progressTracker) ?: continue
        if (result == null) {
            result = success
            resultRepository = repository
        } else {
            val resultData = result.data
            val successData = success.data
            if (resultData != null && successData != null && resultData.contentEquals(successData)) {
                // At two different locations, but same content, this is fine
            } else {
                // This is a problem! (https://blog.autsoft.hu/a-confusing-dependency/)
                LOG.warn("File {} has been found at both {} (used) and {} (ignored), with different content! Please verify the dependency, as it may have been compromised.", path, resultRepository, repository)
            }
        }
    }

    return result
}

private fun <T> Request.execute(listener:ActivityListener?, responseTranslator:ResponseTranslator<T>):Response<T> {
    if (listener == null) {
        return execute(responseTranslator)
    } else {
        listener.beginActivity(uri)
        try {
            return execute(object : ResponseTranslator<T> {
                private val startNs = System.nanoTime()

                override fun decode(response: Response<*>, originalIn: InputStream): T {
                    val totalLength = response.headers.entries.find { it.key.equals("Content-Length", ignoreCase = true) }?.value?.first()?.toLongOrNull() ?: 0L

                    listener.activityProgressBytes(0L, totalLength, System.nanoTime() - startNs)

                    return responseTranslator.decode(response, object : InputStream() {

                        private var totalRead = 0L
                            set(value) {
                                field = value
                                if (value > totalReadHighMark) {
                                    totalReadHighMark = value
                                    listener.activityProgressBytes(value, totalLength, System.nanoTime() - startNs)
                                }
                            }
                        private var totalReadMark = 0L

                        private var totalReadHighMark = 0L

                        override fun read(): Int {
                            val read = originalIn.read()
                            if (read != -1) {
                                totalRead++
                            }
                            return read
                        }

                        override fun read(b: ByteArray): Int {
                            val read = super.read(b)
                            if (read > 0) {
                                totalRead += read
                            }
                            return read
                        }

                        override fun read(b: ByteArray, off: Int, len: Int): Int {
                            val read = super.read(b, off, len)
                            if (read > 0) {
                                totalRead += read
                            }
                            return read
                        }

                        override fun skip(n: Long): Long {
                            val skipped = originalIn.skip(n)
                            totalRead = maxOf(0, totalRead + skipped)
                            return skipped
                        }

                        override fun available(): Int {
                            return originalIn.available()
                        }

                        override fun close() {
                            originalIn.close()
                            listener.activityProgressBytes(totalRead, totalLength, System.nanoTime() - startNs)
                        }

                        override fun reset() {
                            originalIn.reset()
                            totalRead = totalReadMark
                        }

                        override fun mark(readlimit: Int) {
                            originalIn.mark(readlimit)
                            totalReadMark = totalRead
                        }

                        override fun markSupported(): Boolean {
                            return originalIn.markSupported()
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