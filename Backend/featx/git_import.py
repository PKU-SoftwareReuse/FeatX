"""Download repositories into the shared workspace; never commit or push changes."""
import os
import re
import signal
import socket
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.parse import unquote, urlsplit, urlunsplit
from .errors import AppError

ROOT = Path(__file__).resolve().parents[2]


def setting(key, default=''):
    if key in os.environ:
        return os.environ[key]
    env_file = ROOT / '.env'
    if env_file.is_file():
        value = None
        for line in env_file.read_text(encoding='utf-8').splitlines():
            left, separator, right = line.strip().partition('=')
            if separator and left.strip() == key:
                value = right.strip()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in ('"', "'"):
                    value = value[1:-1]
        if value is not None:
            return value
    return default


def parse_repository_link(url):
    """Extract GitHub repository and branch/tag references without guessing a version."""
    parsed = urlsplit(url)
    if (parsed.hostname or '').lower().rstrip('.') not in ('github.com', 'www.github.com', 'ssh.github.com'):
        return url, None, None
    parts = parsed.path.strip('/').split('/')
    if len(parts) < 2 or not all(parts[:2]):
        raise AppError(400, 'INVALID_GIT_LINK', '请粘贴具体仓库、分支或标签的链接')
    repository = parts[1].removesuffix('.git')
    clone_url = urlunsplit(('https', 'github.com', f'/{parts[0]}/{repository}.git', '', ''))
    if len(parts) == 2:
        return clone_url, None, None
    if parts[2] == 'tree' and len(parts) > 3:
        reference = unquote('/'.join(parts[3:]))
        if reference.startswith('refs/heads/'):
            return clone_url, 'branch', reference[len('refs/heads/'):]
        if reference.startswith('refs/tags/'):
            return clone_url, 'tag', reference[len('refs/tags/'):]
        return clone_url, 'auto', reference
    if parts[2:4] == ['releases', 'tag'] and len(parts) > 4:
        return clone_url, 'tag', unquote('/'.join(parts[4:]))
    raise AppError(400, 'UNSUPPORTED_GIT_LINK', '请使用仓库、分支或具体标签链接；Commit ID 请填入单独的输入框')


class GitImport:
    def __init__(self, url=None):
        url = url or ''
        parsed = urlsplit(url)
        host = (parsed.hostname or '').lower().rstrip('.')
        self.github = host in ('github.com', 'www.github.com', 'ssh.github.com')
        self.url, self.ref_type, self.ref_name = parse_repository_link(url) if url else (url, None, None)
        self.proxy = setting('GITHUB_PROXY_URL').strip() if self.github else ''
        if self.github:
            if self.proxy:
                proxy = urlsplit(self.proxy)
                if proxy.scheme not in ('http', 'https', 'socks5', 'socks5h') or not proxy.hostname:
                    raise AppError(500, 'INVALID_PROXY', 'GITHUB_PROXY_URL 格式不正确')
                try:
                    port = proxy.port or (443 if proxy.scheme == 'https' else 1080 if proxy.scheme.startswith('socks') else 80)
                    with socket.create_connection((proxy.hostname, port), timeout=3):
                        pass
                except (OSError, ValueError) as exc:
                    raise AppError(503, 'GITHUB_PROXY_UNAVAILABLE', '无法连接 GitHub 代理，请启动 .env 中 GITHUB_PROXY_URL 指定的代理服务') from exc
        try:
            self.timeout = int(setting('GIT_CLONE_TIMEOUT_SECONDS', '300'))
            if self.timeout <= 0:
                raise ValueError()
        except ValueError as exc:
            raise AppError(500, 'INVALID_GIT_TIMEOUT', 'GIT_CLONE_TIMEOUT_SECONDS 必须是正整数') from exc
        self.deadline = time.monotonic() + self.timeout
        self.env = {key: value for key, value in os.environ.items() if not key.startswith('GIT_')}
        self.env.update(GIT_TERMINAL_PROMPT='0', GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull,
                        GIT_LFS_SKIP_SMUDGE='1', GIT_SSH_COMMAND='ssh -oBatchMode=yes -oStrictHostKeyChecking=yes')
        self.command = ['git', '-c', 'core.hooksPath=' + os.devnull,
                        '-c', 'protocol.file.allow=never', '-c', 'protocol.ext.allow=never',
                        '-c', 'http.followRedirects=initial', '-c', 'credential.helper=']
        if self.github:
            self.command += ['-c', 'http.proxy=' + self.proxy]
            # An explicit proxy must not be bypassed; an empty setting must connect directly.
            for key in ('NO_PROXY', 'no_proxy', 'HTTP_PROXY', 'http_proxy', 'HTTPS_PROXY', 'https_proxy', 'ALL_PROXY', 'all_proxy'):
                self.env.pop(key, None)

    def run(self, *arguments, cwd=None, allow_failure=False):
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise AppError(504, 'GIT_TIMEOUT', 'Git 下载或版本切换超时，请检查网络或调整 GIT_CLONE_TIMEOUT_SECONDS')
        try:
            # File-backed output avoids retaining unbounded git progress in memory.
            with tempfile.TemporaryFile() as output, tempfile.TemporaryFile() as errors:
                process = subprocess.Popen(self.command + list(arguments), cwd=cwd, env=self.env,
                                           stdin=subprocess.DEVNULL, stdout=output, stderr=errors, start_new_session=True)
                try:
                    process.wait(timeout=remaining)
                except subprocess.TimeoutExpired:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait()
                    raise AppError(504, 'GIT_TIMEOUT', 'Git 下载或版本切换超时，请检查网络或调整 GIT_CLONE_TIMEOUT_SECONDS')
                output.seek(0)
                text = output.read(1024 * 1024).decode('utf-8', errors='replace').strip()
                if process.returncode:
                    if allow_failure:
                        return None
                    errors.seek(0, os.SEEK_END)
                    errors.seek(max(0, errors.tell() - 1500))
                    detail = errors.read().decode('utf-8', errors='replace').strip()
                    for private in (self.url, self.proxy):
                        if private:
                            detail = detail.replace(private, '配置地址')
                    raise AppError(502, 'GIT_IMPORT_FAILED', 'Git 操作失败，请检查地址、权限或版本。' + (' ' + detail if detail else ''))
                return text
        except FileNotFoundError as exc:
            raise AppError(500, 'GIT_NOT_INSTALLED', '服务器未安装 Git') from exc

    def resolve_link_reference(self):
        if not self.ref_name:
            return
        candidates = []
        if self.ref_type in ('auto', 'branch'):
            candidates.append('refs/heads/' + self.ref_name)
        if self.ref_type in ('auto', 'tag'):
            candidates.append('refs/tags/' + self.ref_name)
        for reference in candidates:
            if self.run('check-ref-format', reference, allow_failure=True) is None:
                raise AppError(400, 'INVALID_REF', '链接中的分支或标签名称格式不正确')
        output = self.run('ls-remote', '--refs', '--', self.url, *candidates)
        matches = []
        for line in output.splitlines():
            fields = line.split('\t')
            if len(fields) == 2 and fields[1] in candidates:
                matches.append(fields[1])
        if not matches:
            raise AppError(404, 'REF_NOT_FOUND', '链接指定的分支或标签不存在；请勿粘贴文件或子目录页面链接')
        if len(matches) > 1:
            raise AppError(409, 'AMBIGUOUS_REF', '分支和标签存在同名项，请使用明确的分支链接或 releases/tag 标签链接')
        self.ref_type = 'branch' if matches[0].startswith('refs/heads/') else 'tag'

    def check(self):
        self.deadline = time.monotonic() + min(self.timeout, 30)
        if self.ref_name:
            self.resolve_link_reference()
            label = '分支' if self.ref_type == 'branch' else '标签'
            message = f'已验证{label}：{self.ref_name}，确认后下载该版本'
        else:
            self.run('ls-remote', '--symref', '--', self.url, 'HEAD')
            message = '仓库连接正常，未指定 Commit 时使用默认分支'
        return {'projectType': 'Git 仓库', 'repoName': Path(urlsplit(self.url).path).name.removesuffix('.git') or '项目',
                'repositoryUrl': self.url, 'refType': self.ref_type, 'refName': self.ref_name,
                'paths': [], 'message': message}

    def clone(self, destination, commit_id=None):
        commit_id = (commit_id or '').strip()
        if commit_id and not re.fullmatch(r'[0-9a-fA-F]{7,64}', commit_id):
            raise AppError(400, 'INVALID_COMMIT', 'Commit ID 请填写提交哈希；分支和标签由链接指定')
        # Validate the URL's reference even if the caller also supplies an overriding commit.
        self.resolve_link_reference()
        self.run('clone', '--no-recurse-submodules', '--', self.url, str(destination))
        if commit_id:
            target = commit_id.lower()
        elif self.ref_name:
            target = ('refs/remotes/origin/' if self.ref_type == 'branch' else 'refs/tags/') + self.ref_name
        else:
            target = 'HEAD'
        commit = self.run('rev-parse', '--verify', '--end-of-options', target + '^{commit}', cwd=destination, allow_failure=True)
        if not commit or not re.fullmatch(r'[0-9a-f]{40}|[0-9a-f]{64}', commit):
            raise AppError(400, 'COMMIT_NOT_FOUND', '仓库中找不到可用的指定提交或链接版本')
        if commit_id or self.ref_type == 'tag':
            self.run('checkout', '--detach', commit, cwd=destination)
        elif self.ref_type == 'branch':
            self.run('checkout', '-B', self.ref_name, commit, cwd=destination)
            self.run('branch', '--set-upstream-to=origin/' + self.ref_name, self.ref_name, cwd=destination)
        branch = self.run('symbolic-ref', '--quiet', '--short', 'HEAD', cwd=destination, allow_failure=True)
        return {'commit': commit, 'branch': branch, 'remoteUrl': self.url,
                'refType': self.ref_type, 'refName': self.ref_name}


def initialize_uploaded_repository(source):
    """Preserve uploaded history and append a commit marking the imported snapshot."""
    source = Path(source).resolve()
    existing = (source / '.git').exists() or (source / '.git').is_symlink()
    git = GitImport()
    # Apply the import identity only to this operation; do not overwrite existing repository settings.
    git.command += ['-c', 'user.name=FeatX', '-c', 'user.email=featx@localhost',
                    '-c', 'commit.gpgsign=false', '-c', 'core.autocrlf=false']
    try:
        if existing:
            top = git.run('rev-parse', '--show-toplevel', cwd=source)
            common = git.run('rev-parse', '--path-format=absolute', '--git-common-dir', cwd=source)
            if Path(top).resolve() != source or not Path(common).resolve().is_relative_to(source):
                raise AppError(400, 'INVALID_UPLOADED_REPOSITORY', '上传的 .git 引用了项目之外的位置，请上传完整仓库')
        else:
            git.run('init', '--initial-branch=main', '--template=', '.', cwd=source)
            git.run('config', '--local', 'user.name', 'FeatX', cwd=source)
            git.run('config', '--local', 'user.email', 'featx@localhost', cwd=source)
        previous = git.run('rev-parse', '--verify', 'HEAD', cwd=source, allow_failure=True)
        git.run('add', '--all', '--', '.', cwd=source)
        # An unchanged repository still gets an explicit marker commit.
        git.run('commit', '--allow-empty', '--no-gpg-sign', '-m', '导入项目起点', cwd=source)
        commit = git.run('rev-parse', '--verify', 'HEAD', cwd=source)
        branch = git.run('symbolic-ref', '--quiet', '--short', 'HEAD', cwd=source, allow_failure=True)
        remote = git.run('config', '--get', 'remote.origin.url', cwd=source, allow_failure=True)
    except AppError as exc:
        raise AppError(exc.status, 'GIT_INIT_FAILED', '处理上传项目的 Git 仓库失败：' + exc.message) from exc
    # Preserve origin in .git/config; never expose embedded HTTP credentials in project metadata.
    visible_remote = re.sub(r'^(https?://)[^/]*@', r'\1', remote).split('?', 1)[0].split('#', 1)[0] if remote else None
    return {'commit': commit, 'initialCommit': commit, 'previousCommit': previous,
            'branch': branch, 'remoteUrl': visible_remote, 'preservedHistory': existing}
