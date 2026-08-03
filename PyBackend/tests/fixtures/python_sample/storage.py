"""In-memory task persistence for the RepoSummary fixture."""

TASKS: dict[int, dict[str, object]] = {}


def save_task(task_id: int, title: str) -> dict[str, object]:
    task = {"id": task_id, "title": title, "completed": False}
    TASKS[task_id] = task
    return task


def load_task(task_id: int) -> dict[str, object] | None:
    return TASKS.get(task_id)


def list_tasks(completed: bool | None = None) -> list[dict[str, object]]:
    tasks = list(TASKS.values())
    if completed is None:
        return tasks
    return [task for task in tasks if task["completed"] is completed]
