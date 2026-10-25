// WelcomePage.jsx
import {useEffect, useState} from "react";
import {useNavigate} from 'react-router-dom';
import styles from './WelcomePage.module.css';
import {Button, Card, Select, Form, Input, message, Modal, Popconfirm, Popover, Progress, Spin, Tag, Tooltip} from "antd";
import {ArrowRightOutlined, AppstoreOutlined, FolderOutlined, SearchOutlined, DeleteOutlined, ExclamationCircleOutlined, GithubOutlined, SettingOutlined, SyncOutlined} from "@ant-design/icons";
import API from "../api";
import FolderUploadModal from "./FolderUploadModal/FolderUploadModal";
import GitDownModal from "./GitDownModal/GitDownModal";
import {
    formatLocalizedDuration,
    localizeSummaryProgressMessage,
    localizeSummaryStageLabel,
    localizeSummaryStatus,
    localizeSummaryStepDetail,
} from "../utils/progressText";

const projectIcons = ["projects/01-cube.svg", "projects/02-hexagon.svg", "projects/03-prism.svg", "projects/04-rings.svg", "projects/05-puzzle.svg", "projects/06-mountain.svg", "projects/07-leaf.svg", "projects/08-sun.svg", "projects/09-moon.svg", "projects/10-wave.svg", "projects/11-rocket.svg", "projects/12-planet.svg", "projects/13-compass.svg", "projects/14-telescope.svg", "projects/15-flag.svg", "projects/16-lighthouse.svg", "projects/17-bridge.svg", "projects/18-castle.svg", "projects/19-tent.svg", "projects/20-house.svg", "projects/21-key.svg", "projects/22-gear.svg", "projects/23-toolbox.svg", "projects/24-hourglass.svg", "projects/25-magnet.svg", "projects/26-spiral.svg", "projects/27-orbit.svg", "projects/28-blocks.svg", "projects/29-crystal.svg", "projects/30-paper-plane.svg"];
const asset = (file) => `/assets/welcome/${file}`;
const projectIcon = (id) => asset(projectIcons[Array.from(String(id)).reduce((hash, char) => (hash * 31 + char.charCodeAt(0)) >>> 0, 0) % projectIcons.length]);

const WELCOME_COPY = {
        headline: "面向大语言模型编程的功能特征导向界面",
        productLine: "FeatX：通过编辑功能特征来编辑软件",
        analyzing: "正在分析并处理所选代码仓库。",
        connecting: "正在连接……",
        projectDeleted: "项目已删除。",
        projectDeleteFailed: "项目删除失败。",
        projectUpdated: "项目信息已更新。",
        projectUpdateFailed: "项目信息更新失败。",
        resummaryFailed: "重新生成摘要失败。",
        resummaryCompleted: "功能特征摘要已重新生成。",
        confirmDelete: "确认删除项目",
        confirmDeleteDescription: "确定要删除此项目吗？删除后无法恢复。",
        delete: "删除",
        cancel: "取消",
        confirmResummary: "确认重新生成功能特征摘要",
        confirmResummaryDescription: "确定要重新生成功能特征摘要吗？此过程可能需要几分钟。",
        confirm: "确认",
        settings: "项目设置",
        save: "保存",
        projectName: "项目名称",
        description: "项目描述",
        gitRepository: "Git 仓库",
        gitRepositoryPlaceholder: "GitHub、GitLab、GitLink 或其他 Git 仓库地址",
        projectNameRequired: "请输入项目名称。",
        projectActions: "项目操作",
        resummary: "重新生成摘要",
        deleteAction: "删除项目",
        close: "取消",
        notLinked: "未关联",
        summaryInProgress: "正在生成功能特征摘要……",
        summaryProgressTitle: "功能特征摘要进度",
        summaryProgressUnavailable: "此运行暂未捕获结构化进度。",
        summaryProgressFallback: "功能特征摘要正在运行。",
        summaryProgressPendingDetail: "新启动的任务会显示详细进度。",
        summaryDetails: "详情",
        elapsed: "已用时",
        step: "步骤",
        waiting: "等待中。",
        open: "打开",
        openFailed: "无法打开项目。",
        pendingOperationBlocksOpen: "另一个项目中还有尚未确认的 Agent 修改，请先返回该项目确认或放弃修改。",
        discardBeforeSwitchTitle: "放弃当前修改并切换项目？",
        discardBeforeSwitchDescription: "当前项目仍有未完成或未确认的 Agent 修改。继续将恢复当前项目到最近一次提交，并清除全部暂存、未暂存及非忽略的未跟踪文件；已有提交不会删除。完成后将打开目标项目。",
        discardAndOpen: "放弃并打开",
        discardAndSwitchFailed: "放弃当前修改或切换项目失败。",
        metrics: {
            language: "编程语言",
            linesOfCode: "代码行数",
            pythonFiles: "Python 文件数",
            functionsMethods: "函数 / 方法数",
            classes: "类数量",
            methods: "方法数量",
            fields: "字段数量",
        },
        intro: "FeatX 提供一体化环境，支持通过编辑功能特征来编辑软件。其工作流程如下：",
        steps: [
            ["功能特征摘要。", "构建分层的功能特征列表，将代码仓库中的代码组织为功能主题及其下属功能特征。"],
            ["相关代码图谱构建。", "构建完整的相关代码图谱，涵盖每项功能特征的全部实现上下文。"],
            ["智能体生成。", "采用三阶段智能体流程，按照成熟的软件工程工作流生成一致的文件级修改。"],
            ["代码变更确认。", "用户审核生成的代码变更，并将确认后的内容应用回代码仓库。"],
        ],
    };

const statusColor = (status) => {
    if (status === "done" || status === "complete") return "success";
    if (status === "running") return "processing";
    if (status === "failed") return "error";
    return "default";
};

const getGitLink = (project) => project.gitLink || project.githubLink || "";

const getGitName = (project, copy) => project.gitName || project.githubName || getGitLink(project) || copy.notLinked;

const isBrowsableGitLink = (link) => /^https?:\/\//i.test(link || "");

const WelcomePage = () => {
    const navigate = useNavigate();

    const copy = WELCOME_COPY;

    const [loadingConnect, setLoadingConnect] = useState(false);
    const [loadingAnalyse, setLoadingAnalyse] = useState(false);
    const [projectOptions, setProjectOptions] = useState(null);
    const [search, setSearch] = useState("");
    const [sourceFilter, setSourceFilter] = useState("all");
    const [sortOrder, setSortOrder] = useState("newest");
    const [settingsProject, setSettingsProject] = useState(null);
    const [settingsSaving, setSettingsSaving] = useState(false);
    const [settingsAction, setSettingsAction] = useState(null);
    const [summaryProgressByRepo, setSummaryProgressByRepo] = useState({});
    const [settingsForm] = Form.useForm();
    const [modal, modalContextHolder] = Modal.useModal();


    useEffect(() => {
        testConnect();
        getProjects();
    }, [])

    useEffect(() => {
        if (!settingsProject) return;
        settingsForm.resetFields();
        settingsForm.setFieldsValue({
            projectName: settingsProject.projectName || "",
            gitLink: getGitLink(settingsProject),
            ["description"]:
                settingsProject.description || "",
        });
    }, [settingsForm, settingsProject]);

    const testConnect = () => {
        setLoadingConnect(true);
        API.testConnect().then(res => {
            setLoadingConnect(false);
        }).catch(err => {
            console.log(err);
            setLoadingConnect(false);
            message.error(err.message);
        })
    }

    const getProjects = (silent = false) => {
        if (!silent) {
            setLoadingConnect(true);
        }
        API.getProjectsInfo().then(data => {
            setProjectOptions(data);
            if (!silent) {
                setLoadingConnect(false);
            }
        }).catch(err => {
            console.log(err);
            if (!silent) {
                setLoadingConnect(false);
                message.error(err.message);
            }
        })
    }

    const fetchSummaryProgress = () => {
        API.getSummaryProgressAll().then(data => {
            setSummaryProgressByRepo(data || {});
        }).catch(err => {
            console.log(err);
        });
    }

    const hasPendingSummary = projectOptions?.some(project => !project.summaryFlag || project.summaryPending);

    useEffect(() => {
        if (!hasPendingSummary) {
            return;
        }
        fetchSummaryProgress();
        const progressTimer = setInterval(fetchSummaryProgress, 2000);
        const projectTimer = setInterval(() => getProjects(true), 5000);
        return () => {
            clearInterval(progressTimer);
            clearInterval(projectTimer);
        };
    }, [hasPendingSummary]);

    const handleConfirmButton = async (repoId) => {
        setLoadingAnalyse(true);
        try {
            const currentProject = await API.getCurrentProject().catch(() => null);
            if (String(currentProject?.repoId) !== String(repoId)) {
                await API.postProjectPath(repoId);
            }
            navigate('/working')
        } catch (error) {
            console.log(error)
            if (error?.response?.status === 409) {
                modal.confirm({
                    title: copy.discardBeforeSwitchTitle,
                    icon: <ExclamationCircleOutlined/>,
                    content: copy.discardBeforeSwitchDescription,
                    okText: copy.discardAndOpen,
                    okButtonProps: {danger: true},
                    cancelText: copy.cancel,
                    onOk: async () => {
                        setLoadingAnalyse(true);
                        try {
                            await API.discardFeatureChanges();
                            await API.postProjectPath(repoId);
                            navigate('/working');
                        } catch (switchError) {
                            console.log(switchError);
                            message.error(
                                switchError?.response?.data?.message || copy.discardAndSwitchFailed
                            );
                        } finally {
                            setLoadingAnalyse(false);
                        }
                    },
                });
            } else {
                message.error(error?.response?.data?.message || copy.openFailed);
            }
        } finally {
            setLoadingAnalyse(false);
        }
    }

    const handleResummaryButton = (repoId) => {
        setSettingsAction("resummary");
        API.postResummary(repoId).then(() => {
            message.success("分析请求已提交。");
            setSettingsProject(null);
            getProjects();
        }).catch(error => {
            message.error(error.message || copy.resummaryFailed);
            fetchSummaryProgress();
            console.log(error);
        }).finally(() => {
            setSettingsAction(null);
        })
    }

    const handleDropButton = (repoId) => {
        setSettingsAction("delete");
        setLoadingConnect(true);
        API.postDropRepo(repoId).then(() => {
            message.success(copy.projectDeleted);
            setSettingsProject(null);
            getProjects();
        }).catch(error => {
            setLoadingConnect(false);
            message.error(copy.projectDeleteFailed);
            console.log(error)
        }).finally(() => {
            setSettingsAction(null);
        })
    }

    const openSettings = (project) => {
        setSettingsProject(project);
    };

    const closeSettings = () => {
        if (settingsSaving || settingsAction) return;
        setSettingsProject(null);
        settingsForm.resetFields();
    };

    const handleSaveSettings = async () => {
        if (!settingsProject) return;

        try {
            const values = await settingsForm.validateFields();
            setSettingsSaving(true);
            const updatedProject = await API.updateProject(settingsProject.id, values);
            setProjectOptions((projects) => projects.map((project) =>
                project.id === updatedProject.id ? updatedProject : project
            ));
            setSettingsProject(null);
            settingsForm.resetFields();
            message.success(copy.projectUpdated);
        } catch (error) {
            if (error?.errorFields) return;
            message.error(error.message || copy.projectUpdateFailed);
            console.log(error);
        } finally {
            setSettingsSaving(false);
        }
    };

    const progressForProject = (project) => {
        return summaryProgressByRepo?.[project.id] || summaryProgressByRepo?.[String(project.id)];
    }

    const progressPercent = (progress) => {
        if (!progress || progress.percent == null) return 0;
        if (typeof progress.percent === "number") {
            return Math.max(0, Math.min(100, progress.percent));
        }
        if (progress.totalSteps) {
            return Math.round(((progress.currentStep || 1) - 1) * 100 / progress.totalSteps);
        }
        return 0;
    }

    const summaryTooltip = (project) => {
        const progress = progressForProject(project);
        if (project.summaryFlag && !project.summaryPending) {
            return "";
        }
        if (!progress) {
            return (
                <div>
                    <div>{copy.summaryProgressFallback}</div>
                    <div>{copy.summaryProgressPendingDetail}</div>
                </div>
            );
        }
        return (
            <div>
                <div>{localizeSummaryProgressMessage(progress)}</div>
                <div>{copy.elapsed}: {formatLocalizedDuration(progress.elapsedMs)}</div>
                <div>{copy.step}: {progress.currentStep}/{progress.totalSteps}</div>
            </div>
        );
    }

    const renderSummaryProgress = (project) => {
        const progress = progressForProject(project);
        if (!progress) {
            return (
                <div className={styles.summaryProgressPanel}>
                    <div className={styles.summaryProgressTitle}>{copy.summaryProgressFallback}</div>
                    <div className={styles.summaryProgressMuted}>
                        {copy.summaryProgressUnavailable}
                    </div>
                </div>
            );
        }

        return (
            <div className={styles.summaryProgressPanel}>
                <div className={styles.summaryProgressHeader}>
                    <div>
                        <div className={styles.summaryProgressTitle}>
                            {localizeSummaryProgressMessage(progress)}
                        </div>
                        <div className={styles.summaryProgressMuted}>
                            {copy.elapsed} {formatLocalizedDuration(progress.elapsedMs)} · {copy.step} {progress.currentStep}/{progress.totalSteps}
                        </div>
                    </div>
                    <Tag color={statusColor(progress.status)}>
                        {localizeSummaryStatus(progress.status)}
                    </Tag>
                </div>
                <Progress
                    percent={Math.round(progressPercent(progress))}
                    showInfo={progress.percent != null}
                    size="small"
                    status={progress.status === "failed" ? "exception" : "active"}
                    className={styles.summaryProgressBar}
                />
                <div className={styles.summaryStepList}>
                    {(progress.steps || []).map(step => (
                        <div key={step.id} className={styles.summaryStepItem}>
                            <Tag color={statusColor(step.status)} className={styles.summaryStepTag}>
                                {localizeSummaryStatus(step.status)}
                            </Tag>
                            <div className={styles.summaryStepBody}>
                                <div className={styles.summaryStepLabel}>
                                    {localizeSummaryStageLabel(step)}
                                </div>
                                <div className={styles.summaryProgressMuted}>
                                    {localizeSummaryStepDetail(step)}
                                    {step.elapsedMs ? ` · ${formatLocalizedDuration(step.elapsedMs)}` : ""}
                                    {typeof step.percent === "number" ? ` · ${Math.round(step.percent)}%` : ""}
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        );
    }

    const visibleProjects = (projectOptions || []).filter(project => {
        const isUpload = project.source?.kind === "upload";
        return (sourceFilter === "all" || (sourceFilter === "upload" ? isUpload : !isUpload))
            && `${project.projectName} ${getGitLink(project)}`.toLowerCase().includes(search.trim().toLowerCase());
    }).sort((a, b) => sortOrder === "name"
        ? a.projectName.localeCompare(b.projectName, "zh-CN")
        : (Date.parse(b.createdAt) || 0) - (Date.parse(a.createdAt) || 0));

    return (
        <div className={styles.welcomePage} style={{"--hero-art": `url(${asset("illustrations/hero-background.svg")})`}}>
            {modalContextHolder}
            <header className={styles.header}>
                <div className={styles.headerInner}>
                    <a href="/welcome" className={styles.brand} aria-label="FeatX 首页"><img src={asset("brand/featx-logo.svg")} alt="FeatX"/></a>
                    <nav className={styles.navigation} aria-label="主导航">
                        <a href="#projects" className={styles.activeNav}><AppstoreOutlined/> 项目仓库</a>
                    </nav>
                    <span className={styles.headerCaption}>从功能出发，理解代码</span>
                </div>
            </header>
            <section id="workflow" className={styles.hero}>
                <div className={styles.heroInner}>
                    <div className={styles.heroCopy}>
                        <span className={styles.eyebrow}>功能特征导向的软件工作空间</span>
                        <h1>通过功能特征<br/><em>理解与编辑软件</em></h1>
                        <p>导入你的项目，整理功能主题与功能项，<br/>在代码图谱中探索功能与实现的关联。</p>
                    </div>
                    <div className={styles.workflow}>
                        {[
                            ["edit-features", "编辑功能特征", "用自然语言描述或修改软件的功能需求"],
                            ["file-graph", "定位相关代码", "通过功能与代码的关联，定位相关实现"],
                            ["agent-edit", "智能体编辑", "基于功能需求与相关代码，生成可审阅的修改"],
                        ].map(([image, title, description], index) => <div key={image} className={styles.flowCard}>
                            <img src={asset(`illustrations/${image}.svg`)} alt=""/>
                            <h2><span>{index + 1}</span>{title}</h2>
                            <p>{description}</p>
                        </div>)}
                    </div>
                </div>
            </section>
            <main id="projects" className={styles.repository}>
                <div className={styles.repositoryHeading}>
                    <div><h2><AppstoreOutlined/> 项目仓库 <span>{projectOptions?.length ?? "—"}</span></h2>
                        <p>所有项目，一个工作空间。选择项目，继续探索。</p></div>
                    <div className={styles.uploadModalWrapper}>
                        <GitDownModal reloadGetProjectsInfo={getProjects}/>
                        <FolderUploadModal reloadGetProjectsInfo={getProjects}/>
                    </div>
                </div>
                <div className={styles.toolbar}>
                    <Input allowClear prefix={<SearchOutlined/>} placeholder="搜索项目名称或仓库地址" aria-label="搜索项目" value={search} onChange={event => setSearch(event.target.value)}/>
                    <div className={styles.filters}>
                        <Select aria-label="项目来源" value={sourceFilter} onChange={setSourceFilter} options={[{value:"all",label:"全部来源"},{value:"git",label:"Git 导入"},{value:"upload",label:"本地上传"}]}/>
                        <Select aria-label="项目排序" value={sortOrder} onChange={setSortOrder} options={[{value:"newest",label:"最新创建"},{value:"name",label:"按名称排序"}]}/>
                    </div>
                </div>
                <Spin tip={copy.analyzing} spinning={loadingAnalyse}>
                    <Spin tip={copy.connecting} spinning={loadingConnect}>
                        <div className={styles.projectGrid}>
                            {visibleProjects.map(project => {
                                const progress = progressForProject(project);
                                const state = progress?.status || project.analysis?.status;
                                const pending = project.summaryPending;
                                const failed = state === "failed";
                                const label = pending ? "总结中" : failed ? "总结失败" : project.summaryFlag ? "已就绪" : "待总结";
                                const revision = project.git?.refName || project.git?.branch || project.source?.commitId || project.git?.commit?.slice(0, 8);
                                return <Card key={project.id} className={styles.projectCard}>
                                    <div className={styles.cardTop}>
                                        <img className={styles.projectIcon} src={projectIcon(project.id)} alt=""/>
                                        <div className={styles.cardIdentity}><Tooltip title={project.projectName}><h3>{project.projectName}</h3></Tooltip>
                                            <span>{project.source?.kind === "upload" ? "本地上传" : "Git 导入"}</span></div>
                                        <Tooltip title={copy.settings}><Button type="text" icon={<SettingOutlined/>} aria-label={`${project.projectName} 项目设置`} onClick={() => openSettings(project)}/></Tooltip>
                                    </div>
                                    <div className={styles.cardMeta}>
                                        <Tag color={pending ? "processing" : failed ? "error" : project.summaryFlag ? "success" : "default"}>{label}</Tag>
                                        {revision && <span title={revision} className={styles.revision}>{revision}</span>}
                                    </div>
                                    <div className={styles.cardSummary}>
                                        <p className={styles.projectDescription} title={project.description || undefined}>{project.description || (pending ? "正在生成项目简介……" : failed ? "总结未完成，可查看详情或重新生成。" : project.summaryFlag ? "暂无项目简介" : "尚未生成功能摘要。")}</p>
                                        {pending && project.description && <p className={styles.summaryStatus}>{localizeSummaryProgressMessage(progress || {})}</p>}
                                        {pending && progress?.percent != null && <Progress percent={Math.round(progress.percent)} size="small"/>}
                                    </div>
                                    <div className={styles.cardSource}>
                                        {isBrowsableGitLink(getGitLink(project)) ? <a href={getGitLink(project)} target="_blank" rel="noopener noreferrer" title={getGitLink(project)}><GithubOutlined/><span>{getGitName(project, copy)}</span></a>
                                            : <span><FolderOutlined/> {project.source?.kind === "upload" ? "本地项目" : getGitName(project, copy)}</span>}
                                    </div>
                                    <div className={styles.cardFooter}>
                                        <span className={styles.createdAt}>{project.createdAt ? new Date(project.createdAt).toLocaleDateString("zh-CN") : ""}</span>
                                        <div className={styles.buttonContainer}>
                                            {(!project.summaryFlag || pending || failed) && <Popover title={copy.summaryProgressTitle} content={renderSummaryProgress(project)} trigger="click"><Button type="text" size="small">进度详情</Button></Popover>}
                                            <Tooltip title={!project.summaryFlag ? summaryTooltip(project) : ""}><Button type="primary" disabled={!project.summaryFlag || loadingAnalyse} onClick={() => handleConfirmButton(project.id)}>打开项目 <ArrowRightOutlined/></Button></Tooltip>
                                        </div>
                                    </div>
                                </Card>;
                            })}
                        </div>
                        {projectOptions && !visibleProjects.length && <div className={styles.emptyState}>
                            <img src={asset("illustrations/empty-projects.svg")} alt=""/>
                            <h3>{projectOptions.length ? "没有找到匹配的项目" : "从你的第一个项目开始"}</h3>
                            <p>{projectOptions.length ? "试试其他关键词，或调整来源筛选。" : "点击上方 Git 导入或上传文件夹，开始整理功能摘要。"}</p>
                            {projectOptions.length > 0 && <Button type="link" onClick={() => {setSearch("");setSourceFilter("all");}}>清除筛选</Button>}
                        </div>}
                    </Spin>
                </Spin>
            </main>
            <Modal
                title={copy.settings}
                open={Boolean(settingsProject)}
                onCancel={closeSettings}
                onOk={handleSaveSettings}
                okText={copy.save}
                cancelText={copy.close}
                confirmLoading={settingsSaving}
                okButtonProps={{disabled: Boolean(settingsAction)}}
                maskClosable={false}
                width={620}
            >
                <Form form={settingsForm} layout="vertical" requiredMark={false}>
                    <Form.Item
                        name="projectName"
                        label={copy.projectName}
                        rules={[{required: true, whitespace: true, message: copy.projectNameRequired}]}
                    >
                        <Input maxLength={255}/>
                    </Form.Item>
                    {<Form.Item name="description" label={copy.description}>
                            <Input.TextArea autoSize={{minRows: 3, maxRows: 6}}/>
                        </Form.Item>}
                    <Form.Item name="gitLink" label={copy.gitRepository}>
                        <Input placeholder={copy.gitRepositoryPlaceholder} maxLength={2048}/>
                    </Form.Item>
                </Form>

                <div className={styles.settingsActions}>
                    <span className={styles.settingsActionsLabel}>{copy.projectActions}</span>
                    <div className={styles.settingsActionButtons}>
                        <Popconfirm
                            title={copy.confirmResummary}
                            description={copy.confirmResummaryDescription}
                            onConfirm={() => handleResummaryButton(settingsProject?.id)}
                            okText={copy.confirm}
                            cancelText={copy.cancel}
                            disabled={!settingsProject?.summaryFlag}
                        >
                            <Button
                                icon={<SyncOutlined/>}
                                loading={settingsAction === "resummary"}
                                disabled={settingsSaving || !settingsProject?.summaryFlag}
                            >
                                {copy.resummary}
                            </Button>
                        </Popconfirm>
                        <Popconfirm
                            title={copy.confirmDelete}
                            description={copy.confirmDeleteDescription}
                            onConfirm={() => handleDropButton(settingsProject?.id)}
                            okText={copy.delete}
                            cancelText={copy.cancel}
                        >
                            <Button
                                danger
                                icon={<DeleteOutlined/>}
                                loading={settingsAction === "delete"}
                                disabled={settingsSaving || Boolean(settingsAction)}
                            >
                                {copy.deleteAction}
                            </Button>
                        </Popconfirm>
                    </div>
                </div>
            </Modal>

            <footer className={styles.footer}>FeatX · 从功能到代码，让项目更清晰</footer>
        </div>
    );
}

export default WelcomePage;
