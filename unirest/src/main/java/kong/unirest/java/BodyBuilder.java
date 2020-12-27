/**
 * The MIT License
 *
 * Copyright for portions of unirest-java are held by Kong Inc (c) 2013.
 *
 * Permission is hereby granted, free of charge, to any person obtaining
 * a copy of this software and associated documentation files (the
 * "Software"), to deal in the Software without restriction, including
 * without limitation the rights to use, copy, modify, merge, publish,
 * distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to
 * the following conditions:
 *
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 * EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
 * LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
 * WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package kong.unirest.java;

import kong.unirest.*;
import kong.unirest.java.multi.MediaType;
import kong.unirest.java.multi.MultipartBodyPublisher;
import org.apache.http.HttpHeaders;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.Charset;
import java.util.Optional;
import java.util.stream.Collectors;

public class BodyBuilder {
    public static final Charset ASCII = Charset.forName("US-ASCII");
    private final Config config;
    private final kong.unirest.HttpRequest request;

    public BodyBuilder(Config config, kong.unirest.HttpRequest request) {
        this.config = config;
        this.request = request;
    }

    public HttpRequest.BodyPublisher getBody() {
        Optional<Body> body = request.getBody();
        return body
                .map(o -> toPublisher(o))
                .orElseGet(HttpRequest.BodyPublishers::noBody);
    }

    private HttpRequest.BodyPublisher toPublisher(Body o) {
        if (o.isEntityBody()) {
            return mapToUniBody(o);
        } else {
            return mapToMultipart(o);
        }
    }

    private java.net.http.HttpRequest.BodyPublisher mapToMultipart(Body o) {
        try {
            if (o.multiParts().isEmpty()) {
                setContentAsFormEncoding(o);
                return HttpRequest.BodyPublishers.noBody();
            }
            if (!o.isMultiPart()) {
                setContentAsFormEncoding(o);
                return HttpRequest.BodyPublishers.ofString(
                        toFormParams(o)
                );
            }

            MultipartBodyPublisher.Builder builder = MultipartBodyPublisher.newBuilder();
            o.multiParts().forEach(part -> {
                setMultiPart(o, builder, part);
            });

            MultipartBodyPublisher build = builder.build();
            request.header("Content-Type", "multipart/form-data; boundary=" + build.boundary());
            return build;
        } catch (Exception e) {
            throw new UnirestException(e);
        }
    }

    private void setContentAsFormEncoding(Body o) {
        String content = "application/x-www-form-urlencoded";
        if(o.getCharset() != null){
            content = content + "; charset="+o.getCharset().toString();
        }
        request.header(HttpHeaders.CONTENT_TYPE, content);
    }

    private String toFormParams(Body o) {
       return o.multiParts()
                .stream()
                .filter(p -> p instanceof ParamPart)
                .map(p -> (ParamPart) p)
                .map(p -> toPair(p, o))
                .collect(Collectors.joining("&"));
    }

    private String toPair(ParamPart p, Body o) {
        try {
            String encoding = o.getCharset() == null ? "UTF-8" : o.getCharset().toString();
            return String.format("%s=%s", p.getName(), URLEncoder.encode(p.getValue(), encoding));
        } catch (UnsupportedEncodingException e) {
            throw new UnirestException(e);
        }
    }

    private boolean allPartsAreStrings(Body o) {
        return o.multiParts().stream().noneMatch(p -> p.isFile());
    }

    private void setMultiPart(Body o, MultipartBodyPublisher.Builder builder, BodyPart part) {
        if (part.isFile()) {
            if (part instanceof FilePart) {
                try {
                    builder.filePart(part.getName(),
                            ((File) part.getValue()).toPath(),
                            MediaType.parse(part.getContentType()));
                } catch (FileNotFoundException e) {
                    throw new UnirestException(e);
                }
            } else if (part instanceof InputStreamPart) {
                if (part.getFileName() != null) {
                    builder.formPart(part.getName(), standardizeName(part, o.getMode()),
                            MultipartBodyPublisher.ofMediaType(HttpRequest.BodyPublishers.ofInputStream(() -> (InputStream) part.getValue()),
                                    MediaType.parse(part.getContentType())));
                } else {
                    builder.formPart(part.getName(),
                            MultipartBodyPublisher.ofMediaType(HttpRequest.BodyPublishers.ofInputStream(() -> (InputStream) part.getValue()),
                                    MediaType.parse(part.getContentType())));
                }

            } else if (part instanceof ByteArrayPart) {
                builder.formPart(part.getName(),
                        standardizeName(part, o.getMode()),
                        MultipartBodyPublisher.ofMediaType(HttpRequest.BodyPublishers.ofByteArray((byte[]) part.getValue()),
                                MediaType.parse(part.getContentType())));
            }
        } else {
            builder.textPart(part.getName(), String.valueOf(part.getValue()));
        }
    }

    private String standardizeName(BodyPart part, MultipartMode mode) {
        if (mode.equals(MultipartMode.STRICT)) {
            return part.getFileName().chars()
                    .mapToObj(c -> {
                        if (!ASCII.newEncoder().canEncode((char) c)) {
                            return '?';
                        }
                        return Character.valueOf((char) c);
                    }).map(c -> c.toString())
                    .collect(Collectors.joining());
        }
        return part.getFileName();
    }

    private HttpRequest.BodyPublisher mapToUniBody(Body b) {
        BodyPart bodyPart = b.uniPart();
        if (bodyPart == null) {
            return HttpRequest.BodyPublishers.noBody();
        } else if (String.class.isAssignableFrom(bodyPart.getPartType())) {
            Charset charset = b.getCharset();
            if (charset == null) {
                return HttpRequest.BodyPublishers.ofString((String) bodyPart.getValue());
            }
            return HttpRequest.BodyPublishers.ofString((String) bodyPart.getValue(), charset);
        } else {
            return HttpRequest.BodyPublishers.ofByteArray((byte[]) bodyPart.getValue());
        }
    }
}
