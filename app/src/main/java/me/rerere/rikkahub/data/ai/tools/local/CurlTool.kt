package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

private const val CURL_MAX_BYTES = 2_000_000
private const val CURL_DEFAULT_CHARS = 20_000
private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")

/**
 * curl 风格的 HTTP 工具。
 *
 * 关键点：
 * - body 支持字符串、数字、对象、数组；对象/数组会自动 JSON 序列化（之前只接受字符串，POST 容易变成空体）
 * - headers 的值支持字符串/数字/数组
 * - Content-Type 既可来自 content_type，也可来自 headers，显式 headers 优先
 * - 返回统一 JSON：status / headers / text / truncated
 */
internal fun buildCurlTool(): Tool = Tool(
    name = "curl",
    description = """
        Send an HTTP request and return the response as readable text. Works without a workspace.
        Supports GET/POST/PUT/PATCH/DELETE/HEAD, custom headers, JSON body (string or object), urlencoded form,
        redirects, timeout and HTML-to-text conversion.
        NOT for downloading files: only text is returned (max 2MB). Use the system curl inside a workspace for files.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("url", buildJsonObject {
                    put("type", "string")
                    put("description", "完整 URL，必须 http:// 或 https:// 开头")
                })
                put("method", buildJsonObject {
                    put("type", "string")
                    put("description", "GET / POST / PUT / PATCH / DELETE / HEAD，默认 GET")
                })
                put("headers", buildJsonObject {
                    put("type", "object")
                    put("description", "请求头键值对，例如 {\"Authorization\":\"Bearer xxx\"}")
                })
                put("body", buildJsonObject {
                    put("type", "string")
                    put("description", "请求体：字符串原样发送；对象/数组会自动 JSON 序列化（推荐传对象）")
                })
                put("form", buildJsonObject {
                    put("type", "object")
                    put("description", "表单键值对，发送为 application/x-www-form-urlencoded（与 body 二选一）")
                })
                put("content_type", buildJsonObject {
                    put("type", "string")
                    put("description", "请求体类型，默认 application/json；headers 里的 Content-Type 优先")
                })
                put("timeout", buildJsonObject {
                    put("type", "integer")
                    put("description", "超时秒数，默认 20，最大 120")
                })
                put("max_chars", buildJsonObject {
                    put("type", "integer")
                    put("description", "最多返回多少字符，默认 20000")
                })
                put("raw", buildJsonObject {
                    put("type", "boolean")
                    put("description", "true 返回原始 HTML，默认会把 HTML 转成纯文本")
                })
            },
            required = listOf("url"),
        )
    },
    execute = { raw ->
        val params = raw.jsonObject
        val url = params.str("url")?.trim().orEmpty()
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "url 必须以 http:// 或 https:// 开头"
        }
        val method = (params.str("method") ?: "GET").uppercase()
        require(method in METHODS) { "不支持的 method: $method（支持 ${METHODS.joinToString()}）" }

        // 1) headers：值可以是字符串/数字/数组；Content-Type 单独抽出来给 RequestBody
        var contentType = params.str("content_type") ?: "application/json"
        val headerPairs = mutableListOf<Pair<String, String>>()
        (params["headers"] as? JsonObject)?.forEach { (name, value) ->
            val key = name.trim()
            if (key.isEmpty()) return@forEach
            if (key.equals("Content-Length", true) || key.equals("Host", true)) return@forEach
            val text = value.headerText()
            if (key.equals("Content-Type", true)) contentType = text else headerPairs += key to text
        }

        // 2) body：对象/数组自动 JSON 化；form 优先
        val form = params["form"] as? JsonObject
        val bodyElement = params["body"]
        val formEncoded = if (form != null && form.isNotEmpty()) {
            form.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v.paramText())}" }
        } else null
        val bodyText: String? = when {
            formEncoded != null -> formEncoded
            bodyElement == null || bodyElement is JsonNull -> null
            bodyElement is JsonPrimitive && bodyElement.isString -> bodyElement.content
            else -> bodyElement.toString()
        }
        val effectiveContentType = if (formEncoded != null) "application/x-www-form-urlencoded" else contentType

        if ((method == "GET" || method == "HEAD") && (bodyText != null || formEncoded != null)) {
            return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                put("url", url)
                put("method", method)
                put("error", "$method 不支持请求体；请改用 POST/PUT/PATCH")
            }.toString()))
        }

        val timeoutSec = (params.str("timeout")?.toIntOrNull() ?: 20).coerceIn(1, 120)
        val maxChars = (params.str("max_chars")?.toIntOrNull() ?: CURL_DEFAULT_CHARS).coerceIn(500, 200_000)
        val keepRaw = params["raw"]?.paramBool() ?: false

        withContext(Dispatchers.IO) {
            val result = runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                    .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                    .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()

                val requestBody = if (method == "GET" || method == "HEAD") {
                    null
                } else {
                    (bodyText ?: "").toRequestBody(effectiveContentType.toMediaTypeOrNull())
                }

                val builder = Request.Builder().url(url)
                builder.header("User-Agent", "RikkaHub/curl-tool")
                builder.header("Accept", "*/*")
                headerPairs.forEach { (k, v) -> builder.header(k, v) }
                builder.method(method, requestBody)

                client.newCall(builder.build()).execute().use { resp ->
                    val bytes = readLimited(resp.body?.byteStream(), CURL_MAX_BYTES)
                    val mediaType = resp.body?.contentType()
                    val charset = mediaType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    Fetched(
                        status = resp.code,
                        message = resp.message,
                        finalUrl = resp.request.url.toString(),
                        contentType = resp.header("Content-Type").orEmpty(),
                        headers = resp.headers.toMultimap(),
                        bytes = bytes,
                        charset = charset,
                        isHtml = mediaType?.subtype?.contains("html", true) == true,
                    )
                }
            }

            result.fold(
                onSuccess = { page ->
                    val text = page.bytes.toString(page.charset)
                    val bodyOut = if (!keepRaw && page.isHtml) htmlToText(text) else text
                    val truncated = bodyOut.length > maxChars
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("url", url)
                        put("method", method)
                        put("status", page.status)
                        put("message", page.message)
                        put("finalUrl", page.finalUrl)
                        put("contentType", page.contentType)
                        put("headers", buildJsonObject {
                            page.headers.forEach { (k, values) ->
                                put(k, buildJsonArray { values.forEach { add(it) } })
                            }
                        })
                        put("truncated", truncated)
                        put("text", if (truncated) bodyOut.take(maxChars) + "\n...[truncated]" else bodyOut)
                    }.toString()))
                },
                onFailure = { e ->
                    listOf(UIMessagePart.Text(buildJsonObject {
                        put("url", url)
                        put("method", method)
                        put("error", "${e.javaClass.simpleName}: ${e.message}")
                    }.toString()))
                },
            )
        }
    },
)

private class Fetched(
    val status: Int,
    val message: String,
    val finalUrl: String,
    val contentType: String,
    val headers: Map<String, List<String>>,
    val bytes: ByteArray,
    val charset: java.nio.charset.Charset,
    val isHtml: Boolean,
)

private fun readLimited(input: java.io.InputStream?, limit: Int): ByteArray {
    if (input == null) return ByteArray(0)
    val out = ByteArrayOutputStream()
    input.use { ins ->
        val buf = ByteArray(16 * 1024)
        var total = 0
        while (total < limit) {
            val n = ins.read(buf)
            if (n <= 0) break
            val take = minOf(n, limit - total)
            out.write(buf, 0, take)
            total += take
        }
    }
    return out.toByteArray()
}

private fun JsonElement.headerText(): String = when (this) {
    is JsonArray -> joinToString(", ") { it.paramText() }
    else -> paramText()
}

private fun JsonElement.paramText(): String = when (this) {
    is JsonPrimitive -> contentOrNull ?: ""
    is JsonNull -> ""
    else -> toString()
}

private fun JsonElement.paramBool(): Boolean = when (this) {
    is JsonPrimitive -> contentOrNull?.toBooleanStrictOrNull() ?: false
    else -> false
}

private fun encode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")

private fun JsonObject.str(name: String): String? =
    this[name]?.let { if (it is JsonPrimitive) it.contentOrNull else null }

private fun htmlToText(html: String): String {
    var s = html
    s = s.replace(Regex("(?is)<(script|style|noscript|template)[^>]*>.*?</\\1>"), " ")
    s = s.replace(Regex("(?s)<!--.*?-->"), " ")
    s = s.replace(Regex("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>|</h[1-6]>"), "\n")
    s = s.replace(Regex("(?s)<[^>]+>"), " ")
    s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<")
        .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
    s = s.replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
    s = s.replace(Regex("\\n\\s*\\n+"), "\n\n")
    return s.trim()
}
