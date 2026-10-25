"""Folder upload transport: retain paths and bytes, never parse or execute source."""
from datetime import datetime, timezone
from pathlib import PurePosixPath
from secrets import token_urlsafe
from uuid import uuid4
from zipfile import ZipFile, ZIP_STORED
from fastapi import APIRouter, Request
from starlette.concurrency import run_in_threadpool
from starlette.datastructures import UploadFile
from .errors import AppError
from .projects import public
from .analyses import start_summary
from .git_import import initialize_uploaded_repository
import shutil

router = APIRouter(prefix='/projects', tags=['uploads'])
MAX_BYTES = 100 * 1024 * 1024
MAX_FILES = 2000


def checked_paths(paths):
    seen = set()
    for value in paths:
        if not isinstance(value, str) or len(value) > 1024:
            raise AppError(400, 'INVALID_PATH', '文件路径无效')
        path = value.replace('\\', '/')
        parts = path.split('/')
        if (not path or path.startswith('/') or ':' in parts[0]
                or any(part in ('', '.', '..') for part in parts)
                or any(ord(char) < 32 for char in path)):
            raise AppError(400, 'INVALID_PATH', '只允许安全的项目相对路径')
        if path in seen:
            raise AppError(400, 'DUPLICATE_PATH', '上传包含重复文件路径')
        seen.add(path)
    for path in seen:
        if any(str(parent) in seen for parent in PurePosixPath(path).parents):
            raise AppError(400, 'INVALID_PATH', '文件和目录路径冲突')
    return [value.replace('\\', '/') for value in paths]


def save_upload(app, name, files, paths):
    store = app.state.store
    project_id = uuid4().hex
    archive_dir = store.root.parent / 'uploads'
    archive_dir.mkdir(parents=True, exist_ok=True)
    archive = archive_dir / f'{project_id}.zip'
    temporary = archive.with_suffix('.pending')
    total = 0
    directory = app.state.shared.directory(project_id)
    source = directory / 'source'
    source.mkdir(parents=True, exist_ok=False)
    saved = False
    try:
        with ZipFile(temporary, 'w', compression=ZIP_STORED) as bundle:
            for file, path in zip(files, paths):
                file.file.seek(0)
                target = source / path
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(path, 'w') as destination, target.open('wb') as source_file:
                    while chunk := file.file.read(1024 * 1024):
                        total += len(chunk)
                        if total > MAX_BYTES:
                            raise AppError(413, 'UPLOAD_TOO_LARGE', '项目文件总大小不能超过 100 MiB')
                        destination.write(chunk)
                        source_file.write(chunk)
        git_info = initialize_uploaded_repository(source)
        record = {
            'id': project_id, 'name': name, 'git': git_info,
            'source': {'kind': 'upload', 'uploadId': project_id, 'filename': 'project.zip'},
            'upload': {'fileCount': len(files), 'bytes': total},
            'uploadToken': token_urlsafe(32),
            'createdAt': datetime.now(timezone.utc).isoformat(), 'analysis': None,
        }
        with store.lock:
            temporary.replace(archive)
            try:
                store.write(record)
            except Exception:
                archive.unlink(missing_ok=True)
                raise
        saved = True
        try:
            start_summary(app, project_id)
        except AppError as exc:
            # Upload is durable even if the summary service is unavailable; allow manual retry.
            with store.lock:
                record = store.read(project_id)
                record['summaryError'] = exc.message
                store.write(record)
        return public(store.read(project_id))
    finally:
        temporary.unlink(missing_ok=True)
        if not saved:
            shutil.rmtree(directory, ignore_errors=True)


@router.post('/upload', status_code=201)
async def upload_project(request: Request):
    # Browsers send Content-Length for FormData. Bound parser/spooled disk usage as well as saved bytes.
    try:
        length = int(request.headers['content-length'])
    except (KeyError, ValueError):
        raise AppError(411, 'CONTENT_LENGTH_REQUIRED', '上传必须提供 Content-Length')
    if length < 0:
        raise AppError(400, 'INVALID_LENGTH', '上传长度无效')
    if length > MAX_BYTES + 4 * 1024 * 1024:
        raise AppError(413, 'UPLOAD_TOO_LARGE', '项目文件总大小不能超过 100 MiB')
    async with request.form(max_files=MAX_FILES, max_fields=MAX_FILES + 5) as form:
        files, paths = form.getlist('files'), form.getlist('paths')
        name = form.get('folderName')
        if not isinstance(name, str) or not name.strip() or len(name.strip()) > 160:
            raise AppError(400, 'INVALID_NAME', '请输入不超过 160 字的项目名称')
        if not files or len(files) > MAX_FILES or not all(isinstance(file, UploadFile) for file in files):
            raise AppError(400, 'INVALID_FILES', '请选择 1～2000 个项目文件')
        if len(paths) != len(files):
            raise AppError(400, 'INVALID_PATHS', 'files 和 paths 必须一一对应')
        safe_paths = checked_paths(paths)
        return await run_in_threadpool(save_upload, request.app, name.strip(), files, safe_paths)
