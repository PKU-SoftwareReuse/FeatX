"""Run the agent in the project worktree; review, commit or revert with Git."""
from __future__ import annotations

import codecs
import difflib
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
from .agent_activity import project_event
from .models import Feature, ProjectSummary

ROOT = Path(__file__).resolve().parents[2]
# Use the pinned native executable: the systemd service does not inherit nvm's PATH.
BINARY = ROOT / 'tools/codex/node_modules/@openai/codex-linux-x64/vendor/x86_64-unknown-linux-musl/bin/codex'
VERSION = json.loads((ROOT / 'tools/codex/package.json').read_text())['dependencies']['@openai/codex']
CODEX_HOME = ROOT / 'data/codex-home'
RUN_TIMEOUT_SECONDS = 1800
ACTIVE = {'PREPARED', 'RUNNING', 'COMPLETED'}


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
        raise AppError(400, 'INVALID_PATH', '不能访问项目之外的文件')
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
        raise AppError(500, 'AGENT_GIT_FAILED', '项目 Git 操作失败或超时') from exc
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
                direct = run.get('executionMode') == 'direct'
                run.update(status='FAILED', requiresCleanup=direct, failureMessage=
                           '主后端已重启，请检查并放弃本次未提交修改后重试。' if direct else '主后端已重启，本次执行被中断；旧候选内容未应用。')
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
        if run and (run['status'] in ACTIVE or run.get('requiresCleanup')):
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

    def invalidate_models(self):
        with self.model_cache_lock:
            self.model_cache = None

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
        options = [{'value': 'default', 'label': (base['model'] + '（配置默认）') if base.get('model') else '智能体默认模型',
                    'model': base.get('model'), 'profile': None, 'provider': base.get('model_provider', 'openai')}]
        native, model_error = self.native_models() if self.binary.is_file() else ([], '智能体执行器尚未安装')
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
            options.append({'value': 'profile:' + name, 'label': f"{name} · {model or '智能体默认模型'}",
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
        notice = '' if configured or custom_provider else '尚未检测到智能体认证信息，请配置服务商或完成执行器登录。'
        if model_error:
            notice = (notice + ' ' if notice else '') + model_error + '；暂仅显示本地配置，请稍后重试。'
        scanner = getattr(self.app.state, 'model_discovery', None)
        sync = scanner.snapshot() if scanner else None
        if sync and sync.get('message'):
            notice = (notice + ' ' if notice else '') + sync['message']
        return {'models': [option['value'] for option in options], 'modelOptions': options, 'modelSync': sync,
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

    def append_detail(self, run_id, text):
        if text:
            with self.lock:
                with (self.directory(run_id) / 'details.log').open('a', encoding='utf-8') as stream:
                    stream.write(self.redact(text))

    def activity_history(self, project_id, run_id):
        run = self.read(run_id, project_id)
        directory = self.directory(run_id)
        records = {}
        if run.get('activityVersion'):
            path = directory / 'events.jsonl'
            if path.exists():
                with path.open(encoding='utf-8') as stream:
                    for line in stream:
                        try:
                            event = json.loads(line)
                            if event.get('event') == 'activity':
                                item = json.loads(event['text'])
                                records[item['id']] = item
                        except (ValueError, KeyError):
                            continue
        else:
            # Reconstruct historical activities from native events, not model prose.
            # Original per-operation timestamps are unavailable: do not invent durations.
            path = directory / 'stdout.jsonl'
            starts = {}
            if path.exists():
                with path.open(encoding='utf-8', errors='replace') as stream:
                    for line in stream:
                        try:
                            item, _ = project_event(json.loads(line), starts)
                            if item:
                                item.update(startedAt=None, updatedAt=None, elapsedMs=None)
                                records[item['id']] = item
                        except (ValueError, KeyError, TypeError):
                            continue
        return {'items': list(records.values())}

    def detail_page(self, project_id, run_id, cursor=0):
        run = self.read(run_id, project_id)
        directory = self.directory(run_id)
        path = directory / 'details.log'
        if not path.exists():
            # Historical runs retain readable Markdown in events.jsonl.
            # Convert lazily once; do not touch their result/worktree or re-run anything.
            history = directory / 'events.jsonl'
            with self.lock:
                if not path.exists():
                    with path.open('w', encoding='utf-8') as target:
                        if history.exists():
                            with history.open(encoding='utf-8') as source:
                                for line in source:
                                    try:
                                        event = json.loads(line)
                                        if event.get('event') != 'activity':
                                            target.write(self.redact(event.get('text', '')))
                                    except ValueError:
                                        continue
        limit = 48 * 1024
        with path.open('rb') as stream:
            size = stream.seek(0, os.SEEK_END)
            if cursor < 0 or cursor > size:
                raise AppError(400, 'INVALID_CURSOR', '日志位置已失效，请重新打开日志')
            stream.seek(cursor)
            raw = stream.read(limit)
            decoder = codecs.getincrementaldecoder('utf-8')(errors='replace')
            text = decoder.decode(raw, final=cursor + len(raw) >= size)
            remaining, _ = decoder.getstate()
            next_cursor = cursor + len(raw) - len(remaining)
        return {'cursor': cursor, 'nextCursor': next_cursor, 'hasMore': next_cursor < size,
                'running': run['status'] in ('PREPARED', 'RUNNING'), 'text': text}

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
                raise AppError(503, 'CODEX_NOT_INSTALLED', '智能体执行器未安装，请先安装项目运行依赖')
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
                   'configuredModel': selection['model'], 'executionMode': 'direct', 'activityVersion': 1, 'requiresCleanup': False,
                   'initialUntracked': [name for name in git(source, 'ls-files', '--others', '-z').decode().split('\0') if name],
                   'status': 'PREPARED', 'createdAt': now(), 'eventCount': 0,
                   'baseCommit': git(source, 'rev-parse', 'HEAD').decode().strip(),
                   'summaryHash': digest(source.parent / 'summary.json'), 'stagedPaths': [],
                   'changedPaths': [], 'metadataOnlyEligible': False, 'failureMessage': None}
            atomic_json(directory / 'base-summary.json', summary)
            self.save(run)
            project['agentRunId'] = run_id
            self.app.state.store.write(project)
            self.emit(run_id, 'status', '### 正在准备功能变更\n\n已记录 Git 起点，将直接修改项目工作区；完成后请审阅并提交，或放弃回退。\n\n')
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
                   'files': snippets, 'note': '文件片段可能截断，必要时读取当前项目中的完整文件。'}
        return (Path(__file__).parent / 'prompts/feature_edit.md').read_text() + json.dumps(context, ensure_ascii=False, indent=2)

    def stop_process(self, run_id):
        process = self.processes.get(run_id)
        if process is None:
            run = self.read(run_id)
            pid = run.get('executorPid')
            if isinstance(pid, int) and pid > 1:
                try:
                    command = Path(f'/proc/{pid}/cmdline').read_bytes().split(b'\0')
                    marker = str(self.directory(run_id) / 'final-message.md').encode()
                    # Never signal a reused PID unless it is the exact executor command for this run.
                    if str(self.binary).encode() in command and marker in command:
                        os.killpg(pid, signal.SIGKILL)
                except (FileNotFoundError, ProcessLookupError):
                    pass
            return
        if process.poll() is None:
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
            workspace = source
            if git(workspace, 'rev-parse', 'HEAD').decode().strip() != run['baseCommit']:
                raise AppError(409, 'SOURCE_CHANGED', '项目版本已变化，请重新提交任务')
            prompt = self.prompt(run, workspace)
            (directory / 'prompt.txt').write_text(prompt, encoding='utf-8')
            env = self.environment()
            command = [str(self.binary), 'exec', '--json', '--ephemeral',
                       '--color', 'never', '-C', str(workspace),
                       '-o', str(directory / 'final-message.md')]
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
                latest['executorPid'] = process.pid
                self.save(latest)
            self.append_detail(run_id, '智能体开始执行\n模型：' + run.get('modelLabel', '') + '\n')
            self.emit(run_id, 'status', '### 智能体执行中\n\n模型：' + run.get('modelLabel', '智能体默认模型') + '\n\n')
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
            activities = {}
            with (directory / 'stdout.jsonl').open('wb') as raw:
                for line in process.stdout:
                    raw.write(line)
                    try:
                        event = json.loads(line)
                    except (ValueError, UnicodeDecodeError):
                        self.append_detail(run_id, line.decode('utf-8', errors='replace'))
                        continue
                    activity, detail = project_event(event, activities)
                    self.append_detail(run_id, detail)
                    if activity:
                        self.emit(run_id, 'activity', json.dumps(activity, ensure_ascii=False))
                    kind = event.get('type')
                    if kind == 'turn.completed':
                        turn_complete = True
                        failure = None
                    elif kind in {'turn.failed', 'error'}:
                        detail = event.get('error') or event.get('message') or '智能体执行失败'
                        failure = detail.get('message', str(detail)) if isinstance(detail, dict) else str(detail)
            code = process.wait()
            diagnostic = directory / 'stderr.log'
            if diagnostic.exists():
                with diagnostic.open(encoding='utf-8', errors='replace') as stream:
                    for line in stream:
                        self.append_detail(run_id, line)
            with self.lock:
                if self.read(run_id)['status'] == 'DISCARDED':
                    return
            if timed_out.is_set():
                raise AppError(504, 'CODEX_TIMEOUT', '智能体执行超时，已停止。')
            if code or failure or not turn_complete:
                detail = (directory / 'stderr.log').read_text(errors='replace')[-1800:]
                for secret in (env.get('CODEX_API_KEY'), env.get('OPENAI_API_KEY')):
                    if secret:
                        detail = detail.replace(secret, '[已隐藏]')
                raise AppError(502, 'CODEX_FAILED', failure or detail or f'智能体异常退出：{code}')
            if git(workspace, 'rev-parse', 'HEAD').decode().strip() != run['baseCommit']:
                raise AppError(409, 'CANDIDATE_HEAD_CHANGED', '智能体修改了 Git 提交历史，需要人工检查；不会覆盖已有提交')
            final_message = directory / 'final-message.md'
            result_text = final_message.read_text(encoding='utf-8') if final_message.exists() else ''
            paths = self.changed_paths(run)
            with self.lock:
                latest = self.read(run_id)
                if latest['status'] == 'DISCARDED':
                    return
                latest.update(status='COMPLETED', finishedAt=now(), changedPaths=paths,
                              metadataOnlyEligible=not paths, resultSummary=result_text, requiresCleanup=False)
                self.save(latest)
                self.emit(run_id, 'completed', '\n### 执行完成\n\n项目工作区的修改已生成、尚未提交。请查看差异后确认提交，或放弃回退。\n')
        except Exception as exc:
            # Stop all writes before restoring a failed direct execution.
            self.stop_process(run_id)
            with self.lock:
                run = self.read(run_id)
                if run['status'] != 'DISCARDED':
                    detail = self.redact(exc.message if isinstance(exc, AppError) else str(exc))
                    cleanup = False
                    try:
                        self.rollback(run)
                        note = '本次未提交修改已回退到任务开始前。'
                    except Exception as restore_error:
                        cleanup = True
                        note = '自动回退未完成，请先处理工作区：' + self.redact(restore_error.message if isinstance(restore_error, AppError) else str(restore_error))
                    run.update(status='FAILED', failureMessage=detail + ' ' + note, finishedAt=now(), requiresCleanup=cleanup,
                               changedPaths=[], stagedPaths=[])
                    self.save(run)
                    self.append_detail(run_id, '\n执行失败：' + detail + '\n' + note + '\n')
                    self.emit(run_id, 'failed', '\n执行失败：' + detail + '\n\n' + note + '\n')

        finally:
            if timer:
                timer.cancel()
            self.stop_process(run_id)
            self.processes.pop(run_id, None)

    def workspace(self, run):
        if run.get('executionMode') == 'direct':
            return self.app.state.shared.directory(run['projectId']) / 'source'
        return self.directory(run['runId']) / 'workspace'

    def check_head(self, run):
        if git(self.workspace(run), 'rev-parse', 'HEAD').decode().strip() != run['baseCommit']:
            raise AppError(409, 'SOURCE_CHANGED', '项目已有新的 Git 提交，请先人工处理；不会覆盖提交历史')

    def feature_proposal(self, run):
        if run['operation'] == 'delete':
            return None
        base = json.loads((self.directory(run['runId']) / 'base-summary.json').read_text())
        before = next((f for e in base['epics'] for f in e['features'] if f['id'] == run['featureId']), {})
        return Feature.model_validate({'id': run['featureId'], 'description': run['request'],
                                       'codeElements': before.get('codeElements', [])}).model_dump()

    def dirty_paths(self, run):
        workspace = self.workspace(run)
        tracked = git(workspace, 'diff', '--no-renames', '--name-only', '-z', run['baseCommit']).decode().split('\0')
        untracked = git(workspace, 'ls-files', '--others', '--exclude-standard', '-z').decode().split('\0')
        return list(dict.fromkeys(p for p in [*tracked, *untracked] if p))

    def changed_paths(self, run):
        workspace = self.workspace(run)
        paths = self.dirty_paths(run)
        protected = set(run.get('initialUntracked', []))
        for path in paths:
            if path in protected:
                raise AppError(409, 'PREEXISTING_FILE_CHANGED', '任务触及原有的忽略文件，请人工处理：' + path)
            target = safe_file(workspace, path)
            if target.is_symlink():
                raise AppError(415, 'SYMLINK_CHANGE', '暂不支持审阅符号链接变更')
        return paths

    def rollback(self, run, paths=None):
        if run.get('executionMode') != 'direct':
            return
        self.check_head(run)
        workspace = self.workspace(run)
        changed = self.dirty_paths(run) if paths is None else paths
        protected = set(run.get('initialUntracked', []))
        if protected.intersection(changed):
            raise AppError(409, 'PREEXISTING_FILE_CHANGED', '原有忽略文件已发生变化，保留文件并等待人工处理')
        tracked = set(git(workspace, 'ls-tree', '-r', '--name-only', '-z', run['baseCommit']).decode().split('\0'))
        # Remove only new files of this clean-start run, never git clean -x or all untracked data.
        for path in changed:
            if path in tracked:
                continue
            target = workspace / path
            if not target.parent.resolve().is_relative_to(workspace.resolve()):
                raise AppError(409, 'INVALID_PATH', '无法安全回退项目外部路径')
            if target.is_dir() and not target.is_symlink():
                raise AppError(409, 'UNSUPPORTED_CHANGE', '请先人工处理目录类型变更：' + path)
            target.unlink(missing_ok=True)
            git(workspace, 'reset', '--', path)
            parent = target.parent
            while parent != workspace:
                try:
                    parent.rmdir()
                except OSError:
                    break
                parent = parent.parent
        restore = [path for path in changed if path in tracked]
        for path in restore:
            if not (workspace / path).parent.resolve().is_relative_to(workspace.resolve()):
                raise AppError(409, 'INVALID_PATH', '无法安全回退项目外部路径')
        if restore:
            git(workspace, 'restore', '--source=' + run['baseCommit'], '--staged', '--worktree', '--', *restore)

    def ready(self, project_id, run_id=None):
        run = self.read(run_id, project_id) if run_id else self.current(project_id)
        if not run or run['status'] != 'COMPLETED':
            raise AppError(409, 'CANDIDATE_NOT_READY', '本次修改尚未准备好')
        return run

    def status(self, project_id):
        with self.lock:
            run = self.current(project_id)
            if run and run['status'] == 'COMPLETED':
                self.check_head(run)
                run['changedPaths'] = self.changed_paths(run)
                actual_staged = git(self.workspace(run), 'diff', '--cached', '--no-renames', '--name-only', '-z', run['baseCommit']).decode().split('\0')
                unstaged = set(git(self.workspace(run), 'diff', '--name-only', '-z').decode().split('\0'))
                run['stagedPaths'] = [p for p in actual_staged if p in run['changedPaths'] and p not in unstaged]
                run['metadataOnlyEligible'] = not run['changedPaths']
                self.save(run)
            paths = run.get('changedPaths', []) if run and run['status'] == 'COMPLETED' else []
            staged = [p for p in (run or {}).get('stagedPaths', []) if p in paths]
            remaining = [p for p in paths if p not in staged]
            return {'branch': self.app.state.store.read(project_id).get('git', {}).get('branch', ''),
                    'commitScope': 'COMPLETE' if paths and not remaining else 'METADATA_ONLY' if run and run['status'] == 'COMPLETED' and not paths else 'NONE',
                    'stagedPaths': staged, 'unstagedPaths': remaining, 'untrackedPaths': [],
                    'candidatePaths': paths, 'pendingCandidatePaths': paths, 'committedCandidatePaths': [],
                    'unstagedCandidatePaths': remaining, 'requiresCleanup': bool(run and run.get('requiresCleanup'))}

    def candidate(self, project_id, path, run_id=None):
        with self.lock:
            run = self.ready(project_id, run_id)
            workspace = self.workspace(run)
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
                    'staged': path in run['stagedPaths'], 'stagedContent': git(workspace, 'show', ':' + path, check=False).decode('utf-8') if path in run['stagedPaths'] else None,
                    'newFile': not bool(git(workspace, 'ls-tree', '--name-only', run['baseCommit'], '--', path)),
                    'deleted': not target.is_file(), 'diff': ''.join(difflib.unified_diff(original_text.splitlines(keepends=True), content.splitlines(keepends=True), fromfile='a/' + path, tofile='b/' + path))}

    def graph(self, project_id, run_id=None):
        with self.lock:
            run = self.ready(project_id, run_id)
            directory = self.directory(run['runId'])
            base = json.loads((directory / 'base-summary.json').read_text())
            feature = next((f for e in base['epics'] for f in e['features'] if f['id'] == run['featureId']), {})
            proposal = self.feature_proposal(run)
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
            self.check_head(run)
            target = safe_file(self.workspace(run), path)
            if path not in run['changedPaths'] and not git(self.workspace(run), 'ls-tree', '--name-only', run['baseCommit'], '--', path):
                raise AppError(400, 'NOT_CANDIDATE', '只能编辑当前项目中已有或本次新增的文件')
            if len(content.encode()) > 1024 * 1024:
                raise AppError(413, 'FILE_TOO_LARGE', '内容超过 1 MiB')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding='utf-8')
            git(self.workspace(run), 'reset', '--', path)
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
            if self.current(project_id)['runId'] != run['runId']:
                raise AppError(409, 'STALE_RUN', '不能操作历史任务，请刷新项目')
            if operation == 'discard':
                if run['status'] == 'COMMITTED':
                    raise AppError(409, 'ALREADY_COMMITTED', '修改已经提交，不能按未提交任务放弃')
                if run['status'] == 'DISCARDED':
                    return self.status(project_id)
                self.stop_process(run['runId'])
                try:
                    if run['status'] != 'FAILED' or run.get('requiresCleanup'):
                        self.rollback(run)
                except Exception:
                    run.update(status='FAILED', requiresCleanup=True, failureMessage='回退未完成，请处理工作区后重试')
                    self.save(run)
                    raise
                run.update(status='DISCARDED', finishedAt=now(), requiresCleanup=False, changedPaths=[], stagedPaths=[])
                self.save(run)
                self.emit(run['runId'], 'failed', '任务已放弃，本次未提交修改已回退。')
                return self.status(project_id)
            run = self.ready(project_id, run['runId'])
            if run.get('executionMode') != 'direct':
                raise AppError(409, 'LEGACY_RUN', '这是旧执行方式的任务，请放弃后重新生成')
            self.check_head(run)
            if operation == 'commit':
                return self.accept(run)
            run['changedPaths'] = self.changed_paths(run)
            if path not in run['changedPaths']:
                raise AppError(400, 'NOT_CANDIDATE', '文件不属于本次未提交修改')
            workspace = self.workspace(run)
            if operation == 'stage':
                git(workspace, 'add', '--all', '--', path)
                run['stagedPaths'] = list(dict.fromkeys([*run['stagedPaths'], path]))
            elif operation == 'unstage':
                git(workspace, 'reset', '--', path)
                run['stagedPaths'] = [p for p in run['stagedPaths'] if p != path]
            elif operation == 'revert':
                self.rollback(run, [path])
                run['changedPaths'] = self.changed_paths(run)
                run['stagedPaths'] = [p for p in run['stagedPaths'] if p != path]
                run['metadataOnlyEligible'] = not run['changedPaths']
            else:
                raise AppError(400, 'UNKNOWN_OPERATION', '不支持的修改操作')
            self.save(run)
            return self.status(project_id)

    def accept(self, run):
        project_id = run['projectId']
        source = self.workspace(run)
        summary_path = source.parent / 'summary.json'
        self.check_head(run)
        if digest(summary_path) != run['summaryHash']:
            raise AppError(409, 'SUMMARY_CHANGED', '项目摘要已经变化，不能覆盖，请先处理后再确认')
        current_paths = self.changed_paths(run)
        staged = [p for p in git(source, 'diff', '--cached', '--no-renames', '--name-only', '-z', run['baseCommit']).decode().split('\0') if p]
        if current_paths and not staged:
            raise AppError(409, 'NO_SELECTED_FILES', '请先选择至少一个要保留的文件，或使用全部保留并提交')
        unstaged = set(git(source, 'diff', '--no-renames', '--name-only', '-z').decode().split('\0'))
        if set(staged).intersection(unstaged):
            raise AppError(409, 'SELECTION_CHANGED', '已选择的文件又发生变化，请重新审阅并选择保留')
        if set(current_paths) != set(run['changedPaths']):
            raise AppError(409, 'CHANGES_CHANGED', '修改文件列表发生变化，请刷新后重新确认提交')
        remaining = [path for path in current_paths if path not in set(staged)]
        summary = json.loads(summary_path.read_text())
        epic = next(e for e in summary['epics'] if e['id'] == run['moduleId'])
        proposal = self.feature_proposal(run)
        if run['operation'] == 'delete':
            epic['features'] = [f for f in epic['features'] if f['id'] != run['featureId']]
        elif run['operation'] == 'add':
            epic['features'].append(proposal)
        else:
            epic['features'] = [proposal if f['id'] == run['featureId'] else f for f in epic['features']]
        ProjectSummary.model_validate(summary)
        if remaining:
            # The confirmation dialog explicitly authorizes discarding unselected files.
            # Revert only those paths; selected index entries remain untouched.
            self.rollback(run, remaining)
            run['discardedPaths'] = list(dict.fromkeys([*run.get('discardedPaths', []), *remaining]))
            run['changedPaths'] = self.changed_paths(run)
            self.save(run)
        committed = None
        try:
            git(source, 'commit', '--allow-empty', '--no-gpg-sign', '-m', 'FeatX: ' + run['operation'] + ' ' + run['request'][:80])
            committed = git(source, 'rev-parse', 'HEAD').decode().strip()
            atomic_json(summary_path, summary)
        except Exception:
            # Undo only the commit just made here, preserving all worktree/index changes for retry.
            if committed and git(source, 'rev-parse', 'HEAD').decode().strip() == committed:
                git(source, 'reset', '--soft', run['baseCommit'])
            raise
        project = self.app.state.store.read(project_id)
        project.setdefault('git', {})['commit'] = committed
        self.app.state.store.write(project)
        run.update(status='COMMITTED', acceptedAt=now(), requiresCleanup=False, committedPaths=staged)
        self.save(run)
        return {'featureId': run['featureId'],
                'commitScope': 'PARTIAL' if run.get('discardedPaths') else 'COMPLETE' if current_paths else 'METADATA_ONLY',
                'committedPaths': staged, 'discardedPaths': run.get('discardedPaths', []),
                'status': self.status(project_id)}

    def shutdown(self):
        self.stopping = True
        for run_id in list(self.processes):
            self.stop_process(run_id)
