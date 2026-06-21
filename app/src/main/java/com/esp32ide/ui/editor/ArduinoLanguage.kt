package com.esp32ide.ui.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.Styles
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.lang.util.BaseAnalyzeManager
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.regex.Pattern

/**
 * A robust Arduino/C++ language implementation for Sora Editor.
 * Provides real-time syntax highlighting for keywords, types, built-ins, etc.
 */
class ArduinoLanguage : Language {
    override fun getFormatter(): Formatter = EmptyLanguage.EmptyFormatter.INSTANCE
    override fun getSymbolPairs(): SymbolPairMatch = SymbolPairMatch().apply {
        putPair("(", SymbolPairMatch.SymbolPair("(", ")"))
        putPair("[", SymbolPairMatch.SymbolPair("[", "]"))
        putPair("{", SymbolPairMatch.SymbolPair("{", "}"))
        putPair("\"", SymbolPairMatch.SymbolPair("\"", "\""))
        putPair("'", SymbolPairMatch.SymbolPair("'", "'"))
    }
    
    override fun requireAutoComplete(
        content: ContentReference,
        position: CharPosition,
        publisher: CompletionPublisher,
        extraArguments: Bundle
    ) {}

    override fun getInterruptionLevel(): Int = Language.INTERRUPTION_LEVEL_STRONG
    override fun getNewlineHandlers(): Array<NewlineHandler> = arrayOf()
    override fun getAnalyzeManager(): AnalyzeManager = ArduinoAnalyzeManager()
    
    override fun getIndentAdvance(content: ContentReference, line: Int, column: Int): Int {
        return try {
            val lineText = content.getLine(line)
            if (lineText.trim().endsWith("{")) 4 else 0
        } catch (e: Exception) { 0 }
    }

    override fun destroy() {}
    override fun useTab(): Boolean = true
}

class ArduinoAnalyzeManager : BaseAnalyzeManager() {
    
    private val keywords = listOf(
        "if", "else", "for", "while", "do", "switch", "case", "default", "break",
        "continue", "return", "void", "class", "struct", "public", "private",
        "protected", "new", "delete", "this", "static", "const", "inline", "template",
        "sizeof", "typedef", "enum", "volatile", "extern", "goto"
    )

    private val types = listOf(
        "int", "float", "double", "char", "bool", "long", "short", "unsigned", "signed",
        "uint8_t", "uint16_t", "uint32_t", "int8_t", "int16_t", "int32_t", "String", "boolean", "size_t"
    )

    private val builtins = listOf(
        "setup", "loop", "pinMode", "digitalWrite", "digitalRead", "analogRead",
        "analogWrite", "delay", "delayMicroseconds", "millis", "micros", "Serial",
        "HIGH", "LOW", "INPUT", "OUTPUT", "INPUT_PULLUP", "LED_BUILTIN", "print", "println", "begin"
    )

    // Sora Editor color styles
    private val STYLE_NORMAL = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
    private val STYLE_KEYWORD = TextStyle.makeStyle(EditorColorScheme.KEYWORD, true)
    private val STYLE_TYPE = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_NAME)
    private val STYLE_BUILTIN = TextStyle.makeStyle(EditorColorScheme.FUNCTION_NAME)
    private val STYLE_STRING = TextStyle.makeStyle(EditorColorScheme.LITERAL)
    private val STYLE_COMMENT = TextStyle.makeStyle(EditorColorScheme.COMMENT)
    private val STYLE_NUMBER = TextStyle.makeStyle(EditorColorScheme.ANNOTATION)
    private val STYLE_PREPROCESSOR = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_VALUE)

    private val pattern = Pattern.compile(
        "(//.*|/\\*.*?\\*/)|" +                         // group 1: Comments
        "(\"(?:\\\\.|[^\"])*\"|'(?:\\\\.|[^'])*')|" +   // group 2: Strings
        "\\b(${keywords.joinToString("|")})\\b|" +     // group 3: Keywords
        "\\b(${types.joinToString("|")})\\b|" +        // group 4: Types
        "\\b(${builtins.joinToString("|")})\\b|" +     // group 5: Builtins
        "\\b(0x[0-9a-fA-F]+|[0-9]+)\\b|" +             // group 6: Numbers
        "(#[a-zA-Z_]+)",                               // group 7: Preprocessor
        Pattern.DOTALL
    )

    override fun insert(start: CharPosition, end: CharPosition, insertedContent: CharSequence) {
        rerun()
    }

    override fun delete(start: CharPosition, end: CharPosition, deletedContent: CharSequence) {
        rerun()
    }

    override fun rerun() {
        val receiver = getReceiver()
        val ref = getContentRef()
        if (receiver != null && ref != null) {
            val styles = Styles()
            val builder = io.github.rosemoe.sora.lang.styling.MappedSpans.Builder()
            
            for (i in 0 until ref.getLineCount()) {
                val lineText = ref.getLine(i)
                val matcher = pattern.matcher(lineText)
                var lastEnd = 0
                
                builder.addIfNeeded(i, 0, STYLE_NORMAL)
                
                while (matcher.find()) {
                    if (matcher.start() > lastEnd) {
                        builder.addIfNeeded(i, lastEnd, STYLE_NORMAL)
                    }
                    
                    val style = when {
                        matcher.group(1) != null -> STYLE_COMMENT
                        matcher.group(2) != null -> STYLE_STRING
                        matcher.group(3) != null -> STYLE_KEYWORD
                        matcher.group(4) != null -> STYLE_TYPE
                        matcher.group(5) != null -> STYLE_BUILTIN
                        matcher.group(6) != null -> STYLE_NUMBER
                        matcher.group(7) != null -> STYLE_PREPROCESSOR
                        else -> STYLE_NORMAL
                    }
                    
                    builder.addIfNeeded(i, matcher.start(), style)
                    lastEnd = matcher.end()
                }
                
                if (lastEnd < lineText.length) {
                    builder.addIfNeeded(i, lastEnd, STYLE_NORMAL)
                }
            }
            
            styles.spans = builder.build()
            receiver.setStyles(this, styles)
        }
    }

    override fun destroy() {
        super.destroy()
    }
}
