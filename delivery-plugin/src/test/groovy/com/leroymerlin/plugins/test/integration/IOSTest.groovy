package com.leroymerlin.plugins.test.integration

import groovy.io.FileType
import org.junit.Assert
import org.junit.Test

/**
 * Created by alexandre on 17/12/15.
 */
class IOSTest extends AbstractIntegrationTest {

    @Override
    String getProjectName() {
        return "ios"
    }

    @Test
    void testBuildTaskGeneration() {
        def archiveDirectory = new File(workingDirectory, "build/archive_ipa")
        applyExtraGradle('''
delivery{
    archiveRepositories = {
        maven {
            url uri("''' + archiveDirectory.absolutePath + '''")
        }
    }
    flows{
        xcodeBuild{
            build
        }
    }

if(file("${System.properties['user.home']}/.gradle/signing_ios.properties").exists()){
    signingProperties {
        releaseBis {
            target = "deliveryBis"
            scheme = "delivery"
            propertiesFile = file("${System.properties['user.home']}/.gradle/signing_ios.properties")
        }

        release {
            target = "delivery"
            scheme = "delivery"
            propertiesFile = file("${System.properties['user.home']}/.gradle/signing_ios.properties")
        }
    }
}
}
''')
        testTask('xcodeBuildFlow')
        def list = []
        archiveDirectory.eachFileRecurse(FileType.FILES, {
            f ->
                list << f
        })

        for (file in list) {
            if (!file.path.contains("$archiveDirectory/com/leroymerlin/delivery/ios/1.0.0-SNAPSHOT/ios-app-deliverybis-1.0.0-SNAPSHOT-delivery")
                    && !file.path.contains("$archiveDirectory/com/leroymerlin/delivery/ios/1.0.0-SNAPSHOT/ios-app-delivery-1.0.0-SNAPSHOT-delivery"))
                throw new AssertionError("${file.name} has not a correct name or a correct path")
        }

        Assert.assertEquals("archive folder should contain 4 files", 4, list.size())
    }
}