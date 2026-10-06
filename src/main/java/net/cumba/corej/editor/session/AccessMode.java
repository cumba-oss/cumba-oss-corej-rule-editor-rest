package net.cumba.corej.editor.session;

/** What a session's token may do with the repository (O9, §5.1.3). */
public enum AccessMode
{
    /** The token can push: save, commit and push are allowed. */
    EDIT,
    /** The token can read but not push: edit and run without saving (O12). */
    READ_ONLY
}
