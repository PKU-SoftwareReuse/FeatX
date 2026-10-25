import json
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase
from unittest.mock import patch
from fastapi.testclient import TestClient
from featx.main import create_app


class SummarySmoke(TestCase):
    def test_shared_summary_and_current_run_validation(self):
        with TemporaryDirectory() as root:
            app = create_app(Path(root)/'data', shared_dir=Path(root)/'shared')
            with TestClient(app) as client:
                def checkout(destination, revision=None):
                    destination.mkdir()
                    return {'commit': 'a' * 40, 'branch': 'main'}
                from featx.errors import AppError
                with patch('featx.git_import.GitImport.clone', side_effect=checkout), patch.object(
                        app.state.reposummary, 'submit', side_effect=AppError(503, 'OFFLINE', '离线')):
                    p=client.post('/api/projects',json={'name':'test','source':{'url':'https://example.com/repo.git'}}).json()
                base='/api/projects/'+p['id']
                run={'projectId':p['id'],'runId':'abc','status':'queued','totalStages':0,'stages':[]}
                with patch.object(app.state.reposummary,'submit',return_value=run):
                    self.assertEqual(202,client.post(base+'/summary').status_code)
                with patch.object(app.state.reposummary,'poll',return_value={**run,'status':'complete'}):
                    status=client.get(base+'/summary/status').json()
                    self.assertEqual('failed',status['status'])
                directory=app.state.shared.directory(p['id']);directory.mkdir()
                (directory/'summary.json').write_text(json.dumps({'projectId':p['id'],'runId':'abc',
                    'epics':[{'id':'e','description':'测试内容','features':[{'id':'f','description':'测试内容','codeElements':['x.rs::f']}]}]}))
                self.assertEqual('e',client.get(base+'/summary').json()['epics'][0]['id'])
                self.assertEqual(404,client.get('/api/v1/health').status_code)
                self.assertEqual(404,client.get(base+'/result').status_code)
