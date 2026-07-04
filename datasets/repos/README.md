# FeatX Repository Snapshots

This directory contains repository snapshots used by the Docker Compose
artifact. Numeric directory names correspond to `project_info.id` values in the
MySQL seed.

## Included Snapshot

- `12/`: NBlog case-study repository snapshot for `project_info.id = 12`.

The snapshot contains the original source tree plus FeatX preprocessing outputs:

- `src/main/java`: source files shown and edited by FeatX.
- `preprocess1/main/java`: first normalization pass.
- `delombok/main/java`: delombok output.
- `preprocess2/main/java`: final normalized source used by graph analysis.

The backend image copies this directory into `/workspace/repos`. When the
`featx-repos` Docker volume is empty, Docker initializes the volume from the
image contents, so reviewers can inspect the seeded NBlog project without
cloning it manually.

If the volume already exists and reviewers want to restore the packaged
snapshot, run:

```bash
docker compose down -v
docker compose up -d
```
