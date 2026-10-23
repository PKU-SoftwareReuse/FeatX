"""Explicit extension points; no code execution or Git writes."""
from fastapi import APIRouter, Request
from .errors import AppError

router = APIRouter(prefix='/projects/{project_id}', tags=['future operations'])


@router.post('/edits')
@router.post('/agent/runs')
@router.get('/diff')
@router.post('/git/operations')
def not_implemented(project_id: str, request: Request):
    store = request.app.state.store
    with store.lock:
        store.read(project_id)
    raise AppError(501, 'NOT_IMPLEMENTED', '此功能仅保留框架，尚未实现')
