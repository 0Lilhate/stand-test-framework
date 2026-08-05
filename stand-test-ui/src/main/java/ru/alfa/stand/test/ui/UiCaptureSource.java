package ru.alfa.stand.test.ui;

/**
 * Where a {@link UiCapture} reads the value it stores in the run's variable store.
 */
public enum UiCaptureSource {

    /** The element's text content. */
    TEXT,

    /** The value of an input element — not expressible through {@link #TEXT}. */
    VALUE,

    /** A named attribute of the element. */
    ATTRIBUTE
}
