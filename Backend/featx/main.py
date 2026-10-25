import os
from pathlib import Path
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from . import projects, analyses, graphs, operations, uploads
from .errors import AppError
from .reposummary import RepoSummaryClient
from .store import Store
from .shared import SharedProjects

ROOT = Path(__file__).resolve().parents[2]


def create_app(data_dir=None, reposummary_url=None, shared_dir=None):
    app = FastAPI(title='FeatX API', version='1.0.0', description='项目管理、功能摘要和文件存储接口。')
    app.state.store = Store(data_dir or os.getenv('FEATX_DATA_DIR', str(ROOT / 'data')))
    app.state.shared = SharedProjects(shared_dir)
    app.state.reposummary = RepoSummaryClient(reposummary_url or os.getenv('REPOSUMMARY_HTTP_URL', 'http://127.0.0.1:18091'))

    @app.exception_handler(AppError)
    async def handle_error(_request: Request, exc: AppError):
        return JSONResponse(status_code=exc.status, content={'error': {'code': exc.code, 'message': exc.message}})

    @app.get('/api/health', tags=['health'])
    def health():
        return {'status': 'ready', 'storage': 'json-files'}

    @app.get('/api/integrations/reposummary', tags=['health'])
    def dependency_health():
        return app.state.reposummary.health()

    for router in (uploads.router, projects.router, analyses.router, graphs.router, operations.router):
        app.include_router(router, prefix='/api')

    return app
