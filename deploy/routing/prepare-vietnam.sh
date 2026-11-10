#!/usr/bin/env bash
set -euo pipefail

DATA_DIR="${ROUTING_DATA_DIR:-/opt/dauviendu-routing}"
IMAGE="ghcr.io/project-osrm/osrm-backend:v26.10.0-debian"
PBF="$DATA_DIR/vietnam-latest.osm.pbf"

mkdir -p "$DATA_DIR"

if [[ ! -s "$PBF" ]]; then
  curl --fail --location --retry 3 \
    "https://download.geofabrik.de/asia/vietnam-latest.osm.pbf" \
    --output "$PBF.part"
  mv "$PBF.part" "$PBF"
fi

docker pull "$IMAGE"

run_osrm() {
  docker run --rm --cpus=2 --volume "$DATA_DIR:/data" "$IMAGE" "$@"
}

if [[ ! -s "$DATA_DIR/vietnam-latest.osrm.mldgr" ]]; then
  run_osrm osrm-extract --threads 2 -p /opt/car.lua /data/vietnam-latest.osm.pbf
  run_osrm osrm-partition --threads 2 /data/vietnam-latest.osrm
  run_osrm osrm-customize --threads 2 /data/vietnam-latest.osrm
fi

mkdir -p "$DATA_DIR/foot"
if [[ ! -s "$DATA_DIR/foot/vietnam-latest.osrm.mldgr" ]]; then
  run_osrm osrm-extract --threads 2 -p /opt/foot.lua /data/vietnam-latest.osm.pbf --output /data/foot/vietnam-latest
  run_osrm osrm-partition --threads 2 /data/foot/vietnam-latest.osrm
  run_osrm osrm-customize --threads 2 /data/foot/vietnam-latest.osrm
fi

echo "OSRM Vietnam data is ready in $DATA_DIR"
echo "Start car and walking routers with: docker compose --profile routing up -d routing routing-foot"
