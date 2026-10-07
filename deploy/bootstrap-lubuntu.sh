#!/usr/bin/env bash
# One-shot bootstrap for the distributed Kotlin campaign pipeline on the
# lubuntu k3s host. Run ON THE LUBUNTU HOST as root:
#
#   sudo bash /home/mstro/k3s-pod-data/agent-home/campaign-data/bootstrap.sh
#
# Does: clone bundle -> build 3 images -> import into k3s -> create secret ->
# deploy manifests -> wait for Postgres -> run tracker import job -> enable
# host sshd (so the agent can take over) -> print status.
set -euo pipefail

DATA=/home/mstro/k3s-pod-data/agent-home/campaign-data
SRC=$DATA/openclaw-job-search
NS=campaign
log() { echo -e "\n=== $1 ==="; }

log "clone branch from bundle"
rm -rf "$SRC"
git clone -b zcode/kotlin-distributed "$DATA/kotlin-dist.bundle" "$SRC"
cd "$SRC"
git log --oneline -1

log "build images (native compile: 10-25 min first run)"
docker build -f deploy/docker/Dockerfile.native --target finder -t localhost:5000/campaign-finder:v1 "$SRC/kotlin"
docker build -f deploy/docker/Dockerfile.native --target api    -t localhost:5000/campaign-api:v1 "$SRC/kotlin"
docker build -f deploy/docker/Dockerfile.jvm    --target worker -t localhost:5000/campaign-worker:v1 "$SRC/kotlin"
docker build -f deploy/docker/Dockerfile.jvm    --target importer -t localhost:5000/campaign-importer:v1 "$SRC/kotlin"

log "import images into k3s containerd"
for img in campaign-finder campaign-api campaign-worker campaign-importer; do
  docker save "localhost:5000/$img:v1" | k3s ctr images import -
done

log "namespace + db secret"
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
kubectl apply -f deploy/k8s/00-namespace.yaml
DB_PASS="$(openssl rand -hex 16)"
kubectl -n "$NS" create secret generic campaign-db --from-literal=password="$DB_PASS" --dry-run=client -o yaml | kubectl apply -f -

log "deploy manifests"
kubectl apply -f deploy/k8s/postgres.yaml
kubectl apply -f deploy/k8s/finder.yaml
kubectl apply -f deploy/k8s/worker.yaml
kubectl apply -f deploy/k8s/api.yaml

log "wait for postgres"
kubectl -n "$NS" rollout status statefulset/postgres --timeout=180s
sleep 5

log "import tracker.json + events.jsonl (idempotent)"
cat <<EOF | kubectl apply -f -
apiVersion: batch/v1
kind: Job
metadata:
  name: tracker-import
  namespace: $NS
spec:
  backoffLimit: 1
  template:
    spec:
      restartPolicy: Never
      containers:
        - name: import
          image: localhost:5000/campaign-importer:v1
          imagePullPolicy: IfNotPresent
          args: ["/data/tracker.json", "/data/events.jsonl"]
          env:
            - name: DB_URL
              value: jdbc:postgresql://postgres.$NS.svc.cluster.local:5432/campaign
            - name: DB_USER
              value: campaign
            - name: DB_PASSWORD
              valueFrom: { secretKeyRef: { name: campaign-db, key: password } }
          volumeMounts: [{ name: data, mountPath: /data }]
      volumes:
        - name: data
          hostPath: { path: $DATA, type: Directory }
EOF
kubectl -n "$NS" wait --for=condition=complete job/tracker-import --timeout=300s || true
kubectl -n "$NS" logs job/tracker-import || true

log "enable host sshd so the agent can take over"
systemctl enable --now ssh 2>/dev/null || systemctl enable --now sshd 2>/dev/null || echo "WARN: could not enable sshd"

log "status"
kubectl get pods -n "$NS"
kubectl get svc -n "$NS"
curl -s "http://localhost:30080/api/v1/stats" || echo "(api not up yet - check pods)"
echo
echo "BOOTSTRAP DONE - tell the agent sshd is on (or paste any errors above)."
