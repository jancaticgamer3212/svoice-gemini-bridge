package com.svoice.gemini;

import java.nio.charset.Charset;

/** java.nio.charset.StandardCharsets is API 19; these names work on Android 4.3 too. */
final class Charsets {
    static final Charset UTF8 = Charset.forName("UTF-8");
    static final Charset ASCII = Charset.forName("US-ASCII");

    private Charsets() {}
}
