from fastapi import APIRouter, Request

router = APIRouter(prefix='/projects/{project_id}', tags=['summary files'])


@router.get('/summary')
def summary(project_id: str, request: Request):
    store = request.app.state.store
    with store.lock:
        store.read(project_id)
        return request.app.state.shared.read_summary(project_id)
