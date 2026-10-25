from fastapi import APIRouter, Body, Request
from .errors import AppError

router = APIRouter(prefix='/projects/{project_id}', tags=['summary'])


def refresh_task(app, project):
    task = project.get('analysis')
    if not task or not task.get('runId') or task['status'] in ('complete', 'failed'):
        return task
    try:
        task = app.state.reposummary.poll(task['runId'], project['id'])
    except AppError as exc:
        if exc.status != 404:
            raise
        task = {**task, 'status': 'failed', 'error': exc.message}
    if task['status'] == 'complete':
        try:
            app.state.shared.read_summary(project['id'], task['runId'])
            project['summaryRunId'] = task['runId']
        except AppError as exc:
            task = {**task, 'status': 'failed', 'error': exc.message}
    project['analysis'] = task
    project.pop('result', None)
    app.state.store.write(project)
    return task


def start_summary(app, project_id, hints=None):
    store = app.state.store
    with store.lock:
        project = store.read(project_id)
        task = refresh_task(app, project)
        if task and task['status'] in ('queued', 'running'):
            raise AppError(409, 'SUMMARY_RUNNING', '已有总结任务，请等待完成')
        task = app.state.reposummary.submit(project, hints)
        project['analysis'] = task
        project['summaryHints'] = hints or {}
        project.pop('summaryError', None)
        project.pop('result', None)
        store.write(project)
        return task


@router.post('/summary', status_code=202)
def submit(project_id: str, request: Request, body: dict | None = Body(default=None)):
    hints = (body or {}).get('hints') or {}
    if not isinstance(hints, dict):
        raise AppError(400, 'INVALID_HINTS', 'hints 必须是对象')
    return start_summary(request.app, project_id, hints)


@router.get('/summary/status')
def poll(project_id: str, request: Request):
    store = request.app.state.store
    with store.lock:
        project = store.read(project_id)
        task = refresh_task(request.app, project)
        try:
            summary = request.app.state.shared.read_summary(project_id)
            available_run = summary['runId']
        except AppError:
            available_run = None
        return {**(task or {'status': 'not_started', 'message': project.get('summaryError', '')}),
                'hasSummary': available_run is not None, 'summaryRunId': available_run}
