package com.dbx.dialect.api;

/**
 * A pair descriptor as a contract snapshot stores it: the version it was written with and the text
 * that version's codec produced (ADR-0008 §Registration). The version travels beside the text so
 * recovery selects the codec before it reads the text.
 */
public record EncodedDescriptor(DescriptorVersion version, String text) {

    public EncodedDescriptor {
        Checks.present(version, "version");
        Checks.nonEmpty(text, "text");
    }
}
