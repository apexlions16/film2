package com.apexlions.film2.studio.navigation

object Destinations {
    const val LIST = "list"
    const val SETTINGS = "settings"
    const val NEW_TITLE = "new-title"

    const val ATTACH_FILES_ROUTE = "attach-files/{titleId}/{titleType}"
    fun attachFiles(titleId: String, titleType: String) = "attach-files/$titleId/$titleType"

    const val ARG_TITLE_ID = "titleId"
    const val ARG_TITLE_TYPE = "titleType"
}
