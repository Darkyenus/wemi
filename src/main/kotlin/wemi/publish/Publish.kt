package wemi.publish

import wemi.Configurations
import wemi.Keys
import wemi.Scope
import wemi.dependency.Classifier
import wemi.dependency.Repository
import java.nio.file.Path

/**
 * Artifact to [Classifier] pairing used in [wemi.Keys.publishArtifacts].
 */
typealias ArtifactEntry = Pair<Path, Classifier?>

/**
 * Collect artifact entries to be added to [wemi.Keys.publishArtifacts].
 *
 * ### Example:
 * ```
 * Keys.publishArtifacts addAll { using(buildingLegacy) { artifacts("legacy", true, true) } }
 * ```
 *
 * @param classifier which should be used for the artifacts (it will be prefixed to source and documentation
 * classifiers, if any).  May be null for no prefix, however note that the default, classifier-less artifacts
 * may be already added by default by the [wemi.Archetype].
 * @param includeSources true to package sources and add them to resulting artifacts under [Repository.M2.SourcesClassifier].
 * Source artifact is obtained through [Configurations.archivingSources].
 * @param includeDocumentation true to package documentation and add it to resulting artifacts under [Repository.M2.JavadocClassifier].
 * Documentation artifact is obtained through [Configurations.archivingDocs].
 */
fun Scope.artifacts(classifier:Classifier?, includeSources:Boolean = true, includeDocumentation:Boolean = true):List<ArtifactEntry> {
    val result = ArrayList<ArtifactEntry>(3)

    val artifact = Keys.archive.get()

    if (artifact != null) {
        result.add(artifact to classifier)
    }

    if (includeSources) {
        val sourceArtifact = using(Configurations.archivingSources) { Keys.archive.get() }
        if (sourceArtifact != null) {
            result.add(sourceArtifact to Repository.M2.joinClassifiers(classifier, Repository.M2.SourcesClassifier))
        }
    }

    if (includeDocumentation) {
        val docsArtifact = using(Configurations.archivingDocs) { Keys.archive.get() }
        if (docsArtifact != null) {
            result.add(docsArtifact to Repository.M2.joinClassifiers(classifier, Repository.M2.JavadocClassifier))
        }
    }

    return result
}