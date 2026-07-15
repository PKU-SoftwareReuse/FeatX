import "./App.css"
import React from 'react';
import {BrowserRouter as Router, Navigate, Route, Routes} from 'react-router-dom';
import WelcomePage from "./WelcomePage/WelcomePage";
import Template from "./template/Template";
import DebloatingPage from "./DebloatingPage/DebloatingPage";
import {ConfigProvider} from "antd";
import zhCN from "antd/locale/zh_CN";

function App() {
    return (
        <ConfigProvider locale={zhCN}>
            <Router>
                <Routes>
                    <Route path="/" element={<Navigate to="/welcome"/>}/>
                    <Route path="/welcome" element={<WelcomePage/>}/>
                    <Route path="/debloating" element={<DebloatingPage/>}/>
                    <Route path="/graph/*" element={<Navigate to="/graph/all"/>}/> {/*Hide*/}
                    <Route path="/template" element={<Template/>}/> {/*Hide*/}
                    <Route path="*" element={<Navigate to="/"/>}/>
                </Routes>
            </Router>
        </ConfigProvider>
    );
}

export default App;
