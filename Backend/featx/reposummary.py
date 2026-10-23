"""HTTP only: never imports analysis code, accesses source trees, or loads models."""
import os
from urllib.parse import quote
import httpx
from pydantic import ValidationError
from .errors import AppError
from .models import Task, AnalysisResult


class RepoSummaryClient:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip('/')

    def call(self, method, path, payload=None):
        try:
            with httpx.Client(timeout=15, follow_redirects=False, trust_env=False) as client:
                response = client.request(method, self.base_url + path, json=payload)
        except httpx.RequestError as exc:
            raise AppError(503, 'REPOSUMMARY_UNREACHABLE',
                           'RepoSummary 不可达或超时；提交超时时请先确认远端任务状态再重试') from exc
        if response.status_code == 501:
            raise AppError(501, 'REPOSUMMARY_NOT_IMPLEMENTED', 'RepoSummary 待接入，业务尚未实现')
        if not response.is_success:
            raise AppError(502, 'REPOSUMMARY_ERROR', f'RepoSummary 返回 HTTP {response.status_code}')
        try:
            data = response.json()
            if not isinstance(data, dict):
                raise ValueError('expected object')
            return data
        except ValueError as exc:
            raise AppError(502, 'INVALID_REPOSUMMARY_RESULT', 'RepoSummary 返回了无效 JSON 对象') from exc

    def health(self):
        data = self.call('GET', '/health')
        if not isinstance(data.get('businessReady'), bool):
            raise AppError(502, 'INVALID_REPOSUMMARY_RESULT', '健康接口缺少 businessReady 布尔值')
        return data

    def parse(self, model, data):
        try:
            return model.model_validate(data).model_dump(mode='json')
        except ValidationError as exc:
            raise AppError(502, 'INVALID_REPOSUMMARY_RESULT', 'RepoSummary 数据不符合接口契约') from exc

    def submit(self, project):
        source = dict(project['source'])
        if source.get('kind') == 'upload':
            base = os.getenv('FEATX_DOWNLOAD_BASE_URL', 'http://127.0.0.1:8080').rstrip('/')
            source['downloadUrl'] = (f"{base}/api/v1/projects/{project['id']}/archive"
                                     f"?token={quote(project['uploadToken'], safe='')}")
        return self.parse(Task, self.call('POST', '/v1/analyses',
                                         {'projectId': project['id'], 'source': source}))

    def poll(self, task_id):
        data = self.parse(Task, self.call('GET', f'/v1/analyses/{quote(task_id, safe="")}'))
        if data['taskId'] != task_id:
            raise AppError(502, 'INVALID_REPOSUMMARY_RESULT', 'RepoSummary 返回了错误的任务 ID')
        return data

    def result(self, task_id):
        return self.parse(AnalysisResult, self.call('GET', f'/v1/analyses/{quote(task_id, safe="")}/result'))
