"""Only submit and poll HTTP. Results are read from the shared filesystem."""
from urllib.parse import quote
import httpx
from pydantic import ValidationError
from .errors import AppError
from .models import Task


class RepoSummaryClient:
    def __init__(self, base_url):
        self.base_url = base_url.rstrip('/')

    def call(self, method, path, payload=None):
        try:
            with httpx.Client(timeout=15, follow_redirects=False, trust_env=False) as client:
                response = client.request(method, self.base_url + path, json=payload)
        except httpx.RequestError as exc:
            raise AppError(503, 'REPOSUMMARY_UNREACHABLE', 'RepoSummary 不可达或超时；不要自动重复提交') from exc
        if response.status_code == 501:
            raise AppError(501, 'REPOSUMMARY_NOT_IMPLEMENTED', 'RepoSummary 业务尚未实现')
        if not response.is_success:
            try:
                message = response.json().get('error', {}).get('message')
            except (ValueError, AttributeError):
                message = None
            raise AppError(response.status_code if response.status_code in (404, 409) else 502,
                           'REPOSUMMARY_ERROR', message or f'RepoSummary HTTP {response.status_code}')
        try:
            data = response.json()
            if not isinstance(data, dict):
                raise ValueError()
            return data
        except ValueError as exc:
            raise AppError(502, 'INVALID_REPOSUMMARY_RESULT', 'RepoSummary 返回了无效 JSON') from exc

    def health(self):
        return self.call('GET', '/health')

    def parse_task(self, data, project_id=None, run_id=None):
        try:
            # Providers may return either a plain error message or {code, message}.
            # Normalize at the HTTP boundary; task identity/status validation remains strict.
            error = data.get('error')
            if isinstance(error, dict) and isinstance(error.get('message'), str):
                code = error.get('code')
                message = error['message']
                if isinstance(code, str) and code:
                    message = f'{code}: {message}'
                data = {**data, 'error': message}
            task = Task.model_validate(data).model_dump(mode='json')
            if (project_id and task['projectId'] != project_id) or (run_id and task['runId'] != run_id):
                raise ValueError('task identity mismatch')
            return task
        except (ValueError, ValidationError) as exc:
            raise AppError(502, 'INVALID_REPOSUMMARY_RESULT', '任务状态不符合约定') from exc

    def submit(self, project, hints=None):
        return self.parse_task(self.call('POST', '/summary', {'projectId': project['id'], 'hints': hints or {}}), project['id'])

    def poll(self, run_id, project_id):
        return self.parse_task(self.call('GET', f'/summary/{quote(run_id, safe="")}'), project_id, run_id)
