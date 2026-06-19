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
 *    from the running kernel. May require root on some devices.
 *
 * 2. **Extract from boot.img** – unpack a user-selected boot.img file with magiskboot
 *    to obtain the embedded kernel config. Requires root.
 *
 * 3. **Fetch from repository** – download the matching `_stock_config` file from the
 *    user's GitHub fork's config/stock_config/ directory.
 *
 * All methods save the result as `config/stock_config/<configId>_stock_config` so
 * the CI workflow can reference it via the `stock_config` parameter.
 */
object StockConfigManager {

    private const val TAG = "StockConfigManager"
    const val STOCK_CONFIG_DIR = "config/stock_config"
    const val STOCK_CONFIG_SUFFIX = "_stock_config"

    // ── Device detection ─────────────────────────────────────────────────

    val KNOWN_DEVICES = mapOf(
        // OnePlus
        "CPH2581" to "oneplus_12_b",   "CPH2573" to "oneplus_12_b",
        "PJD110" to "oneplus_12_b",    "CPH2583" to "oneplus_12_b",
        "CPH2557" to "oneplus_11_b",   "CPH2451" to "oneplus_11_b",
        "CPH2449" to "oneplus_11_b",   "CPH2609" to "oneplus_12r_b",
        "CPH2413" to "oneplus_10_pro_b", "CPH2417" to "oneplus_10_pro_b",
        "NE2210" to "oneplus_10_pro_b",
        // Google Pixel
        "husky" to "pixel_8_pro",      "shiba" to "pixel_8",
        "akita" to "pixel_8a",         "felix" to "pixel_fold",
        "cheetah" to "pixel_7_pro",    "panther" to "pixel_7",
        "lynx" to "pixel_7a",          "oriole" to "pixel_6",
        "raven" to "pixel_6_pro",
        // Samsung
        "beyond2q" to "samsung_s20_plus", "beyondxq" to "samsung_s20_ultra",
        // Xiaomi
        "alioth" to "xiaomi_poco_f3",  "sweet" to "xiaomi_redmi_note10_pro",
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
            get() = if (matchedManifest != null) {
                "$model ($codename → $matchedManifest)"
            } else {
                "$model ($codename)"
            }

        val configId: String
            get() = matchedManifest ?: codename.ifBlank { device }.takeIf { it.isNotBlank() } ?: model
                .replace(" ", "_")
                .lowercase()
                .filter { it.isLetterOrDigit() || it == '_' }
                .trim('_')
    }

    data class StockConfigResult(
        val success: Boolean,
        val deviceInfo: DeviceInfo,
        val configPath: String?,
        val output: List<String>,
        val source: String = ""  // "proc", "bootimg", "repo"
    )

    fun detectDevice(): DeviceInfo {
        val model = Build.MODEL.trim()
        val manufacturer = Build.MANUFACTURER.trim()
        val product = Build.PRODUCT.trim()
        val device = Build.DEVICE.trim()
        val board = Build.BOARD.trim()
        val fingerprint = Build.FINGERPRINT.trim()
        val codename = resolveCodename(device, product, model)
        val matchedManifest = KNOWN_DEVICES[model] ?: KNOWN_DEVICES[codename] ?: KNOWN_DEVICES[device]
        return DeviceInfo(model, manufacturer, product, device, board, fingerprint, codename, matchedManifest)
    }

    private fun resolveCodename(deviceName: String, productName: String, model: String): String =
        listOf(deviceName, productName, model, model.lowercase().replace(" ", "_"))
            .first { it.isNotBlank() }.trim()

    // ── Method 1: Extract from /proc/config ──────────────────────────────

    /**
     * Read the kernel config directly from [/proc/config.gz] or [/proc/config].
     * Typically requires root on production kernels but may work without on
     * development/debug builds.
     */
    fun extractFromProcConfig(
        context: Context,
        configId: String? = null,
        onOutput: ((String) -> Unit)? = null
    ): StockConfigResult {
        val deviceInfo = detectDevice()
        val targetId = configId?.trim()?.takeIf { it.isNotBlank() } ?: deviceInfo.configId
        if (targetId.isBlank()) {
            return StockConfigResult(false, deviceInfo, null,
                listOf("无法确定设备标识"), "proc")
        }

        val configDir = File(context.filesDir, STOCK_CONFIG_DIR)
        configDir.mkdirs()
        val destFile = File(configDir, "${targetId}${STOCK_CONFIG_SUFFIX}")
        val output = mutableListOf<String>()

        fun log(msg: String) { output.add(msg); onOutput?.invoke(msg); Log.d(TAG, msg) }

        try {
            log("━━━ [方式1] 从 /proc/config 提取 ━━━")
            log("设备: ${deviceInfo.displayName}")

            // Build script header
            val header = "# Stock Kernel Config from /proc/config\n" +
                "# Device: ${deviceInfo.model}\n" +
                "# Manufacturer: ${deviceInfo.manufacturer}\n" +
                "# Product: ${deviceInfo.product}\n" +
                "# Board: ${deviceInfo.board}\n" +
                "# Config ID: $targetId\n" +
                "# Source: /proc/config\n" +
                "# Extracted: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}\n" +
                "\n"
            destFile.writeText(header)

            val script = """
                DEST=${shellQuote(destFile.absolutePath)}
                echo "尝试读取 /proc/config.gz …"
                if [ -f /proc/config.gz ] && zcat /proc/config.gz > /dev/null 2>&1; then
                    zcat /proc/config.gz | grep '^CONFIG_' >> "\$DEST"
                    COUNT=\$(grep -c '^CONFIG_' "\$DEST" 2>/dev/null || echo 0)
                    echo "✓ 从 /proc/config.gz 提取成功，\$COUNT 个配置项"
                elif [ -f /proc/config ] && grep -q '^CONFIG_' /proc/config 2>/dev/null; then
                    grep '^CONFIG_' /proc/config >> "\$DEST"
                    COUNT=\$(grep -c '^CONFIG_' "\$DEST" 2>/dev/null || echo 0)
                    echo "✓ 从 /proc/config 提取成功，\$COUNT 个配置项"
                elif [ -r /proc/config.gz ]; then
                    cat /proc/config.gz | gunzip 2>/dev/null | grep '^CONFIG_' >> "\$DEST" || true
                    COUNT=\$(grep -c '^CONFIG_' "\$DEST" 2>/dev/null || echo 0)
                    echo "✓ 从 /proc/config.gz(unzip) 提取成功，\$COUNT 个配置项"
                else
                    echo "✗ 无法读取 /proc/config.gz 或 /proc/config"
                    exit 2
                fi
            """.trimIndent()

            val result = RootUtils.execRootScript(script, timeoutSeconds = 30L)
            output.addAll(result.output.filter { it.isNotBlank() })

            val success = destFile.isFile && destFile.length() > 0L &&
                destFile.readLines().any { it.startsWith("CONFIG_") }
            if (success) {
                log("配置已保存: ${destFile.absolutePath}")
            }
            return StockConfigResult(success, deviceInfo, destFile.takeIf { success }?.absolutePath, output, "proc")
        } catch (e: Exception) {
            Log.e(TAG, "extractFromProcConfig failed", e)
            return StockConfigResult(false, deviceInfo, null, output + (e.message ?: "未知错误"), "proc")
        }
    }

    // ── Method 2: Extract from boot.img file ─────────────────────────────

    /**
     * Unpack a user-selected boot.img file with magiskboot and extract the
     * embedded kernel config. Requires root for magiskboot execution.
     */
    fun extractFromBootImageFile(
        context: Context,
        bootImagePath: String,
        configId: String? = null,
        onOutput: ((String) -> Unit)? = null
    ): StockConfigResult {
        val deviceInfo = detectDevice()
        val targetId = configId?.trim()?.takeIf { it.isNotBlank() } ?: deviceInfo.configId
        if (targetId.isBlank()) {
            return StockConfigResult(false, deviceInfo, null,
                listOf("无法确定设备标识"), "bootimg")
        }

        val output = mutableListOf<String>()
        fun log(msg: String) { output.add(msg); onOutput?.invoke(msg); Log.d(TAG, msg) }

        val workDir = File(context.cacheDir, "stock-config-bootimg")
        workDir.deleteRecursively()
        workDir.mkdirs()

        val configDir = File(context.filesDir, STOCK_CONFIG_DIR)
        configDir.mkdirs()

        try {
            log("━━━ [方式2] 从 boot.img 文件提取 ━━━")
            log("设备: ${deviceInfo.displayName}")
            log("镜像路径: $bootImagePath")

            // Check root
            if (!RootUtils.isRootAvailable()) {
                return StockConfigResult(false, deviceInfo, null,
                    output + "需要 root 权限才能使用 magiskboot 解包", "bootimg")
            }

            // Locate magiskboot
            val magiskboot = locateMagiskboot(context)
            if (magiskboot == null) {
                return StockConfigResult(false, deviceInfo, null,
                    output + "未找到 magiskboot (需要 libmagiskboot.so)", "bootimg")
            }
            log("magiskboot: ${magiskboot.absolutePath}")

            // Copy boot image to work dir and make readable
            val bootImg = File(workDir, "boot.img")

            val setupScript = """
                set -e
                SRC=${shellQuote(bootImagePath)}
                DST=${shellQuote(bootImg.absolutePath)}
                
                echo "检查源文件…"
                if [ ! -f "\$SRC" ]; then
                    echo "✗ 源文件不存在: \$SRC"
                    exit 2
                fi
                
                SRC_SIZE=\$(wc -c < "\$SRC" 2>/dev/null || echo 0)
                echo "源文件大小: \$SRC_SIZE bytes"
                
                # If source is not readable, try copy via cp
                if ! cp "\$SRC" "\$DST" 2>/dev/null; then
                    echo "cp 失败，尝试 cat…"
                    cat "\$SRC" > "\$DST" || exit 3
                fi
                chmod 644 "\$DST"
                echo "✓ 镜像已复制到工作目录"
            """.trimIndent()

            val setupResult = RootUtils.execRootScript(setupScript, timeoutSeconds = 30L)
            output.addAll(setupResult.output.filter { it.isNotBlank() })

            if (!setupResult.success || !bootImg.isFile || bootImg.length() == 0L) {
                workDir.deleteRecursively()
                return StockConfigResult(false, deviceInfo, null, output, "bootimg")
            }

            // Unpack with magiskboot
            log("正在用 magiskboot 解包…")

            val unpackScript = """
                set -e
                MAGISKBOOT=${shellQuote(magiskboot.absolutePath)}
                WORK=${shellQuote(workDir.absolutePath)}
                
                cd "\$WORK"
                
                # Unpack
                echo "解包 boot 镜像…"
                "\$MAGISKBOOT" unpack boot.img
                
                echo "解包文件列表:"
                ls -la "\$WORK" || true
                
                # Try to extract kernel config
                if [ -f "\$WORK/kernel" ]; then
                    echo "找到 kernel 文件，提取内核配置…"
                    "\$MAGISKBOOT" extract "\$WORK/kernel" || true
                    if [ -f "\$WORK/kconfig" ]; then
                        echo "✓ 从 kernel 提取 kconfig 成功"
                    else
                        echo "✗ extract 未生成 kconfig"
                    fi
                else
                    echo "✗ 未找到 kernel 文件（可能是 GKI init_boot 格式）"
                fi
                
                # Check result
                if [ ! -f "\$WORK/kconfig" ]; then
                    echo "✗ 未能提取内核配置"
                    exit 4
                fi
                
                CONFIG_COUNT=\$(grep -c '^CONFIG_' "\$WORK/kconfig" 2>/dev/null || echo 0)
                echo "✓ kconfig 配置项: \$CONFIG_COUNT"
            """.trimIndent()

            val unpackResult = RootUtils.execRootScript(unpackScript, timeoutSeconds = 120L)
            output.addAll(unpackResult.output.filter { it.isNotBlank() })

            // Save config
            val kconfigFile = File(workDir, "kconfig")
            val destFile = File(configDir, "${targetId}${STOCK_CONFIG_SUFFIX}")

            if (kconfigFile.isFile && kconfigFile.length() > 0L) {
                val header = buildString {
                    appendLine("# ==================== Stock Kernel Config ====================")
                    appendLine("# Device: ${deviceInfo.model}")
                    appendLine("# Manufacturer: ${deviceInfo.manufacturer}")
                    appendLine("# Product: ${deviceInfo.product}")
                    appendLine("# Board: ${deviceInfo.board}")
                    appendLine("# Config ID: $targetId")
                    appendLine("# Source: boot.img ($bootImagePath)")
                    appendLine("# Extracted: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine("# ================================================================")
                    appendLine()
                }
                destFile.writeText(header)
                kconfigFile.forEachLine { line ->
                    val trimmed = line.trim()
                    if (trimmed.startsWith("CONFIG_")) {
                        destFile.appendText("$trimmed\n")
                    }
                }
                val count = destFile.readLines().count { it.startsWith("CONFIG_") }
                log("配置已保存: ${destFile.absolutePath}")
                log("配置项数: $count")
            } else {
                log("✗ 未找到提取的 kconfig，回退到 /proc/config…")
                // Fallback to /proc/config
                val fallbackScript = """
                    DEST=${shellQuote(destFile.absolutePath)}
                    echo "# Stock Config for $targetId (from /proc/config fallback)" > "\$DEST"
                    zcat /proc/config.gz 2>/dev/null | grep '^CONFIG_' >> "\$DEST" || \
                    grep '^CONFIG_' /proc/config 2>/dev/null >> "\$DEST" || true
                """.trimIndent()
                RootUtils.execRootScript(fallbackScript, timeoutSeconds = 20L)
            }

            workDir.deleteRecursively()
            val success = destFile.isFile && destFile.length() > 0L &&
                destFile.readLines().any { it.startsWith("CONFIG_") }
            return StockConfigResult(success, deviceInfo,
                destFile.takeIf { success }?.absolutePath, output, "bootimg")
        } catch (e: Exception) {
            Log.e(TAG, "extractFromBootImageFile failed", e)
            workDir.deleteRecursively()
            return StockConfigResult(false, deviceInfo, null,
                output + (e.message ?: "未知错误"), "bootimg")
        }
    }

    // ── Method 3: Fetch from repository ──────────────────────────────────

    /**
     * Download a stock_config file from the remote GitHub repository's
     * `config/stock_config/` directory.
     *
     * URL pattern:
     *   https://raw.githubusercontent.com/{owner}/{repo}/{branch}/config/stock_config/{configId}_stock_config
     */
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
        fun log(msg: String) { output.add(msg); onOutput?.invoke(msg); Log.d(TAG, msg) }

        val configDir = File(context.filesDir, STOCK_CONFIG_DIR)
        configDir.mkdirs()
        val destFile = File(configDir, "${targetId}${STOCK_CONFIG_SUFFIX}")

        try {
            log("━━━ [方式3] 从仓库获取 ━━━")
            log("设备: ${deviceInfo.displayName}")
            log("目标 ID: $targetId")

            val fileName = "${targetId}${STOCK_CONFIG_SUFFIX}"
            val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/$STOCK_CONFIG_DIR/$fileName"

            log("远程 URL: $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("User-Agent", "ABK-Android")

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                log("✗ HTTP $responseCode: 远程仓库中没有 $fileName")
                return StockConfigResult(false, deviceInfo, null, output, "repo")
            }

            val content = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            if (content.isBlank() || !content.contains("CONFIG_")) {
                log("✗ 远程文件内容无效（不含 CONFIG_ 条目）")
                return StockConfigResult(false, deviceInfo, null, output, "repo")
            }

            // Add a header if not present
            val hasHeader = content.trimStart().startsWith("#")
            val finalContent = if (hasHeader) content else {
                buildString {
                    appendLine("# Stock Kernel Config for $targetId")
                    appendLine("# Fetched from: $url")
                    appendLine("# Date: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}")
                    appendLine()
                    append(content)
                }
            }
            destFile.writeText(finalContent)

            val count = destFile.readLines().count { it.startsWith("CONFIG_") }
            log("✓ 从仓库获取成功: $fileName")
            log("配置项数: $count")

            return StockConfigResult(true, deviceInfo, destFile.absolutePath, output, "repo")
        } catch (e: Exception) {
            Log.e(TAG, "fetchFromRepository failed", e)
            return StockConfigResult(false, deviceInfo, null,
                output + (e.message ?: "网络错误"), "repo")
        }
    }

    // ── Helper: resolve boot.img from a content URI (SAF) ────────────────

    /**
     * Copy a content-URI (e.g. from SAF file picker) to a local temp path
     * accessible from the root shell, and return that path.
     */
    fun copyContentUriToLocal(context: Context, uri: Uri): String? {
        return try {
            val tempFile = File(context.cacheDir, "selected-boot-${System.currentTimeMillis()}.img")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output -> input.copyTo(output) }
            }
            if (tempFile.isFile && tempFile.length() > 0L) tempFile.absolutePath else null
        } catch (e: Exception) {
            Log.e(TAG, "copyContentUriToLocal failed", e)
            null
        }
    }

    // ── Cached config management ─────────────────────────────────────────

    fun stockConfigPath(context: Context, configId: String): File =
        File(context.filesDir, "$STOCK_CONFIG_DIR/${configId}${STOCK_CONFIG_SUFFIX}")

    fun listCachedConfigs(context: Context): List<File> {
        val dir = File(context.filesDir, STOCK_CONFIG_DIR)
        return dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(STOCK_CONFIG_SUFFIX) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    fun configIdFromFile(file: File): String =
        file.name.removeSuffix(STOCK_CONFIG_SUFFIX)

    // ── Internal helpers ─────────────────────────────────────────────────

    private fun locateMagiskboot(context: Context): File? {
        // Bundled libmagiskboot.so
        val bundled = File(context.applicationInfo.nativeLibraryDir, "libmagiskboot.so")
        if (bundled.isFile) return bundled
        // /data/local/tmp
        val localTmp = File("/data/local/tmp/magiskboot")
        if (localTmp.isFile && localTmp.canExecute()) return localTmp
        // /data/adb/ksud/bin
        val adbKsud = File("/data/adb/ksud/bin/magiskboot")
        if (adbKsud.isFile && adbKsud.canExecute()) return adbKsud
        return null
    }

    private fun shellQuote(value: String): String =
        "'${value.replace("'", "'\"'\"'")}'"
}
