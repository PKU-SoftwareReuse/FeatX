import os

import pandas as pd
from dotenv import load_dotenv


BASE_DIR = os.path.dirname(os.path.abspath(__file__))

load_dotenv()


def ensure_nonempty_csv(file_path, required_columns=None):
    if not os.path.exists(file_path) or os.path.getsize(file_path) == 0:
        raise RuntimeError(f"Required RepoSummary output is empty or missing: {file_path}")

    df = pd.read_csv(file_path)
    if df.empty:
        raise RuntimeError(f"Required RepoSummary output has no rows: {file_path}")

    if required_columns:
        missing = [column for column in required_columns if column not in df.columns]
        if missing:
            raise RuntimeError(f"RepoSummary output {file_path} is missing columns: {missing}")

    return df


def clear_project_data(cursor, project_id):
    """
    清空指定 project_id 的所有相关表数据（modules、features、code_map、graph_edge）。
    注意删除顺序：依赖表 → 被依赖表
    """
    # 先删除 code_map（依赖 features）
    cursor.execute("""
        DELETE cm FROM code_map cm
        JOIN features f ON cm.feature = f.id
        JOIN modules m ON f.module = m.id
        WHERE m.repo = %s
    """, (project_id,))

    # 删除 features（依赖 modules）
    cursor.execute("""
        DELETE f FROM features f
        JOIN modules m ON f.module = m.id
        WHERE m.repo = %s
    """, (project_id,))

    # 删除 modules
    cursor.execute("""
        DELETE FROM modules
        WHERE repo = %s
    """, (project_id,))

    # 删除 graph_edge
    cursor.execute("""
        DELETE FROM graph_edge
        WHERE repo = %s
    """, (project_id,))

    # 可选：重置 project_info 的 summary_flag
    cursor.execute("""
        UPDATE project_info SET summary_flag = FALSE WHERE id = %s
    """, (project_id,))


def main(project_id):
    import mysql.connector

    conn = mysql.connector.connect(
        host=os.getenv("DB_HOST"),
        port=int(os.getenv("DB_PORT")),
        user=os.getenv("DB_USER"),
        password=os.getenv("DB_PASSWORD"),
        database=os.getenv("DB_NAME")
    )
    try:
        write_project_summary(project_id, conn)
    finally:
        conn.close()


def write_project_summary(project_id, conn):
    cursor = conn.cursor(dictionary=True)
    try:
        clear_project_data(cursor, project_id)
        save_features(project_id, cursor)
        save_edges(project_id, cursor)
        set_finish(project_id, cursor)
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        cursor.close()


def normalize_description(value, fallback):
    if value is None:
        return fallback
    if pd.isna(value):
        return fallback
    description = str(value).strip()
    if not description or description.lower() in {"nan", "null", "none"}:
        return fallback
    return description


def require_description(value, label):
    description = normalize_description(value, "")
    if not description:
        raise RuntimeError(f"RepoSummary output is missing {label}")
    return description


def get_or_create_module(cursor, repo_id, cluster_id, module_desc, module_desc_cn):
    module_desc = normalize_description(module_desc, f"Module {cluster_id} feature group")
    module_desc_cn = require_description(module_desc_cn, f"Chinese module description for cluster {cluster_id}")
    cursor.execute("""
        SELECT id FROM modules WHERE repo=%s AND cluster_id=%s
    """, (repo_id, cluster_id))
    result = cursor.fetchone()
    if result:
        cursor.execute("""
            UPDATE modules
            SET module_desc=CASE WHEN module_desc IS NULL OR module_desc='' THEN %s ELSE module_desc END,
                module_desc_cn=CASE WHEN module_desc_cn IS NULL OR module_desc_cn='' THEN %s ELSE module_desc_cn END
            WHERE id=%s
        """, (module_desc, module_desc_cn, result['id']))
        return result['id']
    cursor.execute("""
        INSERT INTO modules (repo, cluster_id, module_desc, module_desc_cn)
        VALUES (%s, %s, %s, %s)
    """, (repo_id, cluster_id, module_desc, module_desc_cn))
    return cursor.lastrowid


def get_or_create_feature(cursor, module_id, feature_id_val, feature_desc, feature_desc_cn):
    feature_desc = normalize_description(feature_desc, f"Feature {feature_id_val}")
    feature_desc_cn = require_description(feature_desc_cn, f"Chinese feature description for feature {feature_id_val}")
    cursor.execute("""
        SELECT id FROM features WHERE module=%s AND feature_id=%s
    """, (module_id, feature_id_val))
    result = cursor.fetchone()
    if result:
        cursor.execute("""
            UPDATE features
            SET feature_desc=CASE WHEN feature_desc IS NULL OR feature_desc='' THEN %s ELSE feature_desc END,
                feature_desc_cn=CASE WHEN feature_desc_cn IS NULL OR feature_desc_cn='' THEN %s ELSE feature_desc_cn END
            WHERE id=%s
        """, (feature_desc, feature_desc_cn, result['id']))
        return result['id']
    cursor.execute("""
        INSERT INTO features (module, feature_id, feature_desc, feature_desc_cn)
        VALUES (%s, %s, %s, %s)
    """, (module_id, feature_id_val, feature_desc, feature_desc_cn))
    return cursor.lastrowid


def get_or_create_code_map(cursor, feature_id, method_name):
    cursor.execute("""
        SELECT id FROM code_map WHERE feature=%s AND method_name=%s
    """, (feature_id, method_name))
    result = cursor.fetchone()
    if result:
        return result['id']
    cursor.execute("""
        INSERT INTO code_map (feature, method_name)
        VALUES (%s, %s)
    """, (feature_id, method_name))
    return cursor.lastrowid


def insert_row(row, cursor, repo_id):
    # 逐级查重或插入
    module_id = get_or_create_module(
        cursor,
        repo_id,
        row['cluster_id'],
        row['module_desc'],
        row['module_desc_cn'],
    )
    feature_id = get_or_create_feature(
        cursor,
        module_id,
        row['id'],
        row['desc'],
        row['desc_cn'],
    )
    get_or_create_code_map(cursor, feature_id, row['method_name'])


def save_features(project_id, cursor):
    file_path = os.path.join(BASE_DIR, "../output", project_id, "features.csv")

    df = ensure_nonempty_csv(
        file_path,
        ["id", "cluster_id", "module_desc", "module_desc_cn", "desc", "desc_cn", "method_name"],
    )
    for row in df.to_dict("records"):
        insert_row(row, cursor, project_id)


def get_or_create_graph_edge(cursor, src, dest, repo_id):
    cursor.execute("""
        SELECT id FROM graph_edge WHERE src=%s AND dest=%s AND repo=%s
    """, (src, dest, repo_id))
    result = cursor.fetchone()
    cursor.fetchall()  # 清除任何未读的查询结果
    if result:
        return result['id']
    cursor.execute("""
        INSERT INTO graph_edge (src, dest, repo) VALUES (%s, %s, %s)
    """, (src, dest, repo_id))
    return cursor.lastrowid


def save_edges(project_id, cursor):
    file_path = os.path.join(BASE_DIR, "../output", project_id, "file_adj_matrix.csv")

    # 加载邻接矩阵CSV（假设第一行和第一列是节点名）
    df = pd.read_csv(file_path, index_col=0)

    for src in df.index:
        for dst in df.columns:
            if df.loc[src, dst] == 1:
                get_or_create_graph_edge(cursor, src, dst, project_id)


def set_finish(project_id, cursor):
    cursor.execute("""
            UPDATE project_info SET summary_flag = TRUE WHERE id = %s
        """, (project_id,))


if __name__ == '__main__':
    main("16")
