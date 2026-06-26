package com.esp32ide.utils

import android.content.Context
import android.graphics.Color
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import org.json.JSONObject

data class EditorTheme(
    val name: String,
    val background: Int,
    val foreground: Int,
    val lineNumber: Int,
    val lineNumberActive: Int,
    val gutterBackground: Int,
    val selection: Int,
    val cursor: Int,
    val currentLineHighlight: Int,
    val keyword: Int,
    val type: Int,
    val string: Int,
    val char: Int,
    val number: Int,
    val comment: Int,
    val function: Int,
    val operator: Int,
    val preprocessor: Int,
    val identifier: Int,
    val constant: Int,
    val annotation: Int,
    val error: Int
)

object ThemeManager {

    private const val THEMES_DIR = "editor-themes"
    private val cache = mutableMapOf<String, EditorTheme>()

    fun listThemeNames(context: Context): List<String> {
        val files = context.assets.list(THEMES_DIR) ?: emptyArray()
        return files.filter { it.endsWith(".json") }
            .map { it.removeSuffix(".json") }
            .sorted()
    }

    fun load(context: Context, themeName: String): EditorTheme {
        cache[themeName]?.let { return it }

        val json = try {
            context.assets.open("$THEMES_DIR/$themeName.json")
                .bufferedReader()
                .use { it.readText() }
        } catch (e: Exception) {
            context.assets.open("$THEMES_DIR/Darcula.json")
                .bufferedReader()
                .use { it.readText() }
        }

        val root = JSONObject(json)
        val editor = root.getJSONObject("editor")
        val syntax = root.getJSONObject("syntax")

        fun c(obj: JSONObject, key: String): Int = Color.parseColor(obj.getString(key))

        val theme = EditorTheme(
            name = root.optString("name", themeName),
            background = c(editor, "background"),
            foreground = c(editor, "foreground"),
            lineNumber = c(editor, "lineNumber"),
            lineNumberActive = c(editor, "lineNumberActive"),
            gutterBackground = c(editor, "gutterBackground"),
            selection = c(editor, "selection"),
            cursor = c(editor, "cursor"),
            currentLineHighlight = c(editor, "currentLineHighlight"),
            keyword = c(syntax, "keyword"),
            type = c(syntax, "type"),
            string = c(syntax, "string"),
            char = c(syntax, "char"),
            number = c(syntax, "number"),
            comment = c(syntax, "comment"),
            function = c(syntax, "function"),
            operator = c(syntax, "operator"),
            preprocessor = c(syntax, "preprocessor"),
            identifier = c(syntax, "identifier"),
            constant = c(syntax, "constant"),
            annotation = c(syntax, "annotation"),
            error = c(syntax, "error")
        )

        cache[themeName] = theme
        return theme
    }

    fun applyTheme(editor: CodeEditor, theme: EditorTheme) {
        val scheme = object : EditorColorScheme() {
            override fun applyDefault() {
                super.applyDefault()
                setColor(WHOLE_BACKGROUND, theme.background)
                setColor(TEXT_NORMAL, theme.foreground)
                setColor(LINE_NUMBER, theme.lineNumber)
                setColor(LINE_NUMBER_CURRENT, theme.lineNumberActive)
                setColor(LINE_NUMBER_BACKGROUND, theme.gutterBackground)
                setColor(LINE_DIVIDER, theme.lineNumber)
                setColor(SELECTED_TEXT_BACKGROUND, theme.selection)
                setColor(SELECTION_INSERT, theme.cursor)
                setColor(SELECTION_HANDLE, theme.cursor)
                setColor(CURRENT_LINE, theme.currentLineHighlight)
                
                setColor(KEYWORD, theme.keyword)
                setColor(ATTRIBUTE_NAME, theme.type)
                setColor(LITERAL, theme.string)
                setColor(COMMENT, theme.comment)
                setColor(FUNCTION_NAME, theme.function)
                setColor(OPERATOR, theme.operator)
                setColor(ATTRIBUTE_VALUE, theme.preprocessor)
                setColor(ANNOTATION, theme.annotation)
                setColor(IDENTIFIER_NAME, theme.identifier)
            }
        }
        editor.colorScheme = scheme
    }
}
