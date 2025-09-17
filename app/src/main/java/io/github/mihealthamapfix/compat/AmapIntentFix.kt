package io.github.mihealthamapfix.compat

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.Keep
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * AMap 意图修正器（全局拦截 Context/Activity 的 startActivity*）
 *
 * 目标：当小米运动健康尝试跳转高德地图时，确保：
 * 1) Intent 被显式指向 "com.autonavi.minimap"（避免系统路由到其他处理者或因未设置 package 而不稳定）；
 * 2) 当使用高德私有 Scheme（androidamap:// 或 amapuri://）时，确保数据完整，不做无谓拦截；
 * 3) 不影响非高德相关的跳转。
 *
 * 说明：选择拦截调用点（startActivity）而非应用内被混淆的方法名，可以最大化兼容不同渠道/混淆/分包差异。
 */
@Keep
object AmapIntentFix {

    private const val AMAP_PACKAGE = "com.autonavi.minimap"
    private val AMAP_SCHEMES = setOf("androidamap", "amapuri")

    fun installGlobalStartActivityHooks(appCl: ClassLoader) {
        // 1) 拦截 ContextWrapper.startActivity(Intent) / startActivity(Intent, Bundle)
        hookMethod(
            "android.content.ContextWrapper",
            "startActivity",
            arrayOf(Intent::class.java),
        )
        hookMethod(
            "android.content.ContextWrapper",
            "startActivity",
            arrayOf(Intent::class.java, Bundle::class.java),
        )

        // 2) 再拦截 Activity.startActivity*（有些路径会直接走 Activity 实现）
        hookMethod(
            "android.app.Activity",
            "startActivity",
            arrayOf(Intent::class.java),
        )
        hookMethod(
            "android.app.Activity",
            "startActivity",
            arrayOf(Intent::class.java, Bundle::class.java),
        )
    }

    private fun hookMethod(className: String, method: String, paramTypes: Array<Class<*>>) {
        try {
            XposedHelpers.findAndHookMethod(
                className,
                null /* BootClassLoader */,
                method,
                *paramTypes,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val intent = param.args[0] as? Intent ?: return
                        val fixed = maybeFixAmapIntent(intent)
                        if (fixed) {
                            XposedBridge.log("MiHealthAmapFixCompat[I]: 已修正 AMap 跳转 -> $intent")
                        }
                    }
                }
            )
            XposedBridge.log("MiHealthAmapFixCompat[D]: 已安装钩子 $className#$method(${paramTypes.joinToString { it.simpleName }})")
        } catch (t: Throwable) {
            XposedBridge.log("MiHealthAmapFixCompat[E]: 安装钩子失败 $className#$method ：$t")
        }
    }

    /**
     * 如判定为高德地图的跳转，则做“最小必要修改”：
     * - 若未显式设置 package，则强制 setPackage(com.autonavi.minimap)
     * - 若 Component 设置到其他包，也改回高德（避免被 ROM/三方浏览器劫持）
     *
     * 返回：是否进行了修改（用于打印日志）
     */
    private fun maybeFixAmapIntent(intent: Intent): Boolean {
        val data: Uri? = intent.data
        val isAmapByScheme = data?.scheme?.lowercase() in AMAP_SCHEMES
        val isAmapByPkg = intent.`package` == AMAP_PACKAGE
        val isAmapByComponent = intent.component?.packageName == AMAP_PACKAGE

        val looksLikeAmap = isAmapByScheme || isAmapByPkg || isAmapByComponent

        if (!looksLikeAmap) return false

        var changed = false

        if (intent.`package` == null) {
            intent.`package` = AMAP_PACKAGE
            changed = true
        }

        val comp: ComponentName? = intent.component
        if (comp != null && comp.packageName != AMAP_PACKAGE) {
            // 不强行指定具体 Activity，仅修正包名，交由系统在目标包内解析
            intent.component = ComponentName(AMAP_PACKAGE, comp.className)
            changed = true
        }

        return changed
    }
}
