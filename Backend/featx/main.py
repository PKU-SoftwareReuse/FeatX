import os
from pathlib import Path
from fastapi import FastAPI, Request
from fastapi.responses import FileResponse, JSONResponse
from . import projects, analyses, graphs, operations, uploads
from .errors import AppError
from .reposummary import RepoSummaryClient
from .store import Store

ROOT = Path(__file__).resolve().parents[2]


def create_app(data_dir=None, reposummary_url=None, frontend_dir=None):
    app = FastAPI(title='FeatX API', version='1.0.0', description='Language-neutral demo; single process, JSON files only.')
    app.state.store = Store(data_dir or os.getenv('FEATX_DATA_DIR', str(ROOT / 'data')))
    app.state.reposummary = RepoSummaryClient(reposummary_url or os.getenv('REPOSUMMARY_HTTP_URL', 'http://127.0.0.1:8091'))
    frontend = Path(frontend_dir or os.getenv('FEATX_FRONTEND_DIR', str(ROOT / 'Frontend/build'))).resolve()

    @app.exception_handler(AppError)
    async def handle_error(_request: Request, exc: AppError):
        return JSONResponse(status_code=exc.status, content={'error': {'code': exc.code, 'message': exc.message}})

    @app.get('/api/v1/health', tags=['health'])
    def health():
        return {'status': 'ready', 'storage': 'json-files'}

    @app.get('/api/v1/integrations/reposummary', tags=['health'])
    def dependency_health():
        return app.state.reposummary.health()

    for router in (uploads.router, projects.router, analyses.router, graphs.router, operations.router):
        app.include_router(router, prefix='/api/v1')

    @app.get('/{path:path}', include_in_schema=False)
    def frontend_file(path: str):
        if path == 'api' or path.startswith('api/'):
            raise AppError(404, 'NOT_FOUND', 'API 不存在')
        target = (frontend / ('index.html' if path in ('', 'welcome', 'working') else path)).resolve()
        if not target.is_relative_to(frontend) or not target.is_file():
            raise AppError(404, 'NOT_FOUND', '文件不存在；开发时请启动前端或先构建 Frontend')
        return FileResponse(target)

    return app
