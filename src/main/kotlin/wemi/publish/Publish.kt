package wemi.publish

import wemi.EvalScope
import wemi.dependency.Classifier
import wemi.dependency.JavadocClassifier
import wemi.dependency.SourcesClassifier
import wemi.dependency.joinClassifiers
import wemi.keys.archive
import wemi.keys.archiveDocs
import wemi.keys.archiveSources
import wemi.keys.publishArtifacts
import java.nio.file.Path

/**
 * Artifact to [Classifier] pairing used in [publishArtifacts].
 */
typealias ArtifactEntry = Pair<Path, Classifier>

/**
 * Collect artifact entries to be added to [publishArtifacts].
 *
 * ### Example:
 * ```
 * Keys.publishArtifacts addAll { using(buildingLegacy) { artifacts("legacy", true, true) } }
 * ```
 *
 * @param classifier which should be used for the artifacts (it will be prefixed to source and documentation
 * classifiers, if any).  May be [wemi.dependency.NoClassifier] for no prefix, however note that the default, classifier-less artifacts
 * may be already added by default by the [wemi.Archetype].
 * @param includeSources true to package sources and add them to resulting artifacts under [SourcesClassifier].
 * @param includeDocumentation true to package documentation and add it to resulting artifacts under [JavadocClassifier].
 */
fun EvalScope.artifacts(classifier: Classifier, includeSources:Boolean = true, includeDocumentation:Boolean = true):List<ArtifactEntry> {
    val result = ArrayList<ArtifactEntry>(3)

    val artifact = archive.get()

    if (artifact != null) {
        result.add(artifact to classifier)
    }

    if (includeSources) {
        val sourceArtifact = archiveSources.get()
        result.add(sourceArtifact to joinClassifiers(classifier, SourcesClassifier))
    }

    if (includeDocumentation) {
        val docsArtifact = archiveDocs.get()
        result.add(docsArtifact to joinClassifiers(classifier, JavadocClassifier))
    }

    return result
}