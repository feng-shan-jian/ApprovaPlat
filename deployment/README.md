# ApprovaPlat Deployment Assets

This directory contains the production configuration, systemd unit, Nginx configuration, release
gates, and deployment samples that must ship with an ApprovaPlat release.

The maintained installation and release procedures are documented in
[`docs/operations/workflow-deployment.md`](../docs/operations/workflow-deployment.md) and
[`docs/operations/workflow-release.md`](../docs/operations/workflow-release.md).

## Database Release Contract

- `sql/release-order.txt` is the core fresh-install order and produces exactly 94 tables, including
  27 `wf_*` tables.
- `sql/integration/8.0.0__sms_oss.sql` is optional. Its four integration tables are excluded from the
  workflow core order, the 94-table schema contract, and the 35-check database release gate.
- The current development baseline supports fresh installation only. A failed partial installation
  must be discarded and recreated from an empty schema; no development upgrade path is shipped.

## Token Secret Lifecycle

The standard single-node deployment requires no manual token-secret generation:

1. `ruoyi-backend.service` creates `/var/lib/ruoyi-secrets` as a private persistent systemd state directory.
2. On the first successful backend start, the application creates a random 64-byte HS512 secret at
   `/var/lib/ruoyi-secrets/token-secret` using an atomic write.
3. Later starts reuse the same file, so deployments and restarts do not rotate the signing key.
4. `RUOYI_TOKEN_SECRET` remains optional and takes priority when an operator injects a managed secret.

The secret file is runtime state. It must never be committed, copied into a release bundle, or stored
inside the application JAR. Include `/var/lib/ruoyi-secrets/token-secret` in encrypted host backups and
preserve it when replacing application releases.

For multiple backend nodes, inject the same `RUOYI_TOKEN_SECRET` into every node through the deployment
secret manager. Node-local generated secrets must not be used behind a shared load balancer because a
token signed by one node would be rejected by another node.

## Configuration Contract

- `RUOYI_TOKEN_SECRET`: optional Base64/Base64URL value whose decoded content is at least 64 bytes.
- `RUOYI_TOKEN_SECRET_FILE`: optional persistent file path; defaults to
  `/var/lib/ruoyi-secrets/token-secret` in the production configuration.
- `token.secret-file.enabled`: remains `true` in the supported production bundle so a standard
  single-node installation starts without secret provisioning.

The release gate validates these production defaults and rejects embedded secrets, unexpected secret
paths, and disabled persistent generation.
