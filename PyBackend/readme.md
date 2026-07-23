# PyBackend

PyBackend is the Python analysis service used by FeatX. It contains RepoSummary,
FocusGraph ranking, Python static-analysis tooling, model serving, and embedding
cache management.

For ASE artifact evaluation, reviewers should normally run RepoSummary through
the top-level Docker Compose deployment. The backend container installs the
Python dependencies and invokes RepoSummary as part of FeatX workflows. A
separate Python virtual environment is not required for the recommended review
path.

## Component-Level Check

From this directory:

```bash
python3 -m compileall -q src
```

Expected result: the command exits with status 0.

## Manual Deployment

Manual deployment is intended for development or customized environments, not
for the primary artifact-evaluation path.

Install Python dependencies:

```bash
pip install -r requirements.txt
```

Create a local `.env` file when running RepoSummary outside Docker:

```env
LOTM_REPO_PATH=/path/to/featx/repos
DB_HOST=127.0.0.1
DB_PORT=3306
DB_NAME=lotm
DB_USER=featx
DB_PASSWORD=featx

OPENAI_BASE_URL=https://api.deepseek.com
OPENAI_API_KEY=
OPENAI_API_MODEL=deepseek-v4-pro
```

For manual FeatX deployment, `LOTM_REPO_PATH` and the database settings must
match the Java backend configuration in
`JavaBackend/src/main/resources/application.properties`. Do not commit real API keys
or reviewer credentials.

## Main Outputs

When run as part of feature extraction, RepoSummary produces structured
intermediate files such as:

- file dependency matrices;
- method metadata and method-call matrices; and
- feature/module summaries used to populate FeatX's database.

These files are implementation intermediates. The artifact's reviewer-facing
seed data are provided in `datasets/mysql/featx_seed.sql` and
`datasets/repos/12`.
