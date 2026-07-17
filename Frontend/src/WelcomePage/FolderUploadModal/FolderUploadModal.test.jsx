import React from "react";
import {fireEvent, render, screen, waitFor} from "@testing-library/react";
import {LanguageProvider} from "../../i18n/LanguageContext";
import FolderUploadModal from "./FolderUploadModal";

jest.mock("../../API", () => ({
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
