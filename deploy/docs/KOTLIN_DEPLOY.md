# Deploy runbook (lubuntu k3s)
#
# Stack: MongoDB 7 (single-node replica set rs0) + Kafka 3.7 (KRaft, single
# broker) + finder / apply-worker / campaign-api (JVM) + msrouter (its own pod).
# Kafka owns delivery; Mongo owns bookkeeping and dedupe.
#
# Host access (single-node k3s, lubuntu, user mstro): ssh -p 2222
# mstro@<laptop-ip> (same password as the pod's agent user). Host sshd does
# NOT auto-start after a reboot - start it before remote ops.
#
# Prereqs (once, on the lubuntu host as a user with k3s + docker):
#   export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
#   kubectl apply -f deploy/k8s/00-namespace.yaml
#   # Secrets, generated once and kept under $DATA, plus the provider keys:
#   cp <mac>/.env  $DATA/msrouter.env          # msrouter provider keys
#   bash deploy/bootstrap-lubuntu.sh           # generates everything else
#
# One-shot bootstrap (does all of the below):
#   sudo bash /home/mstro/k3s-pod-data/agent-home/campaign-data/bootstrap.sh
#
# Targeted changes afterwards:
#   docker build -f deploy/docker/Dockerfile.jvm-services --target <finder-jvm|api-jvm> \
#     -t localhost:5000/campaign-<name>:v1 kotlin/
#   docker build -f deploy/docker/Dockerfile.jvm --target <worker|importer> \
#     -t localhost:5000/campaign-<name>:v1 kotlin/
#   docker build -f deploy/docker/Dockerfile.msrouter -t localhost:5000/msrouter:k3s-headless-v1 \
#     /home/mstro/k3s-pod-data/agent-home/apps/msrouter   # k3s-headless branch
#   docker save <img>:<tag> | k3s ctr images import -
#   # :v1 / :k3s-headless-v1 never change tag: after every image rebuild run
#   kubectl -n campaign rollout restart deploy/<name>
#
# Nodes (not built here; pull from Docker Hub like postgres:16-alpine did):
#   mongo:7, apache/kafka:3.7.1
#
# Import the live tracker (idempotent):
#   scp the live tracker.json + events.jsonl to $DATA, then
#   bash deploy/rerun-import.sh          # or: kubectl delete job tracker-import
#                                        # kubectl apply -f deploy/k8s/tracker-import.yaml
#
# Verify:
#   curl http://<laptop-ip>:30080/api/v1/stats      # submitted/attempts/skips/blockers
#   curl http://<laptop-ip>:30080/api/v1/jobs       # job_state (PENDING/IN_FLIGHT/terminal)
#   bash deploy/campaign-status.sh                  # pods, lag, replica state, events
#   kubectl -n campaign logs deploy/apply-worker --tail=50   # shadow releases
#
# Cutover (user-gated, separate decision; rollback = APPLY_MODE=shadow + restart):
#   1. Stop the Mac Python agent (it owns the same campaign).
#   2. kubectl -n campaign set env deploy/apply-worker APPLY_MODE=live
#   3. Watch: kubectl -n campaign logs -f deploy/apply-worker
#      and curl .../api/v1/stats after the first submitted event.
#
# Coexistence notes:
#   - The lubuntu-agent pod (default ns) keeps running Chrome/Xvfb/VNC; the
#     finder and worker attach to its CDP on NodePort 30922 only. Chrome
#     rejects a DNS Host header, so the CDP path stays on the node IP.
#   - msrouter moved OFF lubuntu-agent into its own pod in namespace campaign
#     (ClusterIP :3000); its gateway keys live in secret campaign-msrouter.
#   - The gateway is PAUSED on purpose since 2026-10-08: msrouter.yaml pins
#     spec.replicas: 0 and finder.yaml sets FINDER_SCORING_ENABLED=false, so no
#     CV scoring tokens are spent. The finder still collects, dedupes and
#     produces; every candidate scores the neutral fallback 70, so the score
#     >= 60 CV filter is off and volume goes up. campaign-status.sh prints
#     "PAUSED (replicas=0)". To resume: set both values back (replicas 1,
#     FINDER_SCORING_ENABLED true) and rollout restart finder. Note APPLY_MODE
#     must stay shadow while the gateway is down.
#   - While APPLY_MODE=shadow, no browser opens and no application rows are
#     written: the worker consumes, re-checks dedupe, records job_state, and
#     commits. Safe next to the Mac campaign.
#   - Mongo data lives in /home/mstro/k3s-pod-data/mongodb (root user campaign,
#     keyfile from secret campaign-mongo). Kafka data in .../kafka.
#   - Postgres data is retired in .../postgres; delete the dir only after M8
#     verification against the 1809/272/102/2621 baseline passes.
#
# Diagnostics:
#   - A mongod pod that is Ready but serves writes to nobody means the replica
#     set never got a primary: check job/mongo-init logs (rs.initiate + a
#     db.hello() wait), like the msrouter Kafka tooling documents.
#   - Kafka "the port is open" is not readiness; probes run
#     kafka-broker-api-versions.sh for a real metadata request.
#   - Rising attempts + rebalance warnings in the worker log mean the poll
#     interval budget was exceeded: check MAX_POLL_INTERVAL_MS vs the agent
#     loop constants (pinned by PollBudgetTest).
#   - The DLQ topic campaign.jobs.dlq holding a message means a job is dead
#     (job_state.status=DEAD); inspect with
#     kubectl -n campaign exec kafka-0 -- /opt/kafka/bin/kafka-console-consumer.sh \
#       --bootstrap-server localhost:9092 --topic campaign.jobs.dlq --from-beginning \
#       --max-messages 1
