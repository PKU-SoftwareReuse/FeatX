import {render, screen} from "@testing-library/react";

jest.mock("../graph/featureGraph/FeatureGraph", () => () => null);
jest.mock("./CodeDiffComponent/CodeDiffComponent", () => () => null);
jest.mock("./MarkdownRenderComponent/MarkdownRenderComponent", () => () => null);
jest.mock("./FocusGraphStageModal/FocusGraphStageModal", () => () => null);

import {FeatureListItem, supportsReasoningGraphStages} from "./DebloatingPage";

describe("FeatureListItem", () => {
    test("supports Java delete stages without enabling Python delete stages", () => {
        expect(supportsReasoningGraphStages(false, "edit")).toBe(true);
        expect(supportsReasoningGraphStages(false, "add")).toBe(true);
        expect(supportsReasoningGraphStages(false, "delete")).toBe(true);
        expect(supportsReasoningGraphStages(true, "delete")).toBe(false);
        expect(supportsReasoningGraphStages(false, "select")).toBe(false);
    });

    test("keeps the complete editor visible in the minimal compressed layout", () => {
        const feature = {
            featureId: 7,
            featureDescription: "Original description",
        };
        const editedText = "A complete feature description that must remain editable after the Diff Panel opens.";

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
                setEditedText={jest.fn()}
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
    });
});
