import re
import pandas as pd
from collections import defaultdict
from typing import List, Optional, Any
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote
from urllib.request import Request, urlopen

from dotenv import load_dotenv

from ..analysis.java.method_analyzer import JavaMethodAnalyzer
from .clustering import cluster_all_functions_to_features, find_best_resolution, save_to_file_cluster
from .description_generation import generate_epic_descriptions, generate_feature_descriptions
from .embedding import load_summary_embedding_model
from .models import File, Function, method_Cluster
import os

IGNORED_ANALYSIS_DIRECTORIES = {
    ".git", "node_modules", "target", "build", "dist", "__pycache__", ".venv", "venv", "env",
    "preprocess1", "delombok", "preprocess2"
}


load_dotenv()


def create_directory_summary(root_path):
    Files_summary = []
    num = 0
    for root, dirs, files in os.walk(root_path):
        dirs[:] = [name for name in dirs if name not in IGNORED_ANALYSIS_DIRECTORIES]
        for file in files:
            if file.endswith(('.java')):
                afile_path = os.path.join(root, file)
                relative_path = os.path.relpath(afile_path, root_path)  # 转换为相对路径
                file_path = java_class_path_from_file(relative_path)
                file_name = file_path.split(".")[-1]
                with open(afile_path, 'r', encoding='utf-8') as f:
                    file_content = f.read()

                file_summary = File(
                    file_id=num,
                    file_name=file_name,
                    file_path=file_path,
                    file_code=file_content,
                    file_desc=file_name,
                    func_list=[],
                    file_txt_vector=[],
                    file_discode=""
                )
                print(file_summary.file_path + " " + file_summary.file_desc)
                Files_summary.append(file_summary)
                num += 1
    return Files_summary


def normalize_java_class_name(value: Any) -> str:
    if value is None:
        return ""
    class_name = str(value).strip()
    if class_name.endswith(".java"):
        class_name = class_name[:-5]
    class_name = class_name.replace("\\", ".").replace("/", ".")
    class_name = re.sub(r"\.+", ".", class_name).strip(".")
    return class_name


def java_class_path_from_file(path: str) -> str:
    class_path = os.path.splitext(str(path))[0].replace("\\", "/")
    lower_path = class_path.lower()
    for marker in ("src/main/java/", "src/main/kotlin/", "java/"):
        index = lower_path.find(marker)
        if index >= 0:
            class_path = class_path[index + len(marker):]
            break
    return normalize_java_class_name(class_path)


def java_class_name_from_signature(signature: str) -> str:
    method_path = str(signature).split("(", 1)[0]
    parts = method_path.split(".")
    if len(parts) <= 1:
        return method_path
    return ".".join(parts[:-1])


def simple_java_class_name(class_name: str) -> str:
    class_name = normalize_java_class_name(class_name)
    return class_name.split(".")[-1] if class_name else ""


def add_functions_to_files(files: List[File], functions: List[Function]):
    files_by_path = {normalize_java_class_name(file.file_path): file for file in files}
    files_by_simple_name = defaultdict(list)
    for file in files:
        files_by_simple_name[file.file_name].append(file)

    attached = 0
    unmatched = []
    for function in functions:
        candidate_classes = [
            normalize_java_class_name(function.func_file),
            java_class_name_from_signature(function.func_fullName),
        ]

        matched_file = None
        for class_name in candidate_classes:
            if class_name in files_by_path:
                matched_file = files_by_path[class_name]
                break

        if matched_file is None:
            for class_name in candidate_classes:
                simple_name = simple_java_class_name(class_name)
                simple_matches = files_by_simple_name.get(simple_name, [])
                if len(simple_matches) == 1:
                    matched_file = simple_matches[0]
                    break

        if matched_file is None:
            unmatched.append(function.func_fullName)
            continue

        matched_file.func_list.append(function)
        attached += 1

    print(f"Attached {attached}/{len(functions)} methods to files")
    if unmatched:
        print(f"Unmatched method examples: {unmatched[:5]}")
    return attached


def class_name_from_method_row(row) -> str:
    class_name = row.get("class_name", "")
    class_name = normalize_java_class_name(class_name)
    if class_name:
        return class_name
    return java_class_name_from_signature(row["method_signature"])


def features_to_csv(features, method_clusters, filename):
    rows = []
    # 创建 DataFrame
    for feature in features:
        # 根据cluster_id获取对应的method_cluster
        method_cluster = next((mc for mc in method_clusters if mc.cluster_id == feature.cluster_id), None)
        for function in feature.feature_func_list:
            # 将函数的文件路径添加到列表中
            rows.append({
                "id": feature.feature_id,
                "cluster_id": feature.cluster_id,
                "module_desc": method_cluster.cluster_desc,
                "desc": feature.feature_desc,
                "method_name": function.func_fullName,
                "flow": feature.feature_flow,
                "notf": feature.feature_notf,
            })
    df = pd.DataFrame(rows)

    # 保存为 CSV 文件
    df.to_csv(filename, index=False)
    print(f"Features saved to {filename}")


def _repo_id_from_project_root(project_root: str) -> str:
    """Resolve the FeatX repository id for direct repo_summary callers."""
    configured_root = os.getenv("LOTM_REPO_PATH")
    if configured_root:
        try:
            relative = Path(project_root).resolve().relative_to(Path(configured_root).resolve())
            if relative.parts:
                return relative.parts[0]
        except ValueError:
            pass
    raise RuntimeError(
        "Java import analysis requires a repository id. Pass repo_id to repo_summary "
        "or configure LOTM_REPO_PATH so it can be inferred from project_root."
    )


def _request_java_import_matrix(repo_id: str, output_dir: str) -> None:
    """Ask the Spring Boot Java service for the import matrix."""
    backend_url = os.getenv("FEATX_BACKEND_URL")
    if not backend_url:
        backend_port = os.getenv("SERVER_PORT", "8080")
        backend_url = f"http://127.0.0.1:{backend_port}"
    endpoint = (
        backend_url.rstrip("/")
        + "/analysis/java/import-matrix?repoId="
        + quote(str(repo_id), safe="")
    )
    timeout = float(os.getenv("FEATX_BACKEND_TIMEOUT_SECONDS", "120"))
    request = Request(endpoint, headers={"Accept": "text/csv"}, method="GET")
    try:
        with urlopen(request, timeout=timeout) as response:
            matrix = response.read()
    except HTTPError as error:
        details = error.read().decode("utf-8", errors="replace").strip()
        raise RuntimeError(
            f"Spring Boot Java import analysis returned HTTP {error.code}: {details}"
        ) from error
    except URLError as error:
        raise RuntimeError(
            f"Unable to reach Spring Boot Java import analysis at {endpoint}: {error.reason}"
        ) from error

    if not matrix.strip():
        raise RuntimeError("Spring Boot Java import analysis returned an empty matrix.")
    output_path = Path(output_dir)
    output_path.mkdir(parents=True, exist_ok=True)
    (output_path / "file_adj_matrix.csv").write_bytes(matrix)


def repo_summary(project_root: str, output_dir: str, repo_id: Optional[str] = None):
    # Java Import Analysis
    _request_java_import_matrix(repo_id or _repo_id_from_project_root(project_root), output_dir)

    # Java Method Analysis
    method_analyzer = JavaMethodAnalyzer()
    method_analyzer.analyze_project(project_root, output_dir)

    # Load methods
    method_df = pd.read_csv(os.path.join(output_dir, "method.csv"))
    functions = []

    for index, row in method_df.iterrows():
        function_fullName = row["method_signature"]
        function_name = function_fullName.split("(")[0].split(".")[-1]
        func_file = class_name_from_method_row(row)
        function = Function(
            func_id=row["ID"],
            func_name=function_name,
            func_desc=function_name,
            func_file=func_file,
            func_flow="",
            func_notf="",
            func_code=row["method_code"],
            func_fullName=function_fullName,
            func_txt_vector=[]
        )
        functions.append(function)
    # print(functions[5])
    for function in functions[:5]:
        print(
            f"Function ID: {function.func_id}, file name: {function.func_file}, Name: {function.func_name}, Description: {function.func_desc}")
        print(f"Code Snippet:\n{function.func_code}\n")

    # Load adjacency matrix
    func_adj_matrix_df = pd.read_csv(os.path.join(output_dir, 'method_adj_matrix.csv'), header=None).to_numpy()
    function_adj_matrix = func_adj_matrix_df[1:, 1:]

    for i, function in enumerate(functions):
        function.func_id = i  # 从0开始编号

    # Load files
    files = create_directory_summary(project_root)

    attached_functions = add_functions_to_files(files, functions)
    if functions and attached_functions == 0:
        raise RuntimeError(
            "No parsed Java methods were attached to files; check class/path normalization before generating features."
        )

    # Print some file and function details for verification
    for file in files[:5]:
        print(f"File ID: {file.file_id}, Name: {file.file_name}, Path: {file.file_path}, Description: {file.file_desc}")
        for function in file.func_list[:5]:  # 只打印前5个函数
            print(f"  Function ID: {function.func_id}, Name: {function.func_name}, Description: {function.func_desc}")
        print("\n")

    model = load_summary_embedding_model()
    for file in files:
        file.file_txt_vector = model.encode(file.file_desc).tolist()

    # files clustering
    best_gamma, best_labels, results = find_best_resolution(
        files,
        a=0.5,  # 更看重语义时调高
        n_points=25,
        gamma_min=0.05, gamma_max=0.6,  # 收窄范围减少过度切分
        seeds_per_gamma=8,
        use_knn=True, knn_k=20,  # 建议开启
        use_threshold=False, threshold_tau=0.0,
        min_clusters=3, max_clusters_ratio=0.15,
        min_cluster_size=3,
        use_silhouette=False,  # 默认关，避免误导
    )

    clusters = save_to_file_cluster(files, best_labels)

    for c in clusters:
        print(
            f"Cluster ID: {c.cluster_id}, {len(c.cluster_file_list)} Files: {[file.file_name for file in c.cluster_file_list]}")

    # 将clusters展开到函数层
    method_clusters = []
    for cluster in clusters:
        func_list = []
        for file in cluster.cluster_file_list:
            for function in file.func_list:
                # 将函数添加到聚类中
                function.func_txt_vector = model.encode(function.func_desc).tolist()
                func_list.append(function)
        method_cluster = method_Cluster(cluster.cluster_id, "", func_list)
        method_clusters.append(method_cluster)

    for method_cluster in method_clusters:
        print(
            f"Cluster ID: {method_cluster.cluster_id}, Functions: {[f.func_name for f in method_cluster.cluster_func_list]}")

    # functions clustering

    feature_list = []
    feature_list, summary = cluster_all_functions_to_features(
        method_clusters,
        function_adj_matrix=function_adj_matrix,
        weight_parameter=0.25,
        gamma_min=0.05, gamma_max=0.2, n_points=24,
        seeds_per_gamma=8,
        use_knn=True, knn_k=20,
        use_threshold=False, threshold_tau=0.0,
        min_clusters=5, max_clusters_ratio=0.2,
        use_silhouette=False, silhouette_sample_size=None,
        objective="CPM",
        consensus_tau=0.4, consensus_gamma=0.1,
        rng_seed=2025,
        target_total_features=None,  # 不再使用全局强约束
    )
    print(f"Total Features: {len(feature_list)}")
    for f in feature_list:
        print(f"Feature ID {f.feature_id}: {f.cluster_id} {set(x.func_file for x in f.feature_func_list)}")

    generate_feature_descriptions(feature_list, output_dir=output_dir)
    generate_epic_descriptions(feature_list, method_clusters, output_dir=output_dir)

    features_to_csv(feature_list, method_clusters, os.path.join(output_dir, "features.csv"))
