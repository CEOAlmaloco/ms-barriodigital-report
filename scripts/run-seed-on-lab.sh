#!/bin/bash
set -eu
javac -cp "/tmp/ojdbc-libs/*" /tmp/SeedReport.java -d /tmp
sudo docker cp /tmp/SeedReport.class bd-report:/tmp/SeedReport.class
sudo docker exec bd-report sh -c 'cd /tmp && java -cp "/tmp/ojdbc-libs/*:." SeedReport'
sudo docker exec bd-bff curl -s -H "X-User-Id: oid" -H "X-User-Roles: Admin" -H "X-User-Name: Admin" \
  "http://report:8086/api/report/kpis?range=last7d" > /tmp/kpis.json
python3 <<'PY'
import json
d = json.load(open("/tmp/kpis.json"))
print("resolved", d.get("resolvedCount"))
print("active", d.get("activeByStatus"))
print("series", len(d.get("requestsOverTime") or []))
print("lastEvent", d.get("lastEventAt"))
PY
