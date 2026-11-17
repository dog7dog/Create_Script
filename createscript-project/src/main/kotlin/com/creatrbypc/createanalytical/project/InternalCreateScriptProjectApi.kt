package com.creatrbypc.createanalytical.project

/**
 * Marks declarations of `createscript-project` that are public only because other modules of the CreateScript
 * repository use them (the JSON reader and writer, package archives and the registry formats).
 *
 * They are not part of the supported API: they can change or disappear in any release, without a CHANGELOG
 * entry. Using a marked declaration gives a warning unless the caller opts in with
 * `@OptIn(InternalCreateScriptProjectApi::class)` or the compiler option
 * `-opt-in=com.creatrbypc.createanalytical.project.InternalCreateScriptProjectApi`.
 *
 * See `docs/api/api-surface.md`.
 */
@RequiresOptIn(
    message = "This is an internal API of createscript-project. It can change in any release without notice; see docs/api/api-surface.md.",
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
public annotation class InternalCreateScriptProjectApi
