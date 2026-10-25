"""Read the model picker from the pinned CLI, without starting a model task."""
import json
import os
import queue
import signal
import subprocess
import threading
import time


def list_models(binary, home, env):
    process = subprocess.Popen([str(binary), 'app-server'], cwd=home, env=env,
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, text=True, encoding='utf-8',
                               start_new_session=True)
    messages = queue.Queue()
    deadline = time.monotonic() + 12

    def receive():
        try:
            for line in process.stdout:
                try:
                    messages.put(json.loads(line))
                except ValueError:
                    pass
        finally:
            messages.put(None)
    threading.Thread(target=receive, daemon=True).start()

    def send(message):
        process.stdin.write(json.dumps(message) + '\n')
        process.stdin.flush()

    def response(request_id):
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('读取 Codex 模型列表超时')
            try:
                message = messages.get(timeout=remaining)
            except queue.Empty as exc:
                raise TimeoutError('读取 Codex 模型列表超时') from exc
            if message is None:
                raise RuntimeError('Codex 模型目录进程已退出，请检查原生配置')
            if not isinstance(message, dict):
                continue
            if message.get('id') == request_id:
                if 'error' in message:
                    raise RuntimeError('Codex 无法读取模型目录，请检查配置或登录状态')
                return message['result']

    try:
        send({'id': 1, 'method': 'initialize', 'params': {'clientInfo': {'name': 'featx', 'version': '1.0.0'}}})
        response(1)
        send({'method': 'initialized'})
        result, cursor, request_id = [], None, 2
        seen = set()
        while True:
            params = {'limit': 100, 'includeHidden': False}
            if cursor:
                params['cursor'] = cursor
            send({'id': request_id, 'method': 'model/list', 'params': params})
            page = response(request_id)
            result.extend(item for item in page.get('data', []) if isinstance(item, dict))
            cursor = page.get('nextCursor')
            if not cursor:
                return result
            if cursor in seen:
                raise RuntimeError('Codex 模型目录返回了重复分页标记')
            seen.add(cursor)
            request_id += 1
    finally:
        if process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=2)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            except ProcessLookupError:
                pass
        process.stdin.close()
