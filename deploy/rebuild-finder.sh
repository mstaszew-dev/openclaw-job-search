#!/usr/bin/env bash
# Rebuild the JVM finder image (with node + @playwright/mcp), import it, and
# restart the finder deployment. Used after the JVM-fallback switch.
set -euo pipefail
DATA=/home/mstro/k3s-pod-data/agent-home/campaign-data
SRC=$DATA/openclaw-job-search
NS=campaign
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml

rm -rf "$SRC"
git clone -q -b zcode/kotlin-distributed "$DATA/kotlin-dist.bundle" "$SRC"
cd "$SRC"
docker build -f deploy/docker/Dockerfile.jvm-services --target finder-jvm -t localhost:5000/campaign-finder:v1 "$SRC/kotlin"
docker save localhost:5000/campaign-finder:v1 | k3s ctr images import -
kubectl -n "$NS" rollout restart deploy/finder
kubectl -n "$NS" rollout status deploy/finder --timeout=180s
sleep 20
kubectl -n "$NS" logs deploy/finder --tail=5
echo "FINDER-REBUILD DONE"
