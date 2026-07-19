import {act, render, waitFor} from "@testing-library/react";

import FeatureGraph from "./FeatureGraph";

let mockClickHandler;
const mockDestroy = jest.fn();
const mockSelectNodes = jest.fn();
const mockUnselectAll = jest.fn();

jest.mock("vis-network/standalone/esm/vis-network", () => ({
    DataSet: class DataSet {
        constructor(items) {
            this.items = items;
        }
    },
    Network: class Network {
        destroy = mockDestroy;
        selectNodes = mockSelectNodes;
        unselectAll = mockUnselectAll;

        on(event, handler) {
            if (event === "click") mockClickHandler = handler;
        }
    },
}), {virtual: true});

const graphData = {
    nodes: [{id: "Demo", methods: ["run()"], type: "Default"}],
    edges: [],
};

beforeEach(() => {
    mockClickHandler = null;
    jest.clearAllMocks();
});

test("waits for an explicit node click and clears selection on a background click", async () => {
    const onNodeClick = jest.fn(() => true);
    const onBackgroundClick = jest.fn(() => true);
    render(
        <FeatureGraph
            graphData={graphData}
            onNodeClick={onNodeClick}
            onBackgroundClick={onBackgroundClick}
            nodeFontSize={17}
        />
    );

    await waitFor(() => expect(mockClickHandler).toEqual(expect.any(Function)));
    expect(onNodeClick).not.toHaveBeenCalled();
    expect(mockSelectNodes).not.toHaveBeenCalled();

    act(() => mockClickHandler({nodes: ["Demo"], edges: []}));
    expect(onNodeClick).toHaveBeenCalledWith("Demo");

    act(() => mockClickHandler({nodes: [], edges: []}));
    expect(onBackgroundClick).toHaveBeenCalledWith(expect.any(Function));
    expect(mockUnselectAll).toHaveBeenCalledTimes(1);
});
