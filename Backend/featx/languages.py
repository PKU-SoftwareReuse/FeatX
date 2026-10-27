"""Filename/byte-size language estimates only; never parse source or infer graph data."""
import os
import time
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from threading import RLock
from fastapi import APIRouter, Request
from .errors import AppError

router = APIRouter(prefix='/projects', tags=['projects'])
EXTENSIONS = {}
for language, extensions in {
    'Python': '.py .pyi .pyw', 'JavaScript': '.js .jsx .mjs .cjs', 'TypeScript': '.ts .tsx .mts .cts',
    'Java': '.java', 'Vue': '.vue', 'Svelte': '.svelte', 'Astro': '.astro',
    'HTML': '.html .htm', 'CSS': '.css', 'SCSS': '.scss', 'Sass': '.sass', 'Less': '.less',
    'C': '.c .h', 'C++': '.cpp .cc .cxx .hpp .hh .hxx', 'C#': '.cs', 'Go': '.go',
    'Rust': '.rs', 'Ruby': '.rb .rake', 'PHP': '.php .phtml', 'Kotlin': '.kt .kts',
    'Swift': '.swift', 'Dart': '.dart', 'Scala': '.scala .sc', 'Shell': '.sh .bash .zsh',
    'PowerShell': '.ps1 .psm1', 'SQL': '.sql', 'R': '.r', 'Julia': '.jl', 'Lua': '.lua',
    'Perl': '.pl .pm', 'Elixir': '.ex .exs', 'Erlang': '.erl .hrl', 'Haskell': '.hs .lhs',
    'OCaml': '.ml .mli', 'F#': '.fs .fsx', 'Clojure': '.clj .cljs .cljc',
    'Objective-C': '.m .mm', 'Solidity': '.sol', 'Zig': '.zig', 'Assembly': '.asm .s',
    'Fortran': '.f .f90 .f95', 'Visual Basic': '.vb', 'Groovy': '.groovy .gradle',
}.items():
    EXTENSIONS.update({extension: language for extension in extensions.split()})
EXCLUDED = {'.git', 'node_modules', 'vendor', 'third_party', 'third-party', '.venv', 'venv', 'env',
            'dist', 'build', 'target', 'coverage', '__pycache__', '.next', '.nuxt', '.cache',
            '.idea', '.vscode', 'site-packages', '.tox', '.mypy_cache', '.pytest_cache'}
_lock = RLock()


def measure_languages(source):
    if not source.is_dir():
        raise AppError(404, 'SOURCE_MISSING', '项目源文件不存在')
    sizes, counts = defaultdict(int), defaultdict(int)
    incomplete = False
    def onerror(_error):
        nonlocal incomplete
        incomplete = True
    for directory, dirs, files in os.walk(source, followlinks=False, onerror=onerror):
        dirs[:] = [name for name in dirs if name.lower() not in EXCLUDED and not Path(directory, name).is_symlink()]
        for name in files:
            lower = name.lower()
            if lower.endswith(('.min.js', '.min.css', '.map')):
                continue
            language = EXTENSIONS.get(Path(lower).suffix)
            if lower in ('dockerfile', 'makefile', 'gnumakefile'):
                language = 'Dockerfile' if lower == 'dockerfile' else 'Makefile'
            if not language:
                continue
            path = Path(directory, name)
            try:
                if path.is_symlink() or not path.is_file():
                    continue
                size = path.stat().st_size
            except OSError:
                incomplete = True
                continue
            if size:
                sizes[language] += size
                counts[language] += 1
    total = sum(sizes.values())
    return {'items': [{'name': name, 'bytes': size, 'files': counts[name], 'percent': round(size / total * 100, 2)}
                      for name, size in sorted(sizes.items(), key=lambda item: (-item[1], item[0]))],
            'totalBytes': total, 'incomplete': incomplete, 'basis': 'file-extension-bytes',
            'measuredAt': datetime.now(timezone.utc).isoformat()}


@router.get('/{project_id}/languages')
def project_languages(project_id: str, request: Request):
    store = request.app.state.store
    with store.lock:
        store.read(project_id)
    # Per-app cache; summaries and project records are not modified by display statistics.
    with _lock:
        cache = getattr(request.app.state, 'language_cache', None)
        if cache is None:
            cache = request.app.state.language_cache = {}
        entry = cache.get(project_id)
        if entry and time.monotonic() - entry[0] < 600:
            return entry[1]
    result = measure_languages(request.app.state.shared.directory(project_id) / 'source')
    with _lock:
        cache[project_id] = (time.monotonic(), result)
    return result
