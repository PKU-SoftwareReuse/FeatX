# FeatX Artifact Data

This directory contains the data shipped with the ASE 2026 FeatX artifact. The
data support two reviewer activities:

- inspection of the feature-editing commit dataset used in the paper's replay
  study; and
- inspection of the seeded NBlog case study used by the Docker Compose demo.

## Commit Replay Dataset

Path: `datasets/commits/dataset.json`

This JSON file contains the 38 feature-editing commits used for the replay
study. The top-level object has one field:

- `dataset`: list of project-level entries.

Each project entry contains:

- `project_name`: project identifier.
- `issues`: feature-editing examples collected for that project.

Each issue entry contains:

- `id`: issue identifier within the project.
- `issue`: original issue or commit label, often including a commit URL.
- `description`: normalized natural-language feature-editing description.
- `methods`: methods associated with the feature-editing change.

Validation commands:

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

This data-only seed dump initializes the Docker Compose MySQL service with the
precomputed NBlog feature map:

- `project_info`: 1 row
- `modules`: 31 rows
- `features`: 75 rows
- `code_map`: 713 rows
- `graph_edge`: 2885 rows

The MySQL Docker image imports this file automatically when the MySQL data
volume is empty. The seed contains application data only; it does not contain
API credentials, MySQL user definitions, logs, or unrelated operational tables.

## Repository Snapshot

Path: `datasets/repos/12`

This is the NBlog source snapshot matching `project_info.id = 12` in the MySQL
seed. It contains:

- `src/main/java`: source files shown and edited by FeatX.
- `preprocess1/main/java`: first normalization pass.
- `delombok/main/java`: delombok output.
- `preprocess2/main/java`: final normalized source used by graph analysis.

The backend Docker image copies this directory into `/workspace/repos/12`, so
reviewers can inspect the seeded project without cloning NBlog manually.
