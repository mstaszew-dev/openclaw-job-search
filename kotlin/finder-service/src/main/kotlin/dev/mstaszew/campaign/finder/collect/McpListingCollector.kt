package dev.mstaszew.campaign.finder.collect

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.mstaszew.campaign.common.mcp.McpClientException
import dev.mstaszew.campaign.common.mcp.McpStdioClient
import dev.mstaszew.campaign.common.net.LlmClientException
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Duration

/**
 * Harvests listings by driving the shared Chrome (CDP) through the Playwright
 * MCP server: navigate to the board's remote-search page for one stack, then
 * browser_evaluate a per-board extraction script that reads the listing JSON
 * the SPA already loaded. One board+stack per call; failures of a single
 * combination are logged and skipped so one bad page never blocks the run.
 */
class McpListingCollector(
    private val clientFactory: () -> McpStdioClient,
    private val stacks: List<String> = listOf("java", "kotlin", "php", "node", "react"),
    private val toolTimeout: Duration = Duration.ofSeconds(90),
    private val mapper: ObjectMapper = ObjectMapper(),
) : ListingCollector {

    override fun collect(source: String): List<CollectedListing> {
        val out = mutableListOf<CollectedListing>()
        clientFactory().use { client ->
            client.initialize("finder-service", "0.1.0")
            for (stack in stacks) {
                try {
                    out += collectStack(client, source, stack)
                } catch (e: Exception) {
                    when (e) {
                        is McpClientException, is LlmClientException -> log.warn(
                            "collect {} stack {} failed: {}",
                            source, stack, e.message,
                        )
                        else -> throw e
                    }
                }
            }
        }
        return out
    }

    private fun collectStack(client: McpStdioClient, source: String, stack: String): List<CollectedListing> {
        val pageUrl = pageUrl(source, stack)
        client.callToolText(
            "browser_navigate",
            mapper.createObjectNode().apply { put("url", pageUrl) },
            toolTimeout,
        )
        // let the SPA settle its own canonical redirects before evaluating
        runCatching {
            client.callToolText(
                "browser_wait_for",
                mapper.createObjectNode().apply { put("time", 3) },
                toolTimeout,
            )
        }
        val extracted = client.callToolText(
            "browser_evaluate",
            mapper.createObjectNode().apply {
                put("function", "() => ${extractionScript(source)}")
            },
            toolTimeout,
        )
        return parse(source, extracted)
    }

    /** Test-visible wrapper around the text extraction used by [collect]. */
    internal fun parseForTest(text: String, source: String): List<CollectedListing> = parse(source, text)

    private fun parse(source: String, text: String): List<CollectedListing> {
        val start = text.indexOf('[')
        val end = text.lastIndexOf(']')
        if (start < 0 || end <= start) return emptyList()
        val array: JsonNode = mapper.readTree(text.substring(start, end + 1))
        return array.mapNotNull { toListing(source, it) }
    }

    private fun toListing(source: String, n: JsonNode): CollectedListing? {
        val jobId = n.path("id").asText("")
        val url = n.path("url").asText("")
        val company = n.path("company").asText("")
        val title = n.path("title").asText("")
        // fail closed: a page section without an explicit remote flag is skipped
        val remote = n.path("remote").asBoolean(false)
        if (jobId.isBlank() || url.isBlank() || title.isBlank() || !remote) return null
        return CollectedListing(
            source = source,
            sourceJobId = jobId,
            company = company,
            roleTitle = title,
            url = url,
            remotePolicy = "remote",
            salaryMin = n.path("salaryMin").decimalOrNull(),
            salaryMax = n.path("salaryMax").decimalOrNull(),
            stack = n.path("stack").mapNotNull { it.asText(null) },
            rawJson = n.toString(),
        )
    }

    private fun JsonNode.decimalOrNull(): BigDecimal? {
        val text = if (isNumber) asText() else asText("").filter { it.isDigit() }
        return text.takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() }
    }

    companion object {
        private val log = LoggerFactory.getLogger(McpListingCollector::class.java)

        fun pageUrl(source: String, stack: String): String = when (source) {
            "nofluffjobs" -> "https://nofluffjobs.com/pl/$stack?criteria=cityId%3Dremote"
            "justjoin" -> "https://justjoin.it/all-remote/$stack"
            "theprotocol" -> "https://theprotocol.it/filtry/$stack?remote=true"
            else -> throw IllegalArgumentException("unknown board source: $source")
        }

        /**
         * Extraction scripts run in page context and return a JSON array
         * string. They read the SPA's own state (__NEXT_DATA__ / embedded
         * JSON), so no brittle CSS selectors; they are best-effort and a
         * board redesign degrades to an empty batch, never a crash.
         */
        fun extractionScript(source: String): String = when (source) {
            "nofluffjobs" -> """(() => {
                const el = document.getElementById('__NEXT_DATA__');
                if (!el) return '[]';
                const data = JSON.parse(el.textContent);
                const jobs = (data?.props?.pageProps?.jobs) || [];
                return JSON.stringify(jobs.map(j => ({
                    remote: true,
                    id: String(j.id || ''),
                    title: j.name || '',
                    company: (j.company && j.company.name) || '',
                    url: 'https://nofluffjobs.com/pl/job/' + (j.url || j.id || ''),
                    salaryMin: j.salary && j.salary.from,
                    salaryMax: j.salary && j.salary.to,
                    stack: (j.skills || []).map(s => s.name).filter(Boolean)
                })));
            })()"""
            "justjoin" -> """(() => {
                const el = document.getElementById('__NEXT_DATA__');
                if (!el) return '[]';
                const data = JSON.parse(el.textContent);
                const jobs = (data?.props?.pageProps?.offers) || [];
                return JSON.stringify(jobs.map(j => ({
                    remote: true,
                    id: String(j.id || j.slug || ''),
                    title: j.title || '',
                    company: (j.company && j.company.name) || '',
                    url: 'https://justjoin.it/offers/' + (j.id || j.slug || ''),
                    salaryMin: j.employment_types && j.employment_types[0] && j.employment_types[0].from,
                    salaryMax: j.employment_types && j.employment_types[0] && j.employment_types[0].to,
                    stack: ((j.skills || [])).map(s => s.name).filter(Boolean)
                })));
            })()"""
            "theprotocol" -> """(() => {
                const el = document.querySelector('script#__NEXT_DATA__, script[type="application/json"]');
                if (!el) return '[]';
                const data = JSON.parse(el.textContent);
                const jobs = (data?.props?.pageProps?.offers) || (data?.props?.pageProps?.dehydratedState?.queries?.[0]?.state?.data?.offers) || [];
                return JSON.stringify(jobs.map(j => ({
                    remote: true,
                    id: String(j.id || j.offerId || ''),
                    title: j.name || j.title || '',
                    company: (j.company && j.company.name) || '',
                    url: 'https://theprotocol.it/praca/' + (j.url || j.id || ''),
                    salaryMin: j.employmentTypes && j.employmentTypes[0] && j.employmentTypes[0].salaryFrom,
                    salaryMax: j.employmentTypes && j.employmentTypes[0] && j.employmentTypes[0].salaryTo,
                    stack: ((j.skills || [])).map(s => s.name).filter(Boolean)
                })));
            })()"""
            else -> throw IllegalArgumentException("unknown board source: $source")
        }
    }
}
