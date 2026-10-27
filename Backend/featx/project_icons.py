"""Project icon metadata and locally cached GitHub owner avatars."""
import base64
import os
import re
import tempfile
from urllib.parse import urlsplit
import httpx
from .errors import AppError
from .git_import import setting


def github_owner(project):
    source = project.get('source') or {}
    for url in (source.get('url'), (project.get('git') or {}).get('remoteUrl')):
        if not url:
            continue
        if re.match(r'^[\w.-]+@[^/:]+:', url):
            url = 'ssh://' + url.replace(':', '/', 1)
        parsed = urlsplit(url)
        parts = parsed.path.strip('/').split('/')
        if (parsed.hostname or '').lower() in ('github.com', 'www.github.com', 'ssh.github.com') and len(parts) >= 2:
            if re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9-]{0,38}', parts[0]):
                return parts[0]
    return None


def default_icon(project):
    owner = github_owner(project)
    return {'kind': 'github', 'owner': owner} if owner else None


def validate_icon(icon, project):
    if icon is None:
        return default_icon(project)
    if icon.get('kind') == 'builtin' and type(icon.get('index')) is int and 0 <= icon['index'] < 30:
        return {'kind': 'builtin', 'index': icon['index']}
    if icon.get('kind') == 'github' and github_owner(project):
        return default_icon(project)
    if icon.get('kind') == 'upload':
        data = icon.get('dataUrl', '')
        if isinstance(data, str) and data.startswith('data:image/jpeg;base64,') and len(data) <= 180000:
            try:
                raw = base64.b64decode(data.split(',', 1)[1], validate=True)
                if raw.startswith(b'\xff\xd8\xff') and raw.endswith(b'\xff\xd9'):
                    return {'kind': 'upload', 'dataUrl': data}
            except ValueError:
                pass
    raise AppError(400, 'INVALID_ICON', '请选择内置图标，或重新裁剪并上传图片')


def image_type(raw):
    if raw.startswith(b'\x89PNG\r\n\x1a\n'):
        return 'image/png'
    if raw.startswith(b'\xff\xd8\xff'):
        return 'image/jpeg'
    if raw.startswith(b'RIFF') and raw[8:12] == b'WEBP':
        return 'image/webp'
    raise AppError(502, 'AVATAR_INVALID', '头像图片格式无效')


def owner_avatar(root, owner):
    cache = root.parent / 'project-icons'
    cache.mkdir(exist_ok=True)
    path = cache / f'{owner.lower()}.img'
    if path.is_file():
        raw = path.read_bytes()
        return raw, image_type(raw)
    try:
        with httpx.Client(proxy=setting('GITHUB_PROXY_URL').strip() or None, timeout=12, trust_env=False) as client:
            user = client.get(f'https://api.github.com/users/{owner}', headers={'Accept': 'application/vnd.github+json'})
            user.raise_for_status()
            avatar = urlsplit(user.json()['avatar_url'])
            if avatar.scheme != 'https' or avatar.hostname != 'avatars.githubusercontent.com':
                raise ValueError('Unexpected avatar host')
            with client.stream('GET', f'https://avatars.githubusercontent.com{avatar.path}', params={'s':256}) as response:
                response.raise_for_status()
                chunks, size = [], 0
                for chunk in response.iter_bytes():
                    size += len(chunk)
                    if size > 1024 * 1024:
                        raise ValueError('Avatar too large')
                    chunks.append(chunk)
                raw = b''.join(chunks)
        mime = image_type(raw)
        name = None
        try:
            with tempfile.NamedTemporaryFile(dir=cache, delete=False) as file:
                name = file.name
                file.write(raw)
            os.replace(name, path)
        finally:
            if name and os.path.exists(name):
                os.unlink(name)
        return raw, mime
    except (httpx.HTTPError, ValueError, KeyError) as exc:
        raise AppError(502, 'AVATAR_UNAVAILABLE', '暂时无法获取 GitHub 头像') from exc
