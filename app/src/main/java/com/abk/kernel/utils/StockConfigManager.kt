package com.abk.kernel.utils

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Three methods to obtain a stock kernel config (defconfig):
 *
 * 1. **Extract from /proc/config** – read /proc/config.gz or /proc/config directly
 *    from the running kernel. Requires login + fork.
 *
 * 2. **Extract from boot.img** – unpack a user-selected boot.img file with magiskboot
 *    to obtain the embedded kernel config. Requires login + fork + root.
 *
 * 3. **Fetch from repository** – download the matching `_stock_config` file from the
 *    remote upstream repository's config/stock_config/ directory.
 *
 * For methods 1 & 2, after extraction the config is pushed to the user's fork at
 * config/stock_config/<configId>_stock_config and referenced in builds via the
 * `stock_config` parameter.
 */
object StockConfigManager {

    private const val TAG = "StockConfigManager"
    const val STOCK_CONFIG_DIR = "config/stock_config"
    const val STOCK_CONFIG_SUFFIX = "_stock_config"

    // ── Device detection ─────────────────────────────────────────────────

    val KNOWN_DEVICES = mapOf(
        "CPH2581" to "oneplus_12_b",   "CPH2573" to "oneplus_12_b",
        "PJD110"  to "oneplus_12_b",   "CPH2583" to "oneplus_12_b",
        "CPH2557" to "oneplus_11_b",   "CPH2451" to "oneplus_11_b",
        "CPH2449" to "oneplus_11_b",   "CPH2609" to "oneplus_12r_b",
        "CPH2413" to "oneplus_10_pro_b","CPH2417" to "oneplus_10_pro_b",
        "NE2210"  to "oneplus_10_pro_b",
        "husky"   to "pixel_8_pro",    "shiba"   to "pixel_8",
        "akita"   to "pixel_8a",       "felix"   to "pixel_fold",
        "cheetah" to "pixel_7_pro",    "panther" to "pixel_7",
        "lynx"    to "pixel_7a",       "oriole"  to "pixel_6",
        "raven"   to "pixel_6_pro",
        "beyond2q" to "samsung_s20_plus","beyondxq" to "samsung_s20_ultra",
        "alioth"  to "xiaomi_poco_f3", "sweet"   to "xiaomi_redmi_note10_pro",
        "beryllium" to "xiaomi_poco_f1",
    )

    data class DeviceInfo(
        val model: String,
        val manufacturer: String,
        val product: String,
        val device: String,
        val board: String,
        val fingerprint: String,
        val codename: String,
        val matchedManifest: String?
    ) {
        val displayName: String
            get() = if (matchedManifest != null) "$model ($codename → $matchedManifest)"
                    else "$model ($codename)"

        val configId: String
            get() = matchedManifest
                ?: codename.ifBlank { device }.takeIf { it.isNotBlank() }
                ?: model.toLowerCase().replace(" ", "_").filter { it.isLetterOrDigit() || it == '_' }.trim('_')
    }

    data class StockConfigResult(
        val success: Boolean,
        val deviceInfo: DeviceInfo,
        val configPath: String?,
        val output: List<String>,
        val source: String = ""
    )

    fun detectDevice(): DeviceInfo {
        val model = Build.MODEL.trim()
        val manufacturer = Build.MANUFACTURER.trim()
        val product = Build.PRODUCT.trim()
        val device = Build.DEVICE.trim()
        val board = Build.BOARD.trim()
        val fingerprint = Build.FINGERPRINT.trim()
        val codename = listOf(device, product, model.lowercase().replace(" ", "_"))
            .first { it.isNotBlank() }.trim()
        val matched = KNOWN_DEVICES[model] ?: KNOWN_DEVICES[codename] ?: KNOWN_DEVICES[device]
        return DeviceInfo(model, manufacturer, product, device, board, fingerprint, codename, matched)
    }

    // ── Method 1: Extract from /proc/config ──────────────────────────────

    fun extractFromProcConfig(
        context: Context,
        configId: String? = null,
        onOutput: ((String) -> Unit)? = null
    ): StockConfigResult {
        val deviceInfo = detectDevice()
        val targetId = configId?.trim()?.takeIf { it.isNotBlank() } ?: deviceInfo.configId
        if (targetId.isBlank())
            return StockConfigResult(false, deviceInfo, null, listOf("无法确定设备标识"), "proc")

        val configDir = File(context.filesDir, STOCK_CONFIG_DIR)
        configDir.mkdirs()
        val destFile = File(configDir, targetId + STOCK_CONFIG_SUFFIX)
        val output = mutableListOf<String>()

        fun log(m: String) { output.add(m); onOutput?.invoke(m); Log.d(TAG, m) }

        try {
            log("━━━ [方式1] 从 /proc/config 提取 ━━━")
            log("设备: ${deviceInfo.displayName}")

            val destPath = destFile.absolutePath
            val script = buildString {
                val D = sq(destPath)
                appendLine("echo '尝试读取 /proc/config.gz …'")
                appendLine("if [ -f /proc/config.gz ] && zcat /proc/config.gz > /dev/null 2>&1; then")
                appendLine("  echo '# Stock Kernel Config from /proc/config' > $D")
                appendLine("  echo '# Device: ${deviceInfo.model}' >> $D")
                appendLine("  echo '# Manufacturer: ${deviceInfo.manufacturer}' >> $D")
                appendLine("  echo '# Config ID: $targetId' >> $D")
                appendLine("  echo '# Source: /proc/config.gz' >> $D")
                appendLine("  echo '' >> $D")
                appendLine("  zcat /proc/config.gz | grep '^CONFIG_' >> $D 2>/dev/null")
                appendLine("  COUNT=${'$'}(grep -c '^CONFIG_' $D 2>/dev/null || echo 0)")
                appendLine("  echo '✓ 从 /proc/config.gz 提取成功，${'$'}COUNT 个配置项'")
                appendLine("elif [ -f /proc/config ] && grep -q '^CONFIG_' /proc/config 2>/dev/null; then")
                appendLine("  echo '# Stock Kernel Config from /proc/config' > $D")
                appendLine("  grep '^CONFIG_' /proc/config >> $D")
                appendLine("  echo '✓ 从 /proc/config 提取成功'")
                appendLine("elif [ -r /proc/config.gz ]; then")
                appendLine("  cat /proc/config.gz | gunzip 2>/dev/null | grep '^CONFIG_' >> $D || true")
                appendLine("  echo '✓ 从 /proc/config.gz(unzip) 提取'")
                appendLine("else")
                appendLine("  echo '✗ 无法读取 /proc/config.gz 或 /proc/config'")
                appendLine("  exit 2")
                appendLine("fi")
            }

            val result = RootUtils.execRootCommandForWebUi(script, timeoutSeconds = 30L)
            output.addAll(result.output.filter { it.isNotBlank() })
            if (!result.success) {
                // Try without root
                output.add("(已尝试无 root 方式读取)")
                tryNoRootProcConfig(destFile, deviceInfo, targetId, output)
            }

            val success = destFile.isFile && destFile.length() > 0L &&
                destFile.readLines().any { it.startsWith("CONFIG_") }
            if (success) log("配置已保存: $destPath")
            return StockConfigResult(success, deviceInfo, destFile.takeIf { success }?.absolutePath, output, "proc")
        } catch (e: Exception) {
            Log.e(TAG, "extractFromProcConfig failed", e)
            return StockConfigResult(false, deviceInfo, null, output + (e.message ?: "未知错误"), "proc")
        }
    }

    private fun tryNoRootProcConfig(dest: File, info: DeviceInfo, targetId: String, output: MutableList<String>) {
        try {
            val lines = when {
                File("/proc/config.gz").canRead() -> {
                    val bytes = File("/proc/config.gz").readBytes()
                    val gzIs = java.util.zip.GZIPInputStream(bytes.inputStream())
                    gzIs.bufferedReader().readLines()
                }
                File("/proc/config").canRead() -> File("/proc/config").readLines()
                else -> return
            }
            val configLines = lines.filter { it.trim().startsWith("CONFIG_") }
            if (configLines.isNotEmpty()) {
                dest.bufferedWriter().use { w ->
                    w.appendLine("# Stock Kernel Config from /proc/config")
                    w.appendLine("# Device: ${info.model}")
                    w.appendLine("# Config ID: $targetId")
                    w.appendLine("# Source: /proc/config (no-root)")
                    w.appendLine()
                    configLines.forEach { w.appendLine(it) }
                }
                output.add("✓ 无 root 方式读取成功，${configLines.size} 个配置项")
            }
        } catch (_: Exception) { }
    }

    // ── Method 2: Extract from boot.img ──────────────────────────────────

    fun extractFromBootImageFile(
        context: Context,
        bootImagePath: String,
        configId: String? = null,
        onOutput: ((String) -> Unit)? = null
    ): StockConfigResult {
        val deviceInfo = detectDevice()
        val targetId = configId?.trim()?.takeIf { it.isNotBlank() } ?: deviceInfo.configId
        if (targetId.isBlank())
            return StockConfigResult(false, deviceInfo, null, listOf("无法确定设备标识"), "bootimg")

        val output = mutableListOf<String>()
        fun log(m: String) { output.add(m); onOutput?.invoke(m); Log.d(TAG, m) }

        val workDir = File(context.cacheDir, "stock-config-bootimg")
        workDir.deleteRecursively(); workDir.mkdirs()
        val configDir = File(context.filesDir, STOCK_CONFIG_DIR)
        configDir.mkdirs()

        try {
            log("━━━ [方式2] 从 boot.img 文件提取 ━━━")
            log("设备: ${deviceInfo.displayName}")
            log("镜像路径: $bootImagePath")

            val magiskboot = locateMagiskboot(context)
            if (magiskboot == null)
                return StockConfigResult(false, deviceInfo, null,
                    output + "未找到 magiskboot (需要 libmagiskboot.so)", "bootimg")

            val bootImg = File(workDir, "boot.img")
            val destFile = File(configDir, targetId + STOCK_CONFIG_SUFFIX)

            // Copy boot image to work dir
            val srcP = sq(bootImagePath)
            val dstP = sq(bootImg.absolutePath)
            val cpScript = buildString {
                appendLine("echo '检查源文件…'")
                appendLine("[ -f $srcP ] || { echo '✗ 源文件不存在'; exit 2; }")
                appendLine("SRC_SIZE=${'$'}(wc -c < $srcP)")
                appendLine("echo '源文件大小: ${'$'}SRC_SIZE bytes'")
                appendLine("cp $srcP $dstP 2>/dev/null || cat $srcP > $dstP || exit 3")
                appendLine("chmod 644 $dstP")
                appendLine("echo '✓ 镜像已复制'")
            }
            val cpResult = RootUtils.execRootCommandForWebUi(cpScript, timeoutSeconds = 30L)
            output.addAll(cpResult.output.filter { it.isNotBlank() })
            if (!cpResult.success || !bootImg.isFile || bootImg.length() == 0L) {
                workDir.deleteRecursively()
                return StockConfigResult(false, deviceInfo, null, output, "bootimg")
            }

            // Unpack with magiskboot
            log("正在用 magiskboot 解包…")
            val mb = sq(magiskboot.absolutePath)
            val wk = sq(workDir.absolutePath)
            val unpackScript = buildString {
                appendLine("cd $wk")
                appendLine("echo '解包 boot 镜像…'")
                appendLine("$mb unpack boot.img")
                appendLine("echo '解包文件列表:'")
                appendLine("ls -la $wk || true")
                appendLine("if [ -f $wk/kernel ]; then")
                appendLine("  echo '找到 kernel 文件，提取内核配置…'")
                appendLine("  $mb extract $wk/kernel || true")
                appendLine("  [ -f $wk/kconfig ] && echo '✓ 从 kernel 提取 kconfig 成功' || echo '✗ 未生成 kconfig'")
                appendLine("else")
                appendLine("  echo '✗ 未找到 kernel 文件（可能是 GKI init_boot 格式）'")
                appendLine("fi")
                appendLine("[ -f $wk/kconfig ] || { echo '✗ 未能提取内核配置'; exit 4; }")
            }
            val unpackResult = RootUtils.execRootCommandForWebUi(unpackScript, timeoutSeconds = 120L)
            output.addAll(unpackResult.output.filter { it.isNotBlank() })

            val kconfigFile = File(workDir, "kconfig")
            if (kconfigFile.isFile && kconfigFile.length() > 0L &&
                kconfigFile.readText().contains("CONFIG_")
            ) {
                val header = buildString {
                    appendLine("# ==================== Stock Kernel Config ====================")
                    appendLine("# Device: ${deviceInfo.model}")
                    appendLine("# Manufacturer: ${deviceInfo.manufacturer}")
                    appendLine("# Config ID: $targetId")
                    appendLine("# Source: boot.img ($bootImagePath)")
                    appendLine("# Extracted: ${now()}")
                    appendLine("# ================================================================")
                    appendLine()
                }
                destFile.writeText(header)
                kconfigFile.forEachLine { if (it.trim().startsWith("CONFIG_")) destFile.appendText(it.trim() + "\n") }
                val cnt = destFile.readLines().count { it.startsWith("CONFIG_") }
                log("配置已保存: ${destFile.absolutePath} ($cnt 项)")
            }

            workDir.deleteRecursively()
            val success = destFile.isFile && destFile.length() > 0L &&
                destFile.readLines().any { it.startsWith("CONFIG_") }
            return StockConfigResult(success, deviceInfo,
                destFile.takeIf { success }?.absolutePath, output, "bootimg")
        } catch (e: Exception) {
            Log.e(TAG, "extractFromBootImageFile failed", e)
            workDir.deleteRecursively()
            return StockConfigResult(false, deviceInfo, null, output + (e.message ?: "未知错误"), "bootimg")
        }
    }

    // ── Method 3: Fetch from repository ──────────────────────────────────

    fun fetchFromRepository(
        context: Context,
        owner: String,
        repo: String,
        branch: String,
        deviceId: String,
        onOutput: ((String) -> Unit)? = null
    ): StockConfigResult {
        val deviceInfo = detectDevice()
        val targetId = deviceId.trim().takeIf { it.isNotBlank() } ?: deviceInfo.configId
        val output = mutableListOf<String>()
        fun log(m: String) { output.add(m); onOutput?.invoke(m); Log.d(TAG, m) }

        val configDir = File(context.filesDir, STOCK_CONFIG_DIR)
        configDir.mkdirs()
        val destFile = File(configDir, targetId + STOCK_CONFIG_SUFFIX)

        try {
            log("━━━ [方式3] 从仓库获取 ━━━")
            log("设备: ${deviceInfo.displayName}")

            val fileName = targetId + STOCK_CONFIG_SUFFIX
            val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/$STOCK_CONFIG_DIR/$fileName"
            log("远程 URL: $url")

            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000; conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "ABK-Android")

            if (conn.responseCode != 200) {
                log("✗ HTTP ${conn.responseCode}: 仓库中没有 $fileName")
                conn.disconnect()
                return StockConfigResult(false, deviceInfo, null, output, "repo")
            }
            val content = conn.inputStream.bufferedReader().readText()
            conn.disconnect()

            if (!content.contains("CONFIG_")) {
                log("✗ 远程文件内容无效")
                return StockConfigResult(false, deviceInfo, null, output, "repo")
            }
            val hasHeader = content.trimStart().startsWith("#")
            val final = if (hasHeader) content else buildString {
                appendLine("# Stock Kernel Config for $targetId")
                appendLine("# Fetched from: $url")
                appendLine("# Date: ${now()}")
                appendLine(); append(content)
            }
            destFile.writeText(final)
            log("✓ 从仓库获取成功 ($fileName)")
            return StockConfigResult(true, deviceInfo, destFile.absolutePath, output, "repo")
        } catch (e: Exception) {
            Log.e(TAG, "fetchFromRepository failed", e)
            return StockConfigResult(false, deviceInfo, null, output + (e.message ?: "网络错误"), "repo")
        }
    }

    // ── Push extracted config to fork repo ───────────────────────────────

    /**
     * Push the extracted stock config file to the user's GitHub fork via the
     * [Contents API](https://docs.github.com/en/rest/repos/contents).
     *
     * Uses a personal access token (classic or fine-grained) from OAuth login.
     *
     * @return true if the file was successfully pushed.
     */
    fun pushStockConfigToFork(
        context: Context,
        token: String,
        owner: String,
        repo: String,
        branch: String,
        deviceId: String,
        onOutput: ((String) -> Unit)? = null
    ): Boolean {
        val targetId = deviceId.trim().takeIf { it.isNotBlank() } ?: return false
        val localFile = File(context.filesDir, "$STOCK_CONFIG_DIR/${targetId}$STOCK_CONFIG_SUFFIX")
        if (!localFile.isFile) return false

        val output = mutableListOf<String>()
        fun log(m: String) { output.add(m); onOutput?.invoke(m); Log.d(TAG, m) }

        try {
            log("━━━ 推送 stock_config 到 fork 仓库 ━━━")
            val content = localFile.readText()
            val encoded = android.util.Base64.encodeToString(
                content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
            )
            val fileName = targetId + STOCK_CONFIG_SUFFIX
            val remotePath = "$STOCK_CONFIG_DIR/$fileName"
            val apiUrl = "https://api.github.com/repos/$owner/$repo/contents/$remotePath"

            log("推送目标: $owner/$repo/$remotePath")

            // First, try to get the existing file's SHA (for updates)
            var sha: String? = null
            try {
                val shaConn = URL(apiUrl + "?ref=$branch").openConnection() as HttpURLConnection
                shaConn.connectTimeout = 10_000; shaConn.readTimeout = 10_000
                shaConn.setRequestProperty("Authorization", "Bearer $token")
                shaConn.setRequestProperty("Accept", "application/vnd.github+json")
                if (shaConn.responseCode == 200) {
                    val json = shaConn.inputStream.bufferedReader().readText()
                    sha = org.json.JSONObject(json).optString("sha", null)
                    log("文件已存在，将更新 (SHA: ${sha?.take(7)})")
                } else {
                    log("文件不存在，将创建")
                }
                shaConn.disconnect()
            } catch (_: Exception) { }

            // Create or update the file
            val json = org.json.JSONObject().apply {
                put("message", "Add stock config for $targetId")
                put("content", encoded)
                put("branch", branch)
                sha?.let { put("sha", it) }
            }

            val putConn = URL(apiUrl).openConnection() as HttpURLConnection
            putConn.connectTimeout = 15_000; putConn.readTimeout = 30_000
            putConn.doOutput = true
            putConn.requestMethod = "PUT"
            putConn.setRequestProperty("Authorization", "Bearer $token")
            putConn.setRequestProperty("Accept", "application/vnd.github+json")
            putConn.setRequestProperty("Content-Type", "application/json")

            putConn.outputStream.bufferedWriter().use { it.write(json.toString()) }

            val code = putConn.responseCode
            val responseBody = try {
                putConn.inputStream.bufferedReader().readText()
            } catch (_: Exception) {
                putConn.errorStream?.bufferedReader()?.readText() ?: ""
            }
            putConn.disconnect()

            if (code in 200..201) {
                log("✓ 已推送到 $owner/$repo/$branch/$remotePath")
                return true
            } else {
                log("✗ 推送失败 HTTP $code: $responseBody")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "pushStockConfigToFork failed", e)
            log("✗ 推送错误: ${e.message}")
            return false
        }
    }

    // ── SAF content URI helper ───────────────────────────────────────────

    fun copyContentUriToLocal(context: Context, uri: Uri): String? {
        return try {
            val temp = File(context.cacheDir, "selected-boot-${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(temp).use { out -> inp.copyTo(out) }
            }
            temp.takeIf { it.isFile && it.length() > 0L }?.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "copyContentUriToLocal failed", e); null
        }
    }

    // ── Cached config management ─────────────────────────────────────────

    fun stockConfigPath(context: Context, configId: String): File =
        File(context.filesDir, "$STOCK_CONFIG_DIR/$configId$STOCK_CONFIG_SUFFIX")

    fun listCachedConfigs(context: Context): List<File> {
        val dir = File(context.filesDir, STOCK_CONFIG_DIR)
        return dir.listFiles()?.filter { it.isFile && it.name.endsWith(STOCK_CONFIG_SUFFIX) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun configIdFromFile(file: File): String = file.name.removeSuffix(STOCK_CONFIG_SUFFIX)

    // ── Internal helpers ─────────────────────────────────────────────────

    private fun locateMagiskboot(context: Context): File? {
        listOf(
            File(context.applicationInfo.nativeLibraryDir, "libmagiskboot.so"),
            File("/data/local/tmp/magiskboot"),
            File("/data/adb/ksud/bin/magiskboot"),
        ).forEach { if (it.isFile) return it }
        return null
    }

    private fun now() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** Shell-quote with single quotes. */
    private fun sq(v: String) = "'${v.replace("'", "'\"'\"'")}'"
}
