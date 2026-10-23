from datetime import datetime, timezone
from uuid import uuid4
from secrets import compare_digest
from fastapi.responses import FileResponse
from .errors import AppError
from fastapi import APIRouter, Request
from .models import ProjectInput

router = APIRouter(prefix='/projects', tags=['projects'])


def public(record):
    return {k: v for k, v in record.items() if k not in ('result', 'uploadToken')}


@router.get('')
def list_projects(request: Request):
    store = request.app.state.store
    with store.lock:
        return [public(p) for p in store.list()]


@router.post('', status_code=201)
def create_project(body: ProjectInput, request: Request):
    record = {'id': uuid4().hex, **body.model_dump(mode='json'),
              'createdAt': datetime.now(timezone.utc).isoformat(), 'analysis': None, 'result': None}
    store = request.app.state.store
    with store.lock:
        store.write(record)
    return public(record)


@router.get('/{project_id}')
def get_project(project_id: str, request: Request):
    store = request.app.state.store
    with store.lock:
        return public(store.read(project_id))


@router.delete('/{project_id}', status_code=204)
def delete_project(project_id: str, request: Request):
    # Deletes registration, result and any owned upload. Never touches an external Git repo or remote task.
    store = request.app.state.store
    with store.lock:
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
