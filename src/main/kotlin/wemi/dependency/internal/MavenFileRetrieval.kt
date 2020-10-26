package wemi.dependency.internal

import com.darkyen.dave.Request
import com.darkyen.dave.Response
import com.darkyen.dave.ResponseTranslator
import com.darkyen.dave.WebbException
import org.slf4j.LoggerFactory
import wemi.ActivityListener
import wemi.boot.WemiUnicodeOutputSupported
import wemi.collections.toMutable
import wemi.dependency.ArtifactPath
import wemi.dependency.CHECKSUMS
import wemi.dependency.CompatibleSortedRepositories
import wemi.dependency.DataWithChecksum
import wemi.dependency.Repository
import wemi.dependency.SortedRepositories
import wemi.submit
import wemi.util.Failable
import wemi.util.GaugedInputStream
import wemi.util.ParsedChecksumFile
import wemi.util.appendSuffix
import wemi.util.appendToPath
import wemi.util.div
import wemi.util.execute
import wemi.util.hashMatches
import wemi.util.httpGet
import wemi.util.onResponse
import wemi.util.parseHashSum
import wemi.util.toHexString
import wemi.util.toPath
import wemi.util.writeText
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException


private val LOG = LoggerFactory.getLogger("MavenFileRetrieval")

private class CacheArtifactPath(val repository:Repository, val cacheFile: Path, val cacheFileExists: Boolean, val cacheControlMs: Long)

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
 * @return [Failable]'s [CacheArtifactPath] may be `null` if the repository is local and does not contain the file
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
                LOG.warn("A local artifact is a directory: {}", localFile)
                return Failable.failure(null)
            }
            LOG.debug("Using local artifact: {}", localFile)
            return Failable.success(ArtifactPath(localFile, null, repository, repositoryArtifactUrl,false))
        } catch (fileDoesNotExist: java.nio.file.NoSuchFileException) {
            LOG.trace("A local artifact does not exist: {}", localFile)
            return Failable.failure(null)
        } catch (ioProblem: IOException) {
            LOG.warn("Failed to retrieve a local artifact: {}", localFile, ioProblem)
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

    return Failable.failure(CacheArtifactPath(repository, cacheFile, cacheFileExists, cacheControlMs))
}

private sealed class DownloadResult {
    class Failure(val reason:String):DownloadResult()
    object UseCache:DownloadResult()
    class Success(val remoteLastModifiedTime:Long, val remoteArtifactData:DataWithChecksum, val checksumMismatches:Int):DownloadResult()
}

private fun retrieveFileDownloadAndVerify(repositoryArtifactUrl: URL, cacheControlMs: Long, snapshot: Boolean, useUnsafeTransport:Boolean, progressTracker: ActivityListener?):DownloadResult {
    var checksumPrefetch: Future<List<ParsedChecksumFile?>>? = null
    try {

        val response: Response<ByteArray>
        // Falls back to current time if headers are invalid/missing, as we don't have any better metric
        var remoteLastModifiedTime = System.currentTimeMillis()
        try {
            response = httpGet(repositoryArtifactUrl, cacheControlMs, useUnsafeTransport = useUnsafeTransport)
                    .execute(progressTracker, ResponseTranslator.BYTES_TRANSLATOR.onResponse { responseStart ->
                        if (responseStart.isSuccess) {
                            // We got response, and it is likely that we will need checksums, start fetching them now
                            checksumPrefetch = ForkJoinPool.commonPool().submit({ tracker -> retrieveChecksums(repositoryArtifactUrl, useUnsafeTransport, tracker) }, progressTracker, "Checksum pre-fetch")
                        }
                    })

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
            return DownloadResult.Failure(e.message ?: e.toString())
        }

        val statusCode = response.statusCode
        if (snapshot && statusCode == 304 /* Not modified */) {
            return DownloadResult.UseCache
        } else if (!response.isSuccess) {
            LOG.debug("Failed to retrieve '{}' - status code {}", repositoryArtifactUrl, statusCode)
            if (statusCode == 404) {
                return DownloadResult.Failure("File does not exist - check the coordinates for typos and ensure that you have added the corresponding repository (HTTP 404)")
            } else if (statusCode / 100 == 5) {
                return DownloadResult.Failure("Repository server error - check that you have added the right repository or try again later (HTTP $statusCode)")
            } else {
                return DownloadResult.Failure("HTTP $statusCode")
            }
        }
        val remoteArtifactData = DataWithChecksum(response.body)

        // Step 4: download and verify checksums
        val checksums = checksumPrefetch.let { checksumPrefetch_ ->
            if (checksumPrefetch_ != null) {
                checksumPrefetch_.get()
            } else {
                retrieveChecksums(repositoryArtifactUrl, useUnsafeTransport, progressTracker)
            }
        }
        val checksumMismatches = verifyChecksums(checksums, repositoryArtifactUrl, remoteArtifactData, progressTracker)

        return DownloadResult.Success(remoteLastModifiedTime, remoteArtifactData, checksumMismatches)
    } finally {
        checksumPrefetch?.cancel(true)
    }
}

/** Retrieve checksums for given artifact that has been found at [repositoryArtifactUrl].
 * @return array of found checksums, whose size and elements correspond to elements of the [wemi.dependency.Checksum] enum. */
private fun retrieveChecksums(repositoryArtifactUrl: URL, useUnsafeTransport: Boolean, progressTracker: ActivityListener?):List<ParsedChecksumFile?> {
    progressTracker?.beginActivity("Retrieving checksums")
    try {
        return CHECKSUMS.map { checksum ->
            ForkJoinPool.commonPool().submit({ taskProgressTracker ->
                val checksumUrl = repositoryArtifactUrl.appendToPath(checksum.suffix)
                val checksumFileBody: String? = try {
                    val checksumResponse = httpGet(checksumUrl, useUnsafeTransport = useUnsafeTransport).execute(taskProgressTracker, ResponseTranslator.STRING_TRANSLATOR)
                    if (!checksumResponse.isSuccess) {
                        LOG.debug("Failed to retrieve checksum '{}' (code: {})", checksumUrl, checksumResponse.statusCode)
                        return@submit null
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
                    return@submit null
                }

                parseHashSum(checksumFileBody)
            }, progressTracker, "$checksum")
        }.map { it.get() }
    } finally {
        progressTracker?.endActivity()
    }
}

/** Verify that retrieved [checksums] for artifact from [repositoryArtifactUrl] match [artifactData].
 * @return amount of mismatched checksums, 0 if none, -1 if no checksums found */
private fun verifyChecksums(checksums: List<ParsedChecksumFile?>, repositoryArtifactUrl: URL, artifactData: DataWithChecksum, progressTracker: ActivityListener?): Int {
    progressTracker?.beginActivity("Verifying checksums")
    try {
        var checksumsChecked = 0
        var checksumMismatchBits = 0

        for ((i, checksum) in CHECKSUMS.withIndex()) {
            val remoteChecksum = checksums[i] ?: continue

            if (remoteChecksum.isEmpty()) {
                LOG.warn("Failed to retrieve {} checksum for '{}': file is malformed", checksum, repositoryArtifactUrl)
            } else {
                // Compute checksum
                val artifactChecksum = artifactData.checksum(checksum)

                checksumsChecked++
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
    } finally {
        progressTracker?.endActivity()
    }
}

/**
 * Download enough data to be able to verify that if [path] in [repository] contains any artifact, it is the same as [verifyAgainst].
 * @return true if there is an artifact and it matches, false if there is an artifact and it does not match, null if there is no artifact
 */
private fun retrieveFileForVerificationOnly(repository:Repository, path:String, verifyAgainst:DataWithChecksum, listener:ActivityListener?):Boolean? {
    val repositoryArtifactUrl = repository.url / path
    LOG.debug("Retrieving file '{}' for verification from {}", path, repository)

    // Verification step 1: Download all checksums
    val checksums = retrieveChecksums(repositoryArtifactUrl, repository.useUnsafeTransport, listener)
    val checksumMismatches = verifyChecksums(checksums, repositoryArtifactUrl, verifyAgainst, listener)
    if (checksumMismatches == 0) {
        return true
    } else if (checksumMismatches > 0) {
        return false
    }

    // There were no checksum files, so we have to download the whole thing
    val response: Response<ByteArray>
    try {
        response = httpGet(repositoryArtifactUrl, useUnsafeTransport = repository.useUnsafeTransport).execute(listener, ResponseTranslator.BYTES_TRANSLATOR)
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
private fun retrieveFileRemotely(repository: Repository, path: String, snapshot:Boolean, cache:CacheArtifactPath, progressTracker: ActivityListener?): Failable<ArtifactPath, String> {
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
            is DownloadResult.Failure -> return Failable.failure("Failed to download file: ${downloadFileResult.reason}")
            DownloadResult.UseCache -> {
                LOG.trace("Using local artifact cache (snapshot not modified): {}", cacheFile)
                return Failable.success(ArtifactPath(cacheFile, null, repository, repositoryArtifactUrl, true))
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
                        return Failable.failure("Checksum mismatch")
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
        return Failable.failure("Failed to save to cache: ${e.message}")
    }
    if (snapshot) {
        try {
            Files.setLastModifiedTime(cacheFile, FileTime.fromMillis(downloadFileSuccess.remoteLastModifiedTime))
        } catch (e: IOException) {
            LOG.warn("Failed to change artifact's '{}' modify time, snapshot cache control may be slightly off", cacheFile, e)
        }
    }

    // Done
    return Failable.success(ArtifactPath(cacheFile, null, repository, repositoryArtifactUrl, false).apply {
        this.dataWithChecksum = remoteArtifactData
    })
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

private data class FileRetrievalMutexToken(val cacheHome:Path?, val path:String)
private val fileRetrievalMutex_lockedTokens = HashSet<FileRetrievalMutexToken>()

/** Run [action] when no other thread is currently using the [path] in given [repositories]. */
private fun <T> fileRetrievalMutex(path:String, repositories: List<Repository>, action:()->T):T {
    val tokens = repositories.map { FileRetrievalMutexToken(it.directoryPath(), path) }

    // Lock the mutex
    val lockedTokens = fileRetrievalMutex_lockedTokens
    synchronized(lockedTokens) {
        while (tokens.any { it in lockedTokens }) {
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (lockedTokens as Object).wait()
        }

        lockedTokens.addAll(tokens)
    }
    try {
        return action()
    } finally {
        synchronized(lockedTokens) {
            lockedTokens.removeAll(tokens)
            @Suppress("PLATFORM_CLASS_MAPPED_TO_KOTLIN")
            (lockedTokens as Object).notifyAll()
        }
    }
}

/** Retrieve the [path] (which may be a [snapshot]) from [repositories].
 * Thread safe, but make sure that [progressTracker] is thread safe as well when forking. */
internal fun retrieveFile(path:String, snapshot:Boolean, repositories: CompatibleSortedRepositories, progressTracker: ActivityListener?, cachePath:(Repository) -> String = {path}):Failable<ArtifactPathWithAlternateRepositories, String> = fileRetrievalMutex(path, repositories) {
    // Check all local repositories and caches
    val localFailures = arrayOfNulls<CacheArtifactPath>(repositories.size)
    for ((i, repository) in repositories.withIndex()) {
        retrieveFileLocally(repository, path, snapshot, cachePath(repository)).use<Unit>({
            return@fileRetrievalMutex Failable.success(ArtifactPathWithAlternateRepositories(it, emptyList(), repositories.drop(i + 1)))
        }, {
            localFailures[i] = it
        })
    }

    // Find (first) result
    var failure:Failable<Any?, String>? = null
    for ((i, cacheArtifactPath) in localFailures.withIndex()) {
        if (cacheArtifactPath == null)
            continue

        val repository = repositories[i]
        val resultFailable = retrieveFileRemotely(repository, path, snapshot, cacheArtifactPath, progressTracker)
        val result = if (resultFailable.successful) {
            resultFailable.value
        } else {
            failure = resultFailable
            null
        } ?: continue

        var alternateRepositories:SortedRepositories = emptyList()
        var possibleAlternateRepositories:SortedRepositories = emptyList()

        if (repository.authoritative) {
            possibleAlternateRepositories = repositories.drop(i + 1)
        } else {
            // Verify other repositories

            // Create parallel tasks
            val tasks = Array(localFailures.size - i - 1) {
                val localFailure = localFailures[i + it + 1] ?: return@Array null
                ForkJoinPool.commonPool().submit({ trackerFork ->
                    retrieveFileForVerificationOnly(localFailure.repository, path, result.dataWithChecksum!!, trackerFork)
                }, progressTracker, "Verifying uniqueness of $path from $repository against ${localFailure.repository}")
            }

            // Collect their results
            for ((j, task) in tasks.withIndex()) {
                val valid = task?.get() ?: continue
                val altRepository = repositories[i + 1 + j]
                if (valid) {
                    // At two different locations, but same content, this could be fine
                    val mut = alternateRepositories.toMutable()
                    mut.add(altRepository)
                    alternateRepositories = mut
                } else {
                    // This is a problem! (https://blog.autsoft.hu/a-confusing-dependency/)
                    LOG.warn("File {} has been found at both {} (used) and {} (ignored), with different content! Please verify the dependency, as it may have been compromised.", path, repository, altRepository)
                }
            }
        }

        return@fileRetrievalMutex Failable.success(ArtifactPathWithAlternateRepositories(result, alternateRepositories, possibleAlternateRepositories))
    }

    return@fileRetrievalMutex failure?.reFail() ?: Failable.failure("No suitable repositories to search in")
}
