import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.intellij.platform")
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("pluginIdeVersion"))
        testFramework(TestFrameworkType.Platform)
    }

    implementation("com.github.mwiede:jsch:0.2.24")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            // current() 复用已在 intellijPlatform 里解析好的 IDE，不额外下载相邻版本
            current()
        }
    }
}

tasks {
    withType<JavaCompile>().configureEach {
        // 源码含中文注释，Windows 默认 GBK 会导致乱码甚至编译失败
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }
}
