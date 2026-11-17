package com.creatrbypc.createanalytical.script.apilistingfixture

import com.creatrbypc.createanalytical.script.InternalCreateScriptApi

// R2.5: declarations for ApiListingTest.markersOnConstantsAndPropertiesAreListed. Kotlin puts the marker of a property
// on a synthetic holder method (`get<Name>$annotations`); a constant or @JvmField field has no getter of that name.
// Only ApiListingTest reads these; they are not part of the API listing of createscript-core (test classes).

@InternalCreateScriptApi
public const val MARKED_TOP_LEVEL_CONSTANT: String = "marked"

public const val PLAIN_TOP_LEVEL_CONSTANT: String = "plain"

public object ApiListingFixtureHolder {
    @InternalCreateScriptApi
    public const val MARKED_CONSTANT: Int = 1

    public const val PLAIN_CONSTANT: Int = 2

    @InternalCreateScriptApi
    public val markedProperty: Int = 3

    public val plainProperty: Int = 4

    @InternalCreateScriptApi
    @JvmField
    public val markedField: Int = 5

    @InternalCreateScriptApi
    public val isMarkedFlag: Boolean = true
}
