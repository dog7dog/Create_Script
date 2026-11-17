package com.creatrbypc.createanalytical.script

/**
 * Marks declarations of the CreateScript Core that are public only because other modules of the CreateScript
 * repository use them, or because they were public in v0.1.0-alpha.
 *
 * They are not part of the supported API: they can change or disappear in any release, without a CHANGELOG
 * entry. Hosts should use `CreateScript` in `script.api` and the types it returns. Using a marked declaration
 * gives a warning unless the caller opts in with `@OptIn(InternalCreateScriptApi::class)` or the compiler option
 * `-opt-in=com.creatrbypc.createanalytical.script.InternalCreateScriptApi`.
 *
 * See `docs/api/api-surface.md`.
 */
@RequiresOptIn(
    message = "This is an internal API of the CreateScript Core. It can change in any release without notice; see docs/api/api-surface.md.",
    level = RequiresOptIn.Level.WARNING,
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY,
    AnnotationTarget.CONSTRUCTOR,
    AnnotationTarget.TYPEALIAS,
)
@MustBeDocumented
public annotation class InternalCreateScriptApi
