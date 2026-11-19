package io.github.ajdailey.qcaret.core.edit;

import io.github.ajdailey.qcaret.core.concurrent.CancellationToken;
import java.util.HashMap;
import java.util.Map;

/**
 * XML entity escape and unescape. Unescape knows the five predefined XML entities, the 252 named
 * entities of HTML 4.01 and decimal and hex character references; any other {@code &}, an unknown
 * name, a reference without its {@code ;} and a reference to no Unicode scalar value stay as they
 * are. Names are case-sensitive.
 */
final class XmlEntities {
  /** The longest reference body considered, between {@code &} and {@code ;}. */
  private static final int MAX_BODY = 32;

  /** Latin-1 entities, U+00A0 to U+00FF in order (HTML 4.01 {@code HTMLlat1}). */
  private static final String[] LATIN1 = {
    "nbsp", "iexcl", "cent", "pound", "curren", "yen", "brvbar", "sect", "uml", "copy", "ordf",
    "laquo", "not", "shy", "reg", "macr", "deg", "plusmn", "sup2", "sup3", "acute", "micro", "para",
    "middot", "cedil", "sup1", "ordm", "raquo", "frac14", "frac12", "frac34", "iquest", "Agrave",
    "Aacute", "Acirc", "Atilde", "Auml", "Aring", "AElig", "Ccedil", "Egrave", "Eacute", "Ecirc",
    "Euml", "Igrave", "Iacute", "Icirc", "Iuml", "ETH", "Ntilde", "Ograve", "Oacute", "Ocirc",
    "Otilde", "Ouml", "times", "Oslash", "Ugrave", "Uacute", "Ucirc", "Uuml", "Yacute", "THORN",
    "szlig", "agrave", "aacute", "acirc", "atilde", "auml", "aring", "aelig", "ccedil", "egrave",
    "eacute", "ecirc", "euml", "igrave", "iacute", "icirc", "iuml", "eth", "ntilde", "ograve",
    "oacute", "ocirc", "otilde", "ouml", "divide", "oslash", "ugrave", "uacute", "ucirc", "uuml",
    "yacute", "thorn", "yuml"
  };

  /**
   * The other HTML 4.01 entities ({@code HTMLsymbol}, {@code HTMLspecial}) as name and code point
   * pairs, and XML's {@code apos}.
   */
  private static final Object[] OTHERS = {
    "quot",
    34,
    "amp",
    38,
    "apos",
    39,
    "lt",
    60,
    "gt",
    62,
    "OElig",
    338,
    "oelig",
    339,
    "Scaron",
    352,
    "scaron",
    353,
    "Yuml",
    376,
    "fnof",
    402,
    "circ",
    710,
    "tilde",
    732,
    "Alpha",
    913,
    "Beta",
    914,
    "Gamma",
    915,
    "Delta",
    916,
    "Epsilon",
    917,
    "Zeta",
    918,
    "Eta",
    919,
    "Theta",
    920,
    "Iota",
    921,
    "Kappa",
    922,
    "Lambda",
    923,
    "Mu",
    924,
    "Nu",
    925,
    "Xi",
    926,
    "Omicron",
    927,
    "Pi",
    928,
    "Rho",
    929,
    "Sigma",
    931,
    "Tau",
    932,
    "Upsilon",
    933,
    "Phi",
    934,
    "Chi",
    935,
    "Psi",
    936,
    "Omega",
    937,
    "alpha",
    945,
    "beta",
    946,
    "gamma",
    947,
    "delta",
    948,
    "epsilon",
    949,
    "zeta",
    950,
    "eta",
    951,
    "theta",
    952,
    "iota",
    953,
    "kappa",
    954,
    "lambda",
    955,
    "mu",
    956,
    "nu",
    957,
    "xi",
    958,
    "omicron",
    959,
    "pi",
    960,
    "rho",
    961,
    "sigmaf",
    962,
    "sigma",
    963,
    "tau",
    964,
    "upsilon",
    965,
    "phi",
    966,
    "chi",
    967,
    "psi",
    968,
    "omega",
    969,
    "thetasym",
    977,
    "upsih",
    978,
    "piv",
    982,
    "ensp",
    8194,
    "emsp",
    8195,
    "thinsp",
    8201,
    "zwnj",
    8204,
    "zwj",
    8205,
    "lrm",
    8206,
    "rlm",
    8207,
    "ndash",
    8211,
    "mdash",
    8212,
    "lsquo",
    8216,
    "rsquo",
    8217,
    "sbquo",
    8218,
    "ldquo",
    8220,
    "rdquo",
    8221,
    "bdquo",
    8222,
    "dagger",
    8224,
    "Dagger",
    8225,
    "bull",
    8226,
    "hellip",
    8230,
    "permil",
    8240,
    "prime",
    8242,
    "Prime",
    8243,
    "lsaquo",
    8249,
    "rsaquo",
    8250,
    "oline",
    8254,
    "frasl",
    8260,
    "euro",
    8364,
    "image",
    8465,
    "weierp",
    8472,
    "real",
    8476,
    "trade",
    8482,
    "alefsym",
    8501,
    "larr",
    8592,
    "uarr",
    8593,
    "rarr",
    8594,
    "darr",
    8595,
    "harr",
    8596,
    "crarr",
    8629,
    "lArr",
    8656,
    "uArr",
    8657,
    "rArr",
    8658,
    "dArr",
    8659,
    "hArr",
    8660,
    "forall",
    8704,
    "part",
    8706,
    "exist",
    8707,
    "empty",
    8709,
    "nabla",
    8711,
    "isin",
    8712,
    "notin",
    8713,
    "ni",
    8715,
    "prod",
    8719,
    "sum",
    8721,
    "minus",
    8722,
    "lowast",
    8727,
    "radic",
    8730,
    "prop",
    8733,
    "infin",
    8734,
    "ang",
    8736,
    "and",
    8743,
    "or",
    8744,
    "cap",
    8745,
    "cup",
    8746,
    "int",
    8747,
    "there4",
    8756,
    "sim",
    8764,
    "cong",
    8773,
    "asymp",
    8776,
    "ne",
    8800,
    "equiv",
    8801,
    "le",
    8804,
    "ge",
    8805,
    "sub",
    8834,
    "sup",
    8835,
    "nsub",
    8836,
    "sube",
    8838,
    "supe",
    8839,
    "oplus",
    8853,
    "otimes",
    8855,
    "perp",
    8869,
    "sdot",
    8901,
    "lceil",
    8968,
    "rceil",
    8969,
    "lfloor",
    8970,
    "rfloor",
    8971,
    "lang",
    9001,
    "rang",
    9002,
    "loz",
    9674,
    "spades",
    9824,
    "clubs",
    9827,
    "hearts",
    9829,
    "diams",
    9830
  };

  private static final Map<String, Integer> NAMED = named();

  private XmlEntities() {}

  /**
   * Returns the code point a name stands for.
   *
   * @param name the name between {@code &} and {@code ;}
   * @return the code point, or -1 for an unknown name
   */
  static int named(String name) {
    Integer cp = NAMED.get(name);
    return cp == null ? -1 : cp;
  }

  /**
   * Returns the number of names known.
   *
   * @return 253: the HTML 4.01 entities and {@code apos}
   */
  static int nameCount() {
    return NAMED.size();
  }

  static String escape(CharSequence text, CancellationToken token) {
    StringBuilder out = new StringBuilder(text.length());
    for (int i = 0; i < text.length(); i++) {
      TextCodec.check(i, token);
      char c = text.charAt(i);
      switch (c) {
        case '&' -> out.append("&amp;");
        case '<' -> out.append("&lt;");
        case '>' -> out.append("&gt;");
        case '"' -> out.append("&quot;");
        case '\'' -> out.append("&apos;");
        default -> out.append(c);
      }
    }
    return out.toString();
  }

  static String unescape(CharSequence text, CancellationToken token) {
    StringBuilder out = new StringBuilder(text.length());
    int i = 0;
    int checked = 0;
    while (i < text.length()) {
      if (i - checked >= TextCodec.CHECK_CHARS) {
        token.check();
        checked = i;
      }
      char c = text.charAt(i);
      int end = c == '&' ? semicolon(text, i + 1) : -1;
      int cp = end < 0 ? -1 : reference(text, i + 1, end);
      if (cp < 0) {
        out.append(c);
        i++;
      } else {
        out.appendCodePoint(cp);
        i = end + 1;
      }
    }
    return out.toString();
  }

  /** Returns the offset of the {@code ;} closing a reference body starting at {@code from}. */
  private static int semicolon(CharSequence text, int from) {
    int limit = Math.min(text.length(), from + MAX_BODY + 1);
    for (int i = from; i < limit; i++) {
      char c = text.charAt(i);
      if (c == ';') {
        return i > from ? i : -1;
      }
      if (c == '&' || c == '<' || Character.isWhitespace(c)) {
        return -1;
      }
    }
    return -1;
  }

  /** Returns the code point of a reference body, or -1 when it names none. */
  private static int reference(CharSequence text, int from, int end) {
    if (text.charAt(from) != '#') {
      return named(text.subSequence(from, end).toString());
    }
    int radix = 10;
    int at = from + 1;
    if (at < end && (text.charAt(at) == 'x' || text.charAt(at) == 'X')) {
      radix = 16;
      at++;
    }
    if (at == end) {
      return -1;
    }
    int value = 0;
    for (int i = at; i < end; i++) {
      int digit = radix == 16 ? TextCodec.hex(text.charAt(i)) : decimal(text.charAt(i));
      if (digit < 0) {
        return -1;
      }
      value = value * radix + digit;
      if (value > Character.MAX_CODE_POINT) {
        return -1;
      }
    }
    boolean surrogate = value >= Character.MIN_SURROGATE && value <= Character.MAX_SURROGATE;
    return value == 0 || surrogate ? -1 : value;
  }

  private static int decimal(char c) {
    return c >= '0' && c <= '9' ? c - '0' : -1;
  }

  private static Map<String, Integer> named() {
    Map<String, Integer> map = new HashMap<String, Integer>();
    for (int i = 0; i < LATIN1.length; i++) {
      map.put(LATIN1[i], 0xa0 + i);
    }
    for (int i = 0; i < OTHERS.length; i += 2) {
      map.put((String) OTHERS[i], (Integer) OTHERS[i + 1]);
    }
    return map;
  }
}
