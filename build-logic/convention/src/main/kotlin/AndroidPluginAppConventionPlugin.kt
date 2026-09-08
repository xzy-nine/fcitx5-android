/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Register `assemble${Variant}Plugins` task for root project,
 * and make all plugins' `assemble${Variant}` depends on it
 */
class AndroidPluginAppConventionPlugin : Plugin<Project> {

    override fun apply(target: Project) {
        target.extensions.configure<ApplicationExtension> {
            buildFeatures {
                buildConfig = true
            }
            buildTypes {
                release {
                    buildConfigField("String", "MAIN_APPLICATION_ID", "\"org.fcitx.fcitx5.android\"")
                    addManifestPlaceholders(
                        mapOf(
                            "mainApplicationId" to "org.fcitx.fcitx5.android",
                        )
                    )
                }
                debug {
                    // custom: 主应用不再有 .debug 后缀，插件的 IPC 权限/action 必须与其保持一致
                    buildConfigField("String", "MAIN_APPLICATION_ID", "\"org.fcitx.fcitx5.android\"")
                    addManifestPlaceholders(
                        mapOf(
                            "mainApplicationId" to "org.fcitx.fcitx5.android",
                        )
                    )
                }
            }
        }
        target.extensions.configure<ApplicationAndroidComponentsExtension> {
            onVariants { variant ->
                val variantName = variant.name.capitalized()
                target.afterEvaluate {
                    val pluginsTaskName = "assemble${variantName}Plugins"
                    val pluginsTask = target.rootProject.tasks.findByName(pluginsTaskName)
                        ?: target.rootProject.tasks.register(pluginsTaskName).get()
                    pluginsTask?.dependsOn(target.tasks.getByName("assemble${variantName}"))
                }
            }
        }
    }

}
