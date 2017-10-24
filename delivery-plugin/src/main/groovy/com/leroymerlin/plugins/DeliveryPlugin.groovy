package com.leroymerlin.plugins

import com.leroymerlin.plugins.core.configurators.*
import com.leroymerlin.plugins.tasks.build.DeliveryBuild
import com.leroymerlin.plugins.utils.PropertiesUtils
import org.gradle.api.Action
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.Configuration
import org.gradle.api.artifacts.PublishArtifact
import org.gradle.api.artifacts.dsl.RepositoryHandler
import org.gradle.api.artifacts.maven.MavenPom
import org.gradle.api.internal.artifacts.dsl.DefaultRepositoryHandler
import org.gradle.api.internal.artifacts.mvnsettings.LocalMavenRepositoryLocator
import org.gradle.api.internal.artifacts.mvnsettings.MavenSettingsProvider
import org.gradle.api.internal.file.FileResolver
import org.gradle.api.internal.plugins.DslObject
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.api.plugins.Convention
import org.gradle.api.plugins.MavenPluginConvention
import org.gradle.api.plugins.MavenRepositoryHandlerConvention
import org.gradle.api.publication.maven.internal.DefaultDeployerFactory
import org.gradle.api.publication.maven.internal.DefaultMavenRepositoryHandlerConvention
import org.gradle.api.publication.maven.internal.MavenFactory
import org.gradle.api.tasks.Upload
import org.gradle.internal.Factory
import org.gradle.internal.logging.LoggingManagerInternal

import javax.inject.Inject
import java.util.logging.Logger

class DeliveryPlugin implements Plugin<Project> {

    public static final int COMPILE_PRIORITY = 300
    public static final int RUNTIME_PRIORITY = 200
    public static final int TEST_COMPILE_PRIORITY = 150
    public static final int TEST_RUNTIME_PRIORITY = 100

    public static final String UPLOAD_TASK_PREFIX = 'upload'
    public static final String INSTALL_TASK_PREFIX = 'install'

    public static final String TASK_UPLOAD = 'uploadArtifacts'
    public static final String TASK_INSTALL = 'installArtifacts'

    static final String VERSION_ARG = 'VERSION'
    static final String VERSION_ID_ARG = 'VERSION_ID'
    static final String GROUP_ARG = 'GROUP'
    static final String PROJECT_NAME_ARG = 'PROJECT_NAME'
    static final String TASK_GROUP = 'delivery'
    static final String DELIVERY_CONF_FILE = 'delivery.properties'

    def configurators = [ReactConfigurator, IonicConfigurator, AndroidConfigurator, JavaConfigurator, IOSConfigurator]

    Project project
    DeliveryPluginExtension deliveryExtension

    private final Factory<LoggingManagerInternal> loggingManagerFactory
    private final FileResolver fileResolver
    private final MavenSettingsProvider mavenSettingsProvider
    private final LocalMavenRepositoryLocator mavenRepositoryLocator

    MavenPluginConvention mavenPluginConvention

    @Inject
    DeliveryPlugin(Factory<LoggingManagerInternal> loggingManagerFactory,
                   FileResolver fileResolver,
                   MavenSettingsProvider mavenSettingsProvider,
                   LocalMavenRepositoryLocator mavenRepositoryLocator) {
        this.loggingManagerFactory = loggingManagerFactory
        this.fileResolver = fileResolver
        this.mavenSettingsProvider = mavenSettingsProvider
        this.mavenRepositoryLocator = mavenRepositoryLocator
    }


    void apply(Project project) {
        this.project = project
        project.plugins.apply('maven')
        this.deliveryExtension = project.extensions.create(TASK_GROUP, DeliveryPluginExtension, project, this)


        MavenFactory mavenFactory = project.getServices().get(MavenFactory.class)
        this.mavenPluginConvention = new MavenPluginConvention(project as ProjectInternal, mavenFactory)
        Convention convention = project.getConvention()
        convention.getPlugins().put("maven", mavenPluginConvention)
        DefaultDeployerFactory deployerFactory = new DefaultDeployerFactory(
                mavenFactory,
                loggingManagerFactory,
                fileResolver,
                mavenPluginConvention,
                project.getConfigurations(),
                mavenPluginConvention.getConf2ScopeMappings(),
                mavenSettingsProvider,
                mavenRepositoryLocator)

        project.getTasks().withType(Upload.class, new Action<Upload>() {
            void execute(Upload upload) {
                RepositoryHandler repositories = upload.getRepositories()
                DefaultRepositoryHandler handler = (DefaultRepositoryHandler) repositories
                DefaultMavenRepositoryHandlerConvention repositoryConvention = new DefaultMavenRepositoryHandlerConvention(handler, deployerFactory)
                new DslObject(repositories).getConvention().getPlugins().put("maven", repositoryConvention)
            }
        })

        project.ext.DeliveryBuild = DeliveryBuild

        setupProperties()

        ProjectConfigurator detectedConfigurator = configurators.find {
            configurator ->
                configurator.newInstance().handleProject(project)
        }?.newInstance()
        if (detectedConfigurator == null) {
            detectedConfigurator = [] as ProjectConfigurator
        } else {
            Logger.global.warning("${project.name} configured as ${detectedConfigurator.class.simpleName - "Configurator"} project")
        }
        this.deliveryExtension.configurator = detectedConfigurator

        project.task(TASK_UPLOAD, group: TASK_GROUP)
        project.task(TASK_INSTALL, group: TASK_GROUP)

        project.afterEvaluate {

            if (deliveryExtension.configurator == null) {
                throw new GradleException("Configurator is null. Can't configure your project. Please set the configurator or apply the plugin after your project plugin")
            }
            deliveryExtension.configurator.configure()

            def buildTasks = []
            buildTasks.addAll(project.tasks.withType(DeliveryBuild))

            buildTasks.each {
                task ->
                    String configurationName = task.variantName + "Config"
                    if (!project.configurations.hasProperty(configurationName)) {
                        project.configurations.create(configurationName)
                        project.dependencies.add(configurationName, 'org.apache.maven.wagon:wagon-http:2.2')

                        project.task("${UPLOAD_TASK_PREFIX}${task.variantName.capitalize()}Artifacts", type: Upload, group: TASK_GROUP) {
                            configuration = project.configurations."${configurationName}"
                            repositories deliveryExtension.archiveRepositories
                        }

                        def installTask = project.task("${INSTALL_TASK_PREFIX}${task.variantName.capitalize()}Artifacts", type: Upload, group: TASK_GROUP) {
                            configuration = project.configurations."${configurationName}"
                        }

                        MavenRepositoryHandlerConvention repositories = new DslObject(installTask.getRepositories()).getConvention().getPlugin(MavenRepositoryHandlerConvention.class)
                        def mavenInstaller = repositories.mavenInstaller()
                        MavenPom pom = mavenInstaller.getPom()
                        pom.setArtifactId(task.variantName as String)
                    }
                    ((Configuration) project.configurations."${configurationName}").artifacts.addAll(task.getArtifacts() as PublishArtifact[])
            }

            def uploadArtifacts = project.tasks.findByName(TASK_UPLOAD)
            uploadArtifacts.dependsOn += project.tasks.withType(Upload).findAll { task -> task.name.startsWith(UPLOAD_TASK_PREFIX) }
            if (project.tasks.findByPath("check") != null) {
                uploadArtifacts.dependsOn += project.tasks.findByPath("check")
            }

            project.tasks.findByName(TASK_INSTALL).dependsOn += project.tasks.withType(Upload).findAll { task -> task.name.startsWith(INSTALL_TASK_PREFIX) }
            if (project.tasks.findByPath("install") == null) {
                project.task("install", dependsOn: [TASK_INSTALL])
            }

            //create default release git flow

            if (!deliveryExtension.flowsContainer.hasProperty("releaseGit")) {
                deliveryExtension.flowsContainer.create(
//tag::gitReleaseFlow[]
                        'releaseGit',
                        {
                            def releaseVersion = PropertiesUtils.getSystemProperty("VERSION", (project.version as String) - '-SNAPSHOT')
                            def releaseBranch = "release/${project.versionId}-$releaseVersion"
                            def matcher = releaseVersion =~ /(\d+)([^\d]*$)/
                            def newVersion = PropertiesUtils.getSystemProperty("NEW_VERSION", matcher.replaceAll("${(matcher[0][1] as int) + 1}${matcher[0][2]}")) - "-SNAPSHOT" + "-SNAPSHOT"
                            //Use 'false' value to skip merge to base branch
                            def baseBranch = PropertiesUtils.getSystemProperty("BASE_BRANCH", 'master')
                            def workBranch = PropertiesUtils.getSystemProperty("BRANCH", 'develop')
                            def newVersionId = Integer.parseInt(project.versionId as String) + 1
                            //def propertyFile = getVersionFile()

                            branch workBranch
                            step 'prepareReleaseBranch', "prepare branch $releaseBranch"
                            branch releaseBranch, true
                            step 'prepareVersion', "prepare version"
                            changeProperties releaseVersion
                            //add propertyFile.path
                            step 'generateVersionFiles', "generate version files"
                            step 'commitVersionFiles', "commit version files"
                            commit "chore (version) : Update version to $releaseVersion"
                            step 'build', 'build and archive'
                            build
                            if (baseBranch != 'false') {
                                step 'stepMergeToBaseBranch', 'merge to base branch'
                                branch baseBranch
                                merge releaseBranch
                                push
                            }
                            step 'tagVersion', 'tag the commit'
                            tag "$project.artifact-$project.versionId-$releaseVersion"
                            pushTag
                            step 'updateVersion', "Update version to $newVersionId - $newVersion"
                            branch releaseBranch
                            changeProperties newVersion, newVersionId
                            //add propertyFile.path
                            commit "chore (version) : Update to new version $releaseVersion and versionId $newVersionId"
                            push
                            step 'mergeDevelop', "Merge release branch to $workBranch"
                            branch workBranch
                            merge releaseBranch
                            push
                        }
//end::gitReleaseFlow[]
                )
            }
        }
    }

    void mapToMavenConfiguration(int priority, String fromConfig, String toConfig) {
        if (project.configurations.hasProperty(fromConfig)) {
            this.mavenPluginConvention.conf2ScopeMappings.addMapping(priority, project.configurations.getByName(fromConfig), toConfig)
        }
    }

    void setupProperties() {

        def parents = new ArrayList<Project>()
        parents.add(project)
        def actualParent = project.parent
        while (actualParent != null) {
            parents.add(actualParent)
            actualParent = actualParent.parent
        }
        Collections.reverse(parents)
        Properties versionProperties = new Properties()
        parents.forEach {
            Properties projectProp = PropertiesUtils.readPropertiesFile(it.file("version.properties"))
            projectProp.each {
                if (it.key != null && it.value != null) versionProperties.put(it.key, it.value)
            }
        }

        if (!project.hasProperty('versionIdKey')) {
            project.ext.versionIdKey = 'versionId'
        }
        if (!project.hasProperty('versionKey')) {
            project.ext.versionKey = 'version'
        }
        if (!project.hasProperty('artifactKey')) {
            project.ext.artifactKey = 'artifact'
        }

        PropertiesUtils.overrideVersionProperties(versionProperties, project.file(DELIVERY_CONF_FILE))
        PropertiesUtils.applyPropertiesOnProject(project, versionProperties)

        if (PropertiesUtils.getSystemProperty(VERSION_ID_ARG)) {
            PropertiesUtils.setProperty(versionProperties, project.versionIdKey as String, PropertiesUtils.getSystemProperty(VERSION_ID_ARG))
        } else {
            String versionId = versionProperties.getProperty(project.versionIdKey as String) ?: "2"
            PropertiesUtils.setProperty(versionProperties, project.versionIdKey as String, versionId)
        }

        if (PropertiesUtils.getSystemProperty(VERSION_ARG)) {
            PropertiesUtils.setProperty(versionProperties, project.versionKey as String, PropertiesUtils.getSystemProperty(VERSION_ARG))
        } else {
            String version = versionProperties.getProperty(project.versionKey as String) ?: "1.0.0-SNAPSHOT"
            PropertiesUtils.setProperty(versionProperties, project.versionKey as String, version)
        }

        if (PropertiesUtils.getSystemProperty(PROJECT_NAME_ARG)) {
            PropertiesUtils.setProperty(versionProperties, project.artifactKey as String, PropertiesUtils.getSystemProperty(PROJECT_NAME_ARG))
        } else {
            String artifact = versionProperties.getProperty(project.artifactKey as String) ?: project.name
            PropertiesUtils.setProperty(versionProperties, project.artifactKey as String, artifact)
        }

        if (PropertiesUtils.getSystemProperty(GROUP_ARG)) {
            PropertiesUtils.setProperty(versionProperties, 'group', PropertiesUtils.getSystemProperty(GROUP_ARG))
        }

        //TODO check group + versionID dans versionProperties
        project.ext.versionId = versionProperties.get(project.versionIdKey)
        project.ext.version = versionProperties.get(project.versionKey)
        project.version = versionProperties.get(project.versionKey)
        project.ext.artifact = versionProperties.get(project.artifactKey)
        if (versionProperties.get("group") != null) {
            project.group = versionProperties.get("group")
        }
        deliveryExtension.configurator?.applyProperties()
    }
}
