pluginManagement {
    repositories {
        // Aliyun mirrors first: they proxy Google's Maven / Central / the Plugin
        // Portal and stay reachable when dl.google.com is geo-blocked. The original
        // repos remain as fallbacks for when direct access is available.
        maven {
            url = uri("https://maven.aliyun.com/repository/google")
            // KSP lives on Google's upstream Maven, so this proxy answers its HEAD with
            // a 502 (it tries the unreachable dl.google.com) instead of a clean 404. A
            // 502 aborts plugin resolution, so steer KSP to the gradle-plugin mirror.
            content { excludeGroup("com.google.devtools.ksp") }
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/public")
            content { excludeGroup("com.google.devtools.ksp") }
        }
        maven {
            url = uri("https://maven.aliyun.com/repository/gradle-plugin")
            // Aliyun only had KSP's marker pom cached; the jars 502 (proxied from the
            // unreachable Google upstream), which disables the whole repo. KSP is on
            // Maven Central, so keep the entire group off every Aliyun mirror.
            content { excludeGroup("com.google.devtools.ksp") }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("androidx.*")
                // com.google.* minus KSP, which resolves from Maven Central instead.
                includeGroupByRegex("com\\.google.*")
                excludeGroup("com.google.devtools.ksp")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
    }
}

rootProject.name = "AdminApplication"
include(":app")
