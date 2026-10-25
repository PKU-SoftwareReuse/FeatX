import './App.css';
import React, {useLayoutEffect, useRef} from 'react';
import {BrowserRouter as Router, Navigate, Route, Routes, useLocation} from 'react-router-dom';
import {ConfigProvider} from 'antd';
import {hasPageTransition} from './utils/usePageNavigation';
import zhCN from 'antd/locale/zh_CN';
import WelcomePage from './WelcomePage/WelcomePage';
import WorkingPage from './WorkingPage/WorkingPage';

function PageRoutes() {
    const location = useLocation();
    const scene = useRef(null);
    useLayoutEffect(() => {
        if (hasPageTransition() || window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
        const animation = scene.current?.animate?.([{opacity:0},{opacity:1}], {duration:180,easing:'ease-out'});
        return () => animation?.cancel();
    }, [location.pathname]);
    return <div ref={scene} className="page-scene"><Routes>
        <Route path="/" element={<Navigate to="/welcome"/>}/>
        <Route path="/welcome" element={<WelcomePage/>}/>
        <Route path="/working" element={<WorkingPage/>}/>
        <Route path="*" element={<Navigate to="/"/>}/>
    </Routes></div>;
}

export default function App() {
    return <ConfigProvider locale={zhCN}><Router useTransitions={false}><PageRoutes/></Router></ConfigProvider>;
}
