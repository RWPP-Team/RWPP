package android.content

/** Callback linkage only; the dedicated server does not create Android dialogs. */
interface DialogInterface {
    fun cancel()
    fun dismiss()

    interface OnCancelListener {
        fun onCancel(dialog: DialogInterface)
    }

    interface OnClickListener {
        fun onClick(dialog: DialogInterface, which: Int)
    }
}
