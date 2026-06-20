package com.esp32ide.ui.editor

import android.os.Bundle
import io.github.rosemoe.sora.lang.EmptyLanguage
import io.github.rosemoe.sora.lang.Language
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager
import io.github.rosemoe.sora.lang.completion.CompletionPublisher
import io.github.rosemoe.sora.lang.format.Formatter
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler
import io.github.rosemoe.sora.lang.util.BaseAnalyzeManager
import io.github.rosemoe.sora.text.CharPosition
import io.github.rosemoe.sora.text.ContentReference
import io.github.rosemoe.sora.widget.SymbolPairMatch

class ArduinoLanguage : Language {
    override fun getFormatter(): Formatter = EmptyLanguage.EmptyFormatter.INSTANCE
    override fun getSymbolPairs(): SymbolPairMatch = SymbolPairMatch()
    
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
        val lineText = content.getLine(line)
        if (lineText.trim().endsWith("{")) return 4
        return 0
    }

    override fun destroy() {}
    override fun useTab(): Boolean = true
}

class ArduinoAnalyzeManager : BaseAnalyzeManager() {
    override fun insert(start: CharPosition, end: CharPosition, insertedContent: CharSequence) {}
    override fun delete(start: CharPosition, end: CharPosition, deletedContent: CharSequence) {}
    override fun rerun() {}
}
