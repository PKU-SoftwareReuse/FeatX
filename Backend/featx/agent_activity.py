"""Compact UI events derived from executor events, never from model reply schemas."""
import json
import shlex
import time
from datetime import datetime, timezone
from pathlib import PurePosixPath


def now():
    return datetime.now(timezone.utc).isoformat()


def command_kind(command):
    try:
        parts = shlex.split(command)
        if len(parts) >= 3 and PurePosixPath(parts[0]).name in {'sh', 'bash', 'zsh'} and parts[1] in {'-c', '-lc'}:
            parts = shlex.split(parts[2])
        if parts and parts[0] == 'cd' and '&&' in parts:
            parts = parts[parts.index('&&') + 1:]
        executable = PurePosixPath(parts[0]).name if parts else ''
        # Conservative labels: don't claim what arbitrary scripts achieve.
        if executable in {'rg', 'grep', 'find'}:
            return 'search', '检索相关代码'
        if executable in {'cat', 'head', 'tail', 'less'}:
            return 'read', '查看文件内容'
    except (ValueError, TypeError):
        pass
    return 'command', '执行操作'


def project_event(event, activity):
    """Return (upsert activity, full readable log); update start times in-place."""
    event_type = event.get('type')
    item = event.get('item') or {}
    item_type = item.get('type')
    item_id = item.get('id')
    if event_type not in ('item.started', 'item.completed'):
        if event_type in ('error', 'turn.failed'):
            return None, '\n[执行器报告错误]\n' + json.dumps(event, ensure_ascii=False) + '\n'
        return None, ''
    if item_type == 'agent_message':
        return None, '\n[智能体说明]\n' + str(item.get('text', '')) + '\n' if event_type == 'item.completed' else ''
    if not item_id or item_type not in ('command_execution', 'file_change', 'mcp_tool_call', 'web_search', 'todo_list'):
        return None, ''
    start = activity.setdefault(item_id, {'startedAt': now(), '_start': time.monotonic()})
    detail = ''
    files = []
    if item_type == 'command_execution':
        kind, title = command_kind(item.get('command', ''))
        if event_type == 'item.started':
            detail = '\n[执行命令]\n$ ' + str(item.get('command', '')) + '\n'
        else:
            detail = '\n[命令结束]\n$ ' + str(item.get('command', '')) + '\n' + str(item.get('aggregated_output', '')) + '\n退出码：' + str(item.get('exit_code')) + '\n'
    elif item_type == 'file_change':
        kind, title = 'files', '修改项目文件'
        files = [c['path'] for c in item.get('changes', []) if isinstance(c, dict) and isinstance(c.get('path'), str)]
        if len(files) == 1:
            title = '修改文件：' + PurePosixPath(files[0]).name
        elif files:
            title = f'修改 {len(files)} 个文件'
        detail = '\n[文件变更]\n' + json.dumps(item, ensure_ascii=False, indent=2) + '\n' if event_type == 'item.completed' else ''
    else:
        kind, title = {'mcp_tool_call': ('tool', '调用工具'), 'web_search': ('search', '检索资料'), 'todo_list': ('plan', '更新执行计划')}[item_type]
        detail = '\n[工具事件]\n' + json.dumps(item, ensure_ascii=False, indent=2) + '\n'
    status = 'running'
    if event_type == 'item.completed':
        failed = item.get('status') in ('failed', 'declined') or bool(item.get('error'))
        if item_type == 'command_execution' and item.get('exit_code') not in (None, 0):
            failed = True
        status = 'warning' if failed else 'complete'
    result = {'id': str(item_id), 'kind': kind, 'title': title, 'status': status,
              'startedAt': start['startedAt'], 'updatedAt': now(),
              'elapsedMs': round((time.monotonic() - start['_start']) * 1000), 'fileCount': len(files)}
    return result, detail
