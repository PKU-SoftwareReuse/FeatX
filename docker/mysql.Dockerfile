FROM mysql:8.0

COPY Backend/src/main/java/cn/edu/pku/lixutian/dao/update-schema.sql /docker-entrypoint-initdb.d/001-schema.sql
COPY datasets/mysql/featx_seed.sql /docker-entrypoint-initdb.d/002-seed.sql
RUN chmod 0644 /docker-entrypoint-initdb.d/001-schema.sql /docker-entrypoint-initdb.d/002-seed.sql
