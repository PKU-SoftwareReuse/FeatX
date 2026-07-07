// WelcomePage.jsx
import React, {useEffect, useState} from "react";
import {useNavigate} from 'react-router-dom';
import styles from './WelcomePage.module.css';
import {Button, Card, Descriptions, message, Popconfirm, Spin, Tooltip,} from "antd";
import {DeleteOutlined, GithubOutlined, SyncOutlined} from '@ant-design/icons';
import API from "../API";
import FolderUploadModal from "./FolderUploadModal/FolderUploadModal";
import GitDownModal from "./GitDownModal/GitDownModal";


const formatMetric = (value) => value === null || value === undefined ? "-" : value.toLocaleString();

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
                                                    disabled={!project.summaryFlag || project.projectType === "PYTHON"}
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
                                    <Tooltip
                                        title={project.projectType === "PYTHON" ? "Python analysis pipeline will be added later." : (!project.summaryFlag ? "Repo summary in progress..." : "")}
                                    >
                                        <div className={styles.buttonContainer}>
                                            <Button
                                                type="primary"
                                                onClick={() => {
                                                    handleConfirmButton(project.id);
                                                }}
                                                disabled={!project.summaryFlag || project.projectType === "PYTHON"}
                                            >
                                                Open
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
