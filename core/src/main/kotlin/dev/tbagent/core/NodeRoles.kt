package dev.tbagent.core

/** Best-effort role inference from Android class names and Chrome's node extras. */
object NodeRoles {
    /** Chrome publishes its internal role in this extra (e.g. "textField", "button", "group"). */
    const val CHROME_ROLE_EXTRA = "AccessibilityNodeInfo.chromeRole"
    const val ROLE_DESCRIPTION_EXTRA = "AccessibilityNodeInfo.roleDescription"
    const val TARGET_URL_EXTRA = "AccessibilityNodeInfo.targetUrl"

    fun infer(n: NodeSnapshot): Role {
        val chrome = (n.extras[CHROME_ROLE_EXTRA] ?: "").lowercase()
        val cls = n.className ?: ""
        return when {
            n.isEditable || cls.endsWith("EditText") || chrome in setOf("textfield", "searchbox", "textfieldwithcombobox") -> Role.EDIT_TEXT
            chrome == "checkbox" || cls.endsWith("CheckBox") -> Role.CHECKBOX
            chrome == "radiobutton" || cls.endsWith("RadioButton") -> Role.RADIO
            chrome == "switch" || cls.endsWith("Switch") || cls.endsWith("ToggleButton") -> Role.SWITCH
            chrome.startsWith("combobox") || chrome == "popupbutton" || chrome == "listbox" || cls.endsWith("Spinner") -> Role.COMBOBOX
            chrome == "link" || n.extras.containsKey(TARGET_URL_EXTRA) -> Role.LINK
            chrome == "button" || cls.endsWith("Button") -> Role.BUTTON
            n.isHeading || chrome == "heading" -> Role.HEADING
            chrome in setOf("group", "radiogroup", "form", "region", "fieldset") -> Role.GROUP
            chrome == "image" || chrome == "img" || cls.endsWith("Image") || cls.endsWith("ImageView") -> Role.IMAGE
            chrome == "list" || cls.endsWith("ListView") -> Role.LIST
            chrome == "table" || cls.endsWith("GridView") -> Role.TABLE
            chrome == "statictext" || cls.endsWith("TextView") -> Role.TEXT
            else -> Role.OTHER
        }
    }
}
