package wemi.dependency

import com.darkyen.dave.Response
import com.darkyen.dave.Webb
import com.darkyen.dave.WebbException
import org.slf4j.LoggerFactory
import org.xml.sax.*
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.helpers.XMLReaderFactory
import wemi.dependency.PomBuildingXMLHandler.Companion.SupportedModelVersion
import wemi.publish.InfoNode
import wemi.util.*
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.net.URI
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.*
import kotlin.collections.HashMap


private val LOG = LoggerFactory.getLogger("Maven2")

/** [Checksum]s to generate when publishing an artifact. */
private val PublishChecksums = arrayOf(Checksum.MD5, Checksum.SHA1)

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

/** Attempt to resolve [dependencyId] in [repository], using [repositories] for direct immediate dependencies. */
fun resolveInM2Repository(dependencyId: DependencyId, repository: Repository, repositories: Collection<Repository>): ResolvedDependency {
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

    if (dependencyId.type == "pom") {
        return resolvedPom
    }

    val pom = resolvedPom.pom ?: return ResolvedDependency(dependencyId, emptyList(), repository, true,
            "Failed to resolve pom: " + resolvedPom.log)

    if (resolvedPom.hasError) {
        LOG.warn("Retrieved pom for {} from {}, but resolution claims error ({}). Something may go wrong.", dependencyId, repository, resolvedPom.log)
    }

    val packaging = if (dependencyId.type == DEFAULT_TYPE) pom.packaging else dependencyId.type

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

            return ResolvedDependency(dependencyId, pom.dependencies, repository, file == null).apply {
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
            return ResolvedDependency(dependencyId, pom.dependencies, repository, true)
        }
    }
}


private fun retrieveFile(path: String, repository: Repository): Pair<ByteArray?, Path?> {
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
    if (checksum != Checksum.None) {
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

            if (dataFileWritten && cache.checksum != Checksum.None) {
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


private fun retrieveRawPom(dependencyId: DependencyId,
                           repository: Repository,
                           chain: Collection<Repository>,
                           pomPath:String = pomPath(dependencyId.group, dependencyId.name, dependencyId.version)): ResolvedDependency {
    // Create path to pom
    val pomUrl = repository.url / pomPath
    LOG.trace("Retrieving pom at '{}' in {} for {}", pomPath, repository, dependencyId)
    val (pomData, pomFile) = retrieveFile(pomPath, repository)
    if (pomData == null) {
        return ResolvedDependency(dependencyId, emptyList(), repository, true, "File not retrieved").apply {
            this.pomFile = pomFile
            this.pomUrl = pomUrl
        }
    }
    val rawPom = RawPom(pomUrl)

    val reader = XMLReaderFactory.createXMLReader()
    val pomBuilder = PomBuildingXMLHandler(rawPom)
    reader.contentHandler = pomBuilder
    reader.errorHandler = pomBuilder
    try {
        reader.parse(InputSource(ByteArrayInputStream(pomData)))
    } catch (se: SAXException) {
        LOG.warn("Error during parsing {}", pomUrl, se)
        return ResolvedDependency(dependencyId, emptyList(), repository, true, "Malformed xml").apply {
            this.pomFile = pomFile
            this.pomData = pomData
            this.pomUrl = pomUrl
        }
    }

    if (pomBuilder.hasParent) {
        var parent: ResolvedDependency? = null
        val parentPomId = DependencyId(pomBuilder.parentGroupId,
                pomBuilder.parentArtifactId,
                pomBuilder.parentVersion,
                preferredRepository = repository,
                type = "pom")

        if (pomBuilder.parentRelativePath.isNotBlank()) {
            // Create new pom-path
            val parentPomPath = (pomPath.dropLastWhile { c -> c != '/' } / pomBuilder.parentRelativePath).toString()
            LOG.trace("Retrieving parent pom of '{}' from relative '{}'", pomPath, parentPomPath)
            parent = retrieveRawPom(parentPomId, repository, chain, parentPomPath)
        }

        if (parent == null || parent.hasError) {
            LOG.trace("Retrieving parent pom of '{}' by coordinates '{}'", pomPath, parentPomId)
            // TODO(jp): Needs to resolve only raw pom!
            parent = LibraryDependencyResolver.resolveSingleDependency(parentPomId, chain)
        }

        val parentPom = parent.rawPom
        if (parent.hasError || parentPom == null) {
            LOG.warn("Pom at '{}' in {} claims to have a parent, but it has not been found (resolved to {})", pomPath, repository, parent)
        } else {
            rawPom.parent = parentPom
        }
    }

    return ResolvedDependency(dependencyId, emptyList(), repository, false).apply {
        this.pomFile = pomFile
        this.pomData = pomData
        this.pomUrl = pomUrl
        this.rawPom = rawPom
    }
}

private fun retrievePom(dependencyId: DependencyId,
                        repository: Repository,
                        chain: Collection<Repository>,
                        pomPath:String = pomPath(dependencyId.group, dependencyId.name, dependencyId.version)): ResolvedDependency {
    val retrievedRawPom = retrieveRawPom(dependencyId, repository, chain)
    if (retrievedRawPom.hasError) {
        return retrievedRawPom
    }
    val rawPom = retrievedRawPom.rawPom ?: return ResolvedDependency(retrievedRawPom.id, retrievedRawPom.dependencies, retrievedRawPom.resolvedFrom, true, "No raw pom found!")

    val pom = rawPom.resolve()

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

        LOG.trace("Resolving dependencyManagement import {} in '{}'", dep, pomPath)
        val importPom = retrievePom(dep, repository, chain)
        if (importPom.hasError) {
            LOG.warn("dependencyManagement import in {} of {} failed: '{}'", pomPath, dep, importPom.log)
        }

        val imported = importPom.pom?.dependencyManagement
        if (imported != null && imported.isNotEmpty()) {
            LOG.trace("dependencyManagement of {} imported {} from {}", pomPath, imported, dep)

            // Specification says, that it should be replaced
            flatDependencyManagement.addAll(imported)
        }
    }
    val fullyResolvedPom = pom.resolveEffectiveDependencies(flatDependencyManagement)

    return ResolvedDependency(dependencyId, emptyList(), repository, false).apply {
        this.pomFile = retrievedRawPom.pomFile
        this.pomData = retrievedRawPom.pomData
        this.pomUrl = retrievedRawPom.pomUrl
        this.rawPom = retrievedRawPom.rawPom
        this.pom = fullyResolvedPom
    }
}

fun pomPath(group:String, name:String, version:String):String {
    val sb = group.replace('.', '/') / name / version
    sb.append('/').append(name).append('-').append(version).append(".pom")
    return sb.toString()
}

private fun DependencyId.artifactPath(extension: String): String {
    return artifactPath(group, name, version, classifier, extension)
}

private fun artifactPath(group:String, name:String, version:String, classifier:Classifier, extension: String):String {
    val fileName = StringBuilder()
    fileName.append(name).append('-').append(version)
    if (classifier.isNotEmpty()) {
        fileName.append('-').append(classifier)
    }
    fileName.append('.').append(extension)

    return (group.replace('.', '/') / name / version / fileName).toString()
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

    val pomPath = path / pomPath(groupId, artifactId, version)
    LOG.debug("Publishing metadata to {}", pomPath)
    checkValidForPublish(pomPath, snapshot)
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
            checkValidForPublish(checksumFile, snapshot)

            checksumFile.writeText(createHashSum(digest, publishedName))
            LOG.debug("Publishing metadata {} to {}", checksum, checksumFile)
        }
    }


    for ((artifact, classifier) in artifacts) {
        val publishedArtifact = path / artifactPath(groupId, artifactId, version, classifier, artifact.name.pathExtension())
        LOG.debug("Publishing {} to {}", artifact, publishedArtifact)
        checkValidForPublish(publishedArtifact, snapshot)

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
            checkValidForPublish(checksumFile, snapshot)

            checksumFile.writeText(createHashSum(digest, publishedName))
            LOG.debug("Publishing {} {} to {}", publishedArtifact, checksum, checksumFile)
        }

        LOG.info("Published {} with {} checksum(s)", publishedArtifact, checksums.size)
    }

    return pomPath.parent.toUri()
}

/** Translated and resolved [RawPom], ready to be used. */
internal class Pom(val groupId:String?, val artifactId:String?, val version:String?,
                  val packaging:String,
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
        val dependencies = ArrayList<Dependency>()
        for (dependency in dependencies) {
            val resolved = resolveDependencyManagement(dependency, dependencyManagement)

            // Check if the scope it is in is transitive
            // https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html#Dependency_Scope
            when (resolved.dependencyId.scope.toLowerCase()) {
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

        return Pom(groupId, artifactId, version, packaging, dependencies, dependencyManagement)
    }
}

/**
 * Contains raw data from a Pom file.
 * Note that when accessing some fields, it is also necessary to check the parent if not set.
 *
 * Before using, resolve with [resolve].
 */
internal class RawPom(
        val url:URL,
        var groupId: String? = null,
        var artifactId: String? = null,
        var version: String? = null,
        var packaging: String = "jar",
        val properties: MutableMap<String, String> = HashMap()) {

    var parent: RawPom? = null
    val dependencies = ArrayList<RawPomDependency>()
    val dependencyManagement = ArrayList<RawPomDependency>()

    fun resolve():Pom {
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
            pom.dependencies.mapTo(dependencies) { translate(it) }
            pom.dependencyManagement.mapTo(dependencyManagement) { translate(it) }
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

    private fun translate(raw:RawPomDependency): Dependency {
        val translatedDependencyId = DependencyId(
                raw.group?.translate() ?: "",
                raw.name?.translate() ?: "",
                raw.version?.translate() ?: "",
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

private class PomBuildingXMLHandler(private val pom: RawPom) : DefaultHandler(), ErrorHandler {

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
            LOG.debug("Pom at {} uses custom repositories, which are not supported yet", pom.url)
        }

        elementStack.removeAt(elementStack.size - 1)
        elementCharacters.setLength(0)
    }

    private fun characters() = elementCharacters.toString()

    override fun characters(ch: CharArray, start: Int, length: Int) {
        elementCharacters.append(ch, start, length)
    }

    override fun warning(exception: SAXParseException?) {
        LOG.warn("warning during parsing {}", pom.url, exception)
    }

    override fun error(exception: SAXParseException?) {
        LOG.warn("error during parsing {}", pom.url, exception)
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
