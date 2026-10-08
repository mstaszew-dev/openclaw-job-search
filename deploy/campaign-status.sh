#!/usr/bin/env bash
# Campaign pipeline status: run on the lubuntu host (or via ssh).
#   sudo bash /home/mstro/k3s-pod-data/agent-home/campaign-data/campaign-status.sh
set -uo pipefail
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
NS=campaign
PASS=$(kubectl -n $NS get secret campaign-mongo -o jsonpath='{.data.password}' 2>/dev/null | base64 -d)
MONGOSHELL() { kubectl -n "$NS" exec mongo-0 -- mongosh -u campaign -p "$PASS" --quiet --eval "$1"; }

echo "=== pods ==="
kubectl -n "$NS" get pods 2>&1

echo "=== gateway (msrouter) ==="
IP=$(kubectl -n "$NS" get svc msrouter -o jsonpath='{.spec.clusterIP}' 2>/dev/null)
curl -s -m 6 "http://$IP:3000/health/ready" || echo "GATEWAY DOWN - restart: kubectl -n $NS rollout restart deploy/msrouter"
echo

echo "=== pipeline stats ==="
curl -s -m 8 http://localhost:30080/api/v1/stats || echo "API UNREACHABLE"
echo

echo "=== job_state (bookkeeping; Kafka owns delivery) ==="
MONGOSHELL 'db.job_state.aggregate([{$group: {_id: "$status", n: {$sum: 1}}}, {$sort: {n: -1}}]).forEach(s => print(s._id + ": " + s.n));' 2>&1 | grep -v '^$'

echo "=== kafka consumer lag (apply-workers) ==="
kubectl -n "$NS" exec kafka-0 -- /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group apply-workers 2>&1 | head -6

echo "=== mongo replica state ==="
MONGOSHELL 'const h = db.hello(); print("set=" + h.setName + " primary=" + h.isWritablePrimary);' 2>&1 | grep -v '^$'

echo "=== last events ==="
MONGOSHELL 'db.events.find({}).sort({at: -1}).limit(5).forEach(e => print(new Date(e.at).toISOString().slice(5,16).replace("T"," ") + " " + e.action));' 2>&1 | grep -v '^$'

echo "=== recent warnings ==="
kubectl -n "$NS" logs deploy/finder --since=30m 2>&1 | grep -E "WARN|ERROR" | tail -3
kubectl -n "$NS" logs deploy/apply-worker --since=30m 2>&1 | grep -E "WARN|ERROR" | tail -3
echo "(empty = clean)"
