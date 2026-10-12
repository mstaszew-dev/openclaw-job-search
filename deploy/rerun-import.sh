#!/usr/bin/env bash
# Targeted rerun: refresh source, rebuild ONLY worker+importer, re-import
# images, restart the worker (the :v1 tag never changes; without a rollout
# restart the pod keeps the old image), rerun the tracker-import job.
# Run on the lubuntu host as root (k3s ctr needs root).
set -euo pipefail
DATA=/home/mstro/k3s-pod-data/agent-home/campaign-data
SRC=$DATA/openclaw-job-search
NS=campaign
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml

rm -rf "$SRC"
git clone -q -b zcode/kotlin-distributed "$DATA/kotlin-dist.bundle" "$SRC"
cd "$SRC"

docker build -q -f deploy/docker/Dockerfile.jvm --target worker   -t localhost:5000/campaign-worker:v1 "$SRC/kotlin"
docker build -q -f deploy/docker/Dockerfile.jvm --target importer -t localhost:5000/campaign-importer:v1 "$SRC/kotlin"
docker save localhost:5000/campaign-worker:v1 | k3s ctr images import -
docker save localhost:5000/campaign-importer:v1 | k3s ctr images import -

kubectl -n "$NS" rollout restart deploy/apply-worker
kubectl -n "$NS" rollout status deploy/apply-worker --timeout=300s
kubectl -n "$NS" delete job tracker-import --ignore-not-found
kubectl apply -f deploy/k8s/tracker-import.yaml
if ! kubectl -n "$NS" wait --for=condition=complete job/tracker-import --timeout=300s; then
  kubectl -n "$NS" logs job/tracker-import --tail=30
  echo "tracker-import FAILED" >&2
  exit 1
fi
kubectl -n "$NS" logs job/tracker-import --tail=30
echo "IMPORT-RERUN DONE"
