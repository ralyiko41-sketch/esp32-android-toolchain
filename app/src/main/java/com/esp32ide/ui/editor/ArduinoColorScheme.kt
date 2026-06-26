package com.esp32ide.ui.editor

import io.github.rosemoe.sora.widget.schemes.EditorColorScheme

/**
 * Custom color scheme for Arduino IDE look.
 */
class ArduinoColorScheme : EditorColorScheme() {
    override fun applyDefault() {
        super.applyDefault()
        
        // Dark theme base (Original SchemeDarcula colors)
        setColor(WHOLE_BACKGROUND, 0xFF2B2B2B.toInt())
        setColor(TEXT_NORMAL, 0xFFFFFFFF.toInt())
        setColor(LINE_NUMBER_BACKGROUND, 0xFF313335.toInt())
        setColor(LINE_NUMBER, 0xFF606366.toInt())
        setColor(LINE_NUMBER_CURRENT, 0xFF606366.toInt())
        setColor(LINE_DIVIDER, 0xFF606366.toInt())
        setColor(CURRENT_LINE, 0xFF323232.toInt())
        
        // Ensure white cursor
        setColor(SELECTION_INSERT, 0xFFFFFFFF.toInt())
        setColor(SELECTION_HANDLE, 0xFFFFFFFF.toInt())
        
        // Highlight slots referenced in ArduinoLanguage.kt (Original Darcula values)
        setColor(KEYWORD, 0xFFCC7832.toInt())         // Orange
        setColor(ATTRIBUTE_NAME, 0xFF4EC9B0.toInt())  // Keep your Teal for Types (Looks better)
        setColor(FUNCTION_NAME, 0xFFFFFFFF.toInt())   // White for Builtins
        setColor(LITERAL, 0xFF6A8759.toInt())         // Green for Strings
        setColor(COMMENT, 0xFF808080.toInt())         // Grey
        setColor(ANNOTATION, 0xFFBBB529.toInt())      // Yellow for Numbers
        setColor(ATTRIBUTE_VALUE, 0xFFBBB529.toInt()) // Preprocessor

        // Force white cursor
        setColor(SELECTION_INSERT, 0xFFFFFFFF.toInt())
        setColor(SELECTION_HANDLE, 0xFFFFFFFF.toInt())
    }
}
