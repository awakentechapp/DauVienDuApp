# Dấu Viễn Du routing service

The Android app sends the current GPS coordinate to the authenticated API on the Contabo VPS. The API forwards it to a self-hosted OSRM service on `127.0.0.1:5000`; OSRM is not exposed publicly, and its request access log is disabled to keep route coordinates out of container logs.

## Prepare the Vietnam road graph on Contabo

Run this once on the VPS after the updated repository is present at `/opt/dauviendu-api`:

```bash
sudo bash /opt/dauviendu-api/deploy/routing/prepare-vietnam.sh
```

The script downloads the Vietnam OpenStreetMap extract from Geofabrik and prepares OSRM's MLD graph with two CPU threads. Preparation takes time and uses VPS CPU, memory, and disk. It does not stop the existing API or websites. The source `.osm.pbf` and generated graph stay outside the Git repository under `/opt/dauviendu-routing`.

## Start the private routing service and API

From `/opt/dauviendu-api`:

```bash
docker compose --profile routing up -d routing
docker compose up -d --build api
```

OSRM binds to `127.0.0.1:5000`; FlashPanel's public proxy should continue to expose only the existing API endpoints. The API route is `POST /api/v1/navigation/route` and requires a signed-in user JWT.

Check the private router from the VPS:

```bash
curl --fail 'http://127.0.0.1:5000/route/v1/driving/106.6990,10.7798;106.7000,10.7800?overview=false'
```

No Nginx or FlashPanel proxy rule for port `5000` is needed.
