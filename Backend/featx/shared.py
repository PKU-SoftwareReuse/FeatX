import json
import os
import re
import shutil
from pathlib import Path
from pydantic import ValidationError
from .errors import AppError
from .models import ProjectSummary


class SharedProjects:
    def __init__(self, root=None):
        self.root = Path(root or os.getenv('SHARED_PROJECT_ROOT', '/home/lixutian/Projects_SSD/FeatX-shared')).resolve()
        self.root.mkdir(parents=True, exist_ok=True)

    def directory(self, project_id):
        if not re.fullmatch(r'[0-9a-f]{32}', project_id):
            raise AppError(404, 'PROJECT_NOT_FOUND', '项目 ID 无效')
        path = self.root / project_id
        if path.is_symlink():
            raise AppError(400, 'INVALID_PATH', '项目目录不能是符号链接')
        return path

    def read_summary(self, project_id, run_id=None):
        path = self.directory(project_id) / 'summary.json'
        if not path.is_file():
            raise AppError(409, 'RESULT_NOT_READY', '摘要尚未生成')
        if path.is_symlink():
            raise AppError(502, 'INVALID_SUMMARY', '摘要文件不能是符号链接')
        try:
            result = ProjectSummary.model_validate(json.loads(path.read_text(encoding='utf-8'))).model_dump(mode='json')
        except (OSError, ValueError, ValidationError) as exc:
            raise AppError(502, 'INVALID_SUMMARY', 'summary.json 不符合约定') from exc
        if result['projectId'] != project_id or (run_id and result['runId'] != run_id):
            raise AppError(502, 'INVALID_SUMMARY', '摘要不属于当前项目或本次任务')
        return result

    def delete(self, project_id):
        path = self.directory(project_id)
        if path.exists():
            shutil.rmtree(path)
