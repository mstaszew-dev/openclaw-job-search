#!/usr/bin/env python3
"""Generate the five StarUML 6 (.mdj) models for the k3s job pipeline.

How StarUML 6.3.4 loads a hand-written project (established by experiment
against the real app; full findings in `docs/diagrams/README.md`):

  * a view must carry ONLY geometry and model content. Hand-written
    `subViews`, `nameCompartment` or `attributeCompartment` refs make the app
    drop the view entirely (the boxes and their text never render).
  * the app builds the compartments itself from the model, so the visible
    text comes from the element's `name` and the element's `attributes`.
  * a note is the exception: `UMLNoteView` never reads the model, so its
    multi-line text must be set on the VIEW (`text`), not on the element.
  * a relation view must reference its own model element id in `model` and
    two distinct view ids in `head` / `tail`; reusing one id for both the
    element and the view kills the label.
  * relation views are the exception to the geometry-only rule: the app
    builds their three labels (name, stereotype, property) from the view's
    own `subViews` (EdgeLabelView) plus the `nameLabel` / `stereotypeLabel`
    / `propertyLabel` refs.
  * labels are drawn as given, without wrapping, so every box is sized from
    its longest text (about 6.3 px per character at Arial 12).

Validity check (manual, not run by this script): export each `.mdj` with
`staruml image -f svg` (the app must load the project to export it), then grep
the SVG for the expected labels. See `docs/diagrams/README.md`.

Usage: python3 gen_mdj.py
"""

import base64
import json
import os
import pathlib

OUT = pathlib.Path(__file__).resolve().parent.parent
PX_PER_CHAR = 6.3
MIN_WIDTH = 320
MAX_WIDTH = 900


def nid():
    """StarUML-style id: base64 of 15 random bytes (20 chars, trailing '=')."""
    return base64.b64encode(os.urandom(15)).decode()


def fit_width(*texts):
    longest = max(len(t) for t in texts if t)
    return max(MIN_WIDTH, min(MAX_WIDTH, int(longest * PX_PER_CHAR) + 60))


class Builder:
    """Builds one project with one diagram. Text lives on the model only."""

    def __init__(self, name, model_name, diagram_type, diagram_name, width,
                 cols=3, dy=150):
        self.project_id = nid()
        self.model_id = nid()
        self.diagram_id = nid()
        self.name = name
        self.model_name = model_name
        self.diagram_type = diagram_type
        self.diagram_name = diagram_name
        self.width = width
        self.cols = cols
        self.dy = dy
        self.margin = 40
        self.gap = 40
        self.elements = []
        self.views = []
        self.boxes = {}

    def _cell(self, col, row):
        return (self.margin + col * (self.width + self.gap),
                self.margin + row * self.dy)

    def _add_view(self, key, view_type, element_id, x, y, w, h, fill, extra=None):
        vid = nid()
        view = {
            "_type": view_type,
            "_id": vid,
            "_parent": {"$ref": self.diagram_id},
            "model": {"$ref": element_id},
            "visible": True,
            "enabled": True,
            "lineColor": "#000000",
            "fillColor": fill,
            "fontColor": "#000000",
            "font": "Arial;12;0",
            "parentStyle": False,
            "containerChangeable": True,
            "containerExtending": True,
            "left": x,
            "top": y,
            "width": w,
            "height": h,
            "autoResize": False,
            "stereotypeDisplay": "label",
            "wordWrap": False,
        }
        if extra:
            view.update(extra)
        self.views.append(view)
        self.boxes[key] = (vid, x, y, w, h)

    def node(self, key, name, col, row, element_type="UMLComponent",
             view_type="UMLComponentView", attributes=None, height=None,
             fill="#ffffff"):
        x, y = self._cell(col, row)
        h = height or (52 + 15 * len(attributes) if attributes else 56)
        eid = nid()
        element = {
            "_type": element_type,
            "_id": eid,
            "_parent": {"$ref": self.model_id},
            "name": name,
            "visibility": "public",
        }
        if attributes:
            element["attributes"] = [{
                "_type": "UMLAttribute",
                "_id": nid(),
                "_parent": {"$ref": eid},
                "name": text,
                "visibility": "public",
                "isStatic": False,
                "isLeaf": False,
                "type": "",
                "isReadOnly": False,
                "isOrdered": False,
                "isUnique": False,
                "isDerived": False,
                "aggregation": "none",
                "isID": False,
            } for text in attributes]
        self.elements.append(element)
        self._add_view(key, view_type, eid, x, y, self.width, h, fill)
        if view_type == "UMLClassView":
            # suppressOperations is a plain view property, not a ref.
            self.views[-1]["suppressOperations"] = True
            self.views[-1]["showVisibility"] = True
            self.views[-1]["showType"] = True
        return key

    def note(self, key, lines, col, row, width=None, height=None):
        """A UML note.

        UMLNoteView does not read the model, so the text must live on the
        VIEW (`text`), not on the element; the element keeps a copy for the
        model side.
        """
        x, y = self._cell(col, row)
        w = max(width or self.width, 560)
        body = "\n".join(lines)
        h = height or (24 + 15 * len(lines))
        eid = nid()
        self.elements.append({
            "_type": "UMLNote",
            "_id": eid,
            "_parent": {"$ref": self.model_id},
            "text": body,
        })
        self._add_view(key, "UMLNoteView", eid, x, y, w, h, "#ffffcc",
                       extra={"text": body, "wordWrap": True,
                              "horzAlign": 0, "vertAlign": 0})
        return key

    def edge(self, tail_key, head_key, text=""):
        tv, tx, ty, tw, th = self.boxes[tail_key]
        hv, hx, hy, hw, hh = self.boxes[head_key]
        if abs(ty - hy) < 10:  # same row: right edge -> left edge
            points = "%d:%d;%d:%d" % (tx + tw, ty + th // 2, hx, hy + hh // 2)
        else:  # bottom center -> top center
            points = "%d:%d;%d:%d" % (tx + tw // 2, ty + th, hx + hw // 2, hy)
        mid = nid()  # model element id
        vid = nid()  # view id, must differ from the model id
        self.elements.append({
            "_type": "UMLDependency",
            "_id": mid,
            "_parent": {"$ref": self.model_id},
            "name": text,
            "source": {"$ref": self._element_of(tail_key)},
            "target": {"$ref": self._element_of(head_key)},
            "visibility": "public",
        })
        labels = [{
            "_type": "EdgeLabelView",
            "_id": nid(),
            "_parent": {"$ref": vid},
            "model": {"$ref": mid},
            "visible": bool(text) if position == 0 else False,
            "enabled": True,
            "lineColor": "#000000",
            "fillColor": "#ffffff",
            "fontColor": "#000000",
            "font": "Arial;12;0",
            "parentStyle": False,
            "containerChangeable": False,
            "containerExtending": True,
            "left": 0,
            "top": 0,
            "width": 300,
            "height": 12,
            "autoResize": False,
            "alpha": 0.0,
            "distance": 12 * (position + 1),
            "hostEdge": {"$ref": vid},
            "edgePosition": 1,
            "horizontalAlignment": 0,
            "verticalAlignment": 5,
            "text": text if position == 0 and text else None,
        } for position in range(3)]
        self.views.append({
            "_type": "UMLDependencyView",
            "_id": vid,
            "_parent": {"$ref": self.diagram_id},
            "model": {"$ref": mid},
            "subViews": labels,
            "visible": True,
            "enabled": True,
            "lineColor": "#000000",
            "fillColor": "#ffffff",
            "fontColor": "#000000",
            "font": "Arial;12;0",
            "parentStyle": False,
            "containerChangeable": True,
            "containerExtending": True,
            "head": {"$ref": hv},
            "tail": {"$ref": tv},
            "lineStyle": 0,
            "points": points,
            "stereotypeDisplay": "label",
            "nameLabel": {"$ref": labels[0]["_id"]},
            "stereotypeLabel": {"$ref": labels[1]["_id"]},
            "propertyLabel": {"$ref": labels[2]["_id"]},
        })

    def _element_of(self, key):
        vid = self.boxes[key][0]
        for view in self.views:
            if view["_id"] == vid:
                return view["model"]["$ref"]
        raise KeyError(key)

    def write(self, path):
        project = {
            "_type": "Project",
            "_id": self.project_id,
            "name": self.name,
            "ownedElements": [{
                "_type": "UMLModel",
                "_id": self.model_id,
                "_parent": {"$ref": self.project_id},
                "name": self.model_name,
                "ownedElements": self.elements + [{
                    "_type": self.diagram_type,
                    "_id": self.diagram_id,
                    "_parent": {"$ref": self.model_id},
                    "name": self.diagram_name,
                    "visible": True,
                    "defaultDiagram": False,
                    "ownedViews": self.views,
                }],
                "visibility": "public",
            }],
        }
        pathlib.Path(path).write_text(json.dumps(project, indent=2) + "\n")
        return len(self.views)

    def check_overlap(self):
        """Fail if two non-relation views intersect (README promises this)."""
        rects = []
        for v in self.views:
            if v["_type"] in ("UMLDependencyView", "EdgeLabelView"):
                continue
            rects.append((v["_type"], v["left"], v["top"], v["width"],
                          v["height"]))
        for i, a in enumerate(rects):
            for b in rects[i + 1:]:
                ax, ay, aw, ah = a[1:5]
                bx, by, bw, bh = b[1:5]
                if not (ax + aw <= bx or bx + bw <= ax
                        or ay + ah <= by or by + bh <= ay):
                    raise SystemExit("overlap: %s %r vs %s %r"
                                     % (a[0], a[1:5], b[0], b[1:5]))
        return len(rects)


# ---------------------------------------------------------------------------
# Diagram content. Every name, port, count, and index below comes from
# docs/diagrams/facts.md (live cluster, 2026-10-11). Node names carry the
# detail because the app renders them verbatim on one line.
# ---------------------------------------------------------------------------

NODE = {"element_type": "UMLNode", "view_type": "UMLNodeView"}
COMP = {"element_type": "UMLComponent", "view_type": "UMLComponentView"}
CLASS = {"element_type": "UMLClass", "view_type": "UMLClassView"}


def diagram_01():
    names = [
        "Mac dev michaels-macbook-air 100.125.162.30 - opencode + Playwright MCP",
        "Tailscale tailnet taila0a683.ts.net - overlay net for laptop and pod",
        "lubuntu laptop mstro-travelmate-p215-52 - Ubuntu 22.04.5, 4 vCPU / 7.8 GB, 100.94.153.31",
        "k3s v1.36.5+k3s1 single node - containerd 2.3.4, no registry, docker save | k3s ctr import",
        "finder :8081 (Deployment, ns campaign) - discovery, dedupe, eligibility, CV score, produce",
        "apply-worker (Deployment, ns campaign) - consumer group apply-workers, APPLY_MODE=shadow",
        "campaign-api :8080 (NodePort 30080, ns campaign) - /stats /jobs /applications",
        "kafka (StatefulSet, apache/kafka:3.7.1) - KRaft, single broker, heap 512 MB, campaign.jobs (3) + dlq (1)",
        "mongo-0 mongo:7 (StatefulSet) - replica set rs0, auth, WiredTiger cache 256 MB",
        "msrouter :3000 (Deployment, ns campaign) - PAUSED, replicas 0 since 2026-10-08",
        "lubuntu-agent (Pod, ns default) - Chrome on Xvfb :99, VNC :5900, CDP :9222, sshd :22",
        "hostPath /home/mstro/k3s-pod-data - mongodb, kafka, agent-home, agent-state, playwright-output, tailscale, usr-local",
    ]
    b = Builder("Deployment - Mac dev, tailnet, k3s laptop", "Deployment",
                "UMLDeploymentDiagram",
                "01 - deployment: Mac, tailnet, k3s on the lubuntu laptop",
                fit_width(*names))
    for index, name in enumerate(names):
        key = "n%d" % index
        b.node(key, name, index % 3, index // 3, **NODE)
    b.note("guide", [
        "Reading guide",
        "One k3s node runs ns campaign (finder, apply-worker, campaign-api,",
        "kafka-0, mongo-0, msrouter) and ns default (lubuntu-agent).",
        "Images are built on the host with docker and imported with",
        "k3s ctr images import; there is no registry.",
        "msrouter is pinned to 0 replicas and finder runs with",
        "FINDER_SCORING_ENABLED=false while the pipeline is paused.",
        "apply-worker reaches Chrome over CDP at the node IP",
        "port 30922 (NodePort 9222:30922).",
        "Host data lives under /home/mstro/k3s-pod-data, owned by mstro.",
        "The laptop host sshd does not auto-start after a reboot.",
    ], 0, 4)
    b.edge("n0", "n1", "ssh -p 2222 / 30022")
    b.edge("n1", "n2", "tailnet 100.94.153.31")
    b.edge("n2", "n3", "images imported into containerd")
    b.edge("n3", "n4", "Deployment")
    b.edge("n3", "n5", "Deployment")
    b.edge("n3", "n6", "NodePort 30080")
    b.edge("n3", "n9", "0/0 replicas")
    b.edge("n3", "n10", "pod in ns default")
    b.edge("n3", "n7", "StatefulSet")
    b.edge("n3", "n8", "StatefulSet")
    b.edge("n4", "n7", "produce campaign.jobs")
    b.edge("n7", "n5", "consume apply-workers")
    b.edge("n5", "n8", "record after write")
    b.edge("n6", "n8", "read")
    b.edge("n5", "n10", "CDP :30922")
    b.edge("n8", "n11", "data")
    b.edge("n7", "n11", "data")
    return b


def diagram_02():
    names = [
        "External sources - NoFluffJobs, JustJoin.it, theProtocol.it (PL only, fully remote)",
        "finder = Discovery & Deduplication Agent - crawl/API, normalize, hash + similarity dedupe",
        "campaign.jobs - Kafka topic, reference name validated_job_offers, 3 partitions, key companyKey",
        "apply-worker pool - consumer group apply-workers, one record per poll, ack after the Mongo write",
        "worker specialisms (reference) - skill/stack, salary/benefit, company/culture checks",
        "campaign.jobs.dlq - poison messages past maxAttempts -> job_state.status=DEAD",
        "mongo-0 = Job Search DB - job_listings, applications, skips, blockers, events, job_state",
        "campaign-api = UI/API - NodePort 30080, /stats /jobs /applications",
        "Chrome in lubuntu-agent - CDP node IP :30922, used only when APPLY_MODE=live",
        "mode switch - APPLY_MODE=shadow, msrouter PAUSED (replicas 0), fallback score 70",
    ]
    b = Builder("Job pipeline - architecture", "Architecture",
                "UMLComponentDiagram",
                "02 - job pipeline: sources, finder, kafka, worker fleet, db and ui",
                fit_width(*names))
    for index, name in enumerate(names):
        b.node("n%d" % index, name, index % 3, index // 3, **COMP)
    b.note("guide", [
        "Reference flow vs deployed",
        "Reference: External sources -> Discovery & Deduplication Agent ->",
        "Kafka (validated_job_offers) -> worker agents -> Job Search DB / UI.",
        "Deployed: finder -> campaign.jobs -> apply-worker pool -> mongo-0 ->",
        "campaign-api, with campaign.jobs.dlq for poison messages.",
        "The reference names three specialist workers; the deployment runs one",
        "consumer group (apply-workers), so parallelism comes from the three",
        "partitions, not from three services.",
        "Kafka owns delivery, Mongo owns bookkeeping: the offset commits only",
        "after the Mongo write succeeds, and a redelivery declines the action.",
    ], 0, 4)
    b.edge("n0", "n1", "collect")
    b.edge("n1", "n2", "produce validated offers")
    b.edge("n2", "n3", "consume")
    b.edge("n3", "n4", "the three checks")
    b.edge("n3", "n5", "maxAttempts")
    b.edge("n3", "n6", "record submitted / skipped / blocked")
    b.edge("n3", "n8", "apply (live only)")
    b.edge("n6", "n7", "read")
    b.edge("n7", "n9", "observed by")
    b.edge("n9", "n3", "gates")
    return b


def diagram_03():
    # Collection field lists beyond the index keys come from the Kotlin repos
    # (JobStateRepositoryImpl, Recorder, campaign-api entities), reviewed at
    # build time; facts.md documents indexes and document counts live.
    classes = [
        ("job_listings", "job_listings - 507 docs", [
            "source: String",
            "sourceJobId: String",
            "companyKey: String (idx)",
            "url: String (idx)",
            "uq_job_listings_source_job UNIQUE (source, sourceJobId)",
            "salary: Doc",
        ]),
        ("applications", "applications - 1845 docs", [
            "_id: String = source:sourceJobId",
            "companyKey: String (idx)",
            "roleKey: String (idx with companyKey)",
            "url: String (idx)",
            "appliedAt: Date (idx)",
            "salary / evidence / followUps / raw / record: Doc",
        ]),
        ("skips", "skips - 654 docs", [
            "reason: String",
            "companyKey: String (idx)",
            "url: String (idx)",
            "at: Date",
            "uq_skips_dedupe: reason, source, sourceJobId, companyKey, url, at",
        ]),
        ("blockers", "blockers - 102 docs", [
            "companyKey: String (idx)",
            "reason: String",
            "at: Date",
            "uq_blockers_dedupe: companyKey, reason, at",
        ]),
        ("events", "events - 40724 docs", [
            "action: String (idx with at)",
            "at: Date (idx)",
        ]),
        ("job_state", "job_state - 94 docs", [
            "status: String (idx)",
            "attempts: Int",
            "lastError: String",
            "score: Int (setOnInsert)",
            "ledger only, never schedules",
        ]),
        ("import_runs", "import_runs - 1 doc", [
            "one row per tracker.json import",
        ]),
        ("transactions", "transaction boundaries", [
            "recordSubmitted: application + event + job_state",
            "recordSkippedDuplicate / recordBlocked",
            "markTerminal / failJob",
            "Kafka owns delivery, Mongo owns bookkeeping",
        ]),
    ]
    b = Builder("MongoDB tracking store", "Data", "UMLClassDiagram",
                "03 - mongo data model: collections, keys, indexes, transactions",
                fit_width(*[f[1] for f in classes], *[a for f in classes for a in f[2]]),
                dy=240)
    for index, (key, name, attributes) in enumerate(classes):
        b.node(key, name, index % 3, index // 3, attributes=attributes, **CLASS)
    b.note("guide", [
        "Notes the metadata does not advertise",
        "uq_ indexes are not flagged unique in live metadata except",
        "uq_job_listings_source_job; the packed dedupe tuples are enforced by",
        "the write path (insert-if-absent), not by MongoDB.",
        "applications._id is the assigned String source:sourceJobId, so its",
        "uniqueness rides on the default _id_ index.",
        "Only three write paths use a transaction; single-document writes stay",
        "outside on purpose.",
        "job_state is a ledger: it records attempts and outcomes and never",
        "schedules work.",
    ], 0, 3)
    b.edge("skips", "applications", "dedupe: id, company, url")
    b.edge("blockers", "applications", "blockedRepeat counts as duplicate")
    b.edge("events", "applications", "ledger")
    b.edge("job_state", "events", "outcome events")
    b.edge("import_runs", "applications", "one-shot import")
    b.edge("job_listings", "applications", "discovery -> application")
    return b


def diagram_04():
    names = [
        "Clients - agent loop, CvScorer (finder), apply-worker",
        "msrouter :3000 - ClusterIP, OpenAI-compatible /v1/chat/completions, PAUSED (replicas 0)",
        "EXTRA_PROVIDER_ORDER 1 - unorouter",
        "EXTRA_PROVIDER_ORDER 2 - groq",
        "EXTRA_PROVIDER_ORDER 3 - sambanova",
        "EXTRA_PROVIDER_ORDER 4 - mistral",
        "EXTRA_PROVIDER_ORDER 5 - gemini = GCP (Google Gemini API, GEMINI_MODEL=gemini-2.5-flash-lite)",
        "EXTRA_PROVIDER_ORDER 6 - cline",
        "EXTRA_PROVIDER_ORDER 7 (last) - cloudflare",
        "local slot (on-host LLM providers) - tried before the extras (msrouter config, not facts.md)",
        "pause switch - msrouter.yaml replicas 0 + finder FINDER_SCORING_ENABLED=false",
    ]
    b = Builder("msrouter providers including GCP Gemini", "Providers",
                "UMLComponentDiagram",
                "04 - msrouter provider chain: GCP (Google Gemini) slot and failover order",
                fit_width(*names))
    for index, name in enumerate(names):
        b.node("n%d" % index, name, index % 3, index // 3, **COMP)
    b.note("guide", [
        "GCP is the Google Gemini slot in msrouter",
        "Base URL: generativelanguage.googleapis.com/v1beta/openai",
        "Default model: gemini-2.5-flash-lite, free tier via GEMINI_API_KEY.",
        "Order: unorouter, groq, sambanova, mistral, gemini, cline, cloudflare.",
        "Cloudflare is last in the walk; the local on-host slot comes first.",
        "The extras order is the facts.md EXTRA_PROVIDER_ORDER; the primary",
        "and local slots come from msrouter config, not facts.md.",
        "Pause switch (moves together): msrouter replicas 0 and finder",
        "FINDER_SCORING_ENABLED=false -> CvScorer fallback score 70; the",
        "score >= 60 CV filter stops rejecting and APPLY_MODE stays shadow.",
    ], 0, 4)
    b.edge("n0", "n1", "POST /v1/chat/completions")
    b.edge("n1", "n9", "local slot")
    for index in (2, 3, 4, 5, 6, 7, 8):
        b.edge("n1", "n%d" % index, "")
    b.edge("n10", "n1", "replicas 0")
    b.edge("n10", "n0", "fallback score 70")
    return b


def diagram_05():
    names = [
        "opencode (Mac) - MCP stdio client, mcp.servers.whatsapp in opencode.json",
        "whatsapp-mcp-server - Python, uv run main.py in tools/whatsapp-mcp",
        "whatsapp-bridge - Go / whatsmeow, WhatsApp Web multi-device, QR link",
        "SQLite store/messages.db - local history, chats + messages",
        "personal WhatsApp account - paired device, the user controls the phone",
        "MCP tools - search_contacts, list_messages, list_chats, get_chat, get_message_context",
        "MCP tools - send_message, send_file, send_audio_message, download_media",
        "scope note - opencode only, NOT wired into the campaign pipeline or k3s",
    ]
    b = Builder("WhatsApp MCP for opencode", "WhatsAppMCP",
                "UMLComponentDiagram",
                "05 - whatsapp mcp: opencode only, not part of the pipeline",
                fit_width(*names))
    for index, name in enumerate(names):
        b.node("n%d" % index, name, index % 3, index // 3, **COMP)
    b.note("guide", [
        "opencode only: nothing in the campaign pipeline or the k3s cluster",
        "calls these tools.",
        "Install: brew install go, clone lharries/whatsapp-mcp under",
        "~/ZCodeProject/tools, then go build the bridge.",
        "The bridge keeps WhatsApp Web multi-device state in a local SQLite",
        "store and prints a QR code to pair.",
        "Pairing needs the user's phone (WhatsApp > Linked devices); the",
        "re-link window is about 20 days.",
        "No message is sent by setup; sends happen only on an explicit ask.",
    ], 0, 3)
    b.edge("n0", "n1", "MCP stdio")
    b.edge("n1", "n2", "HTTP queries / sends")
    b.edge("n2", "n3", "persist")
    b.edge("n2", "n4", "multi-device protocol")
    b.edge("n0", "n5", "tools/list")
    b.edge("n0", "n6", "tools/call")
    b.edge("n0", "n7", "")
    return b


DIAGRAMS = [
    ("01-uml-deployment.mdj", diagram_01),
    ("02-arch-job-pipeline.mdj", diagram_02),
    ("03-mongo-data-model.mdj", diagram_03),
    ("04-gcp-msrouter-providers.mdj", diagram_04),
    ("05-wa-mcp-opencode.mdj", diagram_05),
]


def main():
    for filename, factory in DIAGRAMS:
        b = factory()
        boxes = b.check_overlap()
        views = b.write(OUT / filename)
        print("%s: %d views, %d boxes, overlap clean" % (filename, views, boxes))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
