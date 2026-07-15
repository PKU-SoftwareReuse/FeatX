import "./App.css"
import React from 'react';
import {BrowserRouter as Router, Navigate, Route, Routes} from 'react-router-dom';
import WelcomePage from "./WelcomePage/WelcomePage";
import Template from "./template/Template";
import DebloatingPage from "./DebloatingPage/DebloatingPage";
import {ConfigProvider} from "antd";
import zhCN from "antd/locale/zh_CN";
import enUS from "antd/locale/en_US";
import {LanguageProvider, useLanguage} from "./i18n/LanguageContext";

const AppContent = () => {
    const {language} = useLanguage();

    return (
        <ConfigProvider locale={language === "zh" ? zhCN : enUS}>
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
};

function App() {
    return (
        <LanguageProvider>
            <AppContent/>
        </LanguageProvider>
    );
}

export default App;
