from typing import Literal
from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field
from .graph_preview import build_preview
from .analyses import require_summary_idle

router = APIRouter(prefix='/projects/{project_id}', tags=['summary files'])


@router.get('/summary')
def summary(project_id: str, request: Request):
    store = request.app.state.store
    with store.lock:
        require_summary_idle(store.read(project_id))
        return request.app.state.shared.read_summary(project_id)


class GraphPreviewRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    operation: Literal['add', 'modify']
    description: str = Field(min_length=1, max_length=20000)
    featureId: str | None = None
    epicId: str | None = None


@router.post('/graph/preview')
def graph_preview(project_id: str, body: GraphPreviewRequest, request: Request):
    # Files are atomically replaced. Avoid the agent launch lock: preview is independent and read-only.
    require_summary_idle(request.app.state.store.read(project_id))
    result = request.app.state.shared.read_summary(project_id)
    return build_preview(result, body.operation, body.description, body.featureId, body.epicId)
