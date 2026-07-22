import {buildReadOnlyDiffFiles} from "./CodeDiffComponent";

test("reconstructs read-only file contents at their source line numbers", () => {
    const files = buildReadOnlyDiffFiles(`diff --git a/src/Demo.java b/src/Demo.java
index 1111111..2222222 100644
--- a/src/Demo.java
+++ b/src/Demo.java
@@ -3,3 +3,3 @@
 class Demo {
-    int value = 1;
+    int value = 2;
 }
`);

    expect(files).toHaveLength(1);
    expect(files[0]).toMatchObject({
        path: "src/Demo.java",
        language: "java",
        status: "M",
    });
    expect(files[0].originalContent.split("\n")[3]).toBe("    int value = 1;");
    expect(files[0].modifiedContent.split("\n")[3]).toBe("    int value = 2;");
});

test("keeps repository diff files independently selectable", () => {
    const files = buildReadOnlyDiffFiles(`diff --git a/a.txt b/a.txt
--- a/a.txt
+++ b/a.txt
@@ -1 +1 @@
-old
+new
diff --git a/b.txt b/b.txt
new file mode 100644
--- /dev/null
+++ b/b.txt
@@ -0,0 +1 @@
+added
`);

    expect(files.map((file) => file.path)).toEqual(["a.txt", "b.txt"]);
    expect(files.map((file) => file.status)).toEqual(["M", "A"]);
});

test("recognizes a fully-qualified Java class id as Java source", () => {
    const files = buildReadOnlyDiffFiles(`diff --git a/top.naccl.util.comment.CommentUtils b/top.naccl.util.comment.CommentUtils
--- a/top.naccl.util.comment.CommentUtils
+++ b/top.naccl.util.comment.CommentUtils
@@ -1 +1 @@
-public class CommentUtils {}
+public class CommentUtils { int value; }
`);

    expect(files).toHaveLength(1);
    expect(files[0]).toMatchObject({
        path: "top.naccl.util.comment.CommentUtils",
        language: "java",
    });
});
