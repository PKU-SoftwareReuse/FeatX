"""Shared filesystem locations for the Python backend."""

from pathlib import Path


PACKAGE_ROOT = Path(__file__).resolve().parent
SOURCE_ROOT = PACKAGE_ROOT.parent
PROJECT_ROOT = SOURCE_ROOT.parent
WORKSPACE_ROOT = PROJECT_ROOT.parent
OUTPUT_ROOT = PROJECT_ROOT / "output"
MODELS_ROOT = WORKSPACE_ROOT / "models"
WORKSPACE_ENV_FILE = WORKSPACE_ROOT / ".env"


def repository_output_dir(repo_id: str | int) -> Path:
    """Return the persistent intermediate-output directory for a repository."""
    return OUTPUT_ROOT / str(repo_id)

