from typing import Any, Literal
from urllib.parse import urlsplit
from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class Source(BaseModel):
    model_config = ConfigDict(extra='forbid')
    url: str = Field(min_length=1, max_length=2048)
    commitId: str | None = Field(default=None, pattern=r'^[0-9a-fA-F]{7,64}$')

    @field_validator('url')
    @classmethod
    def git_url(cls, value):
        if any(char.isspace() or ord(char) < 32 for char in value):
            raise ValueError('Git 地址不能包含空白或控制字符')
        parsed = urlsplit(value)
        if parsed.scheme not in {'https', 'http', 'ssh'} or not parsed.hostname:
            raise ValueError('请使用 HTTP(S) 或 ssh:// 格式的 Git 地址')
        if parsed.password or (parsed.scheme != 'ssh' and parsed.username):
            raise ValueError('Git 地址不能包含密码或令牌')
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
            raise ValueError('项目名称不能为空')
        return value.strip()


class Feature(BaseModel):
    id: str = Field(min_length=1)
    description: str
    codeElements: list[str]

    @field_validator('codeElements')
    @classmethod
    def elements(cls, value):
        if any(not item.strip() for item in value) or len(set(value)) != len(value):
            raise ValueError('代码元素必须是非空字符串，且不能重复')
        return value


class Epic(BaseModel):
    id: str = Field(min_length=1)
    description: str
    features: list[Feature]


class ProjectSummary(BaseModel):
    projectId: str
    runId: str
    description: str = ""  # Older summary files may not contain a project description.
    epics: list[Epic]

    @model_validator(mode='after')
    def ids(self):
        epic_ids = [e.id for e in self.epics]
        feature_ids = [f.id for e in self.epics for f in e.features]
        if len(set(epic_ids)) != len(epic_ids) or len(set(feature_ids)) != len(feature_ids):
            raise ValueError('功能主题或功能项 ID 重复')
        return self


class Task(BaseModel):
    projectId: str
    runId: str
    status: Literal['queued', 'running', 'complete', 'failed']
    totalStages: int | None = Field(default=None, ge=0)
    currentStageIndex: int | None = Field(default=None, ge=1)
    overallPercent: float | None = Field(default=None, ge=0, le=100)
    elapsedMs: int | None = None
    stages: list[dict[str, Any]] = Field(default_factory=list)
    message: str = ''
    error: str | None = None
    hints: dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode='after')
    def stages_consistent(self):
        if self.totalStages is not None and self.totalStages != len(self.stages):
            raise ValueError('总阶段数与阶段列表不一致')
        if self.currentStageIndex is not None and self.currentStageIndex > len(self.stages):
            raise ValueError('当前阶段序号无效')
        return self
