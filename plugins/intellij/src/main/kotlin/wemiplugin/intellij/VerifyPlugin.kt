package wemiplugin.intellij

/**
 *
 */
/*
    private static void configureRunPluginVerifierTask(@NotNull Project project, @NotNull IntelliJPluginExtension extension) {
        Utils.info(project, "Configuring run plugin verifier task")
        project.tasks.create(RUN_PLUGIN_VERIFIER_TASK_NAME, RunPluginVerifierTask).with {
            group = GROUP_NAME
            description = "Runs the IntelliJ Plugin Verifier tool to check the binary compatibility with specified IntelliJ IDE builds."
            conventionMapping('failureLevel', { EnumSet.of(RunPluginVerifierTask.FailureLevel.INVALID_PLUGIN) })
            conventionMapping('ideVersions', { Arrays.asList("${extension.type}-${extension.version}") })
            conventionMapping('verifierVersion', { VERIFIER_VERSION_LATEST })
            conventionMapping('distributionFile', { resolveDistributionFile(project) })
            conventionMapping('verificationReportsDirectory', { "${project.buildDir}/reports/pluginVerifier".toString() })
            conventionMapping('downloadDirectory', { ideDownloadDirectory().toString() })
            dependsOn { project.getTasksByName(BUILD_PLUGIN_TASK_NAME, false) }
            dependsOn { project.getTasksByName(VERIFY_PLUGIN_TASK_NAME, false) }
        }
    }
 */