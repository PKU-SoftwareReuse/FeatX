"""Read local project files for display only; never infer graph semantics."""
from fastapi import APIRouter, Request
from .errors import AppError

router = APIRouter(prefix='/projects/{project_id}/files', tags=['files'])


@router.get('/content')
def content(project_id: str, path: str, request: Request):
    request.app.state.store.read(project_id)
    root = (request.app.state.shared.directory(project_id) / 'source').resolve()
    if not path or path.startswith(('/', '\\')) or '..' in path.replace('\\', '/').split('/'):
        raise AppError(400, 'INVALID_PATH', '请提供项目内的文件路径')
    target = (root / path).resolve()
    if not target.is_relative_to(root) or '.git' in target.relative_to(root).parts:
        raise AppError(400, 'INVALID_PATH', '不能读取项目外部文件或 Git 内部文件')
    if not target.is_file():
        raise AppError(404, 'FILE_NOT_FOUND', '源文件不存在，摘要中的文件路径可能已失效')
    try:
        with target.open('rb') as stream:
            data = stream.read(1024 * 1024 + 1)
        if len(data) > 1024 * 1024:
            raise AppError(413, 'FILE_TOO_LARGE', '文件超过 1 MiB，暂不提供在线预览')
        if b'\x00' in data:
            raise AppError(415, 'BINARY_FILE', '此文件不是文本文件，无法预览')
        text = data.decode('utf-8-sig')
    except UnicodeDecodeError as exc:
        raise AppError(415, 'FILE_ENCODING', '此文件不是 UTF-8 文本，无法预览') from exc
    except OSError as exc:
        raise AppError(500, 'FILE_READ_FAILED', '源文件无法读取') from exc
    return {'projectId': project_id, 'path': target.relative_to(root).as_posix(), 'content': text}
