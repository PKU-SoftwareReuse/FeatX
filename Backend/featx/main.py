import os
from pathlib import Path
from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse
from . import projects, analyses, graphs, operations, uploads, files, languages, references
from .errors import AppError
from .reposummary import RepoSummaryClient
from .store import Store
from .agent import AgentManager, CODEX_HOME
from .model_discovery import ModelDiscovery
from .shared import SharedProjects

ROOT = Path(__file__).resolve().parents[2]


def create_app(data_dir=None, reposummary_url=None, shared_dir=None):
    app = FastAPI(title='FeatX API', version='1.0.0', description='项目管理、功能摘要和文件存储接口。')
    app.state.store = Store(data_dir or os.getenv('FEATX_DATA_DIR', str(ROOT / 'data')))
    app.state.shared = SharedProjects(shared_dir)
    app.state.reference_scanner = references.ReferenceScanner()
    summary_url = (reposummary_url if reposummary_url is not None
                   else os.getenv('REPOSUMMARY_HTTP_URL', '')).strip()
    app.state.reposummary = RepoSummaryClient(summary_url or 'http://127.0.0.1:18091')

    app.state.agent = AgentManager(app)

    app.state.model_discovery = ModelDiscovery(CODEX_HOME, app.state.agent.invalidate_models)

    @app.on_event('startup')
    def start_model_discovery():
        # Explicit isolated data directories must not overwrite the shared development catalog.
        if data_dir is None:
            app.state.model_discovery.start()

    @app.on_event('shutdown')
    def stop_agent():
        app.state.model_discovery.stop()
        app.state.agent.shutdown()

    @app.exception_handler(AppError)
    async def handle_error(_request: Request, exc: AppError):
        return JSONResponse(status_code=exc.status, content={'error': {'code': exc.code, 'message': exc.message}})

    @app.get('/api/health', tags=['health'])
    def health():
        return {'status': 'ready', 'storage': 'json-files'}

    @app.get('/api/integrations/reposummary', tags=['health'])
    def dependency_health():
        return app.state.reposummary.health()

    for router in (uploads.router, projects.router, languages.router, references.router, analyses.router, graphs.router, operations.router, files.router):
        app.include_router(router, prefix='/api')

    return app
