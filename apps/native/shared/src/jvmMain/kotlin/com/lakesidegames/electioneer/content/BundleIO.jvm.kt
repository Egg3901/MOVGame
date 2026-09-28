package com.lakesidegames.electioneer.content

actual fun bundleText(name: String): String {
    val stream = object {}.javaClass.getResourceAsStream("/bundles/$name.json")
        ?: error("missing content bundle: $name")
    return stream.bufferedReader().readText()
}
