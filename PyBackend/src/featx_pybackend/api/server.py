"""RepoSummary HTTP 服务与摘要文件生成流程。"""
from __future__ import annotations

import copy
import json
import os
import re
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from uuid import uuid4

ROOT = Path(os.getenv('SHARED_PROJECT_ROOT', '/home/lixutian/Projects_SSD/FeatX-shared')).resolve()
DELAY = 10.0
LOCK = threading.RLock()
JOBS = {}
ACTIVE = {}
EPICS = [
    ('项目接入与管理', ['创建项目', '上传项目文件', '关联仓库', '维护项目信息', '查询项目', '删除项目']),
    ('业务流程与处理', ['接收请求', '校验输入', '执行业务操作', '处理异常', '维护状态', '返回处理结果']),
    ('数据展示与交互', ['组织功能主题', '展示功能项', '展示文件节点', '选择关联元素', '展示运行进度', '刷新页面数据']),
]


class ServiceError(Exception):
    def __init__(self, status, code, message, **details):
        self.status, self.payload = status, {'error': {'code': code, 'message': message, **details}}


def project_dir(project_id):
    if not isinstance(project_id, str) or not re.fullmatch(r'[0-9a-f]{32}', project_id):
        raise ServiceError(400, 'INVALID_PROJECT_ID', 'projectId 必须是主系统分配的 ID')
    path = ROOT / project_id
    if path.is_symlink() or not (path / 'source').is_dir() or (path / 'source').is_symlink():
        raise ServiceError(404, 'SOURCE_NOT_FOUND', '请先将项目上传到共享目录')
    return path


def snapshot(run_id):
    with LOCK:
        if run_id not in JOBS:
            raise ServiceError(404, 'RUN_NOT_FOUND', '任务不存在或服务已重启，请重新总结')
        job = copy.deepcopy(JOBS[run_id])
    start = job.pop('_start')
    stop = job.pop('_stop', None)
    job['elapsedMs'] = round(((stop or time.monotonic()) - start) * 1000)
    return job


def set_phase(run_id, index, fraction, overall):
    with LOCK:
        job = JOBS[run_id]
        job['status'] = 'running'
        job['currentStageIndex'] = index + 1
        job['overallPercent'] = round(overall * 100)
        phase = job['stages'][index]
        phase['status'] = 'running'
        phase['progress'] = {'percent': round(fraction * 100), 'completed': round(fraction * 100),
                             'total': 100, 'unit': 'percent'}
        phase['message'] = f"{phase['name']}：{round(fraction * 100)}%"
        for prior in job['stages'][:index]:
            prior['status'] = 'complete'
            prior['progress'] = {'percent': 100, 'completed': 100, 'total': 100, 'unit': 'percent'}
            prior['message'] = '阶段已完成'


def generate(project_id, run_id, files):
    epics = []
    for e, (title, feature_titles) in enumerate(EPICS):
        features = []
        for f, feature_title in enumerate(feature_titles):
            number = e * 6 + f
            count = 8 + (number * 7) % 23  # 8..30; repeatable and independent of source language.
            selected = [files[(number + i) % len(files)] for i in range(min(3, len(files)))]
            elements = [f'{selected[i % len(selected)]}::Element_E{e+1}_F{f+1}_{i+1:02d}'
                        for i in range(count)]
            features.append({'id': f'feature_{e+1}_{f+1}',
                             'description': feature_title,
                             'codeElements': elements})
        epics.append({'id': f'epic_{e+1}', 'description': title, 'features': features})
    return {'projectId': project_id, 'runId': run_id,
            'description': '项目涵盖' + '、'.join(epic['description'] for epic in epics) + '，提供从项目接入、流程处理到数据展示的功能。',
            'epics': epics}


def run_job(project_id, run_id):
    temporary = None
    try:
        directory = project_dir(project_id)
        source = directory / 'source'
        files = []
        for base, directories, names in os.walk(source, followlinks=False):
            directories[:] = sorted(d for d in directories if d != '.git' and not (Path(base) / d).is_symlink())
            for name in sorted(names):
                path = Path(base) / name
                if path.is_file() and not path.is_symlink():
                    files.append(path.relative_to(source).as_posix())
        if not files:
            raise ValueError('项目中没有文件，无法生成文件节点')
        result = generate(project_id, run_id, files)
        # 按阶段推进任务，写入完成后再发布完成状态。
        with LOCK:
            start = JOBS[run_id]['_start']
        while True:
            elapsed = time.monotonic() - start
            if elapsed >= DELAY:
                break
            if elapsed < 2:
                index, fraction = 0, elapsed / 2
            elif elapsed < 9:
                index, fraction = 1, (elapsed - 2) / 7
            else:
                index, fraction = 2, elapsed - 9
            set_phase(run_id, index, fraction, min(elapsed / DELAY, .99))
            time.sleep(min(.2, max(0, DELAY - elapsed)))
        set_phase(run_id, 2, .99, .99)
        temporary = directory / f'.summary-{run_id}.tmp'
        # open('x') requests 0666, preserving the shared directory's default ACL (unlike mkstemp's 0600).
        with temporary.open('x', encoding='utf-8') as file:
            json.dump(result, file, ensure_ascii=False, indent=2)
            file.flush()
            os.fsync(file.fileno())
        temporary.replace(directory / 'summary.json')
        with LOCK:
            job = JOBS[run_id]
            for phase in job['stages']:
                phase.update(status='complete', message='阶段已完成',
                             progress={'percent': 100, 'completed': 100, 'total': 100, 'unit': 'percent'})
            job.update(status='complete', currentStageIndex=None, overallPercent=100, _stop=time.monotonic())
    except Exception as exc:
        with LOCK:
            job = JOBS[run_id]
            index = (job['currentStageIndex'] or 1) - 1
            job['stages'][index].update(status='failed', message=str(exc))
            job.update(status='failed', error=str(exc), _stop=time.monotonic())
    finally:
        if temporary:
            temporary.unlink(missing_ok=True)
        with LOCK:
            if ACTIVE.get(project_id) == run_id:
                ACTIVE.pop(project_id, None)


def start_job(body):
    project_id = body.get('projectId')
    project_dir(project_id)
    hints = body.get('hints') or {}
    if not isinstance(hints, dict):
        raise ServiceError(400, 'INVALID_HINTS', 'hints 必须是对象')
    with LOCK:
        if project_id in ACTIVE:
            raise ServiceError(409, 'SUMMARY_RUNNING', '该项目正在总结', runId=ACTIVE[project_id])
        run_id = uuid4().hex
        JOBS[run_id] = {
            'projectId': project_id, 'runId': run_id, 'status': 'queued',
            'totalStages': 3, 'currentStageIndex': None, 'overallPercent': 0,
            'hints': hints, 'message': '正在生成功能摘要',
            'stages': [{'id': key, 'name': name, 'status': 'pending', 'message': '等待执行', 'progress': None}
                       for key, name in [('read', '准备项目'), ('generate', '生成功能摘要'), ('save', '写入 summary.json')]],
            'error': None, '_start': time.monotonic(),
        }
        ACTIVE[project_id] = run_id
        accepted = snapshot(run_id)
    threading.Thread(target=run_job, args=(project_id, run_id), daemon=True).start()
    return accepted


class RepoSummaryHandler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'
    timeout = 15

    def do_GET(self):
        from urllib.parse import urlsplit
        path = urlsplit(self.path).path
        try:
            if path == '/health':
                return self.respond(200, {'status': 'ready', 'businessReady': True})
            if re.fullmatch(r'/summary/[0-9a-f]{32}', path):
                return self.respond(200, snapshot(path.rsplit('/', 1)[1]))
            raise ServiceError(404, 'NOT_FOUND', '接口不存在')
        except ServiceError as exc:
            self.respond(exc.status, exc.payload)

    def do_POST(self):
        try:
            if self.path != '/summary':
                raise ServiceError(404, 'NOT_FOUND', '接口不存在')
            if self.headers.get('Transfer-Encoding'):
                raise ServiceError(400, 'INVALID_BODY', '请使用 Content-Length')
            try:
                length = int(self.headers.get('Content-Length', '0'))
                if not 0 <= length <= 1024 * 1024:
                    raise ValueError()
                body = json.loads(self.rfile.read(length).decode('utf-8'))
                if not isinstance(body, dict):
                    raise ValueError()
            except (ValueError, UnicodeError):
                raise ServiceError(400, 'INVALID_BODY', '请发送有效 JSON 对象，最大 1 MiB')
            self.respond(202, start_job(body))
        except ServiceError as exc:
            self.respond(exc.status, exc.payload)

    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode('utf-8')
        self.close_connection = True
        try:
            self.send_response(status)
            self.send_header('Content-Type', 'application/json; charset=utf-8')
            self.send_header('Content-Length', str(len(body)))
            self.send_header('Connection', 'close')
            self.end_headers()
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass


def main():
    host = os.getenv('REPOSUMMARY_HTTP_HOST', '127.0.0.1')
    port = int(os.getenv('REPOSUMMARY_HTTP_PORT', '18091'))
    ROOT.mkdir(parents=True, exist_ok=True)
    print(f'[RepoSummary] {host}:{port}; shared={ROOT}', flush=True)
    with ThreadingHTTPServer((host, port), RepoSummaryHandler) as server:
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass


if __name__ == '__main__':
    main()
