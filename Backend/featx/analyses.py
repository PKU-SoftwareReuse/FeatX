from fastapi import APIRouter, Request
from .errors import AppError

router = APIRouter(prefix='/projects/{project_id}', tags=['analyses'])


@router.post('/analyses', status_code=202)
def submit(project_id: str, request: Request):
    store, client = request.app.state.store, request.app.state.reposummary
    with store.lock:
        project = store.read(project_id)
        task = project['analysis']
        if task and task['status'] in ('queued', 'running'):
            raise AppError(409, 'ANALYSIS_ACTIVE', '已有分析任务，请先查询任务状态')
        task = client.submit(project)  # No state change on a rejected submission.
        project.update(analysis=task, result=None)
        store.write(project)
        return task


@router.get('/analysis')
def poll(project_id: str, request: Request):
    store, client = request.app.state.store, request.app.state.reposummary
    with store.lock:
        project = store.read(project_id)
        task = project['analysis']
        if not task:
            return {'status': 'not_started'}
        if task['status'] == 'failed' or (task['status'] == 'complete' and project['result'] is not None):
            return task
        task = client.poll(task['taskId'])
        if task['status'] == 'complete':
            try:
                result = client.result(task['taskId'])
                if result['source'] != project['source']:
                    raise AppError(502, 'INVALID_REPOSUMMARY_RESULT', '分析结果来源与登记项目不一致')
            except AppError as exc:
                if exc.code == 'INVALID_REPOSUMMARY_RESULT':
                    project['analysis'] = {**task, 'status': 'failed', 'error': exc.message}
                    store.write(project)
                raise
            project['result'] = result
        project['analysis'] = task
        store.write(project)
        return task
