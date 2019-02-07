package wemi.dependency

import com.darkyen.dave.Response
import com.darkyen.dave.Webb
import com.darkyen.dave.WebbException
import com.esotericsoftware.jsonbeans.JsonValue
import com.esotericsoftware.jsonbeans.JsonWriter
import org.slf4j.LoggerFactory
import org.xml.sax.*
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.helpers.XMLReaderFactory
import wemi.dependency.Maven2.PomBuildingXMLHandler.Companion.SupportedModelVersion
import wemi.publish.InfoNode
import wemi.util.*
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.net.URI
import java.net.URL
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.*
import kotlin.collections.HashMap

/**
 * Artifact classifier.
 * @see MavenRepository.Classifier
 */
typealias Classifier = String

/** Maven version 2 or 3 repository.
 * As described [here](https://cwiki.apache.org/confluence/display/MAVENOLD/Repository+Layout+-+Final).
 *
 * @param name of this repository, arbitrary (but should be consistent, as it is used for internal bookkeeping)
 * @param url of this repository
 * @param cache of this repository
 * @param checksum to use when retrieving artifacts from here
 * @param releases whether this repository should be used to query for release versions (non-SNAPSHOT)
 * @param snapshots whether this repository should be used to query for snapshot versions (versions ending with -SNAPSHOT)
 */
class MavenRepository(name: String, val url: URL, override val cache: MavenRepository? = null, val checksum: Checksum = MavenRepository.Checksum.SHA1, val releases:Boolean = true, val snapshots:Boolean = true) : Repository(name) {

    init {
        if (!releases && !snapshots) {
            LOG.warn("{} is not used for releases nor snapshots, so it will be always skipped", this)
        }
    }

    override val local: Boolean
        get() = "file".equals(url.protocol, ignoreCase = true)

    override fun resolveInRepository(dependency: DependencyId, repositories: Collection<Repository>): ResolvedDependency {
        return Maven2.resolveInM2Repository(dependency, this, repositories)
    }

    override fun directoryToLock(): Path? {
        return directoryPath()
    }

    /**
     * @return Path to the directory root, if on this filesystem
     */
    private fun directoryPath(): Path? {
        try {
            if (local) {
                return FileSystems.getDefault().getPath(url.path)
            }
        } catch (ignored:Exception) { }
        return null
    }

    override fun publish(metadata: InfoNode, artifacts: List<Pair<Path, String?>>): URI {
        val lock = directoryToLock()

        return if (lock != null) {
            directorySynchronized(lock) {
                publishLocked(metadata, artifacts)
            }
        } else {
            publishLocked(metadata, artifacts)
        }
    }

    private fun Path.checkValidForPublish(snapshot:Boolean) {
        if (Files.exists(this)) {
            if (snapshot) {
                LOG.info("Overwriting {}", this)
            } else {
                throw UnsupportedOperationException("Can't overwrite published non-snapshot file $this")
            }
        } else {
            Files.createDirectories(this.parent)
        }
    }

    private fun publishLocked(metadata: InfoNode, artifacts: List<Pair<Path, String?>>): URI {
        val path = directoryPath() ?: throw UnsupportedOperationException("Can't publish to non-local repository")

        val groupId = metadata.findChild("groupId")?.text ?: throw IllegalArgumentException("Metadata is missing a groupId:\n$metadata")
        val artifactId = metadata.findChild("artifactId")?.text ?: throw IllegalArgumentException("Metadata is missing a artifactId:\n$metadata")
        val version = metadata.findChild("version")?.text ?: throw IllegalArgumentException("Metadata is missing a version:\n$metadata")

        val snapshot = version.endsWith("-SNAPSHOT")

        val pomPath = path / Maven2.pomPath(groupId, artifactId, version)
        LOG.debug("Publishing metadata to {}", pomPath)
        pomPath.checkValidForPublish(snapshot)
        val pomXML = metadata.toXML()
        Files.newBufferedWriter(pomPath, Charsets.UTF_8).use {
            it.append(pomXML)
        }
        // Create pom.xml hashes
        run {
            val pomXMLBytes = pomXML.toString().toByteArray(Charsets.UTF_8)
            for (checksum in PublishChecksums) {
                val digest = checksum.digest()!!.digest(pomXMLBytes)

                val publishedName = pomPath.name
                val checksumFile = pomPath.parent.resolve("$publishedName${checksum.suffix}")
                checksumFile.checkValidForPublish(snapshot)

                checksumFile.writeText(createHashSum(digest, publishedName))
                LOG.debug("Publishing metadata {} to {}", checksum, checksumFile)
            }
        }


        for ((artifact, classifier) in artifacts) {
            val publishedArtifact = path / Maven2.artifactPath(groupId, artifactId, version, classifier, artifact.name.pathExtension())
            LOG.debug("Publishing {} to {}", artifact, publishedArtifact)
            publishedArtifact.checkValidForPublish(snapshot)

            Files.copy(artifact, publishedArtifact, StandardCopyOption.REPLACE_EXISTING)
            // Create hashes
            val checksums = PublishChecksums
            val digests = Array(checksums.size) { checksums[it].digest()!! }

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
                checksumFile.checkValidForPublish(snapshot)

                checksumFile.writeText(createHashSum(digest, publishedName))
                LOG.debug("Publishing {} {} to {}", publishedArtifact, checksum, checksumFile)
            }

            LOG.info("Published {} with {} checksum(s)", publishedArtifact, checksums.size)
        }

        return pomPath.parent.toUri()
    }

    companion object {
        private val LOG = LoggerFactory.getLogger(MavenRepository::class.java)

        /**
         * Various variants of the same dependency.
         * Examples: jdk15, sources, javadoc, linux
         */
        val Classifier = DependencyAttribute("m2-classifier", true)
        /**
         * Corresponds to the packaging of the dependency (and overrides it).
         * Determines what sort of artifact is retrieved.
         *
         * Examples: jar (default), pom (returns pom.xml, used internally)
         */
        val Type = DependencyAttribute("m2-type", true, "jar")
        /**
         * Scope of the dependency.
         *
         * Examples: compile, provided, test
         * See https://maven.apache.org/pom.html#Dependencies
         *
         * In Wemi used only when filtering.
         */
        val Scope = DependencyAttribute("m2-scope", false, "compile")
        /**
         * Optional dependencies are skipped by default by Wemi.
         */
        val Optional = DependencyAttribute("m2-optional", false, "false")

        /**
         * Concatenate two classifiers.
         */
        internal fun joinClassifiers(first:String?, second:String?):String? {
            return when {
                first == null -> second
                second == null -> first
                else -> "$first-$second"
            }
        }

        /**
         * Classifier appended to artifacts with sources
         */
        const val SourcesClassifier = "sources"
        /**
         * Classifier appended to artifacts with Javadoc
         */
        const val JavadocClassifier = "javadoc"

        /**
         * [Checksum]s to generate when publishing an artifact.
         */
        val PublishChecksums = arrayOf(Checksum.MD5, Checksum.SHA1)

        init {

        }
    }

    internal object Serializer : JsonSerializer<MavenRepository> {
        override fun JsonWriter.write(value: MavenRepository) {
            field("name", value.name)
            field("url", value.url)

            field("cache", value.cache)
            field("checksum", value.checksum)
            field("releases", value.releases)
            field("snapshots", value.snapshots)
        }

        override fun read(value: JsonValue): MavenRepository {
            return MavenRepository(
                    value.field("name"),
                    value.field("url"),
                    value.field("cache"),
                    value.field("checksum"),
                    value.field("releases"),
                    value.field("snapshots"))
        }
    }

    /**
     * Types of checksum in Maven repositories.
     *
     * @param suffix of files with this checksum (extension with dot)
     * @param algo Java digest algorithm name to use when computing this checksum
     */
    enum class Checksum(val suffix: String, private val algo: String) {
        /**
         * Special value for no checksum.
         *
         * Not recommended for general use - use only in extreme cases.
         */
        None(".no-checksum", "no-op"),
        // https://en.wikipedia.org/wiki/File_verification
        /**
         * Standard SHA1 algorithm with .md5 suffix.
         */
        MD5(".md5", "MD5"),
        /**
         * Standard SHA1 algorithm with .sha1 suffix.
         */
        SHA1(".sha1", "SHA-1");

        /**
         * Creates a [MessageDigest] for this [Checksum].
         * @return null if [None]
         * @throws java.security.NoSuchAlgorithmException if not installed
         */
        fun digest(): MessageDigest? {
            if (this == None) {
                return null
            }
            val digest = MessageDigest.getInstance(algo)
            digest.reset()
            return digest
        }

        fun checksum(data: ByteArray): ByteArray {
            val digest = digest() ?: return ByteArray(0)
            digest.update(data)
            return digest.digest()
        }
    }

    override fun toString(): String {
        val sb = StringBuilder()
        sb.append("M2: ").append(name).append(" at ").append(url)
        if (cache != null) {
            sb.append(" (cached by ").append(cache.name).append(')')
        }
        return sb.toString()
    }
}

/**
 * Manages resolution of dependencies through [MavenRepository] Maven repository.
 *
 * Maven repository layout is described
 * [here](https://cwiki.apache.org/confluence/display/MAVENOLD/Repository+Layout+-+Final)
 * or [here](https://blog.packagecloud.io/eng/2017/03/09/how-does-a-maven-repository-work/).
 */
internal object Maven2 {

    private val LOG = LoggerFactory.getLogger(Maven2::class.java)

    private val KPomFile = ArtifactKey<Path>("pomFile", true)
    private val KPomData = ArtifactKey<ByteArray>("pomData", false)
    private val KPom = ArtifactKey<Pom>("pom", false)
    private val KPomUrl = ArtifactKey<URL>("pomUrl", true)

    fun resolveInM2Repository(dependencyId: DependencyId, repository: MavenRepository, repositories: Collection<Repository>): ResolvedDependency {
        val snapshot = dependencyId.isSnapshot
        if (snapshot && !repository.snapshots) {
            return ResolvedDependency(dependencyId, emptyList(), repository, true, "Release only repository skipped for snapshot dependency")
        } else if (!snapshot && !repository.releases) {
            return ResolvedDependency(dependencyId, emptyList(), repository, true, "Snapshot only repository skipped for release dependency")
        }

        // Just retrieving raw pom file
        val resolvedPom = retrievePom(dependencyId, repository, repositories)

        /*
        TODO Implement:
        Snapshot resolution logic abstract:
        - Some snapshots ("non-unique snapshots") are overwritten on publish, so the resolution schema is the same
            - but we need to check remote before local copy
        - Other snapshots ("unique snapshots") use maven-metadata.xml, we need to download that to cache first

        Steps (for snapshots):
        1. Look into cache, if it contains POM or maven-metadata.xml
            1. If it contains either, note its last modified date
        2. Download the POM or maven-metadata.xml (preferring maven-metadata.xml)
            - If cache contained one of the two, try that first
            - If cache contained one of the two, make the query with cache control,
                so that in the best case we don't have to re-download anything
            - If both download attempts fail, resolution failed
        3. If neither was downloaded fresh, resolve completely from cache
        4. Otherwise
            - (POM) continue resolution normally for the POM, overriding the cache
            - (metadata) parse metadata, select newest version (TODO: Maybe some control over this through DependencyId attributes)
                download that. Metadata should be overridden in the cache, rest must not be.
         */

        if (dependencyId.attribute(MavenRepository.Type) == "pom") {
            return resolvedPom
        }

        val pom = resolvedPom.getKey(KPom) ?:
                return ResolvedDependency(dependencyId, emptyList(), repository, true,
                        "Failed to resolve pom: " + resolvedPom.log)

        if (resolvedPom.hasError) {
            LOG.warn("Retrieved pom for {} from {}, but resolution claims error ({}). Something may go wrong.", dependencyId, repository, resolvedPom.log)
        }

        val packaging = dependencyId.attribute(MavenRepository.Type) ?: pom.packaging

        when (packaging) {
            "pom" -> {
                return resolvedPom
            }
            "jar", "bundle" -> { // TODO Should osgi bundles have different handling?
                val jarPath = dependencyId.artifactPath("jar")
                val (data, file) = retrieveFile(jarPath, repository)

                if (file == null) {
                    if (data == null) {
                        LOG.warn("Failed to retrieve jar at '{}' in {}", jarPath, repository)
                    } else {
                        LOG.warn("Jar at '{}' in {} retrieved, but is not on local filesystem. Cache repository may be missing.", jarPath, repository)
                    }
                }

                return ResolvedDependency(dependencyId, pom.effectiveDependencies(), repository, file == null).apply {
                    this.artifact = file
                    if (file == null) {
                        // Do not store artifact's data, unless strictly necessary.
                        // Storing it always would only create a memory leak, as the value is rarely used,
                        // can always be lazily loaded and the size of all dependencies can be quite big.
                        this.artifactData = data
                    }

                }
            }
            else -> {
                LOG.warn("Unsupported packaging {} of {}", packaging, dependencyId)
                return ResolvedDependency(dependencyId, pom.effectiveDependencies(), repository, true)
            }
        }
    }

    /**
     * Contains raw data from a Pom file.
     * Note that when accessing some fields, it is also necessary to check the parent if not set.
     * Inherited elements: https://maven.apache.org/pom.html#Inheritance
     */
    private data class Pom(
            var groupId: String? = null,
            var artifactId: String? = null,
            var version: String? = null,
            var packaging: String = "jar",
            val properties: MutableMap<String, String> = HashMap()
    ) {

        var parent:Pom? = null
        val dependencies = ArrayList<Dependency>()
        val dependencyManagement = ArrayList<Dependency>()

        private inline fun <T> get(getter:Pom.()->T?):T? {
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
            var explicitValuePom = this@Pom
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

        private fun Dependency.translate():Dependency {
            val (dependencyId, exclusions) = this

            val translatedDependencyId = DependencyId(
                    dependencyId.group.translate(),
                    dependencyId.name.translate(),
                    dependencyId.version.translate(),
                    dependencyId.preferredRepository,
                    dependencyId.attributes.mapValues { it.value.translate() }
            )

            val translatedExclusions = exclusions.map { exclusion ->
                DependencyExclusion(exclusion.group.translate(),
                        exclusion.name.translate(),
                        exclusion.version.translate(),
                        exclusion.attributes.mapValues { it.value.translate() })
            }

            return Dependency(translatedDependencyId, translatedExclusions)
        }

        fun translateWithProperties() {
            groupId = groupId?.translate()
            artifactId = artifactId?.translate()
            version = version?.translate()
            packaging = packaging.translate()

            dependencies.replaceAll { it.translate() }
            dependencyManagement.replaceAll { it.translate() }
        }

        /**
         * Resolves against dependencyManagement.
         * http://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Management
         */
        private fun resolve(dependency:Dependency):Dependency {
            var pom = this@Pom
            while (true) {
                checkTemplate@for (templateDep in pom.dependencyManagement) {
                    // Check if dependency is full subset of templateDep, and use templateDep instead if it is
                    // At least groupId, artifactId, type and classifier must all match to consider for replacement
                    val depId = dependency.dependencyId
                    val tempDepId = templateDep.dependencyId
                    if (depId.group == tempDepId.group
                            && depId.name == tempDepId.name
                            && depId.attribute(MavenRepository.Type) == tempDepId.attribute(MavenRepository.Type)
                            && depId.attribute(MavenRepository.Classifier) == tempDepId.attribute(MavenRepository.Classifier)
                            && (depId.version.isBlank() || depId.version == tempDepId.version)) {
                        // Check for full attribute subset
                        for ((attrKey, attrValue) in depId.attributes) {
                            if (tempDepId.attribute(attrKey) != attrValue) {
                                continue@checkTemplate
                            }
                        }
                        // Check for full exclusions subset
                        if (!templateDep.exclusions.containsAll(dependency.exclusions)) {
                            continue@checkTemplate
                        }

                        // Matches
                        LOG.trace("Dependency {} replaced with {} from dependencyManagement", dependency, templateDep)
                        return templateDep
                    }
                }
                pom = pom.parent ?: break
            }
            return dependency
        }

        /**
         * Retrieves dependencies that usage of this POM mandates.
         * Handles things like dependencyManagement correctly, but not on transitive dependencies,
         * as that is handled in higher layer of dependency resolution process.
         *
         * TODO: dependencyManagement of transitive dependencies is not handled to be Maven-like
         */
        fun effectiveDependencies():List<Dependency> {
            val dependencies = ArrayList<Dependency>()

            var pom = this
            while (true) {
                for (dependency in pom.dependencies) {
                    val resolved = pom.resolve(dependency)

                    // Check if the scope it is in is transitive
                    // https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope
                    val scope = resolved.dependencyId.attribute(MavenRepository.Scope)?.toLowerCase()
                    when (scope) {
                        "compile", "runtime" -> {
                            // Kept
                            dependencies.add(resolved)
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
                pom = pom.parent ?: break
            }

            return dependencies
        }
    }

    private fun retrieveFile(path: String, repository: MavenRepository): Pair<ByteArray?, Path?> {
        val url = repository.url / path
        if (repository.local) {
            LOG.debug("Retrieving file '{}' from {}", path, repository)
        } else {
            LOG.info("Retrieving file '{}' from {}", path, repository)
        }

        // Download
        val webb = Webb(null)
        val response: Response<ByteArray>
        try {
            response = webb.get(url.toExternalForm()).executeBytes()
        } catch (e: WebbException) {
            if (e.cause is FileNotFoundException) {
                LOG.debug("Failed to retrieve '{}' from {}, file does not exist", path, repository)
            } else {
                LOG.debug("Failed to retrieve '{}' from {}", path, repository, e)
            }
            return Pair(null, null)
        }

        if (!response.isSuccess) {
            LOG.debug("Failed to retrieve '{}' from {} - status code {}", path, repository, response.statusCode)
            return Pair(null, null)
        }

        // Checksum
        val checksum = repository.checksum
        var computedChecksum:ByteArray? = null
        if (checksum != MavenRepository.Checksum.None) {
            // Compute checksum
            computedChecksum = checksum.checksum(response.body)

            try {
                // Retrieve checksum
                val expectedChecksumData = webb.get((repository.url / (path + checksum.suffix)).toExternalForm()).executeString().body
                val expectedChecksum = parseHashSum(expectedChecksumData)

                when {
                    expectedChecksum.isEmpty() ->
                        LOG.warn("Checksum of '{}' in {} is malformed ('{}'), continuing without checksum", path, repository, expectedChecksumData)
                    hashMatches(expectedChecksum, computedChecksum, path.takeLastWhile { it != '/' }) ->
                        LOG.trace("Checksum of '{}' in {} is valid", path, repository)
                    else -> {
                        LOG.warn("Checksum of '{}' in {} is wrong! Expected {}, got {}", path, repository, computedChecksum, expectedChecksumData)
                        return Pair(null, null)
                    }
                }
            } catch (e: WebbException) {
                if (e.cause is FileNotFoundException) {
                    LOG.warn("Failed to retrieve {} checksum '{}' from {}, file does not exist, continuing without checksum", checksum, path, repository)
                } else {
                    LOG.warn("Failed to retrieve {} checksum '{}' from {}, continuing without checksum", checksum, path, repository, e)
                }
            } catch (e: Exception) {
                LOG.warn("Failed to verify checksum of '{}' in {}, continuing without checksum", path, repository, e)
            }
        } else {
            LOG.debug("Not computing checksum for '{}' in {}", path, repository)
        }

        var retrievedFile: Path? = null

        val cache = repository.cache
        if (cache != null) {
            // We should cache it!
            val dataFile: Path? = (cache.url / path).toPath()

            if (dataFile == null) {
                LOG.warn("Can't save '{}' from {} into cache at {} - cache is not local?", path, repository, cache)
            } else if (dataFile.exists()) {
                var fileIsEqual = true

                if (dataFile.size != response.body.size.toLong()) {
                    fileIsEqual = false
                }

                if (fileIsEqual) {
                    retrievedFile = dataFile
                    LOG.trace("Not saving '{}' from {} into cache at {} - file '{}' already exists", path, repository, cache, dataFile)
                } else {
                    LOG.warn("Not saving '{}' from {} into cache at {} - file '{}' already exists and is different!", path, repository, cache, dataFile)
                }
            } else {
                var dataFileWritten = false
                try {
                    Files.createDirectories(dataFile.parent)
                    Files.newOutputStream(dataFile).use {
                        it.write(response.body, 0, response.body.size)
                    }
                    LOG.debug("File '{}' from {} cached successfully", path, repository)
                    retrievedFile = dataFile
                    dataFileWritten = true
                } catch (e: Exception) {
                    LOG.warn("Error trying to save '{}' from {} into cache at {} - file '{}'", path, repository, cache, dataFile, e)
                }

                if (dataFileWritten && cache.checksum != MavenRepository.Checksum.None) {
                    val checksumFile: Path = (cache.url / (path + cache.checksum.suffix)).toPath()!!
                    if (computedChecksum == null) {
                        computedChecksum = cache.checksum.checksum(response.body)
                    }

                    try {
                        checksumFile.writeText(createHashSum(computedChecksum, path.takeLastWhile { it != '/' }))
                        LOG.debug("Checksum of file '{}' from {} cached successfully", path, repository)
                    } catch (e: Exception) {
                        LOG.warn("Error trying to save checksum of '{}' from {} into cache at {} - file '{}'", path, repository, cache, checksumFile, e)
                    }
                }

            }
        } else {
            try {
                retrievedFile = (repository.url / path).toPath()
            } catch (e: IllegalArgumentException) {
                LOG.debug("File '{}' from {} does not have local representation", path, repository, e)
            }
        }

        // Done
        return Pair(response.body, retrievedFile)
    }

    private fun retrievePom(dependencyId:DependencyId,
                            repository: MavenRepository,
                            chain: Collection<Repository>,
                            pomPath:String = pomPath(dependencyId.group, dependencyId.name, dependencyId.version)): ResolvedDependency {
        // Create path to pom
        val pomUrl = repository.url / pomPath
        LOG.trace("Retrieving pom at '{}' in {} for {}", pomPath, repository, dependencyId)
        val (pomData, pomFile) = retrieveFile(pomPath, repository)
        if (pomData == null) {
            return ResolvedDependency(dependencyId, emptyList(), repository, true, "File not retrieved").apply {
                if (pomFile != null) {
                    putKey(KPomFile, pomFile)
                }
                putKey(KPomUrl, pomUrl)
            }
        }
        val pom = Pom()

        val reader = XMLReaderFactory.createXMLReader()
        val pomBuilder = PomBuildingXMLHandler(pomUrl, pom)
        reader.contentHandler = pomBuilder
        reader.errorHandler = pomBuilder
        try {
            reader.parse(InputSource(ByteArrayInputStream(pomData)))
        } catch (se: SAXException) {
            LOG.warn("Error during parsing {}", pomUrl, se)
            return ResolvedDependency(dependencyId, emptyList(), repository, true, "Malformed xml").apply {
                if (pomFile != null) {
                    putKey(KPomFile, pomFile)
                }
                putKey(KPomData, pomData)
                putKey(KPomUrl, pomUrl)
            }
        }

        if (pomBuilder.hasParent) {
            var parent: ResolvedDependency? = null
            val parentPomId = DependencyId(pomBuilder.parentGroupId,
                    pomBuilder.parentArtifactId,
                    pomBuilder.parentVersion,
                    preferredRepository = repository,
                    attributes = mapOf(MavenRepository.Type to "pom"))

            if (pomBuilder.parentRelativePath.isNotBlank()) {
                // Create new pom-path
                val parentPomPath = (pomPath.dropLastWhile { c -> c != '/' } / pomBuilder.parentRelativePath).toString()
                LOG.trace("Retrieving parent pom of '{}' from relative '{}'", pomPath, parentPomPath)
                parent = retrievePom(parentPomId, repository, chain, parentPomPath)
            }

            if (parent == null || parent.hasError) {
                LOG.trace("Retrieving parent pom of '{}' by coordinates '{}'", pomPath, parentPomId)
                parent = LibraryDependencyResolver.resolveSingleDependency(parentPomId, chain)
            }

            val parentPom = parent.getKey(KPom)
            if (parent.hasError || parentPom == null) {
                LOG.warn("Pom at '{}' in {} claims to have a parent, but it has not been found (resolved to {})", pomPath, repository, parent)
            } else {
                if (!parentPom.packaging.equals("pom", ignoreCase = true)) {
                    LOG.warn("Parent of '{}' in {} has parent with invalid packaging {} (expected 'pom')", pomPath, repository, parentPom.packaging)
                }
                pom.parent = parentPom
            }
        }

        pom.translateWithProperties()

        // Resolve <dependencyManagement> <scope>import
        // http://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope
        //TODO Untested, no known project that uses it
        pom.dependencyManagement.let { dependencyManagement ->
            var i = 0
            while (i < dependencyManagement.size) {
                val dep = dependencyManagement[i].dependencyId
                if (!dep.attribute(MavenRepository.Type).equals("pom", ignoreCase = true)
                        || !dep.attribute(MavenRepository.Scope).equals("import", ignoreCase = true)) {
                    i++
                    continue
                }

                LOG.trace("Resolving dependencyManagement import {} in '{}'", dep, pomPath)
                val importPom = retrievePom(dep, repository, chain)
                if (importPom.hasError) {
                    LOG.warn("dependencyManagement import in {} of {} failed: '{}'", pomPath, dep, importPom.log)
                }

                dependencyManagement.removeAt(i)
                val imported = importPom.getKey(KPom)?.dependencyManagement
                if (imported != null && imported.isNotEmpty()) {
                    LOG.trace("dependencyManagement of {} imported {} from {}", pomPath, imported, dep)

                    // Specification says, that it should be replaced
                    dependencyManagement.addAll(i, imported)
                    i += imported.size
                }
            }
        }

        return ResolvedDependency(dependencyId, emptyList(), repository, false).apply {
            if (pomFile != null) {
                putKey(KPomFile, pomFile)
            }
            putKey(KPomData, pomData)
            putKey(KPomUrl, pomUrl)
            putKey(KPom, pom)
        }
    }

    private class PomBuildingXMLHandler(private val pomUrl: URL, private val pom: Pom) : DefaultHandler(), ErrorHandler {

        var parentGroupId = ""
        var parentArtifactId = ""
        var parentVersion = ""
        var parentRelativePath = ""
        var hasParent = false

        private var lastDependencyGroupId = ""
        private var lastDependencyArtifactId = ""
        private var lastDependencyVersion = ""
        private var lastDependencyExclusions = ArrayList<DependencyExclusion>()
        private var lastDependencyAttributes = HashMap<DependencyAttribute, String>()

        private var lastDependencyExclusionGroupId = ""
        private var lastDependencyExclusionArtifactId = ""
        private var lastDependencyExclusionVersion = "*"

        private val elementStack = ArrayList<String>()
        private val elementCharacters = StringBuilder()

        private fun atElement(path: Array<String>, ignoreLast: Int = 0): Boolean {
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

        override fun startElement(uri: String, localName: String, qName: String, attributes: Attributes) {
            elementStack.add(localName)
            elementCharacters.setLength(0)
        }

        override fun endElement(uri: String, localName: String, qName: String) {
            // Version
            if (atElement(ModelVersion)) {
                if (characters().trim() != SupportedModelVersion) {
                    throw SAXException("Unsupported modelVersion: $elementCharacters")
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
                lastDependencyAttributes[MavenRepository.Classifier] = characters()
            } else if (atElement(DependencyOptional) || atElement(DependencyManagementOptional)) {
                lastDependencyAttributes[MavenRepository.Optional] = characters()
            } else if (atElement(DependencyScope) || atElement(DependencyManagementScope)) {
                lastDependencyAttributes[MavenRepository.Scope] = characters()
            } else if (atElement(DependencyType) || atElement(DependencyManagementType)) {
                lastDependencyAttributes[MavenRepository.Type] = characters()
            } else if (atElement(Dependency)) {
                val projectId = DependencyId(lastDependencyGroupId, lastDependencyArtifactId, lastDependencyVersion, attributes = lastDependencyAttributes)
                val pomDependency = Dependency(projectId, lastDependencyExclusions)
                pom.dependencies.add(pomDependency)

                lastDependencyGroupId = ""
                lastDependencyArtifactId = ""
                lastDependencyVersion = ""
                lastDependencyAttributes = HashMap()

                lastDependencyExclusions = ArrayList()
            } else if (atElement(DependencyManagementDependency)) {
                val projectId = DependencyId(lastDependencyGroupId, lastDependencyArtifactId, lastDependencyVersion, attributes = lastDependencyAttributes)
                val pomDependency = Dependency(projectId, lastDependencyExclusions)
                pom.dependencyManagement.add(pomDependency)

                lastDependencyGroupId = ""
                lastDependencyArtifactId = ""
                lastDependencyVersion = ""
                lastDependencyAttributes = HashMap()

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
                val propertyContent = elementCharacters.toString()

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
                LOG.debug("Pom at {} uses custom repositories, which are not supported yet", pomUrl)
            }

            elementStack.removeAt(elementStack.size - 1)
            elementCharacters.setLength(0)
        }

        private fun characters() = elementCharacters.toString()

        override fun characters(ch: CharArray, start: Int, length: Int) {
            elementCharacters.append(ch, start, length)
        }

        override fun warning(exception: SAXParseException?) {
            LOG.warn("warning during parsing {}", pomUrl, exception)
        }

        override fun error(exception: SAXParseException?) {
            LOG.warn("error during parsing {}", pomUrl, exception)
        }

        companion object {
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

    private fun DependencyId.artifactPath(extension: String): String {
        val classifier = attribute(MavenRepository.Classifier)
        return Maven2.artifactPath(group, name, version, classifier, extension)
    }

    fun pomPath(group:String, name:String, version:String):String {
        val sb = group.replace('.', '/') / name / version
        sb.append('/').append(name).append('-').append(version).append(".pom")
        return sb.toString()
    }

    fun artifactPath(group:String, name:String, version:String, classifier:String?, extension: String):String {
        val fileName = StringBuilder()
        fileName.append(name).append('-').append(version)
        if (classifier != null) {
            fileName.append('-').append(classifier)
        }
        fileName.append('.').append(extension)

        return (group.replace('.', '/') / name / version / fileName).toString()
    }
}