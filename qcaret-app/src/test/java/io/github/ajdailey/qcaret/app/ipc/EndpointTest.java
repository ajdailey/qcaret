package io.github.ajdailey.qcaret.app.ipc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ajdailey.qcaret.core.io.AtomicFiles;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** {@code instance.endpoint} as {@link Endpoint#read} parses it. */
class EndpointTest {
  /** {@code Endpoint.MAX_BYTES}. */
  private static final int LIMIT = 4096;

  private static final String TOKEN = "00".repeat(Protocol.SECRET_BYTES);

  @TempDir Path temp;

  private void writeFile(String text) throws IOException {
    AtomicFiles.writeOwnerOnly(
        temp.resolve(Endpoint.FILE), text.getBytes(StandardCharsets.ISO_8859_1));
  }

  private void assertMalformed() {
    IOException failure =
        assertThrows(
            IOException.class,
            new Executable() {
              @Override
              public void execute() throws Throwable {
                Endpoint.read(temp);
              }
            });
    assertFalse(failure instanceof NoSuchFileException, failure.toString());
    assertNull(InstanceClient.endpointId(temp));
  }

  @Test
  void aWrittenEndpointReadsBack() throws Exception {
    byte[] token = Protocol.secret();
    new Endpoint(65_535, token).write(temp);
    Endpoint read = Endpoint.read(temp);
    assertEquals(65_535, read.port());
    assertArrayEquals(token, read.token());
  }

  @Test
  void aMissingFileIsNoSuchFile() {
    assertThrows(
        NoSuchFileException.class,
        new Executable() {
          @Override
          public void execute() throws Throwable {
            Endpoint.read(temp);
          }
        });
  }

  static List<String> malformed() {
    String rest = TOKEN.substring(2);
    return List.of(
        "",
        "port=1\ntoken=" + TOKEN + "\n",
        "version=2\nport=1\ntoken=" + TOKEN + "\n",
        "version=1\ntoken=" + TOKEN + "\n",
        "version=1\nport=x\ntoken=" + TOKEN + "\n",
        "version=1\nport=0\ntoken=" + TOKEN + "\n",
        "version=1\nport=65536\ntoken=" + TOKEN + "\n",
        "version=1\nport=99999999999\ntoken=" + TOKEN + "\n",
        "version=1\nport=1\n",
        "version=1\nport=1\ntoken=zz" + rest + "\n",
        "version=1\nport=1\ntoken=0" + TOKEN + "\n",
        "version=1\nport=1\ntoken=" + rest + "\n",
        "version=1\nport=1\ntoken=" + TOKEN + "00\n",
        "version=1\nport=1\\u00zz\ntoken=" + TOKEN + "\n");
  }

  @ParameterizedTest
  @MethodSource("malformed")
  void aMalformedEndpointIsRefused(String text) throws Exception {
    writeFile(text);
    assertMalformed();
  }

  @Test
  void anEndpointOverItsByteLimitIsRefused() throws Exception {
    String valid = "version=1\nport=1\ntoken=" + TOKEN + "\n";
    String atLimit = valid + "#" + "x".repeat(LIMIT - valid.length() - 2) + "\n";
    assertEquals(LIMIT, atLimit.length());
    writeFile(atLimit);
    assertEquals(1, Endpoint.read(temp).port());
    writeFile(atLimit + "\n");
    assertMalformed();
  }
}
