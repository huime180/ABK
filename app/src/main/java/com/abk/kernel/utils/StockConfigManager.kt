package com.abk.kernel.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*

/**
 * Extracts and manages device-specific stock kernel configs.
 *
 * Two extraction methods:
 * 1. From /proc/config.gz (running kernel)
 * 2. From a user-selected boot.img file via magiskboot
 *
 * Both methods auto-push the extracted config to the user's fork repo
 * at config/stock_config/<deviceId>_stock_config.
 */
object StockConfigManager {

    private const val TAG = "StockConfigManager"
    const val STOCK_CONFIG_DIR = "config/stock_config"
    const val STOCK_CONFIG_SUFFIX = "_stock_config"

    val KNOWN_DEVICES = mapOf(
        "CPH2581" to "vermeer",        "CPH2573" to "vermeer",
        "PJD110"  to "vermeer",        "CPH2583" to "vermeer",
        "CPH2557" to "salami",         "CPH2451" to "salami",
        "CPH2449" to "salami",         "CPH2609" to "aston",
        "CPH2413" to "lemonade",       "CPH2417" to "lemonade",
        "NE2210"  to "lemonade",
        "husky"   to "husky",          "shiba"   to "shiba",
        "akita"   to "akita",          "felix"   to "felix",
        "cheetah" to "cheetah",        "panther" to "panther",
        "lynx"    to "lynx",           "oriole"  to "oriole",
        "raven"   to "raven",
        "beyond2q" to "beyond2q",      "beyondxq" to "beyondxq",
        "alioth"  to "alioth",         "sweet"   to "sweet",
        "beryllium" to "beryllium",
    )

    data class DeviceInfo(
        val model: String,
        val manufacturer: String,
        val product: String,
        val device: String,
        val board: String,
        val fingerprint: String
    ) {
        val codename: String
            get() = listOf(device, product, model.lowercase().replace(" ", "_"))
                .first { it.isNotBlank() }.trim()
        val configId: String
            get() = KNOWN_DEVICES[model] ?: KNOWN_DEVICES[codename] ?: codename
        val displayName: String
            get() = if (KNOWN_DEVICES.containsKey(model)) "$model ($configId)"
                    else "$model"
    }

    data class StockConfigResult(
        val success: Boolean,
        val deviceInfo: DeviceInfo,
        val configPath: String?,
        val output: List<String>,
        val source: String = "",
        val pushed: Boolean = false
    ) {
    }

    fun detectDevice(): DeviceInfo {
        val model = Build.MODEL.trim()
        val manufacturer = Build.MANUFACTURER.trim()
        val product = Build.PRODUCT.trim()
        val device = Build.DEVICE.trim()
        val board = Build.BOARD.trim()
        val fingerprint = Build.FINGERPRINT.trim()
        return DeviceInfo(model, manufacturer, product, device, board, fingerprint)
    }

    // ── Method 1: from /proc/config ─────────────────────────────────────

    fun extractFromProcConfig(
        context: Context,
        configId: String? = null,
        onOutput: ((String) -> Unit)? = null
    ): StockConfigResult {
        val deviceInfo = detectDevice()
        val targetId = configId?.trim()?.takeIf { it.isNotBlank() } ?: deviceInfo.configId
        val output = mutableListOf<String>()
        fun log(m: String) { output.add(m); onOutput?.invoke(m); Log.d(TAG, m) }

        val configDir = File(context.filesDir, STOCK_CONFIG_DIR); configDir.mkdirs()
        val destFile = File(configDir, "$targetId$STOCK_CONFIG_SUFFIX")

        try {
            log("从 /proc/config 提取 $targetId …")
            val dest = sq(destFile.absolutePath)
            val script = buildString {
                appendLine("echo '# Stock Kernel Config' > $dest")
                appendLine("echo '# Device: ${deviceInfo.model}' >> $dest")
                appendLine("echo '# Config ID: $targetId' >> $dest")
                appendLine("echo '# Source: /proc/config.gz' >> $dest")
                appendLine("echo '' >> $dest")
                appendLine("if [ -f /proc/config.gz ] && zcat /proc/config.gz > /dev/null 2>&1; then")
                appendLine("  zcat /proc/config.gz 2>/dev/null | grep '^CONFIG_' >> $dest || true")
                appendLine("elif [ -f /proc/config ] && grep -q '^CONFIG_' /proc/config 2>/dev/null; then")
                appendLine("  grep '^CONFIG_' /proc/config >> $dest")
                appendLine("else")
                appendLine("  echo '# (empty)' > $dest && exit 0")
                appendLine("fi")
            }
            val result = RootUtils.execRootCommandForWebUi(script, timeoutSeconds = 30L)
            output.addAll(result.output.filter { it.isNotBlank() })
            if (!destFile.isFile || !destFile.readText().contains("CONFIG_")) {
                tryNoRootProcConfig(destFile, deviceInfo, targetId, output)
            }
            val ok = destFile.isFile && destFile.readText().contains("CONFIG_")
            if (ok) log("提取成功: ${destFile.absolutePath}")
            return StockConfigResult(ok, deviceInfo, destFile.takeIf { ok }?.absolutePath, output)
        } catch (e: Exception) {
            Log.e(TAG, "proc extract failed", e)
            return StockConfigResult(false, deviceInfo, null, output + (e.message ?: "失败"))
        }
    }

    private fun tryNoRootProcConfig(dest: File, info: DeviceInfo, targetId: String, output: MutableList<String>) {
        try {
            val lines = when {
                File("/proc/config.gz").canRead() -> {
                    java.util.zip.GZIPInputStream(File("/proc/config.gz").inputStream()).bufferedReader().readLines()
                }
                File("/proc/config").canRead() -> File("/proc/config").readLines()
                else -> return
            }
            val configLines = lines.filter { it.trim().startsWith("CONFIG_") }
            if (configLines.isNotEmpty()) {
                dest.bufferedWriter().use { w ->
                    w.appendLine("# Stock Kernel Config for $targetId")
                    w.appendLine("# Device: ${info.model}")
                    w.appendLine()
                    configLines.forEach(w::appendLine)
                }
                output.add("无 root 方式读取成功，${configLines.size} 项")
            }
        } catch (_: Exception) {}
    }

    // ── Method 2: from boot.img ─────────────────────────────────────────

    fun extractFromBootImage(
        context: Context,
        bootImagePath: String,
        configId: String? = null,
        onOutput: ((String) -> Unit)? = null
    ): StockConfigResult {
        val deviceInfo = detectDevice()
        val targetId = configId?.trim()?.takeIf { it.isNotBlank() } ?: deviceInfo.configId
        val output = mutableListOf<String>()
        fun log(m: String) { output.add(m); onOutput?.invoke(m); Log.d(TAG, m) }

        // 时间戳独立 workDir，避免与 /proc/config 提取残留混淆
        val workDir = File(context.cacheDir, "stock-bootimg-${System.currentTimeMillis()}")
        workDir.mkdirs()
        val configDir = File(context.filesDir, STOCK_CONFIG_DIR); configDir.mkdirs()

        try {
            log("从 boot.img 提取 $targetId …")
            val magiskboot = listOf(
                File("/data/adb/ksud/bin/magiskboot"),
                File("/data/local/tmp/magiskboot"),
                File(context.applicationInfo.nativeLibraryDir, "libmagiskboot.so"),
            ).firstOrNull { it.isFile && it.canRead() }
            if (magiskboot == null) {
                log("未找到 magiskboot (需要 libmagiskboot.so 或 /data/adb/ksud/bin/magiskboot)")
                workDir.deleteRecursively()
                return StockConfigResult(false, deviceInfo, null, output, "bootimg")
            }

            // 复制到 workDir 内执行，规避 SELinux/Playland 限制
            val localMb = File(workDir, "magiskboot")
            magiskboot.copyTo(localMb, overwrite = true)
            localMb.setExecutable(true)

            val bootImg = File(workDir, "boot.img")
            val srcP = sq(bootImagePath)
            val dstP = sq(bootImg.absolutePath)
            val cpSc = buildString {
                appendLine("[ -f $srcP ] || exit 2")
                appendLine("cp $srcP $dstP 2>/dev/null || cat $srcP > $dstP")
                appendLine("chmod 644 $dstP")
            }
            RootUtils.execRootCommandForWebUi(cpSc, timeoutSeconds = 30L)

            if (!bootImg.isFile || bootImg.length() == 0L) {
                log("boot.img 复制失败")
                workDir.deleteRecursively()
                return StockConfigResult(false, deviceInfo, null, output, "bootimg")
            }

            val mb = sq(localMb.absolutePath)
            val wk = sq(workDir.absolutePath)
            val unpackSc = buildString {
                appendLine("cd $wk")
                // 清理可能残留的旧文件
                appendLine("rm -f $wk/kernel $wk/kconfig $wk/kernel_dtb 2>/dev/null || true")
                appendLine("$mb unpack boot.img 2>&1 || { echo 'magiskboot unpack failed'; exit 3; }")
                appendLine("[ -f $wk/kernel ] && { $mb extract $wk/kernel 2>&1 || echo 'magiskboot extract skipped'; } || echo 'no kernel file found'")
            }
            val unpackRes = RootUtils.execRootCommandForWebUi(unpackSc, timeoutSeconds = 120L)
            output.addAll(unpackRes.output.filter { it.isNotBlank() })

            // 严格读取本次 workDir 内的 kconfig
            val kconfig = File(workDir, "kconfig")
            val destFile = File(configDir, "$targetId$STOCK_CONFIG_SUFFIX")
            if (kconfig.isFile && kconfig.readText().contains("CONFIG_")) {
                destFile.writeText(buildString {
                    appendLine("# Stock Kernel Config")
                    appendLine("# Device: ${deviceInfo.model}")
                    appendLine("# Config ID: $targetId")
                    appendLine("# Source: boot.img ($bootImagePath)")
                    appendLine("# Extracted: ${now()}")
                    appendLine()
                })
                kconfig.forEachLine { if (it.trim().startsWith("CONFIG_")) destFile.appendText(it.trim() + "\n") }
                log("提取成功: ${destFile.absolutePath}")
            } else {
                log("magiskboot 未能提取内核配置")
                workDir.deleteRecursively()
                return StockConfigResult(false, deviceInfo, null, output, "bootimg")
            }
            workDir.deleteRecursively()
            return StockConfigResult(true, deviceInfo, destFile.absolutePath, output)
        } catch (e: Exception) {
            Log.e(TAG, "bootimg extract failed", e)
            workDir.deleteRecursively()
            log("异常: ${e.message}")
            return StockConfigResult(false, deviceInfo, null, output, "bootimg")
        }
    }

    // ── Push to fork ────────────────────────────────────────────────────

    fun pushToFork(
        context: Context,
        token: String,
        owner: String,
        repo: String,
        branch: String,
        deviceId: String,
        onOutput: ((String) -> Unit)? = null
    ): Boolean {
        val local = File(context.filesDir, "$STOCK_CONFIG_DIR/$deviceId$STOCK_CONFIG_SUFFIX")
        if (!local.isFile) return false

        fun log(m: String) { onOutput?.invoke(m); Log.d(TAG, m) }
        try {
            val content = local.readText()
            val encoded = Base64.encodeToString(content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            val fileName = "$deviceId$STOCK_CONFIG_SUFFIX"
            val remotePath = "$STOCK_CONFIG_DIR/$fileName"
            val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$remotePath"

            // Get existing SHA if any
            var sha: String? = null
            try {
                val c = URL("$apiUrl?ref=$branch").openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                if (c.responseCode == 200)
                    sha = org.json.JSONObject(c.inputStream.bufferedReader().readText()).optString("sha", null)
                c.disconnect()
            } catch (_: Exception) {}

            val body = org.json.JSONObject().apply {
                put("message", "Add stock config for $deviceId")
                put("content", encoded)
                put("branch", branch)
                sha?.let { put("sha", it) }
            }

            val conn = URL(apiUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            conn.doOutput = true; conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.bufferedWriter().use { it.write(body.toString()) }
            val code = conn.responseCode
            conn.disconnect()

            if (code in 200..201) {
                log("✓ 已推送到 $owner/$repo/$branch/$remotePath")
                return true
            } else {
                log("推送失败 HTTP $code")
                return false
            }
        } catch (e: Exception) {
            log("推送错误: ${e.message}")
            return false
        }
    }

    // ── SAF helper ──────────────────────────────────────────────────────

    fun copyContentUriToLocal(context: Context, uri: Uri): String? {
        return try {
            val temp = File(context.cacheDir, "selected-boot-${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(uri)?.use { i ->
                FileOutputStream(temp).use { o -> i.copyTo(o) }
            }
            temp.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        } catch (e: Exception) { Log.e(TAG, "copyUri", e); null }
    }

    // ── Fetch from upstream repository ───────────────────────────────────

    const val UPSTREAM_OWNER = "xingguangcuican6666"
    const val UPSTREAM_REPO = "ABK"
    const val UPSTREAM_BRANCH = "main"

    fun fetchFromUpstream(
        context: Context,
        deviceId: String,
        onOutput: ((String) -> Unit)? = null
    ): StockConfigResult {
        val deviceInfo = detectDevice()
        val targetId = deviceId.trim().takeIf { it.isNotBlank() } ?: deviceInfo.configId
        val output = mutableListOf<String>()
        fun log(m: String) { output.add(m); onOutput?.invoke(m); Log.d(TAG, m) }

        val configDir = File(context.filesDir, STOCK_CONFIG_DIR); configDir.mkdirs()
        val destFile = File(configDir, "$targetId$STOCK_CONFIG_SUFFIX")

        try {
            val fileName = "$targetId$STOCK_CONFIG_SUFFIX"
            val url = "https://raw.githubusercontent.com/$UPSTREAM_OWNER/$UPSTREAM_REPO/$UPSTREAM_BRANCH/$STOCK_CONFIG_DIR/$fileName"
            log("从上游仓库获取: $url")

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000; conn.readTimeout = 20_000
            conn.setRequestProperty("User-Agent", "ABK-Android")

            if (conn.responseCode != 200) {
                log("上游仓库中未找到 $fileName (HTTP ${conn.responseCode})")
                conn.disconnect()
                return StockConfigResult(false, deviceInfo, null, output, "upstream")
            }

            val content = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (!content.contains("CONFIG_")) {
                log("上游文件内容无效")
                return StockConfigResult(false, deviceInfo, null, output, "upstream")
            }

            val finalContent = if (content.trimStart().startsWith("#")) content else buildString {
                appendLine("# Stock Kernel Config for $targetId")
                appendLine("# Fetched from upstream: $UPSTREAM_OWNER/$UPSTREAM_REPO")
                appendLine("# Date: ${now()}")
                appendLine()
                append(content)
            }
            destFile.writeText(finalContent)
            log("从上游仓库恢复成功: $targetId")
            return StockConfigResult(true, deviceInfo, destFile.absolutePath, output, "upstream")
        } catch (e: Exception) {
            log("网络错误: ${e.message}")
            return StockConfigResult(false, deviceInfo, null, output, "upstream")
        }
    }

    // ── Cached configs ──────────────────────────────────────────────────

    fun stockConfigPath(context: Context, configId: String): File =
        File(context.filesDir, "$STOCK_CONFIG_DIR/$configId$STOCK_CONFIG_SUFFIX")

    fun configIdFromFile(file: File): String = file.name.removeSuffix(STOCK_CONFIG_SUFFIX)

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
    private fun sq(v: String) = "'${v.replace("'", "'\"'\"'")}'"
}
