/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: 2026 Fcitx5 for Android Contributors
 *
 * custom: 小米随手写系统引擎桥（反射）。
 *
 * 小米设备把触控笔手写引擎放在系统 jar 里：
 * `/system_ext/framework/xiaomi-pencilengine-pad.jar`，公开类
 * `com.miui.penengine.impl.*`（小米定制版输入法也是用 `PathClassLoader` 反射加载它，
 * 见搜狗 `com/xiaomi/handwriting/engine/j/c.java`、讯飞 `app/o56.java`）。
 * 这里同样只做**反射调用**，不搬运任何厂商代码，也不在编译期依赖该 jar。
 *
 * 能力（与 AOSP 协议对接）：
 * - [recognizeGesture] → 标准 `android.view.inputmethod.HandwritingGesture`（手势判定）
 * - [recognizeText] → 整段墨迹的识别文本（单结果，无候选列表）
 *
 * 引擎可用性由系统设置 `support_native_handwriting` 决定（小米定制版输入法也是这么探测的）；
 * 不可用时所有方法返回 null / false，调用方回落到本项目自带的 ONNX 管线。
 */
package org.fcitx.fcitx5.android.data.handwriting

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.inputmethod.HandwritingGesture
import dalvik.system.PathClassLoader
import timber.log.Timber
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object XiaomiHandwritingEngine {

    private const val TAG = "XiaomiHandwritingEngine"

    /** 引擎 jar 路径（小米定制版输入法用的同一份；pad 与手机共用）。 */
    private const val ENGINE_JAR = "/system_ext/framework/xiaomi-pencilengine-pad.jar"

    /** 系统设置开关：小米定制版输入法用它探测本机是否支持原生手写。 */
    private const val SETTING_NATIVE_HANDWRITING = "support_native_handwriting"

    private const val CLASS_RECOGNIZE = "com.miui.penengine.impl.algorithm.recognizelib.algorithm.RecognizeFacade"
    private const val CLASS_GESTURE = "com.miui.penengine.impl.algorithm.gesture.GestureFacade"
    private const val CLASS_INK = "com.miui.penengine.impl.data.Ink"
    private const val CLASS_INK_STROKE = "com.miui.penengine.impl.data.Ink\$Stroke"
    private const val CLASS_INK_POINT = "com.miui.penengine.impl.data.Ink\$Point"
    private const val CLASS_PENCIL_ENGINE_MANAGER = "com.miui.penengine.impl.manager.PencilEngineManager"

    /** 逐点构造时使用的 action（等价 `MotionEvent.ACTION_MOVE`）。 */
    private const val POINT_ACTION_MOVE = 2

    // ---- 反射句柄（一次性解析，失败即整体不可用） ----

    @Volatile
    private var resolved = false

    @Volatile
    private var available = false

    /** 包名白名单判定缓存（null = 尚未判定）。 */
    @Volatile
    private var cachedWhitelist: Boolean? = null

    /**
     * 持有引擎 jar 的 [PathClassLoader]。
     *
     * 必须常驻引用：反射得到的方法/构造器都绑在该 loader 加载的类上，
     * 若 loader 被 GC，后续 `invoke` 会失败。
     */
    @Suppress("unused")
    private var loader: ClassLoader? = null

    private var inkBuilderCtor: Constructor<*>? = null
    private var inkBuilderAddStroke: Method? = null
    private var inkBuilderBuild: Method? = null

    private var strokeBuilderCtor: Constructor<*>? = null
    private var strokeBuilderAddPoint: Method? = null
    private var strokeBuilderBuild: Method? = null

    /** `Ink$Point.obtain` 的各重载（按优先级：4 参带时间戳 → 3 参 → 2 参）。 */
    private var pointObtainLong: Method? = null
    private var pointObtainInt: Method? = null
    private var pointObtainPlain: Method? = null

    private val pointObtainVariants: List<Pair<Method?, (StrokePoint) -> Array<Any>>> get() = listOf(
        pointObtainLong to { p: StrokePoint -> argsOf(p, 4) },
        pointObtainInt to { p: StrokePoint -> argsOf(p, 3) },
        pointObtainPlain to { p: StrokePoint -> argsOf(p, 2) },
    )

    private var recognizeCtor: Constructor<*>? = null
    private var recognizeText: Method? = null
    private var recognizeDestroy: Method? = null

    private var gestureCtor: Constructor<*>? = null
    private var gestureGetGoogle: Method? = null
    private var gestureDestroy: Method? = null

    private var recognizer: Any? = null
    private var gesturer: Any? = null

    /** `PencilEngineManager.whitelistResult`（private static boolean）：包名白名单结果。 */
    private var whitelistResultField: java.lang.reflect.Field? = null

    /** `PencilEngineManager.authResult`（private static boolean）：XMS 认证结果（同置 true 更稳）。 */
    private var authResultField: java.lang.reflect.Field? = null

    /**
     * 本应用包名是否在引擎的**授权白名单**内（仅用于诊断/日志）。
     *
     * 引擎的白名单是硬编码字符串数组（`EnableAuthData.WHITE_LIST`，12 个包名），
     * 不在名单内时 facade 能构造成功但每次调用都返回空。我们通过 [applyWhitelistBypass]
     * 让引擎放行，因此这里的结果**不影响可用性**，只用于日志说明走了哪条路。
     *
     * 判定方式是读 jar 原始字节查是否含本包名（jar 仅 190KB，一次性、结果缓存）。
     *
     * @return true = 本包名在名单内；null = 无法判定（读不到 jar）
     */
    fun isPackageWhitelisted(context: Context): Boolean? {
        ensureResolved()
        val pkg = context.packageName ?: return null
        cachedWhitelist?.let { return it }
        val hit = runCatching {
            val bytes = java.io.File(ENGINE_JAR).readBytes()
            containsAscii(bytes, pkg)
        }.getOrNull() ?: return null
        cachedWhitelist = hit
        return hit
    }

    /** 在字节数组里查找 ASCII 子串（dex 的字符串常量区是明文）。 */
    private fun containsAscii(haystack: ByteArray, needle: String): Boolean {
        val n = needle.length
        if (n == 0 || haystack.size < n) return false
        val target = needle.toByteArray(Charsets.US_ASCII)
        var i = 0
        val limit = haystack.size - n
        while (i <= limit) {
            if (haystack[i] == target[0]) {
                var j = 1
                while (j < n && haystack[i + j] == target[j]) j++
                if (j == n) return true
            }
            i++
        }
        return false
    }

    /**
     * 绕过引擎的**包名白名单**。
     *
     * 引擎在识别/手势入口都有一道闸：
     * ```java
     * // RecognizeFacade.recognizeText() / GestureFacade.getGoogleGesture()
     * if (!PencilEngineManager.getAuthResult() || ...) return null;
     *
     * // PencilEngineManager.getAuthResult()
     * if (XMSAuthConnect.getInstance() == null) return whitelistResult;   // ← 我们走这条
     * return authResult;
     * ```
     * `XMSAuthConnect.sInstance` 只在 `pencilEngineInit()` 里赋值，而**全 jar 内无人调用
     * `pencilEngineInit()`**（它由 Estimate/Shape 的使用方从外部调），因此本场景下
     * `getInstance()` 恒为 null、判定完全落在 `whitelistResult` 上。
     * 该字段是 **private static boolean**，由 `initWhitelist(context)` 按包名写入
     * （`EnableAuthData.isEnableAuth`，硬编码 12 个包名）。
     *
     * 这里把 `whitelistResult` 与 `authResult` **两个 static 标志都置 true**，
     * 从而**只绕过包名这一道**：
     * - 不触碰 XMS 在线认证（那条路我们本来就不走；同置 `authResult` 只是防将来有组件
     *   初始化了 XMS 后 `getAuthResult()` 改读它）；
     * - 不改 `isEnableProduct()`（走 `HyperOSCustFeatureResolve`，设备侧已开启）；
     * - 不修改任何引擎文件、不改本应用包名。
     *
     * 必须在**每次构造 facade 之后**调用：两个 facade 的构造器都会调 `initWhitelist()`
     * 重新写入 `whitelistResult`，会把我们的值覆盖掉。
     *
     * @return 是否成功放行
     */
    private fun applyWhitelistBypass(): Boolean {
        val wl = whitelistResultField
        val ar = authResultField
        if (wl == null && ar == null) return false
        return runCatching {
            // 两个 static 标志都置 true：
            // - getInstance()==null 时读 whitelistResult（我们的常态）
            // - 万一将来有组件初始化了 XMS，getAuthResult() 会改读 authResult
            wl?.setBoolean(null, true)
            ar?.setBoolean(null, true)
            true
        }.getOrElse {
            Timber.w(it, "$TAG: whitelist bypass failed")
            false
        }
    }

    /**
     * 引擎是否可用：系统设置已开启 + jar 与所需类/方法全部解析成功。
     *
     * 不做耗时探测，纯读设置 + 一次性反射解析（结果缓存）。
     */
    fun isAvailable(context: Context): Boolean {
        ensureResolved()
        if (!available) return false
        return runCatching {
            Settings.Secure.getInt(context.contentResolver, SETTING_NATIVE_HANDWRITING, 0) == 1
        }.getOrDefault(false)
    }

    /**
     * 文字识别能力是否就绪（jar 中有 `RecognizeFacade` 且系统开关已开）。
     *
     * 与 [isAvailable] 区分：两类 facade 可能只装了一个（实测不同机型/版本不一），
     * 只装手势时不应把「文字识别」也报成可用（否则会静默回落到 ONNX，看起来像「没调用到」）。
     */
    fun isTextRecognitionAvailable(context: Context): Boolean =
        isAvailable(context) && recognizeCtor != null

    /** 手势识别能力是否就绪（jar 中有 `GestureFacade` 且系统开关已开）。 */
    fun isGestureAvailable(context: Context): Boolean =
        isAvailable(context) && gestureCtor != null

    /** 引擎是否已初始化（[open] 成功）。 */
    val isOpen: Boolean get() = recognizer != null || gesturer != null

    /**
     * 初始化引擎（幂等）。
     *
     * 两个 facade（文字识别 / 手势）**各自独立尝试**：任一可用即算引擎可用，
     * 单个失败不影响另一个（实测两类能力可能只装了一个）。
     *
     * @return 是否**文字识别**可用（调用方最常需要它来决定是否跳过 ONNX 加载）；
     *         手势能力单独用 [isGestureAvailable] 判断
     */
    fun open(context: Context): Boolean {
        if (!isAvailable(context)) return false
        if (isOpen) return recognizeCtor != null
        recognizer = runCatching { recognizeCtor?.newInstance(context) }
            .onFailure { Timber.w(it, "$TAG: RecognizeFacade init failed") }
            .getOrNull()
        gesturer = runCatching { gestureCtor?.newInstance(context) }
            .onFailure { Timber.w(it, "$TAG: GestureFacade init failed") }
            .getOrNull()
        // 必须在 facade 构造之后：两者的构造器都会调 initWhitelist() 覆盖该字段
        val bypassed = applyWhitelistBypass()
        val textOk = recognizer != null
        Timber.i(
            "$TAG: open => text=%b, gesture=%b, whitelistBypassed=%b " +
                    "(whitelisted=%b, ctors: text=%b gesture=%b)",
            textOk, gesturer != null, bypassed,
            isPackageWhitelisted(context), recognizeCtor != null, gestureCtor != null,
        )
        if (recognizer == null && gesturer == null) close()
        return textOk
    }

    /** 释放引擎（幂等；不抛异常）。 */
    fun close() {
        runCatching { recognizer?.let { recognizeDestroy?.invoke(it) } }
        runCatching { gesturer?.let { gestureDestroy?.invoke(it) } }
        recognizer = null
        gesturer = null
    }

    /**
     * 手势识别：单笔 → 标准 `HandwritingGesture`（AOSP 类型，可直接交编辑器执行）。
     *
     * @return 未识别/引擎不可用时 null
     */
    fun recognizeGesture(stroke: List<StrokePoint>): HandwritingGesture? {
        val engine = gesturer ?: return null
        val method = gestureGetGoogle ?: return null
        return try {
            val nativeStroke = buildStroke(stroke) ?: return null
            method.invoke(engine, nativeStroke) as HandwritingGesture?
        } catch (t: Throwable) {
            Timber.e(t, "$TAG: recognizeGesture failed")
            null
        }
    }

    /**
     * 文字识别：整段墨迹 → 识别文本。
     *
     * 系统引擎只给**单结果**（无 top-k 候选），因此调用方把它当作首选字使用。
     *
     * @return 未识别/引擎不可用时 null
     */
    fun recognizeText(strokes: List<List<StrokePoint>>): String? {
        val engine = recognizer ?: return null
        val method = recognizeText ?: return null
        if (strokes.isEmpty()) return null
        return try {
            val ink = buildInk(strokes) ?: return null
            (method.invoke(engine, ink) as? String)?.takeIf { it.isNotEmpty() }
        } catch (t: Throwable) {
            Timber.e(t, "$TAG: recognizeText failed")
            null
        }
    }

    // ------------------------------------------------------------------
    // Ink / Stroke / Point 构造
    // ------------------------------------------------------------------

    private fun buildStroke(points: List<StrokePoint>): Any? {
        val builderCtor = strokeBuilderCtor ?: return null
        val addPoint = strokeBuilderAddPoint ?: return null
        val build = strokeBuilderBuild ?: return null
        val builder = builderCtor.newInstance()
        for (p in points) {
            val point = obtainPoint(p) ?: return null
            addPoint.invoke(builder, point)
        }
        return build.invoke(builder)
    }

    /** 整段墨迹（多笔）→ 引擎的 Ink；[recognizeText] 用。 */
    private fun buildInk(strokes: List<List<StrokePoint>>): Any? {
        val builderCtor = inkBuilderCtor ?: return null
        val addStroke = inkBuilderAddStroke ?: return null
        val build = inkBuilderBuild ?: return null
        val builder = builderCtor.newInstance()
        for (s in strokes) {
            val stroke = buildStroke(s) ?: return null
            addStroke.invoke(builder, stroke)
        }
        return build.invoke(builder)
    }

    /**
     * 逐点构造引擎的 `Ink$Point`。
     *
     * 按可用性依次尝试引擎的**原生重载**（全部只依赖基本类型，不需要合成 MotionEvent）：
     * `obtain(float,float,int,long)`（带时间戳，最优）→ `obtain(float,float,int)`
     * → `obtain(float,float)`。取不到任何重载则整条链路回落自有实现。
     */
    private fun obtainPoint(p: StrokePoint): Any? {
        for ((method, args) in pointObtainVariants) {
            val m = method ?: continue
            runCatching { m.invoke(null, *args(p)) }.getOrNull()?.let { return it }
        }
        return null
    }

    /** 点构造重载的参数（与各重载签名一一对应）。 */
    private fun argsOf(p: StrokePoint, arity: Int): Array<Any> = when (arity) {
        4 -> arrayOf(p.x, p.y, POINT_ACTION_MOVE, p.timeMs)
        3 -> arrayOf(p.x, p.y, POINT_ACTION_MOVE)
        else -> arrayOf(p.x, p.y)
    }

    // ------------------------------------------------------------------
    // 反射解析
    // ------------------------------------------------------------------

    /**
     * 放开访问检查。
     *
     * 引擎 jar 里的实现类/成员是包内可见或 `default`，只 `getDeclaredX` 不 `isAccessible`
     * 会在 `invoke`/`newInstance` 时抛 `IllegalAccessException`（解析期看不出问题）。
     */
    private fun <T : java.lang.reflect.AccessibleObject> T.accessible(): T {
        isAccessible = true
        return this
    }

    private fun ensureResolved() {
        if (resolved) return
        synchronized(this) {
            if (resolved) return
            resolved = true
            try {
                val cl = PathClassLoader(ENGINE_JAR, javaClass.classLoader)
                loader = cl

                val inkCls = cl.loadClass(CLASS_INK)
                val strokeCls = cl.loadClass(CLASS_INK_STROKE)
                val pointCls = cl.loadClass(CLASS_INK_POINT)

                val inkBuilderCls = cl.loadClass("$CLASS_INK\$Builder")
                inkBuilderCtor = inkBuilderCls.getDeclaredConstructor().accessible()
                inkBuilderAddStroke = inkBuilderCls.getDeclaredMethod("addStroke", strokeCls).accessible()
                inkBuilderBuild = inkBuilderCls.getDeclaredMethod("build").accessible()

                val strokeBuilderCls = cl.loadClass("$CLASS_INK_STROKE\$Builder")
                strokeBuilderCtor = strokeBuilderCls.getDeclaredConstructor().accessible()
                strokeBuilderAddPoint = strokeBuilderCls.getDeclaredMethod("addPoint", pointCls).accessible()
                strokeBuilderBuild = strokeBuilderCls.getDeclaredMethod("build").accessible()

                // 点构造：只依赖基本类型的原生重载（无需合成 MotionEvent）
                val floatCls = Float::class.javaPrimitiveType
                val intCls = Int::class.javaPrimitiveType
                val longCls = Long::class.javaPrimitiveType
                pointObtainLong = runCatching {
                    pointCls.getDeclaredMethod("obtain", floatCls, floatCls, intCls, longCls).accessible()
                }.getOrNull()
                pointObtainInt = runCatching {
                    pointCls.getDeclaredMethod("obtain", floatCls, floatCls, intCls).accessible()
                }.getOrNull()
                pointObtainPlain = runCatching {
                    pointCls.getDeclaredMethod("obtain", floatCls, floatCls).accessible()
                }.getOrNull()

                runCatching {
                    val recognizeCls = cl.loadClass(CLASS_RECOGNIZE)
                    recognizeCtor = recognizeCls.getDeclaredConstructor(Context::class.java).accessible()
                    recognizeText = recognizeCls.getDeclaredMethod("recognizeText", inkCls).accessible()
                    recognizeDestroy = runCatching {
                        recognizeCls.getDeclaredMethod("onDestroy").accessible()
                    }.getOrNull()
                }.onFailure { Timber.w(it, "$TAG: RecognizeFacade unavailable") }

                runCatching {
                    val gestureCls = cl.loadClass(CLASS_GESTURE)
                    gestureCtor = gestureCls.getDeclaredConstructor(Context::class.java).accessible()
                    gestureGetGoogle = gestureCls.getDeclaredMethod("getGoogleGesture", strokeCls).accessible()
                    gestureDestroy = runCatching {
                        gestureCls.getDeclaredMethod("destroy").accessible()
                    }.getOrNull()
                }.onFailure { Timber.w(it, "$TAG: GestureFacade unavailable") }

                // 包名白名单 / 认证标志放行用的字段（见 applyWhitelistBypass）
                runCatching {
                    val managerCls = cl.loadClass(CLASS_PENCIL_ENGINE_MANAGER)
                    whitelistResultField = runCatching {
                        managerCls.getDeclaredField("whitelistResult").accessible()
                    }.getOrNull()
                    authResultField = runCatching {
                        managerCls.getDeclaredField("authResult").accessible()
                    }.getOrNull()
                    if (whitelistResultField == null && authResultField == null) {
                        Timber.w("$TAG: neither whitelistResult nor authResult field found")
                    }
                }.onFailure { Timber.w(it, "$TAG: whitelist field unavailable") }

                // 可用性：至少有一个识别 facade，且点构造至少有一条路径可用
                val pointOk = pointObtainLong != null || pointObtainInt != null || pointObtainPlain != null
                available = (recognizeCtor != null || gestureCtor != null) && pointOk
                Timber.i(
                    "$TAG: resolved available=%b recognize=%b gesture=%b point=%s",
                    available, recognizeCtor != null, gestureCtor != null,
                    when {
                        pointObtainLong != null -> "4-arg"
                        pointObtainInt != null -> "3-arg"
                        pointObtainPlain != null -> "2-arg"
                        else -> "none"
                    },
                )
            } catch (t: Throwable) {
                // jar 不存在（非小米设备）是正常情况，不刷错误日志
                Timber.i("$TAG: engine not present (${t.javaClass.simpleName}: ${t.message})")
                available = false
            }
        }
    }

    /** 诊断：引擎探测状态（设置页/日志用）。 */
    fun status(context: Context): String {
        ensureResolved()
        val setting = runCatching {
            Settings.Secure.getInt(context.contentResolver, SETTING_NATIVE_HANDWRITING, -1)
        }.getOrDefault(-1)
        return "jar=${if (available) "ok" else "missing"}, " +
                "setting=$SETTING_NATIVE_HANDWRITING:$setting, " +
                "sdk=${Build.VERSION.SDK_INT}"
    }
}
