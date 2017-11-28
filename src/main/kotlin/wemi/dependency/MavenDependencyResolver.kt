package wemi.dependency

import com.darkyen.dave.Response
import com.darkyen.dave.Webb
import com.darkyen.dave.WebbException
import org.slf4j.LoggerFactory
import org.xml.sax.*
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.helpers.XMLReaderFactory
import wemi.dependency.MavenDependencyResolver.PomBuildingXMLHandler.Companion.SupportedModelVersion
import wemi.util.div
import wemi.util.fromHexString
import wemi.util.toFile
import wemi.util.toHexString
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.util.*
import kotlin.collections.HashMap


private val LOG = LoggerFactory.getLogger("MavenDependencyResolver")

/**
 *
 */
internal object MavenDependencyResolver {

    private val PomFile = ArtifactKey<File>("pomFile", true)
    private val PomData = ArtifactKey<ByteArray>("pomData", false)
    private val PomKey = ArtifactKey<Pom>("pom", false)
    private val PomUrlKey = ArtifactKey<URL>("pomUrl", true)

    fun resolveInM2Repository(dependency: Dependency, repository: Repository.M2, chain:RepositoryChain): ResolvedDependency {
        val (dependencyId, exclusions) = dependency

        // Just retrieving raw pom file
        if (dependency.dependencyId.attributes[Repository.M2.M2TypeAttribute] == "pom") {
            return retrievePom(dependencyId, repository, chain)
        }

        val resolvedPom = retrievePom(dependencyId, repository, chain)
        val pom = resolvedPom.getKey(PomKey) ?:
                return ResolvedDependency(dependencyId, emptyList(), repository, true,
                        "Failed to resolve pom: "+resolvedPom.log)

        if (resolvedPom.hasError) {
            LOG.warn("Retrieved pom for {} from {}, but resolution claims error ({}). Something may go wrong.", dependencyId, repository, resolvedPom.log)
        }

        val dependencies = pom.dependencies.mapNotNull { (pomDependencyId, dependencyExclusions) ->
            if (exclusions.any { rule ->
                if (rule.excludes(pomDependencyId)) {
                    LOG.debug("Excluded {} with rule {} (dependency of {})", pomDependencyId, rule, dependency.dependencyId)
                    true
                } else false
            }) {
                return@mapNotNull null
            } else {
                return@mapNotNull Dependency(pomDependencyId, dependencyExclusions + exclusions)
            }
        }

        val packaging = dependencyId.attributes[Repository.M2.M2TypeAttribute] ?: pom.packaging

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

                return ResolvedDependency(dependencyId, dependencies, repository, file == null).apply {
                    this.artifact = file
                    this.artifactData = data

                }
            }
            else -> {
                LOG.warn("Unsupported packaging {} of {}", packaging, dependencyId)
                return ResolvedDependency(dependencyId, dependencies, repository, true)
            }
        }
    }

    private data class Pom(
            var groupId: String? = null,
            var artifactId: String? = null,
            var version: String? = null,
            var packaging: String = "jar",
            val properties:MutableMap<String, String> = HashMap()
    ) {

        val dependencies = ArrayList<Dependency>()

        private fun String.translate():String {
            if (!startsWith("\${", ignoreCase = false) || !endsWith('}', ignoreCase = false)){
                return this
            }

            val key = substring(2, length - 1)
            val explicitKey = properties[key]
            if (explicitKey != null) {
                LOG.debug("Unreliable Pom resolution: property '{}' resolved through explicit properties to '{}'", key, explicitKey)
                return explicitKey
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
                    "project.groupId" -> groupId ?: ""
                    "project.artifactId" -> artifactId ?: ""
                    "project.version" -> version ?: ""
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

        fun applyProperties() {
            groupId = groupId?.translate()
            artifactId = artifactId?.translate()
            version = version?.translate()
            packaging = packaging.translate()

            for (i in dependencies.indices) {
                val (dependencyId, exclusions) = dependencies[i]

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

                dependencies[i] = Dependency(translatedDependencyId, translatedExclusions)
            }
        }

        fun assignParent(parent: Pom) {
            if (groupId == null) {
                groupId = parent.groupId
            }
            if (version == null) {
                version = parent.version
            }
            if (!parent.dependencies.isEmpty()) {
                // TODO Remove duplicates?
                dependencies.addAll(parent.dependencies)
            }
            for ((property, value) in parent.properties) {
                if (!properties.contains(property)) {
                    properties.put(property, value)
                }
            }
        }
    }

    private fun retrieveFile(path: String, repository: Repository.M2): Pair<ByteArray?, File?> {
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
            LOG.debug("Failed to retrieve '{}' from {}", path, repository, e)
            return Pair(null, null)
        }

        if (!response.isSuccess) {
            LOG.debug("Failed to retrieve '{}' from {} - status code {}", path, repository, response.statusCode)
            return Pair(null, null)
        }

        // Checksum
        var computedChecksumString: String? = null

        val checksum = repository.checksum
        if (checksum != Repository.M2.Checksum.None) {
            // Compute checksum
            val computedChecksum = checksum.checksum(response.body)
            computedChecksumString = toHexString(computedChecksum)

            try {
                // Retrieve checksum
                val expectedChecksumString = webb.get((repository.url / (path + checksum.suffix)).toExternalForm()).executeString().body
                val expectedChecksum = fromHexString(expectedChecksumString)

                if (expectedChecksum == null) {
                    LOG.warn("Checksum of '{}' in {} is malformed ('{}'), continuing without checksum", path, repository, expectedChecksumString)
                } else if (Arrays.equals(expectedChecksum, computedChecksum)) {
                    LOG.debug("Checksum of '{}' in {} is valid", path, repository)
                } else {
                    LOG.warn("Checksum of '{}' in {} is wrong! Expected {}, got {}", path, repository, computedChecksumString, toHexString(expectedChecksum))
                    return Pair(null, null)
                }
            } catch (e: WebbException) {
                LOG.warn("Failed to retrieve {} checksum '{}' from {}, continuing without checksum", checksum, path, repository, e)
            } catch (e: Exception) {
                LOG.warn("Failed to verify checksum of '{}' in {}, continuing without checksum", path, repository, e)
            }
        } else {
            LOG.debug("Not computing checksum for '{}' in {}", path, repository)
        }

        var retrievedFile: File? = null

        val cache = repository.cache
        if (cache != null) {
            // We should cache it!
            val dataFile: File? = (cache.url / path).toFile()

            if (dataFile == null) {
                LOG.warn("Can't save '{}' from {} into cache at {} - cache is not local?", path, repository, cache)
            } else if (dataFile.exists()) {
                var fileIsEqual = true

                if (dataFile.length() != response.body.size.toLong()) {
                    fileIsEqual = false
                }

                if (fileIsEqual) {
                    retrievedFile = dataFile
                    LOG.debug("Not saving '{}' from {} into cache at {} - file '{}' already exists", path, repository, cache, dataFile)
                } else {
                    LOG.warn("Not saving '{}' from {} into cache at {} - file '{}' already exists and is different!", path, repository, cache, dataFile)
                }
            } else {
                var dataFileWritten = false
                try {
                    dataFile.parentFile.mkdirs()
                    dataFile.writeBytes(response.body)
                    LOG.debug("File '{}' from {} cached successfully", path, repository)
                    retrievedFile = dataFile
                    dataFileWritten = true
                } catch (e: Exception) {
                    LOG.warn("Error trying to save '{}' from {} into cache at {} - file '{}'", path, repository, cache, dataFile, e)
                }

                if (dataFileWritten && cache.checksum != Repository.M2.Checksum.None) {
                    val checksumFile: File = (cache.url / (path + cache.checksum.suffix)).toFile()!!
                    var checksumString: String? = null
                    if (repository.checksum == cache.checksum) {
                        checksumString = computedChecksumString
                    }
                    if (checksumString == null) {
                        checksumString = toHexString(cache.checksum.checksum(response.body))
                    }
                    try {
                        checksumFile.writeText(checksumString)
                        LOG.debug("Checksum of file '{}' from {} cached successfully", path, repository)
                    } catch (e: Exception) {
                        LOG.warn("Error trying to save checksum of '{}' from {} into cache at {} - file '{}'", path, repository, cache, checksumFile, e)
                    }
                }

            }
        } else {
            try {
                retrievedFile = (repository.url / path).toFile()
            } catch (e: IllegalArgumentException) {
                LOG.debug("File '{}' from {} does not have local representation", path, repository, e)
            }
        }

        // Done
        return Pair(response.body, retrievedFile)
    }

    private fun retrievePom(dependencyId: DependencyId, repository: Repository.M2, chain: RepositoryChain): ResolvedDependency {
        return doRetrieveUntranslatedPom(dependencyId.pomPath(), dependencyId, repository, chain).apply {
            getKey(PomKey)?.applyProperties()
        }
    }

    /**
     * To be called only from [retrievePom].
     *
     * Returned Pom may contain untranslated properties
     */
    private fun doRetrieveUntranslatedPom(pomPath: String, dependencyId: DependencyId, repository: Repository.M2, chain: RepositoryChain): ResolvedDependency {
        // Create path to pom
        val pomUrl = repository.url / pomPath
        LOG.debug("Retrieving pom at '{}' in {} for {}", pomPath, repository, dependencyId)
        val (pomData, pomFile) = retrieveFile(pomPath, repository)
        if (pomData == null) {
            return ResolvedDependency(dependencyId, emptyList(), repository, true).apply {
                if (pomFile != null) {
                    putKey(PomFile, pomFile)
                }
                putKey(PomUrlKey, pomUrl)
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
            LOG.warn("fatal error during parsing {}", pomUrl, se)
            return ResolvedDependency(dependencyId, emptyList(), repository, true, "Malformed xml").apply {
                if (pomFile != null) {
                    putKey(PomFile, pomFile)
                }
                putKey(PomData, pomData)
                putKey(PomUrlKey, pomUrl)
            }
        }

        if (pomBuilder.hasParent) {
            var parent: ResolvedDependency? = null
            val parentPomId = DependencyId(pomBuilder.parentGroupId,
                    pomBuilder.parentArtifactId,
                    pomBuilder.parentVersion,
                    preferredRepository = repository,
                    attributes = mapOf(Repository.M2.M2TypeAttribute to "pom"))

            if (pomBuilder.parentRelativePath.isNotBlank()) {
                // Create new pom-path
                val parentPomPath = (pomPath.dropLastWhile { c -> c != '/' } / pomBuilder.parentRelativePath).toString()
                LOG.debug("Retrieving parent pom of '{}' from relative '{}'", pomPath, parentPomPath)
                parent = doRetrieveUntranslatedPom(parentPomPath, parentPomId, repository, chain)
            }

            if (parent == null || parent.hasError) {
                LOG.debug("Retrieving parent pom of '{}' by coordinates '{}'", pomPath, parentPomId)
                parent = DependencyResolver.resolveSingleDependency(Dependency(parentPomId), chain)
            }

            val parentPom = parent.getKey(PomKey)
            if (parent.hasError || parentPom == null) {
                LOG.warn("Pom at '{}' in {} claims to have a parent, but it has not been found (resolved to {})", pomPath, repository, parent)
            } else {
                if (!parentPom.packaging.equals("pom", ignoreCase = true)) {
                    LOG.warn("Parent of '{}' in {} has parent with invalid packaging {} (expected 'pom')", pomPath, repository, parentPom.packaging)
                }
                pom.assignParent(parentPom)
            }
        }

        return ResolvedDependency(dependencyId, emptyList(), repository, false).apply {
            if (pomFile != null) {
                putKey(PomFile, pomFile)
            }
            putKey(PomData, pomData)
            putKey(PomUrlKey, pomUrl)
            putKey(PomKey, pom)
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
        private var lastDependencyExclusions = mutableListOf<DependencyExclusion>()
        private var lastDependencyAttributes = mutableMapOf<DependencyAttribute, String>()

        private var lastDependencyExclusionGroupId = ""
        private var lastDependencyExclusionArtifactId = ""
        private var lastDependencyExclusionVersion = "*"

        private val elementStack = ArrayList<String>()
        private val elementCharacters = StringBuilder()

        private fun atElement(path: Array<String>, ignoreLast:Int = 0): Boolean {
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
                    throw SAXException("Unsupported modelVersion: " + elementCharacters)
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

            // Dependencies
            else if (atElement(DependencyGroupId)) {
                lastDependencyGroupId = characters()
            } else if (atElement(DependencyArtifactId)) {
                lastDependencyArtifactId = characters()
            } else if (atElement(DependencyVersion)) {
                lastDependencyVersion = characters()
            } else if (atElement(DependencyClassifier)) {
                lastDependencyAttributes[Repository.M2.M2ClassifierAttribute] = characters()
            } else if (atElement(DependencyOptional)) {
                lastDependencyAttributes[Repository.M2.M2OptionalAttribute] = characters()
            } else if (atElement(DependencyScope)) {
                lastDependencyAttributes[Repository.M2.M2ScopeAttribute] = characters()
            } else if (atElement(DependencyType)) {
                lastDependencyAttributes[Repository.M2.M2TypeAttribute] = characters()
            } else if (atElement(Dependency)) {
                val projectId = DependencyId(lastDependencyGroupId, lastDependencyArtifactId, lastDependencyVersion, attributes = lastDependencyAttributes)
                lastDependencyGroupId = ""
                lastDependencyArtifactId = ""
                lastDependencyVersion = ""
                lastDependencyAttributes = mutableMapOf<DependencyAttribute, String>()

                val pomDependency = Dependency(projectId, lastDependencyExclusions)
                lastDependencyExclusions = mutableListOf()

                pom.dependencies.add(pomDependency)
            }

            //Dependency exclusions
            else if (atElement(DependencyExclusionGroupId)) {
                lastDependencyExclusionGroupId = characters()
            } else if (atElement(DependencyExclusionArtifactId)) {
                lastDependencyExclusionArtifactId = characters()
            } else if (atElement(DependencyExclusionVersion)) {
                lastDependencyExclusionVersion = characters()
            } else if (atElement(DependencyExclusion)) {
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

                pom.properties.put(propertyName, propertyContent)
            }

            // Repositories
            else if (atElement(RepoReleases)) {

            } else if (atElement(RepoSnapshots)) {

            } else if (atElement(RepoId)) {

            } else if (atElement(RepoName)) {

            } else if (atElement(RepoUrl)) {

            } else if (atElement(RepoLayout)) {

            } else if (atElement(Repo)) {
                LOG.warn("Pom at {} uses custom repositories, which are not supported yet", pomUrl)
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
            val SupportedModelVersion = "4.0.0"

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
            val DependencyExclusionVersion = arrayOf("project", "dependencies", "dependency", "exclusions", "exclusion", "version")

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

    private fun DependencyId.pomPath(): String {
        val sb = group.replace('.', '/') / name / version
        sb.append('/').append(name).append('-').append(version).append(".pom")
        return sb.toString()
    }

    private fun DependencyId.artifactPath(extension: String): String {
        val fileName = StringBuilder()
        fileName.append(name).append('-').append(version)
        val classifier = attributes[Repository.M2.M2ClassifierAttribute]
        if (classifier != null) {
            fileName.append('-').append(classifier)
        }
        fileName.append('.').append(extension)

        return (group.replace('.', '/') / name / version / fileName).toString()
    }
}