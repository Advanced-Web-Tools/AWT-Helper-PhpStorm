import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.intellij.platform")
}
kotlin { jvmToolchain(17) }
dependencies {
    testImplementation(libs.junit)
    intellijPlatform {
        phpstorm("2024.1")
        bundledPlugin("com.jetbrains.php")
        testFramework(TestFrameworkType.Bundled)
        pluginVerifier()
    }
}
intellijPlatform {
    instrumentCode = false
    pluginConfiguration {
        name = "AWT Helper"
        ideaVersion { sinceBuild = "241"; untilBuild = provider { null } }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.PhpStorm, "2024.1")
            create(IntelliJPlatformType.PhpStorm, "2025.3")
        }
    }
}
tasks.test {
    systemProperty("java.awt.headless", "true")
    systemProperty("idea.load.plugins.id", "com.awt.AWTHelper,com.jetbrains.php")
    providers.gradleProperty("awtPhp").orNull?.let { systemProperty("awt.php", it) }
    providers.gradleProperty("awtFixtures").orNull?.let { systemProperty("awt.fixtures", it) }
}
