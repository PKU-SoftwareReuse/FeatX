import {fireEvent, render, screen} from "@testing-library/react";

jest.mock("../graph/featureGraph/FeatureGraph", () => () => null);
jest.mock("./CodeDiffComponent/CodeDiffComponent", () => () => null);
jest.mock("./MarkdownRenderComponent/MarkdownRenderComponent", () => () => null);
jest.mock("./FocusGraphStageModal/FocusGraphStageModal", () => () => null);

import {
    activeRunStorageKey,
    candidateNodeTypeForDraft,
    FeatureListItem,
    resolveActiveRunIdForRepository,
    shouldShowCandidateDiff,
    supportsReasoningGraphStages,
} from "./DebloatingPage";

describe("FeatureListItem", () => {
    test("supports reasoning stages only for add and edit operations", () => {
        expect(supportsReasoningGraphStages("edit")).toBe(true);
        expect(supportsReasoningGraphStages("add")).toBe(true);
        expect(supportsReasoningGraphStages("delete")).toBe(false);
        expect(supportsReasoningGraphStages("select")).toBe(false);
    });

    test("opens the candidate editor for blue, modified, and staged graph nodes", () => {
        expect(shouldShowCandidateDiff(true, "edit", {type: "Modify"})).toBe(true);
        expect(shouldShowCandidateDiff(true, "edit", {type: "Staged"})).toBe(true);
        expect(shouldShowCandidateDiff(true, "edit", {type: "Default"})).toBe(true);
        expect(shouldShowCandidateDiff(false, "edit", {type: "Modify"})).toBe(false);
    });

    test("derives candidate node color from diff and full-stage state", () => {
        const candidate = {originalContent: "class Demo {}\n"};

        expect(candidateNodeTypeForDraft(candidate, "class Demo {}\n")).toBe("Default");
        expect(candidateNodeTypeForDraft(candidate, "class Demo { int value; }\n")).toBe("Modify");
        expect(candidateNodeTypeForDraft(candidate, "class Demo { int value; }\n", true)).toBe("Staged");
    });

    test("keeps the complete editor visible in the minimal compressed layout", () => {
        const feature = {
            featureId: 7,
            featureDescription: "Original description",
        };
        const editedText = "A complete feature description that must remain editable after the Diff Panel opens.";
        const setEditedText = jest.fn();

        render(
            <FeatureListItem
                item={feature}
                moduleId={1}
                itemNumber="1.1"
                description={feature.featureDescription}
                selectedType="edit"
                selectedFeatureItem={feature}
                submitEnabled
                selectedModel="test-model"
                editedText={editedText}
                setEditedText={setEditedText}
                copy={{pendingChanges: "Pending", submit: "Submit"}}
                onClickItem={jest.fn()}
                submitEdit={jest.fn()}
                handleDelete={jest.fn()}
                handleEdit={jest.fn()}
                compressed
                minimal
            />
        );

        const editor = screen.getByRole("textbox");
        expect(editor.value).toBe(editedText);
        expect(screen.getByRole("button", {name: "Submit"})).not.toBeNull();
        expect(editor.closest("[data-feature-id]").classList.contains("featureItemEditing")).toBe(true);
        fireEvent.change(editor, {target: {value: "Recovered request draft"}});
        expect(setEditedText).toHaveBeenCalledWith("Recovered request draft");
    });
});

describe("Agent run recovery", () => {
    beforeEach(() => {
        sessionStorage.clear();
    });

    test("keeps active Agent runs isolated by repository", async () => {
        sessionStorage.setItem(activeRunStorageKey(12), "run-for-12");
        sessionStorage.setItem(activeRunStorageKey(13), "run-for-13");
        const validateRun = jest.fn().mockResolvedValue({status: "RUNNING"});

        await expect(resolveActiveRunIdForRepository(13, validateRun)).resolves.toBe("run-for-13");
        expect(validateRun).toHaveBeenCalledWith("run-for-13");
        expect(sessionStorage.getItem(activeRunStorageKey(12))).toBe("run-for-12");
    });

    test("drops a stale run instead of blocking the new project's initial selection", async () => {
        sessionStorage.setItem(activeRunStorageKey(13), "discarded-run");
        const validateRun = jest.fn().mockRejectedValue({response: {status: 409}});

        await expect(resolveActiveRunIdForRepository(13, validateRun)).resolves.toBeNull();
        expect(sessionStorage.getItem(activeRunStorageKey(13))).toBeNull();
    });

    test("migrates a valid legacy run only after validating it against the selected project", async () => {
        sessionStorage.setItem("featx.activeRunId", "legacy-run");
        const validateRun = jest.fn().mockResolvedValue({status: "COMPLETED"});

        await expect(resolveActiveRunIdForRepository(12, validateRun)).resolves.toBe("legacy-run");
        expect(sessionStorage.getItem(activeRunStorageKey(12))).toBe("legacy-run");
        expect(sessionStorage.getItem("featx.activeRunId")).toBeNull();
    });
});
