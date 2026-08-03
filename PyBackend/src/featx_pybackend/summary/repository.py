"""RepoSummary language dispatcher.

The language implementations are peers. Shared models, embeddings, clustering,
and description generation live in dedicated modules rather than this entrypoint.
"""

import os

from ..paths import OUTPUT_ROOT


IGNORED_ANALYSIS_DIRECTORIES = {
    ".git", "node_modules", "target", "build", "dist", "__pycache__", ".venv", "venv", "env",
    "preprocess1", "delombok", "preprocess2",
}


def _analysis_root(repo_root: str, source_directory: str, extension: str) -> str:
    conventional_root = os.path.join(repo_root, "src", "main", source_directory)
    if os.path.isdir(conventional_root) and _has_source_files(conventional_root, extension):
        return conventional_root
    return repo_root


def _has_source_files(root_path: str, extension: str) -> bool:
    if not os.path.isdir(root_path):
        return False
    for _root, dirs, files in os.walk(root_path):
        dirs[:] = [name for name in dirs if name not in IGNORED_ANALYSIS_DIRECTORIES]
        if any(file_name.endswith(extension) for file_name in files):
            return True
    return False


def main(project_id: str | int):
    all_projects_dir = os.path.normpath(os.environ["LOTM_REPO_PATH"])
    output_dir = str(OUTPUT_ROOT / str(project_id))
    repo_root = os.path.join(all_projects_dir, str(project_id))
    java_root = _analysis_root(repo_root, "java", ".java")
    python_root = _analysis_root(repo_root, "python", ".py")

    if _has_source_files(java_root, ".java"):
        from . import java_repository

        return java_repository.repo_summary(
            project_root=java_root,
            output_dir=output_dir,
            repo_id=str(project_id),
        )

    if _has_source_files(python_root, ".py"):
        from . import python_repository

        return python_repository.repo_summary(
            project_root=python_root,
            output_dir=output_dir,
        )

    raise RuntimeError(
        f"No supported Java or Python sources found for project {project_id} under {repo_root}"
    )
