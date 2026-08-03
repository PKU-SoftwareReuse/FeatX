"""Language-neutral data structures used by RepoSummary pipelines."""

from dataclasses import dataclass, field
from typing import Any


@dataclass
class Function:
    func_id: int
    func_name: str
    func_desc: str
    func_file: str
    func_flow: str
    func_notf: str
    func_code: str
    func_fullName: str
    func_txt_vector: list[float] = field(default_factory=list)


@dataclass
class File:
    file_id: int
    file_name: str
    file_path: str
    file_code: str = ""
    file_desc: str = ""
    file_discode: str = ""
    func_list: list[Function] = field(default_factory=list)
    file_txt_vector: list[float] = field(default_factory=list)


@dataclass
class FileCluster:
    cluster_id: Any
    cluster_desc: str
    cluster_file_list: list[File]


@dataclass
class MethodCluster:
    cluster_id: Any
    cluster_desc: str
    cluster_func_list: list[Function]


@dataclass
class Feature:
    cluster_id: Any
    feature_id: int
    feature_desc: str
    feature_func_list: list[Function]
    feature_flow: str = ""
    feature_notf: str = ""


# Temporary source-compatible aliases while callers migrate to PEP 8 names.
file_Cluster = FileCluster
method_Cluster = MethodCluster
