/* SPDX-License-Identifier: LGPL-2.1-or-later */
package org.fcitx.fcitx5.android.ui.main.settings.behavior

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.runtime.remember
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.*
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.data.UserDataRestoreRuntime
import org.fcitx.fcitx5.android.ui.main.MainActivity
import org.fcitx.fcitx5.android.ui.main.compose.screens.UserDataRestoreScreen

/** Recovery can style resources, but must never read preferences being replaced by the transaction. */
internal fun restoreUiContext(context: Context): Context = ContextThemeWrapper(context, R.style.Theme_FcitxSettings_System)

/**
 * 数据恢复的隔离宿主。
 *
 * 恢复事务期间不读取偏好、不初始化剪贴板数据库与原生引擎；界面由 Compose 渲染，
 * 主题固定为系统样式。
 */
class UserDataRestoreActivity : ComponentActivity() {
    private val themed by lazy { restoreUiContext(this) }
    private val ui = MainScope()
    private var report = ""
    private var working = true
    private var success = false
    private val restoreBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = Unit
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        onBackPressedDispatcher.addCallback(this, restoreBackCallback)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        val dark = resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        setContent {
            val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                UserDataRestoreScreen(
                    report = report,
                    working = working,
                    success = success,
                    onRestart = ::restart,
                    onRetry = {
                        startActivity(
                            Intent(this, UserDataRestoreActivity::class.java)
                                .putExtra("restore_error", report),
                        )
                        finish()
                    },
                    onSaveDiagnostics = {
                        startActivityForResult(
                            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TITLE, "rime-restore-recovery.txt")
                            },
                            100,
                        )
                    },
                    onDismiss = { finish() },
                )
            }
        }
        val token = intent.getStringExtra("restore_token")
        val pid = intent.getIntExtra("restore_main_pid", -1)
        val application = applicationContext
        val key = token ?: (intent.getStringExtra("recovery_run") ?: java.util.UUID.randomUUID().toString().also {
            intent.putExtra("recovery_run", it)
        })
        val operation = synchronized(companionLock) {
            if (active != null && (activeToken == key || active?.isActive == true)) checkNotNull(active)
            else {
                check(active?.isActive != true) { "A restore operation is already running" }
                background.async {
                    if (token != null) UserDataRestoreRuntime.execute(application, token, pid)
                    else try {
                        UserDataRestoreRuntime.retryRecovery(application)
                    } catch (e: Exception) {
                        UserDataRestoreRuntime.Outcome(false, e.toString())
                    }
                }.also { active = it; activeToken = key }
            }
        }
        ui.launch {
            val result = try {
                operation.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                UserDataRestoreRuntime.Outcome(false, e.toString())
            }
            working = false
            success = result.success
            restoreBackCallback.isEnabled = false
            report = listOfNotNull(intent.getStringExtra("restore_error"), result.message)
                .joinToString("\n\n").take(64000)
            // 触发重组以显示最终状态
            setContent {
                val controller = remember { ThemeController(ColorSchemeMode.System) }
            MiuixTheme(controller = controller) {
                    UserDataRestoreScreen(
                        report = report,
                        working = working,
                        success = success,
                        onRestart = ::restart,
                        onRetry = {
                            startActivity(
                                Intent(this@UserDataRestoreActivity, UserDataRestoreActivity::class.java)
                                    .putExtra("restore_error", report),
                            )
                            finish()
                        },
                        onSaveDiagnostics = {
                            startActivityForResult(
                                Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                                    addCategory(Intent.CATEGORY_OPENABLE)
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TITLE, "rime-restore-recovery.txt")
                                },
                                100,
                            )
                        },
                        onDismiss = { finish() },
                    )
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 100 && resultCode == RESULT_OK && data?.data != null) {
            val uri = data.data!!
            ui.launch {
                try {
                    withContext(Dispatchers.IO) {
                        (contentResolver.openOutputStream(uri)
                            ?: throw java.io.IOException("Cannot open diagnostics document"))
                            .use { it.write(report.toByteArray()) }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    report += "\n\n${e}"
                }
            }
        }
    }

    private fun restart() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }

    override fun onDestroy() {
        ui.cancel()
        super.onDestroy()
    }

    companion object {
        private val companionLock = Any()
        private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var activeToken: String? = null
        private var active: Deferred<UserDataRestoreRuntime.Outcome>? = null
    }
}
