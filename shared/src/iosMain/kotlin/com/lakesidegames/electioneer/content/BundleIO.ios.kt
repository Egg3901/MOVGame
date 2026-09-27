package com.lakesidegames.electioneer.content

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

// Bundle files must be added to the iosApp target's resources (verify on
// macOS: the kmp.yml iOS job decodes every bundle in ContentDataTest).
@OptIn(ExperimentalForeignApi::class)
actual fun bundleText(name: String): String {
    val path = NSBundle.mainBundle.pathForResource(name, ofType = "json", inDirectory = "bundles")
        ?: error("missing content bundle: $name")
    return (NSString.stringWithContentsOfFile(path, NSUTF8StringEncoding, null) as String)
}
