const CARD_WIDTH = 290;
const COLUMN_GAP = 60;
const ROW_GAP = 36;

// Compare complete layouts at their fitted zoom, rather than counting unscaled cards.
export function fileGrid(nodes, viewport, heights = new Map()) {
    if (!nodes.length) return {columns: 1, positions: new Map()};
    const width = Math.max(1, viewport.width || 900) * .9;
    const height = Math.max(1, viewport.height || 600) * .9;
    let best;
    for (let columns = 1; columns <= nodes.length; columns++) {
        const rowHeights = [];
        nodes.forEach((node, index) => {
            const row = Math.floor(index / columns);
            rowHeights[row] = Math.max(rowHeights[row] || 0, heights.get(String(node.id)) || 280);
        });
        const gridWidth = columns * CARD_WIDTH + (columns - 1) * COLUMN_GAP;
        const gridHeight = rowHeights.reduce((sum, value) => sum + value, 0) + (rowHeights.length - 1) * ROW_GAP;
        const zoom = Math.min(1, width / gridWidth, height / gridHeight);
        const fill = gridWidth * zoom / width;
        // Prefer readable cards. For equally readable arrangements, use the available width.
        if (!best || zoom > best.zoom + .0001 || (Math.abs(zoom - best.zoom) <= .0001 && fill > best.fill)) {
            best = {columns, rowHeights, zoom, fill};
        }
    }
    let y = 0;
    const rowOffsets = best.rowHeights.map(value => {const offset = y; y += value + ROW_GAP; return offset;});
    return {
        columns: best.columns,
        positions: new Map(nodes.map((node, index) => [String(node.id), {
            x: (index % best.columns) * (CARD_WIDTH + COLUMN_GAP),
            y: rowOffsets[Math.floor(index / best.columns)],
        }])),
    };
}
