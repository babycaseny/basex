package org.basex.util.http;

import static org.basex.query.QueryError.*;
import static org.basex.util.Token.*;
import static org.basex.util.http.HttpText.*;

import java.io.*;
import java.net.*;
import java.util.*;

import org.basex.build.csv.*;
import org.basex.build.html.*;
import org.basex.build.json.*;
import org.basex.core.*;
import org.basex.io.*;
import org.basex.io.in.*;
import org.basex.io.parse.csv.*;
import org.basex.io.parse.json.*;
import org.basex.io.serial.*;
import org.basex.query.*;
import org.basex.query.util.list.*;
import org.basex.query.value.*;
import org.basex.query.value.item.*;
import org.basex.query.value.map.Map;
import org.basex.query.value.node.*;
import org.basex.query.value.seq.*;
import org.basex.util.*;
import org.basex.util.list.*;

/**
 * HTTP payload helper functions.
 *
 * @author BaseX Team 2005-16, BSD License
 * @author Christian Gruen
 */
public final class HttpPayload {
  /** Payloads (may be {@code null}). */
  private final ValueBuilder payloads;
  /** Input stream. */
  private final InputStream input;
  /** Input info. */
  private final InputInfo info;
  /** Database options. */
  private final MainOptions options;

  /**
   * Constructor.
   * @param input input stream
   * @param body create body
   * @param info input info
   * @param options database options
   */
  public HttpPayload(final InputStream input, final boolean body, final InputInfo info,
      final MainOptions options) {

    this.input = input;
    this.info = info;
    this.options = options;
    payloads = body ? new ValueBuilder() : null;
  }

  /**
   * Parses the HTTP payload and returns a result body element.
   * @param type media type
   * @return body element
   * @throws IOException I/O exception
   * @throws QueryException query exception
   */
  FElem parse(final MediaType type) throws IOException, QueryException {
    final FElem body;
    if(type.isMultipart()) {
      // multipart response
      final byte[] boundary = boundary(type);
      if(boundary == null) throw HC_REQ_X.get(info, "No separation boundary specified");

      body = new FElem(Q_MULTIPART).add(BOUNDARY, boundary);
      final ANodeList parts = new ANodeList();
      extractParts(concat(DASHES, boundary), parts);
      for(final ANode node : parts) body.add(node);
    } else {
      // single part response
      body = new FElem(Q_BODY);
      if(payloads != null) {
        final byte[] pl = (type.isXML() || type.isText()
          ? new NewlineInput(input).encoding(type.parameters().get(CHARSET))
          : new BufferInput(input)
        ).content();
        payloads.add(parse(pl, type));
      }
    }
    return body.add(SerializerOptions.MEDIA_TYPE.name(), type.type());
  }

  /**
   * Returns all payloads.
   * @return payloads
   */
  Value payloads() {
    return payloads.value();
  }

  /**
   * Interprets a payload according to content type and returns a corresponding value.
   * @param payload payload
   * @param type media type
   * @return interpreted payload
   * @throws QueryException query exception
   */
  private Value parse(final byte[] payload, final MediaType type) throws QueryException {
    if(payload.length == 0) return Empty.SEQ;
    try {
      return value(new IOContent(payload), options, type);
    } catch(final IOException ex) {
      throw HC_PARSE_X.get(info, ex);
    }
  }

  /**
   * Extracts the parts from a multipart message.
   * @param sep separation boundary
   * @param parts list with all parts (may be {@code null})
   * @throws IOException I/O Exception
   * @throws QueryException query exception
   */
  private void extractParts(final byte[] sep, final ANodeList parts)
      throws IOException, QueryException {

    try {
      // RFC 1341: Preamble is to be ignored -> read till 1st boundary
      while(true) {
        final byte[] l = readLine();
        if(l == null) throw HC_REQ_X.get(info, "No body specified for http:part");
        if(eq(sep, l)) break;
      }
      // parse part
      while(extractPart(sep, concat(sep, DASHES), parts));
    } finally {
      input.close();
    }
  }

  /**
   * Extracts a part from a multipart message.
   * @param sep separation boundary
   * @param end closing boundary
   * @param parts list with all parts (may be {@code null})
   * @return success flag
   * @throws IOException I/O Exception
   * @throws QueryException query exception
   */
  private boolean extractPart(final byte[] sep, final byte[] end, final ANodeList parts)
      throws IOException, QueryException {

    // check if last line is reached
    final byte[] line = readLine();
    if(line == null || eq(line, end)) return false;

    // content type of part payload - if not defined by header 'Content-Type',
    // it is equal to 'text/plain' (RFC 1341)
    MediaType type = MediaType.TEXT_PLAIN;

    // extract headers
    boolean base64 = false;
    for(byte[] l = line; l != null && l.length > 0;) {
      final int pos = indexOf(l, ':');
      if(pos > 0) {
        final String key = string(substring(l, 0, pos)), val = string(trim(substring(l, pos + 1)));
        if(key.equalsIgnoreCase(CONTENT_TYPE)) {
          type = new MediaType(val);
        } else if(key.equalsIgnoreCase(CONTENT_TRANSFER_ENCODING)) {
          base64 = val.equals(BASE64);
        }
        if(!val.isEmpty() && parts != null)
          parts.add(new FElem(Q_HEADER).add(NAME, key).add(VALUE, val));
      }
      l = readLine();
    }
    if(parts != null) {
      parts.add(new FElem(Q_BODY).add(SerializerOptions.MEDIA_TYPE.name(), type.toString()));
    }

    byte[] payload = extractPart(sep, end, type.parameters().get(CHARSET));
    if(payloads != null) {
      if(base64) payload = Base64.decode(payload);
      payloads.add(parse(payload, type));
    }
    return true;
  }

  /**
   * Reads the next line of an HTTP multipart content.
   * @return line, or {@code null} if end of stream is reached
   * @throws IOException I/O Exception
   */
  private byte[] readLine() throws IOException {
    final ByteList bl = new ByteList();
    for(int b; (b = input.read()) != -1;) {
      // RFC 1341: a line ends with CRLF
      while(b == '\r') {
        b = input.read();
        if(b == '\n') return bl.finish();
        bl.add('\r');
        if(b == -1) return bl.finish();
      }
      bl.add(b);
    }
    return bl.isEmpty() ? null : bl.finish();
  }

  /**
   * Reads the next part of a payload.
   * @param sep separation boundary
   * @param end closing boundary
   * @param enc part content encoding
   * @return payload part content
   * @throws IOException I/O Exception
   */
  private byte[] extractPart(final byte[] sep, final byte[] end, final String enc)
      throws IOException {

    final ByteList bl = new ByteList();
    while(true) {
      final byte[] line = readLine();
      if(line == null || eq(line, sep)) break;

      // RFC 1341: Epilogue is to be ignored
      if(eq(line, end)) {
        while(readLine() != null);
        break;
      }
      if(!bl.isEmpty()) bl.add(CRLF);
      bl.add(line);
    }
    return new TextInput(new IOContent(bl.finish())).encoding(enc).content();
  }

  /**
   * Extracts the encapsulation boundary from the media type.
   * @param type media type
   * @return boundary or {@code null}
   * @throws QueryException query exception
   */
  private byte[] boundary(final MediaType type) throws QueryException {
    final String b = type.parameters().get(BOUNDARY);
    if(b == null) throw HC_REQ_X.get(info, "No separation boundary specified");
    // if the boundary is enclosed in quotes, strip them
    return token(b.charAt(0) == '"' ? b.substring(1, b.lastIndexOf('"')) : b);
  }

  /**
   * Returns a map with multipart form data.
   * @param type media type
   * @return map with file names and contents
   * @throws IOException I/O exception
   * @throws QueryException query exception
   */
  public HashMap<String, Value> multiForm(final MediaType type) throws IOException, QueryException {
    // parse boundary, create helper arrays
    final byte[] bound = concat(DASHES, boundary(type)), last = concat(bound, DASHES);

    final HashMap<String, Value> data = new HashMap<>();
    final ByteList cont = new ByteList();
    int lines = -1;
    String name = null, filename = null;
    for(byte[] line; (line = readLine()) != null;) {
      if(lines >= 0) {
        if(startsWith(line, bound)) {
          // get old value
          Value val = data.get(name);
          if(filename != null) {
            // assign file and contents, join multiple files
            final Map map = val instanceof Map ? (Map) val : Map.EMPTY;
            final Str file = Str.get(filename);
            final B64 contents = new B64(cont.next());
            final Value files = new ValueBuilder().add(map.get(file, info)).add(contents).value();
            val = map.put(file, files, info);
          } else {
            // assign string, join multiple strings
            final Str v = Str.get(cont.next());
            val = val == null ? v : new ValueBuilder().add(val).add(v).value();
          }

          if(!name.isEmpty()) data.put(name, val);
          lines = -1;
          if(eq(line, last)) break;
        } else {
          if(lines++ > 0) cont.add(CRLF);
          cont.add(line);
        }
      } else if(startsWith(line, CONTENT_DISPOSITION)) {
        // get key and file name
        name = contains(line, token(NAME + '=')) ?
          string(line).replaceAll("^.*?" + NAME + "=\"|\".*", "").replaceAll("\\[\\]", "") : null;
        filename = contains(line, token(FILENAME + '=')) ?
          string(line).replaceAll("^.*" + FILENAME + "=\"|\"$", "") : null;
      } else if(line.length == 0) {
        lines = 0;
      }
    }

    return data;
  }

  // STATIC METHODS =====================================================================

  /**
   * Returns an XQuery value for the specified content type.
   * @param input input source
   * @param options database options
   * @param type media type
   * @return xml parser
   * @throws IOException I/O exception
   * @throws QueryException query exception
   */
  public static Value value(final IO input, final MainOptions options, final MediaType type)
      throws IOException, QueryException {

    Value value = null;
    if(type.is(MediaType.APPLICATION_JSON)) {
      final JsonParserOptions opts = new JsonParserOptions(options.get(MainOptions.JSONPARSER));
      opts.assign(type);
      value = JsonConverter.get(opts).convert(input);
    } else if(type.is(MediaType.TEXT_CSV)) {
      final CsvParserOptions opts = new CsvParserOptions(options.get(MainOptions.CSVPARSER));
      opts.assign(type);
      value = CsvConverter.get(opts).convert(input);
    } else if(type.is(MediaType.TEXT_HTML)) {
      final HtmlOptions opts = new HtmlOptions(options.get(MainOptions.HTMLPARSER));
      opts.assign(type);
      value = new DBNode(new HtmlParser(input, options, opts));
    } else if(type.is(MediaType.APPLICATION_X_WWW_FORM_URLENCODED)) {
      final String enc = type.parameters().get(CHARSET);
      value = Str.get(URLDecoder.decode(string(input.read()), enc == null ? Strings.UTF8 : enc));
    } else if(type.isXML()) {
      value = new DBNode(input);
    } else if(type.isText()) {
      value = Str.get(new NewlineInput(input).content());
    } else if(type.isMultipart()) {
      try(final InputStream is = input.inputStream()) {
        final HttpPayload hp = new HttpPayload(is, true, null, options);
        hp.extractParts(concat(DASHES, hp.boundary(type)), null);
        value = hp.payloads();
      }
    }
    return value == null ? new B64(input.read()) : value;
  }
}
