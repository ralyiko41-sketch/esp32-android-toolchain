package com.esp32ide.ui.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.analysis.AsyncIncrementalAnalyzeManager
import io.github.rosemoe.sora.lang.analysis.IncrementalAnalyzeManager.LineTokenizeResult
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.styling.CodeBlock
import io.github.rosemoe.sora.lang.styling.Span
import io.github.rosemoe.sora.lang.styling.TextStyle
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme
import java.util.regex.Pattern

/**
 * A robust Arduino/C++ language implementation for Sora Editor.
 * Provides real-time syntax highlighting for keywords, types, built-ins, etc.
 *
 * FIXES APPLIED:
 * 1. tokenizeLine() now calls line.toString() before passing to Pattern.matcher()
 *    — CharSequence is not always handled correctly by java.util.regex at runtime.
 * 2. Removed Pattern.DOTALL from the compiled pattern. DOTALL makes '.' match '\n',
 *    which causes the comment group (//.*) to consume the rest of the document when
 *    the tokenizer processes concatenated lines. Each line is tokenized independently,
 *    so DOTALL is wrong here.
 * 3. Block-comment state (/* ... */) is now tracked across lines via the Int state
 *    parameter: state 0 = normal, state 1 = inside block comment. The tokenizer
 *    checks and emits the correct style for unclosed block comments.
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

// State constants
private const val STATE_NORMAL = 0
private const val STATE_BLOCK_COMMENT = 1

class ArduinoAnalyzeManager : AsyncIncrementalAnalyzeManager<Int, Span>() {

    private val keywords = listOf(
        "if", "else", "for", "while", "do", "switch", "case", "default", "break",
        "continue", "return", "void", "class", "struct", "public", "private",
        "protected", "new", "delete", "this", "static", "const", "inline", "template",
        "sizeof", "typedef", "enum", "volatile", "extern", "goto"
    )

    private val types = listOf(
        "int", "float", "double", "char", "bool", "long", "short", "unsigned", "signed",
        "uint8_t", "uint16_t", "uint32_t", "int8_t", "int16_t", "int32_t",
        "String", "boolean", "size_t"
    )

    private val builtins = listOf(
        "setup", "loop", "pinMode", "digitalWrite", "digitalRead", "analogRead",
        "analogWrite", "delay", "delayMicroseconds", "millis", "micros", "Serial",
        "HIGH", "LOW", "INPUT", "OUTPUT", "INPUT_PULLUP", "LED_BUILTIN",
        "print", "println", "begin", "WiFi", "HTTPClient", "Wire",
        "Adafruit_BME280", "ESP32Servo", "Servo", "PubSubClient", "ArduinoOTA",
        "TaskHandle_t", "xTaskCreatePinnedToCore", "vTaskDelay"
    )

    private val STYLE_NORMAL      = TextStyle.makeStyle(EditorColorScheme.TEXT_NORMAL)
    private val STYLE_KEYWORD     = TextStyle.makeStyle(EditorColorScheme.KEYWORD, true)
    private val STYLE_TYPE        = TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_NAME)
    private val STYLE_BUILTIN     = TextStyle.makeStyle(EditorColorScheme.FUNCTION_NAME)
    private val STYLE_STRING      = TextStyle.makeStyle(EditorColorScheme.LITERAL)
    private val STYLE_COMMENT     = TextStyle.makeStyle(EditorColorScheme.COMMENT)
    private val STYLE_NUMBER      = TextStyle.makeStyle(EditorColorScheme.ANNOTATION)
    private val STYLE_PREPROCESSOR= TextStyle.makeStyle(EditorColorScheme.ATTRIBUTE_VALUE)

    // FIX 2: Removed Pattern.DOTALL — each line is tokenized independently.
    // The block-comment regex no longer uses .*? (which crossed lines with DOTALL).
    // Instead, block-comment continuation is handled via the state machine below.
    private val pattern = Pattern.compile(
        "(//[^\n]*)|"                                    + // group 1: line comment
                "(/\\*.*?\\*/)|"                                 + // group 2: inline block comment (single-line only)
                "(/\\*)|"                                        + // group 3: block comment OPEN (unclosed on this line)
                "(\"(?:\\\\.|[^\"\n])*\"|'(?:\\\\.|[^'\n])*')|" + // group 4: strings/chars
                "\\b(${keywords.joinToString("|")})\\b|"        + // group 5: keywords
                "\\b(${types.joinToString("|")})\\b|"           + // group 6: types
                "\\b(${builtins.joinToString("|")})\\b|"        + // group 7: builtins
                "\\b(0x[0-9a-fA-F]+|[0-9]+(?:\\.[0-9]*)?)\\b|" + // group 8: numbers
                "(#[a-zA-Z_]+)"                                    // group 9: preprocessor
    )

    // Pattern used when we are already inside a /* block comment */
    private val blockCommentEndPattern = Pattern.compile("\\*/")

    override fun getInitialState(): Int = STATE_NORMAL

    override fun stateEquals(state: Int?, another: Int?): Boolean = state == another

    override fun tokenizeLine(
        line: CharSequence,
        state: Int?,
        lineIndex: Int
    ): LineTokenizeResult<Int, Span> {
        val spans = mutableListOf<Span>()

        // FIX 1: Convert CharSequence → String before passing to matcher.
        val text = line.toString()

        var currentState = state ?: STATE_NORMAL

        // FIX 3: If the previous line left us inside a block comment, consume
        //         characters until we find the closing */ (or run out of line).
        if (currentState == STATE_BLOCK_COMMENT) {
            spans.add(Span.obtain(0, STYLE_COMMENT))
            val endMatcher = blockCommentEndPattern.matcher(text)
            if (endMatcher.find()) {
                // Found closing */; resume normal highlighting after it
                currentState = STATE_NORMAL
                val resumeAt = endMatcher.end()
                if (resumeAt < text.length) {
                    // Tokenize the remainder of the line normally
                    val rest = text.substring(resumeAt)
                    val restResult = tokenizeNormal(rest, resumeAt)
                    spans.addAll(restResult.first)
                    currentState = restResult.second
                }
            }
            // else: whole line is still inside the block comment — state stays BLOCK_COMMENT
            return LineTokenizeResult(currentState, spans)
        }

        // Normal tokenization
        val (moreSpans, newState) = tokenizeNormal(text, 0)
        spans.addAll(moreSpans)
        return LineTokenizeResult(newState, spans)
    }

    /**
     * Tokenizes [text] starting at [offset] (used for column positions in Span).
     * Returns the list of Spans and the resulting state after this segment.
     */
    private fun tokenizeNormal(text: String, offset: Int): Pair<List<Span>, Int> {
        val spans = mutableListOf<Span>()
        val matcher = pattern.matcher(text)
        var lastEnd = 0
        var resultState = STATE_NORMAL

        while (matcher.find()) {
            val start = matcher.start()
            if (start > lastEnd) {
                spans.add(Span.obtain(offset + lastEnd, STYLE_NORMAL))
            }

            val style: Long
            when {
                matcher.group(1) != null -> {       // line comment //
                    style = STYLE_COMMENT
                }
                matcher.group(2) != null -> {       // inline block comment /* ... */
                    style = STYLE_COMMENT
                }
                matcher.group(3) != null -> {       // block comment OPEN /* (unclosed)
                    // The rest of this line is a comment; mark state for next line
                    spans.add(Span.obtain(offset + start, STYLE_COMMENT))
                    return Pair(spans, STATE_BLOCK_COMMENT)
                }
                matcher.group(4) != null -> style = STYLE_STRING
                matcher.group(5) != null -> style = STYLE_KEYWORD
                matcher.group(6) != null -> style = STYLE_TYPE
                matcher.group(7) != null -> style = STYLE_BUILTIN
                matcher.group(8) != null -> style = STYLE_NUMBER
                matcher.group(9) != null -> style = STYLE_PREPROCESSOR
                else -> style = STYLE_NORMAL
            }

            spans.add(Span.obtain(offset + start, style))
            lastEnd = matcher.end()
        }

        if (lastEnd < text.length || spans.isEmpty()) {
            spans.add(Span.obtain(offset + lastEnd, STYLE_NORMAL))
        }

        return Pair(spans, resultState)
    }

    override fun generateSpansForLine(result: LineTokenizeResult<Int, Span>): List<Span> {
        return result.tokens ?: emptyList()
    }

    override fun computeBlocks(
        content: Content,
        delegate: AsyncIncrementalAnalyzeManager<Int, Span>.CodeBlockAnalyzeDelegate
    ): List<CodeBlock> {
        return emptyList()
    }

    override fun destroy() {
        super.destroy()
    }
}