"""Feature edits in the project worktree with Git review and explicit commit."""
import asyncio
import base64
import json
from typing import Literal

from fastapi import APIRouter, Header, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field

router = APIRouter(tags=['feature edits'])


class EditRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    operation: Literal['add', 'modify', 'delete']
    featureId: str | None = None
    epicId: str | None = None
    description: str = Field(default='', max_length=20000)
    model: str | None = None


class CandidateEdit(BaseModel):
    path: str
    content: str
    runId: str | None = None


class GitOperation(BaseModel):
    operation: Literal['status', 'stage', 'unstage', 'revert', 'commit', 'discard']
    path: str | None = None
    runId: str | None = None


@router.get('/agent/models')
def models(request: Request):
    return request.app.state.agent.catalog()


@router.post('/projects/{project_id}/edits', status_code=202)
@router.post('/projects/{project_id}/agent/runs', status_code=202)
def edit(project_id: str, body: EditRequest, request: Request):
    return request.app.state.agent.start(project_id, body)


@router.get('/projects/{project_id}/agent/runs/{run_id}')
def snapshot(project_id: str, run_id: str, request: Request):
    with request.app.state.agent.lock:
        return request.app.state.agent.read(run_id, project_id)


@router.get('/projects/{project_id}/agent/runs/{run_id}/events')
def events(project_id: str, run_id: str, request: Request, last_event_id: str | None = Header(default=None)):
    agent = request.app.state.agent
    agent.read(run_id, project_id)
    try:
        after = max(0, int(last_event_id or 0))
    except ValueError:
        after = 0

    async def stream():
        cursor = after
        idle = 0
        while not await request.is_disconnected():
            batch, status = await asyncio.to_thread(agent.events, run_id, project_id, cursor)
            for event in batch:
                cursor = event['id']
                text = base64.b64encode(event['text'].encode()).decode()
                yield f"id: {cursor}\nevent: {event['event']}\ndata: {text}\n\n"
            if status in {'COMPLETED', 'FAILED', 'COMMITTED', 'DISCARDED'}:
                return
            idle += 1
            if idle % 20 == 0:
                yield ': keepalive\n\n'
            await asyncio.sleep(0.4)
    return StreamingResponse(stream(), media_type='text/event-stream',
                             headers={'Cache-Control': 'no-cache', 'X-Accel-Buffering': 'no'})


@router.get('/projects/{project_id}/agent/progress')
def progress(project_id: str, request: Request):
    agent = request.app.state.agent
    with agent.lock:
        run = agent.current(project_id)
        if not run:
            return {'running': False, 'stage': 'idle', 'message': '尚未启动智能体'}
        status = run['status']
        return {'operation': run['mode'], 'stage': 'agent-stream', 'running': status in {'PREPARED', 'RUNNING'},
                'failed': status == 'FAILED', 'currentStep': None, 'totalSteps': None,
                'message': {'PREPARED': '正在组织上下文', 'RUNNING': '智能体正在执行，请在智能体输出中查看过程',
                            'COMPLETED': '项目修改已生成，等待审阅提交', 'COMMITTED': '修改已确认', 'DISCARDED': '任务已放弃',
                            'FAILED': run.get('failureMessage')}.get(status, status)}


@router.get('/projects/{project_id}/agent/runs/{run_id}/graph')
def graph(project_id: str, run_id: str, request: Request):
    return request.app.state.agent.graph(project_id, run_id)


@router.get('/projects/{project_id}/diff')
def candidate(project_id: str, path: str, request: Request, runId: str | None = None):
    return request.app.state.agent.candidate(project_id, path, runId)


@router.put('/projects/{project_id}/diff')
def update_candidate(project_id: str, body: CandidateEdit, request: Request):
    return request.app.state.agent.update_candidate(project_id, body.path, body.content, body.runId)


@router.post('/projects/{project_id}/git/operations')
def git_operation(project_id: str, body: GitOperation, request: Request):
    return request.app.state.agent.git_operation(project_id, body.operation, body.path, body.runId)
