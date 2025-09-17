package io.github.mihealthamapfix.compat

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.annotation.Keep
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

/**
 * 入口类（LSPosed / Xposed）
 *
 * 做了三件事：
 * 1. 仅在小米运动健康（CN）包内工作（兼容 com.mi.health / com.xiaomi.hm.health）。
 * 2. 延迟到 Application.attach 之后再安装 Hook，保证 ClassLoader 与 Split 已就绪。
 * 3. 两级兜底：
 *    - A) 全局拦截 Context/Activity 的 startActivity*，对 AMap 相关 Intent 做修正。
 *    - B) 监听 ClassLoader#loadClass，当目标类真正加载时再尝试注册 “站内方法” 级 Hook。
 *
 */
@Keep
class XposedEntry : IXposedHookLoadPackage {

    private val TARGET_PACKAGES = setOf("com.mi.health", "com.xiaomi.hm.health")

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName !in TARGET_PACKAGES) return

        logI("命中目标包：${lpparam.packageName}，进程：${lpparam.processName}")

        // 重要：延后到 Application.attach，避免过早查找类/方法导致找不到
        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "attach",
            Context::class.java,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val ctx = param.args[0] as Context
                    val cl = ctx.classLoader
                    logI("Application.attach 完成，ClassLoader=${cl.javaClass.name}")

                    // 输出一行简要的版本信息，方便用户侧回报
                    dumpAppInfoSafe(ctx)

                    // A 方案：无侵入、最高容错 —— 拦截 startActivity*
                    AmapIntentFix.installGlobalStartActivityHooks(cl)

                    // B 方案：仅当你想继续在“站内方法层面”做精细化 Hook 时才启用
                    // 这里演示如何在类被真正加载时再 Hook，可按需实现：
                    // installDeferredInAppHooks(cl)
                }
            }
        )
    }

    /**
     * （可选）示例：当目标类被加载时再尝试注册站内 Hook
     * 你可以把内部逻辑改成：判断类名包含 "amap"/"autonavi" 或你已知的封装类名后，执行具体 Hook。
     */
    private fun installDeferredInAppHooks(appCl: ClassLoader) {
        XposedHelpers.findAndHookMethod(
            ClassLoader::class.java,
            "loadClass",
            String::class.java,
            Boolean::class.javaPrimitiveType,
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val name = param.args[0] as String
                    if (name.contains("amap", true) || name.contains("autonavi", true)) {
                        logI("目标相关类已加载：$name（可在此处注册更精细的方法级 Hook）")
                        // TODO: 在此处调用你项目原有的 “方法级 Hook” 逻辑
                    }
                }
            }
        )
    }

    private fun dumpAppInfoSafe(ctx: Context) {
        try {
            val pm = ctx.packageManager
            val pi = pm.getPackageInfo(ctx.packageName, 0)
            val abi = Build.SUPPORTED_ABIS.joinToString(",")
            logI("App=${pi.packageName} v${pi.versionName}(${pi.longVersionCode}), ABI=$abi, SDK=${Build.VERSION.SDK_INT}")
        } catch (t: Throwable) {
            logE("读取版本信息失败：$t")
        }
    }

    companion object Log {
        fun logI(msg: String) = XposedBridge.log("MiHealthAmapFixCompat[I]: $msg")
        fun logE(msg: String) = XposedBridge.log("MiHealthAmapFixCompat[E]: $msg")
        fun logD(msg: String) = XposedBridge.log("MiHealthAmapFixCompat[D]: $msg")
    }
}
