package dev.mstaszew.campaign.common.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

class McpClientException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Minimal MCP stdio client: spawns the server process (e.g.
 * `node @playwright/mcp/cli.js --cdp-endpoint ...`) and speaks newline-framed
 * JSON-RPC 2.0. Reflection-free (Jackson tree only) so it is native-image
 * safe. A dedicated reader thread feeds lines into a queue so [request]
 * timeouts are real: a wedged server can never block a caller past its
 * deadline. Not thread-safe beyond [nextRequestId] - callers serialize calls
 * (one browser, one tab discipline).
 */
class McpStdioClient(
    command: List<String>,
    private val startupTimeout: Duration = Duration.ofSeconds(60),
    private val mapper: ObjectMapper = ObjectMapper(),
) : AutoCloseable {

    private val process: Process
    private val writer: BufferedWriter
    private val lines = LinkedBlockingQueue<String?>()
    private val nextRequestId = AtomicLong(1)
    var started: Boolean = false
        private set

    init {
        val builder = ProcessBuilder(command).redirectErrorStream(false)
        process = builder.start()
        writer = BufferedWriter(OutputStreamWriter(process.outputStream, StandardCharsets.UTF_8))
        val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
        Thread({
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    lines.put(line)
                }
            } catch (_: InterruptedException) {
                // closing: stop feeding
            } finally {
                runCatching { reader.close() }
                lines.put(null as String?) // EOF sentinel
            }
        }, "mcp-stdio-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** MCP initialize handshake; must be called once before any tool call. */
    fun initialize(clientName: String, clientVersion: String) {
        val result = request(
            "initialize",
            mapper.createObjectNode().apply {
                put("protocolVersion", "2024-11-05")
                replace("capabilities", mapper.createObjectNode())
                replace(
                    "clientInfo",
                    mapper.createObjectNode().apply {
                        put("name", clientName)
                        put("version", clientVersion)
                    },
                )
            },
        )
        notify("notifications/initialized", mapper.createObjectNode())
        started = true
    }

    fun listTools(): List<String> {
        val result = request("tools/list", mapper.createObjectNode())
        return result.path("tools").map { it.path("name").asText() }
    }

    fun callTool(name: String, arguments: JsonNode, timeout: Duration): JsonNode {
        check(started) { "call initialize() first" }
        val result = request(
            "tools/call",
            mapper.createObjectNode().apply {
                put("name", name)
                replace("arguments", arguments)
            },
            timeout,
        )
        if (result.path("isError").asBoolean(false)) {
            throw McpClientException("tool $name failed: ${result.path("content").toString().take(500)}")
        }
        return result
    }

    /** Tool result content items as text (joining text blocks). */
    fun callToolText(name: String, arguments: JsonNode, timeout: Duration): String =
        callTool(name, arguments, timeout)
            .path("content")
            .filter { it.path("type").asText() == "text" }
            .joinToString("\n") { it.path("text").asText() }

    private fun request(method: String, params: JsonNode, timeout: Duration = startupTimeout): JsonNode {
        val id = nextRequestId.getAndIncrement()
        send(mapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            replace("params", params)
        })
        val deadline = System.nanoTime() + timeout.toNanos()
        while (true) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw McpClientException("timed out waiting for response to $method")
            val line = lines.poll(remaining, TimeUnit.NANOSECONDS)
                ?: throw McpClientException("timed out or server died waiting for $method")
            if (line.isBlank()) continue
            val node = mapper.readTree(line)
            if (node.has("method")) continue // server-initiated request/notification; ignored
            if (node.path("id").asLong(-1) != id) continue
            node.path("error").takeIf { !it.isMissingNode && !it.isNull }?.let { error ->
                throw McpClientException("$method error ${error.path("code").asInt()}: ${error.path("message").asText()}")
            }
            return node.path("result")
        }
    }

    private fun notify(method: String, params: JsonNode) {
        send(mapper.createObjectNode().apply {
            put("jsonrpc", "2.0")
            put("method", method)
            replace("params", params)
        })
    }

    private fun send(node: JsonNode) {
        if (!process.isAlive) throw McpClientException("MCP server process died (exit=${process.exitValue()})")
        writer.write(mapper.writeValueAsString(node))
        writer.newLine()
        writer.flush()
    }

    override fun close() {
        runCatching { writer.close() }
        process.destroy()
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }
}
