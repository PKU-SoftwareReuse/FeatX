"""Refresh the configured provider's model catalog without invoking any model."""
from __future__ import annotations

import copy
import json
import logging
import os
import threading
import time
import tomllib
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

import httpx

INTERVAL_SECONDS = 15 * 60
log = logging.getLogger(__name__)

# /models identifies models, not their capabilities. Preserve existing metadata;
# new IDs use a conservative text/tool template compatible with the pinned CLI.
GENERIC_MODEL = {
    'description': '服务商目录提供的模型；能力与实际调用权限未逐个验证。',
    'supported_reasoning_levels': [], 'default_reasoning_level': None,
    'shell_type': 'shell_command', 'visibility': 'list', 'supported_in_api': True,
    'upgrade': None, 'model_messages': None, 'default_reasoning_summary': 'none',
    'supports_reasoning_summary_parameter': False, 'support_verbosity': False,
    'default_verbosity': None, 'apply_patch_tool_type': None,
    'truncation_policy': {'mode': 'bytes', 'limit': 10000}, 'context_window': None,
    'experimental_supported_tools': [], 'input_modalities': ['text'],
    'supports_parallel_tool_calls': False, 'supports_search_tool': False,
    'include_apps_usage_instructions': False, 'prefer_websockets': False,
    'service_tiers': [], 'additional_speed_tiers': [], 'default_service_tier': None,
    'base_instructions': '你是代码助手。遵循用户需求和运行环境提供的工具约定。需要修改代码时先检查文件，实施最小修改，并如实报告结果。只使用实际提供的工具，不虚构文件、执行结果或模型能力。',
}


class DiscoveryError(Exception):
    """Safe user-facing error without response bodies or credential values."""


def timestamp():
    return datetime.now(timezone.utc).isoformat()


def atomic_write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_name('.' + path.name + '.' + uuid4().hex + '.tmp')
    try:
        with temp.open('x', encoding='utf-8') as stream:
            json.dump(data, stream, ensure_ascii=False, indent=2, allow_nan=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
    finally:
        temp.unlink(missing_ok=True)


class ModelDiscovery:
    def __init__(self, home, invalidate, interval=INTERVAL_SECONDS):
        self.home = Path(home)
        self.invalidate = invalidate
        self.interval = interval
        self.stop_event = threading.Event()
        self.scan_lock = threading.Lock()
        self.state_lock = threading.Lock()
        self.thread = None
        self.state = {'status': 'idle', 'intervalSeconds': interval, 'lastAttemptAt': None,
                      'lastSuccessAt': None, 'modelCount': None, 'message': ''}

    def snapshot(self):
        with self.state_lock:
            return dict(self.state)

    def update(self, **values):
        with self.state_lock:
            self.state.update(values)

    def start(self):
        if self.thread and self.thread.is_alive():
            return
        self.stop_event.clear()
        self.thread = threading.Thread(target=self.loop, name='featx-model-discovery', daemon=True)
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        if self.thread:
            self.thread.join(timeout=2)

    def loop(self):
        while not self.stop_event.is_set():
            started = time.monotonic()
            self.refresh()
            if self.stop_event.wait(max(1, self.interval - (time.monotonic() - started))):
                break

    def configuration(self):
        config_file = self.home / 'config.toml'
        config_bytes = config_file.read_bytes()
        config = tomllib.loads(config_bytes.decode('utf-8'))
        provider_name = config.get('model_provider', 'openai')
        provider = config.get('model_providers', {}).get(provider_name, {})
        base = provider.get('base_url') or ('https://api.openai.com/v1' if provider_name == 'openai' else '')
        if not isinstance(base, str) or not base.strip():
            raise DiscoveryError('当前服务商未配置 base_url，保留现有模型目录')
        url = httpx.URL(base.rstrip('/') + '/models')
        if url.scheme not in ('http', 'https') or not url.host or url.username or url.password or url.query:
            raise DiscoveryError('服务商 base_url 无效，保留现有模型目录')
        catalog_name = config.get('model_catalog_json')
        if not isinstance(catalog_name, str) or not catalog_name.strip():
            raise DiscoveryError('请先在 config.toml 中配置 model_catalog_json 保存模型目录')
        catalog = Path(catalog_name).expanduser()
        if not catalog.is_absolute():
            catalog = self.home / catalog
        headers = dict(provider.get('http_headers') or {})
        for key, env_name in (provider.get('env_http_headers') or {}).items():
            if os.environ.get(env_name):
                headers[key] = os.environ[env_name]
        token = provider.get('experimental_bearer_token') or os.environ.get(provider.get('env_key', ''))
        if not token and (provider_name == 'openai' or provider.get('requires_openai_auth')):
            token = os.environ.get('CODEX_API_KEY') or os.environ.get('OPENAI_API_KEY')
            auth_file = self.home / 'auth.json'
            if not token and auth_file.is_file():
                token = json.loads(auth_file.read_text()).get('OPENAI_API_KEY')
        if token and not any(key.lower() == 'authorization' for key in headers):
            headers['Authorization'] = 'Bearer ' + token
        return config_file, config_bytes, config, str(url), headers, catalog, provider_name

    def refresh(self):
        # Serialize scans and never clear a working catalog on a transient error.
        if not self.scan_lock.acquire(blocking=False):
            return
        self.update(status='syncing', lastAttemptAt=timestamp())
        try:
            config_file, before, config, url, headers, catalog, provider = self.configuration()
            with httpx.Client(timeout=15, trust_env=False, follow_redirects=False) as client:
                response = client.get(url, headers=headers)
            if not response.is_success:
                raise DiscoveryError(f'模型目录扫描失败（HTTP {response.status_code}），保留上次结果')
            body = response.json()
            if not isinstance(body, dict) or not isinstance(body.get('data'), list) or body.get('has_more'):
                raise DiscoveryError('模型列表格式不符合约定或结果未完整返回，保留上次结果')
            ids = []
            for item in body['data']:
                if not isinstance(item, dict) or not isinstance(item.get('id'), str) or not item['id'].strip():
                    raise DiscoveryError('模型列表包含无效 ID，保留上次结果')
                if item['id'] not in ids:
                    ids.append(item['id'])
            if not ids:
                raise DiscoveryError('服务商返回空模型列表，保留上次结果')
            previous = json.loads(catalog.read_text()) if catalog.exists() else {'models': []}
            if not isinstance(previous, dict) or not isinstance(previous.get('models'), list):
                raise DiscoveryError('本地模型目录格式错误，未覆盖文件')
            known = {item['slug']: item for item in previous['models'] if isinstance(item, dict) and isinstance(item.get('slug'), str)}
            models = []
            for index, model_id in enumerate(ids):
                entry = copy.deepcopy(known.get(model_id, GENERIC_MODEL))
                entry.update(slug=model_id, priority=index)
                entry.setdefault('display_name', model_id)
                models.append(entry)
            if self.stop_event.is_set():
                return
            if config_file.read_bytes() != before:
                raise DiscoveryError('扫描期间服务商配置发生变化，保留现有目录，等待下次扫描')
            updated = {**previous, 'models': models}
            if updated != previous:
                atomic_write(catalog, updated)
            self.invalidate()
            default = config.get('model')
            message = f'配置默认模型 {default} 未在服务商模型列表中列出，请确认或选择列表中的模型。' if default and default not in ids else ''
            self.update(status='ready', lastSuccessAt=timestamp(), modelCount=len(ids), message=message,
                        provider=provider, defaultListed=not default or default in ids)
            log.info('Model discovery completed: %d models', len(ids))
        except httpx.RequestError:
            self.update(status='error', message='服务商模型列表不可达或超时，保留上次结果')
            log.warning('Model discovery failed: provider unavailable; previous catalog retained')
        except DiscoveryError as exc:
            self.update(status='error', message=str(exc))
            log.warning('Model discovery failed: %s', exc)
        except (OSError, ValueError, TypeError, KeyError, AttributeError):
            # Avoid logging response bodies or config values that may contain credentials.
            self.update(status='error', message='模型扫描失败：请检查服务商接口、认证和目录配置；保留上次结果')
            log.warning('Model discovery failed: invalid response or configuration; previous catalog retained')
        finally:
            self.scan_lock.release()
