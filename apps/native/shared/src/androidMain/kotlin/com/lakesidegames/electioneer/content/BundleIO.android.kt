package com.lakesidegames.electioneer.content

// commonMain resources ship inside the APK and resolve via the classloader.
actual fun bundleText(name: String): String {
    val stream = object {}.javaClass.classLoader?.getResourceAsStream("bundles/$name.json")
        ?: error("missing content bundle: $name")
    return stream.bufferedReader().readText()
}
