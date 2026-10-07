#!/usr/bin/env bash
# Targeted rerun: refresh source, rebuild ONLY worker+importer, re-import
# images, rerun the tracker-import job. Services stay untouched.
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

kubectl -n "$NS" delete job tracker-import --ignore-not-found
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
kubectl -n "$NS" wait --for=condition=complete job/tracker-import --timeout=420s
kubectl -n "$NS" logs job/tracker-import
echo "IMPORT-RERUN DONE"
