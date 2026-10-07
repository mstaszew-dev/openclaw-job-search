# Deploy runbook (lubuntu k3s)
#
# Prereqs (once, on the lubuntu host as a user with k3s + docker):
#   export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
#   kubectl apply -f deploy/k8s/00-namespace.yaml
#   # DB password secret (never committed):
#   kubectl -n campaign create secret generic campaign-db \
#     --from-literal=password='<generate-one>'
#
# Build + import images (branch zcode/kotlin-distributed checked out on the host):
#   docker build -f deploy/docker/Dockerfile.native \
#     --target finder -t localhost:5000/campaign-finder:v1 .
#   docker build -f deploy/docker/Dockerfile.native \
#     --target api   -t localhost:5000/campaign-api:v1 .
#   docker build -f deploy/docker/Dockerfile.jvm    \
#     -t localhost:5000/campaign-worker:v1 .
#   for img in campaign-finder campaign-api campaign-worker; do
#     echo <sudo-pass> | sudo -S bash -c \
#       "docker save localhost:5000/$img:v1 | k3s ctr images import -"
#   done
#
# Deploy (shadow mode default):
#   kubectl apply -f deploy/k8s/postgres.yaml
#   kubectl apply -f deploy/k8s/finder.yaml
#   kubectl apply -f deploy/k8s/worker.yaml
#   kubectl apply -f deploy/k8s/api.yaml
#
# Import the live tracker (idempotent; run after Postgres is ready):
#   scp the live tracker.json + events.jsonl to the host, then:
#   DB_URL='jdbc:postgresql://localhost:5432/campaign' \
#   DB_USER=campaign DB_PASSWORD=... \
#   ./gradlew :import-tracker:run --args="/tmp/tracker.json /tmp/events.jsonl"
#   (port-forward first: kubectl -n campaign port-forward svc/postgres 5432:5432)
#
# Verify:
#   curl http://<laptop-ip>:30080/api/v1/stats      # submitted/queued/skips
#   kubectl -n campaign logs deploy/finder  --tail=50
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
#     finder and worker attach to its CDP on NodePort 30922 only.
#   - msrouter on that pod serves the LLM gateway on NodePort 30300.
#   - While APPLY_MODE=shadow, no application rows are written: workers claim,
#     re-check dedup, and release. Safe next to the Mac campaign.
#   - Postgres data lives in /home/mstro/k3s-pod-data/postgres (hostPath).
