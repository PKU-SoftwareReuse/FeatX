import io
import subprocess
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import patch
from zipfile import ZipFile
from fastapi.testclient import TestClient
from featx.main import create_app
from featx.errors import AppError


class UploadSmoke(TestCase):
    def test_upload_paths_bytes_handoff_and_delete(self):
        with TemporaryDirectory() as directory:
            app = create_app(directory, shared_dir=__import__('pathlib').Path(directory)/'shared')
            with TestClient(app) as client, patch.object(app.state.reposummary, 'submit', side_effect=AppError(503, 'OFFLINE', 'summary offline')):
                response = client.post('/api/projects/upload', files=[
                    ('folderName', (None, 'Mixed project')),
                    ('paths', (None, 'src/main.rs')), ('files', ('main.rs', b'fn main() {}')),
                    ('paths', (None, 'settings.json')), ('files', ('settings.json', b'{"ok":true}')),
                ])
                self.assertEqual(201, response.status_code, response.text)
                project = response.json(); base = '/api/projects/' + project['id']
                self.assertEqual('upload', project['source']['kind'])
                self.assertNotIn('uploadToken', project)
                self.assertEqual(403, client.get(base + '/archive').status_code)
                record = app.state.store.read(project['id'])
                archive = client.get(base + '/archive', params={'token': record['uploadToken']})
                self.assertEqual(200, archive.status_code)
                with ZipFile(io.BytesIO(archive.content)) as bundle:
                    self.assertEqual(['src/main.rs', 'settings.json'], bundle.namelist())
                    self.assertEqual(b'fn main() {}', bundle.read('src/main.rs'))
                source = app.state.shared.directory(project['id']) / 'source' / 'src/main.rs'
                self.assertEqual(b'fn main() {}', source.read_bytes())
                repository = source.parent.parent
                self.assertEqual(project['git']['commit'], subprocess.check_output(['git', '-C', str(repository), 'rev-parse', 'HEAD'], text=True).strip())
                self.assertEqual('main', project['git']['branch'])
                self.assertIsNone(project['git']['remoteUrl'])
                self.assertEqual(204, client.delete(base).status_code)
                self.assertFalse((app.state.store.root.parent / 'uploads' / (project['id'] + '.zip')).exists())

    def test_rejects_unsafe_paths_and_missing_files(self):
        with TemporaryDirectory() as directory:
            app = create_app(directory, shared_dir=__import__('pathlib').Path(directory)/'shared')
            with TestClient(app) as client, patch.object(app.state.reposummary, 'submit', side_effect=AppError(503, 'OFFLINE', 'summary offline')):
                for path in ['../escape.txt', '/absolute.txt', 'C:/outside', 'a/../bad.txt']:
                    response = client.post('/api/projects/upload', files=[
                        ('folderName', (None, 'Unsafe')), ('paths', (None, path)), ('files', ('x', b'x'))])
                    self.assertEqual(400, response.status_code)
                self.assertEqual([], client.get('/api/projects').json())
