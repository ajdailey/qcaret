/**
 * The only place QCaret builds a JDK XML parser or schema factory, enforced by ArchUnit. Each
 * {@link io.github.ajdailey.qcaret.core.xml.SecureXml} method states what its parser permits and
 * refuses; none reads a file or URL the caller did not pass.
 */
package io.github.ajdailey.qcaret.core.xml;
