import org.slf4j.LoggerFactory
import wemi.run.system
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

/**
 * Gather and return the project version according to the current git commit and tags.
 */
fun gatherProjectVersion():String {
	return versionAccordingToGit() ?: "dev-${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}"
}

private val SYSTEM_LOG = LoggerFactory.getLogger("system")

fun lastGitCommit():String? {
	return system("git", "rev-list", "--max-count=1", "master", timeoutMs = 60_000) { code, _ -> SYSTEM_LOG.warn("Could not get the latest commit ({})", code); null }
}

fun versionAccordingToGit():String? {
	val lastWemiVersionTag = system("git", "describe", "--tags", "--match=*.*", "--abbrev=0", timeoutMs = 60_000) { code, _ -> SYSTEM_LOG.warn("Could not find Wemi version ({})", code); null } ?: return null
	val lastVersionCommit = system("git", "rev-list", "--max-count=1", lastWemiVersionTag, timeoutMs = 60_000) { code, _ -> SYSTEM_LOG.warn("Could not get version_commit ({})", code); null } ?: return null
	val latestCommit = lastGitCommit() ?: return null

	val matcher = Pattern.compile("([0-9]+)\\.([0-9]+).*").matcher(lastWemiVersionTag)
	if (!matcher.matches()) {
		SYSTEM_LOG.warn("Wemi version tag does not match the expected pattern: $lastWemiVersionTag")
		return null
	}
	val lastWemiVersionMajor = matcher.group(1)
	val lastWemiVersionMinor = matcher.group(2)

	return if(lastVersionCommit == latestCommit) {
		// We are at a release commit
		"$lastWemiVersionMajor.$lastWemiVersionMinor"
	} else {
		// We are at a snapshot commit for next dev version
		"$lastWemiVersionMajor.${lastWemiVersionMinor.toInt() + 1}-SNAPSHOT"
	}
}