"""One JSON document per project. Single process only; no database."""
import json
import os
import re
import tempfile
from pathlib import Path
from threading import RLock
from .errors import AppError


class Store:
    def __init__(self, root):
        self.root = Path(root).resolve() / 'projects'
        self.root.mkdir(parents=True, exist_ok=True)
        self.lock = RLock()

    def path(self, project_id):
        if not re.fullmatch(r'[0-9a-f]{32}', project_id):
            raise AppError(404, 'PROJECT_NOT_FOUND', '项目不存在')
        return self.root / f'{project_id}.json'

    def read(self, project_id):
        path = self.path(project_id)
        if not path.is_file():
            raise AppError(404, 'PROJECT_NOT_FOUND', '项目不存在')
        try:
            return json.loads(path.read_text(encoding='utf-8'))
        except (OSError, ValueError) as exc:
            raise AppError(500, 'STORAGE_ERROR', '项目文件无法读取，请检查 data 目录') from exc

    def write(self, record):
        destination = self.path(record['id'])
        name = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', encoding='utf-8', dir=self.root,
                                             prefix='.pending-', delete=False) as file:
                name = file.name
                json.dump(record, file, ensure_ascii=False, allow_nan=False)
                file.flush()
                os.fsync(file.fileno())
            os.replace(name, destination)
        finally:
            if name and os.path.exists(name):
                os.unlink(name)

    def list(self):
        return [self.read(p.stem) for p in sorted(self.root.glob('*.json'))]

    def delete(self, project_id):
        record = self.read(project_id)
        if record['source'].get('kind') == 'upload':
            (self.root.parent / 'uploads' / f'{project_id}.zip').unlink(missing_ok=True)
        self.path(project_id).unlink()
