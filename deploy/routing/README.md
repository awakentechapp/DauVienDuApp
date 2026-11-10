# Dấu Viễn Du routing service

The Android app sends the current GPS coordinate and selected travel mode to the authenticated API on the Contabo VPS. The API forwards car and motorcycle requests to OSRM on `127.0.0.1:5000`, and walking requests to a separate foot-profile OSRM service on `127.0.0.1:5001`. OSRM is not exposed publicly, and request access logs are disabled to keep route coordinates out of container logs. Motorcycle routes exclude motorways; walking routes use the dedicated foot graph.

## Prepare the Vietnam road graph on Contabo

Run this once on the VPS after the updated repository is present at `/opt/dauviendu-api`. The script reuses the existing car graph and prepares an additional walking graph under `/opt/dauviendu-routing/foot`:

```bash
sudo bash /opt/dauviendu-api/deploy/routing/prepare-vietnam.sh
```

The script downloads the Vietnam OpenStreetMap extract from Geofabrik and prepares the car graph plus a separate pedestrian graph with two CPU threads. Preparation takes time and uses VPS CPU, memory, and disk. It does not stop the existing API or websites. The source `.osm.pbf` and generated graphs stay outside the Git repository under `/opt/dauviendu-routing`.

## Start the private routing service and API

From `/opt/dauviendu-api`:

```bash
docker compose --profile routing up -d routing routing-foot
docker compose up -d --build api
```

Both OSRM instances bind only to `127.0.0.1`; FlashPanel's public proxy should continue to expose only the existing API endpoints. The API route is `POST /api/v1/navigation/route` and requires a signed-in user JWT. Omit `travelMode` or send `car` for a car route; use `motorcycle` for a route that excludes motorways; use `walking` for pedestrian paths.

Check the private router from the VPS:

```bash
curl --fail 'http://127.0.0.1:5000/route/v1/driving/106.6990,10.7798;106.7000,10.7800?overview=false'
curl --fail 'http://127.0.0.1:5001/route/v1/foot/106.6990,10.7798;106.7000,10.7800?overview=false'
```

No Nginx or FlashPanel proxy rule for ports `5000` or `5001` is needed.
