# AR 3D model processing

Admin and Super Admin capture 12–32 overlapping JPEG/PNG photos for an AR card. The API sends these photos to the self-hosted NodeODM worker, which uses ODM's experimental AI background-removal masks and reconstructs a textured GLB. The API stores that GLB in the private Contabo bucket and gives the Android AR renderer a short-lived signed URL. Photos and models are not sent to a third-party reconstruction service.

## VPS setup

Run NodeODM on the same VPS as the API:

```sh
docker compose -f backend/deploy/ar-model-worker.compose.yml up -d
```

The compose file publishes port 3000 on loopback only. Keep the API settings aligned with that address:

```text
ArModelProcessing__Enabled=true
ArModelProcessing__NodeOdmUrl=http://127.0.0.1:3000
```

The API process must be allowed to accept about 130 MB request bodies and its reverse proxy must permit the same upload size. The worker needs available disk space and RAM while processing image sets. Adjust `MaxUploadBytes`, `MaxModelBytes`, and the reverse proxy limit together if you change the app limits.

The API startup database initialization runs the idempotent AR migration when `Database:ApplyMigrationsAndSeedOnStartup` is enabled. It adds model status, object key, worker task ID, and error columns to `ar.ar_info_cards`.

## Capture guidance

Capture a full circle around one stationary object, keeping the object in focus and in frame. Use bright, even light and overlapping viewpoints. A plain background with clear contrast against the object helps automatic masking. Avoid moving people, blank reflective surfaces, blur, and large changes in distance. Background removal is experimental and can miss edges or remove parts that blend into the background; more images do not guarantee a usable reconstruction.
