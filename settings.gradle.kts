// eink 模块独立构建（eink/lib 分支）——宿主形态与本文件无关：
// 作为子模块嵌入宿主时，宿主自己的 settings.gradle 生效，本文件被忽略。
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 阿里云镜像优先：本机直连 dl.google.com 偶发读超时（与主仓同款
        // 网络环境，镜像为完整代理，不影响产物一致性）
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
    // 模块自有版本目录挂载（与宿主嵌入形态同构，见
    // modules/eink/src/main/java/io/legado/app/eink/contract/EINK-PORTING.md §2 步骤 3）
    versionCatalogs {
        create("einkLibs") {
            from(files("modules/eink/gradle/libs.versions.toml"))
        }
    }
}
rootProject.name = "eink-lib"

include(":modules:eink")
