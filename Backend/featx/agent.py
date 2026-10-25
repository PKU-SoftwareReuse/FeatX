"""Pinned Codex executor. Candidate repositories, JSONL output, and explicit acceptance."""
from __future__ import annotations

import hashlib
import json
import os
import re
import signal
import subprocess
import threading
import tomllib
import time
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from uuid import uuid4

from .errors import AppError
from .codex_catalog import list_models
from .models import Feature, ProjectSummary

ROOT = Path(__file__).resolve().parents[2]
# Use the pinned native executable: the systemd service does not inherit nvm's PATH.
BINARY = ROOT / 'tools/codex/node_modules/@openai/codex-linux-x64/vendor/x86_64-unknown-linux-musl/bin/codex'
VERSION = json.loads((ROOT / 'tools/codex/package.json').read_text())['dependencies']['@openai/codex']
CODEX_HOME = ROOT / 'data/codex-home'
RUN_TIMEOUT_SECONDS = 1800
ACTIVE = {'PREPARED', 'RUNNING', 'COMPLETED'}
SCHEMA = {
    'type': 'object', 'additionalProperties': False,
    'properties': {'summary': {'type': 'string'}, 'description': {'type': 'string'},
                   'codeElements': {'type': 'array', 'items': {'type': 'string'}}},
    'required': ['summary', 'description', 'codeElements'],
}


def now():
    return datetime.now(timezone.utc).isoformat()


def atomic_json(path, data):
    temp = path.with_name('.' + path.name + '.' + uuid4().hex + '.tmp')
    try:
        with temp.open('x', encoding='utf-8') as stream:
            json.dump(data, stream, ensure_ascii=False, allow_nan=False, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def safe_file(root, name):
    if not isinstance(name, str) or '\\' in name or name.startswith('/'):
        raise AppError(400, 'INVALID_PATH', '文件路径必须是项目相对路径')
    parts = PurePosixPath(name).parts
    if not parts or any(p in ('..', '.git') for p in parts):
        raise AppError(400, 'INVALID_PATH', '无效的项目文件路径')
    target = root / name
    if not target.resolve().is_relative_to(root.resolve()):
        raise AppError(400, 'INVALID_PATH', '不能访问候选项目之外的文件')
    return target


def git(root, *args, input=None, check=True):
    env = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    env.update(GIT_TERMINAL_PROMPT='0', GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_NOSYSTEM='1')
    try:
        result = subprocess.run(['git', '-c', 'core.hooksPath=' + os.devnull,
                                 '-c', 'core.quotePath=false', '-c', 'commit.gpgsign=false',
                                 '-c', 'user.name=FeatX', '-c', 'user.email=featx@localhost',
                                 *args], cwd=root, env=env, input=input, capture_output=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise AppError(500, 'AGENT_GIT_FAILED', '候选仓库操作失败或超时') from exc
    if check and result.returncode:
        raise AppError(409, 'AGENT_GIT_FAILED', result.stderr.decode(errors='replace')[-1500:])
    return result.stdout


def read_text(path):
    if not path.is_file():
        return ''
    data = path.read_bytes()
    if len(data) > 1024 * 1024 or b'\0' in data:
        raise AppError(415, 'PREVIEW_UNSUPPORTED', '该文件过大或为二进制，暂不支持在线编辑')
    try:
        return data.decode('utf-8')
    except UnicodeDecodeError as exc:
        raise AppError(415, 'PREVIEW_UNSUPPORTED', '该文件不是 UTF-8 文本') from exc


class AgentManager:
    def __init__(self, app):
        self.app = app
        self.lock = app.state.store.lock
        self.root = app.state.store.root.parent / 'agent-runs'
        self.root.mkdir(parents=True, exist_ok=True)
        self.processes = {}
        self.model_cache = None
        self.model_cache_lock = threading.Lock()
        self.stopping = False
        self.binary = BINARY
        for path in self.root.glob('*/run.json'):
            run = json.loads(path.read_text())
            if run['status'] in {'PREPARED', 'RUNNING'}:
                run.update(status='FAILED', failureMessage='主后端已重启，本次执行被中断；候选内容未应用。')
                self.save(run)
                self.emit(run['runId'], 'failed', run['failureMessage'])

    def directory(self, run_id):
        if not re.fullmatch(r'[0-9a-f]{32}', run_id or ''):
            raise AppError(404, 'RUN_NOT_FOUND', '智能体任务不存在')
        return self.root / run_id

    def read(self, run_id, project_id=None):
        path = self.directory(run_id) / 'run.json'
        if not path.is_file():
            raise AppError(404, 'RUN_NOT_FOUND', '智能体任务不存在')
        run = json.loads(path.read_text())
        if project_id and run['projectId'] != project_id:
            raise AppError(404, 'RUN_NOT_FOUND', '任务不属于此项目')
        return run

    def save(self, run):
        atomic_json(self.directory(run['runId']) / 'run.json', run)

    def current(self, project_id):
        project = self.app.state.store.read(project_id)
        return self.read(project['agentRunId'], project_id) if project.get('agentRunId') else None

    def assert_idle(self, project_id):
        run = self.current(project_id)
        if run and run['status'] in ACTIVE:
            raise AppError(409, 'AGENT_PENDING', '该项目仍有智能体任务或待确认修改，请先确认或放弃')

    def environment(self):
        env = dict(os.environ)
        CODEX_HOME.mkdir(parents=True, exist_ok=True)
        env['CODEX_HOME'] = str(CODEX_HOME)
        return env

    @staticmethod
    def read_config(path):
        if not path.exists():
            return {}
        try:
            with path.open('rb') as stream:
                config = tomllib.load(stream)
        except (OSError, tomllib.TOMLDecodeError) as exc:
            raise AppError(400, 'INVALID_CODEX_CONFIG', f'请检查 {path.name} 的 TOML 格式和读取权限') from exc
        for key in ('model', 'model_provider'):
            if key in config and (not isinstance(config[key], str) or not config[key].strip()):
                raise AppError(400, 'INVALID_CODEX_CONFIG', f'{path.name} 中的 {key} 必须是非空字符串')
        return config

    def native_models(self):
        files = [CODEX_HOME / 'config.toml', CODEX_HOME / 'auth.json']
        catalog_file = self.read_config(CODEX_HOME / 'config.toml').get('model_catalog_json')
        if isinstance(catalog_file, str):
            path = Path(catalog_file).expanduser()
            files.append(path if path.is_absolute() else CODEX_HOME / path)
        signature = tuple((str(path), path.stat().st_mtime_ns if path.exists() else None) for path in files)
        with self.model_cache_lock:
            cached = self.model_cache
            if cached and cached['signature'] == signature and time.monotonic() < cached['until']:
                return cached['models'], cached['error']
            try:
                models = list_models(self.binary, CODEX_HOME, self.environment())
                error = ''
            except (OSError, RuntimeError, TimeoutError, ValueError) as exc:
                models, error = [], str(exc)
            self.model_cache = {'signature': signature, 'until': time.monotonic() + (120 if not error else 10),
                                'models': models, 'error': error}
            return models, error

    def catalog(self):
        base = self.read_config(CODEX_HOME / 'config.toml')
        options = [{'value': 'default', 'label': (base['model'] + '（配置默认）') if base.get('model') else 'Codex 默认模型',
                    'model': base.get('model'), 'profile': None, 'provider': base.get('model_provider', 'openai')}]
        native, model_error = self.native_models() if self.binary.is_file() else ([], 'Codex 尚未安装')
        seen = set()
        for item in native:
            model = item.get('model') or item.get('id')
            if not isinstance(model, str) or not model or item.get('hidden') or model in seen:
                continue
            seen.add(model)
            options.append({'value': 'model:' + model, 'label': item.get('displayName') or model,
                            'model': model, 'profile': None, 'overrideModel': True,
                            'provider': base.get('model_provider', 'openai')})
        for path in sorted(CODEX_HOME.glob('*.config.toml')):
            name = path.name.removesuffix('.config.toml')
            if not re.fullmatch(r'[A-Za-z0-9_-]+', name):
                continue
            profile = self.read_config(path)
            model = profile.get('model', base.get('model'))
            options.append({'value': 'profile:' + name, 'label': f"{name} · {model or 'Codex 默认模型'}",
                            'model': model, 'profile': name,
                            'provider': profile.get('model_provider', base.get('model_provider', 'openai'))})
        env = self.environment()
        provider_config = base.get('model_providers', {}).get(base.get('model_provider', 'openai'), {})
        configured = bool(env.get('CODEX_API_KEY') or env.get('OPENAI_API_KEY') or (CODEX_HOME / 'auth.json').is_file()
                          or provider_config.get('experimental_bearer_token')
                          or env.get(provider_config.get('env_key', '')))
        custom_provider = any(option['provider'] != 'openai' for option in options)
        # This is a hint only. Authentication, including custom provider credentials,
        # is owned and validated by Codex, never guessed from auth.json alone.
        notice = '' if configured or custom_provider else '尚未检测到登录信息：请运行 tools/codex/codex login --device-auth，或在 config.toml 中配置服务商。'
        if model_error:
            notice = (notice + ' ' if notice else '') + model_error + '；暂仅显示本地配置，请稍后重试。'
        return {'models': [option['value'] for option in options], 'modelOptions': options,
                'defaultModel': 'default', 'version': VERSION, 'installed': self.binary.is_file(),
                'authConfigured': configured, 'message': notice}

    def redact(self, text):
        secrets = [os.environ.get('CODEX_API_KEY'), os.environ.get('OPENAI_API_KEY')]
        try:
            config = self.read_config(CODEX_HOME / 'config.toml')
            for provider in config.get('model_providers', {}).values():
                secrets.extend([provider.get('experimental_bearer_token'), os.environ.get(provider.get('env_key', ''))])
        except (AppError, AttributeError):
            pass
        for secret in secrets:
            if isinstance(secret, str) and secret:
                text = text.replace(secret, '[已隐藏]')
        return text

    def emit(self, run_id, event, text):
        text = self.redact(text)
        with self.lock:
            run = self.read(run_id)
            run['eventCount'] = run.get('eventCount', 0) + 1
            with (self.directory(run_id) / 'events.jsonl').open('a', encoding='utf-8') as stream:
                stream.write(json.dumps({'id': run['eventCount'], 'event': event, 'text': text}, ensure_ascii=False) + '\n')
            self.save(run)

    def events(self, run_id, project_id, after=0):
        with self.lock:
            run = self.read(run_id, project_id)
            path = self.directory(run_id) / 'events.jsonl'
            events = [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []
            return [e for e in events if e['id'] > after], run['status']

    def start(self, project_id, body):
        with self.lock:
            self.assert_idle(project_id)
            project = self.app.state.store.read(project_id)
            if (project.get('analysis') or {}).get('status') in {'queued', 'running'}:
                raise AppError(409, 'SUMMARY_RUNNING', '请等待项目总结完成')
            info = self.catalog()
            if not info['installed']:
                raise AppError(503, 'CODEX_NOT_INSTALLED', '请运行 npm ci --prefix tools/codex 安装固定版本')
            model = body.model or info['defaultModel']
            if model not in info['models']:
                raise AppError(400, 'INVALID_MODEL', '所选配置已不存在，请刷新工作台模型列表')
            selection = next(option for option in info['modelOptions'] if option['value'] == model)
            summary = self.app.state.shared.read_summary(project_id)
            feature, epic = None, None
            if body.operation == 'add':
                epic = next((e for e in summary['epics'] if e['id'] == body.epicId), None)
            else:
                for e in summary['epics']:
                    for f in e['features']:
                        if f['id'] == body.featureId:
                            epic, feature = e, f
            if epic is None:
                raise AppError(404, 'FEATURE_NOT_FOUND', '功能主题或功能项不存在')
            if body.operation != 'delete' and not body.description.strip():
                raise AppError(400, 'EMPTY_DESCRIPTION', '功能描述不能为空')
            source = self.app.state.shared.directory(project_id) / 'source'
            if git(source, 'status', '--porcelain').strip():
                raise AppError(409, 'SOURCE_DIRTY', '项目源码存在未提交修改，请先处理后再启动智能体')
            run_id = uuid4().hex
            directory = self.directory(run_id)
            directory.mkdir()
            run = {'runId': run_id, 'projectId': project_id, 'operation': body.operation,
                   'mode': 'feature-' + body.operation, 'moduleId': epic['id'],
                   'featureId': feature['id'] if feature else 'feature_' + uuid4().hex,
                   'request': body.description.strip() or (feature or {}).get('description', ''),
                   'model': model, 'modelLabel': selection['label'], 'codexProfile': selection['profile'],
                   'modelOverride': selection.get('model') if selection.get('overrideModel') else None,
                   'configuredModel': selection['model'], 'status': 'PREPARED', 'createdAt': now(), 'eventCount': 0,
                   'baseCommit': git(source, 'rev-parse', 'HEAD').decode().strip(),
                   'summaryHash': digest(source.parent / 'summary.json'), 'stagedPaths': [],
                   'changedPaths': [], 'metadataOnlyEligible': False, 'failureMessage': None}
            atomic_json(directory / 'base-summary.json', summary)
            atomic_json(directory / 'schema.json', SCHEMA)
            self.save(run)
            project['agentRunId'] = run_id
            self.app.state.store.write(project)
            self.emit(run_id, 'status', '### 正在准备功能变更\n\n正在创建独立候选工作区，项目源码暂不修改。\n\n')
            threading.Thread(target=self.execute, args=(run_id,), daemon=True).start()
            return self.read(run_id)

    def prompt(self, run, workspace):
        base = json.loads((self.directory(run['runId']) / 'base-summary.json').read_text())
        epic = next(e for e in base['epics'] if e['id'] == run['moduleId'])
        feature = next((f for f in epic['features'] if f['id'] == run['featureId']), None)
        project = self.app.state.store.read(run['projectId'])
        paths = list(dict.fromkeys(x.split('::', 1)[0] for x in (feature or {}).get('codeElements', [])))
        paths = list(dict.fromkeys(['README.md', *paths]))[:9]
        snippets, remaining = [], 100000
        for name in paths:
            if PurePosixPath(name).name.startswith('.env') or PurePosixPath(name).name in {'id_rsa', 'id_ed25519', 'auth.json'}:
                continue
            try:
                file = safe_file(workspace, name)
                if not file.is_file():
                    continue
                with file.open('rb') as stream:
                    raw = stream.read(min(16000, remaining) + 1)
                if b'\0' in raw:
                    continue
                text = raw[:min(16000, remaining)].decode('utf-8', errors='replace')
                snippets.append({'path': name, 'content': text, 'truncated': len(raw) > len(text.encode('utf-8'))})
                remaining -= len(raw)
                if remaining <= 0:
                    break
            except (AppError, OSError):
                continue
        context = {'project': {'id': run['projectId'], 'name': project['name'], 'description': base.get('description', '')},
                   'operation': run['operation'], 'request': run['request'], 'epic': {'id': epic['id'], 'description': epic['description']},
                   'featureBefore': feature, 'targetFeatureId': run['featureId'],
                   'otherFeatures': [{'epic': e['description'], 'features': [{'id': f['id'], 'description': f['description']} for f in e['features']]} for e in base['epics']],
                   'files': snippets, 'note': '文件片段可能截断，必要时读取当前候选仓库中的完整文件。'}
        return (Path(__file__).parent / 'prompts/feature_edit.md').read_text() + json.dumps(context, ensure_ascii=False, indent=2)

    def stop_process(self, run_id):
        process = self.processes.get(run_id)
        if process and process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
            except ProcessLookupError:
                pass

    def execute(self, run_id):
        timer = None
        timed_out = threading.Event()
        directory = self.directory(run_id)
        try:
            run = self.read(run_id)
            source = self.app.state.shared.directory(run['projectId']) / 'source'
            workspace = directory / 'workspace'
            git(directory, 'clone', '--local', '--no-hardlinks', '--', str(source), str(workspace))
            git(workspace, 'remote', 'remove', 'origin')
            if git(workspace, 'rev-parse', 'HEAD').decode().strip() != run['baseCommit']:
                raise AppError(409, 'SOURCE_CHANGED', '项目版本已变化，请重新提交任务')
            prompt = self.prompt(run, workspace)
            (directory / 'prompt.txt').write_text(prompt, encoding='utf-8')
            env = self.environment()
            command = [str(self.binary), 'exec', '--json', '--ephemeral',
                       '--color', 'never', '-C', str(workspace),
                       '--output-schema', str(directory / 'schema.json'), '-o', str(directory / 'result.json')]
            if run.get('codexProfile'):
                command += ['--profile', run['codexProfile']]
            if run.get('modelOverride'):
                command += ['--model', run['modelOverride']]
            command.append('-')
            with self.lock:
                latest = self.read(run_id)
                if latest['status'] == 'DISCARDED' or self.stopping:
                    return
                latest.update(status='RUNNING', startedAt=now())
                self.save(latest)
                with (directory / 'stderr.log').open('wb') as errors:
                    process = subprocess.Popen(command, cwd=workspace, env=env, stdin=subprocess.PIPE,
                                               stdout=subprocess.PIPE, stderr=errors, start_new_session=True)
                self.processes[run_id] = process
            self.emit(run_id, 'status', '### Codex 执行中\n\n模型：' + run.get('modelLabel', 'Codex 默认模型') + '\n\n')
            timeout = RUN_TIMEOUT_SECONDS
            def timeout_run():
                timed_out.set()
                self.stop_process(run_id)
            timer = threading.Timer(timeout, timeout_run)
            timer.start()
            process.stdin.write(prompt.encode())
            process.stdin.close()
            turn_complete = False
            failure = None
            with (directory / 'stdout.jsonl').open('wb') as raw:
                for line in process.stdout:
                    raw.write(line)
                    try:
                        event = json.loads(line)
                    except (ValueError, UnicodeDecodeError):
                        continue
                    kind = event.get('type')
                    item = event.get('item', {})
                    if kind == 'turn.completed':
                        turn_complete = True
                        failure = None
                    elif kind in {'turn.failed', 'error'}:
                        detail = event.get('error') or event.get('message') or 'Codex 执行失败'
                        failure = detail.get('message', str(detail)) if isinstance(detail, dict) else str(detail)
                    elif kind == 'item.started' and item.get('type') == 'command_execution':
                        self.emit(run_id, 'delta', '\n**执行命令**\n```sh\n' + item.get('command', '')[:4000] + '\n```\n')
                    elif kind == 'item.completed':
                        if item.get('type') == 'agent_message':
                            text = item.get('text', '')
                            try:
                                text = json.loads(text).get('summary', text)
                            except (ValueError, AttributeError):
                                pass
                            self.emit(run_id, 'delta', '\n' + text + '\n\n')
                        elif item.get('type') == 'command_execution':
                            output = item.get('aggregated_output', '')
                            if output:
                                self.emit(run_id, 'delta', '\n```text\n' + output[-6000:] + '\n```\n')
                        elif item.get('type') == 'file_change':
                            changes = item.get('changes', [])
                            self.emit(run_id, 'delta', '\n**文件修改**\n' + '\n'.join('- ' + str(c.get('path', '')) for c in changes) + '\n\n')
            code = process.wait()
            with self.lock:
                if self.read(run_id)['status'] == 'DISCARDED':
                    return
            if timed_out.is_set():
                raise AppError(504, 'CODEX_TIMEOUT', 'Codex 执行超时，已停止；项目源码没有改变。')
            if code or failure or not turn_complete:
                detail = (directory / 'stderr.log').read_text(errors='replace')[-1800:]
                for secret in (env.get('CODEX_API_KEY'), env.get('OPENAI_API_KEY')):
                    if secret:
                        detail = detail.replace(secret, '[已隐藏]')
                raise AppError(502, 'CODEX_FAILED', failure or detail or f'Codex 异常退出：{code}')
            if git(workspace, 'rev-parse', 'HEAD').decode().strip() != run['baseCommit']:
                raise AppError(409, 'CANDIDATE_HEAD_CHANGED', 'Codex 修改了 Git 提交历史，拒绝自动采用结果')
            result = json.loads((directory / 'result.json').read_text())
            proposal = self.validate_proposal(run, result)
            atomic_json(directory / 'proposal.json', proposal)
            paths = self.changed_paths(run)
            with self.lock:
                latest = self.read(run_id)
                if latest['status'] == 'DISCARDED':
                    return
                latest.update(status='COMPLETED', finishedAt=now(), changedPaths=paths,
                              metadataOnlyEligible=not paths, resultSummary=result['summary'])
                self.save(latest)
                self.emit(run_id, 'completed', '\n### 执行完成\n\n候选修改已准备好，请审阅后确认；项目源码尚未改变。\n')
        except Exception as exc:
            with self.lock:
                run = self.read(run_id)
                if run['status'] != 'DISCARDED':
                    detail = self.redact(exc.message if isinstance(exc, AppError) else str(exc))
                    run.update(status='FAILED', failureMessage=detail, finishedAt=now())
                    self.save(run)
                    self.emit(run_id, 'failed', '\n执行失败：' + detail + '\n\n候选修改未应用到项目。\n')
        finally:
            if timer:
                timer.cancel()
            self.stop_process(run_id)
            self.processes.pop(run_id, None)

    def validate_proposal(self, run, result):
        if not isinstance(result, dict) or not isinstance(result.get('summary'), str):
            raise AppError(502, 'INVALID_AGENT_RESULT', 'Codex 未返回约定的结果 JSON')
        if run['operation'] == 'delete':
            return None
        feature = Feature.model_validate({'id': run['featureId'], 'description': result['description'],
                                          'codeElements': result['codeElements']}).model_dump()
        if not feature['description'].strip():
            raise AppError(502, 'INVALID_AGENT_RESULT', '功能描述不能为空')
        workspace = self.directory(run['runId']) / 'workspace'
        for element in feature['codeElements']:
            path, sep, name = element.partition('::')
            if not sep or not name.strip() or not safe_file(workspace, path).is_file():
                raise AppError(502, 'INVALID_AGENT_RESULT', '关联元素必须使用真实文件路径::元素名称')
        return feature

    def changed_paths(self, run):
        workspace = self.directory(run['runId']) / 'workspace'
        git(workspace, 'add', '-N', '--all', '--', '.')
        paths = git(workspace, 'diff', '--name-only', '-z', run['baseCommit']).decode().split('\0')
        paths = [p for p in paths if p]
        for path in paths:
            target = safe_file(workspace, path)
            if target.is_symlink():
                raise AppError(415, 'SYMLINK_CHANGE', '暂不支持应用符号链接变更')
        return paths

    def ready(self, project_id, run_id=None):
        run = self.read(run_id, project_id) if run_id else self.current(project_id)
        if not run or run['status'] != 'COMPLETED':
            raise AppError(409, 'CANDIDATE_NOT_READY', '候选修改尚未准备好')
        return run

    def status(self, project_id):
        with self.lock:
            run = self.current(project_id)
            paths = run.get('changedPaths', []) if run and run['status'] == 'COMPLETED' else []
            staged = [p for p in (run or {}).get('stagedPaths', []) if p in paths]
            remaining = [p for p in paths if p not in staged]
            return {'branch': self.app.state.store.read(project_id).get('git', {}).get('branch', ''),
                    'commitScope': 'COMPLETE' if paths and not remaining else 'METADATA_ONLY' if run and run['status'] == 'COMPLETED' and not paths else 'NONE',
                    'stagedPaths': staged, 'unstagedPaths': remaining, 'untrackedPaths': [],
                    'candidatePaths': paths, 'pendingCandidatePaths': paths, 'committedCandidatePaths': [],
                    'unstagedCandidatePaths': remaining}

    def candidate(self, project_id, path, run_id=None):
        with self.lock:
            run = self.ready(project_id, run_id)
            workspace = self.directory(run['runId']) / 'workspace'
            target = safe_file(workspace, path)
            original = git(workspace, 'show', run['baseCommit'] + ':' + path, check=False)
            if len(original) > 1024 * 1024 or b'\0' in original:
                raise AppError(415, 'PREVIEW_UNSUPPORTED', '原文件过大或为二进制，暂不支持在线编辑')
            try:
                original_text = original.decode('utf-8')
            except UnicodeDecodeError as exc:
                raise AppError(415, 'PREVIEW_UNSUPPORTED', '二进制文件无法在线编辑') from exc
            content = read_text(target)
            return {'key': path, 'path': path, 'originalContent': original_text, 'modifiedContent': content,
                    'staged': path in run['stagedPaths'], 'stagedContent': content if path in run['stagedPaths'] else None,
                    'newFile': not bool(git(workspace, 'ls-tree', '--name-only', run['baseCommit'], '--', path)),
                    'deleted': not target.is_file(), 'diff': git(workspace, 'diff', run['baseCommit'], '--', path).decode(errors='replace')}

    def graph(self, project_id, run_id=None):
        with self.lock:
            run = self.ready(project_id, run_id)
            directory = self.directory(run['runId'])
            base = json.loads((directory / 'base-summary.json').read_text())
            feature = next((f for e in base['epics'] for f in e['features'] if f['id'] == run['featureId']), {})
            proposal = json.loads((directory / 'proposal.json').read_text())
            elements = (proposal or feature).get('codeElements', [])
            grouped = {}
            for value in elements:
                path, sep, name = value.partition('::')
                if sep:
                    grouped.setdefault(path, []).append(name)
            for path in run['changedPaths']:
                grouped.setdefault(path, [])
            return {'nodes': [{'id': path, 'funcFile': path, 'methods': list(dict.fromkeys(names)),
                               'type': 'Staged' if path in run['stagedPaths'] else 'Modify' if path in run['changedPaths'] else 'Default'}
                              for path, names in grouped.items()], 'edges': []}

    def update_candidate(self, project_id, path, content, run_id=None):
        with self.lock:
            run = self.ready(project_id, run_id)
            target = safe_file(self.directory(run['runId']) / 'workspace', path)
            if path not in run['changedPaths'] and not git(self.directory(run['runId']) / 'workspace', 'ls-tree', '--name-only', run['baseCommit'], '--', path):
                raise AppError(400, 'NOT_CANDIDATE', '只能编辑本次候选仓库中的已有文件')
            if len(content.encode()) > 1024 * 1024:
                raise AppError(413, 'FILE_TOO_LARGE', '内容超过 1 MiB')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding='utf-8')
            run['stagedPaths'] = [p for p in run['stagedPaths'] if p != path]
            run['changedPaths'] = self.changed_paths(run)
            run['metadataOnlyEligible'] = not run['changedPaths']
            self.save(run)
            return self.candidate(project_id, path, run['runId'])

    def git_operation(self, project_id, operation, path=None, run_id=None):
        with self.lock:
            if operation == 'status':
                return self.status(project_id)
            run = self.read(run_id, project_id) if run_id else self.current(project_id)
            if not run:
                if operation == 'discard':
                    return self.status(project_id)
                raise AppError(409, 'NO_AGENT_RUN', '没有待处理的智能体任务')
            if operation == 'discard':
                if run['status'] == 'COMMITTED':
                    raise AppError(409, 'ALREADY_COMMITTED', '修改已确认，不能作为候选任务放弃')
                run.update(status='DISCARDED', finishedAt=now())
                self.save(run)
                self.stop_process(run['runId'])
                self.emit(run['runId'], 'failed', '任务已放弃，项目源码没有改变。')
                return self.status(project_id)
            run = self.ready(project_id, run['runId'])
            if operation == 'commit':
                return self.accept(run)
            if path not in run['changedPaths']:
                raise AppError(400, 'NOT_CANDIDATE', '文件不属于本次候选修改')
            if operation == 'stage':
                run['stagedPaths'] = list(dict.fromkeys([*run['stagedPaths'], path]))
            elif operation == 'unstage':
                run['stagedPaths'] = [p for p in run['stagedPaths'] if p != path]
            elif operation == 'revert':
                workspace = self.directory(run['runId']) / 'workspace'
                target = safe_file(workspace, path)
                exists = git(workspace, 'ls-tree', '--name-only', run['baseCommit'], '--', path)
                if exists:
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(git(workspace, 'show', run['baseCommit'] + ':' + path))
                else:
                    target.unlink(missing_ok=True)
                git(workspace, 'reset', '--', path)
                run['changedPaths'] = self.changed_paths(run)
                run['stagedPaths'] = [p for p in run['stagedPaths'] if p != path]
                run['metadataOnlyEligible'] = not run['changedPaths']
            else:
                raise AppError(400, 'UNKNOWN_OPERATION', '不支持的候选修改操作')
            self.save(run)
            return self.status(project_id)

    def accept(self, run):
        project_id = run['projectId']
        source = self.app.state.shared.directory(project_id) / 'source'
        summary_path = source.parent / 'summary.json'
        if git(source, 'status', '--porcelain').strip() or git(source, 'rev-parse', 'HEAD').decode().strip() != run['baseCommit']:
            raise AppError(409, 'SOURCE_CHANGED', '项目源码已经变化，不能应用旧候选修改')
        if digest(summary_path) != run['summaryHash']:
            raise AppError(409, 'SUMMARY_CHANGED', '项目摘要已经变化，不能应用旧候选修改')
        directory = self.directory(run['runId'])
        current_paths = self.changed_paths(run)
        if set(current_paths) != set(run['changedPaths']):
            raise AppError(409, 'CANDIDATE_CHANGED', '候选文件已经变化，请刷新后重新确认')
        if set(current_paths) != set(run['stagedPaths']):
            raise AppError(409, 'CONFIRM_ALL_REQUIRED', '请先确认全部候选文件，再应用本次功能变更')
        result = json.loads((directory / 'result.json').read_text())
        proposal = self.validate_proposal(run, result)
        summary = json.loads(summary_path.read_text())
        epic = next(e for e in summary['epics'] if e['id'] == run['moduleId'])
        if run['operation'] == 'delete':
            epic['features'] = [f for f in epic['features'] if f['id'] != run['featureId']]
        elif run['operation'] == 'add':
            epic['features'].append(proposal)
        else:
            epic['features'] = [proposal if f['id'] == run['featureId'] else f for f in epic['features']]
        summary = ProjectSummary.model_validate(summary).model_dump()
        patch = git(directory / 'workspace', 'diff', '--binary', run['baseCommit'])
        if patch:
            git(source, 'apply', '--check', '--index', '-', input=patch)
        try:
            if patch:
                git(source, 'apply', '--index', '-', input=patch)
            git(source, 'commit', '--allow-empty', '--no-gpg-sign', '-m', 'FeatX: ' + run['operation'] + ' ' + run['request'][:80])
            atomic_json(summary_path, summary)
        except Exception:
            # Only our own just-applied transaction exists: source was required clean above.
            git(source, 'reset', '--hard', run['baseCommit'])
            raise
        project = self.app.state.store.read(project_id)
        project.setdefault('git', {})['commit'] = git(source, 'rev-parse', 'HEAD').decode().strip()
        self.app.state.store.write(project)
        run.update(status='COMMITTED', acceptedAt=now())
        self.save(run)
        return {'featureId': run['featureId'], 'commitScope': 'COMPLETE' if patch else 'METADATA_ONLY',
                'status': self.status(project_id)}

    def shutdown(self):
        self.stopping = True
        for run_id in list(self.processes):
            self.stop_process(run_id)
