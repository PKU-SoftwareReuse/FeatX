// DebloatingPage.jsx

import styles from './DebloatingPage.module.css';
import React, {useEffect, useRef, useState} from "react";
import {Splitter, Collapse, Modal, Input, Card, List, Spin, Button, Tooltip, Popconfirm} from "antd";
import {
    DeleteTwoTone,
    EditTwoTone,
    PlusSquareTwoTone,
    ExclamationCircleOutlined,
    SwapOutlined
} from '@ant-design/icons';
import classNames from "classnames";

import API from "../API";
import FeatureGraph from "../graph/featureGraph/FeatureGraph";
import CodeDiffComponent from "./CodeDiffComponent/CodeDiffComponent";
import MarkdownRendererComponent from "./MarkdownRenderComponent/MarkdownRenderComponent";

const {Panel} = Collapse;
const {TextArea} = Input;

const DebloatingPage = () => {

    const [loadingFeatureList, setLoadingFeatureList] = useState(false);
    const [loadingFeatureGraph, setLoadingFeatureGraph] = useState(false);
    const [loadingCode, setLoadingCode] = useState(false);

    useEffect(() => {
        const fetchData = async () => {
            const res = await getFeatureData()
            console.log(res)
            setActiveKey(res[0].moduleId)
            handleSelect(res[0].featureList[0])
        }
        fetchData()
    }, [])


    const [featureData, setFeatureData] = useState([]);
    const [selectedFeatureItem, setSelectedFeatureItem] = useState(null);
    const [selectedType, setSelectedType] = useState(null);

    const getFeatureData = async () => {
        setLoadingFeatureList(true)
        setLoadingFeatureGraph(true);
        setLoadingCode(true);
        try {
            const res = await API.getFeatures()
            setFeatureData(res)
            setLoadingFeatureList(false)
            return res
        } catch (error) {
            console.error(error)
            return []
        }
    }

    const [graphData, setGraphData] = useState({nodes: [], edges: []});

    const getFeatureGraphData = (featureId, selectedType) => {
        setLoadingFeatureGraph(true);
        setLoadingCode(true);
        if (selectedType === 'delete') {
            API.getMinGraphData(featureId).then((data) => {
                setGraphData(data)
                setLoadingFeatureGraph(false)
            }).catch((error) => {
                console.error('Error fetching FeatureGraph data:', error)
            })
        } else if (selectedType === 'edit' || selectedType === 'select') {
            API.getMaxGraphData(featureId).then((data) => {
                setGraphData(data)
                setLoadingFeatureGraph(false)
            }).catch((error) => {
                console.error('Error fetching FeatureGraph data:', error)
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
            })
        }

    }

    const featureGraphRef = useRef();

    useEffect(() => {
        if (graphData && graphData.nodes?.length > 0) {
            featureGraphRef.current?.selectRandomNode();
        }
    }, [graphData]);

    const [codeDiff, setCodeDiff] = useState('');

    const getCodeDiff = (classNodeId) => {
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
                    // getCodeDiff(classNodeId)
                })
            } else {
                // 修改后显示 CodeDiff
                API.getNewDiffByClass(classNodeId).then((data) => {
                    setCodeDiff(data)
                    setLoadingCode(false);
                }).catch(error => {
                    console.error('Error fetching code diff:', error)
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
        setSubmitEnabled(false);
        setModeTrans(false);
        setChatMode(false);
        setConfirmEnabled(false);

        setSelectedFeatureItem(item)
        setSelectedType("delete")
        getFeatureGraphData(item.featureId, "delete")
        setConfirmEnabled(true)
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
        setChatContent("");
        setChatMode(true)
        setModeTrans(true)
        eventSource.onmessage = (event) => {
            const decoded = decodeURIComponent(escape(atob(event.data)));
            setChatContent(prev => prev + decoded);
        };
        eventSource.onerror = () => {

            eventSource.close(); // 关闭连接
            setLoadingFeatureList(false);
            setConfirmEnabled(true)

            setTimeout(() => {
                getFeatureGraphData(0, "new")
            }, 1500); // 延迟 3000 毫秒（3秒）

            setTimeout(() => {
                setChatMode(false);
            }, 3000); // 延迟 3000 毫秒（3秒）
        };
        return () => {
            eventSource.close();
        };
    }

    const [confirmEnabled, setConfirmEnabled] = useState(false)
    const [submitEnabled, setSubmitEnabled] = useState(false)

    const submitEdit = (item) => {
        setLoadingFeatureList(true)
        setSubmitEnabled(false)

        if (selectedType == 'edit') {
            // console.log(editedText);
            API.modifyFeature(editedText)
                .then((data) => {
                    handleChat(API.getLlmResponse())
                })
                .catch((error) => {
                    console.error('Error Add Feature:', error)
                });
        } else if (selectedType == 'add') {
            // 构建请求参数，包含moduleId
            const requestData = {
                featureDescription: editedText,
                moduleId: item.moduleId || selectedFeatureItem.moduleId
            };

            API.addFeature(requestData)
                .then((data) => {
                    handleChat(API.getLlmResponse())
                })
                .catch((error) => {
                    console.error('Error Add Feature:', error)
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
                    console.error('Error Confirm Delete Feature:', error)
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
                    console.error('Error Confirm Delete Feature:', error)
                });
        }else{
            alert('This is a [Fake] success message')
        }

    }


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
                                    Features
                                </div>
                            }
                            bordered={false}
                            bodyStyle={{paddingTop: 12, paddingBottom: 4}}
                        >
                            <Spin spinning={loadingFeatureList} tip={"Fetching repo's feature summary."} size="large">
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
                            </Spin>
                        </Card>
                    </Splitter.Panel>


                    {/*中间功能去臃肿详情*/}
                    <Splitter.Panel defaultSize="40%" min="30%" max="50%" className={styles.main_area}>
                        <div style={{height: '100%', overflow: 'hidden', display: 'flex', flexDirection: 'column'}}>
                            <Card
                                title={
                                    <div className={styles.card_title}>
                                        <div>
                                            {chatMode ? `Generated By LLM` : `CodeMap`}
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
                                    Code Diff
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
        </>

    )
}

export default DebloatingPage;