import csv
import tempfile
import unittest
from pathlib import Path

from featx_pybackend.analysis.java.method_analyzer import JavaMethodAnalyzer


class JavaMethodAnalyzerTest(unittest.TestCase):
    def test_java_signatures_preserve_qualified_types_arrays_and_varargs(self):
        with tempfile.TemporaryDirectory() as temporary_dir:
            root = Path(temporary_dir)
            source_root = root / "src"
            source_root.mkdir()
            (source_root / "Example.java").write_text(
                """
                package demo;

                class Example {
                    static void array(String[] args) {}
                    static void trailingArray(String args[]) {}
                    static void matrix(int[][] values) {}
                    static void varargs(String... values) {}
                    static void qualified(top.naccl.model.dto.Blog blog) {}
                    static void generic(java.util.List<String[]> values) {}

                    void factory() {
                        Runnable callback = new Runnable() {
                            public void run() {}
                        };
                    }

                    static class Nested {
                        void nested() {}
                    }
                }
                """,
                encoding="utf-8",
            )

            output_root = root / "output"
            JavaMethodAnalyzer().analyze_project(str(source_root), str(output_root))

            with (output_root / "method.csv").open(encoding="utf-8", newline="") as handle:
                signatures = {row["method_signature"] for row in csv.DictReader(handle)}

        self.assertIn("demo.Example.array( String[] args )", signatures)
        self.assertIn("demo.Example.trailingArray( String[] args )", signatures)
        self.assertIn("demo.Example.matrix( int[][] values )", signatures)
        self.assertIn("demo.Example.varargs( String... values )", signatures)
        self.assertIn("demo.Example.qualified( top.naccl.model.dto.Blog blog )", signatures)
        self.assertIn("demo.Example.generic( java.util.List<String[]> values )", signatures)
        self.assertIn("demo.Example.factory(  )", signatures)
        self.assertIn("demo.Example.Nested.nested(  )", signatures)
        self.assertFalse(any(signature.endswith(".run(  )") for signature in signatures))


if __name__ == "__main__":
    unittest.main()
