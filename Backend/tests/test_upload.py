import io
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import patch
from zipfile import ZipFile
from fastapi.testclient import TestClient
from featx.main import create_app
from featx.models import AnalysisResult


class UploadSmoke(TestCase):
    def test_upload_paths_bytes_handoff_and_delete(self):
        with TemporaryDirectory() as directory:
            app = create_app(directory)
            with TestClient(app) as client:
                response = client.post('/api/v1/projects/upload', files=[
                    ('folderName', (None, 'Mixed project')),
                    ('paths', (None, 'src/main.rs')), ('files', ('main.rs', b'fn main() {}')),
                    ('paths', (None, 'settings.json')), ('files', ('settings.json', b'{"ok":true}')),
                ])
                self.assertEqual(201, response.status_code, response.text)
                project = response.json(); base = '/api/v1/projects/' + project['id']
                self.assertEqual('upload', project['source']['kind'])
                self.assertNotIn('uploadToken', project)
                self.assertEqual(403, client.get(base + '/archive').status_code)
                record = app.state.store.read(project['id'])
                archive = client.get(base + '/archive', params={'token': record['uploadToken']})
                self.assertEqual(200, archive.status_code)
                with ZipFile(io.BytesIO(archive.content)) as bundle:
                    self.assertEqual(['src/main.rs', 'settings.json'], bundle.namelist())
                    self.assertEqual(b'fn main() {}', bundle.read('src/main.rs'))
                payloads = []
                def remote_call(method, path, payload):
                    payloads.append(payload)
                    return {'taskId': 'uploaded', 'status': 'queued'}
                with patch.object(app.state.reposummary, 'call', side_effect=remote_call):
                    self.assertEqual(202, client.post(base + '/analyses').status_code)
                self.assertIn('/archive?token=', payloads[0]['source']['downloadUrl'])
                parsed = AnalysisResult.model_validate({'version': '1', 'source': payloads[0]['source'],
                                                       'features': [], 'graph': {'nodes': [], 'edges': []}})
                self.assertEqual(project['source'], parsed.source.model_dump())
                self.assertEqual(204, client.delete(base).status_code)
                self.assertFalse((app.state.store.root.parent / 'uploads' / (project['id'] + '.zip')).exists())

    def test_rejects_unsafe_paths_and_missing_files(self):
        with TemporaryDirectory() as directory:
            app = create_app(directory)
            with TestClient(app) as client:
                for path in ['../escape.txt', '/absolute.txt', 'C:/outside', 'a/../bad.txt']:
                    response = client.post('/api/v1/projects/upload', files=[
                        ('folderName', (None, 'Unsafe')), ('paths', (None, path)), ('files', ('x', b'x'))])
                    self.assertEqual(400, response.status_code)
                self.assertEqual([], client.get('/api/v1/projects').json())
