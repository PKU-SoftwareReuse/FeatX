// GraphOption.jsx

const GraphOption = {
    options: {
        interaction: {
            hover: true
        },
        physics: {          // 物理引擎配置（关键）
            enabled: true,    // 启用物理模拟
            stabilization: {  // 稳定化参数
                enabled: true,
                iterations: 100 // 预计算迭代次数
            },
            solver: 'forceAtlas2Based', // 高性能布局算法
            forceAtlas2Based: {
                gravitationalConstant: -80, // 节点排斥力（负值越大越分散）
                centralGravity: 0.002,       // 中心引力
                springLength: 250,          // 边理想长度
                springConstant: 0.05        // 边弹性系数
            }
        },
        layout: {
            hierarchical: {
                enabled: false,
                direction: 'UD',    // 方向：LR/UD
                nodeSpacing: 300,   // 节点间距
                treeSpacing: 200    // 子树间距
            }
        },
        nodes: {
            font: {
                size: 20,          // 🔥 设置字体大小（默认14）
                face: 'Arial',
            },
            shape: 'box',        // 可选，box比默认的ellipse更适合显示多行
        },
        edges: {
            arrows: {
                to: {
                    enabled: true,
                    scaleFactor: 0.5
                }
            }
        }
    },

    typeColors: {
        Default: {background: '#e6f7ff', border: '#1890ff', highlight: '#C3D7FB'},
        // Interface: {background: '#F2FCF3', border: '#208A3C', highlight: '#C9E6CA'},
        // Field: {background: '#FEF3E6', border: '#FF9800', highlight: '#F5CBA5'},
        Modify: {background: '#FFEBEE', border: '#D32F2F', highlight: '#F5B7B1'},
        // StaticInitializer: {background: '#E7E3FF', border: '#5E35B1', highlight: '#C2B7F0'},
    },
}

export default GraphOption
