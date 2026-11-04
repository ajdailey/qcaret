package io.github.ajdailey.qcaret.core.text;

/**
 * An encoding and the rule that chose it.
 *
 * @param encoding the encoding
 * @param reason the rule
 */
public record EncodingDetection(TextEncoding encoding, DetectionReason reason) {}
