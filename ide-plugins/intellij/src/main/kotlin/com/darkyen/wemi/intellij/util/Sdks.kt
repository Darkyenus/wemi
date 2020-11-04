package com.darkyen.wemi.intellij.util

import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.JavaSdkVersion
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.pom.java.LanguageLevel
import org.jetbrains.kotlin.idea.util.projectStructure.version

const val MIN_JAVA_VERSION_FOR_WEMI = 8
val MIN_JAVA_SDK_VERSION_FOR_WEMI = JavaSdkVersion.JDK_1_8
const val MAX_JAVA_VERSION_FOR_WEMI_HINT = 13

val SDK_TYPE_FOR_WEMI: JavaSdk
		get() = JavaSdk.getInstance()

/** Get list of SDKs under which Wemi can run. */
fun getWemiCompatibleSdkList(): List<Sdk> {
	val sdkTypeForWemi = SDK_TYPE_FOR_WEMI
	return ProjectJdkTable.getInstance().getSdksOfType(sdkTypeForWemi)
			.filter { sdkTypeForWemi.isOfVersionOrHigher(it, MIN_JAVA_SDK_VERSION_FOR_WEMI) && it.homePath != null } // Intentionally allowing JREs
}

fun getWemiCompatibleSdk(version:JavaSdkVersion? = null):Sdk? {
	val list = ArrayList<Sdk>(getWemiCompatibleSdkList())
	list.sortBy { it.version?.ordinal ?: -1 }

	val targetVersion = version ?: JavaSdkVersion.fromLanguageLevel(LanguageLevel.HIGHEST)

	// Find first version that is same or smallest larger. If no such version, return the most recent version.
	return list.find { it.version?.isAtLeast(targetVersion) ?: false } ?: list.lastOrNull()
}