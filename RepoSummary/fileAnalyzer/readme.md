javac -encoding utf8 -cp "lib/*" src/ImportAnalyzer.java -d out
java -cp "out:lib/javaparser-core-3.25.1.jar" ImportAnalyzer "/path/to/java/repository"
