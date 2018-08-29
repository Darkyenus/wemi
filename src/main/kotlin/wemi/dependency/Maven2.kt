package wemi.dependency

import com.darkyen.dave.Response
import com.darkyen.dave.Webb
import com.darkyen.dave.WebbException
import org.slf4j.LoggerFactory
import org.xml.sax.*
import org.xml.sax.helpers.DefaultHandler
import org.xml.sax.helpers.XMLReaderFactory
import wemi.dependency.Maven2.PomBuildingXMLHandler.Companion.SupportedModelVersion
import wemi.util.*
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import kotlin.collections.HashMap

/**
 * Manages resolution of dependencies through [Repository.M2] Maven repository.
 *
 * Maven repository layout is described
 * [here](https://cwiki.apache.org/confluence/display/MAVENOLD/Repository+Layout+-+Final).
 */
internal object Maven2 {

    private val LOG = LoggerFactory.getLogger(Maven2::class.java)

    private val KPomFile = ArtifactKey<Path>("pomFile", true)
    private val KPomData = ArtifactKey<ByteArray>("pomData", false)
    private val KPom = ArtifactKey<Pom>("pom", false)
    private val KPomUrl = ArtifactKey<URL>("pomUrl", true)

    fun resolveInM2Repository(dependencyId: DependencyId, repository: Repository.M2, chain: RepositoryChain): ResolvedDependency {

        // Just retrieving raw pom file
        if (dependencyId.attribute(Repository.M2.Type) == "pom") {
            return retrievePom(dependencyId, repository, chain)
        }

        val resolvedPom = retrievePom(dependencyId, repository, chain)
        val pom = resolvedPom.getKey(KPom) ?:
                return ResolvedDependency(dependencyId, emptyList(), repository, true,
                        "Failed to resolve pom: " + resolvedPom.log)

        if (resolvedPom.hasError) {
            LOG.warn("Retrieved pom for {} from {}, but resolution claims error ({}). Something may go wrong.", dependencyId, repository, resolvedPom.log)
        }

        val packaging = dependencyId.attribute(Repository.M2.Type) ?: pom.packaging

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
                            && depId.attribute(Repository.M2.Type) == tempDepId.attribute(Repository.M2.Type)
                            && depId.attribute(Repository.M2.Classifier) == tempDepId.attribute(Repository.M2.Classifier)
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
                    val scope = resolved.dependencyId.attribute(Repository.M2.Scope)?.toLowerCase()
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

    private fun retrieveFile(path: String, repository: Repository.M2): Pair<ByteArray?, Path?> {
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
        if (checksum != Repository.M2.Checksum.None) {
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

                if (dataFileWritten && cache.checksum != Repository.M2.Checksum.None) {
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
                            repository: Repository.M2,
                            chain: RepositoryChain,
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
                    attributes = mapOf(Repository.M2.Type to "pom"))

            if (pomBuilder.parentRelativePath.isNotBlank()) {
                // Create new pom-path
                val parentPomPath = (pomPath.dropLastWhile { c -> c != '/' } / pomBuilder.parentRelativePath).toString()
                LOG.trace("Retrieving parent pom of '{}' from relative '{}'", pomPath, parentPomPath)
                parent = retrievePom(parentPomId, repository, chain, parentPomPath)
            }

            if (parent == null || parent.hasError) {
                LOG.trace("Retrieving parent pom of '{}' by coordinates '{}'", pomPath, parentPomId)
                parent = DependencyResolver.resolveSingleDependency(parentPomId, chain)
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
                if (!dep.attribute(Repository.M2.Type).equals("pom", ignoreCase = true)
                        || !dep.attribute(Repository.M2.Scope).equals("import", ignoreCase = true)) {
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
                lastDependencyAttributes[Repository.M2.Classifier] = characters()
            } else if (atElement(DependencyOptional) || atElement(DependencyManagementOptional)) {
                lastDependencyAttributes[Repository.M2.Optional] = characters()
            } else if (atElement(DependencyScope) || atElement(DependencyManagementScope)) {
                lastDependencyAttributes[Repository.M2.Scope] = characters()
            } else if (atElement(DependencyType) || atElement(DependencyManagementType)) {
                lastDependencyAttributes[Repository.M2.Type] = characters()
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
        val classifier = attribute(Repository.M2.Classifier)
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