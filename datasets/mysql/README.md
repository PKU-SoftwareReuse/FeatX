# FeatX MySQL Seed Data

This directory contains a curated MySQL seed dump for the Docker Compose
artifact. It is imported automatically when the MySQL container starts with an
empty data volume.

## Contents

- `featx_seed.sql`: data-only dump for the FeatX artifact tables.

## Source

The seed was exported from a validated FeatX Docker MySQL instance for the
NBlog case study:

- repository: `https://github.com/Naccl/NBlog`
- `project_info`: 1 row
- `modules`: 31 rows
- `features`: 75 rows
- `code_map`: 713 rows
- `graph_edge`: 2885 rows

The dump contains only the application tables used by FeatX:

- `project_info`
- `modules`
- `features`
- `code_map`
- `graph_edge`

It does not include API credentials, MySQL users, logs, or unrelated operational
tables.

## Regenerating the Seed

With the Docker Compose stack running:

```bash
docker exec featx_ae_verify-mysql-1 sh -lc 'MYSQL_PWD=featx mysqldump -h127.0.0.1 -ufeatx lotm --single-transaction --skip-triggers --set-gtid-purged=OFF --column-statistics=0 --no-tablespaces --no-create-info --complete-insert --hex-blob project_info modules features code_map graph_edge' > datasets/mysql/featx_seed.sql
```

After regenerating, scan for accidental secrets before submitting the artifact:

```bash
rg -n "sk-[A-Za-z0-9_-]{10,}|AKIA[0-9A-Z]{16}|BEGIN (RSA|OPENSSH|PRIVATE) KEY" datasets/mysql/featx_seed.sql
```
