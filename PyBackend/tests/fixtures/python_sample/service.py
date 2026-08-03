"""Task-management behavior for the RepoSummary fixture."""

from .storage import list_tasks, load_task, save_task


def create_task(task_id: int, title: str) -> dict[str, object]:
    if not title.strip():
        raise ValueError("title is required")
    return save_task(task_id, title.strip())


def complete_task(task_id: int) -> dict[str, object]:
    task = load_task(task_id)
    if task is None:
        raise KeyError(task_id)
    task["completed"] = True
    return task


def pending_tasks() -> list[dict[str, object]]:
    return list_tasks(completed=False)
