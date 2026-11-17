/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.broadcast.BroadcastSecurityManager
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.core.engine.RimeEngineProbeService
import org.fcitx.fcitx5.android.data.BundledPresets
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.handwriting.GoogleDigitalInkEngine
import org.fcitx.fcitx5.android.data.handwriting.HandwritingLegacyCleanup
import org.fcitx.fcitx5.android.data.handwriting.MlKitBundledModel
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.AppPrefsMigration
import org.fcitx.fcitx5.android.data.prefs.SettingsUiPreferences
import org.fcitx.fcitx5.android.data.prefs.SmartDefaultInitializer
import org.fcitx.fcitx5.android.data.prefs.SplitKeyboardStateManager
import org.fcitx.fcitx5.android.data.theme.IconThemeManager
import org.fcitx.fcitx5.android.data.theme.ThemeManager
import org.fcitx.fcitx5.android.sync.webdav.AutoDictSync
import org.fcitx.fcitx5.android.input.config.DirectBootLayoutSync
import org.fcitx.fcitx5.android.input.config.UserConfigFiles
import org.fcitx.fcitx5.android.input.font.FontProviders
import org.fcitx.fcitx5.android.utils.AppUtil
import org.fcitx.fcitx5.android.utils.Locales
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.startActivity
import org.fcitx.fcitx5.android.utils.userManager
import timber.log.Timber
import kotlin.system.exitProcess

class FcitxApplication : Application(), androidx.work.Configuration.Provider {

    private var restoreProcess = false
    private var engineProbeProcess = false
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // The imported-engine probe is a disposable isolated-UID process. It only loads the
        // packaged JNI bridge and copies the explicitly passed read-only descriptor into its own
        // sealed memfd; normal preferences, recovery and data initialization must not run there.
        engineProbeProcess = RimeEngineProbeService.isProbeProcess(base)
        if (engineProbeProcess) return
        restoreProcess = org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.isRestoreProcess(base)
        if (!restoreProcess) try {
            org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.recoverBeforeInitialization(base)
        } catch (e: Exception) {
            runCatching { org.fcitx.fcitx5.android.data.UserDataRestoreRuntime.launchRecovery(base, e) }
            throw e
        }
        // custom: 内置谷歌数字墨水模型物化（assets → 应用私有目录）。
        // 必须早于 ContentProvider：ML Kit 的模型已下载状态存在 shared_prefs，
        // 其 MlKitInitProvider 在 Application.onCreate 之前运行。
        // `:asr` 独立进程不涉及手写，跳过以免多进程同时写同一份 shared_prefs。
        if (!engineProbeProcess && !getProcessName().endsWith(ASR_PROCESS_SUFFIX)) {
            MlKitBundledModel.materializeIfNeeded(base)
        }
    }

    override val workManagerConfiguration: androidx.work.Configuration
        get() = androidx.work.Configuration.Builder().setMinimumLoggingLevel(android.util.Log.WARN).build()

    val coroutineScope = MainScope() + CoroutineName("FcitxApplication")

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SHUTDOWN) return
            Timber.d("Device shutting down, closing the input engine...")
            try {
                FcitxDaemon.shutdownFcitx()
            } catch (e: Exception) {
                Timber.e(e, "Failed to close the input engine during device shutdown")
            }
        }
    }

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_UNLOCKED) return
            if (!isDirectBootMode) return
            Timber.d("Device unlocked, app will exit now and restart to normal mode")
            FcitxDaemon.getFirstConnectionOrNull()?.also {
                // try to shutdown fcitx gracefully
                FcitxDaemon.stopFcitx()
            }
            AppUtil.exit()
        }
    }

    private val restartFcitxInstanceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_RESTART_FCITX_INSTANCE) return
            if (FcitxDaemon.getFirstConnectionOrNull() != null) {
                Timber.i("Received broadcast '${intent.action}', try to restart fcitx instance ...")
                FcitxDaemon.restartFcitx()
            } else {
                Timber.i("Received broadcast '${intent.action}', but there's no fcitx instance")
            }
        }
    }

    var isDirectBootMode = false
        private set

    internal var directBootLayoutSync: DirectBootLayoutSync? = null
        private set

    val directBootAwareContext: Context
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && isDirectBootMode) {
            createDeviceProtectedStorageContext()
        } else {
            applicationContext
        }


    override fun onCreate() {
        super.onCreate()
        // custom: 离线语音识别跑在独立 `:asr` 进程（见 AsrInferenceService / AndroidManifest）。
        // 该进程只需要能 dlopen 原生库并读模型文件，绝不能初始化剪贴板 Room 数据库、
        // WebDAV 自动同步、主题/广播/偏好等主进程子系统 —— 否则会出现多进程同时打开同一个
        // Room 库、以及在 `:asr` 里凭空启动网络同步等严重问题。
        if (getProcessName().endsWith(ASR_PROCESS_SUFFIX)) {
            instance = this
            return
        }
        if (engineProbeProcess) return
        if (restoreProcess) { instance = this; return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !userManager.isUserUnlocked) {
            isDirectBootMode = true
            registerReceiver(unlockReceiver, IntentFilter(Intent.ACTION_USER_UNLOCKED))
            // Unlock can finish between the first check and receiver registration.
            // Choose normal storage now if its broadcast has already been missed.
            if (userManager.isUserUnlocked) {
                isDirectBootMode = false
                unregisterReceiver(unlockReceiver)
            }
        }
        val ctx = directBootAwareContext

        if (!BuildConfig.DEBUG) {
            Thread.setDefaultUncaughtExceptionHandler { _, e ->
                val crashTime = System.currentTimeMillis()
                val sharedPreferences = PreferenceManager.getDefaultSharedPreferences(ctx)
                val lastCrashTimePrefKey = "last_crash_time"
                val lastCrashTime = sharedPreferences.getLong(lastCrashTimePrefKey, -1L)
                // make sure it was written to persistent storage
                sharedPreferences.edit(commit = true) {
                    putLong(lastCrashTimePrefKey, crashTime)
                }
                if (crashTime - lastCrashTime <= 10_000L) {
                    // continuous crashes within 10 seconds, maybe in a crash loop. just bail
                    exitProcess(10)
                }
                val truncated = e.stackTraceToString().let {
                    if (it.length > MAX_STACKTRACE_SIZE) it.take(MAX_STACKTRACE_SIZE) + "<truncated>" else it
                }
                org.fcitx.fcitx5.android.ui.main.log.CrashReportStore(ctx.noBackupFilesDir).record(
                    "--------- Persisted app crash\nRecorded at (Unix ms): $crashTime\n" +
                        "Crash build: ${BuildConfig.BUILD_GIT_HASH}\nCrash process: ${Process.myPid()}\n$truncated\n"
                )
                // 崩溃日志页已由 Compose 承载，调用栈经路由参数传入
                startActivity<org.fcitx.fcitx5.android.ui.main.MainActivity> {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    action = Intent.ACTION_RUN
                    putExtra(
                        org.fcitx.fcitx5.android.ui.main.MainActivity.EXTRA_COMPOSE_ROUTE,
                        "CrashLog",
                    )
                    putExtra(
                        org.fcitx.fcitx5.android.ui.main.MainActivity.EXTRA_COMPOSE_ROUTE_ARG,
                        truncated,
                    )
                }
                exitProcess(10)
            }
        }

        instance = this
        // we don't have AppPrefs available yet
        val sharedPrefs = PreferenceManager.getDefaultSharedPreferences(ctx)
        Timber.setupForest(verbose = sharedPrefs.getBoolean("verbose_log", false))

        Timber.d("isDirectBootMode=$isDirectBootMode")

        // custom: 后台预热 kotlin-reflect，见 ReflectionPrewarm
        org.fcitx.fcitx5.android.input.ReflectionPrewarm.prewarmAsync()

        // custom: 提前在后台同步数据目录，见 DataSyncPreloader
        org.fcitx.fcitx5.android.core.data.DataSyncPreloader.start()

        AppPrefs.init(sharedPrefs)
        AppPrefsMigration.apply(sharedPrefs)
        SplitKeyboardStateManager.init(this)
        val appPrefs = AppPrefs.getInstance()
        SettingsUiPreferences.initialize(
            ctx,
            hadInitializedSettings = appPrefs.internal.settingsInitialized.getValue() ||
                !appPrefs.internal.firstRun.getValue()
        )
        // Smart settings initialization (on first install)
        SmartDefaultInitializer.initialize(this, appPrefs)
        // record last pid for crash logs
        AppPrefs.getInstance().internal.pid.apply {
            val currentPid = Process.myPid()
            lastPid = getValue()
            Timber.d("Last pid is $lastPid. Set it to current pid: $currentPid")
            setValue(currentPid)
        }
        ClipboardManager.init(ctx)
        BroadcastSecurityManager.init(ctx)
        if (BroadcastSecurityManager.isEnabled()) {
            BroadcastSecurityManager.startListening()
        }
        val themeInitialization = ThemeManager.init(resources.configuration)
        // 安装内置键盘布局/主题/图标主题（幂等，后台执行，开箱即用）
        if (!isDirectBootMode) {
            UserConfigFiles.directBootTextKeyboardLayoutJson()?.let { destination ->
                directBootLayoutSync = DirectBootLayoutSync(this, appPrefs.keyboard.textKeyboardLayoutProfile, destination)
            }
            BundledPresets.installAsync {
                IconThemeManager.refreshAsync()
            }
            // Custom resources live in credential-protected external storage. The unlock
            // receiver restarts this process before loading them with normal preferences.
            // Preload off-main so the first keyboard can reuse the cached fonts and icons.
            FontProviders.preloadFontsAsync()
            IconThemeManager.initAsync()
        }
        Locales.onLocaleChange(resources.configuration)
        registerReceiver(shutdownReceiver, IntentFilter(Intent.ACTION_SHUTDOWN))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !isDirectBootMode) {
            coroutineScope.launch {
                themeInitialization?.join()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    AppPrefs.getInstance().syncToDeviceEncryptedStorage()
                    ThemeManager.syncToDeviceEncryptedStorage()
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            restartFcitxInstanceReceiver,
            IntentFilter(ACTION_RESTART_FCITX_INSTANCE),
            PERMISSION_TEST_INPUT_METHOD,
            null,
            ContextCompat.RECEIVER_EXPORTED
        )
        if (!isDirectBootMode) {
            AutoDictSync.startAutoSync()
        }
        coroutineScope.launch {
            // custom: 清理已废弃引擎的模型目录
            val removed = withContext(Dispatchers.IO) {
                HandwritingLegacyCleanup.removeLegacyOnnxModels(ctx)
            }
            // custom: 内置数字墨水模型诊断（一条日志，确认包内模型被 ML Kit 认作已下载）
            Timber.i(
                "digital ink model: bundled=%b, materialized=%b, mlkitDownloaded=%b, legacyRemoved=%d",
                MlKitBundledModel.isBundled(ctx),
                MlKitBundledModel.isMaterialized(ctx),
                GoogleDigitalInkEngine.isModelDownloaded(ctx),
                removed,
            )
        }
    }

    override fun onTerminate() {
        directBootLayoutSync?.close()
        super.onTerminate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (restoreProcess || engineProbeProcess) return
        ThemeManager.onSystemPlatteChange(newConfig)
        Locales.onLocaleChange(newConfig)
    }

    companion object {
        private var lastPid: Int? = null
        private var instance: FcitxApplication? = null
        fun getInstance() =
            instance ?: throw IllegalStateException("FcitxApplication has not been created!")

        fun getLastPid() = lastPid
        private const val MAX_STACKTRACE_SIZE = 128000

        const val ACTION_RESTART_FCITX_INSTANCE =
            "${BuildConfig.APPLICATION_ID}.action.RESTART_FCITX_INSTANCE"

        /** custom: 离线语音识别独立进程的后缀（与 AndroidManifest 中 android:process 一致）。 */
        private const val ASR_PROCESS_SUFFIX = ":asr"

        /**
         * This permission is requested by com.android.shell, makes it possible to restart
         * fcitx instance from `adb shell am` command:
         * ```sh
         * adb shell am broadcast -a org.fcitx.fcitx5.android.action.RESTART_FCITX_INSTANCE
         * ```
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-7.0.0_r1/packages/Shell/AndroidManifest.xml#67
         *
         * other candidate: android.permission.TEST_INPUT_METHOD requires Android 14
         * https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-14.0.0_r1/packages/Shell/AndroidManifest.xml#628
         */
        const val PERMISSION_TEST_INPUT_METHOD = "android.permission.READ_INPUT_STATE"
    }
}
