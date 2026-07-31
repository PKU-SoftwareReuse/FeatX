import React, {createContext, useContext, useEffect, useMemo, useState} from "react";

const LANGUAGE_STORAGE_KEY = "featx-language";
const LanguageContext = createContext(null);

const getConfiguredLanguage = () => {
    const configuredLanguage = process.env.REACT_APP_DEFAULT_LANGUAGE?.trim().toUpperCase();
    if (configuredLanguage === "CN") return "zh";
    if (configuredLanguage === "EN") return "en";
    if (configuredLanguage) {
        console.warn(`Unsupported REACT_APP_DEFAULT_LANGUAGE=${configuredLanguage}; expected CN or EN.`);
    }
    return null;
};

const getInitialLanguage = () => {
    if (typeof window === "undefined") return getConfiguredLanguage() || "zh";

    try {
        const storedLanguage = window.localStorage.getItem(LANGUAGE_STORAGE_KEY);
        if (storedLanguage === "zh" || storedLanguage === "en") {
            return storedLanguage;
        }
    } catch (error) {
        console.warn("Unable to read the saved language preference:", error);
    }

    return getConfiguredLanguage()
        || (window.navigator.language?.toLowerCase().startsWith("zh") ? "zh" : "en");
};

export const LanguageProvider = ({children}) => {
    const [language, setLanguageState] = useState(getInitialLanguage);

    const setLanguage = (nextLanguage) => {
        if (nextLanguage !== "zh" && nextLanguage !== "en") return;
        setLanguageState(nextLanguage);
        try {
            window.localStorage.setItem(LANGUAGE_STORAGE_KEY, nextLanguage);
        } catch (error) {
            console.warn("Unable to save the language preference:", error);
        }
    };

    useEffect(() => {
        const isChinese = language === "zh";
        document.documentElement.lang = isChinese ? "zh-CN" : "en";
        document.querySelector('meta[name="description"]')?.setAttribute(
            "content",
            isChinese
                ? "FeatX：通过编辑功能特征来编辑软件"
                : "FeatX: Edit software by editing features"
        );
    }, [language]);

    const value = useMemo(() => ({
        language,
        isChinese: language === "zh",
        apiLanguage: language === "zh" ? "CN" : "EN",
        setLanguage,
    }), [language]);

    return (
        <LanguageContext.Provider value={value}>
            {children}
        </LanguageContext.Provider>
    );
};

export const useLanguage = () => {
    const context = useContext(LanguageContext);
    if (!context) {
        throw new Error("useLanguage must be used inside LanguageProvider");
    }
    return context;
};

const hasContent = (value) =>
    value !== null && value !== undefined && (typeof value !== "string" || value.trim() !== "");

export const getLocalizedField = (record, englishKey, language) => {
    if (!record) return "";

    const chineseValue = [
        record[`${englishKey}CN`],
        record[`${englishKey}Cn`],
        record[`${englishKey}cn`],
    ].find(hasContent);
    const englishValue = record[englishKey];
    const values = language === "zh"
        ? [chineseValue, englishValue]
        : [englishValue, chineseValue];

    return values.find(hasContent) ?? "";
};
