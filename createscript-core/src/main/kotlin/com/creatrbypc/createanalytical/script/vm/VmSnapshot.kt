package com.creatrbypc.createanalytical.script.vm

/**
 * The saved state of a run between two instructions, from `ScriptRun.snapshot`, to continue with
 * `CreateScript.resume`. Plain data without references to the game or files: the host decides how to store it
 * (field by field, as a game host does). States saved by earlier releases still resume (see
 * `docs/api/api-surface.md`). New fields are added at the end with default values.
 *
 * Not included: the progress of an instruction that takes several ticks and of a garbage collection (see
 * `CreateScript.resume`).
 *
 * @property pc the next instruction (in the innermost function when [callStack] is not empty).
 * @property state [VmState.RUNNING] or [VmState.WAITING]; finished runs are not saved.
 * @property waitRemaining the ticks left in `waitTicks`.
 * @property resumeWithUnit `true` when the run is coming back from `waitTicks` and the `Unit` result is not yet on the stack.
 * @property stack the operand stack, bottom first.
 * @property variables the top-level variables; `null` is not yet initialized.
 * @property consoleLines the console history (at most 32 lines).
 * @property callStack the functions being called, outermost first; empty at the top level.
 * @property objects the objects on the heap, by ascending id.
 * @property nextObjectId the id of the next object.
 * @property randomState the 64-bit state of the random number generator (every value is valid).
 */
public data class VmSnapshot(
    val pc: Int,
    val state: VmState,
    val waitRemaining: Int,
    val resumeWithUnit: Boolean,
    val stack: List<Value>,
    val variables: List<Value?>,
    val consoleLines: List<String>,
    val callStack: List<CallFrameSnapshot> = emptyList(),
    val objects: List<ObjectSnapshot> = emptyList(),
    val nextObjectId: Int = 1,
    val randomState: Long = 0L,
)

/**
 * One object on the heap of a [VmSnapshot].
 *
 * @property id the id that [Value.ObjectRef] uses.
 * @property classId a class of the program (an index into its classes, 0 or more), or [LIST_CLASS], [VECTOR_CLASS]
 *   or [MATRIX_CLASS].
 * @property fields the property values of an instance, in declaration order; `null` only while it is being
 *   initialized. Empty for lists, vectors and matrices.
 * @property list the elements of a list, vector or matrix; `null` for an instance.
 */
public data class ObjectSnapshot(val id: Int, val classId: Int, val fields: List<Value?> = emptyList(), val list: ListSnapshot? = null) {
    public companion object {
        /** [classId] of a list. */
        public const val LIST_CLASS: Int = -1

        /** [classId] of a `Vector`: an immutable list of 1 to 16 `Double`s. */
        public const val VECTOR_CLASS: Int = -2

        /** [classId] of a `Matrix`: an immutable list of 1 to 8 vectors (rows) of the same length, 1 to 8. */
        public const val MATRIX_CLASS: Int = -3
    }
}

/**
 * The elements of a list, vector or matrix in a [VmSnapshot].
 *
 * @property element the type of the elements.
 * @property mutable `true` for a list made with `mutableListOf`.
 * @property elements the elements, first to last.
 */
public data class ListSnapshot(val element: SnapshotVerifier.SlotType, val mutable: Boolean, val elements: List<Value>)

/**
 * One function call in progress in a [VmSnapshot].
 *
 * @property function the function of the program (an index into its functions).
 * @property returnPc the instruction to return to.
 * @property locals the local variables (parameters first); `null` is not yet initialized.
 */
public data class CallFrameSnapshot(val function: Int, val returnPc: Int, val locals: List<Value?>)
