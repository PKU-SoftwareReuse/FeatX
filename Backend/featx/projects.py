from datetime import datetime, timezone
from uuid import uuid4
from secrets import compare_digest
from fastapi.responses import FileResponse
from .errors import AppError
from fastapi import APIRouter, Request
from .models import ProjectInput, Source
from .errors import AppError
from .git_import import GitImport

router = APIRouter(prefix='/projects', tags=['projects'])


def public(record, shared=None):
    result = {k: v for k, v in record.items() if k not in ('result', 'uploadToken')}
    result['description'] = ''
    if shared is not None:
        try:
            result['description'] = shared.read_summary(record['id'], record.get('summaryRunId'))['description']
        except AppError:
            # An absent/invalid summary must not make the project list unavailable.
            pass
    return result


@router.get('')
def list_projects(request: Request):
    store = request.app.state.store
    with store.lock:
        return [public(p, request.app.state.shared) for p in store.list()]


@router.post('/git/check')
def check_git(body: Source):
    return GitImport(body.url).check()


@router.post('', status_code=201)
def create_project(body: ProjectInput, request: Request):
    # Complete clone/checkout before publishing a project or starting its summary.
    importer = GitImport(body.source.url)
    project_id = uuid4().hex
    directory = request.app.state.shared.directory(project_id)
    directory.mkdir(exist_ok=False)
    source = directory / 'source'
    store = request.app.state.store
    saved = False
    try:
        git_info = importer.clone(source, body.source.commitId)
        record = {'id': project_id, **body.model_dump(mode='json'), 'git': git_info,
                  'createdAt': datetime.now(timezone.utc).isoformat(), 'analysis': None}
        with store.lock:
            store.write(record)
        saved = True
        from .analyses import start_summary
        try:
            start_summary(request.app, project_id)
        except AppError as exc:
            with store.lock:
                record = store.read(project_id)
                record['summaryError'] = exc.message
                store.write(record)
        return public(store.read(project_id), request.app.state.shared)
    finally:
        if not saved:
            request.app.state.shared.delete(project_id)


@router.get('/{project_id}')
def get_project(project_id: str, request: Request):
    store = request.app.state.store
    with store.lock:
        return public(store.read(project_id), request.app.state.shared)


@router.delete('/{project_id}', status_code=204)
def delete_project(project_id: str, request: Request):
    # Deletes registration, result and any owned upload. Never touches an external Git repo or remote task.
    store = request.app.state.store
    with store.lock:
        from .analyses import refresh_task
        project = store.read(project_id)
        task = refresh_task(request.app, project)
        if task and task['status'] in ('queued', 'running'):
            raise AppError(409, 'SUMMARY_RUNNING', '请等待总结任务结束后再删除项目')
        request.app.state.shared.delete(project_id)
        store.delete(project_id)


@router.get('/{project_id}/archive')
def download_archive(project_id: str, request: Request, token: str = ''):
    store = request.app.state.store
    with store.lock:
        project = store.read(project_id)
        if project['source'].get('kind') != 'upload':
            raise AppError(404, 'NO_UPLOAD', '此项目不是上传项目')
        if not token or not compare_digest(token.encode(), project.get('uploadToken', '').encode()):
            raise AppError(403, 'DOWNLOAD_DENIED', '下载凭证无效')
        archive = store.root.parent / 'uploads' / f'{project_id}.zip'
        if not archive.is_file():
            raise AppError(404, 'UPLOAD_MISSING', '上传文件不存在')
        return FileResponse(archive, media_type='application/zip', filename=project['source']['filename'])
