import React from "react";
import {fireEvent, render, screen, waitFor} from "@testing-library/react";
import {Modal} from "antd";
import {LanguageProvider} from "../i18n/LanguageContext";
import API from "../API";
import WelcomePage from "./WelcomePage";

const mockNavigate = jest.fn();

jest.mock("react-router-dom", () => ({
    useNavigate: () => mockNavigate,
}));

jest.mock("../API", () => ({
    testConnect: jest.fn(),
    getProjectsInfo: jest.fn(),
    getSummaryProgressAll: jest.fn(),
    getCurrentProject: jest.fn(),
    postProjectPath: jest.fn(),
    discardFeatureChanges: jest.fn(),
}));

jest.mock("./FolderUploadModal/FolderUploadModal", () => () => null);
jest.mock("./GitDownModal/GitDownModal", () => () => null);

const projects = [
    {
        id: 12,
        projectName: "Current project",
        projectType: "JAVA",
        summaryFlag: true,
        description: "Current",
        loc: 10,
        noc: 1,
        nom: 2,
        nof: 3,
    },
    {
        id: 13,
        projectName: "Target project",
        projectType: "JAVA",
        summaryFlag: true,
        description: "Target",
        loc: 20,
        noc: 2,
        nom: 3,
        nof: 4,
    },
];

beforeAll(() => {
    Object.defineProperty(window, "matchMedia", {
        writable: true,
        value: (query) => ({
            matches: false,
            media: query,
            onchange: null,
            addListener: jest.fn(),
            removeListener: jest.fn(),
            addEventListener: jest.fn(),
            removeEventListener: jest.fn(),
            dispatchEvent: jest.fn(),
        }),
    });
});

beforeEach(() => {
    window.localStorage.setItem("featx-language", "en");
    API.testConnect.mockResolvedValue({});
    API.getProjectsInfo.mockResolvedValue(projects);
    API.getCurrentProject.mockResolvedValue({repoId: 12});
    API.discardFeatureChanges.mockResolvedValue({});
    mockNavigate.mockReset();
});

afterEach(() => {
    Modal.destroyAll();
    jest.clearAllMocks();
});

test("asks before discarding an active Agent run and switches only after confirmation", async () => {
    API.postProjectPath
        .mockRejectedValueOnce({response: {status: 409}})
        .mockResolvedValueOnce({});

    render(
        <LanguageProvider>
            <WelcomePage/>
        </LanguageProvider>
    );

    const openButtons = await screen.findAllByRole("button", {name: "Open"});
    fireEvent.click(openButtons[1]);

    expect((await screen.findAllByText("Discard current changes and switch projects?")).length).toBeGreaterThan(0);
    expect(API.discardFeatureChanges).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", {name: "Discard and open"}));

    await waitFor(() => expect(API.discardFeatureChanges).toHaveBeenCalledTimes(1));
    await waitFor(() => expect(API.postProjectPath).toHaveBeenCalledTimes(2));
    expect(API.postProjectPath).toHaveBeenLastCalledWith(13);
    expect(mockNavigate).toHaveBeenCalledWith("/debloating");
});

test("keeps the current operation when the switch confirmation is cancelled", async () => {
    API.postProjectPath.mockRejectedValueOnce({response: {status: 409}});

    render(
        <LanguageProvider>
            <WelcomePage/>
        </LanguageProvider>
    );

    const openButtons = await screen.findAllByRole("button", {name: "Open"});
    fireEvent.click(openButtons[1]);
    await screen.findAllByText("Discard current changes and switch projects?");
    fireEvent.click(screen.getByRole("button", {name: "No"}));

    await waitFor(() => expect(
        screen.queryAllByText("Discard current changes and switch projects?")
    ).toHaveLength(0));
    expect(API.discardFeatureChanges).not.toHaveBeenCalled();
    expect(API.postProjectPath).toHaveBeenCalledTimes(1);
    expect(mockNavigate).not.toHaveBeenCalled();
});
