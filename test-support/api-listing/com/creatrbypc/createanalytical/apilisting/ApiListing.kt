package com.creatrbypc.createanalytical.apilisting

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.spi.ToolProvider

// A3: the public API of a module as seen on the JVM, made with javap of JDK 21 from the compiled classes
// (docs/api/api-surface.md). Shared by the tests of createscript-core and createscript-project (both add this
// directory to their test sources); it uses only the JDK.
//
// What is listed: public classes whose outer classes are public too (not synthetic), and their public and protected
// fields, constructors and methods that are not synthetic. Each line is the javap declaration. A field that is a
// constant also shows its value (code that uses it copies the value). After `//` a line can show:
//   opt-in      the declaration needs opt-in (it, a class around it, or for a getter or a constant field its property,
//               has the marker). A marker on a property that no listed declaration picks up fails the listing.
//   returns !/? the result is @NotNull / @Nullable
//   params      one mark per parameter: ! (@NotNull), ? (@Nullable), - (neither)
//
// Kotlin `internal` and the JVM: an internal function or property accessor is public on the JVM with a name ending in
// `$<module>` (for example `isEmpty$createscript_project`); these are left out. An internal class, constructor or
// constant is public on the JVM without such a mark; javap cannot tell it from a public one (that needs the Kotlin
// metadata), so it is listed like other JVM-public declarations.
object ApiListing {
    const val UPDATE_VARIABLE = "CREATESCRIPT_API_UPDATE"
    const val JDK_FEATURE = 21

    private val NULLABILITY = mapOf("org.jetbrains.annotations.NotNull" to "!", "org.jetbrains.annotations.Nullable" to "?")

    private class Member(val declaration: String) {
        var flags = ""
        var constant: String? = null
        val annotations = ArrayList<String>()
        val parameters = ArrayList<MutableList<String>>()
    }

    private class ClassInfo(val name: String, val declaration: String) {
        var flags = ""
        val annotations = ArrayList<String>()
        val members = ArrayList<Member>()
    }

    // The listing of the classes under `classDirectories`. `module` is the Gradle project name (for the internal names)
    // and `markers` the fully qualified names of the opt-in annotations.
    fun listing(module: String, classDirectories: List<File>, markers: Set<String>): String {
        check(Runtime.version().feature() == JDK_FEATURE) {
            "The API listing is made with JDK $JDK_FEATURE; this test runs on JDK ${Runtime.version()}"
        }
        val files = classDirectories.filter { it.isDirectory }.flatMap { root ->
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".class") && it.name != "module-info.class" }.toList()
        }.sortedBy { it.path }
        check(files.isNotEmpty()) { "No classes found in $classDirectories" }
        val classes = files.chunked(200).flatMap { parse(javap(listOf("-v", "-p") + it.map { file -> file.path })) }
        val byName = classes.associateBy { it.name }
        val mangled = "$" + module.replace('-', '_')

        fun outerChain(name: String): List<ClassInfo> {
            val result = ArrayList<ClassInfo>()
            var current = name
            while (current.contains('$')) {
                current = current.substringBeforeLast('$')
                byName[current]?.let { result.add(it) }
            }
            return result
        }

        fun isPublic(flags: String) = "ACC_PUBLIC" in flags
        fun marked(annotations: List<String>) = annotations.any { it in markers }

        val out = StringBuilder()
        out.append("# Public API of ").append(module).append(" on the JVM (javap of JDK ").append(JDK_FEATURE).append(").\n")
        out.append("# Made by ApiListingTest; to change it run the tests with ").append(UPDATE_VARIABLE).append("=1. See docs/api/api-surface.md.\n")
        for (info in classes.sortedBy { it.name }) {
            val outers = outerChain(info.name)
            if (!isPublic(info.flags) || "ACC_SYNTHETIC" in info.flags || outers.any { !isPublic(it.flags) }) continue
            val classOptIn = marked(info.annotations) || outers.any { marked(it.annotations) }
            out.append('\n').append(info.declaration)
            if (classOptIn) out.append("  // opt-in")
            out.append('\n')
            val annotationHolders = info.members.filter { it.methodName()?.endsWith("\$annotations") == true }
                .associateBy { it.methodName()!!.removeSuffix("\$annotations") }
            val usedHolders = HashSet<String>()
            val lines = ArrayList<String>()
            for (member in info.members) {
                val flags = member.flags
                if (!(isPublic(flags) || "ACC_PROTECTED" in flags) || "ACC_SYNTHETIC" in flags) continue
                val name = member.methodName() ?: member.fieldName()
                if (name != null && (name.endsWith(mangled) || name.contains("$mangled\$") || name.endsWith("\$annotations"))) continue
                val notes = ArrayList<String>()
                // A getter shares its name with the holder. A constant (or @JvmField) field has no getter: its holder is
                // named like the getter the property would have, `get` + the name with its first letter in upper case.
                val holderName = when {
                    name == null -> null
                    name in annotationHolders -> name
                    member.fieldName() != null -> getterName(name).takeIf { it in annotationHolders }
                    else -> null
                }
                holderName?.let { usedHolders.add(it) }
                val optIn = classOptIn || marked(member.annotations) || (holderName != null && marked(annotationHolders.getValue(holderName).annotations))
                if (optIn) notes.add("opt-in")
                member.annotations.firstNotNullOfOrNull { NULLABILITY[it] }?.let { if (member.methodName() != null || member.fieldName() != null) notes.add("returns $it") }
                if (member.parameters.isNotEmpty()) {
                    notes.add("params " + member.parameters.joinToString(" ") { p -> p.firstNotNullOfOrNull { NULLABILITY[it] } ?: "-" })
                }
                val declaration = member.declaration + (member.constant?.let { " = $it" } ?: "")
                lines.add("  " + declaration + if (notes.isEmpty()) "" else "  // " + notes.joinToString("; "))
            }
            lines.sorted().forEach { out.append(it).append('\n') }
            // A marker on a property that no listed declaration picks up would be missing from the listing (as the
            // marker of a top-level constant was before R2.5), so such a holder fails the listing instead
            val missed = annotationHolders.filter { (holder, member) -> holder !in usedHolders && marked(member.annotations) }.keys.sorted()
            check(missed.isEmpty()) {
                "${info.name}: the opt-in marker on ${missed.joinToString { "$it\$annotations" }} applies to no listed declaration"
            }
        }
        return out.toString()
    }

    // The JVM getter name of a Kotlin property (`count` -> `getCount`, `FUNCTION_TYPE_NAME` -> `getFUNCTION_TYPE_NAME`).
    // Properties named `isX` keep their name, and their holder is found by the name itself.
    private fun getterName(property: String): String = "get" + property.replaceFirstChar { it.uppercaseChar() }

    private fun Member.methodName(): String? = Regex("""([\w$<>]+)\(""").find(declaration)?.groupValues?.get(1)

    private fun Member.fieldName(): String? = if (declaration.contains('(')) null else declaration.trimEnd(';').substringAfterLast(' ')

    private fun javap(arguments: List<String>): String {
        val out = StringWriter()
        val err = StringWriter()
        val tool = ToolProvider.findFirst("javap").orElse(null)
        if (tool != null) {
            val code = PrintWriter(out).use { o -> PrintWriter(err).use { e -> tool.run(o, e, *arguments.toTypedArray()) } }
            check(code == 0) { "javap failed ($code): $err" }
            return out.toString()
        }
        val executable = File(System.getProperty("java.home"), "bin/javap")
        check(executable.canExecute()) { "javap is not available: no ToolProvider and no ${executable.path}" }
        val process = ProcessBuilder(listOf(executable.path) + arguments).redirectErrorStream(false).start()
        val text = process.inputStream.bufferedReader().readText()
        val errors = process.errorStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "javap failed: $errors" }
        return text
    }

    // Reads `javap -v -p` output: the class line, its flags and annotations, and for each member its declaration,
    // flags, constant value, annotations and parameter annotations. Header lines (file, date, checksum) are ignored.
    private fun parse(text: String): List<ClassInfo> {
        val result = ArrayList<ClassInfo>()
        var current: ClassInfo? = null
        var member: Member? = null
        var inMembers = false
        var section = ""
        var parameter = -1
        for (raw in text.lines()) {
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            if (raw.startsWith("Classfile ")) {
                current = null; member = null; inMembers = false; section = ""
                continue
            }
            if (current == null) {
                if (indent == 0 && line.isNotEmpty() && (line.contains(" class ") || line.contains(" interface ") || line.contains(" enum ") ||
                        line.startsWith("class ") || line.startsWith("interface "))) {
                    val name = Regex("""(?:class|interface|enum)\s+([\w.$]+)""").find(line)!!.groupValues[1]
                    current = ClassInfo(name, line).also { result.add(it) }
                }
                continue
            }
            if (!inMembers) {
                when {
                    line == "{" -> inMembers = true
                    indent == 2 && line.startsWith("flags:") -> current.flags = line
                }
                continue
            }
            if (indent == 0 && line == "}") {
                member = null; section = "class"
                continue
            }
            if (section == "class") {
                if (indent == 0 && line.endsWith(":")) section = if (line == "RuntimeInvisibleAnnotations:" || line == "RuntimeVisibleAnnotations:") "class-annotations" else "class"
                continue
            }
            if (section == "class-annotations") {
                if (indent == 0) { section = if (line == "RuntimeInvisibleAnnotations:" || line == "RuntimeVisibleAnnotations:") "class-annotations" else "class"; continue }
                if (indent == 4) current.annotations.add(line)
                continue
            }
            if (indent == 2 && line.isNotEmpty()) {
                member = Member(line).also { current.members.add(it) }
                section = ""
                continue
            }
            val m = member ?: continue
            if (indent == 4) {
                section = ""
                when {
                    line.startsWith("flags:") -> m.flags = line
                    line.startsWith("ConstantValue:") -> m.constant = line.removePrefix("ConstantValue:").trim()
                    line == "RuntimeInvisibleAnnotations:" || line == "RuntimeVisibleAnnotations:" -> section = "annotations"
                    line == "RuntimeInvisibleParameterAnnotations:" || line == "RuntimeVisibleParameterAnnotations:" -> { section = "parameters"; parameter = -1 }
                }
                continue
            }
            when (section) {
                "annotations" -> if (indent == 8) m.annotations.add(line)
                "parameters" -> when {
                    indent == 6 && line.startsWith("parameter ") -> {
                        parameter = line.removePrefix("parameter ").removeSuffix(":").trim().toInt()
                        while (m.parameters.size <= parameter) m.parameters.add(ArrayList())
                    }
                    indent == 10 && parameter >= 0 -> m.parameters[parameter].add(line)
                }
            }
        }
        return result
    }

    // The first lines that differ, for the failure message
    fun difference(expected: String, actual: String, limit: Int = 40): String {
        val old = expected.lines().toSet()
        val new = actual.lines().toSet()
        val removed = expected.lines().filter { it !in new }
        val added = actual.lines().filter { it !in old }
        val shown = removed.take(limit).map { "- $it" } + added.take(limit).map { "+ $it" }
        return shown.joinToString("\n") + "\n(${removed.size} lines removed, ${added.size} lines added)"
    }
}
