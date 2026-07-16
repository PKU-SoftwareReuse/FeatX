// DebloatingPage.jsx

import styles from './DebloatingPage.module.css';
import React, {useEffect, useRef, useState} from "react";
import {Splitter, Collapse, Modal, Input, Card, List, Spin, Button, Tooltip, Popconfirm, message, Progress} from "antd";
import {
    DeleteTwoTone,
    EditTwoTone,
    PlusSquareTwoTone,
    ExclamationCircleOutlined,
    SwapOutlined,
    ApartmentOutlined
} from '@ant-design/icons';
import classNames from "classnames";

import API from "../API";
import FeatureGraph from "../graph/featureGraph/FeatureGraph";
import CodeDiffComponent from "./CodeDiffComponent/CodeDiffComponent";
import MarkdownRendererComponent from "./MarkdownRenderComponent/MarkdownRenderComponent";
import FocusGraphStageModal from "./FocusGraphStageModal/FocusGraphStageModal";

const {Panel} = Collapse;
const {TextArea} = Input;

const DebloatingPage = () => {

    const [loadingFeatureList, setLoadingFeatureList] = useState(false);
    const [loadingFeatureGraph, setLoadingFeatureGraph] = useState(false);
    const [loadingCode, setLoadingCode] = useState(false);
    const [currentProject, setCurrentProject] = useState(null);
    const [operationProgress, setOperationProgress] = useState(null);
    const [focusGraphStages, setFocusGraphStages] = useState([]);
    const [focusGraphModalOpen, setFocusGraphModalOpen] = useState(false);
    const progressTimerRef = useRef(null);
    const expectedProgressOperationRef = useRef(null);
    const graphRefreshTimerRef = useRef(null);
    const chatCloseTimerRef = useRef(null);
    const eventSourceRef = useRef(null);
    const isPythonProject = currentProject?.projectType === "PYTHON";

    const clearPostAgentTimers = () => {
        if (eventSourceRef.current) {
            eventSourceRef.current.close();
            eventSourceRef.current = null;
        }
        if (graphRefreshTimerRef.current) {
            clearTimeout(graphRefreshTimerRef.current);
            graphRefreshTimerRef.current = null;
        }
        if (chatCloseTimerRef.current) {
            clearTimeout(chatCloseTimerRef.current);
            chatCloseTimerRef.current = null;
        }
    }

    const clearFocusGraphStages = () => {
        setFocusGraphStages([]);
        setFocusGraphModalOpen(false);
    }

    useEffect(() => {
        const fetchData = async () => {
            const project = await API.getCurrentProject().catch(() => null)
            setCurrentProject(project)
            const res = await getFeatureData()
            if (res.length > 0 && res[0].featureList.length > 0) {
                setActiveKey(res[0].moduleId)
                handleSelect(res[0].featureList[0])
            }
        }
        fetchData()
    }, [])


    const [featureData, setFeatureData] = useState([]);
    const [selectedFeatureItem, setSelectedFeatureItem] = useState(null);
    const [selectedType, setSelectedType] = useState(null);

    const getFeatureData = async () => {
        setLoadingFeatureList(true)
        try {
            const res = await API.getFeatures()
            setFeatureData(res)
            setLoadingFeatureList(false)
            return res
        } catch (error) {
            console.error(error)
            setLoadingFeatureList(false)
            setLoadingFeatureGraph(false)
            setLoadingCode(false)
            message.error(errorMessage(error, "Failed to fetch feature summary."))
            return []
        }
    }

    const [graphData, setGraphData] = useState({nodes: [], edges: []});

    const getFeatureGraphData = (featureId, selectedType) => {
        setLoadingFeatureGraph(true);
        setLoadingCode(false);
        setCodeDiff('');
        if (selectedType === 'delete') {
            API.getMinGraphData(featureId).then((data) => {
                setGraphData(data)
                setLoadingFeatureGraph(false)
            }).catch((error) => {
                console.error('Error fetching FeatureGraph data:', error)
                setLoadingFeatureGraph(false)
                message.error(errorMessage(error, "Failed to fetch CodeMap."))
            })
        } else if (selectedType === 'edit' || selectedType === 'select') {
            API.getMaxGraphData(featureId).then((data) => {
                setGraphData(data)
                setLoadingFeatureGraph(false)
            }).catch((error) => {
                console.error('Error fetching FeatureGraph data:', error)
                setLoadingFeatureGraph(false)
                message.error(errorMessage(error, "Failed to fetch CodeMap."))
            })
        } else if (selectedType === 'add') {
            setGraphData(null)
            setLoadingFeatureGraph(false);
        } else if (selectedType === 'new') {
            API.getNewGraphData().then((data) => {
                setGraphData(data)
                setLoadingFeatureGraph(false)
            }).catch((error) => {
                console.error('Error fetching FeatureGraph data:', error)
                setLoadingFeatureGraph(false)
                message.error(errorMessage(error, "Failed to fetch generated graph."))
            })
        } else {
            setGraphData({nodes: [], edges: []})
            setLoadingFeatureGraph(false)
        }

    }

    const featureGraphRef = useRef();

    const [codeDiff, setCodeDiff] = useState('');

    const getCodeDiff = (classNodeId) => {
        if (!classNodeId) {
            setCodeDiff('')
            setLoadingCode(false)
            return
        }
        setLoadingCode(true)

        // 检查当前选中的feature是否是新生成的feature
        const isNewFeature = selectedFeatureItem && selectedFeatureItem.isNewGenerated;

        if (isNewFeature) {
            // 如果是新生成的feature，总是使用newFeatureCode接口
            console.log('Fetching new feature code for classId:', classNodeId);
            API.getNewFeatureCode(classNodeId).then((data) => {
                console.log('Received new feature code:', data);
                setCodeDiff(data);
                setLoadingCode(false);
            }).catch(error => {
                console.error('Error fetching new feature code:', error);
                setLoadingCode(false);
            });
        } else if (selectedType == "delete") {
            API.getDeleteDiffByClass(classNodeId).then((data) => {
                setCodeDiff(data)
                setLoadingCode(false);
            }).catch(error => {
                console.error('Error fetching code diff:', error)
                setLoadingCode(false);
                message.error(errorMessage(error, "Failed to fetch code diff."))
                // getCodeDiff(classNodeId)
            })
        } else if (selectedType == "edit" || selectedType === "add") {
            if (!confirmEnabled) {
                // 修改前显示Context
                API.getContextByClass(classNodeId).then((data) => {
                    setCodeDiff(data)
                    setLoadingCode(false);
                }).catch(error => {
                    console.error('Error fetching code diff:', error)
                    setLoadingCode(false);
                    message.error(errorMessage(error, "Failed to fetch code context."))
                    // getCodeDiff(classNodeId)
                })
            } else {
                // 修改后显示 CodeDiff
                API.getNewDiffByClass(classNodeId).then((data) => {
                    setCodeDiff(data)
                    setLoadingCode(false);
                }).catch(error => {
                    console.error('Error fetching code diff:', error)
                    setLoadingCode(false);
                    message.error(errorMessage(error, "Failed to fetch generated code diff."))
                    // getCodeDiff(classNodeId)
                })
            }

        } else if (selectedType == "select") {
            // 修改前显示Context
            API.getContextByClass(classNodeId).then((data) => {
                setCodeDiff(data)
                setLoadingCode(false);
            }).catch(error => {
                console.error('Error fetching code diff:', error)
                setLoadingCode(false);
                message.error(errorMessage(error, "Failed to fetch code context."))
                // getCodeDiff(classNodeId)
            })
        } else {
            alert("Maybe Not Todo")
        }

    }

    const [modal, contextHolder] = Modal.useModal();

    const onClickItem = (item) => {
        if (selectedFeatureItem == null || item.featureId != selectedFeatureItem.featureId) {
            handleSelect(item)
        }
    }

    const handleSelect = (item) => {
        if (selectedType == "add" || selectedType == "edit") {
            modal.confirm({
                title: 'You are trying to do another thing',
                icon: <ExclamationCircleOutlined/>,
                content: 'Do you want to give up your modification?',
                okText: 'Yes, give up!',
                cancelText: 'Cancel',
                onOk: async () => {
                    await getFeatureData()
                    goSelect(item)
                }
            });
        } else {
            goSelect(item)
        }
    }

    const goSelect = (item) => {
        clearPostAgentTimers();
        clearFocusGraphStages();
        setSubmitEnabled(false);
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);

        setSelectedFeatureItem(item)
        setSelectedType("select")
        getFeatureGraphData(item.featureId, "select")
        setConfirmEnabled(false)
    }

    const handleDelete = (item) => {
        if (selectedType == "add" || selectedType == "edit") {
            modal.confirm({
                title: 'You are trying to do another thing',
                icon: <ExclamationCircleOutlined/>,
                content: 'Do you want to give up your modification?',
                okText: 'Yes, give up!',
                cancelText: 'Cancel',
                onOk: async () => {
                    await getFeatureData()
                    goDelete(item)
                }
            });
        } else {
            goDelete(item)
        }
    }

    const goDelete = (item) => {
        clearPostAgentTimers();
        clearFocusGraphStages();
        setSubmitEnabled(false);
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);

        setSelectedFeatureItem(item)
        setSelectedType("delete")
        getFeatureGraphData(item.featureId, "delete")
        if (isPythonProject) {
            preparePythonDelete(item)
        } else {
            setConfirmEnabled(true)
        }
    }

    const handleEdit = (item) => {
        if (selectedType == "add" || (selectedType == "edit" && selectedFeatureItem != null && selectedFeatureItem.featureId != item.featureId)) {
            modal.confirm({
                title: 'You are trying to do another thing',
                icon: <ExclamationCircleOutlined/>,
                content: 'Do you want to give up your modification?',
                okText: 'Yes, give up!',
                cancelText: 'Cancel',
                onOk: async () => {
                    await getFeatureData()
                    goEdit(item)
                }
            });
        } else {
            goEdit(item)
        }
    }

    const goEdit = (item) => {
        clearPostAgentTimers();
        clearFocusGraphStages();
        setSubmitEnabled(true);
        setConfirmEnabled(false);
        setModeTrans(false);
        setChatMode(false);


        setSelectedFeatureItem(item)
        setSelectedType("edit")
        setEditedText(item.featureDescription)
        getFeatureGraphData(item.featureId, "edit")
    }

    const [editedText, setEditedText] = useState('')
    const [activeKey, setActiveKey] = useState(null);

    const changeActiveKey = (newActiveKey) => {
        if (selectedType == "edit" || selectedType == "add") {
            modal.confirm({
                title: 'You are trying to do another thing',
                icon: <ExclamationCircleOutlined/>,
                content: 'Do you want to give up your modification?',
                okText: 'Yes, give up!',
                cancelText: 'Cancel',
                onOk: async () => {
                    clearPostAgentTimers()
                    await getFeatureData()
                    setSelectedType(null)
                    setSelectedFeatureItem(null)
                    setActiveKey(newActiveKey)
                }
            });
        } else {
            setActiveKey(newActiveKey)
            setSelectedFeatureItem(null)
        }
    }

    // 新增元素模板，根据需求修改
    const createNewFeature = (moduleId) => {
        let timestamp = Date.now();
        return {
            featureId: `new-${timestamp}`, // 唯一id
            featureDescription: `new SubFeature Item ${timestamp}`,
            isNew: true,
            moduleId: moduleId, // 添加moduleId
        }
    }

    const handleAdd = (module) => {
        if (selectedType == "edit" || (selectedType == "add" && activeKey != module.moduleId)) {
            modal.confirm({
                title: 'You are trying to do another thing',
                icon: <ExclamationCircleOutlined/>,
                content: 'Do you want to give up your modification?',
                okText: 'Yes, give up!',
                cancelText: 'Cancel',
                onOk: async () => {
                    await getFeatureData()
                    goAdd(module)
                }
            });
        } else if (selectedType == "add" && activeKey == module.moduleId) {
            return
        } else {
            goAdd(module)
        }
    }

    // Add 按钮事件
    const goAdd = (module) => {
        clearPostAgentTimers();
        clearFocusGraphStages();
        setSubmitEnabled(true)
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);


        setActiveKey(module.moduleId)
        setSelectedType("add")

        let newFeatureItem = createNewFeature(module.moduleId);

        setFeatureData((prev) =>
            prev.map((m) => {
                if (m.moduleId === module.moduleId) {
                    return {
                        ...m,
                        featureList: [newFeatureItem, ...m.featureList],
                    };
                }
                return m;
            })
        );
        setEditedText(newFeatureItem.featureDescription)
        setSelectedFeatureItem(newFeatureItem)
        getFeatureGraphData(null, "add")

    };

    const [chatMode, setChatMode] = useState(false)
    const [chatContent, setChatContent] = useState("")
    const containerRef = useRef(null);
    const [modeTrans, setModeTrans] = useState(false)

    useEffect(() => {
        if (containerRef.current) {
            requestAnimationFrame(() => {
                containerRef.current.scrollTop = containerRef.current.scrollHeight;
            });
        }
    }, [chatContent]);


    const handleChat = (eventSource) => {
        clearPostAgentTimers()
        eventSourceRef.current = eventSource
        setChatContent("");
        setChatMode(true)
        setModeTrans(true)
        setOperationProgress({
            operation: selectedType === 'add' ? "python-add" : "python-modify",
            stage: "agent-stream",
            message: "Streaming Python Agent code generation.",
            currentStep: 8,
            totalSteps: 8,
            running: true,
            failed: false
        })
        eventSource.onmessage = (event) => {
            const decoded = decodeURIComponent(escape(atob(event.data)));
            setChatContent(prev => prev + decoded);
        };
        eventSource.onerror = () => {

            eventSource.close(); // 关闭连接
            eventSourceRef.current = null
            stopProgressPolling()
            setLoadingFeatureList(false);
            setConfirmEnabled(true)
            setOperationProgress({
                operation: selectedType === 'add' ? "python-add" : "python-modify",
                stage: "complete",
                message: "Code generation finished. Review the diff and confirm or drop it.",
                currentStep: 8,
                totalSteps: 8,
                running: false,
                failed: false
            })

            clearPostAgentTimers()
            graphRefreshTimerRef.current = setTimeout(() => {
                getFeatureGraphData(0, "new")
                graphRefreshTimerRef.current = null
            }, 1500); // 延迟 3000 毫秒（3秒）

            chatCloseTimerRef.current = setTimeout(() => {
                setChatMode(false);
                chatCloseTimerRef.current = null
            }, 3000); // 延迟 3000 毫秒（3秒）
        };
        return () => {
            eventSource.close();
            eventSourceRef.current = null;
        };
    }

    const [confirmEnabled, setConfirmEnabled] = useState(false)
    const [submitEnabled, setSubmitEnabled] = useState(false)

    const errorMessage = (error, fallback) => {
        const data = error?.response?.data;
        if (typeof data === "string" && data.trim()) {
            return data;
        }
        if (data?.message) {
            return data.message;
        }
        if (error?.message) {
            return error.message;
        }
        return fallback;
    }

    const progressPercent = () => {
        if (!operationProgress) {
            return 0;
        }
        const total = operationProgress.totalSteps || 1;
        const current = operationProgress.currentStep || 0;
        return Math.max(0, Math.min(100, Math.round((current / total) * 100)));
    }

    const isFeatureModifyProgress = () => {
        return operationProgress?.operation === "python-modify"
            || operationProgress?.operation === "python-add"
            || operationProgress?.operation === "python-delete"
            || operationProgress?.stage === "submit"
            || operationProgress?.stage === "agent-stream";
    }

    const featureListLoadingOverlay = () => {
        if (!isFeatureModifyProgress()) {
            return (
                <div className={styles.featureListLoadingMessage}>
                    Fetching repo's feature summary.
                </div>
            );
        }
        return (
            <div className={styles.featureListProgress}>
                <div className={styles.featureListProgressMessage}>
                    {operationProgress?.message || "Preparing modification."}
                </div>
                <Progress
                    percent={progressPercent()}
                    size="small"
                    status={operationProgress?.failed ? "exception" : "active"}
                    showInfo
                />
            </div>
        );
    }

    const stopProgressPolling = () => {
        if (progressTimerRef.current) {
            clearInterval(progressTimerRef.current);
            progressTimerRef.current = null;
        }
        expectedProgressOperationRef.current = null;
    }

    const fetchOperationProgress = (expectedOperation = expectedProgressOperationRef.current) => {
        API.getLlmProgress()
            .then((progress) => {
                if (expectedOperation && progress.operation !== expectedOperation) {
                    return;
                }
                setOperationProgress(progress)
                if (!progress.running || progress.failed) {
                    stopProgressPolling()
                }
            })
            .catch((error) => {
                console.error('Error fetching operation progress:', error)
            })
    }

    const startProgressPolling = (expectedOperation) => {
        stopProgressPolling()
        expectedProgressOperationRef.current = expectedOperation || null;
        fetchOperationProgress(expectedOperation)
        progressTimerRef.current = setInterval(() => fetchOperationProgress(expectedOperation), 1000)
    }

    const loadFocusGraphStages = () => {
        if (!isPythonProject || (selectedType !== "edit" && selectedType !== "add")) {
            clearFocusGraphStages()
            return Promise.resolve([])
        }
        return API.getFocusGraphStages()
            .then((stages) => {
                const normalized = Array.isArray(stages) ? stages : []
                setFocusGraphStages(normalized)
                return normalized
            })
            .catch((error) => {
                console.error('Error fetching FocusGraph stages:', error)
                setFocusGraphStages([])
                return []
            })
    }

    useEffect(() => {
        return () => {
            stopProgressPolling()
            clearPostAgentTimers()
        }
    }, [])

    const preparePythonDelete = (item) => {
        setLoadingFeatureList(true)
        setSubmitEnabled(false)
        setConfirmEnabled(false)
        setOperationProgress({
            operation: "python-delete",
            stage: "submit",
            message: "Submitting feature deletion.",
            currentStep: 0,
            totalSteps: 8,
            running: true,
            failed: false
        })
        startProgressPolling("python-delete")
        API.deleteFeature({
            featureId: item.featureId,
            featureDescription: item.featureDescription
        })
            .then(() => {
                stopProgressPolling()
                setLoadingFeatureList(false)
                setConfirmEnabled(true)
                setChatMode(false)
                setModeTrans(false)
                API.getLlmProgress()
                    .then((progress) => {
                        setOperationProgress(progress)
                    })
                    .catch(() => {
                        setOperationProgress({
                            operation: "python-delete",
                            stage: "complete",
                            message: "Deterministic Python delete diff is ready. Review the affected files and confirm or drop it.",
                            currentStep: 4,
                            totalSteps: 4,
                            running: false,
                            failed: false
                        })
                    })
                getFeatureGraphData(0, "new")
            })
            .catch((error) => {
                console.error('Error Delete Feature:', error)
                stopProgressPolling()
                setLoadingFeatureList(false)
                const msg = errorMessage(error, "Failed to prepare deletion context.")
                setOperationProgress({
                    operation: "python-delete",
                    stage: "failed",
                    message: msg,
                    currentStep: operationProgress?.currentStep || 0,
                    totalSteps: operationProgress?.totalSteps || 8,
                    running: false,
                    failed: true,
                    error: msg
                })
                message.error(msg)
            })
    }

    const submitEdit = (item) => {
        setLoadingFeatureList(true)
        setSubmitEnabled(false)
        clearFocusGraphStages()
        const expectedOperation = selectedType === 'add' ? "python-add" : "python-modify";
        setOperationProgress({
            operation: expectedOperation,
            stage: "submit",
            message: selectedType === 'add' ? "Submitting feature addition." : "Submitting feature modification.",
            currentStep: 0,
            totalSteps: 8,
            running: true,
            failed: false
        })
        startProgressPolling(expectedOperation)

        if (selectedType == 'edit') {
            // console.log(editedText);
            API.modifyFeature(editedText)
                .then(async (data) => {
                    await loadFocusGraphStages()
                    handleChat(API.getLlmResponse())
                })
                .catch((error) => {
                    console.error('Error Modify Feature:', error)
                    stopProgressPolling()
                    setLoadingFeatureList(false)
                    setSubmitEnabled(true)
                    const msg = errorMessage(error, "Failed to prepare modification context.")
                    setOperationProgress({
                        operation: selectedType === 'add' ? "python-add" : "python-modify",
                        stage: "failed",
                        message: msg,
                        currentStep: operationProgress?.currentStep || 0,
                        totalSteps: operationProgress?.totalSteps || 8,
                        running: false,
                        failed: true,
                        error: msg
                    })
                    message.error(msg)
                });
        } else if (selectedType == 'add') {
            // 构建请求参数，包含moduleId
            const requestData = {
                featureDescription: editedText,
                moduleId: item.moduleId || selectedFeatureItem.moduleId
            };

            API.addFeature(requestData)
                .then(async (data) => {
                    await loadFocusGraphStages()
                    handleChat(API.getLlmResponse())
                })
                .catch((error) => {
                    console.error('Error Add Feature:', error)
                    stopProgressPolling()
                    setLoadingFeatureList(false)
                    setSubmitEnabled(true)
                    const msg = errorMessage(error, "Failed to add feature.")
                    setOperationProgress({
                        operation: "python-add",
                        stage: "failed",
                        message: msg,
                        currentStep: operationProgress?.currentStep || 0,
                        totalSteps: operationProgress?.totalSteps || 8,
                        running: false,
                        failed: true,
                        error: msg
                    })
                    message.error(msg)
                });
        }
    }

    const [loadingConfirm, setLoadingConfirm] = useState(false);

    const confirmDiff = () => {
        if (selectedType == 'delete') {
            setLoadingConfirm(true);
            API.confirmDelete()
                .then(async (data) => {
                    // 等待刷新数据
                    const res = await getFeatureData();
                    console.log(activeKey);
                    // 在新的数据里找到当前 activeKey 对应的模块
                    const activeModule = res.find(m => m.moduleId == activeKey);
                    console.log(activeModule);
                    if (activeModule && activeModule.featureList.length > 0) {
                        handleSelect(activeModule.featureList[0]);
                    } else {
                        setActiveKey(res[0].moduleId)
                        handleSelect(res[0].featureList[0])
                    }
                    setLoadingConfirm(false);
                })
                .catch((error) => {
                    console.error('Error Confirm Delete Feature:', error)
                    setLoadingConfirm(false);
                    message.error(errorMessage(error, "Failed to apply delete diff."))
                });
        } else if (selectedType == 'edit') {
            setLoadingConfirm(true);
            API.confirmModify()
                .then(async (data) => {
                    // 等待刷新数据
                    const res = await getFeatureData();
                    // console.log(activeKey);
                    // 在 res 中找到指定 featureId 的对象
                    const featureId = selectedFeatureItem.featureId;
                    let foundFeature = null;

                    for (const module of res) {
                        const match = module.featureList.find(f => f.featureId === featureId);
                        if (match) {
                            foundFeature = match;
                            break; // 找到就退出
                        }
                    }

                    if (foundFeature) {
                        // 传递整个对象给 handleSelect
                        goSelect(foundFeature);
                    } else {
                        console.warn("未找到 featureId:", featureId);
                    }
                    setLoadingConfirm(false);
                })
                .catch((error) => {
                    console.error('Error Confirm Modify Feature:', error)
                    setLoadingConfirm(false);
                    message.error(errorMessage(error, "Failed to apply modify diff."))
                });
        }else if (selectedType == 'add') {
            setLoadingConfirm(true);
            API.confirmAdd()
                .then(async (data) => {
                    // 等待刷新数据
                    const res = await getFeatureData();
                    // console.log(activeKey);
                    // 在 res 中找到指定 featureId 的对象
                    const featureId = data;
                    let foundFeature = null;

                    for (const module of res) {
                        const match = module.featureList.find(f => f.featureId === featureId);
                        if (match) {
                            foundFeature = match;
                            break; // 找到就退出
                        }
                    }

                    if (foundFeature) {
                        // 传递整个对象给 handleSelect
                        goSelect(foundFeature);
                    } else {
                        console.warn("未找到 featureId:", featureId);
                    }
                    setLoadingConfirm(false);
                })
                .catch((error) => {
                    console.error('Error Confirm Add Feature:', error)
                    setLoadingConfirm(false);
                    message.error(errorMessage(error, "Failed to apply add diff."))
                });
        }else{
            alert('This is a [Fake] success message')
        }

    }

    const hasFocusGraphStages = isPythonProject
        && (selectedType === "edit" || selectedType === "add")
        && Array.isArray(focusGraphStages)
        && focusGraphStages.length > 0;


    return (
        <>
            {contextHolder}
            <Spin spinning={loadingConfirm} tip={"Applying the Code Diff..."} size={"large"}>
                <Splitter className={styles.background_area}>
                    {/*左侧可滚动功能列表 */}
                    <Splitter.Panel defaultSize="21%" resizable={false} className={styles.main_area}>
                        <Card
                            title={
                                <div className={styles.card_title}>
                                    Feature Panel
                                </div>
                            }
                            bordered={false}
                            bodyStyle={{paddingTop: 12, paddingBottom: 4}}
                        >
                            <div className={styles.featureListBody}>
                                <div className={classNames(styles.featureListContent, {
                                    [styles.featureListContentLoading]: loadingFeatureList,
                                })}>
                                    <div className={styles.scrollContainer}>
                                    <Collapse
                                        accordion
                                        bordered={false}
                                        className={styles.moduleCollapse}
                                        activeKey={activeKey}
                                        onChange={(activeKey) => changeActiveKey(activeKey)}
                                    >
                                        {featureData.map((module, moduleIndex) => (
                                            <Panel
                                                header={
                                                    <div className={styles.moduleTitle}>
                                                        <div>{`${moduleIndex + 1}. ${module.moduleDesc}`}</div>
                                                        <div className={styles.iconContainer}>
                                                            <Button
                                                                type="text"
                                                                icon={<PlusSquareTwoTone twoToneColor="#13B54D"/>}
                                                                onClick={(e) => {
                                                                    e.stopPropagation()
                                                                    handleAdd(module)
                                                                }}
                                                                className={classNames(styles.icon, {
                                                                    [styles.selectedIcon]: module.moduleId === activeKey && (selectedType === 'add'),
                                                                })}
                                                            />
                                                        </div>
                                                    </div>
                                                }
                                                key={module.moduleId}
                                            >
                                                <List
                                                    dataSource={module.featureList}
                                                    renderItem={(item, featureIndex) => {
                                                        // // 调试信息：打印当前feature的信息
                                                        // if (item.isNewGenerated) {
                                                        //     console.log('Rendering modified feature:', item);
                                                        // }
                                                        const displayIndex = item.isNew || item.isNewGenerated
                                                            ? 0
                                                            : module.featureList
                                                            .filter((f) => !f.isNew && !f.isNewGenerated)
                                                            .findIndex((f) => f === item) + 1;
                                                        return (
                                                            <List.Item
                                                                onClick={() => onClickItem(item)}
                                                                className={classNames({
                                                                    [styles.item]: true,
                                                                    [styles.selectedItem]: selectedFeatureItem != null && item.featureId === selectedFeatureItem.featureId,
                                                                })}
                                                            >
                                                                <div>
                                                                    {`${moduleIndex + 1}.${displayIndex} `}
                                                                    {(selectedType === 'edit' || selectedType === 'add') && selectedFeatureItem != null && item.featureId === selectedFeatureItem.featureId ? (
                                                                        <Tooltip
                                                                            title={!submitEnabled ? "You have made some modifications. Please confirm or drop it." : ""}
                                                                        >
                                                                            <div>
                                                                                <TextArea
                                                                                    value={editedText}
                                                                                    onChange={(e) => setEditedText(e.target.value)}
                                                                                    onClick={(e) => e.stopPropagation()}
                                                                                    size={'middle'}
                                                                                    autoSize={{minRows: 2, maxRows: 5}}
                                                                                    disabled={!submitEnabled}
                                                                                />

                                                                                <Button
                                                                                    type="primary"
                                                                                    onClick={() => {
                                                                                        submitEdit(item)
                                                                                    }}
                                                                                    disabled={!submitEnabled}
                                                                                >
                                                                                    Submit
                                                                                </Button>
                                                                            </div>
                                                                        </Tooltip>
                                                                    ) : (
                                                                        item.featureDescription
                                                                    )}
                                                                </div>
                                                                <div className={styles.iconContainer}>
                                                                    <Button
                                                                        type="text"
                                                                        icon={<DeleteTwoTone twoToneColor="#F74E52"/>}
                                                                        onClick={(e) => {
                                                                            e.stopPropagation();
                                                                            handleDelete(item)
                                                                        }}
                                                                        className={classNames(styles.icon, {
                                                                            [styles.selectedIcon]: selectedFeatureItem != null && item.featureId === selectedFeatureItem.featureId && selectedType === 'delete',
                                                                        })}
                                                                    />
                                                                    <Button
                                                                        type="text"
                                                                        icon={<EditTwoTone twoToneColor="#EFA92B"/>}
                                                                        onClick={(e) => {
                                                                            e.stopPropagation();
                                                                            handleEdit(item)
                                                                        }}
                                                                        className={classNames(styles.icon, {
                                                                            [styles.selectedIcon]: selectedFeatureItem != null && item.featureId === selectedFeatureItem.featureId && (selectedType === 'edit' || selectedType === 'add'),
                                                                        })}
                                                                    />
                                                                </div>
                                                            </List.Item>
                                                        )
                                                    }}
                                                />
                                            </Panel>
                                        ))}
                                    </Collapse>
                                    </div>
                                </div>
                                {loadingFeatureList && (
                                    <div className={styles.featureListOverlay}>
                                        <Spin spinning size="large"/>
                                        {featureListLoadingOverlay()}
                                    </div>
                                )}
                            </div>
                        </Card>
                    </Splitter.Panel>


                    {/*中间功能去臃肿详情*/}
                    <Splitter.Panel defaultSize="40%" min="30%" max="50%" className={styles.main_area}>
                        <div style={{height: '100%', overflow: 'hidden', display: 'flex', flexDirection: 'column'}}>
                            <Card
                                title={
                                    <div className={styles.card_title}>
                                        <div>
                                            {chatMode ? `Agent Panel` : `CodeMap Panel`}
                                        </div>
                                        <div className={styles.iconContainer}>
                                            <Button
                                                type="text"
                                                icon={<SwapOutlined/>}
                                                onClick={(e) => {
                                                    setChatMode(!chatMode)
                                                }}
                                                className={styles.icon}
                                                disabled={!modeTrans}
                                            />
                                        </div>
                                    </div>
                                }
                                bordered={false}
                                bodyStyle={{paddingTop: 12, paddingBottom: 4}}
                            >
                                <div style={{display: chatMode ? 'block' : 'none', height: '100%'}}>
                                    <div ref={containerRef} className={styles.scrollContainer}>
                                        <MarkdownRendererComponent content={chatContent}/>
                                    </div>
                                </div>
                                <div style={{display: chatMode ? 'none' : 'block', height: '100%'}}>
                                    <Spin spinning={loadingFeatureGraph} tip={"fetching codeMap."} size={"large"}>
                                        <FeatureGraph
                                            ref={featureGraphRef}
                                            graphData={graphData}
                                            onNodeClick={getCodeDiff}
                                        />
                                    </Spin>
                                </div>
                            </Card>
                        </div>
                    </Splitter.Panel>

                    {/*右侧待删减代码可视化展示*/}
                    <Splitter.Panel className={styles.main_area}>
                        <Card
                            title={
                                <div className={styles.card_title}>
                                    <div>Diff Panel</div>
                                    <div className={styles.diffPanelActions}>
                                        <Tooltip
                                            title={hasFocusGraphStages
                                                ? "Show Python FocusGraph initial, expanded, and reasoning graphs."
                                                : "FocusGraph stages are available after Python Add/Modify submit."}
                                        >
                                            <Button
                                                type="text"
                                                icon={<ApartmentOutlined/>}
                                                className={styles.icon}
                                                disabled={!hasFocusGraphStages}
                                                onClick={() => setFocusGraphModalOpen(true)}
                                            />
                                        </Tooltip>
                                    </div>
                                </div>
                            }
                            bordered={false}
                            bodyStyle={{paddingTop: 12, paddingBottom: 4}}
                        >

                            <Spin spinning={loadingCode} tip={"Fetching code details."} size="large">
                                <CodeDiffComponent
                                    diffText={codeDiff}
                                    isPlainCode={false}
                                />
                                <Tooltip
                                    title={!confirmEnabled ? "You haven't made any modifications. Please submit first." : ""}
                                >
                                    <Popconfirm title="Confirm All Code Diff"
                                                description="Have you read all the diff(the node marked with red)?"
                                                onConfirm={confirmDiff}
                                                okText="Yes"
                                                cancelText="No"
                                    >
                                        <div className={styles.centerButtonWrapper}>
                                            <Button
                                                type="primary"
                                                // onClick={confirmDiff}
                                                disabled={!confirmEnabled}
                                            >
                                                Confirm Apply the Diff
                                            </Button>
                                        </div>
                                    </Popconfirm>
                                </Tooltip>
                            </Spin>
                        </Card>
                    </Splitter.Panel>
                </Splitter>
            </Spin>
            <FocusGraphStageModal
                open={focusGraphModalOpen}
                onClose={() => setFocusGraphModalOpen(false)}
                stages={focusGraphStages}
            />
        </>

    )
}

export default DebloatingPage;
