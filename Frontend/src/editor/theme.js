// One Monaco palette for source preview and inline diff; never switch back to the default theme.
export const EDITOR_THEME_NAME = 'featx-light';
export const EDITOR_TYPOGRAPHY = {
    fontFamily: "'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace",
    fontSize: 13,
    lineHeight: 22,
};

export function registerEditorTheme(instance) {
    instance.editor.defineTheme(EDITOR_THEME_NAME, {
        base:'vs', inherit:true,
        rules:[
            {token:'',foreground:'243247'},
            {token:'comment',foreground:'596579',fontStyle:'italic'},
            {token:'keyword',foreground:'244A9C'},
            {token:'string',foreground:'13634E'},
            {token:'number',foreground:'713B8D'},
            {token:'tag',foreground:'21568F'},
            {token:'attribute.name',foreground:'66417E'},
            {token:'attribute.value',foreground:'13634E'},
            {token:'delimiter',foreground:'44536A'},
        ],
        colors:{
            'editor.background':'#FBFDFF',
            'editor.foreground':'#243247',
            'editorGutter.background':'#FBFDFF',
            'editorLineNumber.foreground':'#67758A',
            'editorLineNumber.activeForeground':'#354F72',
            'editor.selectionBackground':'#DBEAFC',
            'editor.inactiveSelectionBackground':'#EAF1FA',
            'editor.lineHighlightBackground':'#F2F7FD',
            'editor.lineHighlightBorder':'#00000000',
            'editorCursor.foreground':'#538FE2',
            'editorIndentGuide.background1':'#EAF0F8',
            'editorIndentGuide.activeBackground1':'#CADBF0',
            'editorWidget.background':'#FFFFFF',
            'editorWidget.border':'#DDE8F5',
            'editor.findMatchBackground':'#D5E7FC',
            'editor.findMatchHighlightBackground':'#E9F1FC',
            'focusBorder':'#A7C9F3',
            'scrollbarSlider.background':'#BACBDD55',
            'scrollbarSlider.hoverBackground':'#A3BBD488',
            'scrollbarSlider.activeBackground':'#8BAACAAA',
            'diffEditor.insertedLineBackground':'#4DAE7726',
            'diffEditor.removedLineBackground':'#D4707022',
            'diffEditor.insertedTextBackground':'#2E9C5748',
            'diffEditor.removedTextBackground':'#CB535340',
            'diffEditorGutter.insertedLineBackground':'#2F9E6533',
            'diffEditorGutter.removedLineBackground':'#D45E5E30',
        },
    });
}

