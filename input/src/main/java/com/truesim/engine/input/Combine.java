package com.truesim.engine.input;

/** How an action's bindings make one value (application-api.md D87). */
public enum Combine {
    /** The value with the largest magnitude (the longest vector); binary: down while any is. The default. */
    LARGEST,
    /** The values added: a mouse and a stick turning the camera together. */
    SUM,
    /** The first binding's value that is not 0, in the bindings' order: a priority. */
    FIRST
}
