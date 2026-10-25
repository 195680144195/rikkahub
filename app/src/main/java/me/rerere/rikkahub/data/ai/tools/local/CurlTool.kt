package me.rerere.rikkahub.data.ai.tools.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
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

/**
 * 完整的 HTTP 请求工具（curl 风格）。
 *
 * 特点：不依赖工作区、不依赖搜索服务，任何助手打开开关即可用。
 * 支持方法 / 自定义请求头 / 原始 body / 表单 / 超时 / HTML 转文本。
 */
internal fun buildCurlTool(): Tool = Tool(
    name = "curl",
    description = """
        Send an HTTP request and return the response as readable text. Works without a workspace.
        Supports GET/POST/PUT/PATCH/DELETE/HEAD, custom headers, raw body or urlencoded form,
        redirects, timeout and HTML-to-text conversion. Use raw=true to keep HTML source.
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
                    put("description", "原始请求体（JSON/文本等），与 form 二选一")
                })
                put("form", buildJsonObject {
                    put("type", "object")
                    put("description", "表单键值对，会以 application/x-www-form-urlencoded 发送")
                })
                put("content_type", buildJsonObject {
                    put("type", "string")
                    put("description", "body 的 Content-Type，默认 application/json")
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
                    put("description", "true 返回原始响应，默认 false 会把 HTML 转成纯文本")
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
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) {
            "不支持的 method: $method"
        }
        val headers = params["headers"] as? JsonObject
        val form = params["form"] as? JsonObject
        val bodyText = params.str("body")
        val contentType = params.str("content_type") ?: "application/json"
        val timeoutSec = (params.str("timeout")?.toIntOrNull() ?: 20).coerceIn(1, 120)
        val maxChars = (params.str("max_chars")?.toIntOrNull() ?: CURL_DEFAULT_CHARS).coerceIn(500, 200_000)
        val keepRaw = params["raw"]?.jsonPrimitive?.booleanOrNull ?: false

        withContext(Dispatchers.IO) {
            val result = runCatching {
                val client = OkHttpClient.Builder()
                    .connectTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                    .readTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                    .writeTimeout(timeoutSec.toLong(), TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()

                val requestBody = when {
                    method == "GET" || method == "HEAD" -> null
                    form != null && form.isNotEmpty() -> {
                        val encoded = form.entries.joinToString("&") { (k, v) ->
                            "${encode(k)}=${encode(v.jsonPrimitive.contentOrNull.orEmpty())}"
                        }
                        encoded.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull())
                    }
                    bodyText != null -> bodyText.toRequestBody(contentType.toMediaTypeOrNull())
                    else -> "".toRequestBody(contentType.toMediaTypeOrNull())
                }

                val builder = Request.Builder().url(url)
                builder.header("User-Agent", "RikkaHub/curl-tool")
                builder.header("Accept", "*/*")
                headers?.forEach { (k, v) ->
                    if (k.isNotBlank()) builder.header(k, v.jsonPrimitive.contentOrNull.orEmpty())
                }
                builder.method(method, requestBody)

                client.newCall(builder.build()).execute().use { resp ->
                    val bytes = ByteArrayOutputStream().also { out ->
                        val input = resp.body?.byteStream()
                        if (input != null) {
                            input.use { ins ->
                                val buf = ByteArray(16 * 1024)
                                var total = 0
                                while (total < CURL_MAX_BYTES) {
                                    val n = ins.read(buf)
                                    if (n <= 0) break
                                    out.write(buf, 0, n)
                                    total += n
                                }
                            }
                        }
                    }.toByteArray()

                    val mediaType = resp.body?.contentType()
                    val charset = mediaType?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
                    val textBody = runCatching { bytes.toString(charset) }
                        .getOrElse { bytes.toString(Charsets.UTF_8) }
                    val isHtml = mediaType?.subtype?.contains("html", true) == true

                    Triple(resp, textBody, isHtml)
                }
            }.map { (resp, text, isHtml) ->
                val bodyOut = if (!keepRaw && isHtml) htmlToText(text) else text
                val truncated = bodyOut.length > maxChars
                val textOut = if (truncated) bodyOut.take(maxChars) + "\n...[truncated]" else bodyOut
                buildJsonObject {
                    put("url", url)
                    put("method", method)
                    put("status", resp.code)
                    put("message", resp.message)
                    put("finalUrl", resp.request.url.toString())
                    put("contentType", resp.header("Content-Type").orEmpty())
                    put("headers", buildJsonObject {
                        resp.headers.toMultimap().forEach { (k, values) ->
                            put(k, buildJsonArray { values.forEach { add(it) } })
                        }
                    })
                    put("truncated", truncated)
                    put("text", textOut)
                }.toString()
            }

            result.fold(
                onSuccess = { json -> listOf(UIMessagePart.Text(json)) },
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

private fun encode(s: String): String =
    java.net.URLEncoder.encode(s, "UTF-8")

private fun JsonObject.str(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull

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
