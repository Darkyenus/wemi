package com.darkyen.wemi.intellij.util

import com.intellij.openapi.projectRoots.*
import com.intellij.openapi.projectRoots.impl.SdkVersionUtil
import com.intellij.pom.java.LanguageLevel
import org.jetbrains.kotlin.idea.util.projectStructure.version
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

const val MIN_JAVA_VERSION_FOR_WEMI = 8
val MIN_JAVA_SDK_VERSION_FOR_WEMI = JavaSdkVersion.JDK_1_8
const val MAX_JAVA_VERSION_FOR_WEMI_HINT = 13

private fun getJavaSdkVersion(sdk:Sdk):JavaSdkVersion? {
	val jdkType = sdk.sdkType as? JavaSdkType ?: return null
	if (jdkType is JavaSdk) {
		jdkType.getVersion(sdk)?.let { return it }
	}

	val jdkJavaVersion = SdkVersionUtil.getJdkVersionInfo(sdk.homePath ?: return null)?.version ?: return null
	return JavaSdkVersion.fromJavaVersion(jdkJavaVersion)
}

fun getJavaExecutable(sdk:Sdk):Path? {
	val jdkType = sdk.sdkType as? JavaSdkType
	if (jdkType != null) {
		jdkType.getVMExecutablePath(sdk)?.let { return Paths.get(it) }
		jdkType.getBinPath(sdk)?.let { bin ->
			findExistingFileFromAlternatives(Paths.get(bin), "java", "java.exe")?.let { return it }
		}
	}

	return getJavaExecutable(sdk.homePath)
}

fun getJavaExecutable(jreHomeOrBin:String?):Path? {
	return findExistingFileFromAlternatives(Paths.get(jreHomeOrBin ?: return null),
		"bin/java", "bin/java.exe", "java", "java.exe", "jre/bin/java", "jre/bin/java.exe")
}

/** Get list of SDKs under which Wemi can run. */
fun getWemiCompatibleSdkList(): List<Sdk> {
	return ProjectJdkTable.getInstance().allJdks.filter { sdk ->
		val javaVersion = getJavaSdkVersion(sdk) ?: return@filter false
		sdk.homePath != null && javaVersion.isAtLeast(MIN_JAVA_SDK_VERSION_FOR_WEMI)
	} // Intentionally allowing JREs
}

fun getWemiCompatibleSdk(version:JavaSdkVersion? = null):Sdk? {
	val list = ArrayList<Sdk>(getWemiCompatibleSdkList())
	list.sortBy { it.version?.ordinal ?: -1 }

	val targetVersion = version ?: JavaSdkVersion.fromLanguageLevel(LanguageLevel.HIGHEST)

	// Find first version that is same or smallest larger. If no such version, return the most recent version.
	return list.find { it.version?.isAtLeast(targetVersion) ?: false } ?: list.lastOrNull()
}

fun createWemiCompatibleSdk(javaHome: Path):Sdk {
	var home = javaHome
	var jdk = JdkUtil.checkForJdk(home.toString())

	// Sometimes JRE is inside JDK
	if (!jdk && JdkUtil.checkForJdk(home.parent.toString())) {
		home = home.parent
		jdk = true
	}

	val sdk = JavaSdk.getInstance().createJdk(home.normalize().fileName.toString(), home.toString(), jdk)
	ProjectJdkTable.getInstance().addJdk(sdk)
	return sdk
}


private fun findExistingFileFromAlternatives(base:Path, vararg alternatives:String): Path? {
	for (s in alternatives) {
		val f = base.resolve(s)
		if (Files.exists(f)) {
			return f.toAbsolutePath()
		}
	}
	return null
}