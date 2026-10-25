import './App.css';
import React from 'react';
import {BrowserRouter as Router, Navigate, Route, Routes} from 'react-router-dom';
import {ConfigProvider} from 'antd';
import zhCN from 'antd/locale/zh_CN';
import WelcomePage from './WelcomePage/WelcomePage';
import WorkingPage from './WorkingPage/WorkingPage';

export default function App() {
    return <ConfigProvider locale={zhCN}>
        <Router><Routes>
            <Route path="/" element={<Navigate to="/welcome"/>}/>
            <Route path="/welcome" element={<WelcomePage/>}/>
            <Route path="/working" element={<WorkingPage/>}/>
            <Route path="*" element={<Navigate to="/"/>}/>
        </Routes></Router>
    </ConfigProvider>;
}
