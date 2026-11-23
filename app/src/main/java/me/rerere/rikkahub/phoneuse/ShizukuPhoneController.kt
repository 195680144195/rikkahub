package me.rerere.rikkahub.phoneuse

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import android.util.Xml
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

/**
 * 用 Shizuku（shell 权限）实现 PhoneController：
 * - 节点树：uiautomator dump
 * - 点击/滑动/输入：input
 * - 截图：screencap
 * - 打开应用：monkey
 *
 * 不依赖无障碍服务，清后台后也不会失效（只要 Shizuku 还在跑）。
 */
class ShizukuPhoneController(private val context: Context) : PhoneController {

    private var lastElements: List<PhoneElement> = emptyList()
    private var lastWidth = 0
    private var lastHeight = 0

    override fun visiblePackages(): Set<String> {
        val text = runCatching {
            exec("dumpsys window windows 2>/dev/null | grep -E 'mCurrentFocus|mFocusedApp' | head -4")
        }.getOrDefault("")
        return Regex("([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)/")
            .findAll(text)
            .map { it.groupValues[1] }
            .toSet()
    }

    override suspend fun captureScreen(): PhoneScreen = withContext(Dispatchers.IO) {
        val path = "/sdcard/.kokona_ui.xml"
        val xml = exec("uiautomator dump $path >/dev/null 2>&1; cat $path 2>/dev/null; rm -f $path")
        if (xml.isBlank()) error("uiautomator dump 失败（界面可能是安全窗口或正在动画）")
        parseScreen(xml)
    }

    override suspend fun screenshot(maxSide: Int): ByteArray = withContext(Dispatchers.IO) {
        val raw = execBytes("screencap -p")
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: error("截图解码失败")
        val longSide = maxOf(bmp.width, bmp.height)
        val scaled = if (longSide > maxSide) {
            val ratio = maxSide.toFloat() / longSide
            Bitmap.createScaledBitmap(
                bmp,
                (bmp.width * ratio).toInt().coerceAtLeast(1),
                (bmp.height * ratio).toInt().coerceAtLeast(1),
                true,
            )
        } else bmp
        ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    override suspend fun gesture(
        fromX: Float, fromY: Float, toX: Float, toY: Float, durationMs: Long,
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val cmd = if (fromX == toX && fromY == toY) {
                "input tap ${fromX.toInt()} ${fromY.toInt()}"
            } else {
                "input swipe ${fromX.toInt()} ${fromY.toInt()} ${toX.toInt()} ${toY.toInt()} ${durationMs.coerceAtLeast(1)}"
            }
            exec(cmd)
            true
        }.getOrDefault(false)
    }

    override suspend fun setText(elementIndex: Int?, text: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            if (elementIndex != null) {
                val el = lastElements.firstOrNull { it.index == elementIndex } ?: return@runCatching false
                exec("input tap ${(el.left + el.right) / 2} ${(el.top + el.bottom) / 2}")
                Thread.sleep(120)
            }
            val escaped = text.replace(" ", "%s").replace("'", "'\\''")
            exec("input text '$escaped'")
            true
        }.getOrDefault(false)
    }

    override suspend fun pressKey(key: PhoneKey): Boolean = withContext(Dispatchers.IO) {
        val code = when (key) {
            PhoneKey.BACK -> 4
            PhoneKey.HOME -> 3
            PhoneKey.RECENTS -> 187
            PhoneKey.NOTIFICATIONS -> 83
            PhoneKey.ENTER -> 66
        }
        runCatching { exec("input keyevent $code"); true }.getOrDefault(false)
    }

    override fun listApps(): List<PhoneApp> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { PhoneApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
    }

    override fun openApp(packageName: String): Boolean = runCatching {
        exec("monkey -p $packageName -c android.intent.category.LAUNCHER 1 2>&1")
        true
    }.getOrDefault(false)

    // ---------------- shell ----------------

    private fun runShell(cmd: String): ByteArray {
        val service = IShizukuService.Stub.asInterface(Shizuku.getBinder())
        val process = service.newProcess(arrayOf("sh", "-c", cmd), null, null)
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(process.inputStream).use { it.readBytes() }
        runCatching { process.waitFor() }
        runCatching { process.destroy() }
        return bytes
    }

    private fun exec(cmd: String): String = runShell(cmd).toString(Charsets.UTF_8)

    private fun execBytes(cmd: String): ByteArray = runShell(cmd)

    // ---------------- uiautomator XML ----------------

    private fun parseScreen(xml: String): PhoneScreen {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        var event = parser.eventType
        val elements = mutableListOf<PhoneElement>()
        var pkg: String? = null
        val packages = linkedSetOf<String>()
        var width = 0
        var height = 0
        var index = 0

        while (event != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (event == org.xmlpull.v1.XmlPullParser.START_TAG && parser.name == "node") {
                val bounds = parser.getAttributeValue(null, "bounds") ?: ""
                val m = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]").find(bounds)
                val l = m?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val t = m?.groupValues?.get(2)?.toIntOrNull() ?: 0
                val r = m?.groupValues?.get(3)?.toIntOrNull() ?: 0
                val b = m?.groupValues?.get(4)?.toIntOrNull() ?: 0
                width = maxOf(width, r)
                height = maxOf(height, b)

                val nodePkg = parser.getAttributeValue(null, "package")
                if (nodePkg != null) {
                    pkg = pkg ?: nodePkg
                    packages += nodePkg
                }
                val text = parser.getAttributeValue(null, "text").orEmpty()
                val desc = parser.getAttributeValue(null, "content-desc").orEmpty()
                val label = text.ifBlank { desc }
                val cls = parser.getAttributeValue(null, "class").orEmpty()
                val resId = parser.getAttributeValue(null, "resource-id").orEmpty().substringAfterLast('/')
                val clickable = parser.getAttributeValue(null, "clickable") == "true"
                val scrollable = parser.getAttributeValue(null, "scrollable") == "true"
                val focused = parser.getAttributeValue(null, "focused") == "true"
                val editable = cls.contains("EditText") || parser.getAttributeValue(null, "editable") == "true"
                // uiautomator 给所有节点都写 checked=false，只有 checkable 才有意义
                val checkable = parser.getAttributeValue(null, "checkable") == "true"
                val checked = if (!checkable) null else when (parser.getAttributeValue(null, "checked")) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
                if (label.isNotBlank() || clickable || editable || scrollable || focused) {
                    elements += PhoneElement(
                        index = index++,
                        className = cls.substringAfterLast('.'),
                        label = label,
                        resourceId = resId.ifBlank { null },
                        left = l, top = t, right = r, bottom = b,
                        clickable = clickable,
                        editable = editable,
                        scrollable = scrollable,
                        checked = checked,
                        focused = focused,
                    )
                }
            }
            event = parser.next()
        }

        lastElements = elements
        lastWidth = width
        lastHeight = height
        val keyboardVisible = packages.any { it.contains("inputmethod", ignoreCase = true) }
        return PhoneScreen(
            packageName = pkg,
            appLabel = pkg?.let { p ->
                runCatching {
                    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(p, 0)).toString()
                }.getOrNull()
            },
            packages = packages,
            width = width,
            height = height,
            keyboardVisible = keyboardVisible,
            elements = elements,
            truncated = false,
        )
    }

    companion object {
        fun isAvailable(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

        fun hasPermission(): Boolean = runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

        /** 申请授权并等待结果：授权成功返回 true，拒绝/超时返回 false */
        suspend fun ensurePermission(context: Context, timeoutMs: Long = 10_000): Boolean {
            if (!isAvailable()) return false
            if (hasPermission()) return true
            val deferred = CompletableDeferred<Boolean>()
            val listener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
                if (!deferred.isCompleted) {
                    deferred.complete(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            return try {
                requestPermission(context)
                withTimeoutOrNull(timeoutMs) { deferred.await() } ?: false
            } finally {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
        }

        fun requestPermission(context: Context) {
            runCatching {
                context.startActivity(
                    Intent(context, ShizukuPermissionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
