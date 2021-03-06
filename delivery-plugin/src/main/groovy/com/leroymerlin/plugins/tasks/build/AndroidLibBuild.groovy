package com.leroymerlin.plugins.tasks.build

import com.leroymerlin.plugins.DeliveryPlugin
import org.gradle.api.Task
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.bundling.Jar

/**
 * Created by alexandre on 15/02/2017.
 */
class AndroidLibBuild extends DeliveryBuild {
    @Input
    void addVariant(variant) {
        String classifier = variant.buildType.name
        //After Android plugin 3.0.0
        try {
            String fileName = "$variantName-${classifier}.aar"
            variant.outputs.all {
                outputFileName = fileName
            }
            outputFiles.put("", project.file("build/outputs/aar/$fileName"))
            // Before Android plugin 3.0.0
        } catch (MissingMethodException ignored) {
            outputFiles.put("", variant.outputs.get(0).outputFile as File)
        }
        def assembleTask = AndroidBuild.getAssembleTask(variant)
        dependsOn.add(assembleTask)
        assembleTask.dependsOn += project.tasks.withType(PrepareBuildTask)

        if (variant.mappingFile) {
            if (!variant.mappingFile.exists()) {
                variant.mappingFile.parentFile.mkdirs()
                variant.mappingFile.createNewFile()
            }
            outputFiles.put("mapping", variant.mappingFile as File)
        }
        variant.sourceSets.each { sourceSet ->
            if (sourceSet.name == "main") {
                def sourcesJar = project.task("sources${variant.name.capitalize()}Jar", type: Jar, group: DeliveryPlugin.TASK_GROUP) {
                    classifier = 'sources'
                    from sourceSet.java.srcDirs
                }
                outputFiles.put("sources-$classifier" as String, sourcesJar.outputs.getFiles().getSingleFile())
                dependsOn.add(sourcesJar)
            }
        }
    }
}
