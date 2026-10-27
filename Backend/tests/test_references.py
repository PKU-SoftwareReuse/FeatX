import tempfile
from pathlib import Path
from unittest import TestCase
from unittest.mock import patch
from featx.references import ReferenceScanner
from featx.errors import AppError


class ReferencesTests(TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.scanner = ReferenceScanner()

    def write(self, name, text):
        file = self.root / name
        file.parent.mkdir(parents=True, exist_ok=True)
        file.write_bytes(text if isinstance(text, bytes) else text.encode())

    def scan(self, *files):
        return self.scanner.scan('p', self.root, files)

    def test_explicit_references_and_comments_have_real_lines(self):
        self.write('src/a.ts', '// import x from "./wrong"\n/* require("./wrong") */\nimport x from "./b";\nrequire("./b");\nconst text = "require(\'./wrong\')";\n')
        self.write('src/b.ts', 'export const x=1')
        self.write('src/wrong.ts', '')
        result = self.scan('src/a.ts', 'src/b.ts', 'src/wrong.ts')
        self.assertEqual(1, len(result['edges']))
        edge = result['edges'][0]
        self.assertEqual(('src/a.ts', 'src/b.ts'), (edge['source'], edge['target']))
        self.assertEqual([3,4], [e['line'] for e in edge['evidence']])
        self.assertEqual(result['edges'], self.scan('src/a.ts', 'src/b.ts', 'src/wrong.ts')['edges'])

    def test_module_relative_alias_and_unknown_extension(self):
        self.write('app/a.py', 'from . import helper\n')
        self.write('app/helper.py', '')
        self.write('lib/a.java', 'import com.demo.Service;\n')
        self.write('src/main/java/com/demo/Service.java', '')
        self.write('ui/page.vue', '<script>\nimport x from "@/utils/x";\n</script>')
        self.write('ui/src/utils/x.ts', '')
        self.write('scripts/main.unknown', 'include "helper.unknown"\n')
        self.write('scripts/helper.unknown', '')
        result=self.scan('app/a.py','app/helper.py','lib/a.java','src/main/java/com/demo/Service.java',
                         'ui/page.vue','ui/src/utils/x.ts','scripts/main.unknown','scripts/helper.unknown')
        self.assertEqual(4,len(result['edges']))

    def test_ambiguous_missing_binary_external_and_traversal(self):
        self.write('a.js','import x from "utils";\nimport y from "react";\nimport z from "./gone";\n')
        self.write('one/utils.ts','');self.write('two/utils.ts','');self.write('react.ts','')
        self.write('binary.py',b'\0import x')
        self.assertEqual([],self.scan('a.js','one/utils.ts','two/utils.ts','binary.py','gone.js','react.ts')['edges'])
        with self.assertRaises(AppError):self.scan('../outside.py')
        self.root.joinpath('escape').symlink_to('/etc/passwd')
        with self.assertRaises(AppError):self.scan('escape')

    def test_changed_and_removed_files_do_not_reuse_old_results(self):
        self.write('a.js','import "./b"');self.write('b.js','');self.write('c.js','')
        first=self.scan('a.js','b.js','c.js')
        self.assertEqual('b.js',first['edges'][0]['target'])
        self.write('a.js','import "./c"')
        self.assertEqual('c.js',self.scan('a.js','b.js','c.js')['edges'][0]['target'])
        (self.root/'c.js').unlink()
        self.assertEqual([],self.scan('a.js','b.js','c.js')['edges'])

    def test_limits_return_partial_without_errors(self):
        self.write('a.js','import "./b"');self.write('b.js','')
        with patch('featx.references.SECONDS',0):
            result=self.scan('a.js','b.js')
            self.assertTrue(result['partial']);self.assertEqual([],result['edges'])
        with patch('featx.references.SCAN_LIMIT',1):self.assertTrue(self.scan('a.js','b.js')['partial'])
        with patch('featx.references.TOTAL_LIMIT',2):self.assertTrue(self.scan('a.js','b.js')['partial'])
