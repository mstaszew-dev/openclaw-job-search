#!/usr/bin/env bash
# One-shot bootstrap for the distributed Kotlin campaign pipeline on the
# lubuntu k3s host. Run ON THE LUBUNTU HOST as root:
#
#   sudo bash /home/mstro/k3s-pod-data/agent-home/campaign-data/bootstrap.sh
#
# Stack: MongoDB 7 (rs0) + Kafka 3.7 (KRaft) + 4 JVM services + msrouter.
# Postgres is gone: delete postgres.yaml, keep its data dir on disk until M8
# verification passes, then reclaim the space.
set -euo pipefail

DATA=/home/mstro/k3s-pod-data/agent-home/campaign-data
SRC=$DATA/openclaw-job-search
MSROUTER_SRC=/home/mstro/k3s-pod-data/agent-home/apps/msrouter
NS=campaign
log() { echo -e "\n=== $1 ==="; }

log "clone branch from bundle"
rm -rf "$SRC"
git clone -b zcode/kotlin-distributed "$DATA/kotlin-dist.bundle" "$SRC"
cd "$SRC"
git log --oneline -1

log "build images (JVM: fits the 7GB host; native deferred)"
docker build -f deploy/docker/Dockerfile.jvm-services --target finder-jvm -t localhost:5000/campaign-finder:v1 "$SRC/kotlin"
docker build -f deploy/docker/Dockerfile.jvm-services --target api-jvm    -t localhost:5000/campaign-api:v1 "$SRC/kotlin"
docker build -f deploy/docker/Dockerfile.jvm    --target worker -t localhost:5000/campaign-worker:v1 "$SRC/kotlin"
docker build -f deploy/docker/Dockerfile.jvm    --target importer -t localhost:5000/campaign-importer:v1 "$SRC/kotlin"

log "build msrouter image from its own clone (k3s-headless branch)"
git -C "$MSROUTER_SRC" fetch origin zcode-headless 2>/dev/null || true
git -C "$MSROUTER_SRC" checkout k3s-headless
# dist/ and node_modules/ are already built in that clone; no credentials used
docker build -f "$SRC/deploy/docker/Dockerfile.msrouter" -t localhost:5000/msrouter:k3s-headless-v1 "$MSROUTER_SRC"

log "import images into k3s containerd"
for img in campaign-finder campaign-api campaign-worker campaign-importer msrouter; do
  tag=v1
  [ "$img" = msrouter ] && tag=k3s-headless-v1
  docker save "localhost:5000/$img:$tag" | k3s ctr images import -
done

log "namespace + secrets"
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
kubectl apply -f deploy/k8s/00-namespace.yaml

# Mongo root password: the mongod image entrypoint reads it only when the
# data dir is empty, so it is generated once and kept in $DATA. The keyfile
# must be exactly one line (mongod rejects a multi-line file).
MONGO_PASS="$(cat $DATA/.mongo-pass 2>/dev/null || openssl rand -hex 24)"
echo -n "$MONGO_PASS" > $DATA/.mongo-pass && chmod 600 $DATA/.mongo-pass
MONGO_KEY="$(cat $DATA/.mongo-keyfile 2>/dev/null || openssl rand -hex 256)"
echo -n "$MONGO_KEY" > $DATA/.mongo-keyfile && chmod 600 $DATA/.mongo-keyfile
kubectl -n "$NS" create secret generic campaign-mongo \
  --from-literal=password="$MONGO_PASS" \
  --from-literal=keyfile="$MONGO_KEY" \
  --dry-run=client -o yaml | kubectl apply -f -

# Kafka KRaft needs a stable cluster id or the storage format changes on every
# restart and the no-op second boot fails.
KAFKA_ID="$(cat $DATA/.kafka-cluster-id 2>/dev/null || echo CLUSTER-$(openssl rand -hex 10 | tr 'a-f' 'A-F'))"
echo -n "$KAFKA_ID" > $DATA/.kafka-cluster-id
kubectl -n "$NS" create secret generic campaign-kafka \
  --from-literal=cluster-id="$KAFKA_ID" \
  --dry-run=client -o yaml | kubectl apply -f -

# PROVIDER KEYS: the gateway's .env file (from the Mac msrouter checkout)
# becomes a Secret, so the keys stop sitting in the persistent home dir.
if [ -f $DATA/msrouter.env ]; then
  kubectl -n "$NS" create secret generic campaign-msrouter \
    --from-env-file=$DATA/msrouter.env --dry-run=client -o yaml | kubectl apply -f -
else
  echo "MISSING $DATA/msrouter.env - create it from the Mac msrouter .env before deploying" >&2
  exit 1
fi

log "old postgres deployment is replaced, not deleted"
kubectl -n "$NS" scale deploy/finder deploy/apply-worker deploy/campaign-api --all --replicas=0 --ignore-not-found
kubectl -n "$NS" delete statefulset postgres --ignore-not-found 2>/dev/null && echo "postgres statefulset deleted (data dir /home/mstro/k3s-pod-data/postgres kept for M8)" || true

log "deploy manifests"
kubectl apply -f deploy/k8s/mongodb.yaml
kubectl apply -f deploy/k8s/kafka.yaml
kubectl apply -f deploy/k8s/msrouter.yaml
kubectl apply -f deploy/k8s/finder.yaml
kubectl apply -f deploy/k8s/worker.yaml
kubectl apply -f deploy/k8s/api.yaml

log "wait for mongo, initiate the replica set"
kubectl -n "$NS" rollout status statefulset/mongo --timeout=300s
kubectl -n "$NS" delete job mongo-init --ignore-not-found
kubectl apply -f deploy/k8s/mongodb.yaml
kubectl -n "$NS" wait --for=condition=complete job/mongo-init --timeout=240s || \
  kubectl -n "$NS" logs job/mongo-init --tail=20

log "wait for kafka, create topics"
kubectl -n "$NS" rollout status statefulset/kafka --timeout=300s
kubectl -n "$NS" delete job kafka-init --ignore-not-found
kubectl apply -f deploy/k8s/kafka.yaml
kubectl -n "$NS" wait --for=condition=complete job/kafka-init --timeout=240s || \
  kubectl -n "$NS" logs job/kafka-init --tail=20

log "import tracker.json + events.jsonl (idempotent)"
kubectl -n "$NS" delete job tracker-import --ignore-not-found
kubectl apply -f deploy/k8s/tracker-import.yaml
kubectl -n "$NS" wait --for=condition=complete job/tracker-import --timeout=300s || true
kubectl -n "$NS" logs job/tracker-import --tail=30 || true

log "enable host sshd so the agent can take over"
systemctl enable --now ssh 2>/dev/null || systemctl enable --now sshd 2>/dev/null || echo "WARN: could not enable sshd"

log "status"
kubectl get pods -n "$NS"
kubectl get svc -n "$NS"
curl -s "http://localhost:30080/api/v1/stats" || echo "(api not up yet - check pods)"
echo
echo "BOOTSTRAP DONE - tell the agent sshd is on (or paste any errors above)."
