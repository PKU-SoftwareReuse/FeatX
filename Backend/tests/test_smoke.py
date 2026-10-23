"""Small framework smoke checks. Test fixtures are never shipped as demo results."""
import importlib.util
from pathlib import Path
from tempfile import TemporaryDirectory
from threading import Thread
from http.server import ThreadingHTTPServer
import unittest
from fastapi.testclient import TestClient
from featx.main import create_app

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('summary_stub', ROOT / 'PyBackend/src/featx_pybackend/api/server.py')
stub = importlib.util.module_from_spec(spec)
spec.loader.exec_module(stub)


class FrameworkSmoke(unittest.TestCase):
    def setUp(self):
        self.temp = TemporaryDirectory()
        self.server = ThreadingHTTPServer(('127.0.0.1', 0), stub.RepoSummaryHandler)
        self.thread = Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.url = f'http://127.0.0.1:{self.server.server_port}'
        self.app = create_app(self.temp.name, self.url)
        self.client = TestClient(self.app)

    def tearDown(self):
        self.client.close()
        self.server.shutdown(); self.server.server_close(); self.thread.join()
        self.temp.cleanup()

    def project(self):
        response = self.client.post('/api/v1/projects', json={'name': 'Any language', 'source': {'url': 'https://example.com/repo.git'}})
        self.assertEqual(201, response.status_code)
        return response.json()

    def test_stub_and_persistence(self):
        project = self.project(); base = f'/api/v1/projects/{project["id"]}'
        self.assertEqual(200, self.client.get('/api/v1/health').status_code)
        self.assertFalse(self.client.get('/api/v1/integrations/reposummary').json()['businessReady'])
        response = self.client.post(base + '/analyses')
        self.assertEqual(501, response.status_code)
        self.assertEqual('REPOSUMMARY_NOT_IMPLEMENTED', response.json()['error']['code'])
        self.assertEqual('not_started', self.client.get(base + '/analysis').json()['status'])
        self.assertEqual(409, self.client.get(base + '/graph').status_code)
        with TestClient(create_app(self.temp.name, self.url)) as restarted:
            self.assertEqual(project['id'], restarted.get('/api/v1/projects').json()[0]['id'])
        for method, path in [('post', '/edits'), ('post', '/agent/runs'), ('get', '/diff'), ('post', '/git/operations')]:
            self.assertEqual(501, getattr(self.client, method)(base + path).status_code)
        self.assertEqual(204, self.client.delete(base).status_code)
        self.assertEqual(404, self.client.get(base).status_code)

    def test_language_neutral_result_and_failed_references(self):
        project = self.project(); base = f'/api/v1/projects/{project["id"]}'
        remote = self.app.state.reposummary
        task = {'taskId': 't1', 'status': 'queued', 'progress': None, 'error': None, 'message': ''}
        # Use the actual client model validation with an isolated in-memory HTTP-call fixture.
        graph = {'version': '1', 'source': project['source'], 'language': 'anything',
                 'features': [{'id': 'f', 'name': 'Feature', 'nodeIds': ['n']}],
                 'graph': {'nodes': [{'id': 'n', 'name': 'Node', 'type': 'arbitrary-kind'}], 'edges': []}}
        def call(method, path, payload=None):
            if path.endswith('/result'):
                return graph
            return task if method == 'POST' else {**task, 'status': 'complete'}
        remote.call = call
        self.assertEqual(202, self.client.post(base + '/analyses').status_code)
        self.assertEqual('complete', self.client.get(base + '/analysis').json()['status'])
        self.assertEqual('arbitrary-kind', self.client.get(base + '/graph').json()['nodes'][0]['type'])
        graph['graph']['edges'] = [{'source': 'n', 'target': 'missing', 'type': 'x'}]
        self.client.post(base + '/analyses')
        self.assertEqual(502, self.client.get(base + '/analysis').status_code)
        self.assertEqual('failed', self.client.get(base + '/analysis').json()['status'])
        self.assertEqual(409, self.client.get(base + '/result').status_code)

    def test_empty_graph_and_unreachable(self):
        project = self.project(); base = f'/api/v1/projects/{project["id"]}'
        remote = self.app.state.reposummary
        def call(method, path, payload=None):
            if path.endswith('/result'):
                return {'version': '1', 'source': project['source'], 'features': [], 'graph': {'nodes': [], 'edges': []}}
            return {'taskId': 'empty', 'status': 'complete'}
        remote.call = call
        self.client.post(base + '/analyses')
        self.assertEqual('complete', self.client.get(base + '/analysis').json()['status'])
        self.assertEqual([], self.client.get(base + '/graph').json()['nodes'])
        with TestClient(create_app(self.temp.name, 'http://127.0.0.1:1')) as offline:
            self.assertEqual(503, offline.get('/api/v1/integrations/reposummary').status_code)
        self.assertEqual(404, self.client.get('/api/v1/no-such-api').status_code)
        self.assertEqual(422, self.client.post('/api/v1/projects', json={'name': 'x', 'source': {'url': 'file:///etc/passwd'}}).status_code)


if __name__ == '__main__':
    unittest.main()
