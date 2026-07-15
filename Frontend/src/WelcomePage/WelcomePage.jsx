// WelcomePage.jsx
import React, {useEffect, useState} from "react";
import {useNavigate} from 'react-router-dom';
import styles from './WelcomePage.module.css';
import {Button, Card, Descriptions, Form, Input, message, Modal, Popconfirm, Segmented, Spin, Tooltip,} from "antd";
import {DeleteOutlined, GithubOutlined, SettingOutlined, SyncOutlined, TranslationOutlined} from '@ant-design/icons';
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
        pythonUnavailable: "Python 分析功能将在后续版本中提供。",
        summaryInProgress: "正在生成功能特征摘要……",
        open: "打开",
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
        pythonUnavailable: "Python analysis pipeline will be added later.",
        summaryInProgress: "Repo summary in progress...",
        open: "Open",
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
    const [settingsForm] = Form.useForm();


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

    const getProjects = () => {
        setLoadingConnect(true);
        API.getProjectsInfo().then(data => {
            setProjectOptions(data);
            setLoadingConnect(false);
        }).catch(err => {
            console.log(err);
            getProjects();
        })
    }

    const handleConfirmButton = (repoId) => {
        setLoadingAnalyse(true);

        API.postProjectPath(repoId).then(response => {
            navigate('/debloating')
            setLoadingAnalyse(false);
        }).catch(error => {
            console.log(error)
            setLoadingAnalyse(false);
        })
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

    return (
        <div className={styles.welcomePage}>

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
                                    <Tooltip
                                        title={project.projectType === "PYTHON" ? copy.pythonUnavailable : (!project.summaryFlag ? copy.summaryInProgress : "")}
                                    >
                                        <div className={styles.buttonContainer}>
                                            <Button
                                                type="primary"
                                                onClick={() => {
                                                    handleConfirmButton(project.id);
                                                }}
                                                disabled={!project.summaryFlag || project.projectType === "PYTHON"}
                                            >
                                                {copy.open}
                                            </Button>
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
                            disabled={!settingsProject?.summaryFlag || settingsProject?.projectType === "PYTHON"}
                        >
                            <Button
                                icon={<SyncOutlined/>}
                                loading={settingsAction === "resummary"}
                                disabled={settingsSaving || !settingsProject?.summaryFlag || settingsProject?.projectType === "PYTHON"}
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
