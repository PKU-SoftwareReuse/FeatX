# FeatX Artifact Data

This directory contains the data shipped with the ASE 2026 artifact package.

## Commit Replay Dataset

Path: `datasets/commits/dataset.json`

This JSON file contains the feature-editing commits used for the replay study.
The top-level object has one key, `dataset`, whose value is a list of projects.
Each project contains:

- `project_name`: project identifier.
- `issues`: feature-editing examples for that project.

Each issue contains:

- `id`: issue id within the project.
- `issue`: original issue or commit label, often with a commit URL.
- `description`: short feature-editing description.
- `methods`: methods related to the feature-editing change.

Expected counts:

```bash
jq '[.dataset[] | .issues | length] | add' datasets/commits/dataset.json
jq -r '.dataset[] | "\(.project_name)\t\(.issues | length)"' datasets/commits/dataset.json
```

Expected output:

```text
38
FlappyBird  2
PlayEdu     15
NBlog       21
```

## MySQL Seed

Path: `datasets/mysql/featx_seed.sql`

This is a data-only seed dump for the Docker Compose MySQL service. It contains
the NBlog feature map already computed by FeatX:

- `project_info`: 1 row
- `modules`: 31 rows
- `features`: 75 rows
- `code_map`: 713 rows
- `graph_edge`: 2885 rows

The MySQL Docker image imports this file automatically when the MySQL data
volume is empty.

## Repository Snapshot

Path: `datasets/repos/12`

This is the NBlog source snapshot matching `project_info.id = 12` in the MySQL
seed. It contains:

- `src/main/java`: source files shown and edited by FeatX.
- `preprocess1/main/java`: first normalization pass.
- `delombok/main/java`: delombok output.
- `preprocess2/main/java`: final normalized source used by graph analysis.

The backend Docker image copies this directory into `/workspace/repos/12`, so
reviewers can inspect the seeded project without cloning it manually.
