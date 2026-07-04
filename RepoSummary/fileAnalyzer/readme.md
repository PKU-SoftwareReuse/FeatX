# File Analyzer Utility

This directory contains a JavaParser-based utility used during repository
analysis experiments. It is not part of the recommended ASE artifact-evaluation
path; the Docker Compose deployment uses the integrated backend and RepoSummary
workflow.

For manual experimentation:

```bash
javac -encoding utf8 -cp "lib/*" src/ImportAnalyzer.java -d out
java -cp "out:lib/javaparser-core-3.25.1.jar" ImportAnalyzer "/path/to/java/repository"
```

The input should be a Java repository or source tree. Generated build output
under `out/` is not required for the artifact package.
