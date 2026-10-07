#!/usr/bin/env bash
# Campaign pipeline status: run on the lubuntu host (or via ssh).
#   sudo bash /home/mstro/k3s-pod-data/agent-home/campaign-data/campaign-status.sh
set -uo pipefail
export KUBECONFIG=/etc/rancher/k3s/k3s.yaml
NS=campaign

echo "=== pods ==="
kubectl -n "$NS" get pods 2>&1

echo "=== gateway (msrouter) ==="
curl -s -m 6 http://localhost:30300/health/live || echo "GATEWAY DOWN - restart: ssh pod, cd ~/apps/msrouter && setsid env PORT=3000 MSROUTER_HEADLESS=1 DIRECTOR_INTERVAL_MINUTES=-1 DIRECTOR_AUTOSTART=false KAFKA_ENABLED=false VPN_ROTATION_INTERVAL_MINUTES=0 LAPTOP_ENABLED=false nohup node dist/main.js > /tmp/msrouter.log 2>&1 &"
echo

echo "=== pipeline stats ==="
curl -s -m 8 http://localhost:30080/api/v1/stats || echo "API UNREACHABLE"
echo

echo "=== queue states ==="
kubectl -n "$NS" exec postgres-0 -- psql -U campaign -d campaign -t -c \
  "SELECT state, count(*) FROM apply_tasks GROUP BY state ORDER BY 2 DESC;" 2>&1 | grep -v '^$'

echo "=== last events ==="
kubectl -n "$NS" exec postgres-0 -- psql -U campaign -d campaign -t -c \
  "SELECT to_char(at, 'MM-DD HH24:MI') || ' ' || action FROM events ORDER BY seq DESC LIMIT 5;" 2>&1 | grep -v '^$'

echo "=== recent warnings ==="
kubectl -n "$NS" logs deploy/finder --since=30m 2>&1 | grep -E "WARN|ERROR" | tail -3
kubectl -n "$NS" logs deploy/apply-worker --since=30m 2>&1 | grep -E "WARN|ERROR" | tail -3
echo "(empty = clean)"
