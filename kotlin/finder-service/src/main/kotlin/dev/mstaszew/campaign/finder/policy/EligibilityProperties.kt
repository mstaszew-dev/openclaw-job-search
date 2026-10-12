package dev.mstaszew.campaign.finder.policy

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Campaign targeting rules ported from prompt.py + AGENT_TICK.md: PL only,
 * fully remote only, stack allowlist, hard seniority/tech exclusions, and the
 * B2B salary floor that applies ONLY when a salary is listed.
 */
@ConfigurationProperties(prefix = "finder.policy")
data class EligibilityProperties(
    var allowedRegions: Set<String> = setOf("PL"),
    /** Listing must be remote (remotePolicy contains one of these tokens). */
    var remoteTokens: Set<String> = setOf("remote", "zdalna", "zdalnie", "fully remote"),
    /** At least one token must appear in title+stack for the listing to pass. */
    var stackAllowlist: Set<String> = setOf(
        "java", "kotlin", "spring", "spring boot", "php", "laravel", "node", "node.js",
        "nodejs", "react", "javascript", "typescript",
    ),
    /** Any hit in the title rejects the listing (case-insensitive, word-ish match). */
    var excludedTitleKeywords: Set<String> = setOf(
        "team leader", "team lead", "tech lead", "technical lead", "lead developer",
        "lead engineer", "principal", "staff", "architect", "manager", "director", "head", "vp",
    ),
    /** Any hit anywhere (title+stack+company) rejects. */
    var excludedAnyKeywords: Set<String> = setOf(
        "abap", "salesforce", "apex", "c++", ".net", "c#", "android", "ios", "flutter",
        "react native", "devops", "site reliability", "qa", "tester", "test automation",
        "data engineer", "data scientist", "machine learning",
    ),
    /** B2B monthly net+VAT floor applied only when the listing shows a salary. */
    var plnB2bFloor: Int = 15000,
    /** Boards accepted as sources (legacy IL sources are rejected). */
    var allowedSources: Set<String> = setOf("nofluffjobs", "justjoin", "theprotocol"),
)
