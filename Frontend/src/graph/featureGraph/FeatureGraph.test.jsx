import {act, render, waitFor} from "@testing-library/react";

import FeatureGraph from "./FeatureGraph";

let mockClickHandler;
const mockDestroy = jest.fn();
const mockDataSetUpdate = jest.fn();
const mockGetPositions = jest.fn(() => ({Demo: {x: 120, y: 80}}));
const mockMoveNode = jest.fn();
const mockNetworkCreated = jest.fn();
const mockRedraw = jest.fn();
const mockSelectNodes = jest.fn();
const mockStopSimulation = jest.fn();
const mockUnselectAll = jest.fn();

jest.mock("vis-network/standalone/esm/vis-network", () => ({
    DataSet: class DataSet {
        constructor(items) {
            this.items = items;
        }

        update(items) {
            mockDataSetUpdate(items);
        }
    },
    Network: class Network {
        constructor(...args) {
            mockNetworkCreated(...args);
        }

        destroy = mockDestroy;
        getPositions = mockGetPositions;
        moveNode = mockMoveNode;
        redraw = mockRedraw;
        selectNodes = mockSelectNodes;
        stopSimulation = mockStopSimulation;
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
    mockGetPositions.mockReturnValue({Demo: {x: 120, y: 80}});
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
    expect(onNodeClick).toHaveBeenCalledWith("Demo", graphData.nodes[0]);

    act(() => mockClickHandler({nodes: [], edges: []}));
    expect(onBackgroundClick).toHaveBeenCalledWith(expect.any(Function));
    expect(mockUnselectAll).toHaveBeenCalledTimes(1);
});

test("updates node colors in place without rebuilding or moving the graph", async () => {
    const onNodeClick = jest.fn(() => true);
    const {rerender} = render(
        <FeatureGraph
            graphData={graphData}
            onNodeClick={onNodeClick}
            onBackgroundClick={jest.fn(() => true)}
            nodeFontSize={17}
        />
    );
    await waitFor(() => expect(mockNetworkCreated).toHaveBeenCalledTimes(1));

    const modifiedGraphData = {
        ...graphData,
        nodes: graphData.nodes.map((node) => ({...node, type: "Modify"})),
    };
    rerender(
        <FeatureGraph
            graphData={modifiedGraphData}
            onNodeClick={onNodeClick}
            onBackgroundClick={jest.fn(() => true)}
            nodeFontSize={17}
        />
    );

    await waitFor(() => expect(mockDataSetUpdate).toHaveBeenCalledTimes(1));
    expect(mockNetworkCreated).toHaveBeenCalledTimes(1);
    expect(mockDestroy).not.toHaveBeenCalled();
    expect(mockGetPositions).toHaveBeenCalledWith(["Demo"]);
    expect(mockMoveNode).toHaveBeenCalledWith("Demo", 120, 80);
    expect(mockStopSimulation).toHaveBeenCalledTimes(1);
    expect(mockRedraw).toHaveBeenCalledTimes(1);

    act(() => mockClickHandler({nodes: ["Demo"], edges: []}));
    expect(onNodeClick).toHaveBeenCalledWith("Demo", modifiedGraphData.nodes[0]);
});
