import {render} from "@testing-library/react";

import GitDiffEditor from "./GitDiffEditor";

let diffEditorProps;

jest.mock("@monaco-editor/react", () => ({
    DiffEditor: (props) => {
        diffEditorProps = props;
        return <div data-testid="diff-editor"/>;
    },
    loader: {config: jest.fn()},
}));

jest.mock("monaco-editor", () => ({
    KeyCode: {KeyS: 49},
    KeyMod: {CtrlCmd: 2048},
}), {virtual: true});

const file = {
    language: "java",
    originalContent: "class Demo {}",
    modifiedContent: "class Demo { int value; }",
};

test.each([false, true])("always uses the unified inline diff layout (readOnly=%s)", (readOnly) => {
    const originalNode = {style: {}};
    const originalEditor = {
        getDomNode: jest.fn(() => originalNode),
        updateOptions: jest.fn(),
    };
    const modifiedEditor = {
        addCommand: jest.fn(),
        getValue: jest.fn(() => file.modifiedContent),
        onDidChangeModelContent: jest.fn(() => ({dispose: jest.fn()})),
        updateOptions: jest.fn(),
    };
    const {unmount} = render(<GitDiffEditor file={file} readOnly={readOnly}/>);

    diffEditorProps.onMount({
        getOriginalEditor: () => originalEditor,
        getModifiedEditor: () => modifiedEditor,
    });

    expect(diffEditorProps.options.renderSideBySide).toBe(false);
    expect(diffEditorProps.options.enableSplitViewResizing).toBe(false);
    expect(diffEditorProps.options.renderMarginRevertIcon).toBe(!readOnly);
    expect(diffEditorProps.options.readOnly).toBe(readOnly);
    expect(originalEditor.updateOptions).toHaveBeenCalledWith(expect.objectContaining({
        lineNumbers: "off",
    }));
    expect(modifiedEditor.updateOptions).toHaveBeenCalledWith(expect.objectContaining({
        lineNumbers: "on",
        readOnly,
    }));
    expect(originalNode.style.visibility).toBe("hidden");

    unmount();
});
