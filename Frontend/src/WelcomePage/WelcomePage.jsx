// WelcomePage.jsx
import React, {useEffect, useState} from "react";
import {useNavigate} from 'react-router-dom';
import styles from './WelcomePage.module.css';
import {Button, Card, Descriptions, Form, Input, message, Modal, Popconfirm, Popover, Progress, Segmented, Spin, Tag, Tooltip,} from "antd";
import {DeleteOutlined, DownOutlined, ExclamationCircleOutlined, GithubOutlined, SettingOutlined, SyncOutlined, TranslationOutlined} from '@ant-design/icons';
import API from "../API";
import FolderUploadModal from "./FolderUploadModal/FolderUploadModal";
import GitDownModal from "./GitDownModal/GitDownModal";
import {getLocalizedField, useLanguage} from "../i18n/LanguageContext";

const WELCOME_COPY = {
    zh: {
        languageSelector: "界面语言",
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
        descriptionEn: "英文描述",
        descriptionCn: "中文描述",
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
    },
    en: {
        languageSelector: "Language",
        headline: "A Feature-Oriented Interface for LLM Programming",
        productLine: "FeatX: Editing Software by Editing Features",
        analyzing: "We're analyzing and processing your selected repo.",
        connecting: "Connecting....",
        projectDeleted: "Project deleted.",
        projectDeleteFailed: "Failed to delete project.",
        projectUpdated: "Project information updated.",
        projectUpdateFailed: "Failed to update project information.",
        resummaryFailed: "Failed to regenerate the summary.",
        resummaryCompleted: "Feature summary regenerated.",
        confirmDelete: "Confirm Delete Project",
        confirmDeleteDescription: "Do you want to delete this project? This cannot be restored.",
        delete: "Yes",
        cancel: "No",
        confirmResummary: "Confirm ReSummary",
        confirmResummaryDescription: "Do you want to get a brand new summary? It will take some minutes...",
        confirm: "Yes",
        settings: "Project Settings",
        save: "Save",
        projectName: "Project Name",
        descriptionEn: "English Description",
        descriptionCn: "Chinese Description",
        gitRepository: "Git Repository",
        gitRepositoryPlaceholder: "GitHub, GitLab, GitLink, or another Git repository URL",
        projectNameRequired: "Please enter a project name.",
        projectActions: "Project Actions",
        resummary: "Regenerate Summary",
        deleteAction: "Delete Project",
        close: "Cancel",
        notLinked: "Blank Git Link",
        summaryInProgress: "Repo summary in progress...",
        summaryProgressTitle: "Repo Summary Progress",
        summaryProgressUnavailable: "No structured progress has been captured for this run yet.",
        summaryProgressFallback: "Repo summary is running.",
        summaryProgressPendingDetail: "Detailed progress will appear for newly captured runs.",
        summaryDetails: "Details",
        elapsed: "Elapsed",
        step: "Step",
        waiting: "Waiting.",
        open: "Open",
        openFailed: "Failed to open the project.",
        pendingOperationBlocksOpen: "Another project still has unconfirmed Agent changes. Return to it and confirm or discard them first.",
        discardBeforeSwitchTitle: "Discard current changes and switch projects?",
        discardBeforeSwitchDescription: "The current project still has an unfinished or unconfirmed Agent change. Continuing restores it to the latest commit and removes all staged, unstaged, and non-ignored untracked files; existing commits are preserved. The target project will then open.",
        discardAndOpen: "Discard and open",
        discardAndSwitchFailed: "Failed to discard the current changes or switch projects.",
        metrics: {
            language: "Language",
            linesOfCode: "Line of Codes",
            pythonFiles: "Python Files",
            functionsMethods: "Functions / Methods",
            classes: "Classes",
            methods: "Number of Methods",
            fields: "Number of Fields",
        },
        javaClasses: "Number of Classes",
        intro: "FeatX provides an integrated environment that supports editing software by editing features. Its workflow can be described as follows:",
        steps: [
            ["Feature Summarization.", "Constructs a hierarchical feature list to organize repository code into features and epics."],
            ["CodeMap Construction.", "Builds a comprehensive CodeMap that captures the full implementation context of each feature."],
            ["CodeAgent Generation.", "A three-stage CodeAgent pipeline generates consistent file-level modifications following established software engineering workflows."],
            ["Diff Confirmation.", "The user reviews the code modifications and applies the confirmed changes back to the repository."],
        ],
    },
};

const formatMetric = (value, language) => value === null || value === undefined
    ? "-"
    : value.toLocaleString(language === "zh" ? "zh-CN" : "en-US");

const formatDuration = (milliseconds) => {
    if (!milliseconds || milliseconds < 0) {
        return "0s";
    }
    const totalSeconds = Math.floor(milliseconds / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    if (hours > 0) {
        return `${hours}h ${minutes}m ${seconds}s`;
    }
    if (minutes > 0) {
        return `${minutes}m ${seconds}s`;
    }
    return `${seconds}s`;
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

const getProjectStats = (project, copy) => {
    if (project.projectType === "PYTHON") {
        return [
            [copy.metrics.language, "Python"],
            [copy.metrics.linesOfCode, project.loc],
            [copy.metrics.pythonFiles, project.nof],
            [copy.metrics.functionsMethods, project.nom],
            [copy.metrics.classes, project.noc],
        ];
    }

    return [
        [copy.metrics.language, "Java"],
        [copy.metrics.linesOfCode, project.loc],
        [copy.javaClasses || copy.metrics.classes, project.noc],
        [copy.metrics.methods, project.nom],
        [copy.metrics.fields, project.nof],
    ];
};


const WelcomePage = () => {
    const navigate = useNavigate();
    const {language, setLanguage} = useLanguage();
    const copy = WELCOME_COPY[language];

    const [loadingConnect, setLoadingConnect] = useState(false);
    const [loadingAnalyse, setLoadingAnalyse] = useState(false);
    const [projectOptions, setProjectOptions] = useState(null);
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
            [language === "zh" ? "descriptionCn" : "description"]:
                language === "zh"
                    ? settingsProject.descriptionCn || ""
                    : settingsProject.description || "",
        });
    }, [language, settingsForm, settingsProject]);

    const testConnect = () => {
        setLoadingConnect(true);
        API.testConnect().then(res => {
            setLoadingConnect(false);
        }).catch(err => {
            console.log(err);
            testConnect();
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
                getProjects();
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

    const hasPendingSummary = projectOptions?.some(project => !project.summaryFlag);

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
            navigate('/debloating')
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
                            navigate('/debloating');
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
            message.success(copy.resummaryCompleted);
            setSettingsProject(null);
            getProjects();
        }).catch(error => {
            message.error(copy.resummaryFailed);
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
            message.error(language === "en"
                ? error?.response?.data?.message || copy.projectDeleteFailed
                : copy.projectDeleteFailed);
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
            const updatedProject = await API.updateProject(settingsProject.id, {
                ...values,
                description: language === "en"
                    ? values.description
                    : settingsProject.description,
                descriptionCn: language === "zh"
                    ? values.descriptionCn
                    : settingsProject.descriptionCn,
            });
            setProjectOptions((projects) => projects.map((project) =>
                project.id === updatedProject.id ? updatedProject : project
            ));
            setSettingsProject(null);
            settingsForm.resetFields();
            message.success(copy.projectUpdated);
        } catch (error) {
            if (error?.errorFields) return;
            message.error(language === "en"
                ? error?.response?.data?.message || copy.projectUpdateFailed
                : copy.projectUpdateFailed);
            console.log(error);
        } finally {
            setSettingsSaving(false);
        }
    };

    const progressForProject = (project) => {
        return summaryProgressByRepo?.[project.id] || summaryProgressByRepo?.[String(project.id)];
    }

    const progressPercent = (progress) => {
        if (!progress) return 0;
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
        if (project.summaryFlag) {
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
                <div>{progress.message || copy.summaryProgressFallback}</div>
                <div>{copy.elapsed}: {formatDuration(progress.elapsedMs)}</div>
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
                        <div className={styles.summaryProgressTitle}>{progress.message || copy.summaryProgressFallback}</div>
                        <div className={styles.summaryProgressMuted}>
                            {copy.elapsed} {formatDuration(progress.elapsedMs)} · {copy.step} {progress.currentStep}/{progress.totalSteps}
                        </div>
                    </div>
                    <Tag color={statusColor(progress.status)}>{progress.status}</Tag>
                </div>
                <Progress
                    percent={Math.round(progressPercent(progress))}
                    size="small"
                    status={progress.status === "failed" ? "exception" : "active"}
                    className={styles.summaryProgressBar}
                />
                <div className={styles.summaryStepList}>
                    {(progress.steps || []).map(step => (
                        <div key={step.id} className={styles.summaryStepItem}>
                            <Tag color={statusColor(step.status)} className={styles.summaryStepTag}>
                                {step.status}
                            </Tag>
                            <div className={styles.summaryStepBody}>
                                <div className={styles.summaryStepLabel}>{step.label}</div>
                                <div className={styles.summaryProgressMuted}>
                                    {step.detail || copy.waiting}
                                    {step.elapsedMs ? ` · ${formatDuration(step.elapsedMs)}` : ""}
                                    {typeof step.percent === "number" ? ` · ${Math.round(step.percent)}%` : ""}
                                </div>
                            </div>
                        </div>
                    ))}
                </div>
            </div>
        );
    }

    return (
        <div className={styles.welcomePage}>
            {modalContextHolder}

            <div className={styles.languageSwitcher}>
                <TranslationOutlined className={styles.languageIcon}/>
                <Segmented
                    aria-label={copy.languageSelector}
                    value={language}
                    onChange={setLanguage}
                    options={[
                        {label: "中文", value: "zh"},
                        {label: "English", value: "en"},
                    ]}
                />
            </div>

            {/* LOGO */}
            <h1 className={styles.logo1}>{copy.headline}</h1>
            <h1 className={styles.logo2}>{copy.productLine}</h1>


            <Spin tip={copy.analyzing} size={"large"} spinning={loadingAnalyse}>
                <Spin tip={copy.connecting} size={"large"} spinning={loadingConnect}>
                    <div className={styles.projectGrid}>
                        {projectOptions ? (
                            projectOptions.map((project) => (
                                <Card
                                    key={project.id}
                                    title={
                                        <div className={styles.cardTitle}>
                                            <Tooltip title={copy.settings}>
                                                <Button
                                                    type="text"
                                                    icon={<SettingOutlined/>}
                                                    aria-label={copy.settings}
                                                    className={styles.titleButtonLeft}
                                                    onClick={() => openSettings(project)}
                                                />
                                            </Tooltip>
                                            <span className={styles.cardTitleText}>{project.projectName}</span>
                                        </div>
                                    }
                                    hoverable
                                    className={styles.projectCard}
                                >
                                    <Descriptions column={1} size="small" layout="horizontal">
                                        {getProjectStats(project, copy).map(([label, value]) => (
                                            <Descriptions.Item key={label} label={label}>
                                                {typeof value === "number" ? formatMetric(value, language) : value}
                                            </Descriptions.Item>
                                        ))}
                                        <Descriptions.Item label={copy.gitRepository}>
                                            {isBrowsableGitLink(getGitLink(project)) ? (
                                                <a href={getGitLink(project)} target="_blank"
                                                   rel="noopener noreferrer" className={styles.gitLink}>
                                                    <GithubOutlined/>
                                                    <span>{getGitName(project, copy)}</span>
                                                </a>
                                            ) : getGitLink(project) ? (
                                                <span className={styles.gitLink}>
                                                    <GithubOutlined/>
                                                    <span>{getGitName(project, copy)}</span>
                                                </span>
                                            ) : copy.notLinked}
                                        </Descriptions.Item>
                                    </Descriptions>
                                    <p className={styles.projectDescription}>
                                        {getLocalizedField(project, "description", language)}
                                    </p>
                                    <Tooltip title={!project.summaryFlag ? summaryTooltip(project) : ""}>
                                        <div className={styles.buttonContainer}>
                                            <Button
                                                type="primary"
                                                onClick={() => {
                                                    handleConfirmButton(project.id);
                                                }}
                                                disabled={!project.summaryFlag}
                                            >
                                                {copy.open}
                                            </Button>
                                            {!project.summaryFlag ? (
                                                <Popover
                                                    title={copy.summaryProgressTitle}
                                                    content={renderSummaryProgress(project)}
                                                    trigger="click"
                                                    placement="top"
                                                >
                                                    <Button icon={<DownOutlined/>}>
                                                        {copy.summaryDetails}
                                                    </Button>
                                                </Popover>
                                            ) : null}
                                        </div>
                                    </Tooltip>
                                </Card>
                            ))) : null
                        }
                    </div>
                    <div className={styles.uploadModalWrapper}>
                        <FolderUploadModal reloadGetProjectsInfo={getProjects}/>
                        <GitDownModal reloadGetProjectsInfo={getProjects}/>
                    </div>
                </Spin>
            </Spin>

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
                    {language === "zh" ? (
                        <Form.Item name="descriptionCn" label={copy.descriptionCn}>
                            <Input.TextArea autoSize={{minRows: 3, maxRows: 6}}/>
                        </Form.Item>
                    ) : (
                        <Form.Item name="description" label={copy.descriptionEn}>
                            <Input.TextArea autoSize={{minRows: 3, maxRows: 6}}/>
                        </Form.Item>
                    )}
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

            {/* 介绍文本 */}
            <div className={styles.introText}>
                <p>{copy.intro}</p>
                {copy.steps.map(([title, description], index) => (
                    <p key={title}>
                        {index + 1}. <strong>{title}</strong>{language === "en" ? " " : null}{description}
                    </p>
                ))}
            </div>
        </div>


    );
}

export default WelcomePage;
