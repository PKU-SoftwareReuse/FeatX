# FeatX MySQL Seed Data

This directory contains the curated MySQL seed dump used by the Docker Compose
artifact. The seed is imported automatically when the MySQL container starts
with an empty data volume.

## Contents

- `featx_seed.sql`: data-only dump for the FeatX artifact tables.

## Source And Scope

The seed was exported from a validated FeatX Docker MySQL instance for the
NBlog case study:

- repository: `https://github.com/Naccl/NBlog`
- `project_info`: 1 row
- `modules`: 31 rows
- `features`: 75 rows
- `code_map`: 713 rows
- `graph_edge`: 2885 rows

The dump contains only the FeatX application tables required for the seeded
case study:

- `project_info`
- `modules`
- `features`
- `code_map`
- `graph_edge`

It does not include API credentials, MySQL user accounts, logs, or unrelated
operational tables.

## Validation

With the Docker Compose stack running, reviewers can validate the imported
counts from the repository root:

```bash
docker compose exec -e MYSQL_PWD=featx mysql mysql -h127.0.0.1 -ufeatx lotm \
  -e "SELECT COUNT(*) AS projects FROM project_info; SELECT COUNT(*) AS modules FROM modules; SELECT COUNT(*) AS features FROM features; SELECT COUNT(*) AS code_map_entries FROM code_map; SELECT COUNT(*) AS graph_edges FROM graph_edge;"
```

## Regenerating The Seed

This section is for artifact maintainers. With a validated Docker Compose stack
running, regenerate the dump with:

```bash
docker exec featx_ae_verify-mysql-1 sh -lc 'MYSQL_PWD=featx mysqldump -h127.0.0.1 -ufeatx lotm --single-transaction --skip-triggers --set-gtid-purged=OFF --column-statistics=0 --no-tablespaces --no-create-info --complete-insert --hex-blob project_info modules features code_map graph_edge' > datasets/mysql/featx_seed.sql
```

After regenerating, scan for accidental secrets before submitting the artifact:

```bash
rg -n "sk-[A-Za-z0-9_-]{10,}|AKIA[0-9A-Z]{16}|BEGIN (RSA|OPENSSH|PRIVATE) KEY" datasets/mysql/featx_seed.sql
```
