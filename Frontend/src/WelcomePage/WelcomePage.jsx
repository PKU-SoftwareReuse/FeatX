// WelcomePage.jsx
import React, {useEffect, useState} from "react";
import {useNavigate} from 'react-router-dom';
import styles from './WelcomePage.module.css';
import {Button, Card, Descriptions, Popconfirm, Select, Spin, Tooltip,} from "antd";
import {FolderOpenOutlined, GithubOutlined, SyncOutlined} from '@ant-design/icons';
import API from "../API";
import FolderUploadModal from "./FolderUploadModal/FolderUploadModal";
import classNames from "classnames";


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
                                            <span>{project.projectName}</span>
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
                                                    className={styles.titleButton}
                                                    disabled={!project.summaryFlag}
                                                />
                                            </Popconfirm>
                                        </div>
                                    }
                                    hoverable
                                    className={styles.projectCard}
                                >
                                    <Descriptions column={1} size="small" layout="horizontal">
                                        <Descriptions.Item
                                            label="Line of Codes">{project.loc}</Descriptions.Item>
                                        <Descriptions.Item
                                            label="Number of Classes">{project.noc}</Descriptions.Item>
                                        <Descriptions.Item
                                            label="Number of Methods">{project.nom}</Descriptions.Item>
                                        <Descriptions.Item
                                            label="Number of Fields">{project.nof}</Descriptions.Item>
                                        <Descriptions.Item label="GitHub">
                                            <a href={project.githubLink} target="_blank"
                                               rel="noopener noreferrer">
                                                <GithubOutlined style={{marginRight: 8}}/>
                                                {project.githubName}
                                            </a>
                                        </Descriptions.Item>
                                    </Descriptions>
                                    <p style={{textAlign: "center"}}>{project.description}</p>
                                    <Tooltip
                                        title={!project.summaryFlag ? "Repo summary in progress..." : ""}
                                    >
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
                                        </div>
                                    </Tooltip>
                                </Card>
                            ))) : null
                        }
                    </div>
                    <div className={styles.uploadModalWrapper}>
                        <FolderUploadModal reloadGetProjectsInfo={getProjects}/>
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


