// WelcomePage.jsx
import React, {useEffect, useState} from "react";
import {useNavigate} from 'react-router-dom';
import styles from './WelcomePage.module.css';
import {Button, Card, Descriptions, message, Popconfirm, Popover, Progress, Spin, Tag, Tooltip,} from "antd";
import {DeleteOutlined, DownOutlined, GithubOutlined, SyncOutlined} from '@ant-design/icons';
import API from "../API";
import FolderUploadModal from "./FolderUploadModal/FolderUploadModal";
import GitDownModal from "./GitDownModal/GitDownModal";


const formatMetric = (value) => value === null || value === undefined ? "-" : value.toLocaleString();

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

const getProjectStats = (project) => {
    if (project.projectType === "PYTHON") {
        return [
            ["Language", "Python"],
            ["Line of Codes", project.loc],
            ["Python Files", project.nof],
            ["Functions / Methods", project.nom],
            ["Classes", project.noc],
        ];
    }

    return [
        ["Language", "Java"],
        ["Line of Codes", project.loc],
        ["Number of Classes", project.noc],
        ["Number of Methods", project.nom],
        ["Number of Fields", project.nof],
    ];
};


const WelcomePage = () => {
    const navigate = useNavigate();

    const [loadingConnect, setLoadingConnect] = useState(false);
    const [loadingAnalyse, setLoadingAnalyse] = useState(false);
    const [projectOptions, setProjectOptions] = useState(null);
    const [summaryProgressByRepo, setSummaryProgressByRepo] = useState({});


    useEffect(() => {
        testConnect();
        getProjects();
    }, [])

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
        API.postResummary(repoId).then(response => {
            getProjects()
        }).catch(error => {
            console.log(error)
        })
    }

    const handleDropButton = (repoId) => {
        setLoadingConnect(true);
        API.postDropRepo(repoId).then(() => {
            message.success("Project deleted.");
            getProjects();
        }).catch(error => {
            setLoadingConnect(false);
            message.error(error?.response?.data?.message || "Failed to delete project.");
            console.log(error)
        })
    }

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
                    <div>Repo summary is running.</div>
                    <div>Detailed progress will appear for newly captured runs.</div>
                </div>
            );
        }
        return (
            <div>
                <div>{progress.message || "Repo summary is running."}</div>
                <div>Elapsed: {formatDuration(progress.elapsedMs)}</div>
                <div>Step: {progress.currentStep}/{progress.totalSteps}</div>
            </div>
        );
    }

    const renderSummaryProgress = (project) => {
        const progress = progressForProject(project);
        if (!progress) {
            return (
                <div className={styles.summaryProgressPanel}>
                    <div className={styles.summaryProgressTitle}>Repo summary is running.</div>
                    <div className={styles.summaryProgressMuted}>
                        No structured progress has been captured for this run yet.
                    </div>
                </div>
            );
        }

        return (
            <div className={styles.summaryProgressPanel}>
                <div className={styles.summaryProgressHeader}>
                    <div>
                        <div className={styles.summaryProgressTitle}>{progress.message || "Repo summary is running."}</div>
                        <div className={styles.summaryProgressMuted}>
                            Elapsed {formatDuration(progress.elapsedMs)} · Step {progress.currentStep}/{progress.totalSteps}
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
                                    {step.detail || "Waiting."}
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

            {/* LOGO */}
            <h1 className={styles.logo1}>A Feature-Oriented Interface for LLM Programming</h1>
            <h1 className={styles.logo2}>FeatX: Editing Software by Editing Features</h1>


            <Spin tip={"We're analyzing and processing your selected repo."} size={"large"} spinning={loadingAnalyse}>
                <Spin tip={"Connecting...."} size={"large"} spinning={loadingConnect}>
                    <div className={styles.projectGrid}>
                        {projectOptions ? (
                            projectOptions.map((project, index) => (
                                <Card
                                    key={index}
                                    title={
                                        <div className={styles.cardTitle}>
                                            <Popconfirm
                                                title="Confirm Delete Project"
                                                description="Do you want to delete this project? This cannot be restored."
                                                onConfirm={() => {
                                                    handleDropButton(project.id);
                                                }}
                                                okText="Yes"
                                                cancelText="No"
                                            >
                                                <Button
                                                    type="text"
                                                    danger
                                                    icon={<DeleteOutlined/>}
                                                    className={styles.titleButtonLeft}
                                                />
                                            </Popconfirm>
                                            <span className={styles.cardTitleText}>{project.projectName}</span>
                                            <Popconfirm
                                                title="Confirm ReSummary"
                                                description="Do you want to get a brand new summary? It will take some minutes..."
                                                onConfirm={() => {
                                                    handleResummaryButton(project.id);
                                                }}
                                                okText="Yes"
                                                cancelText="No"
                                            >
                                                <Button
                                                    type="text"
                                                    icon={<SyncOutlined/>}
                                                    className={styles.titleButtonRight}
                                                    disabled={!project.summaryFlag}
                                                />
                                            </Popconfirm>
                                        </div>
                                    }
                                    hoverable
                                    className={styles.projectCard}
                                >
                                    <Descriptions column={1} size="small" layout="horizontal">
                                        {getProjectStats(project).map(([label, value]) => (
                                            <Descriptions.Item key={label} label={label}>
                                                {typeof value === "number" ? formatMetric(value) : value}
                                            </Descriptions.Item>
                                        ))}
                                        <Descriptions.Item label="GitHub">
                                            {project.githubLink ? (
                                                <a href={project.githubLink} target="_blank"
                                                   rel="noopener noreferrer">
                                                    <GithubOutlined style={{marginRight: 8}}/>
                                                    {project.githubName}
                                                </a>
                                            ) : project.githubName}
                                        </Descriptions.Item>
                                    </Descriptions>
                                    <p style={{textAlign: "center"}}>{project.description}</p>
                                    <Tooltip title={summaryTooltip(project)}>
                                        <div className={styles.buttonContainer}>
                                            <Button
                                                type="primary"
                                                onClick={() => {
                                                    handleConfirmButton(project.id);
                                                }}
                                                disabled={!project.summaryFlag}
                                            >
                                                Open
                                            </Button>
                                            {!project.summaryFlag ? (
                                                <Popover
                                                    title="Repo Summary Progress"
                                                    content={renderSummaryProgress(project)}
                                                    trigger="click"
                                                    placement="top"
                                                >
                                                    <Button icon={<DownOutlined/>}>
                                                        Details
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

            {/* 介绍文本 */}
            <div className={styles.introText}>
                <p>FeatX provides an integrated environment that supports editing software by editing features. Its workflow can be described as follows:</p>
                <p>1. <strong>Feature Summarization.</strong> Constructs a hierarchical feature list to organize repository code into features and epics.</p>
                <p>2. <strong>CodeMap Construction.</strong> Builds a comprehensive CodeMap that captures the full implementation context of each feature.</p>
                <p>3. <strong>CodeAgent Generation.</strong> A three-stage CodeAgent pipeline generates consistent file-level modifications following established software engineering workflows.</p>
                <p>4. <strong>Diff Confirmation.</strong> The user reviews the code modifications and applies the confirmed changes back to the repository.</p>
            </div>
        </div>


    );
}

export default WelcomePage;
