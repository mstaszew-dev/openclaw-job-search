# Israeli/EU Job Board Access Diagnosis — 2026-07-29

## Root Cause

**The agent infrastructure IP (`149.102.244.113`, Datacamp VPS, Warsaw) is classified as a datacenter/cloud IP and is actively blocked by CDN-based Web Application Firewalls (WAFs) used by the majority of Israeli job boards and several EU boards.**

### Technical Details

| Component | Value |
|-----------|-------|
| Agent outbound IP | `149.102.244.113` |
| Hostname | `unn-149-102-244-113.datapacket.com` |
| ASN | `AS212238 Datacamp Limited` |
| Location | Warsaw, Mazovia, Poland |
| Classification | **Datacenter / Cloud VPS** — NOT residential |

### WAF Blocking Behavior

Israeli job boards use **Radware Bot Manager** (identifiable by the `server: rdwr` HTTP header), which:
- Maintains a list of known datacenter/cloud IP ranges
- Blocks all non-residential IPs with HTTP 403
- Returns a transaction ID for tracking

This is **not** a geographic block — it's a **datacenter IP block**. The system is in Poland but could be anywhere in the world as a datacenter IP and would still be blocked.

## Board-by-Board Access Matrix

### ❌ BLOCKED (403 from VPS — also likely blocked from MacBook if on VPN/datacenter)

| Board | WAF | Status | Notes |
|-------|-----|--------|-------|
| **Drushim** | Radware (`rdwr`) | 403 | Israeli top-tier board |
| **AllJobs** | Radware (`rdwr`) | 403 | Largest IL board |
| **Jobnet** | Radware (`rdwr`) | 403 | High-tech IL board |
| **Indeed Israel** | Cloudflare | 403 | Aggregator |
| **Glassdoor** | Cloudflare | 403 | Aggregator |
| **TopStartups.io** | Cloudflare | 403 | Startup listings |
| **NBN Job Board** | Cloudflare | 403 | Anglo/olim board |
| **Telfed** | Cloudflare | 403 | Sharon area jobs |
| **Pracuj.pl** | Cloudflare | 403 | Largest PL board |
| **Wellfound** | Custom | 403 | Startup roles |
| **Toptal** | Custom | 403 | Freelance network |

### ✅ ACCESSIBLE (200 from VPS — fully usable by agent)

| Board | URL | Content Quality | Java Jobs? |
|-------|-----|----------------|------------|
| **JobMaster** | https://www.jobmaster.co.il | SSR with real listings | ✅ 24+ Java jobs |
| **Janglo** | https://www.janglo.net/jobs?categories=progming | SSR with real listings | ✅ Some |
| **DevJobs IL** | https://www.devjobs.co.il | SSR (has minor SQL bug) | ✅ ~3,271 total |
| **TechAviv** | https://jobs.techaviv.com | SSR | ✅ Curated roles |
| **JobsSeek** | https://www.jobsseek.info | SSR | ✅ ~49 hi-tech roles |
| **JustJoinIt** (PL) | https://justjoin.it | SSR | ✅ 803 remote Java |
| **NoFluffJobs** (PL) | https://nofluffjobs.com | SSR | ✅ 428 remote Java |
| **LinkedIn** | https://www.linkedin.com/jobs/ | SSR (guest view) | ✅ Major pool |
| **Cord.co** | https://www.cord.co | SSR | ✅ |
| **Arc.dev** | https://arc.dev | SSR | ✅ Remote roles |
| **JetBrains Careers** | https://www.jetbrains.com/careers | SSR | ✅ |

### ⚠️ PARTIALLY ACCESSIBLE (redirects/CAPTCHAs possible)

| Board | Status | Notes |
|-------|--------|-------|
| **Secret Tel Aviv** | 301→`jobs.secrettelaviv.com` | Redirect destination may block |

## Recommended Strategy

### Option A: Use Local Chrome Browser for Blocked Boards (PREFERRED)

The MacBook's Chrome browser (connected via CDP at `127.0.0.1:9222`) uses the MacBook's **actual network connection**, NOT the VPS IP.

**If the MacBook is on residential internet:** Drushim, AllJobs, Jobnet should work in the local Chrome browser. The `web_fetch` tool is the one being blocked — not necessarily the browser.

**Action items:**
1. Navigate to `https://www.drushim.co.il` in a Chrome tab — check if it loads
2. Navigate to `https://www.alljobs.co.il` — check if it loads
3. If they load, the campaign scripts can use Playwright/CDP to interact with them
4. If they also get 403, the MacBook is likely on a VPN or same datacenter network — switch to residential network

### Option B: Agent Uses Accessible IL Boards (NO VPN NEEDED)

These boards are accessible from the VPS right now and can be scraped/interacted with by the agent directly:

**Immediate targets (IL):**
1. **JobMaster** — `https://www.jobmaster.co.il/jobs/?q=java` — 24 Java jobs, full SSR, free apply
2. **Janglo** — `https://www.janglo.net/jobs?categories=progming` — English-language Anglo community
3. **DevJobs IL** — `https://www.devjobs.co.il` — 3,271 dev jobs (fix: skip broken search, use main listing)
4. **TechAviv** — `https://jobs.techaviv.com` — Curated startup roles
5. **JobsSeek** — `https://www.jobsseek.info` — Hi-tech focused

### Option C: Pivot to EU/PL Remote Roles (B2B, 15,000+ PLN)

These boards are fully accessible from the VPS and have massive PL/remote Java pools:

1. **JustJoinIt** — `https://justjoin.it/all-locations/java?workplace=remote` — **803 remote Java jobs**, B2B contracts, 40-170 PLN/hour range
2. **NoFluffJobs** — `https://nofluffjobs.com/pl/search?job=java&remote=1` — **428 remote Java jobs**, transparent salaries
3. **Arc.dev** — Remote-first developer jobs globally
4. **LinkedIn** — Global search with Israel + Poland + remote filters

### Option D: Hybrid (RECOMMENDED)

Combine IL and PL strategies:

| Channel | Method | Target |
|---------|--------|--------|
| IL — Drushim/AllJobs/Jobnet | Use local Chrome CDP (if residential) | Mid-senior Java/Spring |
| IL — JobMaster/Janglo/DevJobs | Agent `web_fetch` (works now) | Mid-senior Java/Spring |
| IL — LinkedIn | CDP browser or `web_fetch` | Remote Java IL |
| PL/EU — JustJoinIt/NoFluffJobs | Agent `web_fetch` (works now) | Remote Java B2B 15k+ PLN |

## Actionable Next Steps

### 1. Test Local Chrome (5 minutes)
```bash
# In CDP Chrome, open Drushim
# The agent can navigate to: https://www.drushim.co.il
# If it loads = residential IP works = use Chrome for IL boards
```

### 2. If Local Chrome Also Blocked
The MacBook is on the same network or a VPN:
- Disable VPN temporarily
- Switch to mobile hotspot (residential IP)
- Use a residential proxy service (e.g., BrightData residential pool)

### 3. Run Campaign on Accessible Boards (IMMEDIATE)
```bash
# JobMaster Java search (accessible via web_fetch):
curl -sL "https://www.jobmaster.co.il/jobs/?q=java" | grep -oP 'checknum\.asp\?key=\d+'

# JustJoinIt remote Java:
curl -sL "https://justjoin.it/all-locations/java?workplace=remote" | head -c 5000
```

### 4. Update IL_BOARDS.md
Add accessible board priority and note the VPS IP restriction for blocked boards.

## Summary

| Issue | Cause | Solution |
|-------|-------|----------|
| Drushim 403 | Radware blocks datacenter IPs | Use local Chrome or accessible alternatives |
| AllJobs 403 | Radware blocks datacenter IPs | Use local Chrome or accessible alternatives |
| Jobnet 403 | Radware blocks datacenter IPs | Use local Chrome or accessible alternatives |
| Agent can't `web_fetch` IL boards | VPS IP = datacenter | Use CDP browser for blocked sites |
| Agent CAN `web_fetch` some boards | They don't use WAFs | JobMaster, Janglo, DevJobs, TechAviv, JobsSeek |
| Agent CAN `web_fetch` PL boards | They don't use WAFs on all | JustJoinIt, NoFluffJobs, Arc.dev |

**Bottom line:** The system is NOT geographically blocked — it's an IP classification issue. The agent VPS IP is classified as datacenter and rejected by WAFs. The local Chrome browser on the MacBook may work if it's on a residential network. If not, pivot to the accessible boards (JobMaster, JustJoinIt, NoFluffJobs) which have substantial Java job pools and are fully usable without any network changes.
