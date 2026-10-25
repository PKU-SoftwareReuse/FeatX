"""Read-only text retrieval snapshots for the graph presentation, not agent context."""
import math
import random
import re
from collections import Counter
from .errors import AppError


def tokens(text):
    result = []
    for part in re.findall(r'[\u4e00-\u9fff]+|[A-Za-z][A-Za-z0-9]*|\d+', text or ''):
        if '\u4e00' <= part[0] <= '\u9fff':
            result.extend(part[i:i + 2] for i in range(len(part) - 1)) if len(part) > 1 else result.append(part)
        else:
            part = re.sub(r'([A-Z]+)([A-Z][a-z])', r'\1 \2', part)
            part = re.sub(r'([a-z0-9])([A-Z])', r'\1 \2', part)
            result.extend(part.lower().split())
    return result


class TextSpace:
    def __init__(self, documents):
        counts = Counter(term for text in documents for term in set(tokens(text)))
        self.size = len(documents)
        self.idf = {term: 1 + math.log((self.size + 1) / (count + 1)) for term, count in counts.items()}

    def vector(self, text):
        values = {term: (1 + math.log(count)) * self.idf.get(term, 1 + math.log(self.size + 1))
                  for term, count in Counter(tokens(text)).items()}
        length = math.sqrt(sum(value * value for value in values.values()))
        return {term: value / length for term, value in values.items()} if length else {}

    @staticmethod
    def cosine(first, second):
        if len(first) > len(second):
            first, second = second, first
        return sum(value * second.get(term, 0) for term, value in first.items())


def snapshot(elements):
    nodes = {}
    for item in elements:
        path = item['filePath']
        node = nodes.setdefault(path, {'id': path, 'funcFile': path, 'type': 'Default', 'methods': [],
                                      'elements': [], 'sourceFeatureIds': [], 'original': False})
        node['methods'].append(item['name'])
        node['elements'].append({**item, 'score': round(item.get('score', 0), 8)})
        node['original'] = node['original'] or item['original']
        node['sourceFeatureIds'] = list(dict.fromkeys([*node['sourceFeatureIds'], *item['sourceFeatureIds']]))
    return {'nodes': list(nodes.values()), 'edges': [], 'fileCount': len(nodes), 'elementCount': len(elements)}


def build_preview(summary, operation, description, feature_id=None, epic_id=None):
    if not description.strip():
        raise AppError(400, 'EMPTY_DESCRIPTION', '请填写功能描述')
    features = [{**feature, 'epicId': epic['id'], 'epicDescription': epic['description']}
                for epic in summary['epics'] for feature in epic['features']]
    current = next((feature for feature in features if feature['id'] == feature_id), None) if operation == 'modify' else None
    if operation == 'modify' and current is None:
        raise AppError(404, 'FEATURE_NOT_FOUND', '功能项不存在，请刷新项目')
    if operation == 'add' and not any(epic['id'] == epic_id for epic in summary['epics']):
        raise AppError(404, 'EPIC_NOT_FOUND', '功能主题不存在，请刷新项目')
    space = TextSpace([f['description'] for f in features] + [e['description'] for e in summary['epics']])
    new_query = space.vector(description)
    old_query = space.vector(current['description']) if current else {}

    def query_score(vector, text_space=space, new=new_query, old=old_query):
        score = text_space.cosine(new, vector)
        return .7 * score + .3 * text_space.cosine(old, vector) if current else score

    scores = {f['id']: .8 * query_score(space.vector(f['description'])) + .2 * query_score(space.vector(f['epicDescription'])) for f in features}
    related = sorted((f for f in features if (not current or f['id'] != current['id']) and scores[f['id']] > 0),
                     key=lambda f: (-scores[f['id']], f['id']))[:2 if current else 3]
    if operation == 'add' and not related:
        # No lexical hit: use actual elements from the selected Epic, never invent nodes.
        candidates = [f for f in features if f['epicId'] == epic_id and f['codeElements']]
        related = random.sample(candidates, min(3, len(candidates)))
    selected = ([current] if current else []) + related
    elements = {}
    for feature in selected:
        for qualified in feature['codeElements']:
            path, delimiter, name = qualified.partition('::')
            if not delimiter or not path or not name.strip():
                raise AppError(502, 'INVALID_SUMMARY', '摘要中的代码元素缺少有效的 文件路径::元素名')
            item = elements.setdefault(qualified, {'id': qualified, 'filePath': path, 'name': name,
                                                   'sourceFeatureIds': [], 'original': False})
            item['sourceFeatureIds'] = list(dict.fromkeys([*item['sourceFeatureIds'], feature['id']]))
            item['original'] = item['original'] or bool(current and feature['id'] == current['id'])
    element_space = TextSpace(list(elements))
    element_new = element_space.vector(description)
    element_old = element_space.vector(current['description']) if current else {}
    for item in elements.values():
        direct = query_score(element_space.vector(item['id']), element_space, element_new, element_old)
        item['score'] = .6 * direct + .4 * max(scores[id_] for id_ in item['sourceFeatureIds'])
    ranked = sorted(elements.values(), key=lambda item: (-item['score'], not item['original'], item['id']))
    keep_count = min(12, math.ceil(len(ranked) / 2))
    kept = ranked[:keep_count]
    keep_ids = {item['id'] for item in kept}
    initial = [item for item in elements.values() if item['original']]
    notices = []
    if not related:
        notices.append('未找到额外的相关功能，未补入无关候选。')
    elif len(related) < (2 if current else 3):
        notices.append(f'找到 {len(related)} 个相关功能，按实际结果展示。')
    if not elements:
        notices.append('当前候选没有代码元素关联，跳过图谱扩展与裁剪。')
    return {
        'projectId': summary['projectId'], 'summaryRunId': summary['runId'], 'operation': operation,
        'features': [{'featureId': f['id'], 'epicId': f['epicId'], 'description': f['description'],
                      'score': round(scores[f['id']], 8), 'origin': 'current' if current and f['id'] == current['id'] else 'related'} for f in selected],
        'relatedFeatureCount': len(related),
        'stages': {'initial': snapshot(initial), 'expanded': snapshot(list(elements.values())), 'pruned': snapshot(kept)},
        'keptElementIds': [item['id'] for item in kept],
        'removedElementIds': [item['id'] for item in elements.values() if item['id'] not in keep_ids],
        'notices': notices,
    }
