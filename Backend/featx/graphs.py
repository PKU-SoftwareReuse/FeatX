from fastapi import APIRouter, Request
from .errors import AppError

router = APIRouter(prefix='/projects/{project_id}', tags=['results'])


def result_for(request, project_id):
    store = request.app.state.store
    with store.lock:
        project = store.read(project_id)
        if project['result'] is None:
            raise AppError(409, 'RESULT_NOT_READY', '尚无已完成的分析结果')
        return project['result']


@router.get('/result')
def result(project_id: str, request: Request):
    return result_for(request, project_id)


@router.get('/graph')
def graph(project_id: str, request: Request):
    return result_for(request, project_id)['graph']


@router.get('/features')
def features(project_id: str, request: Request):
    return result_for(request, project_id)['features']
