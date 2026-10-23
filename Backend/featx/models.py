from typing import Any, Literal
from urllib.parse import urlsplit
from pydantic import BaseModel, Field, field_validator, model_validator


class Source(BaseModel):
    url: str = Field(min_length=1, max_length=2048)
    revision: str | None = Field(default=None, max_length=256)

    @field_validator('url')
    @classmethod
    def git_url(cls, value):
        parsed = urlsplit(value)
        if parsed.scheme not in {'https', 'http', 'ssh'} or not parsed.hostname:
            raise ValueError('Use an HTTP(S) or ssh:// Git URL')
        if parsed.password or (parsed.scheme != 'ssh' and parsed.username):
            raise ValueError('Do not put credentials in repository URLs')
        return value


class UploadSource(BaseModel):
    kind: Literal['upload']
    uploadId: str = Field(pattern=r'^[0-9a-f]{32}$')
    filename: str


class ProjectInput(BaseModel):
    name: str = Field(min_length=1, max_length=160)
    source: Source

    @field_validator('name')
    @classmethod
    def nonblank(cls, value):
        if not value.strip():
            raise ValueError('name cannot be blank')
        return value.strip()


class Feature(BaseModel):
    id: str = Field(min_length=1)
    name: str
    description: str = ''
    nodeIds: list[str] = Field(default_factory=list)


class Location(BaseModel):
    path: str
    startLine: int | None = Field(default=None, ge=1)
    endLine: int | None = Field(default=None, ge=1)


class Node(BaseModel):
    id: str = Field(min_length=1)
    name: str
    type: str
    location: Location | None = None
    attributes: dict[str, Any] = Field(default_factory=dict)


class Edge(BaseModel):
    source: str
    target: str
    type: str


class Graph(BaseModel):
    nodes: list[Node]
    edges: list[Edge]


class AnalysisResult(BaseModel):
    version: Literal['1']
    source: UploadSource | Source
    language: str | None = None
    features: list[Feature]
    graph: Graph

    @model_validator(mode='after')
    def references(self):
        node_ids = {n.id for n in self.graph.nodes}
        if len(node_ids) != len(self.graph.nodes):
            raise ValueError('duplicate node IDs')
        if len({f.id for f in self.features}) != len(self.features):
            raise ValueError('duplicate feature IDs')
        if any(e.source not in node_ids or e.target not in node_ids for e in self.graph.edges):
            raise ValueError('edge references an unknown node')
        if any(n not in node_ids for f in self.features for n in f.nodeIds):
            raise ValueError('feature references an unknown node')
        return self


class Task(BaseModel):
    taskId: str = Field(min_length=1, max_length=512)
    status: Literal['queued', 'running', 'complete', 'failed']
    progress: float | None = Field(default=None, ge=0, le=1)
    message: str = ''
    error: str | None = None
