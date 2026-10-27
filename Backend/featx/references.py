"""Bounded text-only reference hints. No AST, source execution, or repository traversal."""
import bisect
import codecs
import hashlib
import posixpath
import re
import stat
import time
from collections import OrderedDict, defaultdict
from pathlib import Path, PurePosixPath
from threading import RLock

from fastapi import APIRouter, Request
from pydantic import BaseModel, ConfigDict, Field
from .errors import AppError

router = APIRouter(prefix='/projects/{project_id}/graph', tags=['graph references'])
FILE_LIMIT = 128 * 1024
TOTAL_LIMIT = 8 * 1024 * 1024
SCAN_LIMIT = 150
SECONDS = .3
REFERENCE_LIMIT = 128
CACHE_LIMIT = 512
EXTENSIONS = ('.ts', '.tsx', '.mts', '.cts', '.js', '.jsx', '.mjs', '.cjs', '.vue', '.svelte', '.py', '.pyi',
              '.java', '.kt', '.cs', '.c', '.h', '.cc', '.cpp', '.hpp', '.rs', '.go', '.rb',
              '.php', '.swift', '.dart', '.scala', '.sh', '.ex', '.exs', '.lua', '.pl', '.jl')
# Quoted strings are kept, comments/docstrings are blanked without changing line numbers.
LEX = re.compile(r'''(?P<comment>"""[\s\S]*?(?:"""|\Z)|\x27\x27\x27[\s\S]*?(?:\x27\x27\x27|\Z)|/\*[\s\S]*?(?:\*/|\Z)|//[^\n]*|(?m:^[ \t]*(?:--|;)[^\n]*)|\#[^\n]*)|(?P<string>"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)''')
QUOTED = re.compile(r'''(?P<kind>\b(?:import|export))\s+(?:type\s+)?(?:\{[^}]{0,1800}\}|[\w$]+(?:\s*,\s*\{[^}]{0,1800}\})?|\*\s*(?:as\s+\w+)?)\s*\bfrom\s*["'](?P<spec>[^"'\n]{1,512})["']|(?P<direct>\b(?:import|require|require_once|include|include_once|source|load|use))\s*(?:\(\s*)?["'](?P<path>[^"'\n]{1,512})["']''')
INCLUDE = re.compile(r'^[ \t]*#\s*include\s*[<"]([^>"\n]{1,512})[>"]', re.M)
FROM = re.compile(r'^[ \t]*from\s+([.\w]+)\s+import\s+([^\n;]{1,512})', re.M)
MODULE = re.compile(r'^[ \t]*(?:(?:pub|global)\s+)?(import|using|use|mod|source|load)\s+(?:static\s+)?([\w.\\:/-]+)([^\n;]{0,256})', re.M)


def extract(text):
    masked = list(text)
    strings = []
    for match in LEX.finditer(text):
        value = match.group()
        if match.lastgroup == 'string':
            strings.append((match.start(), match.end()))
        elif not re.match(r'#\s*include\b', value):
            masked[match.start():match.end()] = ['\n' if c == '\n' else ' ' for c in value]
    clean = ''.join(masked)
    starts = [item[0] for item in strings]
    lines = [0] + [match.end() for match in re.finditer('\n', text)]
    refs, seen = [], set()

    def emit(offset, spec, kind, python_relative=False, literal=False, statement=None):
        index = bisect.bisect_right(starts, offset) - 1
        if index >= 0 and strings[index][0] < offset < strings[index][1]:
            return
        spec = spec.strip()
        if not spec or any(c in spec for c in '*${}()') or '://' in spec:
            return
        line = bisect.bisect_right(lines, offset)
        key = (line, spec, kind)
        if key in seen or len(refs) >= REFERENCE_LIMIT:
            return
        seen.add(key)
        end = text.find('\n', offset)
        refs.append({'specifier': spec, 'type': kind, 'pythonRelative': python_relative, 'literal': literal,
                     'line': line, 'statement': (statement or text[lines[line-1]:end if end >= 0 else len(text)].strip())[:240]})

    for match in QUOTED.finditer(clean):
        if clean[match.end():].lstrip(' \t').startswith(('+', '.', '$', '[', '?')):
            continue  # Literal prefix of a computed path is not an explicit reference.
        emit(match.start(), match['spec'] or match['path'], match['kind'] or match['direct'], literal=True, statement=re.sub(r'\s+', ' ', match.group()))
    for match in INCLUDE.finditer(clean):
        emit(match.start() + len(match.group()) - len(match.group().lstrip()), match[1], 'include', literal=True)
    for match in FROM.finditer(clean):
        module = match[1]
        emit(match.start(), module, 'from', True)
        for name in match[2].strip(' ()').split(',')[:16]:
            name = name.strip().split(' as ')[0]
            if re.fullmatch(r'\w+', name):
                emit(match.start(), module + ('' if module.endswith('.') else '.') + name, 'from', True)
    for match in MODULE.finditer(clean):
        # Do not turn alias declarations or wildcard imports into a guessed dependency.
        if '=' in match[3] or '*' in match[3] or re.search(r'\bfrom\b', match[3]) or match[2].endswith(('.', '::')):
            continue
        emit(match.start(), match[2], match[1])
    refs.sort(key=lambda item: (item['line'], item['specifier'], item['type']))
    return refs, len(refs) >= REFERENCE_LIMIT


class PathIndex:
    def __init__(self, paths):
        self.paths = set(paths)
        self.aliases = defaultdict(set)
        self.suffixes = defaultdict(set)
        for path in paths:
            variants = {path}
            suffix = PurePosixPath(path).suffix
            if suffix in EXTENSIONS:
                variants.add(path[:-len(suffix)])
            # ESM TypeScript often names the emitted JS file in source imports.
            emitted = {'.ts': ('.js',), '.tsx': ('.js', '.jsx'), '.mts': ('.mjs',), '.cts': ('.cjs',)}
            for extension in emitted.get(suffix, ()):
                variants.add(path[:-len(suffix)] + extension)
            stem = PurePosixPath(path).stem
            if stem in ('index', '__init__', 'mod', 'init'):
                variants.add(posixpath.dirname(path))
            for alias in variants:
                self.aliases[alias].add(path)
                parts = alias.split('/')
                for offset in range(len(parts)):
                    self.suffixes['/'.join(parts[offset:])].add(path)

    @staticmethod
    def one(matches, source):
        # Ambiguity must remain ambiguity even if one of the matches is the source.
        return next(iter(matches)) if len(matches) == 1 and source not in matches else None

    def resolve(self, source, ref):
        spec = ref['specifier'].replace('\\', '/')
        parent = posixpath.dirname(source)
        if ref['pythonRelative'] and spec.startswith('.'):
            count = len(spec) - len(spec.lstrip('.'))
            relative = '../' * (count - 1) + spec[count:].replace('.', '/')
            key = posixpath.normpath(posixpath.join(parent, relative))
            return self.one(self.aliases.get(key, set()), source), 'path'
        if spec.startswith(('./', '../')):
            key = posixpath.normpath(posixpath.join(parent, spec))
            exact = {key} if key in self.paths else self.aliases.get(key, set())
            return self.one(exact, source), 'path'
        if spec.startswith('/'):
            return self.one(self.aliases.get(spec.lstrip('/'), set()), source), 'path'
        # Explicit filenames work even for unknown language extensions.
        key = posixpath.normpath(posixpath.join(parent, spec))
        if key in self.paths:
            return key if key != source else None, 'path'
        if spec.startswith('@/'):
            tail = spec[2:]
            found = self.suffixes.get('src/' + tail, set()) | self.suffixes.get('lib/' + tail, set())
            return self.one(found, source), 'alias'
        if spec.startswith(('node:', 'http:', 'https:')):
            return None, 'module'
        # A bare quoted package name is usually an external dependency. Do not
        # guess it is a same-named local file without a path or known root alias.
        if ref.get('literal') and not PurePosixPath(spec).suffix and not spec.startswith(('src/', 'lib/', 'app/', 'include/')):
            return None, 'module'
        module = spec.replace('::', '/')
        if '/' not in module and PurePosixPath(module).suffix not in EXTENSIONS:
            module = module.replace('.', '/')
        if module.startswith('crate/'):
            module = 'src/' + module[6:]
        if module.startswith(('self/', 'super/')):
            module = module.replace('self/', './', 1).replace('super/', '../', 1)
            return self.one(self.aliases.get(posixpath.normpath(posixpath.join(parent, module)), set()), source), 'module'
        local = posixpath.normpath(posixpath.join(parent, module))
        if local in self.aliases:
            return self.one(self.aliases[local], source), 'module'
        return self.one(self.suffixes.get(module, set()), source), 'module'


class ReferenceScanner:
    def __init__(self):
        self.cache = OrderedDict()
        self.lock = RLock()

    def scan(self, project_id, root, files):
        root = root.resolve()
        candidates = []
        # Validate every submitted path before applying scan limits; bad paths are not swallowed.
        for name in dict.fromkeys(files):
            name = name.replace('\\', '/')
            parts = PurePosixPath(name).parts
            if not name or name.startswith('/') or '..' in parts or '.git' in parts or '\0' in name or ':' in name:
                raise AppError(400, 'INVALID_PATH', '引用分析只接受项目内文件路径')
            target = (root / name).resolve()
            if not target.is_relative_to(root) or '.git' in target.relative_to(root).parts:
                raise AppError(400, 'INVALID_PATH', '不能分析项目外部文件')
            candidates.append((posixpath.normpath(name), target))
        started = time.monotonic()
        partial = len(candidates) > SCAN_LIMIT
        existing, signatures = {}, {}
        for name, path in candidates[:SCAN_LIMIT]:
            try:
                info = path.stat()
                if not stat.S_ISREG(info.st_mode):
                    partial = True
                    continue
                existing[name] = path
                signatures[name] = (info.st_mtime_ns, info.st_size, info.st_ctime_ns, info.st_ino)
            except OSError:
                partial = True
        index = PathIndex(existing)
        edges, used, scanned = {}, 0, 0
        ranks = {'path': 3, 'module': 2, 'alias': 1}
        for name, path in existing.items():
            if time.monotonic() - started >= SECONDS:
                partial = True
                break
            amount = min(signatures[name][1], FILE_LIMIT)
            if used + amount > TOTAL_LIMIT:
                partial = True
                break
            used += amount
            partial |= signatures[name][1] > FILE_LIMIT
            key = (project_id, str(root), name)
            with self.lock:
                cached = self.cache.get(key)
                if cached:
                    self.cache.move_to_end(key)
            if cached and cached[0] == signatures[name]:
                refs, truncated = cached[1], cached[2]
            else:
                try:
                    with path.open('rb') as stream:
                        raw = stream.read(FILE_LIMIT)
                    if b'\0' in raw:
                        partial = True
                        continue
                    text = codecs.getincrementaldecoder('utf-8-sig')().decode(raw, final=signatures[name][1] <= FILE_LIMIT)
                    if signatures[name][1] > FILE_LIMIT:
                        text = text.rsplit('\n', 1)[0] if '\n' in text else ''
                    refs, truncated = extract(text)
                    after = path.stat()
                    if (after.st_mtime_ns, after.st_size, after.st_ctime_ns, after.st_ino) != signatures[name]:
                        partial = True
                        continue  # An agent is writing this file; never cache a mixed version.
                    with self.lock:
                        self.cache[key] = (signatures[name], refs, truncated)
                        self.cache.move_to_end(key)
                        while len(self.cache) > CACHE_LIMIT:
                            self.cache.popitem(last=False)
                except (OSError, UnicodeError, ValueError, re.error):
                    partial = True
                    continue
            scanned += 1
            partial |= truncated
            for ref in refs:
                if time.monotonic() - started >= SECONDS:
                    partial = True
                    break
                target, level = index.resolve(name, ref)
                if not target:
                    continue
                pair = (name, target)
                edge = edges.setdefault(pair, {'id': 'ref-' + hashlib.sha256((name + '\0' + target).encode()).hexdigest()[:24],
                    'source': name, 'target': target, 'type': 'reference', 'match': level, 'rank': ranks[level], 'evidence': []})
                if ranks[level] > edge['rank']:
                    edge.update(match=level, rank=ranks[level])
                evidence = {'source': name, 'line': ref['line'], 'statement': ref['statement'], 'type': ref['type']}
                if len(edge['evidence']) < 3 and evidence not in edge['evidence']:
                    edge['evidence'].append(evidence)
        return {'projectId': project_id, 'edges': sorted(edges.values(), key=lambda edge: edge['id']),
                'partial': partial, 'scannedFiles': scanned}


class ReferencesRequest(BaseModel):
    model_config = ConfigDict(extra='forbid')
    files: list[str] = Field(max_length=2000)


@router.post('/references')
def references(project_id: str, body: ReferencesRequest, request: Request):
    store = request.app.state.store
    with store.lock:
        store.read(project_id)
    if any(len(path) > 2048 for path in body.files):
        raise AppError(400, 'INVALID_PATH', '文件路径过长')
    return request.app.state.reference_scanner.scan(project_id,
        request.app.state.shared.directory(project_id) / 'source', body.files)
