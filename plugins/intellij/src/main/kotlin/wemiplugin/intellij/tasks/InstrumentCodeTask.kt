package wemiplugin.intellij.tasks

// TODO(jp): Implement

/*
class IntelliJInstrumentCodeTask extends ConventionTask {

	// Set the classes dir to the new one with the instrumented classes
    sourceSet.output.classesDirs.from = instrumentTask.outputDir

	@Internal
	SourceSet sourceSet = project.sourceSets

	@Input
	@Optional
	IdeaDependency ideaDependency = extension.ideaDependency

	Object compilerVersion = {
                def version = extension.version
                if (version && version.endsWith('-SNAPSHOT')) {
                    if (extension.type == 'CL') {
                        return "CLION-$version".toString()
                    }
                    if (extension.type == 'RD') {
                        return "RIDER-$version".toString()
                    }
                    if (extension.type == 'PY' || extension.type == 'PC') {
                        return "PYCHARM-$version".toString()
                    }
                    return version
                }
                return IdeVersion.createIdeVersion(ideaDependency.buildNumber).asStringWithoutProductCode()
            }


}

*/
