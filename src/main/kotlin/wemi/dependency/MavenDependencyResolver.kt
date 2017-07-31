package wemi.dependency

import com.darkyen.dave.Response
import com.darkyen.dave.Webb
import com.darkyen.dave.WebbException
import org.slf4j.LoggerFactory
import org.xml.sax.*
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.helpers.XMLReaderFactory
import wemi.util.div
import wemi.util.fromHexString
import wemi.util.toFile
import wemi.util.toHexString
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URL
import java.util.*


private val LOG = LoggerFactory.getLogger("MavenDependencyResolver")

/**
 *
 */
internal object MavenDependencyResolver {

    fun resolveInM2Repository(projectDependency: ProjectDependency, repository: Repository.M2): ResolvedProject {
        val (project, exclusions) = projectDependency
        val pom = retrievePom(project, repository) ?: return ResolvedProject(project, null, emptyList(), true)

        val dependencies = pom.dependencies.mapNotNull { (dependency, dependencyExclusions) ->
            if (exclusions.any { rule ->
                if (rule.excludes(dependency)) {
                    LOG.debug("Excluded {} with rule {} (dependency of {})", dependency, rule, projectDependency.project)
                    true
                } else false
            }) {
                return@mapNotNull null
            } else {
                return@mapNotNull ProjectDependency(dependency, dependencyExclusions + exclusions)
            }
        }

        var error = false

        val packaging = project.attributes[Repository.M2.M2TypeAttribute] ?: pom.packaging
        val artifact: File? = when (packaging) {
            "pom" -> null
            "jar" -> {
                val jarPath = project.artifactPath("jar")
                val (data, file) = retrieveFile(jarPath, repository)
                if (file == null) {
                    error = true
                    if (data == null) {
                        LOG.warn("Failed to retrieve jar at '{}' in {}", jarPath, repository)
                        null
                    } else {
                        LOG.warn("Jar at '{}' in {} retrieved, but is not on local filesystem. Cache repository may be missing.", jarPath, repository)
                        null
                    }
                } else {
                    file
                }
            }
            else -> {
                LOG.warn("Unsupported packaging {} of {}", packaging, project)
                error = true
                null
            }
        }

        return ResolvedProject(project, artifact, dependencies, error)
    }

    private data class Pom(
            var groupId: String? = null,
            var artifactId: String? = null,
            var version: String? = null,
            var packaging: String = "jar"
    ) {

        val dependencies = mutableListOf<ProjectDependency>()

        fun assignParent(pom: Pom) {
            if (groupId == null) {
                groupId = pom.groupId
            }
            if (version == null) {
                version = pom.version
            }
            if (!pom.dependencies.isEmpty()) {
                // TODO Remove duplicates?
                dependencies.addAll(pom.dependencies)
            }
        }
    }

    private fun retrieveFile(path: String, repository: Repository.M2): Pair<ByteArray?, File?> {
        val url = repository.url / path
        LOG.debug("Retrieving file '{}' from {}", path, repository)

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

    private fun retrievePom(project: ProjectId, repository: Repository.M2): Pom? {
        return retrievePom(project.pomPath(), project, repository)
    }

    private fun retrievePom(pomPath: String, project: ProjectId?, repository: Repository.M2): Pom? {
        // Create path to pom
        val pomUrl = repository.url / pomPath
        LOG.debug("Retrieving pom at '{}' in {} for {}", pomPath, repository, project ?: "parent project")
        val pomData = retrieveFile(pomPath, repository)
        if (pomData.first == null) {
            return null
        }
        val pom = Pom()

        val reader = XMLReaderFactory.createXMLReader()
        val pomBuilder = PomBuildingXMLHandler(pomUrl, pom)
        reader.contentHandler = pomBuilder
        reader.errorHandler = pomBuilder
        try {
            reader.parse(InputSource(ByteArrayInputStream(pomData.first)))
        } catch (se: SAXException) {
            LOG.warn("fatal error during parsing {}", pomUrl, se)
            return null
        }

        if (pomBuilder.hasParent) {
            var parent: Pom? = null
            if (pomBuilder.parentRelativePath.isNotBlank()) {
                // Create new pom-path
                val parentPomPath = (pomPath.dropLastWhile { c -> c != '/' } / pomBuilder.parentRelativePath).toString()
                LOG.debug("Retrieving parent pom of '{}' from relative '{}'", pomPath, parentPomPath)
                parent = retrievePom(parentPomPath, null, repository)
            }
            if (parent == null) {
                val parentProjectId = ProjectId(pomBuilder.parentGroupId, pomBuilder.parentArtifactId, pomBuilder.parentVersion)
                LOG.debug("Retrieving parent pom of '{}' by coordinates '{}'", pomPath, parentProjectId)
                parent = retrievePom(parentProjectId, repository)
            }

            if (parent == null) {
                LOG.warn("Pom at '{}' in {} claims to have a parent, but it has not been found", pomPath, repository)
            } else {
                if (!parent.packaging.equals("pom", ignoreCase = true)) {
                    LOG.warn("Parent of '{}' in {} has parent with invalid packaging {} (expected 'pom')", pomPath, repository, parent.packaging)
                }
                pom.assignParent(parent)
            }
        }

        return pom
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
        private var lastDependencyExclusions = mutableListOf<ProjectExclusion>()
        private var lastDependencyAttributes = mutableMapOf<ProjectAttribute, String>()

        private var lastDependencyExclusionGroupId = ""
        private var lastDependencyExclusionArtifactId = ""
        private var lastDependencyExclusionVersion = "*"

        private val elementStack = mutableListOf<String>()
        private val elementCharacters = StringBuilder()

        private fun atElement(path: Array<String>): Boolean {
            if (elementStack.size != path.size) {
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
                val projectId = ProjectId(lastDependencyGroupId, lastDependencyArtifactId, lastDependencyVersion, attributes = lastDependencyAttributes)
                lastDependencyGroupId = ""
                lastDependencyArtifactId = ""
                lastDependencyVersion = ""
                lastDependencyAttributes = mutableMapOf<ProjectAttribute, String>()

                val pomDependency = ProjectDependency(projectId, lastDependencyExclusions)
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
                lastDependencyExclusions.add(ProjectExclusion(
                        lastDependencyExclusionGroupId, lastDependencyExclusionArtifactId, lastDependencyExclusionVersion))
                lastDependencyExclusionGroupId = ""
                lastDependencyExclusionArtifactId = ""
                lastDependencyExclusionVersion = "*"
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

            val Repo = arrayOf("project", "repositories", "repository")
            val RepoReleases = arrayOf("project", "repositories", "repository", "releases")
            val RepoSnapshots = arrayOf("project", "repositories", "repository", "snapshots")
            val RepoId = arrayOf("project", "repositories", "repository", "id")
            val RepoName = arrayOf("project", "repositories", "repository", "name")
            val RepoUrl = arrayOf("project", "repositories", "repository", "url")
            val RepoLayout = arrayOf("project", "repositories", "repository", "layout")
        }
    }

    private fun ProjectId.pomPath(): String {
        val sb = group.replace('.', '/') / name / version
        sb.append('/').append(name).append('-').append(version).append(".pom")
        return sb.toString()
    }

    private fun ProjectId.artifactPath(extension: String): String {
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