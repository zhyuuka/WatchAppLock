package com.watchapplock

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * 枚举可启动的已安装第三方应用，供设置页选择锁定目标。
 *
 * 仅返回带 LAUNCHER Intent 的应用，过滤掉系统核心与本应用。
 */
object AppListProvider {

    private const val TAG = "AppListProvider"

    data class AppInfo(val pkg: String, val label: String, val isSystem: Boolean)

    fun list(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val flags = PackageManager.GET_META_DATA
        val installed = try {
            pm.getInstalledApplications(flags)
        } catch (t: Throwable) {
            Log.w(TAG, "getInstalledApplications failed: ${t.message}")
            return emptyList()
        }

        val out = ArrayList<AppInfo>(installed.size)
        for (ai in installed) {
            val pkg = ai.packageName
            if (pkg == context.packageName) continue
            val launch = pm.getLaunchIntentForPackage(pkg)
            if (launch == null) continue   // 仅可启动应用
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val label = try { pm.getApplicationLabel(ai).toString() } catch (_: Throwable) { pkg }
            out.add(AppInfo(pkg, label, isSystem))
        }
        out.sortBy { it.label.lowercase() }
        return out
    }

    /** 转成 JSON 数组供 JsBridge 返回。 */
    fun toJson(list: List<AppInfo>): String {
        val arr = JSONArray()
        for (a in list) {
            arr.put(JSONObject().apply {
                put("pkg", a.pkg)
                put("label", a.label)
                put("system", a.isSystem)
            })
        }
        return arr.toString()
    }
}
