import React from "react";
import {fireEvent, render, screen, waitFor} from "@testing-library/react";
import {LanguageProvider} from "../../i18n/LanguageContext";
import FolderUploadModal from "./FolderUploadModal";
import API from "../../api";

jest.mock("../../api", () => ({
    uploadProject: jest.fn(),
}));

test("enables confirmation after selecting a Java project folder", async () => {
    render(
        <LanguageProvider>
            <FolderUploadModal reloadGetProjectsInfo={jest.fn()}/>
        </LanguageProvider>
    );

    fireEvent.click(screen.getByRole("button", {name: /upload new repo/i}));
    const input = document.querySelector("input[type=file]");
    const file = new File(["class Main {}"], "Main.java", {type: "text/plain"});
    Object.defineProperty(file, "webkitRelativePath", {
        value: "demo/src/main/java/Main.java",
    });
    fireEvent.change(input, {target: {files: [file]}});

    await waitFor(() => {
        const buttons = screen.getAllByRole("button");
        const okButton = buttons.find(button => button.classList.contains("ant-btn-primary"));
        expect(okButton).toBeDefined();
        expect(okButton.disabled).toBe(false);
    });
});

test("submits non-source project files with their original relative paths", async () => {
    API.uploadProject.mockResolvedValue({});
    render(
        <LanguageProvider>
            <FolderUploadModal reloadGetProjectsInfo={jest.fn()}/>
        </LanguageProvider>
    );

    fireEvent.click(screen.getByRole("button", {name: /upload new repo/i}));
    const input = document.querySelector("input[type=file]");
    const javaFile = new File(["class Main {}"], "Main.java", {type: "text/plain"});
    const pomFile = new File(["<project/>"] , "pom.xml", {type: "application/xml"});
    Object.defineProperty(javaFile, "webkitRelativePath", {
        value: "demo/src/main/java/Main.java",
    });
    Object.defineProperty(pomFile, "webkitRelativePath", {
        value: "demo/pom.xml",
    });
    fireEvent.change(input, {target: {files: [javaFile, pomFile]}});

    let okButton;
    await waitFor(() => {
        const buttons = screen.getAllByRole("button");
        okButton = buttons.find(button => button.classList.contains("ant-btn-primary"));
        expect(okButton?.disabled).toBe(false);
    });
    fireEvent.click(okButton);

    await waitFor(() => expect(API.uploadProject).toHaveBeenCalled());
    const formData = API.uploadProject.mock.calls[0][0];
    expect(formData.getAll("paths")).toEqual(["src/main/java/Main.java", "pom.xml"]);
    expect(formData.getAll("files")).toHaveLength(2);
});
