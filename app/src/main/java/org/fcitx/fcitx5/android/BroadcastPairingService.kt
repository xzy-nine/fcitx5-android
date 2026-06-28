package org.fcitx.fcitx5.android

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.runBlocking
import org.fcitx.fcitx5.android.common.ipc.IBroadcastPairingService
import org.fcitx.fcitx5.android.data.broadcast.BroadcastSecurityManager
import timber.log.Timber

class BroadcastPairingService : Service() {

    companion object {
        private const val TAG = "Fcitx5广播剪切板"
    }

    private fun resolveCallerPackage(claimedPackage: String): Boolean {
        val callingUid = Binder.getCallingUid()
        if (callingUid == android.os.Process.myUid()) return true
        val pkgs = packageManager.getPackagesForUid(callingUid) ?: return false
        return claimedPackage in pkgs
    }

    private val binder = object : IBroadcastPairingService.Stub() {
        override fun requestPairing(
            pairingCode: String,
            packageName: String,
            appName: String
        ): Boolean {
            Timber.tag(TAG).d("收到配对请求: package=$packageName, appName=$appName")
            if (pairingCode.isBlank() || packageName.isBlank()) return false
            if (!resolveCallerPackage(packageName)) {
                Timber.tag(TAG).w("调用方 UID 与声称的包名不匹配: $packageName")
                return false
            }
            return runBlocking {
                BroadcastSecurityManager.verifyPairingCode(pairingCode, packageName, appName)
            }
        }

        override fun revokePairing(packageName: String): Boolean {
            if (packageName.isBlank()) return false
            if (!resolveCallerPackage(packageName)) {
                Timber.tag(TAG).w("撤销配对: 调用方包名不匹配 $packageName")
                return false
            }
            Timber.tag(TAG).d("撤销配对: package=$packageName")
            return runBlocking {
                BroadcastSecurityManager.revokePairing(packageName)
            }
        }

        override fun isAppPaired(packageName: String): Boolean {
            if (packageName.isBlank()) return false
            if (!resolveCallerPackage(packageName)) return false
            return runBlocking {
                BroadcastSecurityManager.isAppPaired(packageName)
            }
        }

        override fun getSharedKey(packageName: String): String? {
            if (packageName.isBlank()) return null
            if (!resolveCallerPackage(packageName)) {
                Timber.tag(TAG).w("获取密钥: 调用方包名不匹配 $packageName")
                return null
            }
            if (!runBlocking { BroadcastSecurityManager.isAppPaired(packageName) }) {
                Timber.tag(TAG).w("获取密钥: $packageName 未配对")
                return null
            }
            return try {
                BroadcastSecurityManager.getSharedKeyForPackage(packageName)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "获取共享密钥失败")
                null
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        Timber.tag(TAG).d("BroadcastPairingService onBind")
        return binder
    }
}
