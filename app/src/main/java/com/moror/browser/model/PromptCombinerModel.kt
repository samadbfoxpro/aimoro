package com.moror.browser.model

import java.util.UUID

enum class CombinerPlacementMode(val code: Int) {
    AFTER_COMMA(0),
    AT_BEGINNING(1),
    AT_END(2),
    PER_FOLDER(3);

    companion object {
        fun fromInt(code: Int): CombinerPlacementMode {
            return entries.firstOrNull { it.code == code } ?: AFTER_COMMA
        }
    }
}

data class PromptCombinerItem(
    val id: String = UUID.randomUUID().toString(),
    var folderId: String = "",
    var title: String = "",
    var text: String = "",
    var isEnabled: Boolean = false,
    var order: Int = 0
)

data class PromptCombinerFolder(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "",
    var order: Int = 0,
    var isCustomInput: Boolean = false,
    var customInputText: String = "",
    var customTitle: String = "",
    var isCustomInputActive: Boolean = false,
    var placementMode: CombinerPlacementMode = CombinerPlacementMode.AFTER_COMMA,
    var commaIndex: Int = 1
)

data class PromptCombinerData(
    var isEnabled: Boolean = false,
    var isStandaloneGlobalEnabled: Boolean = true,
    var autoCaptureBasePrompt: Boolean = false,
    var activeFolderId: String = "",
    val activeItemIds: MutableList<String> = mutableListOf(),
    var placementMode: CombinerPlacementMode = CombinerPlacementMode.AFTER_COMMA,
    var commaIndex: Int = 1,
    var separator: String = ", ",
    val folders: MutableList<PromptCombinerFolder> = mutableListOf(),
    val items: MutableList<PromptCombinerItem> = mutableListOf()
)
