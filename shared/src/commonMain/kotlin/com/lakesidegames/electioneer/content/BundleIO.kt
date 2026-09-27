package com.lakesidegames.electioneer.content

// Reads a content bundle by name ("us-events", "countries/ca"). Platform
// actuals resolve the file from packaged resources.
expect fun bundleText(name: String): String
